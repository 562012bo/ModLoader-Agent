package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
 * Adds a {@code default boolean contains(String key)} method back onto
 * {@code net.minecraft.world.level.storage.ValueInput}, restoring the one presence-check method a
 * mod calling the old {@code CompoundTag}-based save/load API expects, which the newer typed
 * {@code ValueInput} abstraction dropped entirely.
 *
 * <p>Root cause, confirmed against Leaf 26.1.2's real decompiled source: {@code ValueInput} (the
 * interface passed into every entity/block-entity's {@code readAdditionalSaveData}/{@code load})
 * exposes only TYPED accessors - {@code getBooleanOr}, {@code getIntOr}, {@code Optional<Integer>
 * getInt}, {@code getString}, etc. - and no generic, type-agnostic "does this key exist at all"
 * method. Mods still written against the older {@code CompoundTag}-based save API (which DOES have
 * a real {@code contains(String)}) - such as Guard Villagers' {@code GuardEntity.readAdditionalSaveData}
 * - call {@code input.contains(key)} expecting that same method, and get
 * {@code NoSuchMethodError} instead: not a version mismatch on an existing method (like the
 * {@code ItemStack#hurtAndBreak} fix), but a method that was simply never carried over to the new
 * abstraction at all.
 *
 * <p>The fix delegates to the real, underlying {@code CompoundTag} when one is available:
 * {@code TagValueInput} (confirmed against the decompiled source to be the concrete
 * implementation actually used for ordinary entity/block-entity save data) holds its backing data
 * in a plain public field, {@code TagValueInput#input}, of type {@code CompoundTag} - which still
 * has the exact same real {@code contains(String)} method the mod expects. So: if {@code this} is a
 * {@code TagValueInput}, answer from its backing {@code CompoundTag} directly (exact, correct
 * behaviour, not a guess); for any other {@code ValueInput} implementation this project doesn't
 * know the internals of, fall back to {@code false} (conservatively "not present" - a
 * false-negative here just means the mod treats the key as absent and uses its own default, which
 * is safe, rather than crashing outright).
 */
public final class ValueInputContainsCompatTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ValueInputContainsCompatTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/level/storage/ValueInput";
	private static final String METHOD_NAME = "contains";
	private static final String METHOD_DESC = "(Ljava/lang/String;)Z";

	private static final String TAG_VALUE_INPUT_CLASS = "net/minecraft/world/level/storage/TagValueInput";
	private static final String TAG_VALUE_INPUT_FIELD = "input";
	private static final String COMPOUND_TAG_CLASS = "net/minecraft/nbt/CompoundTag";

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

			for (MethodNode m : (List<MethodNode>) classNode.methods) {
				if (m.name.equals(METHOD_NAME) && m.desc.equals(METHOD_DESC)) {
					// Already present (a different server build, or an already-reverted API
					// change) - nothing to do.
					return null;
				}
			}

			LabelNode notTagInput = new LabelNode();

			MethodNode contains = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, METHOD_DESC, null, null);
			InsnList il = contains.instructions;
			il.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
			il.add(new TypeInsnNode(Opcodes.INSTANCEOF, TAG_VALUE_INPUT_CLASS));
			il.add(new JumpInsnNode(Opcodes.IFEQ, notTagInput));
			il.add(new VarInsnNode(Opcodes.ALOAD, 0));
			il.add(new TypeInsnNode(Opcodes.CHECKCAST, TAG_VALUE_INPUT_CLASS));
			il.add(new FieldInsnNode(Opcodes.GETFIELD, TAG_VALUE_INPUT_CLASS, TAG_VALUE_INPUT_FIELD,
					"L" + COMPOUND_TAG_CLASS + ";"));
			il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // key
			il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPOUND_TAG_CLASS, "contains",
					"(Ljava/lang/String;)Z", false));
			il.add(new InsnNode(Opcodes.IRETURN));
			il.add(notTagInput);
			il.add(new InsnNode(Opcodes.ICONST_0));
			il.add(new InsnNode(Opcodes.IRETURN));
			contains.maxStack = 2;
			contains.maxLocals = 2;
			classNode.methods.add(contains);

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Added a contains(String) default method to " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to add contains(String) to " + className
					+ " - leaving it unpatched", t);
			return null;
		}
	}
}