package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes Farmer's Delight Refabricated's "early tag" system work on this Leaf build, by setting up
 * {@code vectorwing.farmersdelight.refabricated.RefabricatedEarlyTagUtils}'s state directly, instead
 * of relying on that mod's own (broken, on this build) mixin.
 *
 * <p>Root cause, confirmed against Farmer's Delight Refabricated's real source
 * (github.com/MehVahdJukaar/FarmersDelightRefabricated, branch fabric/latest/26.2) and Leaf 26.2's
 * real decompiled source:
 * <ol>
 *   <li>{@code RefabricatedEarlyTagUtils} keeps a static {@code ResourceManager resourceManager}
 *       field, used to load a couple of tags "early" (before vanilla's normal tag loading) so a
 *       couple of loot tables can be conditionally modified. It's set via
 *       {@code setLootTableResourceManager(...)} and cleared via
 *       {@code resetEarlyTagCollections()}.</li>
 *   <li>Those two methods are only ever called from Farmer's Delight's own
 *       {@code ReloadableServerRegistriesMixin}, which injects into two of
 *       {@code ReloadableServerRegistries.reload(...)}'s synthetic lambda methods,
 *       {@code lambda$reload$0} and {@code lambda$reload$1} - identified purely by their
 *       compiler-assigned name, which depends on how many lambdas appear, and in what order, inside
 *       {@code reload(...)}'s real compiled body.</li>
 *   <li>Leaf's real {@code reload(...)} declares a {@code Conversions conversions} local (a
 *       Paper/Leaf-specific addition, not present in vanilla) that gets captured by the very lambda
 *       Farmer's Delight targets - shifting that lambda's real synthetic parameter list to five
 *       parameters (adding {@code Conversions}) where Farmer's Delight's mixin handler declares
 *       only four. That mismatch keeps the injection from applying, so
 *       {@code setLootTableResourceManager(...)} is simply never called, and
 *       {@code resourceManager} stays {@code null} forever - hence the
 *       {@code NullPointerException} the moment any code needs it (e.g. checking whether a block is
 *       tagged {@code drops_cake_slice} while modifying a loot table).</li>
 * </ol>
 *
 * <p>Rather than trying to match Leaf's exact (and fork-specific, liable to change again) lambda
 * shape, this transformer calls the same two methods directly from a location that can't be
 * affected by how many lambdas are inside {@code reload(...)} or what they capture: the very start
 * of {@code reload(...)} itself, which is a plain, stably-named public method. Every call to
 * {@code reload(...)} (server start, {@code /reload}, etc.) now resets the early-tag caches and
 * (re)points {@code resourceManager} at the fresh manager being used for that reload, before any of
 * the actual reload work begins - functionally equivalent to what Farmer's Delight's own mixin
 * intended, just anchored to a location Mixin doesn't need to guess the shape of.
 */
public final class ReloadableServerRegistriesRefabricatedFixTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ReloadableServerRegistriesRefabricatedFixTransformer");

	private static final String TARGET_CLASS = "net/minecraft/server/ReloadableServerRegistries";
	private static final String TARGET_METHOD = "reload";
	private static final String TARGET_DESC = "(Lnet/minecraft/core/LayeredRegistryAccess;"
			+ "Ljava/util/List;"
			+ "Lnet/minecraft/server/packs/resources/ResourceManager;"
			+ "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";

	// manager is the 3rd parameter (index 2): context=0, updatedContextTags=1, manager=2, executor=3
	// - all reference types, one slot each, static method.
	private static final int MANAGER_PARAM_INDEX = 2;

	private static final String FDRF_UTILS_CLASS = "vectorwing/farmersdelight/refabricated/RefabricatedEarlyTagUtils";
	private static final String RESOURCE_MANAGER_DESC = "Lnet/minecraft/server/packs/resources/ResourceManager;";

	private static final class SafeClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;

		SafeClassWriter(int flags, ClassLoader targetLoader) {
			super(flags);
			this.targetLoader = targetLoader;
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			Class<?> class1;
			Class<?> class2;
			try {
				class1 = Class.forName(type1.replace('/', '.'), false, targetLoader);
				class2 = Class.forName(type2.replace('/', '.'), false, targetLoader);
			} catch (Throwable t) {
				return "java/lang/Object";
			}
			if (class1.isAssignableFrom(class2)) {
				return type1;
			}
			if (class2.isAssignableFrom(class1)) {
				return type2;
			}
			if (class1.isInterface() || class2.isInterface()) {
				return "java/lang/Object";
			}
			do {
				class1 = class1.getSuperclass();
			} while (!class1.isAssignableFrom(class2));
			return class1.getName().replace('.', '/');
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET_CLASS.equals(className)) {
			return null;
		}

		// If Farmer's Delight Refabricated isn't installed, RefabricatedEarlyTagUtils won't exist -
		// skip quietly rather than injecting a call that would throw NoClassDefFoundError.
		try {
			Class.forName(FDRF_UTILS_CLASS.replace('/', '.'), false, loader);
		} catch (Throwable t) {
			return null;
		}

		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(classfileBuffer).accept(classNode, 0);

			boolean patched = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD) || !method.desc.equals(TARGET_DESC)) {
					continue;
				}

				InsnList prefix = new InsnList();
				prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FDRF_UTILS_CLASS,
						"resetEarlyTagCollections", "()V", false));
				prefix.add(new VarInsnNode(Opcodes.ALOAD, MANAGER_PARAM_INDEX));
				prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FDRF_UTILS_CLASS,
						"setLootTableResourceManager", "(" + RESOURCE_MANAGER_DESC + ")V", false));
				method.instructions.insert(prefix);

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " set up Farmer's Delight Refabricated's early-tag ResourceManager directly, "
					+ "since that mod's own mixin doesn't apply against this Leaf build.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to wire up RefabricatedEarlyTagUtils in " + className
					+ " - leaving it unpatched", t);
			return null;
		}
	}
}