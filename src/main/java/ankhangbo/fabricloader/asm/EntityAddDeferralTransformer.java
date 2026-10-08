package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes {@code ERROR: Cannot update chunk status for entity GuardEntity[...] since entity chunk (x,z) is receiving
 * update} - an entity that a mod spawns from {@code ServerEntityEvents.ENTITY_LOAD} (GuardVillagers' guards) was
 * rejected by Moonrise and lost.
 *
 * <ul>
 *   <li>{@code ChunkEntitySlices#updateStatus(...)}: {@code EntityAddDeferral.enter()} first,
 *       {@code exit()} before every return and in a catch-all handler (try/finally).</li>
 *   <li>{@code ServerLevel#addEntity(Entity, SpawnReason)}: at the head,
 *       {@code if (EntityAddDeferral.shouldDefer()) { defer(this, entity, reason); return true; }}.</li>
 * </ul>
 * Hand-written frames only (F_FULL for the finally handler, built from the method descriptor; F_SAME after the head
 * check), so no class is loaded while these classes are being defined.
 */
public final class EntityAddDeferralTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.EntityAddDeferralTransformer");

	private static final String SLICES = "ca/spottedleaf/moonrise/patches/chunk_system/level/entity/ChunkEntitySlices";
	private static final String LEVEL = "net/minecraft/server/level/ServerLevel";
	private static final String HELPER = "ankhangbo/fabricloader/compat/EntityAddDeferral";
	private static final String ADD_DESC = "(Lnet/minecraft/world/entity/Entity;Lorg/bukkit/event/entity/CreatureSpawnEvent$SpawnReason;)Z";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!SLICES.equals(className) && !LEVEL.equals(className)) {
			return null;
		}
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);
			boolean patched = false;
			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (SLICES.equals(className) && m.name.equals("updateStatus") && m.instructions.size() > 0
						&& (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
					wrapUpdateStatus(cn, m);
					patched = true;
				} else if (LEVEL.equals(className) && m.name.equals("addEntity") && m.desc.equals(ADD_DESC)
						&& (m.access & Opcodes.ACC_STATIC) == 0) {
					guardAddEntity(m);
					patched = true;
				}
			}
			if (!patched) {
				LOGGER.warning(className + ": hook for entity-add deferral not found - NOT patched "
						+ "(different Moonrise/Leaf version?)");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (entity-add deferral)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	private static MethodInsnNode call(String name) {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, "()V", false);
	}

	private static void wrapUpdateStatus(ClassNode cn, MethodNode m) {
		// exit() before every return
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			int op = insn.getOpcode();
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				m.instructions.insertBefore(insn, call("exit"));
			}
		}
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		m.instructions.insert(start);
		m.instructions.insert(call("enter")); // runs before 'start'
		m.instructions.add(end);

		List<Object> locals = new ArrayList<>();
		if ((m.access & Opcodes.ACC_STATIC) == 0) {
			locals.add(cn.name);
		}
		for (Type t : Type.getArgumentTypes(m.desc)) {
			switch (t.getSort()) {
				case Type.BOOLEAN:
				case Type.BYTE:
				case Type.CHAR:
				case Type.SHORT:
				case Type.INT:
					locals.add(Opcodes.INTEGER);
					break;
				case Type.FLOAT:
					locals.add(Opcodes.FLOAT);
					break;
				case Type.LONG:
					locals.add(Opcodes.LONG);
					break;
				case Type.DOUBLE:
					locals.add(Opcodes.DOUBLE);
					break;
				case Type.ARRAY:
					locals.add(t.getDescriptor());
					break;
				default:
					locals.add(t.getInternalName());
			}
		}
		InsnList h = new InsnList();
		h.add(handler);
		h.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 1, new Object[] { "java/lang/Throwable" }));
		h.add(call("exit"));
		h.add(new InsnNode(Opcodes.ATHROW));
		m.instructions.add(h);
		m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
	}

	private static void guardAddEntity(MethodNode m) {
		LabelNode go = new LabelNode();
		InsnList add = new InsnList();
		add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "shouldDefer", "()Z", false));
		add.add(new JumpInsnNode(Opcodes.IFEQ, go));
		add.add(new VarInsnNode(Opcodes.ALOAD, 0));
		add.add(new VarInsnNode(Opcodes.ALOAD, 1));
		add.add(new VarInsnNode(Opcodes.ALOAD, 2));
		add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "defer",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
		add.add(new InsnNode(Opcodes.ICONST_1));
		add.add(new InsnNode(Opcodes.IRETURN));
		add.add(go);
		add.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		m.instructions.insert(add);
	}
}