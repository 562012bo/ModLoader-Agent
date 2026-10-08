package ankhangbo.fabricloader.compat;

import org.bukkit.Material;

/**
 * Modded-item compatibility shim for {@link Material#isLegacy()}.
 *
 * <p>Bukkit's {@code Material} enum only has constants for vanilla items/blocks — it has no way
 * to represent a Fabric mod's item (e.g. {@code naturescompass:naturescompass},
 * {@code toughasnails:leaf_boots}). Whenever CraftBukkit code needs to map a modded
 * {@code net.minecraft.world.item.Item} to a {@code Material} (see
 * {@code CraftMagicNumbers}'s static {@code ITEM_MATERIAL} table, built by uppercasing every
 * registered item's path and looking it up via {@code Material.getMaterial(String)}), that lookup
 * simply returns {@code null} for any modded item, since no matching constant exists.
 *
 * <p>{@code CraftItemStack.getType()} then also returns that {@code null}. Most of CraftBukkit's
 * own internal code was written assuming {@code getType()} is never null and calls
 * {@code .isLegacy()} on it directly — e.g. {@code org.bukkit.inventory.ItemStack}'s copy
 * constructor, reached from {@code CraftingRecipe}'s constructor, reached from
 * {@code net.minecraft.world.item.crafting.ShapedRecipe.toBukkitRecipe()}, reached from
 * {@code CraftInventoryCrafting.getRecipe()} — which Paper's container-click handling
 * ({@code ServerGamePacketListenerImpl.handleContainerClick}) calls on every crafting-table click
 * to figure out which recipe currently matches, purely so it can fire Bukkit's crafting events.
 * Any recipe whose result (or a candidate ingredient) is a modded item makes that whole chain
 * throw a NullPointerException, which aborts the entire click packet - Paper only "suppresses" it
 * (logs and moves on), so from the player's perspective the click just silently does nothing.
 *
 * <p><b>Not actually called anymore.</b> {@link NullSafeMaterialTransformer} used to redirect
 * every {@code INVOKEVIRTUAL org/bukkit/Material.isLegacy ()Z} call site to this method, but that
 * redirect crossed a classloader boundary it shouldn't have: this class (part of the -javaagent
 * jar) is loaded by the system/agent classloader, a PARENT of the server's own classloader that
 * actually defines {@code org.bukkit.Material} — and a parent classloader can never resolve a
 * class only its child knows about. Every redirected call site threw {@code NoClassDefFoundError}
 * the instant it first ran (deep inside Bukkit's own static bootstrap), crashing the server before
 * startup even finished. The transformer now inlines the null check directly into each patched
 * class's own bytecode instead (see its javadoc), so this method is kept only to document the
 * semantics ("unknown/modded material" ⇒ "not a legacy (pre-1.13) material") the inlined check
 * implements — it is otherwise dead code.
 */
public final class NullSafeMaterial {
	private NullSafeMaterial() {
	}

	public static boolean isLegacySafe(Material material) {
		return material != null && material.isLegacy();
	}
}
