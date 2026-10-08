package ankhangbo.fabricloader.asm;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code io.papermc.paperclip.Paperclip}.
 *
 * <p>Rather than diffing individual instructions inside {@code main()} (which
 * would break the moment PaperMC reorders/changes that method internally),
 * this wholesale REPLACES the entire body of
 * {@code Paperclip#main(String[])} with:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     ankhangbo.fabricloader.Pclip.main(args);
 * }
 * }</pre>
 *
 * {@link ankhangbo.fabricloader.Pclip} then reflectively calls Paperclip's
 * own (unpatched) {@code setupClasspath()} / {@code findMainClass()} private
 * methods to do the actual extraction/patch/download work, so nothing about
 * that logic needs to be re-implemented or kept version-in-sync - only the
 * one line that used to build a plain {@code URLClassLoader} changes, inside
 * {@code Pclip} itself.
 */
public final class PaperclipTransformer implements ClassFileTransformer {

    private static final String TARGET_CLASS = "io/papermc/paperclip/Paperclip";
    private static final String MAIN_DESC = "([Ljava/lang/String;)V";
    private static final String PCLIP = "ankhangbo/fabricloader/Pclip";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET_CLASS.equals(className)) {
            return null; // not our target - leave untouched
        }

        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if ("main".equals(name) && MAIN_DESC.equals(descriptor)) {
                    // Declare the method fresh in the writer and fill in our
                    // own body right here, ignoring whatever original
                    // instructions the ClassReader would otherwise feed us.
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0); // args
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, PCLIP, "main", MAIN_DESC, false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0); // recomputed by COMPUTE_MAXS
                    mv.visitEnd();

                    return null; // tell the reader to skip visiting the original body
                }

                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }
}
