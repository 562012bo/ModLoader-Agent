package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rewrites calls (in ANY class: mods, libraries) to vanilla/Fabric methods that this Leaf build renamed, so that they link even
 * if the target class was not given a bridge method (see LeafApiBridgeTransformer, which adds the bridge to {@code Mth}).
 * Two independent layers on purpose: whichever one applies first fixes {@code NoSuchMethodError: Mth.positiveModulo(float,float)}.
 *
 * <p>Rows: {@code Mth.positiveModulo(FF)F} and {@code (DD)D} -> {@code positiveModuloForAnyDenominator} (identical body).
 * Only the method name of an {@code INVOKESTATIC} changes: no stack, frame or max change. A byte pre-filter keeps the cost for
 * unrelated classes to a substring scan.
 */
public final class CallSiteRemapTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.CallSiteRemapTransformer");

	private static final String MTH = "net/minecraft/util/Mth";
	private static final byte[] MARK_NAME = "positiveModulo".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] MARK_OWNER = MTH.getBytes(StandardCharsets.US_ASCII);

	private static boolean contains(byte[] data, byte[] needle) {
		outer:
		for (int i = 0, max = data.length - needle.length; i <= max; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) continue outer;
			}

			return true;
		}

		return false;
	}

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null || MTH.equals(className) || className.startsWith("java/") || className.startsWith("jdk/")
				|| className.startsWith("org/objectweb/")) {
			return null;
		}

		if (!contains(classfileBuffer, MARK_NAME) || !contains(classfileBuffer, MARK_OWNER)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			int changed = 0;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKESTATIC) continue;

					MethodInsnNode mi = (MethodInsnNode) insn;

					if (mi.owner.equals(MTH) && mi.name.equals("positiveModulo") && (mi.desc.equals("(FF)F") || mi.desc.equals("(DD)D"))) {
						mi.name = "positiveModuloForAnyDenominator";
						changed++;
					}
				}
			}

			if (changed == 0) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("REMAPPED " + changed + " call(s) to Mth.positiveModulo -> positiveModuloForAnyDenominator in " + className);
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to remap calls in " + className, t);
			return null;
		}
	}
}
