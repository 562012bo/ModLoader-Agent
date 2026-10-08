package ankhangbo.fabricloader.asm;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fix: NoSuchMethodError: 'void net.minecraft.world.entity.schedule.Activity.<init>(java.lang.String)'
 *
 * Fabric:  private Activity(String name)
 * Paper:   private Activity(String name, int id)
 *
 * Inject constructor 1 tham số vào Activity, delegate sang constructor 2 tham số.
 */
public class ActivityConstructorCompatTransformer implements ClassFileTransformer {

    private static final String ACTIVITY = "net/minecraft/world/entity/schedule/Activity";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {

        if (className == null || !className.equals(ACTIVITY)) {
            return null; // không phải class cần patch
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {

                private boolean hasOneArgCtor = false;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    // Kiểm tra xem constructor 1 tham số đã tồn tại chưa (tránh inject trùng)
                    if ("<init>".equals(name) && "(Ljava/lang/String;)V".equals(descriptor)) {
                        hasOneArgCtor = true;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                @Override
                public void visitEnd() {
                    if (!hasOneArgCtor) {
                        injectOneArgConstructor(this);
                    }
                    super.visitEnd();
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[ActivityConstructorCompat] Failed: " + t);
            t.printStackTrace();
            return null;
        }
    }

    private static void injectOneArgConstructor(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "(Ljava/lang/String;)V",
            null,
            null
        );
        mv.visitCode();

        // this(name, BuiltInRegistries.ACTIVITY.size())
        mv.visitVarInsn(Opcodes.ALOAD, 0);                       // this
        mv.visitVarInsn(Opcodes.ALOAD, 1);                       // name
        mv.visitFieldInsn(
            Opcodes.GETSTATIC,
            "net/minecraft/core/registries/BuiltInRegistries",
            "ACTIVITY",
            "Lnet/minecraft/core/Registry;"
        );
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "net/minecraft/core/Registry",
            "size",
            "()I",
            true
        );
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            ACTIVITY,
            "<init>",
            "(Ljava/lang/String;I)V",
            false
        );
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}