package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DEFINITIVE fix for every "AbstractMethodError: Method X.getMaxStackSize()I is abstract"
 * (and getViewers/onOpen/onClose/getContents/getOwner/setMaxStackSize/getLocation).
 *
 * <p>Root cause: CraftBukkit/Paper turned {@code Container#getMaxStackSize()} from a default
 * method (vanilla returns 99) into an abstract one and added seven more abstract methods. Mods
 * compiled against vanilla never implement them.
 *
 * <p>The old per-class transformers (ContainerMaxStackSizeTransformer / ContainerBukkitCompat...)
 * decide per implementing class whether to add the method, using a super/interface map that is
 * built while classes load. A JVM calls a transformer for a class BEFORE it loads that class's
 * superclass/interfaces, so for a class whose ancestors (e.g. a mod's own base class or
 * interface) are not loaded yet the map is incomplete, "implements Container" is not detected,
 * and the class is silently left unpatched - hence a new mod broke each time one was fixed.
 *
 * <p>This transformer instead fixes the interface itself: every abstract CraftBukkit-added method
 * of {@code net.minecraft.world.Container} gets a default body. Classes that implement the
 * method keep theirs; classes that don't inherit the default. Resolution happens inside the JVM
 * at call time, so class loading order no longer matters. Straight-line bodies: COMPUTE_MAXS.
 */
public final class ContainerInterfaceDefaultsTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ContainerInterfaceDefaultsTransformer");

	private static final String TARGET = "net/minecraft/world/Container";
	private static final String HELPER = "ankhangbo/fabricloader/compat/ContainerCompat";

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
			StringBuilder done = new StringBuilder();

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if ((m.access & Opcodes.ACC_ABSTRACT) == 0 || (m.access & Opcodes.ACC_STATIC) != 0) {
					continue;
				}
				InsnList b = new InsnList();
				String key = m.name + m.desc.substring(0, m.desc.indexOf(')') + 1);
				switch (m.name) {
					case "getMaxStackSize":
						if (!m.desc.equals("()I")) {
							continue;
						}
						b.add(new IntInsnNode(Opcodes.BIPUSH, 99)); // vanilla's original default
						b.add(new InsnNode(Opcodes.IRETURN));
						break;
					case "getContents":
						b.add(new VarInsnNode(Opcodes.ALOAD, 0));
						b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getContents",
								"(Ljava/lang/Object;)Ljava/util/List;", false));
						b.add(new InsnNode(Opcodes.ARETURN));
						break;
					case "getViewers":
						b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
								"()Ljava/util/List;", false));
						b.add(new InsnNode(Opcodes.ARETURN));
						break;
					case "getOwner":
					case "getLocation":
						b.add(new InsnNode(Opcodes.ACONST_NULL));
						b.add(new InsnNode(Opcodes.ARETURN));
						break;
					case "onOpen":
					case "onClose":
					case "setMaxStackSize":
						b.add(new InsnNode(Opcodes.RETURN));
						break;
					default:
						continue;
				}
				m.access &= ~Opcodes.ACC_ABSTRACT;
				m.instructions = b;
				m.maxStack = 2;
				m.maxLocals = 3;
				done.append(m.name).append(' ');
			}

			if (done.length() == 0) {
				LOGGER.warning("Container has no abstract CraftBukkit methods to default - not patched");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED Container: added default bodies for: " + done);
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}