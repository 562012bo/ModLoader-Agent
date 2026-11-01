package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Logger;

/**
 * Opening a modded menu that stores no player and has no player-inventory slots (Waystones' WaystoneSelectionMenu) makes
 * {@code GenericMenuBukkitView} unable to build its InventoryView -> {@code getBukkitView()} returns null ->
 * {@code NullPointerException: "destination" is null} in {@code AbstractContainerMenu.transferTo}. transferTo(other, player) itself
 * receives the opening player, so this publishes it ({@code GenericMenuBukkitView.setOpeningPlayer}) for the duration of the call.
 * Disable with {@code -Dankhangbo.transferToPlayer=false}.
 */
public final class ContainerTransferToPlayerTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.ContainerTransferToPlayerTransformer");
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.transferToPlayer"));

	private static final String TARGET = "net/minecraft/world/inventory/AbstractContainerMenu";
	private static final String HELPER = "ankhangbo/fabricloader/compat/GenericMenuBukkitView";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!ENABLED || !TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!m.name.equals("transferTo") || !m.desc.startsWith("(L" + TARGET + ";L") || !m.desc.endsWith(";)V")) continue;

				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.RETURN) {
						m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "clearOpeningPlayer", "()V", false));
					}
				}

				InsnList set = new InsnList();
				set.add(new VarInsnNode(Opcodes.ALOAD, 2));
				set.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "setOpeningPlayer", "(Ljava/lang/Object;)V", false));
				m.instructions.insert(set);

				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				LOGGER.info("PATCHED " + className + "#transferTo (publishes the opening player for modded menus without one, e.g. Waystones)");
				return w.toByteArray();
			}
		} catch (Throwable t) {
			LOGGER.warning("Could not patch " + className + "#transferTo: " + t);
		}

		return null;
	}
}