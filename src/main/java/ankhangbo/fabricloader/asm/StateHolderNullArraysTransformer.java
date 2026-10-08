package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Create Fly ({@code IPlacementHelper}: {@code new BlockState(Blocks.AIR, null, null)}) builds a state with null
 * property arrays. Vanilla never dereferences them in the constructor, but Leaf/Moonrise's
 * {@code StateHolder(Object owner, Property[] keys, Comparable[] values)} does
 * ({@code new ZeroCollidingReferenceStateTable(keys)} -> {@code properties.length}), so the block bootstrap dies with
 * {@code ExceptionInInitializerError: Cannot read the array length because "properties" is null}.
 *
 * <p>Fix: at the head of that constructor replace a null {@code keys}/{@code values} by an empty array
 * (== a state without properties, which is what Create Fly means). Straight-line code only (static helper call),
 * so no stack map frames are touched and no class is loaded while the class is defined.
 */
public final class StateHolderNullArraysTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.StateHolderNullArraysTransformer");

	private static final String TARGET = "net/minecraft/world/level/block/state/StateHolder";
	private static final String PROPERTY = "net/minecraft/world/level/block/state/properties/Property";
	private static final String CTOR_DESC = "(Ljava/lang/Object;[L" + PROPERTY + ";[Ljava/lang/Comparable;)V";
	private static final String HELPER = "ankhangbo/fabricloader/compat/NullStateArrays";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			MethodNode ctor = null;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (m.name.equals("<init>") && m.desc.equals(CTOR_DESC)) {
					ctor = m;
					break;
				}
			}

			if (ctor == null) {
				LOGGER.warning(className + ": (Object, Property[], Comparable[]) constructor not found - NOT patched "
						+ "(different Leaf/Moonrise version?)");
				return null;
			}

			InsnList head = new InsnList();
			// keys = (Property[]) orEmpty(keys, Property.class)
			head.add(new VarInsnNode(Opcodes.ALOAD, 2));
			head.add(new LdcInsnNode(Type.getObjectType(PROPERTY)));
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "orEmpty",
					"([Ljava/lang/Object;Ljava/lang/Class;)[Ljava/lang/Object;", false));
			head.add(new TypeInsnNode(Opcodes.CHECKCAST, "[L" + PROPERTY + ";"));
			head.add(new VarInsnNode(Opcodes.ASTORE, 2));
			// values = (Comparable[]) orEmpty(values, Comparable.class)
			head.add(new VarInsnNode(Opcodes.ALOAD, 3));
			head.add(new LdcInsnNode(Type.getObjectType("java/lang/Comparable")));
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "orEmpty",
					"([Ljava/lang/Object;Ljava/lang/Class;)[Ljava/lang/Object;", false));
			head.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Comparable;"));
			head.add(new VarInsnNode(Opcodes.ASTORE, 3));

			AbstractInsnNode first = ctor.instructions.getFirst();

			if (first != null) ctor.instructions.insertBefore(first, head);
			else ctor.instructions.add(head);

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (null property arrays tolerated, for Create Fly)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}
