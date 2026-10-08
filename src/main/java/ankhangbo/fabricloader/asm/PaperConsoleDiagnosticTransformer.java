package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Prints {@code ConsoleDiag.report(server)} when Paper's console is constructed (see ConsoleDiag). */
public final class PaperConsoleDiagnosticTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.PaperConsoleDiagnosticTransformer");

	private static final String TARGET = "com/destroystokyo/paper/console/PaperConsole";

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
				if (m.name.equals("<init>") && m.desc.equals("(Lnet/minecraft/server/dedicated/DedicatedServer;)V")) {
					InsnList add = new InsnList();
					add.add(new VarInsnNode(Opcodes.ALOAD, 1));
					add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "ankhangbo/fabricloader/compat/ConsoleDiag",
							"report", "(Ljava/lang/Object;)V", false));
					m.instructions.insert(add);
					patched = true;
				}
			}
			if (!patched) {
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}