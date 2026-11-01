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
 * {@code CraftMagicNumbers.getMaterial(Item)} is meant to return {@code Material.AIR} for an item Bukkit does not know
 * ({@code ITEM_MATERIAL.getOrDefault(item, AIR)}), but the static initializer stores {@code item -> null} for every MODDED item
 * (no Material constant of that name), so {@code getOrDefault} finds the key and returns null. {@code ItemStack.getType()} is then
 * null and plugins NPE ({@code getType().getKey()} in WorldGuard/CoreProtect, {@code CraftLegacy.modern_ordinal(null)}).
 *
 * <p>Only ITEMS: {@code getMaterial(Block)} deliberately keeps returning null (BukkitMaterialIsAirNullSafe, CraftBlockStates... rely
 * on "null = modded block"; AIR would make modded blocks look like air and register block-state factories under AIR).
 * Straight-line patch: {@code Objects.requireNonNullElse(result, Material.AIR)} before each ARETURN, no frames.
 */
public final class CraftMagicNumbersItemMaterialNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.CraftMagicNumbersItemMaterialNullSafeTransformer");
	private static final String TARGET = "org/bukkit/craftbukkit/util/CraftMagicNumbers";
	private static final String MATERIAL = "org/bukkit/Material";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);
			int changed = 0;

			for (MethodNode m : cn.methods) {
				if (!m.name.equals("getMaterial") || !m.desc.equals("(Lnet/minecraft/world/item/Item;)L" + MATERIAL + ";")
						|| (m.access & Opcodes.ACC_STATIC) == 0) continue;

				for (AbstractInsnNode in : m.instructions.toArray()) {
					if (in.getOpcode() != Opcodes.ARETURN) continue;

					InsnList pre = new InsnList();
					pre.add(new FieldInsnNode(Opcodes.GETSTATIC, MATERIAL, "AIR", "L" + MATERIAL + ";"));
					pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse",
							"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
					pre.add(new TypeInsnNode(Opcodes.CHECKCAST, MATERIAL));
					m.instructions.insertBefore(in, pre);
					changed++;
				}
			}

			if (changed == 0) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (getMaterial(Item) returns AIR instead of null for modded items)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}