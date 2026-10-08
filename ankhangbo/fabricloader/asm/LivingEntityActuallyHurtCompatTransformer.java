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
 * Fix Paper/Leaf — thêm lại overload actuallyHurt(ServerLevel, DamageSource, F)V
 * vào LivingEntity (giống Fabric gốc), delegate sang overload 4-tham-số của Paper.
 *
 * CHỈ can thiệp vào class GAME (net.minecraft.world.entity.LivingEntity)
 * — không đụng mod, không đụng Mixin.
 *
 * Overload 3 args KHÔNG tự xử lý logic — chỉ gọi overload 4 args của Paper/Leaf:
 *
 *   protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
 *       EntityDamageEvent event = createFakeEvent(this, source, dmg);
 *       this.actuallyHurt(level, source, dmg, event);   // 4-arg overload của Paper
 *   }
 */
public class LivingEntityActuallyHurtCompatTransformer implements ClassFileTransformer {

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String SERVER_LEVEL  = "net/minecraft/server/level/ServerLevel";
    private static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String DAMAGE_EVENT  = "org/bukkit/event/entity/EntityDamageEvent";

    private static final String THREE_ARG_DESC =
        "(L" + SERVER_LEVEL + ";L" + DAMAGE_SOURCE + ";F)V";

    private static final String FOUR_ARG_DESC =
        "(L" + SERVER_LEVEL + ";L" + DAMAGE_SOURCE + ";F" + "L" + DAMAGE_EVENT + ";)Z";

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {

        if (className == null || !className.equals(LIVING_ENTITY)) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {

                private boolean hasThreeArgActuallyHurt = false;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ("actuallyHurt".equals(name) && THREE_ARG_DESC.equals(descriptor)) {
                        hasThreeArgActuallyHurt = true;
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                @Override
                public void visitEnd() {
                    if (!hasThreeArgActuallyHurt) {
                        injectThreeArgActuallyHurt(this);
                    }
                    super.visitEnd();
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[LivingEntityActuallyHurtCompat] Failed: " + t);
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Inject:
     *   protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
     *       EntityDamageEvent event = createFakeEvent(this, source, dmg);
     *       this.actuallyHurt(level, source, dmg, event);   // 4-arg overload của Paper
     *       // POP return value (4-arg return boolean, 3-arg return void)
     *   }
     */
    private static void injectThreeArgActuallyHurt(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PROTECTED,
            "actuallyHurt",
            THREE_ARG_DESC,
            null,
            null
        );
        mv.visitCode();

        // this (receiver cho method call)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        // level (arg1)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // source (arg2)
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        // dmg (arg3)
        mv.visitVarInsn(Opcodes.FLOAD, 3);

        // createFakeEvent(this, source, dmg)
        mv.visitVarInsn(Opcodes.ALOAD, 0);   // this
        mv.visitVarInsn(Opcodes.ALOAD, 2);   // source
        mv.visitVarInsn(Opcodes.FLOAD, 3);   // dmg
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "ankhangbo/fabricloader/asm/LivingEntityActuallyHurtCompatTransformer",
            "createFakeEvent",
            "(L" + LIVING_ENTITY + ";L" + DAMAGE_SOURCE + ";F)L" + DAMAGE_EVENT + ";",
            false
        );

        // this.actuallyHurt(level, source, dmg, event)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            LIVING_ENTITY,
            "actuallyHurt",
            FOUR_ARG_DESC,
            false
        );

        // pop return value (boolean)
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Tạo EntityDamageEvent giả để gọi overload 4 args của Paper/Leaf.
     * Paper/Leaf dùng event rất nhiều (isCancelled, getDamage, getDamage(Modifier)...)
     * nên KHÔNG thể push null.
     *
     * Constructor: EntityDamageEvent(Entity, DamageCause, double)
     */
    public static Object createFakeEvent(Object entity, Object source, float dmg) {
        try {
            Class<?> eventClass = Class.forName("org.bukkit.event.entity.EntityDamageEvent");
            Class<?> causeClass = Class.forName("org.bukkit.event.entity.EntityDamageEvent$DamageCause");
            Class<?> entityClass = Class.forName("org.bukkit.entity.Entity");

            Object bukkitEntity = entity.getClass().getMethod("getBukkitEntity").invoke(entity);
            Object cause = causeClass.getField("CUSTOM").get(null);

            return eventClass.getConstructor(entityClass, causeClass, double.class)
                    .newInstance(bukkitEntity, cause, (double) dmg);
        } catch (Throwable t) {
            System.err.println("[LivingEntityActuallyHurtCompat] createFakeEvent failed: " + t);
            t.printStackTrace();
            return null;
        }
    }
}