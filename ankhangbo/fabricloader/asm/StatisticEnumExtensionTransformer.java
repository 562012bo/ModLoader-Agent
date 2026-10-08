package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets {@code org.bukkit.Statistic} (an enum) gain constants AFTER class loading, without Unsafe and without mutating a
 * static final: the class is rewritten so that
 * <ul>
 *   <li>a second private constructor {@code (String name, int ordinal, Type type, NamespacedKey key)} exists (it calls
 *       {@code Enum.<init>(name, ordinal)} and assigns the enum's own {@code type}/{@code key} fields, so a mod statistic can
 *       carry its real key {@code modid:path} instead of {@code minecraft:<name>});</li>
 *   <li>{@code public static Statistic fabric$create(String, int, Type, NamespacedKey)} calls it;</li>
 *   <li>{@code values()} returns {@code StatisticBridge.withExtras($VALUES.clone())}, i.e. the vanilla/Paper constants
 *       followed by the registered mod constants. {@code valueOf(String)} and {@code EnumMap}/{@code EnumSet} get their
 *       constants through {@code values()} too, so they see the extra ones as long as they are first used after
 *       {@code StatisticBridge.register}.</li>
 * </ul>
 * Straight-line code only, no frames needed.
 */
public final class StatisticEnumExtensionTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.StatisticEnumExtensionTransformer");

	private static final String TARGET = "org/bukkit/Statistic";
	private static final String TYPE = "Lorg/bukkit/Statistic$Type;";
	private static final String KEY = "Lorg/bukkit/NamespacedKey;";
	private static final String BRIDGE = "ankhangbo/fabricloader/compat/StatisticBridge";

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

			boolean hasType = false;
			boolean hasKey = false;
			for (FieldNode f : (List<FieldNode>) cn.fields) {
				hasType |= f.name.equals("type") && f.desc.equals(TYPE);
				hasKey |= f.name.equals("key") && f.desc.equals(KEY);
			}
			if (!hasType || !hasKey) {
				LOGGER.warning("org.bukkit.Statistic has no type/key fields - NOT patched (different Paper version?)");
				return null;
			}

			boolean valuesPatched = false;
			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (m.name.equals("values") && m.desc.equals("()[Lorg/bukkit/Statistic;")) {
					for (AbstractInsnNode insn : m.instructions.toArray()) {
						if (insn.getOpcode() == Opcodes.CHECKCAST) {
							m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "withExtras",
									"(Ljava/lang/Object;)Ljava/lang/Object;", false));
							valuesPatched = true;
							break;
						}
					}
				}
			}
			if (!valuesPatched) {
				LOGGER.warning("Statistic.values() has an unexpected shape - NOT patched");
				return null;
			}

			String ctorDesc = "(Ljava/lang/String;I" + TYPE + KEY + ")V";
			MethodNode ctor = new MethodNode(Opcodes.ACC_PRIVATE, "<init>", ctorDesc, null, null);
			InsnList c = new InsnList();
			c.add(new VarInsnNode(Opcodes.ALOAD, 0));
			c.add(new VarInsnNode(Opcodes.ALOAD, 1));
			c.add(new VarInsnNode(Opcodes.ILOAD, 2));
			c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false));
			c.add(new VarInsnNode(Opcodes.ALOAD, 0));
			c.add(new VarInsnNode(Opcodes.ALOAD, 3));
			c.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET, "type", TYPE));
			c.add(new VarInsnNode(Opcodes.ALOAD, 0));
			c.add(new VarInsnNode(Opcodes.ALOAD, 4));
			c.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET, "key", KEY));
			c.add(new InsnNode(Opcodes.RETURN));
			ctor.instructions = c;
			cn.methods.add(ctor);

			MethodNode factory = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fabric$create",
					ctorDesc.replace(")V", ")Lorg/bukkit/Statistic;"), null, null);
			InsnList f = new InsnList();
			f.add(new TypeInsnNode(Opcodes.NEW, TARGET));
			f.add(new InsnNode(Opcodes.DUP));
			f.add(new VarInsnNode(Opcodes.ALOAD, 0));
			f.add(new VarInsnNode(Opcodes.ILOAD, 1));
			f.add(new VarInsnNode(Opcodes.ALOAD, 2));
			f.add(new VarInsnNode(Opcodes.ALOAD, 3));
			f.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, "<init>", ctorDesc, false));
			f.add(new InsnNode(Opcodes.ARETURN));
			factory.instructions = f;
			cn.methods.add(factory);

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED org.bukkit.Statistic (values() can include mod statistics)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}