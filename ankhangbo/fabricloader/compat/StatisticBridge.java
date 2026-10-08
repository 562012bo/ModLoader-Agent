package ankhangbo.fabricloader.compat;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes statistics registered by Fabric mods (e.g. {@code immersive_aircraft:distance_total}) real Bukkit
 * {@link org.bukkit.Statistic}-like citizens: a constant in {@code org.bukkit.Statistic}, a mapping in
 * {@code CraftStatistic}, so {@code PlayerStatisticIncrementEvent}, {@code Player#getStatistic/setStatistic/incrementStatistic}
 * and plugins that list {@code Statistic.values()} all see and handle them - and nothing prints "Unhandled statistic".
 *
 * <p>JDK-only signatures: this class lives in the agent classloader. Bukkit/Minecraft are reached reflectively through the
 * game classloader. No Unsafe and no static-final mutation: {@code org.bukkit.Statistic} itself is rewritten at load time
 * (see {@link ankhangbo.fabricloader.asm.StatisticEnumExtensionTransformer}) so that {@code values()} appends our
 * constants, built through a generated constructor/factory inside the enum.
 *
 * <p>Call {@link #register(ClassLoader)} once, after the mods' {@code main} entrypoints ran (their stats exist then) and before
 * the server and the plugins start. Only UNTYPED custom statistics are added; typed ones (mined/used/crafted for modded
 * blocks and items) need a Bukkit Material that does not exist and are left alone.
 */
public final class StatisticBridge {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.StatisticBridge");

	/** Constants appended by {@link #register}; read by the rewritten {@code Statistic.values()}. */
	private static final List<Object> EXTRAS = new CopyOnWriteArrayList<>();

	private static boolean done;

	private StatisticBridge() {
	}

	/** Called from the rewritten {@code Statistic.values()} with the freshly cloned base array. */
	public static Object withExtras(Object base) {
		if (EXTRAS.isEmpty()) {
			return base;
		}

		Object[] in = (Object[]) base;
		Object[] out = (Object[]) Array.newInstance(in.getClass().getComponentType(), in.length + EXTRAS.size());
		System.arraycopy(in, 0, out, 0, in.length);
		int i = in.length;

		for (Object e : EXTRAS) {
			out[i++] = e;
		}

		return out;
	}

	@SuppressWarnings("unchecked")
	public static synchronized void register(ClassLoader cl) {
		if (done) {
			return;
		}

		done = true;

		try {
			Class<?> stat = Class.forName("org.bukkit.Statistic", true, cl);
			Class<?> typeC = Class.forName("org.bukkit.Statistic$Type", true, cl);
			Class<?> keyC = Class.forName("org.bukkit.NamespacedKey", true, cl);
			Method create;

			try {
				create = stat.getMethod("fabric$create", String.class, int.class, typeC, keyC);
			} catch (NoSuchMethodException e) {
				LOGGER.warning("org.bukkit.Statistic was not extended (StatisticEnumExtensionTransformer inactive?) - "
						+ "modded statistics stay unknown to Bukkit");
				return;
			}

			Class<?> craftStat = Class.forName("org.bukkit.craftbukkit.CraftStatistic", true, cl);
			Field mapField = craftStat.getDeclaredField("statistics");
			mapField.setAccessible(true);
			Map<Object, Object> map = (Map<Object, Object>) mapField.get(null);

			if (map.getClass().getName().contains("Immutable")) {
				LOGGER.warning("CraftStatistic.statistics is immutable (CraftStatisticMutableMapTransformer inactive?) - "
						+ "modded statistics stay unknown to Bukkit");
				return;
			}

			Object[] base = (Object[]) stat.getMethod("values").invoke(null);
			Set<String> usedNames = new HashSet<>();

			for (Object o : base) {
				usedNames.add(((Enum<?>) o).name());
			}

			Object untyped = Enum.valueOf((Class<Enum>) typeC, "UNTYPED");
			Class<?> registryC = Class.forName("net.minecraft.core.Registry", false, cl);
			Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl);
			Object custom = builtIn.getField("CUSTOM_STAT").get(null);
			Set<?> keys = (Set<?>) registryC.getMethod("keySet").invoke(custom);
			List<Object> sorted = new ArrayList<>(keys);
			sorted.sort((a, b) -> a.toString().compareTo(b.toString()));

			List<String> added = new ArrayList<>();

			for (Object key : sorted) {
				String id = key.toString();
				int colon = id.indexOf(':');
				String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
				String path = colon < 0 ? id : id.substring(colon + 1);

				if (namespace.equals("minecraft") || map.containsKey(key)) {
					continue;
				}

				String name = (namespace + "_" + path).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");

				for (int n = 2; usedNames.contains(name); n++) {
					name = name.replaceAll("_\\d+$", "") + "_" + n;
				}

				usedNames.add(name);
				Object nk = keyC.getConstructor(String.class, String.class).newInstance(namespace, path);
				Object constant = create.invoke(null, name, base.length + EXTRAS.size(), untyped, nk);
				EXTRAS.add(constant);
				map.put(key, constant);
				added.add(name);
			}

			if (added.isEmpty()) {
				LOGGER.info("No modded custom statistics to register with Bukkit");
				return;
			}

			// The JDK caches an enum's constants the first time anything calls Enum.valueOf / EnumMap / EnumSet /
			// Class#getEnumConstants. If that already happened for org.bukkit.Statistic, every EnumMap<Statistic,...> would be
			// sized for the OLD constants and throw ArrayIndexOutOfBoundsException on a mod constant. Class#getEnumConstants()
			// builds the cache from the new values() when it is still empty (and then fixes it for everybody) and returns the
			// stale one otherwise - so a length mismatch means "too late": undo everything instead of leaving a trap for plugins.
			Object[] cached = stat.getEnumConstants();

			if (cached.length != base.length + added.size()) {
				for (Object key : sorted) {
					Object c = map.get(key);

					if (c != null && EXTRAS.contains(c)) {
						map.remove(key);
					}
				}

				EXTRAS.clear();
				LOGGER.warning("org.bukkit.Statistic's constants were already cached by the JDK (" + cached.length
						+ " cached vs " + (base.length + added.size()) + " now) - NOT registering " + added.size()
						+ " modded statistic(s) with Bukkit, because plugins using EnumMap/EnumSet<Statistic> would crash on them. "
						+ "Something touches Statistic before the mods' entrypoints finish.");
				return;
			}

			LOGGER.info("Registered " + added.size() + " modded custom statistic(s) with Bukkit: " + added);
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not register modded statistics with Bukkit", t);
		}
	}

	/** For tests / diagnostics. */
	static List<Object> extras() {
		return Collections.unmodifiableList(EXTRAS);
	}
}