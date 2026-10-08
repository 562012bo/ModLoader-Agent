package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Strips the {@code abstract} flag from {@code org.bukkit.craftbukkit.entity.CraftBoat} so it can
 * be instantiated directly as a generic boat wrapper for a modded boat entity.
 *
 * <p>Companion to {@link CraftEntityGenericFallbackTransformer}. Vanilla code that ticks boats
 * (e.g. {@code AbstractBoat#tick()}) does {@code (Vehicle) entity.getBukkitEntity()} - a plain
 * generic {@code CraftEntity} fallback (which only implements the base {@code Entity} interface)
 * fails that cast with a {@code ClassCastException} every tick for any modded boat (e.g.
 * BiomesOPlenty's boats). {@code CraftBoat} already implements {@code Boat} (which extends
 * {@code Vehicle}) and everything else a boat needs; confirmed against Leaf 26.1.2's real
 * decompiled source, it declares zero abstract methods of its own, and its existing per-wood-type
 * subclasses (e.g. {@code CraftOakBoat}) are trivial one-line constructor pass-throughs with no
 * additional overrides - proof that {@code CraftBoat} is already fully self-sufficient and marked
 * {@code abstract} purely to force picking a wood-specific subclass, not because anything is
 * missing. For a modded boat with no matching wood-specific subclass, using bare {@code CraftBoat}
 * directly is the correct generic fallback (as opposed to picking an arbitrary existing subclass
 * like {@code CraftOakBoat}, which would misreport the wood type via its {@code OakBoat} marker
 * interface).
 */
public final class CraftBoatGenericFallbackTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftBoatGenericFallbackTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/entity/CraftBoat";

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
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET_CLASS.equals(className)) {
			return null;
		}

		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(classfileBuffer).accept(classNode, 0);

			if ((classNode.access & Opcodes.ACC_ABSTRACT) == 0) {
				return null;
			}
			classNode.access &= ~Opcodes.ACC_ABSTRACT;

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + " instantiable as a generic boat wrapper "
					+ "for modded boat entities.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to make " + className + " instantiable - leaving it unpatched", t);
			return null;
		}
	}
}