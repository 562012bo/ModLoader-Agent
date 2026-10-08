package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code DataFixTypes.NONE} has a {@code null} type. Vanilla's {@code DataFixTypes#update} returns the input untouched in that
 * case, but mods that wrap the whole method with MixinExtras {@code @WrapMethod} (Railways' {@code updateWithModFixers}) run
 * BEFORE that check and call {@code DataFixerUpper.update(null, ...)}, which dies with
 * {@code NullPointerException: Cannot invoke "DSL$TypeReference.typeName()" because "type" is null}. Paper's world loader
 * ({@code PaperWorldLoader -> SavedDataStorage}) reaches this path with NONE data.
 *
 * <p>Fix: at the start of {@code DataFixerUpper.update(TypeReference, Dynamic, int, int)} return {@code input} when {@code type}
 * is null - exactly what vanilla does for that type. One extra frame node (F_SAME: the locals are still the method's
 * parameters).
 */
public final class DataFixerUpperNullTypeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.DataFixerUpperNullTypeTransformer");

	private static final String TARGET = "com/mojang/datafixers/DataFixerUpper";
	private static final String DESC = "(Lcom/mojang/datafixers/DSL$TypeReference;Lcom/mojang/serialization/Dynamic;II)Lcom/mojang/serialization/Dynamic;";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			MethodNode target = null;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (m.name.equals("update") && m.desc.equals(DESC) && (m.access & Opcodes.ACC_STATIC) == 0) {
					target = m;
					break;
				}
			}

			if (target == null) {
				LOGGER.warning(className + ".update(TypeReference, Dynamic, int, int) not found - NOT patched");
				return null;
			}

			LabelNode ok = new LabelNode();
			InsnList head = new InsnList();
			head.add(new VarInsnNode(Opcodes.ALOAD, 1));
			head.add(new JumpInsnNode(Opcodes.IFNONNULL, ok));
			head.add(new VarInsnNode(Opcodes.ALOAD, 2));
			head.add(new InsnNode(Opcodes.ARETURN));
			head.add(ok);
			head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
			target.instructions.insert(head);

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (update() with a null type returns the input, like vanilla DataFixTypes.NONE)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}
