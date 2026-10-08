package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gives {@code net.minecraft.world.level.block.GrowingPlantHeadBlock#getMaxGrowthAge()} a
 * concrete default body instead of leaving it {@code abstract}.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source (compared directly
 * against the un-patched, Fabric-mapped copy of the same class that mods are compiled against):
 * <ol>
 *   <li>Vanilla/Paper's {@code GrowingPlantHeadBlock} hardcodes a growth ceiling of
 *       {@code MAX_AGE = 25} everywhere (e.g. {@code isRandomlyTicking}, {@code isMaxAge},
 *       {@code performBonemeal}).</li>
 *   <li>Leaf replaces every one of those hardcoded {@code 25} references with a call to a new
 *       method, {@code public abstract int getMaxGrowthAge();}, presumably so individual vanilla
 *       subclasses (kelp, cave vines, twisting/weeping vines, ...) can each report their own real
 *       growth ceiling instead of all sharing one constant. Every vanilla subclass was updated
 *       with a matching override, so vanilla itself never crashes.</li>
 *   <li>Fabric mods (e.g. BiomesOPlenty's plant blocks) are compiled against a Fabric-mapped
 *       snapshot of this class that does NOT have Leaf's patch, so their {@code GrowingPlantHeadBlock}
 *       subclasses were never written or compiled with a {@code getMaxGrowthAge()} override.</li>
 *   <li>At runtime the JVM loads Leaf's real class, sees the method is still abstract, and throws
 *       {@code AbstractMethodError: Missing implementation of resolved method
 *       'abstract int getMaxGrowthAge()'} the moment such a mod block is constructed/registered
 *       - before the server even finishes starting.</li>
 * </ol>
 *
 * <p>Fixing this once, here, is far more robust than patching every individual mod class that
 * happens to extend {@code GrowingPlantHeadBlock} (which would need to be redone for every new
 * mod/every new plant block): this transformer strips the {@code abstract} flag from
 * {@code getMaxGrowthAge()} and gives it a body equivalent to vanilla's pre-Leaf behaviour -
 * {@code return MAX_AGE;} (i.e. 25). Vanilla's own subclasses are untouched (they already declare
 * their own override, which simply takes priority over this inherited default), so this can only
 * ever change behaviour for third-party classes that were never going to implement Leaf's method
 * in the first place.
 */
public final class GrowingPlantHeadBlockMaxGrowthAgeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.GrowingPlantHeadBlockMaxGrowthAgeTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/level/block/GrowingPlantHeadBlock";
	private static final String TARGET_METHOD = "getMaxGrowthAge";
	private static final String TARGET_DESC = "()I";
	private static final String MAX_AGE_FIELD = "MAX_AGE";
	private static final String MAX_AGE_DESC = "I";

	/**
	 * A {@link ClassWriter} that resolves common superclasses using the *target* class's own
	 * classloader (the one passed into {@link ClassFileTransformer#transform}), not this agent's
	 * own classloader.
	 *
	 * <p>{@code ClassWriter}'s default {@code getCommonSuperClass} resolves types via
	 * {@code getClass().getClassLoader()} - i.e. whichever classloader loaded this transformer
	 * class itself (the javaagent's classloader). That classloader cannot see server/Bukkit/NMS
	 * classes at all, so every lookup for a Bukkit or NMS type throws, and a naive catch-and-return
	 * "java/lang/Object" fallback (as an earlier version of this class did) papers over the
	 * exception but returns the *wrong* common supertype. {@code COMPUTE_FRAMES} recomputes stack
	 * map frames for *every* method in the whole class being rewritten - not just the one method a
	 * transformer actually modifies - so a wrong answer here can silently corrupt the frames of a
	 * completely unrelated method elsewhere in the same class file. The JVM verifier then rejects
	 * that unrelated method the next time the class is loaded, with a confusing
	 * {@code VerifyError} that has nothing to do with what was actually changed.
	 *
	 * <p>Using the class's real classloader lets these lookups resolve correctly instead, so
	 * frames only fall back to Object when that's actually the correct common supertype.
	 */
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
				// Truly unresolvable (e.g. a type from a mod jar on yet another classloader) -
				// Object is the only safe answer left, same as ASM's own fallback behaviour.
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
				if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
					// Already concrete (e.g. this Leaf build doesn't have the patch at all) -
					// nothing to do.
					continue;
				}

				method.access &= ~Opcodes.ACC_ABSTRACT;

				InsnList body = new InsnList();
				body.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET_CLASS, MAX_AGE_FIELD, MAX_AGE_DESC));
				body.add(new InsnNode(Opcodes.IRETURN));
				method.instructions = body;
				// COMPUTE_MAXS (below) recalculates these regardless, but keep them sane.
				method.maxStack = 1;
				method.maxLocals = 1;

				patched = true;
			}

			if (!patched) {
				return null;
			}

			// The class itself stays abstract (it still has other abstract methods, e.g.
			// canGrowInto/getBlocksToGrowWhenBonemealed/codec) - only this one method changes.
			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Gave " + className + "#getMaxGrowthAge() a default body ("
					+ "return MAX_AGE) instead of leaving it abstract, for mod compatibility.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to de-abstract getMaxGrowthAge() - leaving it unpatched", t);
			return null;
		}
	}
}