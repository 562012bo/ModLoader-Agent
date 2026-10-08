package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes the startup crash
 * "NullPointerException: CraftRegistry.getMinecraftRegistry() is null" (PaperAdventure.lambda$static$1)
 * raised by mods that print a message from Fabric's SERVER_STARTING event (e.g. LuckPerms).
 *
 * <p>Fabric fires SERVER_STARTING before Paper's CraftServer exists, and CraftServer's constructor
 * is what calls {@code CraftRegistry.setMinecraftRegistry(...)}. Until then Paper's
 * {@code MinecraftServer#sendSystemMessage} -> {@code PaperAdventure.asAdventure} dereferences a
 * null registry.
 *
 * <p>This rewrites {@code CraftRegistry.getMinecraftRegistry()} to
 * {@code registry != null ? registry : RegistryAccess.EMPTY}. EMPTY is enough to serialise plain
 * chat/log components; once CraftServer has set the real registry the getter returns that, so
 * nothing changes afterwards. The stack-map frame is written by hand so no class has to be loaded
 * while CraftRegistry itself is being defined.
 */
public final class CraftRegistryNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftRegistryNullSafeTransformer");

	private static final String TARGET = "org/bukkit/craftbukkit/CraftRegistry";
	private static final String REGISTRY_ACCESS = "net/minecraft/core/RegistryAccess";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET.equals(className)) {
			return null;
		}
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			boolean patched = false;
			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!m.name.equals("getMinecraftRegistry") || !m.desc.equals("()L" + REGISTRY_ACCESS + ";")
						|| (m.access & Opcodes.ACC_STATIC) == 0) {
					continue;
				}
				FieldInsnNode field = null;
				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.GETSTATIC) {
						field = (FieldInsnNode) insn;
						break;
					}
				}
				if (field == null) {
					continue;
				}
				LabelNode done = new LabelNode();
				InsnList b = new InsnList();
				b.add(new FieldInsnNode(Opcodes.GETSTATIC, field.owner, field.name, field.desc));
				b.add(new InsnNode(Opcodes.DUP));
				b.add(new JumpInsnNode(Opcodes.IFNONNULL, done));
				b.add(new InsnNode(Opcodes.POP));
				b.add(new FieldInsnNode(Opcodes.GETSTATIC, REGISTRY_ACCESS, "EMPTY",
						"L" + REGISTRY_ACCESS + "$Frozen;"));
				b.add(done);
				b.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { REGISTRY_ACCESS }));
				b.add(new InsnNode(Opcodes.ARETURN));

				m.instructions = b;
				m.tryCatchBlocks = new ArrayList<>();
				m.localVariables = null;
				patched = true;
			}
			if (!patched) {
				LOGGER.warning("CraftRegistry.getMinecraftRegistry() not found - NOT patched");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED CraftRegistry.getMinecraftRegistry(): falls back to RegistryAccess.EMPTY before CraftServer exists");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}