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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Restores {@code net.minecraft.world.item.ItemStack#hurtAndBreak(int, ServerLevel, ServerPlayer,
 * Consumer<Item>)} as a thin bridge to the method Paper actually ships now.
 *
 * <p>Paper's own patch (confirmed against the real Paper 26.1.2 patch diff the server owner
 * provided) widened this method's third parameter from {@code ServerPlayer} to {@code
 * LivingEntity} - a source-compatible change (any caller passing a {@code ServerPlayer} still
 * compiles fine against the new signature, since {@code ServerPlayer} IS-A {@code LivingEntity}),
 * but NOT a binary-compatible one: the JVM resolves an already-compiled {@code INVOKEVIRTUAL} by
 * its exact descriptor, and {@code (ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server
 * /level/ServerPlayer;Ljava/util/function/Consumer;)V} is simply gone from the class file now, only
 * the {@code LivingEntity}-typed descriptor exists. A mod compiled against the old vanilla
 * signature (such as ToughAsNails' {@code FilledCanteenItem}) throws {@code NoSuchMethodError} the
 * instant it calls the overload it was built against.
 *
 * <p>Since the two versions are behaviourally identical for a {@code ServerPlayer} argument (it's
 * always been widened, never re-typed to mean something different), the correct fix - rather than
 * redirecting call sites - is simply to give {@code ItemStack} BOTH overloads again: add the old
 * 4-arg {@code ServerPlayer}-typed method back (if it isn't already present) as a one-line bridge
 * that just calls the current {@code LivingEntity}-typed one with the same arguments. This is
 * ordinary method overloading, entirely between two descriptors that differ in their third
 * parameter's type, so it coexists with the existing method with no conflict - every OTHER old mod
 * built against the pre-Paper-patch signature is fixed the same way this one call site was, with no
 * per-mod or per-call-site handling needed.
 */
public final class ItemStackHurtAndBreakCompatTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ItemStackHurtAndBreakCompatTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/item/ItemStack";
	private static final String METHOD_NAME = "hurtAndBreak";

	private static final String OLD_DESC =
			"(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V";
	private static final String NEW_DESC =
			"(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET_CLASS.equals(className)) {
			return null;
		}

		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(classfileBuffer).accept(classNode, 0);

			boolean hasOld = false;
			boolean hasNew = false;
			for (MethodNode m : (List<MethodNode>) classNode.methods) {
				if (!m.name.equals(METHOD_NAME)) {
					continue;
				}
				if (m.desc.equals(OLD_DESC)) {
					hasOld = true;
				}
				if (m.desc.equals(NEW_DESC)) {
					hasNew = true;
				}
			}

			if (hasOld) {
				// Already present (a different server build, or an already-reverted patch) - nothing to do.
				return null;
			}
			if (!hasNew) {
				LOGGER.warning(TARGET_CLASS + " has neither the old (ServerPlayer) nor the new "
						+ "(LivingEntity) hurtAndBreak(int,ServerLevel,_,Consumer) overload on this "
						+ "server build - cannot bridge old mod calls to it.");
				return null;
			}

			MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, OLD_DESC, null, null);
			InsnList il = bridge.instructions;
			il.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
			il.add(new VarInsnNode(Opcodes.ILOAD, 1)); // amount
			il.add(new VarInsnNode(Opcodes.ALOAD, 2)); // level
			il.add(new VarInsnNode(Opcodes.ALOAD, 3)); // player (ServerPlayer, widens to LivingEntity for free)
			il.add(new VarInsnNode(Opcodes.ALOAD, 4)); // onBreak
			il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_CLASS, METHOD_NAME, NEW_DESC, false));
			il.add(new InsnNode(Opcodes.RETURN));
			bridge.maxStack = 5;
			bridge.maxLocals = 5;
			classNode.methods.add(bridge);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Restored the old ServerPlayer-typed hurtAndBreak(...) overload on " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to restore the old hurtAndBreak(...) overload on "
					+ className + " - leaving it unpatched", t);
			return null;
		}
	}
}