package ankhangbo.fabricloader.compat;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper called from the patched {@code BlockEntityType#isValid(BlockState)} (see
 * {@link ankhangbo.fabricloader.asm.BlockEntityTypeModdedValidBlockTransformer}).
 *
 * <p>IMPORTANT: this class may only use JDK types in its public signature, because it is loaded by
 * the agent/system classloader (a PARENT of Knot's loader) and so can't see any NMS/Bukkit class.
 *
 * <p>Rule: a block is accepted for a BlockEntityType if it is in the type's {@code validBlocks}
 * set (vanilla behaviour), OR if its class (or one of its superclasses, excluding the generic
 * Block/BaseEntityBlock/BlockBehaviour) is exactly the class of some block already in that set.
 * E.g. a modded {@code StandingSignBlock} instance is accepted for {@code BlockEntityType.SIGN}
 * even if the mod's "add valid block" call never took effect.
 */
public final class BlockEntityTypeCompat {
	private static final Set<String> GENERIC = Set.of(
			"java.lang.Object",
			"net.minecraft.world.level.block.Block",
			"net.minecraft.world.level.block.BaseEntityBlock",
			"net.minecraft.world.level.block.state.BlockBehaviour");

	private static final Map<Set<?>, Entry> CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

	private static final class Entry {
		final int size;
		final Set<Class<?>> classes = new HashSet<>();
		final Map<Class<?>, Boolean> results = new ConcurrentHashMap<>();

		Entry(Set<?> set) {
			this.size = set.size();
			for (Object o : set) {
				if (o != null && !GENERIC.contains(o.getClass().getName())) {
					classes.add(o.getClass());
				}
			}
		}
	}

	private BlockEntityTypeCompat() {
	}

	public static boolean containsOrCompatible(Set<?> validBlocks, Object block) {
		if (validBlocks.contains(block)) {
			return true;
		}
		if (block == null || validBlocks.isEmpty()) {
			return false;
		}
		Entry entry;
		try {
			synchronized (CACHE) {
				entry = CACHE.get(validBlocks);
				if (entry == null || entry.size != validBlocks.size()) {
					entry = new Entry(validBlocks);
					CACHE.put(validBlocks, entry);
				}
			}
		} catch (ConcurrentModificationException e) {
			return false;
		}
		final Entry e = entry;
		return e.results.computeIfAbsent(block.getClass(), c -> matches(e.classes, c));
	}

	private static boolean matches(Set<Class<?>> classes, Class<?> c) {
		for (Class<?> k = c; k != null && !GENERIC.contains(k.getName()); k = k.getSuperclass()) {
			if (classes.contains(k)) {
				return true;
			}
		}
		return false;
	}
}