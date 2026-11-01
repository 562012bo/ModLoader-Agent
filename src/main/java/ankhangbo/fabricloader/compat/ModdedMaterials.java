package ankhangbo.fabricloader.compat;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime side of {@code ModdedMaterialsTransformer}: creates a Bukkit {@code Material} constant for every modded block/item.
 * Everything is reflective with JDK types in the signatures, because this class lives in the agent jar (parent loader) and cannot see
 * Bukkit/Minecraft types (see NullSafeMaterial's javadoc).
 *
 * <p>Naming: a vanilla key ({@code minecraft:stone}) keeps its constant {@code STONE}; a modded key {@code create:gearbox} becomes
 * {@code CREATE_GEARBOX} with key {@code create:gearbox} (non-alphanumerics become '_'), same scheme as other hybrid servers.
 */
public final class ModdedMaterials {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.ModdedMaterials");
	private static Instrumentation inst;
	private static boolean done;

	private ModdedMaterials() { }

	public static void setInstrumentation(Instrumentation i) {
		inst = i;
	}

	private static String sanitize(String s) {
		StringBuilder sb = new StringBuilder(s.length());

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ? c : '_');
		}

		return sb.toString();
	}

	private static String call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return (String) m.invoke(target);
	}

	/** replacement for {@code Identifier.getPath()} in CraftMagicNumbers.<clinit>: the Material constant name for this key (any case) */
	public static String materialPath(Object key) {
		try {
			String ns = call(key, "getNamespace");
			String path = call(key, "getPath");
			return "minecraft".equals(ns) ? path : sanitize(ns + "_" + path);
		} catch (Throwable t) {
			throw new IllegalStateException("cannot read resource key " + key, t);
		}
	}

	public static synchronized void registerAll(ClassLoader loader) {
		if (done) return;
		done = true;

		try {
			Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
			Class<?> materialClass = Class.forName("org.bukkit.Material", true, loader);
			Method add = materialClass.getMethod("ankhangbo$add", String.class, String.class, String.class);
			Method get = materialClass.getMethod("getMaterial", String.class);
			int added = 0;
			Set<String> namespaces = new TreeSet<>();

			for (String field : new String[] {"BLOCK", "ITEM"}) {
				Object registry = registries.getField(field).get(null);
				Method keySet = registry.getClass().getMethod("keySet");
				keySet.setAccessible(true);

				for (Object key : (Set<?>) keySet.invoke(registry)) {
					String ns = call(key, "getNamespace");
					if ("minecraft".equals(ns)) continue;

					String path = call(key, "getPath");
					String name = sanitize(ns + "_" + path).toUpperCase(Locale.ROOT);

					if (get.invoke(null, name) != null) continue; // BLOCK and ITEM share a key, or already added

					add.invoke(null, name, ns, path);
					namespaces.add(ns);
					added++;
				}
			}

			resetEnumCaches(materialClass);
			LOGGER.info("Added " + added + " Bukkit Material constants for modded blocks/items (namespaces: " + namespaces + ")");
		} catch (Throwable t) {
			LOGGER.log(Level.SEVERE, "Could not create Bukkit Materials for modded blocks/items - plugins will see null materials", t);
		}
	}

	/** Class#enumConstants / enumConstantDirectory may already be cached with the old constant list; clear them (best effort) */
	private static void resetEnumCaches(Class<?> enumClass) {
		if (inst == null) return;

		try {
			Module base = Object.class.getModule();
			Module me = ModdedMaterials.class.getModule();
			inst.redefineModule(base, Set.of(), Map.of(), Map.of("java.lang", Set.of(me)), Set.of(), Map.of());
			MethodHandles.Lookup l = MethodHandles.privateLookupIn(Class.class, MethodHandles.lookup());

			for (String f : new String[] {"enumConstants", "enumConstantDirectory"}) {
				try {
					VarHandle vh = l.findVarHandle(Class.class, f, f.equals("enumConstants") ? Object[].class : Map.class);
					vh.set(enumClass, null);
				} catch (NoSuchFieldException e) {
					LOGGER.fine("Class." + f + " not present on this JDK");
				}
			}
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not reset Material's cached enum constants (fine unless something used EnumMap(Material) before this point)", t);
		}
	}
}