package ankhangbo.fabricloader.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime side of "Fabric mods behave like Bukkit plugins".
 *
 * <p>Called from bytecode injected by {@link ankhangbo.fabricloader.asm.FabricModsAsPluginsTransformer}.
 * It lives in the agent's classloader, which cannot see Bukkit/Paper types, so every public method
 * only uses JDK types and Bukkit is reached through reflection / {@link Proxy}.
 *
 * <p>Switch off the Bukkit-side registration with {@code -Dankhangbo.fabricmods.asplugins=false}
 * (the /plugins listing stays).
 */
public final class FabricModBridge {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.FabricModBridge");
	private static final String[] NO_ARGS = new String[0];

	/** Text of the section header in /plugins. */
	private static final String HEADER = "Fabric Mod";
	/** Second section: the same mods listed by their id (a mod's display name often differs from its id). */
	private static final String HEADER_ID = "Fabric IDMod";
	private static final int HEADER_COLOR = 0xDBD0B4;

	private static volatile boolean registered;
	private static volatile List<String[]> cache;

	private FabricModBridge() {
	}

	// ------------------------------------------------------------------ mod list

	/** @return visible mods as {id, displayName, version}, sorted by display name. */
	public static synchronized List<String[]> mods() {
		List<String[]> c = cache;
		if (c != null) {
			return c;
		}
		List<String[]> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModMetadata m = mod.getMetadata();
			String id = m.getId();
			if ("builtin".equals(m.getType())) {
				continue; // minecraft, java
			}
			if (mod.getContainingMod().isPresent()) {
				continue; // jar-in-jar libraries
			}
			if (isFabricApiModule(m)) {
				continue; // fabric-xxx-v1 ... are part of "fabric-api"
			}
			out.add(new String[] { id, m.getName() == null || m.getName().isEmpty() ? id : m.getName(),
					m.getVersion().getFriendlyString() });
		}
		out.sort(Comparator.comparing((String[] a) -> a[1].toLowerCase(Locale.ROOT)));
		cache = out;
		return out;
	}

	private static boolean isFabricApiModule(ModMetadata m) {
		String id = m.getId();
		if (m.containsCustomValue("fabric-api:module-lifecycle")) {
			return true;
		}
		return id.equals("fabric-api-base") || (id.startsWith("fabric-") && id.matches(".*-v\\d+"));
	}

	private static Set<String> modKeys() {
		Set<String> keys = new HashSet<>();
		for (String[] m : mods()) {
			keys.add(m[0]);
			keys.add(m[1]);
		}
		return keys;
	}

	// ------------------------------------------------------------------ /plugins

	/**
	 * Appends two sections to the output of /plugins:
	 * <pre>
	 *   Fabric Mod (N):    display names   (what a plugin that searches by mod name sees)
	 *   Fabric IDMod (N):  mod ids         (what a plugin that searches by mod id sees)
	 * </pre>
	 */
	public static void sendModsSection(Object sender) {
		try {
			List<String[]> mods = mods();
			if (mods.isEmpty()) {
				return;
			}
			Method send = Class.forName("org.bukkit.command.CommandSender", false, sender.getClass().getClassLoader())
					.getMethod("sendMessage", String.class);

			List<String> names = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (String[] m : mods) {
				names.add(m[1]);
				ids.add(m[0]);
			}
			ids.sort(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT)));

			sendSection(send, sender, HEADER, names);
			sendSection(send, sender, HEADER_ID, ids);
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not print the Fabric mod list in /plugins", t);
		}
	}

	private static void sendSection(Method send, Object sender, String header, List<String> items) throws Exception {
		send.invoke(sender, hex(HEADER_COLOR) + header + " \u00a7f(" + items.size() + ")" + hex(HEADER_COLOR) + ":");
		for (int i = 0; i < items.size(); i += 10) {
			StringBuilder line = new StringBuilder(i == 0 ? " \u00a78- " : "  ");
			for (int j = i; j < Math.min(i + 10, items.size()); j++) {
				if (j > i) {
					line.append("\u00a7f, ");
				}
				line.append("\u00a7a").append(items.get(j));
			}
			send.invoke(sender, line.toString());
		}
	}

	private static String hex(int rgb) {
		StringBuilder sb = new StringBuilder("\u00a7x");
		for (char ch : String.format("%06x", rgb).toCharArray()) {
			sb.append('\u00a7').append(ch);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ dependencies

	/** Replacement for {@code Set.contains(id)} in Paper's hasDependency(): also true for Fabric mod ids/names. */
	public static boolean containsOrMod(Collection<?> c, Object id) {
		if (c.contains(id)) {
			return true;
		}
		if (!(id instanceof String)) {
			return false;
		}
		try {
			return modKeys().contains(id);
		} catch (Throwable t) {
			return false;
		}
	}

	// ------------------------------------------------------------------ Bukkit plugin objects

	/** True for the fake Plugin objects created by {@link #registerBukkitPlugins(Object)}. */
	public static boolean isModPlugin(Object o) {
		return o != null && Proxy.isProxyClass(o.getClass()) && Proxy.getInvocationHandler(o) instanceof ModPlugin;
	}

	/** Puts one Plugin object per visible Fabric mod into Paper's plugin manager (idempotent). */
	@SuppressWarnings("unchecked")
	public static void registerBukkitPlugins(Object server) {
		if (registered || !Boolean.parseBoolean(System.getProperty("ankhangbo.fabricmods.asplugins", "true"))) {
			return;
		}
		registered = true;
		try {
			ClassLoader cl = server.getClass().getClassLoader();
			Class<?> pluginC = Class.forName("org.bukkit.plugin.Plugin", false, cl);
			Class<?> loaderC = Class.forName("org.bukkit.plugin.PluginLoader", false, cl);
			Class<?> descC = Class.forName("org.bukkit.plugin.PluginDescriptionFile", false, cl);
			Constructor<?> descCtor = descC.getConstructor(String.class, String.class, String.class);
			Class<?> mgrC = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl", false, cl);
			Object mgr = mgrC.getMethod("getInstance").invoke(null);
			Field imF = mgrC.getDeclaredField("instanceManager");
			imF.setAccessible(true);
			Object im = imF.get(mgr);
			Field plF = im.getClass().getDeclaredField("plugins");
			Field lnF = im.getClass().getDeclaredField("lookupNames");
			plF.setAccessible(true);
			lnF.setAccessible(true);
			List<Object> plugins = (List<Object>) plF.get(im);
			Map<String, Object> lookup = (Map<String, Object>) lnF.get(im);

			Object loader = Proxy.newProxyInstance(cl, new Class<?>[] { loaderC }, new NoopHandler());
			int added = 0;
			int aliased = 0;
			boolean aliases = Boolean.parseBoolean(System.getProperty("ankhangbo.fabricmods.aliasnames", "true"));
			synchronized (im) {
				for (String[] mod : mods()) {
					String key = norm(mod[0]);
					if (lookup.containsKey(key)) {
						continue; // a real plugin with that name wins
					}
					// 1) the plugin named by the mod ID  (getPlugin("create"), depend: [create])
					Object plugin = Proxy.newProxyInstance(cl, new Class<?>[] { pluginC },
							new ModPlugin(mod, mod[0], descCtor.newInstance(mod[0], mod[2], "fabric.mod." + mod[0]), loader, server));
					plugins.add(plugin);
					lookup.put(key, plugin);
					added++;
					// 2) the same mod under its DISPLAY NAME, when that is a different name  (getPlugin("Create Fly"), or code that
					//    compares plugin.getName() with the mod's name). Skipped if a real plugin already uses that name.
					String nameKey = norm(mod[1]);
					if (aliases && !nameKey.equals(key) && !lookup.containsKey(nameKey)) {
						try {
							// PluginDescriptionFile only accepts [A-Za-z0-9 _.-] ("Friends&Foes" would throw), so the description
							// gets a cleaned name; getName() on the proxy still reports the real display name.
							String descName = mod[1].replaceAll("[^A-Za-z0-9 _.-]", "");
							Object alias = Proxy.newProxyInstance(cl, new Class<?>[] { pluginC }, new ModPlugin(mod, mod[1],
									descCtor.newInstance(descName.isEmpty() ? mod[0] : descName, mod[2], "fabric.mod." + mod[0]), loader, server));
							plugins.add(alias);
							lookup.put(nameKey, alias);
							aliased++;
						} catch (Throwable t) {
							LOGGER.log(Level.WARNING, "Could not add display-name entry '" + mod[1] + "' for mod " + mod[0], t);
							lookup.putIfAbsent(nameKey, plugin);
						}
					} else {
						lookup.putIfAbsent(nameKey, plugin);
					}
				}
			}
			LOGGER.info("Registered " + added + " Fabric mods as Bukkit plugins by mod id, plus " + aliased
					+ " extra entries by display name (disable: -Dankhangbo.fabricmods.asplugins=false / -Dankhangbo.fabricmods.aliasnames=false)");
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not register Fabric mods as Bukkit plugins", t);
		}
	}

	private static String norm(String name) {
		return name.replace(' ', '_').toLowerCase(Locale.ENGLISH);
	}

	private static Object defaultFor(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == char.class) {
			return '\0';
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == float.class) {
			return 0f;
		}
		if (type == double.class) {
			return 0d;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == short.class) {
			return (short) 0;
		}
		return 0;
	}

	private static final class ModPlugin implements InvocationHandler {
		private final String[] mod;
		private final String name; // what getName() reports: the mod id, or (alias entry) the display name
		private final Object description;
		private final Object loader;
		private final Object server;
		private final Logger logger;

		ModPlugin(String[] mod, String name, Object description, Object loader, Object server) {
			this.mod = mod;
			this.name = name;
			this.description = description;
			this.loader = loader;
			this.server = server;
			this.logger = Logger.getLogger(name);
		}

		@Override
		public Object invoke(Object proxy, Method m, Object[] args) {
			switch (m.getName()) {
				case "getName":
					return name;
				case "getDescription":
				case "getPluginMeta":
					return description;
				case "isEnabled":
					return Boolean.TRUE;
				case "isNaggable":
					return Boolean.FALSE;
				case "getLogger":
					return logger;
				case "getServer":
					return server;
				case "getPluginLoader":
					return loader;
				case "getDataFolder":
					return new File("config", mod[0]);
				case "toString":
					return "FabricMod{" + name + (name.equals(mod[0]) ? "" : " (id " + mod[0] + ")") + " " + mod[2] + "}";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "getConfig":
				case "getTextResource":
				case "getResource":
				case "saveConfig":
				case "saveDefaultConfig":
				case "saveResource":
				case "reloadConfig":
					throw new UnsupportedOperationException("Fabric mod '" + mod[0] + "' is not a Bukkit plugin");
				default:
					return defaultFor(m.getReturnType());
			}
		}
	}

	private static final class NoopHandler implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method m, Object[] args) {
			switch (m.getName()) {
				case "toString":
					return "FabricModPluginLoader";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					return defaultFor(m.getReturnType());
			}
		}
	}
}