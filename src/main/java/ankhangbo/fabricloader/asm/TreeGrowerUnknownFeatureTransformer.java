package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnList;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stops {@code net.minecraft.world.level.block.grower.TreeGrower#setTreeType(Holder)} from
 * throwing {@code IllegalArgumentException("Unknown tree generator ...")} when a sapling's
 * configured tree feature is a modded one, and instead falls back to a generic
 * {@code org.bukkit.TreeType.TREE}.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source:
 * <ol>
 *   <li>{@code TreeGrower.growTree(...)} (reached from bonemealing a sapling, e.g. via
 *       {@code BoneMealItem.growCrop}) calls {@code this.setTreeType(featureHolder)} *before*
 *       actually placing the tree feature in the world, purely so Paper/CraftBukkit can report a
 *       matching {@code org.bukkit.TreeType} on the {@code StructureGrowEvent} it fires.</li>
 *   <li>{@code setTreeType} is a long chain of {@code feature.is(TreeFeatures.XYZ)} checks against
 *       every *vanilla* tree configured-feature. A modded sapling (e.g. BiomesOPlenty's
 *       {@code SaplingBlockBOP}, which reuses vanilla's {@code performBonemeal}/{@code TreeGrower}
 *       machinery with its own configured feature such as
 *       {@code biomesoplenty:tall_umbran_tree}) matches none of them, so execution falls into the
 *       final {@code else} branch, which throws instead of assigning a fallback value.</li>
 *   <li>Because this throw happens *before* {@code feature.place(...)}, the tree never actually
 *       grows - the whole bonemeal interaction is aborted (caught and "suppressed" further up by
 *       the packet handler), silently wasting the player's bone meal on every single modded
 *       sapling of this kind, forever.</li>
 * </ol>
 *
 * <p>The fix mirrors what vanilla already does for its own generic case (plain oak reports
 * {@code TreeType.TREE}): when none of the known vanilla features match, this transformer makes
 * the method assign {@code TreeType.TREE} instead of throwing, so {@code SaplingBlock.setTreeTypeRT}
 * still gets a valid value and {@code growTree} can proceed to actually place the modded tree.
 * The Bukkit {@code StructureGrowEvent} plugins see for such a tree will simply report the generic
 * {@code TreeType.TREE} instead of a type Bukkit has no constant for - the tree itself still grows
 * correctly, since the real placement logic uses the NMS configured feature, not the Bukkit enum.
 */
public final class TreeGrowerUnknownFeatureTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.TreeGrowerUnknownFeatureTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/level/block/grower/TreeGrower";
	private static final String TARGET_METHOD = "setTreeType";
	private static final String TARGET_DESC = "(Lnet/minecraft/core/Holder;)V";
	private static final String EXCEPTION_TYPE = "java/lang/IllegalArgumentException";
	private static final String TREE_TYPE_OWNER = "org/bukkit/TreeType";
	private static final String TREE_TYPE_DESC = "Lorg/bukkit/TreeType;";

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

				// Every earlier branch does "GETSTATIC org/bukkit/TreeType.XYZ ; ASTORE n" -
				// find which local variable slot that stores into, so we store to the same one.
				int treeTypeVarIndex = -1;
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof FieldInsnNode field
							&& field.getOpcode() == Opcodes.GETSTATIC
							&& field.owner.equals(TREE_TYPE_OWNER)
							&& insn.getNext() instanceof VarInsnNode next
							&& next.getOpcode() == Opcodes.ASTORE) {
						treeTypeVarIndex = next.var;
						break;
					}
				}
				if (treeTypeVarIndex < 0) {
					LOGGER.warning("Could not locate the treeType local variable in "
							+ className + "#" + TARGET_METHOD + " - leaving it unpatched");
					continue;
				}

				// Find "NEW java/lang/IllegalArgumentException ... ATHROW" (the final else branch)
				// and replace that whole range with "treeType = TreeType.TREE;".
				AbstractInsnNode newInsn = null;
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof TypeInsnNode type
							&& type.getOpcode() == Opcodes.NEW
							&& type.desc.equals(EXCEPTION_TYPE)) {
						newInsn = insn;
						break;
					}
				}
				AbstractInsnNode athrow = null;
				if (newInsn != null) {
					for (AbstractInsnNode insn = newInsn; insn != null; insn = insn.getNext()) {
						if (insn.getOpcode() == Opcodes.ATHROW) {
							athrow = insn;
							break;
						}
					}
				}
				if (newInsn == null || athrow == null) {
					LOGGER.warning("Could not locate the 'Unknown tree generator' throw in "
							+ className + "#" + TARGET_METHOD + " - leaving it unpatched");
					continue;
				}

				InsnList replacement = new InsnList();
				replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, TREE_TYPE_OWNER, "TREE", TREE_TYPE_DESC));
				replacement.add(new VarInsnNode(Opcodes.ASTORE, treeTypeVarIndex));
				method.instructions.insertBefore(newInsn, replacement);

				AbstractInsnNode cursor = newInsn;
				while (cursor != null) {
					AbstractInsnNode next = cursor.getNext();
					method.instructions.remove(cursor);
					if (cursor == athrow) {
						break;
					}
					cursor = next;
				}

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " fall back to TreeType.TREE for unrecognized (modded) tree features "
					+ "instead of throwing.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate unknown tree generators - leaving it unpatched", t);
			return null;
		}
	}
}