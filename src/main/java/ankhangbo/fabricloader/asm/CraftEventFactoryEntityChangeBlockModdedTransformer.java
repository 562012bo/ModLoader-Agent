package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code CraftEventFactory.callEntityChangeBlockEvent(Entity, BlockPos, BlockState, boolean)} builds an
 * {@code EntityChangeBlockEvent} whose {@code getTo()} is the Bukkit Material of the new block state. For a MODDED block that is
 * null, and plugins crash handling the event (CoreProtect "newtype is null"/"type is null", WorldGuard PlaceBlockEvent
 * checkNotNull) - on every falling block of a modded block.
 *
 * <p>Fix: if the new block has no Bukkit Material, do not fire the event (plugins cannot represent it anyway) and return
 * {@code !cancelled}, i.e. what the method returns when no plugin cancels. Hand-written stack map frame (F_SAME = the method's
 * initial frame, the inserted branch is the first instruction), same technique as ChunkHolderMoonriseBridgeTransformer.
 */
public final class CraftEventFactoryEntityChangeBlockModdedTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.CraftEventFactoryEntityChangeBlockModdedTransformer");
	private static final String TARGET = "org/bukkit/craftbukkit/event/CraftEventFactory";
	private static final String DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);
			boolean patched = false;

			for (MethodNode m : cn.methods) {
				if (!m.name.equals("callEntityChangeBlockEvent") || !m.desc.equals(DESC) || (m.access & Opcodes.ACC_STATIC) == 0) continue;

				LabelNode go = new LabelNode();
				InsnList pre = new InsnList();
				pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
				pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState", "getBlock",
						"()Lnet/minecraft/world/level/block/Block;", false));
				pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/bukkit/craftbukkit/util/CraftMagicNumbers", "getMaterial",
						"(Lnet/minecraft/world/level/block/Block;)Lorg/bukkit/Material;", false));
				pre.add(new JumpInsnNode(Opcodes.IFNONNULL, go));
				pre.add(new VarInsnNode(Opcodes.ILOAD, 3));
				pre.add(new InsnNode(Opcodes.ICONST_1));
				pre.add(new InsnNode(Opcodes.IXOR));
				pre.add(new InsnNode(Opcodes.IRETURN));
				pre.add(go);
				pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
				m.instructions.insert(pre);
				patched = true;
			}

			if (!patched) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (callEntityChangeBlockEvent skips blocks with no Bukkit Material: modded falling blocks)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}