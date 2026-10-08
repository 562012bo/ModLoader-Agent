package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code org.bukkit.craftbukkit.event.CraftEventFactory#handleBlockDropItemEvent(Block,
 * BlockState, ServerPlayer, List)} tolerate a {@code null} {@code items} list, substituting an
 * empty one instead of letting the method's own {@code items.iterator()} throw
 * {@code NullPointerException}.
 *
 * <p>Root cause, confirmed against Leaf 26.1.2's real decompiled source: {@code
 * ServerPlayerGameMode#destroyBlock} sets {@code this.level.captureDrops = new ArrayList<>();},
 * runs the block's own destroy/drop logic, then reads {@code List itemsToDrop =
 * this.level.captureDrops;} back out and passes it straight into this method - all using ONE
 * shared, non-reentrant field on {@code Level} as the "current capture buffer", with no
 * stacking/nesting protection. If breaking this block triggers, as a side effect, breaking or
 * placing ANOTHER block that goes through the same capture path (a multi-part or reactive block a
 * mod added - e.g. attached signs, multi-block structures, physics-triggered neighbor breaks), that
 * INNER call finishes by setting {@code captureDrops} back to {@code null} once IT'S done - and by
 * the time the OUTER call reads the field back, it's already null, not the list it originally set.
 * This is a genuine reentrancy gap in Paper/Leaf's own drop-capturing mechanism, not something this
 * project's other patches caused, but it's severe enough (aborts handling the whole block-break
 * packet) to be worth tolerating here regardless of exactly which mod's block triggers the nested
 * capture.
 *
 * <p>Treating a null capture as "nothing was captured" (an empty list) is the safe, conservative
 * interpretation - the block still breaks; only the (already partially-consumed-by-the-inner-call)
 * drop-event bookkeeping for this call is skipped, rather than the entire packet handler crashing.
 */
public final class CraftEventFactoryNullItemsTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftEventFactoryNullItemsTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/event/CraftEventFactory";
	private static final String TARGET_METHOD = "handleBlockDropItemEvent";
	private static final String TARGET_DESC =
			"(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/block/state/BlockState;"
					+ "Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;)V";

	private static final int ITEMS_PARAM_INDEX = 3; // static method: block=0, state=1, player=2, items=3

	private static final class SafeClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;

		SafeClassWriter(int flags, ClassLoader targetLoader) {
			super(flags);
			this.targetLoader = targetLoader;
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			Class<?> class1;
			Class<?> class2;
			try {
				class1 = Class.forName(type1.replace('/', '.'), false, targetLoader);
				class2 = Class.forName(type2.replace('/', '.'), false, targetLoader);
			} catch (Throwable t) {
				return "java/lang/Object";
			}
			if (class1.isAssignableFrom(class2)) {
				return type1;
			}
			if (class2.isAssignableFrom(class1)) {
				return type2;
			}
			if (class1.isInterface() || class2.isInterface()) {
				return "java/lang/Object";
			}
			do {
				class1 = class1.getSuperclass();
			} while (!class1.isAssignableFrom(class2));
			return class1.getName().replace('.', '/');
		}
	}

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

			boolean patched = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD) || !method.desc.equals(TARGET_DESC)) {
					continue;
				}
				if (method.instructions.size() == 0) {
					continue;
				}

				LabelNode notNull = new LabelNode();
				InsnList guard = new InsnList();
				guard.add(new VarInsnNode(Opcodes.ALOAD, ITEMS_PARAM_INDEX));
				guard.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
				guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
						"()Ljava/util/List;", false));
				guard.add(new VarInsnNode(Opcodes.ASTORE, ITEMS_PARAM_INDEX));
				guard.add(notNull);

				method.instructions.insertBefore(method.instructions.getFirst(), guard);
				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD + " tolerate a null items list.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ "#" + TARGET_METHOD + " - leaving it unpatched", t);
			return null;
		}
	}
}