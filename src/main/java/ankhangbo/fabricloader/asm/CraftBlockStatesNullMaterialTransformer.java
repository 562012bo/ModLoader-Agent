package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code org.bukkit.craftbukkit.block.CraftBlockStates}'s private
 * {@code register(Material, BlockStateFactory)} tolerate a {@code null} {@code Material} instead
 * of crashing the class's own static initializer.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source:
 * <ol>
 *   <li>{@code CraftBlockStates}'s {@code <clinit>} calls the 3-arg {@code register(BlockEntityType,
 *       Class, BiFunction)} overload for every vanilla block-entity type it knows about. That
 *       overload loops over {@code blockEntityType.validBlocks} and, for each one, calls
 *       {@code register(CraftBlockType.minecraftToBukkit(block), factory)}.</li>
 *   <li>{@code CraftBlockType.minecraftToBukkit(block)} returns {@code null} for any block a mod
 *       registered (Bukkit's {@code Material} enum has no constant for it) - this is the exact same
 *       "modded block/item has no Material" root cause {@code NullSafeMaterial}/
 *       {@code NullSafeMaterialTransformer} already handle for {@code Material.isLegacy()} call
 *       sites, just showing up at a different one. Some vanilla block-entity type's
 *       {@code validBlocks} list gets extended by a mod (directly or via a mixin/registry event),
 *       so this modded block ends up in the loop.</li>
 *   <li>The 2-arg {@code register(Material, BlockStateFactory)} overload does
 *       {@code FACTORIES.put(blockType, factory)} unconditionally, where {@code FACTORIES} is an
 *       {@code EnumMap<Material, ...>}. Unlike a {@code HashMap}, {@code EnumMap} throws
 *       {@code NullPointerException} on a null key ({@code EnumMap.typeCheck} calls
 *       {@code key.getClass()} before anything else) - so this one modded block crashes the whole
 *       static initializer.</li>
 *   <li>Because it's the class {@code <clinit>}, the failure is wrapped as
 *       {@code ExceptionInInitializerError} and re-thrown to whichever code happened to touch
 *       {@code CraftBlockStates} first - in practice this can be almost anything (block-spread
 *       ticking, block-break handling, etc.), so the crash's visible stack trace often has nothing
 *       to do with the actual modded block that caused it. Once a class fails to initialize this
 *       way, the JVM permanently marks it erroneous - every later access throws
 *       {@code NoClassDefFoundError} for the rest of the JVM's life, so this doesn't just log once
 *       and recover; it keeps crashing (or blocking block-state creation entirely) for as long as
 *       the server runs.</li>
 * </ol>
 *
 * <p>The correct fix is for {@code register(Material, BlockStateFactory)} to simply skip
 * registering when {@code blockType} is null: a modded block with no {@code Material} equivalent
 * has nothing to register a factory under anyway - it will fall back to
 * {@code CraftBlockStates}'s own {@code DEFAULT_FACTORY} wherever it's looked up later (via
 * {@code getFactory}), which is already designed to handle unmapped materials generically. This
 * transformer inserts that null check as the very first thing the method does:
 * <pre>
 *   ALOAD 0        // blockType
 *   IFNONNULL L1
 *   RETURN
 *   L1:
 *   ... (method's original bytecode, unchanged) ...
 * </pre>
 */
public final class CraftBlockStatesNullMaterialTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftBlockStatesNullMaterialTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/block/CraftBlockStates";
	private static final String TARGET_METHOD = "register";
	private static final String TARGET_DESC =
			"(Lorg/bukkit/Material;Lorg/bukkit/craftbukkit/block/CraftBlockStates$BlockStateFactory;)V";

	/**
	 * A {@link ClassWriter} that resolves common superclasses using the *target* class's own
	 * classloader (the one passed into {@link ClassFileTransformer#transform}), not this agent's
	 * own classloader.
	 *
	 * <p>{@code ClassWriter}'s default {@code getCommonSuperClass} resolves types via
	 * {@code getClass().getClassLoader()} - i.e. whichever classloader loaded this transformer
	 * class itself (the javaagent's classloader). That classloader cannot see server/Bukkit/NMS
	 * classes at all, so every lookup for a Bukkit or NMS type throws, and a naive catch-and-return
	 * "java/lang/Object" fallback (as an earlier version of this class did) papers over the
	 * exception but returns the *wrong* common supertype. {@code COMPUTE_FRAMES} recomputes stack
	 * map frames for *every* method in the whole class being rewritten - not just the one method a
	 * transformer actually modifies - so a wrong answer here can silently corrupt the frames of a
	 * completely unrelated method elsewhere in the same class file. The JVM verifier then rejects
	 * that unrelated method the next time the class is loaded, with a confusing
	 * {@code VerifyError} that has nothing to do with what was actually changed.
	 *
	 * <p>Using the class's real classloader lets these lookups resolve correctly instead, so
	 * frames only fall back to Object when that's actually the correct common supertype.
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
				// Truly unresolvable (e.g. a type from a mod jar on yet another classloader) -
				// Object is the only safe answer left, same as ASM's own fallback behaviour.
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

				LabelNode notNull = new LabelNode();
				InsnList guard = new InsnList();
				guard.add(new VarInsnNode(Opcodes.ALOAD, 0)); // blockType (first param, static method)
				guard.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
				guard.add(new InsnNode(Opcodes.RETURN));
				guard.add(notNull);
				method.instructions.insert(guard);

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#register(Material, BlockStateFactory) "
					+ "tolerate a null Material (modded block with no Bukkit Material equivalent).");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate a null Material in register() - leaving it unpatched", t);
			return null;
		}
	}
}