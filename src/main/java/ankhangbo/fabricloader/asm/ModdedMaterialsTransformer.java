package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gives every MODDED block/item a real Bukkit {@code Material} (what Mohist/Arclight do), so plugins (WorldGuard, CoreProtect, ...)
 * see a normal non-null {@code Block.getType()} / {@code ItemStack.getType()} instead of null.
 *
 * <p>Two class patches, driven by {@link ankhangbo.fabricloader.compat.ModdedMaterials}:
 * <ol>
 *   <li>{@code org.bukkit.Material}: {@code $VALUES} and {@code key} lose {@code final}, and a public static
 *       {@code ankhangbo$add(String name, String namespace, String path)} is added. It builds a new enum constant through the real
 *       private constructor, sets its key to {@code namespace:path}, appends it to {@code $VALUES} and {@code BY_NAME}.</li>
 *   <li>{@code CraftMagicNumbers.<clinit>}: first calls {@code ModdedMaterials.registerAll(loader)} (adds a constant for each
 *       non-vanilla key of BuiltInRegistries.BLOCK / ITEM - this runs before anything builds an EnumMap/switch table over
 *       Material, and before plugins load), and the two lookup loops call {@code ModdedMaterials.materialPath(key)} instead of
 *       {@code key.getPath()} so a modded block resolves to its {@code NAMESPACE_PATH} constant (vanilla keys unchanged).</li>
 * </ol>
 * Straight-line bytecode only (no branches, no frames), JDK types in every helper signature (see NullSafeMaterial's javadoc).
 */
public final class ModdedMaterialsTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.ModdedMaterialsTransformer");
	private static final String MATERIAL = "org/bukkit/Material";
	private static final String MAGIC = "org/bukkit/craftbukkit/util/CraftMagicNumbers";
	private static final String HELPER = "ankhangbo/fabricloader/compat/ModdedMaterials";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!"true".equalsIgnoreCase(System.getProperty("ankhangbo.moddedMaterials", "true"))) return null;

		try {
			if (MATERIAL.equals(className)) return patchMaterial(buf);
			if (MAGIC.equals(className)) return patchMagicNumbers(buf);
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
		}

		return null;
	}

	private static byte[] patchMaterial(byte[] buf) {
		ClassNode cn = new ClassNode();
		new ClassReader(buf).accept(cn, 0);

		for (MethodNode m : cn.methods) {
			if (m.name.equals("ankhangbo$add")) return null; // already patched
		}

		boolean hasValues = false, hasKey = false, hasByName = false, hasCtor = false;

		for (FieldNode f : cn.fields) {
			if (f.name.equals("$VALUES") && f.desc.equals("[L" + MATERIAL + ";")) { f.access &= ~Opcodes.ACC_FINAL; hasValues = true; }
			else if (f.name.equals("key") && f.desc.equals("Lorg/bukkit/NamespacedKey;")) { f.access &= ~Opcodes.ACC_FINAL; hasKey = true; }
			else if (f.name.equals("BY_NAME") && f.desc.equals("Ljava/util/Map;")) hasByName = true;
		}

		for (MethodNode m : cn.methods) {
			if (m.name.equals("<init>") && m.desc.equals("(Ljava/lang/String;II)V")) hasCtor = true;
		}

		if (!hasValues || !hasKey || !hasByName || !hasCtor) {
			LOGGER.warning("org.bukkit.Material has an unexpected shape (values=" + hasValues + " key=" + hasKey + " byName=" + hasByName
					+ " ctor=" + hasCtor + ") - modded materials NOT enabled");
			return null;
		}

		String arr = "[L" + MATERIAL + ";";
		MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, "ankhangbo$add",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)L" + MATERIAL + ";", null, null);
		InsnList il = new InsnList();
		il.add(new FieldInsnNode(Opcodes.GETSTATIC, MATERIAL, "$VALUES", arr));
		il.add(new VarInsnNode(Opcodes.ASTORE, 3)); // old
		il.add(new TypeInsnNode(Opcodes.NEW, MATERIAL));
		il.add(new InsnNode(Opcodes.DUP));
		il.add(new VarInsnNode(Opcodes.ALOAD, 0));
		il.add(new VarInsnNode(Opcodes.ALOAD, 3));
		il.add(new InsnNode(Opcodes.ARRAYLENGTH)); // ordinal
		il.add(new InsnNode(Opcodes.ICONST_0)); // legacy id (unused)
		il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MATERIAL, "<init>", "(Ljava/lang/String;II)V", false));
		il.add(new VarInsnNode(Opcodes.ASTORE, 4)); // m
		il.add(new VarInsnNode(Opcodes.ALOAD, 4));
		il.add(new TypeInsnNode(Opcodes.NEW, "org/bukkit/NamespacedKey"));
		il.add(new InsnNode(Opcodes.DUP));
		il.add(new VarInsnNode(Opcodes.ALOAD, 1));
		il.add(new VarInsnNode(Opcodes.ALOAD, 2));
		il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "org/bukkit/NamespacedKey", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false));
		il.add(new FieldInsnNode(Opcodes.PUTFIELD, MATERIAL, "key", "Lorg/bukkit/NamespacedKey;"));
		il.add(new VarInsnNode(Opcodes.ALOAD, 3));
		il.add(new VarInsnNode(Opcodes.ALOAD, 3));
		il.add(new InsnNode(Opcodes.ARRAYLENGTH));
		il.add(new InsnNode(Opcodes.ICONST_1));
		il.add(new InsnNode(Opcodes.IADD));
		il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf", "([Ljava/lang/Object;I)[Ljava/lang/Object;", false));
		il.add(new TypeInsnNode(Opcodes.CHECKCAST, arr));
		il.add(new VarInsnNode(Opcodes.ASTORE, 5)); // grown
		il.add(new VarInsnNode(Opcodes.ALOAD, 5));
		il.add(new VarInsnNode(Opcodes.ALOAD, 3));
		il.add(new InsnNode(Opcodes.ARRAYLENGTH));
		il.add(new VarInsnNode(Opcodes.ALOAD, 4));
		il.add(new InsnNode(Opcodes.AASTORE));
		il.add(new VarInsnNode(Opcodes.ALOAD, 5));
		il.add(new FieldInsnNode(Opcodes.PUTSTATIC, MATERIAL, "$VALUES", arr));
		il.add(new FieldInsnNode(Opcodes.GETSTATIC, MATERIAL, "BY_NAME", "Ljava/util/Map;"));
		il.add(new VarInsnNode(Opcodes.ALOAD, 0));
		il.add(new VarInsnNode(Opcodes.ALOAD, 4));
		il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
		il.add(new InsnNode(Opcodes.POP));
		il.add(new VarInsnNode(Opcodes.ALOAD, 4));
		il.add(new InsnNode(Opcodes.ARETURN));
		add.instructions = il;
		cn.methods.add(add);

		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(w);
		LOGGER.info("PATCHED " + MATERIAL + " (added ankhangbo$add: Bukkit Materials can be created for modded blocks/items)");
		return w.toByteArray();
	}

	private static byte[] patchMagicNumbers(byte[] buf) {
		ClassNode cn = new ClassNode();
		new ClassReader(buf).accept(cn, 0);
		boolean done = false;

		for (MethodNode m : cn.methods) {
			if (!m.name.equals("<clinit>")) continue;
			int swapped = 0;

			for (AbstractInsnNode in : m.instructions.toArray()) {
				if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.name.equals("getPath") && mi.desc.equals("()Ljava/lang/String;")) {
					m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "materialPath", "(Ljava/lang/Object;)Ljava/lang/String;", false));
					swapped++;
				}
			}

			if (swapped == 0) {
				LOGGER.warning("CraftMagicNumbers.<clinit> has no Identifier.getPath() call - modded materials NOT enabled");
				return null;
			}

			InsnList pre = new InsnList();
			pre.add(new LdcInsnNode(Type.getObjectType(MAGIC)));
			pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
			pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "registerAll", "(Ljava/lang/ClassLoader;)V", false));
			m.instructions.insert(pre);
			done = true;
		}

		if (!done) return null;

		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(w);
		LOGGER.info("PATCHED " + MAGIC + " (registers Bukkit Materials for modded blocks/items before the lookup tables are built)");
		return w.toByteArray();
	}
}