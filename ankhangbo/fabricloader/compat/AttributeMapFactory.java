package ankhangbo.fabricloader.compat;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

/**
 * Called from the patched {@code AttributeSupplier.<init>} (see AttributeSupplierEarlyInitTransformer). Only uses JDK types
 * in its signatures: this class lives in the agent jar, which the game's classes can see but which can't see game classes.
 *
 * <p>Why a plain map: Leaf's {@code AttributeInstanceArrayMap}/{@code ActivityBitSet}/... size their arrays from
 * {@code RegistryTypeManager}, a snapshot of the ATTRIBUTE/ACTIVITY registries taken the first time anything touches it.
 * An {@code AttributeSupplier} is built during startup, so building it with the array map triggers that snapshot before
 * mods (Twilight Forest, ...) have registered their own attributes -> {@code ArrayIndexOutOfBoundsException: Index 40 out of
 * bounds for length 40}, and before the registries have content at all (Railways) -> {@code ExceptionInInitializerError}.
 * With a HashMap here the snapshot is first taken when the first entity/brain is created at runtime, after every mod has
 * registered. (A supplier is read-only static data; the per-entity {@code AttributeMap} keeps Leaf's fast map.)
 * Re-enable the old behaviour with {@code -Dankhangbo.leafAttributeArrayMap=true}.
 */
public final class AttributeMapFactory {
	private static final boolean USE_ARRAY_MAP = Boolean.getBoolean("ankhangbo.leafAttributeArrayMap");

	private AttributeMapFactory() { }

	/**
	 * @param arrayMap Leaf's {@code AttributeInstanceArrayMap} class
	 * @param src the attributes
	 * @param registriesFilled {@code ATTRIBUTE.size() * ACTIVITY.size()}; 0 while the built-in registries have no content yet
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Map make(Class<?> arrayMap, Map src, int registriesFilled) {
		if (!USE_ARRAY_MAP || registriesFilled == 0) {
			// see the class comment: a plain map behaves the same and does not pin Leaf's registry snapshot too early
			return new HashMap(src);
		}

		try {
			return (Map) arrayMap.getConstructor(Map.class).newInstance(src);
		} catch (InvocationTargetException e) {
			Throwable c = e.getCause();

			if (c instanceof RuntimeException) throw (RuntimeException) c;
			if (c instanceof Error) throw (Error) c;
			throw new RuntimeException(c);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}
}
