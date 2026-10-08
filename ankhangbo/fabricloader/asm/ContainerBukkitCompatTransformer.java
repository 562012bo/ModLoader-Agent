package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Supplies defaults for the rest of {@code net.minecraft.world.Container}'s CraftBukkit-added
 * abstract methods - {@code getViewers()}, {@code onOpen(CraftHumanEntity)},
 * {@code onClose(CraftHumanEntity)}, {@code getContents()}, {@code setMaxStackSize(int)} - for any
 * class that (transitively) implements {@code Container} but has no concrete implementation of one
 * anywhere in its resolvable ancestry. {@code getMaxStackSize()} is deliberately NOT handled here -
 * that one already has its own dedicated, separately-verified-working
 * {@link ContainerMaxStackSizeTransformer}; this class only covers the rest of the same family of
 * gaps, confirmed against the real decompiled {@code Container} interface (see its own javadoc for
 * the full list of abstract methods it declares - these five, plus {@code getMaxStackSize()}, are
 * the ones Paper's own patches added specifically for Bukkit integration; every VANILLA container
 * block entity implements all of them itself, individually, since not even the common
 * {@code BaseContainerBlockEntity} base class provides most of them - so a mod's own container
 * class that only implements the pre-existing vanilla Container methods (which it needs anyway just
 * to function) but skips these newer Bukkit-only additions ends up exactly as broken here as it was
 * for {@code getMaxStackSize()}: {@code AbstractMethodError} the moment anything calls one.
 *
 * <p>{@code getViewers()}/{@code onOpen()}/{@code onClose()} share one synthetic
 * {@code ankhangbo$viewers} field (a lazily-initialized {@code List}) injected into the class the
 * first time any of the three needs it, so they stay consistent with each other (opening adds to
 * it, closing removes, viewing reads it) - mirroring vanilla's own {@code transaction} field
 * pattern (confirmed against {@code ChestBlockEntity}'s real source). {@code getContents()} is
 * built as a real loop calling the class's own (guaranteed-present, ordinary, non-Paper-specific)
 * {@code getContainerSize()}/{@code getItem(int)} rather than returning an empty placeholder, since
 * those two are safe to assume present - any working Container implementation needs them regardless
 * of Paper. {@code setMaxStackSize(int)} is left a deliberate no-op stub - low-stakes and rarely
 * called - documented rather than wired up to a field, to avoid touching the already-verified-
 * working {@code getMaxStackSize()} fix in the sibling transformer.
 */
public final class ContainerBukkitCompatTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ContainerBukkitCompatTransformer");

	private static final String TARGET_INTERFACE = "net/minecraft/world/Container";
	private static final String VIEWERS_FIELD = "ankhangbo$viewers";

	private static final String[] METHOD_NAMES = { "getViewers", "onOpen", "onClose", "getContents", "setMaxStackSize" };
	private static final String[] METHOD_DESCS = {
			"()Ljava/util/List;",
			"(Lorg/bukkit/craftbukkit/entity/CraftHumanEntity;)V",
			"(Lorg/bukkit/craftbukkit/entity/CraftHumanEntity;)V",
			"()Ljava/util/List;",
			"(I)V"
	};

	private static final ConcurrentHashMap<String, String> SUPER_NAME_BY_CLASS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String[]> INTERFACES_BY_CLASS = new ConcurrentHashMap<>();
	// key: className + "#" + methodIndex -> 0 not declared, 1 abstract, 2 concrete
	private static final ConcurrentHashMap<String, Integer> OWN_METHOD_STATE = new ConcurrentHashMap<>();

	private static final class SafeClassWriter extends ClassWriter {
		SafeClassWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			try {
				return super.getCommonSuperClass(type1, type2);
			} catch (TypeNotPresentException | LinkageError e) {
				return "java/lang/Object";
			}
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) {
			return null;
		}

		try {
			ClassReader cheapReader = new ClassReader(classfileBuffer);
			String superName = cheapReader.getSuperName();
			String[] interfaces = cheapReader.getInterfaces();
			if (superName != null) {
				SUPER_NAME_BY_CLASS.put(className, superName);
			}
			if (interfaces != null && interfaces.length > 0) {
				INTERFACES_BY_CLASS.put(className, interfaces);
			}

			if (className.equals(TARGET_INTERFACE) || className.startsWith("org/bukkit/")) {
				return null;
			}
			if (!implementsContainer(className)) {
				return null;
			}

			ClassNode classNode = new ClassNode();
			cheapReader.accept(classNode, 0);

			if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
				return null;
			}

			boolean[] needsFix = new boolean[METHOD_NAMES.length];
			MethodNode[] existingNodes = new MethodNode[METHOD_NAMES.length];
			boolean anyFix = false;

			for (int i = 0; i < METHOD_NAMES.length; i++) {
				MethodNode existing = null;
				for (MethodNode m : (List<MethodNode>) classNode.methods) {
					if (m.name.equals(METHOD_NAMES[i]) && m.desc.equals(METHOD_DESCS[i])) {
						existing = m;
						break;
					}
				}
				existingNodes[i] = existing;
				OWN_METHOD_STATE.put(className + "#" + i, existing == null ? 0
						: ((existing.access & Opcodes.ACC_ABSTRACT) != 0 ? 1 : 2));

				boolean broken = existing != null
						? (existing.access & Opcodes.ACC_ABSTRACT) != 0
						: !resolvesConcretelyViaAncestors(superName, i);
				needsFix[i] = broken;
				anyFix |= broken;
			}

			if (!anyFix) {
				return null;
			}

			boolean needsViewersField = needsFix[0] || needsFix[1] || needsFix[2];
			if (needsViewersField && findField(classNode, VIEWERS_FIELD) == null) {
				classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
						VIEWERS_FIELD, "Ljava/util/List;", null, null));
			}

			String owner = className;
			for (int i = 0; i < METHOD_NAMES.length; i++) {
				if (!needsFix[i]) {
					continue;
				}
				InsnList body = buildBody(i, owner);
				if (existingNodes[i] != null) {
					existingNodes[i].access &= ~Opcodes.ACC_ABSTRACT;
					existingNodes[i].access |= Opcodes.ACC_PUBLIC;
					existingNodes[i].instructions = body;
					existingNodes[i].maxStack = 6;
					existingNodes[i].maxLocals = 4;
				} else {
					MethodNode fresh = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAMES[i], METHOD_DESCS[i], null, null);
					fresh.instructions = body;
					fresh.maxStack = 6;
					fresh.maxLocals = 4;
					classNode.methods.add(fresh);
				}
				OWN_METHOD_STATE.put(className + "#" + i, 2);
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Supplied default Container Bukkit-compat method(s) for " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to check/patch " + className
					+ " for missing Container Bukkit-compat methods - leaving it unpatched", t);
			return null;
		}
	}

	private static FieldNode findField(ClassNode classNode, String name) {
		for (Object o : classNode.fields) {
			FieldNode f = (FieldNode) o;
			if (f.name.equals(name)) {
				return f;
			}
		}
		return null;
	}

	/**
	 * @param methodIndex index into {@link #METHOD_NAMES}/{@link #METHOD_DESCS}: 0 getViewers,
	 *                     1 onOpen, 2 onClose, 3 getContents, 4 setMaxStackSize
	 */
	private static InsnList buildBody(int methodIndex, String owner) {
		InsnList il = new InsnList();
		switch (methodIndex) {
			case 0: { // List getViewers()
				emitEnsureViewersField(il, owner);
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VIEWERS_FIELD, "Ljava/util/List;"));
				il.add(new InsnNode(Opcodes.ARETURN));
				break;
			}
			case 1: { // void onOpen(CraftHumanEntity)
				emitEnsureViewersField(il, owner);
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VIEWERS_FIELD, "Ljava/util/List;"));
				il.add(new VarInsnNode(Opcodes.ALOAD, 1));
				il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
				il.add(new InsnNode(Opcodes.POP));
				il.add(new InsnNode(Opcodes.RETURN));
				break;
			}
			case 2: { // void onClose(CraftHumanEntity)
				emitEnsureViewersField(il, owner);
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VIEWERS_FIELD, "Ljava/util/List;"));
				il.add(new VarInsnNode(Opcodes.ALOAD, 1));
				il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "remove", "(Ljava/lang/Object;)Z", true));
				il.add(new InsnNode(Opcodes.POP));
				il.add(new InsnNode(Opcodes.RETURN));
				break;
			}
			case 3: { // List getContents() - real loop over getContainerSize()/getItem(int)
				LabelNode loop = new LabelNode();
				LabelNode end = new LabelNode();

				il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
				il.add(new InsnNode(Opcodes.DUP));
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getContainerSize", "()I", false));
				il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
				il.add(new VarInsnNode(Opcodes.ASTORE, 1));
				il.add(new InsnNode(Opcodes.ICONST_0));
				il.add(new VarInsnNode(Opcodes.ISTORE, 2));

				il.add(loop);
				il.add(new VarInsnNode(Opcodes.ILOAD, 2));
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getContainerSize", "()I", false));
				il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));

				il.add(new VarInsnNode(Opcodes.ALOAD, 1));
				il.add(new VarInsnNode(Opcodes.ALOAD, 0));
				il.add(new VarInsnNode(Opcodes.ILOAD, 2));
				il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getItem", "(I)Lnet/minecraft/world/item/ItemStack;", false));
				il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
				il.add(new InsnNode(Opcodes.POP));

				il.add(new IincInsnNode(2, 1));
				il.add(new JumpInsnNode(Opcodes.GOTO, loop));

				il.add(end);
				il.add(new VarInsnNode(Opcodes.ALOAD, 1));
				il.add(new InsnNode(Opcodes.ARETURN));
				break;
			}
			case 4: { // void setMaxStackSize(int) - no-op stub
				il.add(new InsnNode(Opcodes.RETURN));
				break;
			}
			default:
				throw new IllegalArgumentException("methodIndex " + methodIndex);
		}
		return il;
	}

	private static void emitEnsureViewersField(InsnList il, String owner) {
		LabelNode nonNull = new LabelNode();
		il.add(new VarInsnNode(Opcodes.ALOAD, 0));
		il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, VIEWERS_FIELD, "Ljava/util/List;"));
		il.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
		il.add(new VarInsnNode(Opcodes.ALOAD, 0));
		il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
		il.add(new InsnNode(Opcodes.DUP));
		il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
		il.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, VIEWERS_FIELD, "Ljava/util/List;"));
		il.add(nonNull);
	}

	private static boolean implementsContainer(String className) {
		Set<String> visited = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(className);
		int guard = 0;
		while (!queue.isEmpty() && guard++ < 2000) {
			String cur = queue.poll();
			if (cur == null || !visited.add(cur)) {
				continue;
			}
			if (cur.equals(TARGET_INTERFACE)) {
				return true;
			}
			String sup = SUPER_NAME_BY_CLASS.get(cur);
			if (sup != null) {
				queue.add(sup);
			}
			String[] ifaces = INTERFACES_BY_CLASS.get(cur);
			if (ifaces != null) {
				queue.addAll(Arrays.asList(ifaces));
			}
		}
		return false;
	}

	private static boolean resolvesConcretelyViaAncestors(String superName, int methodIndex) {
		String cur = superName;
		int hops = 0;
		while (cur != null && hops++ < 64) {
			Integer state = OWN_METHOD_STATE.get(cur + "#" + methodIndex);
			if (state != null) {
				if (state == 2) {
					return true;
				}
				if (state == 1) {
					return false;
				}
			}
			cur = SUPER_NAME_BY_CLASS.get(cur);
		}
		return false;
	}
}
