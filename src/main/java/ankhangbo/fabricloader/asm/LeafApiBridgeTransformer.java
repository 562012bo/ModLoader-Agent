package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adds the vanilla/Fabric static methods that this Leaf build renamed or changed, so mods compiled against the Fabric server
 * keep linking ({@code NoSuchMethodError}):
 * <ul>
 *   <li>{@code Mth.positiveModulo(float,float)} / {@code (double,double)}: renamed to {@code positiveModuloForAnyDenominator}
 *       with the identical body {@code (input % mod + mod) % mod} (seen in Twilight Forest's {@code RectangleLatticeIterator}).</li>
 *   <li>{@code BaseFireBlock.fireIgnite(Entity)}: Leaf's version takes an extra {@code BlockPos} (for the Bukkit combust event);
 *       the bridge passes {@code entity.blockPosition()}.</li>
 * </ul>
 * Pure additions of straight-line static methods (no frames); skipped if the method already exists.
 * A scan of the Fabric vs Leaf sources found no other changed statics that mods are likely to call; add more rows below as needed.
 */
public final class LeafApiBridgeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.LeafApiBridgeTransformer");

	private static final String MTH = "net/minecraft/util/Mth";
	private static final String FIRE = "net/minecraft/world/level/block/BaseFireBlock";
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final String BLOCK_POS = "net/minecraft/core/BlockPos";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!MTH.equals(className) && !FIRE.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			int added = 0;

			if (MTH.equals(className)) {
				added += bridgeSameArgs(cn, "positiveModulo", "(FF)F", "positiveModuloForAnyDenominator", Opcodes.FLOAD, Opcodes.FRETURN, 1);
				added += bridgeSameArgs(cn, "positiveModulo", "(DD)D", "positiveModuloForAnyDenominator", Opcodes.DLOAD, Opcodes.DRETURN, 2);
			} else {
				added += bridgeFireIgnite(cn);
			}

			if (added == 0) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (added " + added + " compatibility method(s) for mods built against the Fabric server)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean has(ClassNode cn, String name, String desc) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return true;
		}

		return false;
	}

	/** {@code static T name(T a, T b) { return target(a, b); }} for two same-typed arguments. */
	@SuppressWarnings("unchecked")
	private static int bridgeSameArgs(ClassNode cn, String name, String desc, String target, int load, int ret, int secondSlot) {
		if (has(cn, name, desc) || !has(cn, target, desc)) return 0;

		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		m.instructions.add(new VarInsnNode(load, 0));
		m.instructions.add(new VarInsnNode(load, secondSlot));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, target, desc, false));
		m.instructions.add(new InsnNode(ret));
		cn.methods.add(m);
		return 1;
	}

	@SuppressWarnings("unchecked")
	private static int bridgeFireIgnite(ClassNode cn) {
		String oldDesc = "(L" + ENTITY + ";)V";
		String newDesc = "(L" + ENTITY + ";L" + BLOCK_POS + ";)V";

		if (has(cn, "fireIgnite", oldDesc) || !has(cn, "fireIgnite", newDesc)) return 0;

		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fireIgnite", oldDesc, null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "blockPosition", "()L" + BLOCK_POS + ";", false));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "fireIgnite", newDesc, false));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(m);
		return 1;
	}
}
