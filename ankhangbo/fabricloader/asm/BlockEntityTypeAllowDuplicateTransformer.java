package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Leaf stores a single {@code BlockEntityType} per block ({@code Block.blockEntityType}) and its constructor throws
 * {@code IllegalStateException: Duplicate block entity type} when a block is listed by a second type. Vanilla and Fabric allow
 * that (Create addons such as Bits'n'Bobs register extra block entity types for blocks another type already covers).
 *
 * <p>That field is only read by {@code BlockEntityType#isValid}, which {@code BlockEntityTypeModdedValidBlockTransformer} already
 * replaces with a lookup in the real {@code validBlocks} set - so the check protects nothing here. Fix: turn
 * {@code if (validBlock.blockEntityType != null) throw ...} into a test that never fires, by replacing the loaded field value
 * with {@code null} right before the {@code IFNULL} (POP + ACONST_NULL: same stack shape, no new stack map frame, the throwing
 * branch stays in place as dead-but-verifiable code).
 * Disable with {@code -Dankhangbo.allowDuplicateBlockEntityType=false}.
 */
public final class BlockEntityTypeAllowDuplicateTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.BlockEntityTypeAllowDuplicateTransformer");
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.allowDuplicateBlockEntityType"));

	private static final String TARGET = "net/minecraft/world/level/block/entity/BlockEntityType";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!ENABLED || !TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			int patched = 0;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!m.name.equals("<init>")) continue;

				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() != Opcodes.GETFIELD || !(insn instanceof FieldInsnNode)) continue;

					FieldInsnNode f = (FieldInsnNode) insn;
					AbstractInsnNode next = insn.getNext();

					if (!f.name.equals("blockEntityType") || next == null || next.getOpcode() != Opcodes.IFNULL) continue;

					m.instructions.insert(insn, new InsnNode(Opcodes.ACONST_NULL));
					m.instructions.insert(insn, new InsnNode(Opcodes.POP));
					patched++;
					break;
				}
			}

			if (patched == 0) {
				LOGGER.warning(className + ": duplicate-type check not found - NOT patched (not a Leaf build?)");
				return null;
			}

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (a block may now belong to several block entity types, like on Fabric)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}
