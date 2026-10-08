package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Injects a generic {@code getBukkitView()} override into any concrete class that (transitively)
 * extends {@code net.minecraft.world.inventory.AbstractContainerMenu} but doesn't already declare
 * one of its own.
 *
 * <p>Paper's own source patches make {@code getBukkitView()} abstract on {@code
 * AbstractContainerMenu} and give every VANILLA subclass (crafting, anvil, furnace, ...) its own
 * concrete, per-type override - that's how real CraftBukkit ships. A mod's own menu class (e.g.
 * ToughAsNails' Thermoregulator menu) that directly extends {@code AbstractContainerMenu} is not
 * something Paper's patcher has ever heard of, so it never gets one: an otherwise-perfectly-normal
 * concrete class is left with one unimplemented abstract method, and the very first call to
 * {@code getBukkitView()} on it (opening the mod's GUI, always) throws {@code AbstractMethodError}
 * and crashes whatever triggered it.
 *
 * <p>Every generated override is a one-liner - {@code return (InventoryView)
 * GenericMenuBukkitView.getOrCreateView(this);} - delegating the actual (best-effort, reflective)
 * view construction to {@link ankhangbo.fabricloader.compat.GenericMenuBukkitView}. See that
 * class's own javadoc for why its method signature is erased to {@code Object} throughout: the
 * {@code CHECKCAST} back to {@code org/bukkit/inventory/InventoryView} emitted here is the only
 * place a real Bukkit type is named in the injected bytecode, and it resolves correctly because it
 * lives inside the patched (modded menu) class's own classloader, never this transformer's.
 *
 * <p><b>How "extends AbstractContainerMenu" is determined without ever loading a class early or
 * reflectively walking a possibly-not-yet-loaded ancestor:</b> every single class this transformer
 * is asked to look at (regardless of whether it ends up patched) has its {@code (className,
 * superName)} pair recorded in {@link #SUPER_NAME_BY_CLASS} first. Because the JVM always finishes
 * loading a class's superclass before defining that class itself, and this transformer is
 * installed at {@code premain()} time (before literally anything else in the server has loaded -
 * see {@code FabricLoaderAgent}'s own javadoc), every ancestor of any class this method is ever
 * called for is GUARANTEED to have already passed through this same method, and so already be in
 * the map — this is purely bookkeeping over already-observed ASM metadata, never a fresh
 * classload, so it carries no reentrancy or ordering risk.
 */
public final class AbstractContainerMenuBukkitViewTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.AbstractContainerMenuBukkitViewTransformer");

	private static final String TARGET_MENU_CLASS = "net/minecraft/world/inventory/AbstractContainerMenu";
	private static final String METHOD_NAME = "getBukkitView";
	private static final String METHOD_DESC = "()Lorg/bukkit/inventory/InventoryView;";

	private static final String HELPER_OWNER = "ankhangbo/fabricloader/compat/GenericMenuBukkitView";
	private static final String HELPER_NAME = "getOrCreateView";
	private static final String HELPER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	private static final ConcurrentHashMap<String, String> SUPER_NAME_BY_CLASS = new ConcurrentHashMap<>();

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
			if (superName != null) {
				SUPER_NAME_BY_CLASS.put(className, superName);
			}

			if (superName == null || className.equals(TARGET_MENU_CLASS)
					|| className.startsWith("net/minecraft/") || className.startsWith("org/bukkit/")) {
				return null;
			}

			if (!extendsContainerMenu(superName)) {
				return null;
			}

			ClassNode classNode = new ClassNode();
			cheapReader.accept(classNode, 0);

			if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
				return null;
			}

			for (MethodNode m : (List<MethodNode>) classNode.methods) {
				if (m.name.equals(METHOD_NAME) && m.desc.equals(METHOD_DESC)) {
					// Already has its own override (whatever it does) - leave it alone.
					return null;
				}
			}

			MethodNode getBukkitView = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, METHOD_DESC, null, null);
			InsnList il = getBukkitView.instructions;
			il.add(new VarInsnNode(Opcodes.ALOAD, 0));
			il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_OWNER, HELPER_NAME, HELPER_DESC, false));
			il.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/bukkit/inventory/InventoryView"));
			il.add(new InsnNode(Opcodes.ARETURN));
			getBukkitView.maxStack = 1;
			getBukkitView.maxLocals = 1;
			classNode.methods.add(getBukkitView);

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);

			LOGGER.fine(() -> "Injected a generic getBukkitView() fallback into " + className);
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to check/patch " + className
					+ " for a missing getBukkitView() - leaving it unpatched", t);
			return null;
		}
	}

	private static boolean extendsContainerMenu(String superName) {
		String cur = superName;
		int hops = 0;
		while (cur != null && hops++ < 64) {
			if (cur.equals(TARGET_MENU_CLASS)) {
				return true;
			}
			if (cur.equals("java/lang/Object")) {
				return false;
			}
			cur = SUPER_NAME_BY_CLASS.get(cur);
		}
		return false;
	}
}
