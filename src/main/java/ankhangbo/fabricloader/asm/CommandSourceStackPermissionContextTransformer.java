package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes {@code [voicechat] Failed to use fabric-permission-api-v1 / NullPointerException: ...
 * PermissionContextOwner.getPermissionContext() is null} (thrown while Paper's async command builder asks
 * {@code CommandNode#canUse(source)}).
 *
 * <p>fabric-permission-api-v1's {@code CommandSourceStackMixin} keeps its state in three {@code @Unique} fields
 * (context / sourceType / sourceUuid) that are only assigned by field initialisers Mixin copies into the
 * constructors. For a source that did not go through those constructors the three getters return null.
 *
 * <p>Every getter is rewritten to {@code field != null ? field : default}, where the default is what the constructor
 * would have produced: a fresh {@code CommandPermissionContext(this)} (stored, so it is created once; the field's
 * {@code final} flag is dropped for that), {@code PermissionContext.Type.SYSTEM}, {@code Util.NIL_UUID}. The field
 * is read out of the getter's own GETFIELD, so it works whatever name Mixin gave the {@code @Unique} member.
 * Hand-written F_SAME1 frames; nothing is loaded while the class is being defined.
 */
public final class CommandSourceStackPermissionContextTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CommandSourceStackPermissionContextTransformer");

	private static final String TARGET = "net/minecraft/commands/CommandSourceStack";
	private static final String CONTEXT = "net/fabricmc/fabric/api/permission/v1/PermissionContext";
	private static final String CONTEXT_IMPL = "net/fabricmc/fabric/impl/permission/CommandPermissionContext";

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
			int patched = 0;
			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if ((m.access & Opcodes.ACC_STATIC) != 0) {
					continue;
				}
				if (m.name.equals("getPermissionContext") && m.desc.equals("()L" + CONTEXT + ";")) {
					patched += rewrite(cn, m, "L" + CONTEXT + ";", CONTEXT, Kind.CONTEXT);
				} else if (m.name.equals("fabric_getType") && m.desc.equals("()L" + CONTEXT + "$Type;")) {
					patched += rewrite(cn, m, "L" + CONTEXT + "$Type;", CONTEXT + "$Type", Kind.TYPE);
				} else if (m.name.equals("fabric_getUuid") && m.desc.equals("()Ljava/util/UUID;")) {
					patched += rewrite(cn, m, "Ljava/util/UUID;", "java/util/UUID", Kind.UUID);
				}
			}
			if (patched == 0) {
				return null; // fabric-permission-api-v1 not present
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED CommandSourceStack: permission context getters never return null (" + patched + " getter(s))");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	private enum Kind { CONTEXT, TYPE, UUID }

	@SuppressWarnings("unchecked")
	private static int rewrite(ClassNode cn, MethodNode m, String fieldDesc, String frameType, Kind kind) {
		FieldInsnNode field = null;
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn.getOpcode() == Opcodes.GETFIELD && ((FieldInsnNode) insn).desc.equals(fieldDesc)) {
				field = (FieldInsnNode) insn;
				break;
			}
		}
		if (field == null) {
			return 0;
		}
		LabelNode done = new LabelNode();
		InsnList b = new InsnList();
		b.add(new VarInsnNode(Opcodes.ALOAD, 0));
		b.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
		b.add(new InsnNode(Opcodes.DUP));
		b.add(new JumpInsnNode(Opcodes.IFNONNULL, done));
		b.add(new InsnNode(Opcodes.POP));
		switch (kind) {
			case CONTEXT:
				for (FieldNode f : (List<FieldNode>) cn.fields) {
					if (f.name.equals(field.name) && f.desc.equals(field.desc)) {
						f.access &= ~Opcodes.ACC_FINAL; // we assign it outside a constructor
					}
				}
				b.add(new VarInsnNode(Opcodes.ALOAD, 0));
				b.add(new TypeInsnNode(Opcodes.NEW, CONTEXT_IMPL));
				b.add(new InsnNode(Opcodes.DUP));
				b.add(new VarInsnNode(Opcodes.ALOAD, 0));
				b.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CONTEXT_IMPL, "<init>",
						"(Lnet/minecraft/commands/CommandSourceStack;)V", false));
				b.add(new InsnNode(Opcodes.DUP_X1)); // [ctx, this, ctx]
				b.add(new FieldInsnNode(Opcodes.PUTFIELD, field.owner, field.name, field.desc));
				break;
			case TYPE:
				b.add(new FieldInsnNode(Opcodes.GETSTATIC, CONTEXT + "$Type", "SYSTEM", "L" + CONTEXT + "$Type;"));
				break;
			default:
				b.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/util/Util", "NIL_UUID", "Ljava/util/UUID;"));
		}
		b.add(done);
		b.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { frameType }));
		b.add(new InsnNode(Opcodes.ARETURN));
		m.instructions = b;
		m.tryCatchBlocks.clear();
		m.localVariables = null;
		return 1;
	}
}