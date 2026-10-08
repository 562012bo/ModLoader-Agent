package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code org.bukkit.craftbukkit.event.CraftEventFactory#callEntityPlaceEvent(...)} tolerate
 * a modded entity that has no Bukkit {@code EntityType} equivalent, instead of letting the whole
 * player interaction (placing a boat, a modded vehicle, etc.) fail.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source (and, after an
 * earlier version of this transformer produced a {@code VerifyError}, against the real compiled
 * bytecode of the method too - see the note below):
 * <ol>
 *   <li>{@code callEntityPlaceEvent(Level, BlockPos, Direction, Player, Entity, InteractionHand)}
 *       builds a Bukkit {@code EntityPlaceEvent} by calling {@code entity.getBukkitEntity()} on the
 *       about-to-be-placed entity (e.g. a boat), as one of the constructor's arguments:
 *       {@code new EntityPlaceEvent(entity.getBukkitEntity(), cplayer, clickedBlock, blockFace, ...)}.</li>
 *   <li>{@code Entity.getBukkitEntity()} delegates to {@code CraftEntity.getEntity(server, this)},
 *       which resolves the entity's Bukkit wrapper class via
 *       {@code CraftEntityType.minecraftToBukkit(entity.getType())}.</li>
 *   <li>{@code CraftEntityType.minecraftToBukkit} looks the NMS entity type up in
 *       {@code Registry.ENTITY_TYPE} by namespaced key and throws {@code IllegalArgumentException}
 *       the moment that lookup returns null - which it always will for an entity type a mod
 *       registered (e.g. a modded boat variant), since Bukkit's {@code EntityType} enum has never
 *       heard of it. Unlike blocks (which fall back to {@code CraftBlockStates.DEFAULT_FACTORY}
 *       when unmapped), Bukkit has no generic entity wrapper to fall back to here.</li>
 *   <li>That exception propagates out of the constructor-argument expression, so the event is
 *       never fired, the calling code (e.g. {@code BoatItem.use}, which checks
 *       {@code callEntityPlaceEvent(...).isCancelled()}) never gets a result back, and the whole
 *       packet is dropped ("suppressing error") - the interaction silently fails every time for
 *       that modded entity.</li>
 * </ol>
 *
 * <p><b>Why this isn't just "wrap the getBukkitEntity() call in a try/catch":</b> the call sits in
 * the middle of {@code NEW EntityPlaceEvent; DUP; entity.getBukkitEntity(); <rest of the
 * constructor args>; INVOKESPECIAL &lt;init&gt;}. By the time {@code getBukkitEntity()} runs, the
 * operand stack already holds the two uninitialized {@code EntityPlaceEvent} references pushed by
 * {@code NEW}/{@code DUP}. Per the JVM spec, when an exception is thrown the operand stack is
 * unwound down to nothing but the exception object - those two pending references are gone for
 * good and cannot be "resumed". A try/catch placed directly around just that one call therefore
 * has an exception-path stack (1 item: the caught exception, then substituted null) that can never
 * match the normal-path stack (3 items: the two pending refs plus the call's result) at the merge
 * point right after it, which the verifier correctly rejects as
 * {@code VerifyError: Instruction type does not match stack map}.
 *
 * <p>The fix instead evaluates {@code entity.getBukkitEntity()} up front, at the very start of the
 * method (where the operand stack is genuinely empty and a try/catch is always safe), stores the
 * result - or {@code null} on failure - into a fresh local variable, and replaces the original
 * {@code entity.getBukkitEntity()} call site with a plain load of that local variable. That
 * substitution has the exact same stack effect (pushes one reference) as the two instructions it
 * replaces, so it introduces no new branching and disturbs nothing around it.
 */
public final class CraftEntityPlaceEventNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftEntityPlaceEventNullSafeTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/event/CraftEventFactory";
	private static final String TARGET_METHOD = "callEntityPlaceEvent";
	private static final String TARGET_DESC = "(Lnet/minecraft/world/level/Level;"
			+ "Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/core/Direction;"
			+ "Lnet/minecraft/world/entity/player/Player;"
			+ "Lnet/minecraft/world/entity/Entity;"
			+ "Lnet/minecraft/world/InteractionHand;)"
			+ "Lorg/bukkit/event/entity/EntityPlaceEvent;";

	// Confirmed against the real compiled method: level=0, clickedPos=1, clickedFace=2, player=3,
	// entity=4, hand=5 (all reference types, one slot each, static method - no "this").
	private static final int ENTITY_PARAM_INDEX = 4;

	private static final String CALL_OWNER = "net/minecraft/world/entity/Entity";
	private static final String CALL_NAME = "getBukkitEntity";
	private static final String CALL_DESC = "()Lorg/bukkit/craftbukkit/entity/CraftEntity;";

	/**
	 * A {@link ClassWriter} that resolves common superclasses using the *target* class's own
	 * classloader (the one passed into {@link ClassFileTransformer#transform}), not this agent's
	 * own classloader. See the class-level note in the previous revision of this file for why that
	 * matters - the short version is that {@code COMPUTE_FRAMES} recomputes frames for the whole
	 * class, so resolving types with the wrong classloader here can corrupt an unrelated method's
	 * frames elsewhere in the same class file.
	 */
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

				MethodInsnNode target = null;
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call
							&& call.getOpcode() == Opcodes.INVOKEVIRTUAL
							&& call.owner.equals(CALL_OWNER)
							&& call.name.equals(CALL_NAME)
							&& call.desc.equals(CALL_DESC)) {
						target = call;
						break;
					}
				}
				if (target == null) {
					LOGGER.warning("Could not locate the getBukkitEntity() call in "
							+ className + "#" + TARGET_METHOD + " - leaving it unpatched");
					continue;
				}

				// The call site must be a plain "ALOAD entity ; INVOKEVIRTUAL getBukkitEntity()" -
				// if it's anything more complex than that, our substitution below wouldn't have the
				// same stack effect, so bail out rather than risk another VerifyError.
				AbstractInsnNode entityLoad = target.getPrevious();
				if (!(entityLoad instanceof VarInsnNode loadVar)
						|| loadVar.getOpcode() != Opcodes.ALOAD
						|| loadVar.var != ENTITY_PARAM_INDEX) {
					LOGGER.warning("getBukkitEntity() call site in " + className + "#" + TARGET_METHOD
							+ " wasn't the expected simple 'ALOAD entity' shape - leaving it unpatched");
					continue;
				}

				// A fresh local variable slot, guaranteed unused by the original method.
				int resultVar = method.maxLocals;
				method.maxLocals = resultVar + 1;

				// Replace "ALOAD entity ; INVOKEVIRTUAL getBukkitEntity()" (pushes one reference)
				// with "ALOAD resultVar" (also pushes exactly one reference) - same stack effect,
				// no new branching at this point in the method.
				VarInsnNode loadResult = new VarInsnNode(Opcodes.ALOAD, resultVar);
				method.instructions.insertBefore(entityLoad, loadResult);
				method.instructions.remove(entityLoad);
				method.instructions.remove(target);

				// Compute resultVar up front, at the very start of the method, where the operand
				// stack is empty and a try/catch is always valid.
				LabelNode tryStart = new LabelNode();
				LabelNode tryEnd = new LabelNode();
				LabelNode handlerStart = new LabelNode();
				LabelNode after = new LabelNode();

				InsnList prefix = new InsnList();
				prefix.add(tryStart);
				prefix.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALL_OWNER, CALL_NAME, CALL_DESC, false));
				prefix.add(tryEnd);
				prefix.add(new VarInsnNode(Opcodes.ASTORE, resultVar));
				prefix.add(new JumpInsnNode(Opcodes.GOTO, after));
				prefix.add(handlerStart);
				prefix.add(new InsnNode(Opcodes.POP)); // discard the caught Throwable
				prefix.add(new InsnNode(Opcodes.ACONST_NULL)); // fall back to a null bukkit entity
				prefix.add(new VarInsnNode(Opcodes.ASTORE, resultVar));
				prefix.add(after);
				method.instructions.insert(prefix);

				method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handlerStart, "java/lang/Throwable"));

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " tolerate a modded entity with no Bukkit EntityType equivalent.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate an unmapped entity in " + TARGET_METHOD
					+ " - leaving it unpatched", t);
			return null;
		}
	}
}