package ankhangbo.fabricloader.compat;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Target of {@code CraftTagNullSafeTransformer}. JDK types only in the signature on purpose (see NullSafeMaterial's javadoc). */
public final class NullSafeTags {
	private NullSafeTags() { }

	public static Collector<Object, ?, Set<Object>> nonNullUnmodifiableSet() {
		return Collectors.filtering(Objects::nonNull, Collectors.toUnmodifiableSet());
	}
}