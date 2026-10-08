package ankhangbo.fabricloader.compat;

import java.io.PrintStream;

/**
 * Replacement for {@code System.err.println("Unhandled statistic: " + stat)} in
 * {@code CraftEventFactory#handleStatisticsIncrease}.
 *
 * <p>After {@link StatisticBridge} every untyped custom statistic of a mod is mapped, so what can still reach that line is
 * (a) a TYPED statistic of a modded block/item/entity (e.g. {@code minecraft.mined:biomesoplenty.origin_oak_log}) - Bukkit's
 * typed statistics are keyed by its Material/EntityType enums, which have no constant for it, so no Bukkit event exists and
 * nothing is wrong - and (b) a statistic in the {@code minecraft} namespace that Paper really forgot to map. Only (b) is
 * printed, because that one is a genuine problem worth seeing.
 */
public final class StatisticLog {
	private StatisticLog() {
	}

	public static void println(PrintStream out, String message) {
		int name = message.indexOf("name=");

		if (name >= 0) {
			int colon = message.indexOf(':', name);

			if (colon > 0 && !message.startsWith("minecraft.", colon + 1)) {
				return; // a mod's own block/item/entity: no Bukkit counterpart, not an error
			}
		}

		out.println(message);
	}
}