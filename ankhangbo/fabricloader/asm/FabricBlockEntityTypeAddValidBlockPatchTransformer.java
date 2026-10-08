package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code fabric-object-builder-api-v1}'s {@code BlockEntityTypeMixin#addValidBlock(Block)}
 * also set {@code Block#blockEntityType}, not just add to the {@code validBlocks} Set - because
 * Leaf's real, patched {@code BlockEntityType#isValid(BlockState)} no longer checks that Set at
 * all.
 *
 * <p>Root cause, confirmed by diffing the plain-Fabric-mapped source (what the mod was compiled
 * against) with Leaf 26.1.2's real decompiled source:
 * <ul>
 *   <li>Fabric/vanilla: {@code isValid(state)} is {@code return this.validBlocks.contains(state
 *       .getBlock());} - a Set membership check. {@code addValidBlock}'s plain
 *       {@code validBlocks.add(block)} is entirely sufficient for this.</li>
 *   <li>Leaf: {@code isValid(state)} is {@code return state.getBlock().blockEntityType == this;} -
 *       a cached-field comparison, added purely as a performance optimization (avoids a Set
 *       hash lookup on every check). The field is set in exactly one place: {@code
 *       BlockEntityType}'s own constructor, which iterates whatever {@code Set<Block>} it was
 *       constructed with at THAT time and stamps each of those blocks' {@code blockEntityType}
 *       field.</li>
 * </ul>
 * A mod adding its own block to an EXISTING, already-constructed {@code BlockEntityType} (e.g. a
 * modded wood type's sign added to vanilla's shared {@code BlockEntityType.SIGN}, via Fabric's own
 * {@code addValidBlock}) necessarily runs long after that constructor already returned - so on
 * Leaf, the newly-added block's cached field is simply never set, even though it's genuinely in the
 * Set. {@code isValid()} then always returns false for it, and every attempt to construct that
 * block's block entity (e.g. opening/breaking a modded sign) fails
 * {@code BlockEntity#validateBlockState}'s check with {@code IllegalStateException("Invalid block
 * entity ...")} - and, downstream of that, Bukkit's own {@code CraftBlockStates} factory lookup
 * (keyed off the very same {@code BlockEntityType} identity) also never finds the SIGN-specific
 * factory for it, falling back to a plain {@code CraftBlockState} instead of {@code CraftSign} -
 * which is what throws the separate {@code ClassCastException} in {@code SignBlock#openTextEdit}.
 * Fixing this one gap fixes both symptoms at once, since they share this exact root cause.
 *
 * <p>The fix appends, right before {@code addValidBlock}'s existing {@code RETURN}:
 * {@code block.blockEntityType = this;} - the exact same assignment Leaf's own
 * {@code BlockEntityType} constructor makes for blocks present at construction time, just applied
 * retroactively for one added afterward. No new branches are introduced (a straight-line append
 * before an existing, single return), so this only needs {@code COMPUTE_MAXS}, not full stack-map
 * recomputation.
 */
public final class FabricBlockEntityTypeAddValidBlockPatchTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.FabricBlockEntityTypeAddValidBlockPatchTransformer");

	private static final String TARGET_CLASS = "net/fabricmc/fabric/mixin/object/builder/BlockEntityTypeMixin";
	private static final String TARGET_METHOD = "addValidBlock";
	private static final String TARGET_DESC = "(Lnet/minecraft/world/level/block/Block;)V";

	private static final String BLOCK_CLASS = "net/minecraft/world/level/block/Block";
	private static final String BLOCK_ENTITY_TYPE_FIELD = "blockEntityType";
	private static final String BLOCK_ENTITY_TYPE_DESC = "Lnet/minecraft/world/level/block/entity/BlockEntityType;";

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

				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn.getOpcode() != Opcodes.RETURN) {
						continue;
					}
					InsnList extra = new InsnList();
					extra.add(new VarInsnNode(Opcodes.ALOAD, 1)); // objectref: block param
					extra.add(new VarInsnNode(Opcodes.ALOAD, 0)); // value: this (a BlockEntityType, post-Mixin-merge)
					extra.add(new FieldInsnNode(Opcodes.PUTFIELD, BLOCK_CLASS, BLOCK_ENTITY_TYPE_FIELD,
							BLOCK_ENTITY_TYPE_DESC));
					method.instructions.insertBefore(insn, extra);
					patched = true;
				}
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " also set Block#blockEntityType, matching Leaf's cached-field isValid() check.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ "#" + TARGET_METHOD + " - leaving it unpatched", t);
			return null;
		}
	}
}