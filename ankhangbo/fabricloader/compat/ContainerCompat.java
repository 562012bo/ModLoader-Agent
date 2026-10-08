package ankhangbo.fabricloader.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backing implementation for the default {@code Container#getContents()} injected by
 * {@link ankhangbo.fabricloader.asm.ContainerInterfaceDefaultsTransformer}. JDK types only in the
 * signature (this class lives in the agent classloader and cannot see NMS types); the container's
 * own methods are reached reflectively through its own classloader.
 */
public final class ContainerCompat {
	private static final Map<Class<?>, Method[]> CACHE = new ConcurrentHashMap<>();

	private ContainerCompat() {
	}

	public static List<Object> getContents(Object container) {
		List<Object> out = new ArrayList<>();
		try {
			Method[] m = CACHE.computeIfAbsent(container.getClass(), c -> {
				try {
					Class<?> iface = Class.forName("net.minecraft.world.Container", false, c.getClassLoader());
					return new Method[] { iface.getMethod("getContainerSize"), iface.getMethod("getItem", int.class) };
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			});
			int size = (Integer) m[0].invoke(container);
			for (int i = 0; i < size; i++) {
				out.add(m[1].invoke(container, i));
			}
		} catch (Throwable t) {
			// return whatever we have; never throw from a compat default
		}
		return out;
	}
}