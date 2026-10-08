package ankhangbo.fabricloader.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic {@code org.bukkit.inventory.InventoryView} fallback for a modded
 * {@code net.minecraft.world.inventory.AbstractContainerMenu} subclass that CraftBukkit itself
 * never patched with a {@code getBukkitView()} override.
 *
 * <p>Paper's own source patches give every VANILLA menu subclass (crafting, anvil, furnace, ...) a
 * concrete, per-type {@code getBukkitView()} override — that's how CraftBukkit ships it. A mod's
 * own menu class (e.g. ToughAsNails' Thermoregulator menu), which directly extends
 * {@code AbstractContainerMenu} but is obviously not one Paper has ever heard of, gets no such
 * override. Left alone, that leaves an unimplemented abstract method on an otherwise-concrete
 * class — {@code AbstractMethodError} the instant anything calls {@code getBukkitView()} on it
 * (opening the mod's GUI, in particular, always does). {@code AbstractContainerMenuBukkitViewTransformer}
 * injects a {@code getBukkitView()} override into every such class that is missing one; this class
 * is what that injected override calls to actually build the (generic, best-effort) view.
 *
 * <p><b>Every parameter and return type here is erased to {@code Object}, deliberately.</b> This
 * class lives in the agent's own jar, loaded by the JVM's system/agent classloader — the PARENT of
 * the server's own classloader that defines both the modded menu class and every
 * {@code org.bukkit.*}/{@code net.minecraft.*} type involved. A parent classloader can never
 * resolve a class only its child knows about (this is exactly the mistake
 * {@code NullSafeMaterial}'s original, since-abandoned design made — see its own javadoc), so this
 * method's own descriptor must not mention any of those types directly, or simply being invoked
 * would throw {@code NoClassDefFoundError} before ever reaching this method's body. Reflection,
 * driven off the actual runtime objects' own classes and classloader, is what lets this work
 * without that constraint. The one place a real {@code org.bukkit.inventory.InventoryView} type
 * gets named is a {@code CHECKCAST} inside the injected method itself — safe, because that
 * bytecode lives in (and resolves via) the modded menu class's own (correct, child) classloader,
 * never this one.
 */
public final class GenericMenuBukkitView {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.GenericMenuBukkitView");

	// Keyed by menu instance identity (default Object#equals/#hashCode - AbstractContainerMenu
	// doesn't override either), weak so a closed/discarded menu's cached view is free to be
	// collected along with it - this is a convenience cache, not a source of truth.
	private static final Map<Object, Object> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	private GenericMenuBukkitView() {
	}

	/**
	 * @param menu a live {@code net.minecraft.world.inventory.AbstractContainerMenu} instance
	 * @return a {@code org.bukkit.inventory.InventoryView}, or {@code null} if it could not be
	 *         built for any reason (logged once per menu class the first time this happens, so a
	 *         server owner actually sees it instead of it failing silently every time)
	 */
	public static Object getOrCreateView(Object menu) {
		Object cached = CACHE.get(menu);
		if (cached != null) {
			return cached;
		}

		try {
			Object view = buildView(menu);
			if (view != null) {
				CACHE.put(menu, view);
			}
			return view;
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not build a generic Bukkit InventoryView for "
					+ menu.getClass().getName() + " (a modded menu with no CraftBukkit-provided "
					+ "getBukkitView() override of its own) - returning null; whatever called "
					+ "getBukkitView() on it may itself now throw a NullPointerException instead. "
					+ "If you see this, please report it with this stack trace so the reflective "
					+ "constructor lookup below can be adjusted for your exact server build.", t);
			return null;
		}
	}

	private static Object buildView(Object menu) throws ReflectiveOperationException {
		ClassLoader loader = menu.getClass().getClassLoader();

		// The opening player isn't reliably stored under any one fixed field name across
		// mods/mappings - confirmed against Leaf 26.1.2's real decompiled source that
		// AbstractContainerMenu itself has no such field (only individual VANILLA menu
		// subclasses like EnchantmentMenu declare their own "player" field), AND that
		// ThermoregulatorContainer apparently stores no Player- or player-Inventory-typed field
		// of its own anywhere either. Three strategies, in order, each one a fallback for the
		// previous one failing:
		//   1. A field anywhere in the hierarchy whose type is
		//      net.minecraft.world.entity.player.Player (or a subtype, e.g. ServerPlayer) directly.
		//   2. A field whose type is net.minecraft.world.entity.player.Inventory (the player's
		//      own inventory container) - that class has a real public final "player" field of
		//      its own pointing back at the owning Player (confirmed against the same decompiled
		//      source).
		//   3. this.slots (confirmed to genuinely exist, by that exact name, directly on
		//      AbstractContainerMenu) almost always includes the opening player's own
		//      hotbar/inventory slots - added so the player can interact with their own items
		//      during the GUI session - and every net.minecraft.world.inventory.Slot has a real
		//      public final "container" field (confirmed against the decompiled Slot class)
		//      pointing at whatever backs that slot. Scanning those for one backed by a player
		//      Inventory finds the player even when the menu class stores no reference of its own
		//      at all - which is exactly ThermoregulatorContainer's case.
		Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
		Class<?> playerInventoryClass = Class.forName("net.minecraft.world.entity.player.Inventory", false, loader);

		Object serverPlayer = findFieldValueByType(menu, playerClass);
		if (serverPlayer == null) {
			Object playerInventory = findFieldValueByType(menu, playerInventoryClass);
			if (playerInventory != null) {
				serverPlayer = findFieldValueByType(playerInventory, playerClass);
			}
		}
		if (serverPlayer == null) {
			serverPlayer = findPlayerViaSlots(menu, playerInventoryClass, playerClass);
		}
		if (serverPlayer == null) {
			LOGGER.warning("No Player-typed field (directly, via a player-Inventory-typed field, "
					+ "or via any slot's backing container) found anywhere on "
					+ menu.getClass().getName() + " - cannot build a generic InventoryView "
					+ "without knowing who opened it.");
			return null;
		}
		Object humanEntity = serverPlayer.getClass().getMethod("getBukkitEntity").invoke(serverPlayer);

		// AbstractContainerMenu#slots (protected/public List<Slot>) - only used to size the
		// generic inventory reasonably; non-essential, so any failure here just falls back to a
		// double-chest-sized (54) inventory rather than aborting the whole view.
		int size = 54;
		try {
			Object slots = readInheritedField(menu, "slots");
			int slotCount = (slots instanceof List) ? ((List<?>) slots).size() : 54;
			int rows = Math.max(1, Math.min(6, (slotCount + 8) / 9));
			size = rows * 9;
		} catch (Throwable ignored) {
			// keep the default size
		}

		String title = menu.getClass().getSimpleName();

		Class<?> inventoryHolderClass = Class.forName("org.bukkit.inventory.InventoryHolder", false, loader);
		Class<?> craftInventoryCustomClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftInventoryCustom", false, loader);

		Object inventory = tryConstruct(craftInventoryCustomClass,
				new Class<?>[] { inventoryHolderClass, int.class, String.class },
				new Object[] { null, size, title });
		if (inventory == null) {
			inventory = tryConstruct(craftInventoryCustomClass,
					new Class<?>[] { inventoryHolderClass, int.class },
					new Object[] { null, size });
		}
		if (inventory == null) {
			LOGGER.warning("CraftInventoryCustom has neither a (InventoryHolder,int,String) nor "
					+ "(InventoryHolder,int) constructor on this server build - cannot build a "
					+ "generic InventoryView for " + menu.getClass().getName());
			return null;
		}

		Class<?> humanEntityClass = Class.forName("org.bukkit.entity.HumanEntity", false, loader);
		Class<?> inventoryClass = Class.forName("org.bukkit.inventory.Inventory", false, loader);
		Class<?> containerMenuClass = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", false, loader);
		Class<?> craftInventoryViewClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftInventoryView", false, loader);

		Object view = tryConstruct(craftInventoryViewClass,
				new Class<?>[] { humanEntityClass, inventoryClass, containerMenuClass },
				new Object[] { humanEntity, inventory, menu });
		if (view == null) {
			LOGGER.warning("CraftInventoryView has no (HumanEntity,Inventory,AbstractContainerMenu) "
					+ "constructor on this server build - cannot build a generic InventoryView for "
					+ menu.getClass().getName());
		}
		return view;
	}

	/**
	 * Walks {@code menu.slots} (a {@code List<Slot>}, confirmed to exist by that exact name
	 * directly on {@code AbstractContainerMenu}) looking for a slot whose backing container
	 * (Slot's own public final {@code container} field, confirmed against the decompiled Slot
	 * class) is an instance of the player's own inventory - present in almost every menu, since
	 * that's how the player can interact with their own hotbar/inventory items while the GUI is
	 * open, regardless of whether the menu class itself keeps any reference of its own.
	 */
	private static Object findPlayerViaSlots(Object menu, Class<?> playerInventoryClass, Class<?> playerClass)
			throws ReflectiveOperationException {
		Object slots = readInheritedField(menu, "slots");
		if (!(slots instanceof List)) {
			return null;
		}
		for (Object slot : (List<?>) slots) {
			if (slot == null) {
				continue;
			}
			Object container;
			try {
				container = readInheritedField(slot, "container");
			} catch (NoSuchFieldException e) {
				continue;
			}
			if (container != null && playerInventoryClass.isInstance(container)) {
				Object player = findFieldValueByType(container, playerClass);
				if (player != null) {
					return player;
				}
			}
		}
		return null;
	}

	private static Object readInheritedField(Object obj, String name) throws ReflectiveOperationException {
		for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(obj);
			} catch (NoSuchFieldException ignored) {
				// keep walking up
			}
		}
		throw new NoSuchFieldException(name);
	}

	/**
	 * Scans every declared field, on {@code obj}'s own class and every superclass up to (and
	 * including) {@code Object}, for the first one whose declared type is assignable to {@code
	 * type} and whose value on this instance isn't null. Field NAME is deliberately ignored here -
	 * see {@link #buildView} for why matching by name alone isn't reliable across different mods
	 * and mapping sets.
	 */
	private static Object findFieldValueByType(Object obj, Class<?> type) throws ReflectiveOperationException {
		for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (type.isAssignableFrom(f.getType())) {
					f.setAccessible(true);
					Object value = f.get(obj);
					if (value != null) {
						return value;
					}
				}
			}
		}
		return null;
	}

	private static Object tryConstruct(Class<?> target, Class<?>[] paramTypes, Object[] args) {
		try {
			Constructor<?> ctor = target.getDeclaredConstructor(paramTypes);
			ctor.setAccessible(true);
			return ctor.newInstance(args);
		} catch (Throwable t) {
			return null;
		}
	}
}
