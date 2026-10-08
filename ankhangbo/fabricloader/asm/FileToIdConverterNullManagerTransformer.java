package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code net.minecraft.resources.FileToIdConverter#listMatchingResourceStacks(ResourceManager)}
 * return an empty map instead of throwing a {@code NullPointerException} when called with a
 * {@code null} manager - which, unlike every other null-safety fix in this project, is a MOD bug
 * rather than a "mod content is unmapped in Bukkit" issue, but one severe enough (it aborts server
 * boot entirely, not just one tick) to be worth tolerating anyway.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source: this method's whole
 * body is {@code return manager.listResourceStacks(this.prefix, this::extensionMatches);} - the
 * {@code manager} parameter is whatever the CALLER passed in, not something vanilla computes
 * internally. A real crash traced this back to Farmer's Delight Refabricated's own
 * {@code RefabricatedEarlyTagUtils.loadTagEarly()}, which calls {@code TagLoader#load(ResourceManager)}
 * with a {@code null} manager - almost certainly a {@code ResourceManager} reference the mod cached
 * from some Fabric API resource-reload/lifecycle hook that either hasn't fired yet, or doesn't fire
 * the way the mod expects, under this Paper-hybrid loader's boot sequence. That's a real,
 * independent problem worth investigating on its own, but this specific crash happened inside a
 * shared {@code CompletableFuture} in the server's own loot-table/registry reload pipeline
 * ({@code ReloadableServerRegistries.modifyLootTable}), so the resulting
 * {@code ExecutionException} propagated all the way up and killed the ENTIRE server boot - not just
 * this one mod's feature. Treating "no resource manager yet" as "no matching resources yet" (an
 * empty result, which is what a genuinely-early call in normal vanilla startup would also see) is a
 * safe, conservative interpretation that keeps the rest of the server's boot process - and every
 * other mod's loot table modifications in the same batch - alive instead of taking all of them down
 * over one mod's premature lookup.
 */
public final class FileToIdConverterNullManagerTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.FileToIdConverterNullManagerTransformer");

	private static final String TARGET_CLASS = "net/minecraft/resources/FileToIdConverter";
	private static final String TARGET_METHOD = "listMatchingResourceStacks";
	private static final String TARGET_DESC =
			"(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;";

	private static final int MANAGER_PARAM_INDEX = 1; // this=0, manager=1

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

		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(classfileBuffer).accept(classNode, 0);

			boolean patched = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD) || !method.desc.equals(TARGET_DESC)) {
					continue;
				}
				if (method.instructions.size() == 0) {
					continue;
				}

				LabelNode notNull = new LabelNode();
				InsnList guard = new InsnList();
				guard.add(new VarInsnNode(Opcodes.ALOAD, MANAGER_PARAM_INDEX));
				guard.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
				guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyMap",
						"()Ljava/util/Map;", false));
				guard.add(new InsnNode(Opcodes.ARETURN));
				guard.add(notNull);

				method.instructions.insertBefore(method.instructions.getFirst(), guard);
				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " tolerate a null ResourceManager instead of aborting server boot.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate a null ResourceManager - leaving it unpatched", t);
			return null;
		}
	}
}