package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Silences Paper's "Unknown target reason, please report on the issue tracker" warning (+ stack
 * trace) that every modded mob calling plain {@code Mob#setTarget(LivingEntity)} triggers
 * (GuardVillagers, zombies from variantsandventures, ...). It is harmless: Paper just doesn't
 * know the reason and uses UNKNOWN.
 *
 * <p>Replaces the {@code Logger.log(Level, String, Throwable)} call that follows that exact
 * message with four POPs (same stack effect, no frame change). Event firing is untouched.
 */
public final class MobUnknownTargetReasonQuietTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.MobUnknownTargetReasonQuietTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/entity/Mob";
	private static final String MESSAGE = "Unknown target reason, please report on the issue tracker";

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
				if (!method.name.equals("setTarget")) {
					continue;
				}
				boolean seenMessage = false;
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof LdcInsnNode && MESSAGE.equals(((LdcInsnNode) insn).cst)) {
						seenMessage = true;
					} else if (seenMessage && insn instanceof MethodInsnNode) {
						MethodInsnNode m = (MethodInsnNode) insn;
						if (m.owner.equals("java/util/logging/Logger") && m.name.equals("log")
								&& m.desc.equals("(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V")) {
							// stack: logger, level, message, throwable
							method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
							method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
							method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
							method.instructions.set(insn, new InsnNode(Opcodes.POP));
							patched = true;
							seenMessage = false;
						}
					}
				}
			}
			if (!patched) {
				return null;
			}
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);
			LOGGER.info("Silenced Paper's 'Unknown target reason' warning in Mob#setTarget.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className + " - leaving it unpatched", t);
			return null;
		}
	}
}