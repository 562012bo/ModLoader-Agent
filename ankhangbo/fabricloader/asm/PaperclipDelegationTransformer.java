package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces io.papermc.paperclip.Paperclip's main() body so it does NOT build its own bukkitLoader
 * or run any of its own startup logic — it just delegates to
 * {@code ankhangbo.fabricloader.Pclip#main(String[])}, which builds ONE named classloader
 * ({@code net.fabricmc.loader.impl.launch.knot.KnotCompatibilityClassLoader} — real Fabric
 * Loader source, reused later as Knot's own loader too, not a separate loader of this project's
 * own) and launches real Fabric Loader's own entrypoint through it. Paperclip itself never runs
 * its own logic again after this — this class only needs {@code setupClasspath()}/
 * {@code findMainClass()} (widened to public here, same as before) reflectively, from Pclip, to
 * locate Paper's own patched jar/libraries; nothing else in Paperclip ever executes.
 *
 * Registered via {@code inst.addTransformer(new PaperclipDelegationTransformer(), false)} as
 * early as possible in premain — Paperclip.class has not been loaded yet at that point (the JVM
 * only reads Main-Class out of the manifest to decide what to invoke; it does not load the class
 * until immediately before calling main()), so plain addTransformer is enough; no
 * retransformClasses() needed.
 */
public final class PaperclipDelegationTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.PaperclipDelegationTransformer");
    private static final String TARGET = "io/papermc/paperclip/Paperclip";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) {
            return null;
        }
        try {
            return patch(classfileBuffer);
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to patch io.papermc.paperclip.Paperclip for Pclip "
                    + "delegation — falling back to STOCK Paperclip behavior this run (Paper will "
                    + "boot the old way, real Fabric Loader will NOT be used)", t);
            return null;
        }
    }

    private static byte[] patch(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean rewroteMain = false;
        for (MethodNode method : classNode.methods) {
            switch (method.name) {
                case "main":
                    if ("([Ljava/lang/String;)V".equals(method.desc)) {
                        replaceMainBody(method);
                        rewroteMain = true;
                    }
                    break;
                case "setupClasspath":
                case "findMainClass":
                    widenToPublic(method);
                    break;
                default:
                    break;
            }
        }

        if (!rewroteMain) {
            throw new IllegalStateException("Did not find Paperclip.main(String[]) to rewrite — "
                    + "Paperclip's internal layout changed since this transformer was written");
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        LOGGER.info("PaperclipTransformer installed - waiting for io.papermc.paperclip.Paperclip to load.");
        return writer.toByteArray();
    }

    /** Empties main()'s body and replaces it with: Pclip.main(args); return; */
    private static void replaceMainBody(MethodNode method) {
        InsnList newInsns = new InsnList();
        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0)); // args
        newInsns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "ankhangbo/fabricloader/Pclip",
                "main",
                "([Ljava/lang/String;)V",
                false));
        newInsns.add(new InsnNode(Opcodes.RETURN));

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.instructions.add(newInsns);
    }

    private static void widenToPublic(MethodNode method) {
        method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
    }
}
