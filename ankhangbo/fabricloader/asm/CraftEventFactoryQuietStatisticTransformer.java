package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes {@code CraftEventFactory#handleStatisticsIncrease}'s {@code System.err.println("Unhandled statistic: ...")}
 * through {@code StatisticLog.println}, which keeps printing for statistics in the {@code minecraft} namespace (a real
 * Paper gap) and drops it for a mod's TYPED statistics of its own blocks/items/entities (no Bukkit Material/EntityType exists
 * for them, so there is nothing to report). Untyped mod statistics no longer get here at all: they are registered with
 * Bukkit by {@code StatisticBridge}. Same stack effect as the original call, so no frames change.
 */
public final class CraftEventFactoryQuietStatisticTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftEventFactoryQuietStatisticTransformer");

	private static final String TARGET = "org/bukkit/craftbukkit/event/CraftEventFactory";
	private static final String TEXT = "Unhandled statistic";

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
				if (!m.name.equals("handleStatisticsIncrease")) {
					continue;
				}
				boolean seenText = false;
				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String
							&& ((String) ((LdcInsnNode) insn).cst).contains(TEXT)) {
						seenText = true;
					} else if (insn instanceof InvokeDynamicInsnNode) {
						for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
							if (arg instanceof String && ((String) arg).contains(TEXT)) {
								seenText = true;
							}
						}
					} else if (seenText && insn instanceof MethodInsnNode) {
						MethodInsnNode mi = (MethodInsnNode) insn;
						if (mi.owner.equals("java/io/PrintStream") && mi.name.equals("println")
								&& mi.desc.equals("(Ljava/lang/String;)V")) {
							m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
									"ankhangbo/fabricloader/compat/StatisticLog", "println", "(Ljava/io/PrintStream;Ljava/lang/String;)V", false));
							seenText = false;
							patched = true;
						}
					}
				}
			}
			if (!patched) {
				LOGGER.warning("'Unhandled statistic' println not found in CraftEventFactory - NOT patched");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED CraftEventFactory: 'Unhandled statistic' is only reported for minecraft: statistics");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}