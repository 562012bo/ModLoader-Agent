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
 * Strips the {@code abstract} flag from {@code org.bukkit.craftbukkit.entity.CraftAbstractHorse}
 * so it can be instantiated directly as a generic wrapper for a modded horse-family entity.
 *
 * <p>Companion to {@link CraftEntityGenericFallbackTransformer}, for the exact same reason
 * {@link CraftBoatGenericFallbackTransformer} exists for boats. {@code AbstractHorse#createInventory()}
 * does {@code (org.bukkit.entity.AbstractHorse) this.getBukkitEntity()} the moment ANY horse-family
 * entity (horse, donkey, mule, camel, and any modded subclass - e.g. a modded zebra extending
 * {@code AbstractChestedHorse}) is constructed. Before this transformer, a modded horse-family
 * entity got the plain {@code CraftLivingEntity} generic fallback from
 * {@code CraftEntityGenericFallbackTransformer}, which doesn't implement
 * {@code org.bukkit.entity.AbstractHorse} - so that cast threw {@code ClassCastException}
 * immediately on spawn, every single time (from a spawn egg, natural spawning, {@code /summon},
 * breeding, ...).
 *
 * <p>{@code CraftAbstractHorse} already implements every method of {@code
 * org.bukkit.entity.AbstractHorse} itself concretely (domestication, jump strength, taming,
 * inventory, eating/rearing state, ...) - confirmed against Leaf 26.1.2's real decompiled source,
 * it declares zero abstract methods of its own, exactly like {@code CraftEntity} and
 * {@code CraftBoat} before it. It's marked {@code abstract} purely to force picking one of its
 * per-species subclasses ({@code CraftHorse}, {@code CraftCamel}, {@code CraftSkeletonHorse}, ...),
 * not because anything is missing - so for a modded species with no matching subclass, using bare
 * {@code CraftAbstractHorse} directly is the correct generic fallback (as opposed to picking an
 * arbitrary existing subclass, which would misreport the species via that subclass's own marker
 * interface, e.g. reporting a modded zebra as a {@code Horse}).
 */
public final class CraftAbstractHorseGenericFallbackTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftAbstractHorseGenericFallbackTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/entity/CraftAbstractHorse";

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

			LOGGER.fine(() -> "Made " + className + " instantiable as a generic horse-family wrapper "
					+ "for modded horse-family entities.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to make " + className + " instantiable - leaving it unpatched", t);
			return null;
		}
	}
}