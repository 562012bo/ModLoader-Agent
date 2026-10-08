package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code org.bukkit.block.Block#getType()} returns {@code null} for a block a mod registered (Bukkit's
 * {@code Material} enum has no constant for it - same root cause as {@code NullSafeMaterialTransformer}). Paper's
 * patches inside vanilla classes call {@code block.getType().isAir()} without a null check, e.g.
 * {@code ServerExplosion#interactWithBlocks}: {@code if (bblock.getType().isAir()) continue;}, so any explosion that
 * touches a modded block throws {@code NullPointerException: Cannot invoke "Material.isAir()" because the return value
 * of "Block.getType()" is null}.
 *
 * <p>Fix, at every {@code INVOKEVIRTUAL org/bukkit/Material.isAir ()Z} in {@code net/minecraft/}, CraftBukkit and Paper
 * classes: treat a null material as <b>not air</b> (a modded block is a real block - it must take part in the explosion):
 * <pre>
 *   [material]  GETSTATIC Material.STONE  INVOKESTATIC Objects.requireNonNullElse  CHECKCAST Material  INVOKEVIRTUAL isAir
 * </pre>
 * Straight-line code only (no branch, no stack map frame) and all type references resolve through the patched class's own
 * loader - nothing from this agent jar is referenced (a parent loader can't see Bukkit types, see NullSafeMaterial).
 */
public final class BukkitMaterialIsAirNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.BukkitMaterialIsAirNullSafeTransformer");

	private static final String MATERIAL = "org/bukkit/Material";
	private static final byte[] MARK_MATERIAL = MATERIAL.getBytes(StandardCharsets.US_ASCII);
	private static final byte[] MARK_IS_AIR = "isAir".getBytes(StandardCharsets.US_ASCII);

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
		if (className == null
				|| !(className.startsWith("net/minecraft/") || className.startsWith("org/bukkit/craftbukkit/")
						|| className.startsWith("io/papermc/paper/"))) {
			return null;
		}

		// cheap pre-filter: most classes never mention Material.isAir
		if (!contains(classfileBuffer, MARK_MATERIAL) || !contains(classfileBuffer, MARK_IS_AIR)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			int patched = 0;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
					AbstractInsnNode next = insn.getNext();

					if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
							&& mi.owner.equals(MATERIAL) && mi.name.equals("isAir") && mi.desc.equals("()Z")) {
						InsnList pre = new InsnList();
						pre.add(new FieldInsnNode(Opcodes.GETSTATIC, MATERIAL, "STONE", "L" + MATERIAL + ";"));
						pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse",
								"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
						pre.add(new TypeInsnNode(Opcodes.CHECKCAST, MATERIAL));
						m.instructions.insertBefore(mi, pre);
						patched++;
					}

					insn = next;
				}
			}

			if (patched == 0) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (" + patched + " Material.isAir() call(s) made null-safe for modded blocks)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}
