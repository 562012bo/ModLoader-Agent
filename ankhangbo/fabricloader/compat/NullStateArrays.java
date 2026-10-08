package ankhangbo.fabricloader.compat;

import java.lang.reflect.Array;

/** Helper called from the patched {@code StateHolder.<init>} (see StateHolderNullArraysTransformer). */
public final class NullStateArrays {
	private NullStateArrays() { }

	/** Returns {@code array}, or an empty array of {@code componentType} if it is null. */
	public static Object[] orEmpty(Object[] array, Class<?> componentType) {
		return array != null ? array : (Object[]) Array.newInstance(componentType, 0);
	}
}
