package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes "Invalid block entity minecraft:sign ... got biomesoplenty:origin_oak_sign" and the
 * follow-up "CraftBlockState cannot be cast to CraftSign".
 *
 * <p>Works for BOTH server flavours by replacing the whole body of
 * {@code BlockEntityType#isValid(BlockState)}:
 * <ul>
 *   <li>Paper/vanilla: {@code validBlocks.contains(state.getBlock())}</li>
 *   <li>Leaf: {@code state.getBlock().blockEntityType == this} (never true for blocks added later
 *       to an existing type)</li>
 * </ul>
 * New body: {@code return BlockEntityTypeCompat.containsOrCompatible(this.validBlocks,
 * state.getBlock());} - the real validBlocks set (Fabric's addValidBlock adds to it) plus a
 * same-class fallback. Straight-line code, no branches, so only COMPUTE_MAXS is needed.
 */
public final class BlockEntityTypeModdedValidBlockTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.BlockEntityTypeModdedValidBlockTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/level/block/entity/BlockEntityType";
	private static final String HELPER = "ankhangbo/fabricloader/compat/BlockEntityTypeCompat";
	private static final String BLOCK_DESC = "()Lnet/minecraft/world/level/block/Block;";

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

			String setField = null;
			for (FieldNode f : (List<FieldNode>) classNode.fields) {
				if (f.desc.equals("Ljava/util/Set;") && (Opcodes.ACC_STATIC & f.access) == 0) {
					if (f.name.equals("validBlocks")) {
						setField = f.name;
						break;
					}
					if (setField == null) {
						setField = f.name;
					}
				}
			}
			if (setField == null) {
				LOGGER.warning("BlockEntityType has no Set field - NOT patched");
				return null;
			}

			boolean patched = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals("isValid")
						|| !method.desc.equals("(Lnet/minecraft/world/level/block/state/BlockState;)Z")) {
					continue;
				}
				// Reuse the exact owner/name the original code used to get the Block from the state.
				MethodInsnNode getBlock = null;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).desc.equals(BLOCK_DESC)
							&& !((MethodInsnNode) insn).owner.equals("java/lang/Object")) {
						getBlock = (MethodInsnNode) insn;
						break;
					}
				}
				if (getBlock == null) {
					getBlock = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
							"net/minecraft/world/level/block/state/BlockState", "getBlock", BLOCK_DESC, false);
				}

				InsnList body = new InsnList();
				body.add(new VarInsnNode(Opcodes.ALOAD, 0));
				body.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, setField, "Ljava/util/Set;"));
				body.add(new VarInsnNode(Opcodes.ALOAD, 1));
				body.add(new MethodInsnNode(getBlock.getOpcode(), getBlock.owner, getBlock.name,
						getBlock.desc, getBlock.itf));
				body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "containsOrCompatible",
						"(Ljava/util/Set;Ljava/lang/Object;)Z", false));
				body.add(new InsnNode(Opcodes.IRETURN));

				method.instructions.clear();
				method.instructions.add(body);
				method.tryCatchBlocks = new ArrayList<>();
				method.localVariables = null;
				method.visibleLocalVariableAnnotations = null;
				method.invisibleLocalVariableAnnotations = null;
				patched = true;
			}
			if (!patched) {
				LOGGER.warning("BlockEntityType#isValid(BlockState) not found - NOT patched");
				return null;
			}
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);
			LOGGER.info("PATCHED BlockEntityType#isValid (set field '" + setField + "')");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className + " - leaving it unpatched", t);
			return null;
		}
	}
}