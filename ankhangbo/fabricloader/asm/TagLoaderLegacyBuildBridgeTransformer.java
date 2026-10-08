package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adds back the old, single-argument {@code TagLoader#build(Map)} as a compatibility bridge that
 * simply calls Paper/Leaf's current two-argument {@code build(Map, TagEventConfig)} with
 * {@code null} for the event config, for mods (e.g. Farmer's Delight Refabricated's
 * {@code RefabricatedEarlyTagUtils}) that still call the old, vanilla one-argument signature
 * directly and crash with {@code NoSuchMethodError} otherwise.
 *
 * <p>Root cause: Paper/Leaf added an {@code io.papermc.paper.tag.TagEventConfig} parameter to
 * {@code TagLoader#build(...)} (for its own tag-load-event system), replacing the original vanilla
 * single-argument method entirely rather than overloading it. Any mod compiled against vanilla's
 * mapped API and calling {@code loader.build(builders)} directly (bypassing the normal
 * "loadAndBuild" helper, presumably because it needs the intermediate {@code Map} for its own
 * purposes before the tags are fully resolved) hits a hard {@code NoSuchMethodError} the moment
 * that call executes, since the one-argument overload no longer exists on this build at all.
 *
 * <p>{@code null} is a safe substitute for the missing {@code TagEventConfig}: it's an
 * {@code @Nullable} parameter (Paper's own {@code load}-and-{@code build} convenience methods pass
 * a real one only when firing tag-load events for a specific registry reload; a mod calling the raw
 * {@code build(Map)} directly, outside that normal reload flow, was never going to participate in
 * that event system anyway).
 */
public final class TagLoaderLegacyBuildBridgeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.TagLoaderLegacyBuildBridgeTransformer");

	private static final String TARGET_CLASS = "net/minecraft/tags/TagLoader";
	private static final String METHOD_NAME = "build";
	private static final String OLD_DESC = "(Ljava/util/Map;)Ljava/util/Map;";
	private static final String NEW_DESC = "(Ljava/util/Map;Lio/papermc/paper/tag/TagEventConfig;)Ljava/util/Map;";

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

			boolean hasOld = false;
			boolean hasNew = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(METHOD_NAME)) {
					continue;
				}
				if (method.desc.equals(OLD_DESC)) {
					hasOld = true;
				}
				if (method.desc.equals(NEW_DESC)) {
					hasNew = true;
				}
			}

			if (hasOld || !hasNew) {
				// Either the old overload already exists (nothing to do) or the new one we'd
				// delegate to doesn't (this Leaf build changed shape again) - don't guess further.
				return null;
			}

			MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, OLD_DESC, null, null);
			bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
			bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // builders
			bridge.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // eventConfig = null
			bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_CLASS, METHOD_NAME, NEW_DESC, false));
			bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
			bridge.maxStack = 3;
			bridge.maxLocals = 2;
			classNode.methods.add(bridge);

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Added back " + className + "#build(Map) as a compatibility bridge "
					+ "to build(Map, TagEventConfig), for mods still calling the old vanilla signature.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to add the legacy TagLoader#build(Map) bridge - "
					+ "leaving it unpatched", t);
			return null;
		}
	}
}