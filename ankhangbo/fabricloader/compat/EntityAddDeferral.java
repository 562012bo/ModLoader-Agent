package ankhangbo.fabricloader.compat;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime side of the "entity spawned from inside a chunk's entity-load callback" fix. JDK-only signatures (agent
 * classloader); Minecraft is reached reflectively through the ServerLevel instance's own classloader.
 *
 * <p>Moonrise (Paper's chunk system) refuses to add an entity to a chunk whose entities are in the middle of a status
 * update ("Cannot update chunk status for entity ... since entity chunk is receiving update", the add fails and the
 * entity is lost). Fabric's {@code ServerEntityEvents.ENTITY_LOAD} fires exactly inside that update, and mods such as
 * GuardVillagers spawn new entities from it - fine on vanilla, broken here.
 *
 * <p>{@code ChunkEntitySlices#updateStatus} is bracketed with {@link #enter()}/{@link #exit()}; while inside,
 * {@code ServerLevel#addEntity} calls {@link #defer} instead, which queues the very same call on the main thread.
 * The caller gets {@code true}; the entity appears a moment later instead of never.
 */
public final class EntityAddDeferral {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.EntityAddDeferral");

	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static volatile boolean logged;

	private EntityAddDeferral() {
	}

	public static void enter() {
		DEPTH.get()[0]++;
	}

	public static void exit() {
		int[] d = DEPTH.get();
		if (d[0] > 0) {
			d[0]--;
		}
	}

	public static boolean shouldDefer() {
		return DEPTH.get()[0] > 0;
	}

	public static void defer(Object level, Object entity, Object reason) {
		try {
			Method add = null;
			for (Class<?> c = level.getClass(); c != null && add == null; c = c.getSuperclass()) {
				for (Method m : c.getDeclaredMethods()) {
					if (m.getName().equals("addEntity") && m.getParameterCount() == 2 && m.getReturnType() == boolean.class) {
						add = m;
						break;
					}
				}
			}
			if (add == null) {
				throw new NoSuchMethodException("ServerLevel#addEntity(Entity, SpawnReason)");
			}
			add.setAccessible(true);
			final Method fAdd = add;
			Object server = level.getClass().getMethod("getServer").invoke(level);
			Runnable task = () -> {
				try {
					fAdd.invoke(level, entity, reason);
				} catch (Throwable t) {
					LOGGER.log(Level.WARNING, "Deferred entity add failed for " + entity.getClass().getName(), t);
				}
			};
			server.getClass().getMethod("execute", Runnable.class).invoke(server, task);
			if (!logged) {
				logged = true;
				LOGGER.info("Deferring entity spawns requested during a chunk's entity status update to the next "
						+ "tick (Moonrise would drop them); first seen: " + entity.getClass().getName());
			}
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not defer an entity add for " + entity.getClass().getName(), t);
		}
	}
}