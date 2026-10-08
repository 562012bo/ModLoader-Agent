package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
 * Supplies a default {@code public int getMaxStackSize() { return 64; }} for any class that
 * (transitively) implements {@code net.minecraft.world.Container} but has no concrete
 * implementation of that method anywhere in its resolvable ancestry.
 *
 * <p>Unlike {@link AbstractContainerMenuBukkitViewTransformer}'s target, this one is not a
 * Paper/CraftBukkit-specific gap - {@code Container#getMaxStackSize()} (the no-arg overload) is
 * genuinely abstract in plain vanilla, with no default anywhere, not even on the common
 * {@code BaseContainerBlockEntity} base class most container block entities extend (confirmed
 * against the real decompiled source: every single vanilla container block entity - chests,
 * furnaces, barrels, hoppers, ... - implements this method itself). A mod's own container class
 * that fails to do the same (as {@code toughasnails.block.entity.ThermoregulatorBlockEntity} does)
 * is an otherwise-concrete class missing one required method: {@code AbstractMethodError} the
 * instant anything calls {@code getMaxStackSize()} on it (any inventory click involving one of its
 * slots, in particular, always does, via {@code Slot#getMaxStackSize()}). 64 is what the vast
 * majority of vanilla implementations return anyway, so it's a safe, unsurprising default.
 *
 * <p>If the class already declares its own {@code getMaxStackSize()I} but it's abstract (the
 * ThermoregulatorBlockEntity case), the existing method is given a body in place rather than a
 * second method of the same signature being added (which would be invalid bytecode). If the class
 * declares no such method at all, one is added fresh.
 *
 * <p>Tracks {@code className -> superName} and {@code className -> interfaces} for every class
 * seen (cheap, header-only reads) to determine "(transitively) implements Container" without ever
 * triggering an out-of-order classload - see {@link AbstractContainerMenuBukkitViewTransformer}'s
 * javadoc for why that's safe here for the same reason (ancestors always finish loading, and so
 * always pass through this same transformer, before any of their descendants are defined). Also
 * tracks, per class that passed that cheap filter, whether THAT class's own declaration of
 * {@code getMaxStackSize()I} (if any) is concrete or abstract, so a descendant class can resolve
 * "is this inherited method usable" by walking cached ancestor data alone, no reflection needed.
 */
public final class ContainerMaxStackSizeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ContainerMaxStackSizeTransformer");

	private static final String TARGET_INTERFACE = "net/minecraft/world/Container";
	private static final String METHOD_NAME = "getMaxStackSize";
	private static final String METHOD_DESC = "()I";

	private static final ConcurrentHashMap<String, String> SUPER_NAME_BY_CLASS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String[]> INTERFACES_BY_CLASS = new ConcurrentHashMap<>();

	// 0 = class doesn't declare its own getMaxStackSize()I, 1 = declares it abstract, 2 = declares it concrete.
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

			MethodNode existing = null;
			for (MethodNode m : (List<MethodNode>) classNode.methods) {
				if (m.name.equals(METHOD_NAME) && m.desc.equals(METHOD_DESC)) {
					existing = m;
					break;
				}
			}
			OWN_METHOD_STATE.put(className, existing == null ? 0
					: ((existing.access & Opcodes.ACC_ABSTRACT) != 0 ? 1 : 2));

			boolean needsFix = existing != null
					? (existing.access & Opcodes.ACC_ABSTRACT) != 0
					: !resolvesConcretelyViaAncestors(superName);
			if (!needsFix) {
				return null;
			}

			InsnList body = new InsnList();
			body.add(new IntInsnNode(Opcodes.BIPUSH, 64));
			body.add(new InsnNode(Opcodes.IRETURN));

			if (existing != null) {
				existing.access &= ~Opcodes.ACC_ABSTRACT;
				existing.access |= Opcodes.ACC_PUBLIC;
				existing.instructions = body;
				existing.maxStack = 1;
				existing.maxLocals = 1;
			} else {
				MethodNode fresh = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, METHOD_DESC, null, null);
				fresh.instructions = body;
				fresh.maxStack = 1;
				fresh.maxLocals = 1;
				classNode.methods.add(fresh);
			}
			OWN_METHOD_STATE.put(className, 2);

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Supplied a default getMaxStackSize() (returns 64) for " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to check/patch " + className
					+ " for a missing Container#getMaxStackSize() - leaving it unpatched", t);
			return null;
		}
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

	private static boolean resolvesConcretelyViaAncestors(String superName) {
		String cur = superName;
		int hops = 0;
		while (cur != null && hops++ < 64) {
			Integer state = OWN_METHOD_STATE.get(cur);
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
