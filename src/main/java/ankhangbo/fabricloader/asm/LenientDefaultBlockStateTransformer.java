package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Strips the {@code Preconditions.checkState(blockEntity == null, ...)} call out of
 * {@code org.bukkit.craftbukkit.block.CraftBlockStates$1#createBlockState} (the anonymous
 * {@code DEFAULT_FACTORY} CraftBukkit falls back to whenever it can't find a more specific
 * factory for a block).
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source:
 * <ol>
 *   <li>{@code CraftBlockStates.getBlockState(World, BlockPos, BlockState, BlockEntity)} looks up
 *       {@code Material material = CraftBlockType.minecraftToBukkit(state.getBlock())} - for a
 *       modded block (e.g. ToughAsNails' Thermoregulator), that's a plain
 *       {@code Map<Block,Material>.get(block)} lookup that returns {@code null}, because Bukkit's
 *       {@code Material} enum has never heard of a block a mod added (this is the exact same root
 *       cause {@code NullSafeMaterial}/{@code NullSafeMaterialTransformer} were originally written
 *       for, just showing up at a different call site).</li>
 *   <li>Since the block DOES have a block entity (that's the whole point of a Thermoregulator),
 *       but its {@code BlockEntityType} is equally unknown to CraftBukkit's own per-type factory
 *       map, resolution falls through to {@code DEFAULT_FACTORY}.</li>
 *   <li>{@code DEFAULT_FACTORY#createBlockState} asserts {@code blockEntity == null} - a
 *       reasonable assumption for ordinary vanilla "block with no block entity, and no
 *       Material" cases, but never true for a modded block that has one, so it throws
 *       {@code IllegalStateException("Unexpected BlockState for " + material)} (printed as
 *       "...for null", since material actually is null) - and this fires on essentially every
 *       interaction that needs to build a Bukkit {@code BlockState} for the block, including
 *       simply breaking it.</li>
 * </ol>
 *
 * <p>Removing the assertion (rather than trying to satisfy it) is the right fix here: the method
 * still returns a plain, generic {@code CraftBlockState} either way - CraftBukkit has no idea what
 * type of block entity this is regardless, so the Bukkit-facing view was always going to be
 * generic; the only thing the precondition did was crash instead of quietly returning that generic
 * view. This is scoped as tightly as possible - one exact class, one exact method, one exact
 * three-argument {@code Preconditions.checkState(boolean, String, Object)} call site - specifically
 * so it can never affect any other {@code checkState} call anywhere else on the server (Guava's
 * checkState is used constantly for completely unrelated invariants that must keep failing loudly
 * when violated).
 */
public final class LenientDefaultBlockStateTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.LenientDefaultBlockStateTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/block/CraftBlockStates$1";
	private static final String TARGET_METHOD = "createBlockState";

	private static final String CHECK_OWNER = "com/google/common/base/Preconditions";
	private static final String CHECK_NAME = "checkState";
	private static final String CHECK_DESC = "(ZLjava/lang/String;Ljava/lang/Object;)V";

	private static final class SafeClassWriter extends ClassWriter {
		SafeClassWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			try {
				return super.getCommonSuperClass(type1, type2);
			} catch (TypeNotPresentException | LinkageError e) {
				return "java/lang/Object";
			}
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

			boolean patchedAny = false;
			for (MethodNode method : (java.util.List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD)) {
					continue;
				}
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
						continue;
					}
					MethodInsnNode call = (MethodInsnNode) insn;
					if (call.owner.equals(CHECK_OWNER) && call.name.equals(CHECK_NAME) && call.desc.equals(CHECK_DESC)) {
						// Three category-1 arguments (boolean, String, Object) were already
						// pushed for this call - discard them instead of invoking.
						InsnList replacement = new InsnList();
						replacement.add(new InsnNode(Opcodes.POP));
						replacement.add(new InsnNode(Opcodes.POP));
						replacement.add(new InsnNode(Opcodes.POP));
						method.instructions.insertBefore(call, replacement);
						method.instructions.remove(call);
						patchedAny = true;
					}
				}
			}

			if (!patchedAny) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Removed the blockEntity==null precondition from " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate modded block entities - leaving it unpatched", t);
			return null;
		}
	}
}
