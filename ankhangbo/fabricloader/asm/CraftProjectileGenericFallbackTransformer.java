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
 * Strips the {@code abstract} flag from {@code org.bukkit.craftbukkit.entity.CraftProjectile} so
 * it can be instantiated directly as a generic wrapper for a modded projectile entity.
 *
 * <p>Companion to {@link CraftEntityGenericFallbackTransformer}, for the exact same reason
 * {@link CraftBoatGenericFallbackTransformer}/{@link CraftAbstractHorseGenericFallbackTransformer}
 * exist. {@code Projectile#preHitTargetOrDeflectSelf(...)} does
 * {@code (org.bukkit.entity.Projectile) this.getBukkitEntity()} (via
 * {@code CraftEventFactory#callProjectileHitEvent}) the moment ANY projectile - vanilla or modded -
 * is about to hit something. Before this transformer, a modded projectile (e.g. a modded thrown
 * item extending {@code ThrowableProjectile}) got the plain {@code CraftEntity} generic fallback
 * from {@code CraftEntityGenericFallbackTransformer} (since a projectile is a plain {@code Entity},
 * not a {@code LivingEntity}, so it didn't even qualify for THAT narrower fallback either), which
 * doesn't implement {@code org.bukkit.entity.Projectile} - so that cast threw
 * {@code ClassCastException} the instant the projectile reached anything (a block, an entity, or
 * simply expired), every time.
 *
 * <p>{@code CraftProjectile} already implements every method of {@code org.bukkit.entity.Projectile}
 * concretely itself (bounce state, shooter/owner, hit-entity dispatch, ...) via its
 * {@code AbstractProjectile} superclass - confirmed against Leaf 26.1.2's real decompiled source,
 * {@code CraftProjectile} itself declares zero abstract methods of its own (it only narrows
 * {@code getHandle()}'s return type), exactly like {@code CraftEntity}, {@code CraftBoat}, and
 * {@code CraftAbstractHorse} before it. It's marked {@code abstract} purely to force picking one of
 * its per-projectile-type subclasses ({@code CraftArrow}, {@code CraftSnowball}, ...), not because
 * anything is missing - so for a modded projectile type with no matching subclass, using bare
 * {@code CraftProjectile} directly is the correct generic fallback.
 */
public final class CraftProjectileGenericFallbackTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftProjectileGenericFallbackTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/entity/CraftProjectile";

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

			LOGGER.fine(() -> "Made " + className + " instantiable as a generic projectile wrapper "
					+ "for modded projectile entities.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to make " + className + " instantiable - leaving it unpatched", t);
			return null;
		}
	}
}