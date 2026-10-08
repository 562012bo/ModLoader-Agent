package ankhangbo.fabricloader.compat;

import java.lang.reflect.Constructor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * For an entity whose EntityType is registered by a mod, Bukkit has no CraftEntity wrapper, and Paper code then casts
 * {@code entity.getBukkitEntity()} to the Bukkit interface of the vanilla class the mod entity extends
 * ({@code ClassCastException: CraftLivingEntity cannot be cast to org.bukkit.entity.Sheep}).
 *
 * <p>Walks up the NMS class hierarchy of the entity (e.g. TF BighornSheep -> Sheep -> Animal -> AgeableMob -> PathfinderMob ->
 * Mob -> LivingEntity) and returns {@code new Craft<SimpleName>(server, entity)} for the nearest {@code net.minecraft.*} class
 * that has such a wrapper with a {@code (CraftServer, thatClass)} constructor. Returns null if there is none (the caller then
 * falls back to its generic chain).
 *
 * <p>Only {@code Object}/JDK types in signatures: this class lives in the agent jar, which can't see game or Bukkit classes.
 */
public final class CraftEntityResolver {
	private static final Object NONE = new Object();
	private static final ConcurrentHashMap<Class<?>, Object> CACHE = new ConcurrentHashMap<>();

	private CraftEntityResolver() { }

	public static Object byHierarchy(Object server, Object entity) {
		Class<?> start = entity.getClass();
		Object cached = CACHE.get(start);

		if (cached == null) {
			cached = find(server.getClass(), start);
			CACHE.put(start, cached == null ? NONE : cached);
		}

		if (cached == NONE || cached == null) return null;

		try {
			return ((Constructor<?>) cached).newInstance(server, entity);
		} catch (Throwable t) {
			return null;
		}
	}

	private static Object find(Class<?> serverClass, Class<?> start) {
		ClassLoader cl = start.getClassLoader();

		for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
			String name = c.getName();

			if (name.equals("net.minecraft.world.entity.Entity")) return null; // generic CraftEntity is handled by the caller

			if (!name.startsWith("net.minecraft.")) continue;

			try {
				Class<?> craft = Class.forName("org.bukkit.craftbukkit.entity.Craft" + c.getSimpleName(), false, cl);

				if (java.lang.reflect.Modifier.isAbstract(craft.getModifiers())) continue;

				for (Constructor<?> k : craft.getConstructors()) {
					Class<?>[] p = k.getParameterTypes();

					if (p.length == 2 && p[0].isAssignableFrom(serverClass) && p[1] == c) return k;
				}
			} catch (ClassNotFoundException | LinkageError e) {
				// no wrapper with that name for this class, try its superclass
			}
		}

		return null;
	}
}
