package net.fabricmc.loader.impl.launch.knot;

import java.lang.instrument.ClassFileTransformer;

import ankhangbo.fabricloader.asm.AttributeSupplierEarlyInitTransformer;
import ankhangbo.fabricloader.asm.BlockEntityTypeAllowDuplicateTransformer;
import ankhangbo.fabricloader.asm.BukkitMaterialIsAirNullSafeTransformer;
import ankhangbo.fabricloader.asm.CallSiteRemapTransformer;
import ankhangbo.fabricloader.asm.DataFixerUpperNullTypeTransformer;
import ankhangbo.fabricloader.asm.LeafApiBridgeTransformer;
import ankhangbo.fabricloader.asm.StateHolderNullArraysTransformer;
import ankhangbo.fabricloader.asm.Vec3iUnfinalTransformer;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Applies the "compat" class patches (un-final methods, null-safe Material.isAir, StateHolder null arrays) right before Knot
 * defines a class.
 *
 * <p>Why not only as {@code java.lang.instrument} transformers: JPLIS does NOT call any ClassFileTransformer for a class that is
 * defined while another transformer is still running on the same thread. Several agent transformers call
 * {@code Class.forName(type, false, gameLoader)} from {@code getCommonSuperClass}, which defines game classes (e.g.
 * {@code net.minecraft.world.level.Level}) inside that call - those classes then silently skip EVERY agent transformer. Result
 * seen in the logs: {@code Entity}/{@code Vec3i} were un-finalised but {@code Level} was not, so Create Fly's
 * {@code SchematicLevel} failed with "overrides final method Level.getEntitiesOfClass". Knot's own define path has no such
 * blind spot, so these patches run here instead (and are not registered as agent transformers any more).
 */
final class GameClassPatcher {
	private static final ClassFileTransformer[] CHAIN = {
			new Vec3iUnfinalTransformer(),
			new StateHolderNullArraysTransformer(),
			new BukkitMaterialIsAirNullSafeTransformer(),
			new AttributeSupplierEarlyInitTransformer(),
			new BlockEntityTypeAllowDuplicateTransformer(),
			new DataFixerUpperNullTypeTransformer(),
			new LeafApiBridgeTransformer(),
	};

	static {
		// one line at startup so it is obvious whether this build contains the patcher and which patches it runs
		StringBuilder names = new StringBuilder();

		for (ClassFileTransformer t : CHAIN) {
			if (names.length() > 0) names.append(", ");
			names.append(t.getClass().getSimpleName().replace("Transformer", ""));
		}

		Log.info(LogCategory.KNOT, "GameClassPatcher active (%d patches + call-site remap on Knot's define path): %s", CHAIN.length, names);
	}

	/** applies to every class (mods included), see CallSiteRemapTransformer */
	private static final ClassFileTransformer REMAP = new CallSiteRemapTransformer();

	private GameClassPatcher() { }

	static byte[] apply(String name, byte[] bytes) {
		String internal = name.replace('.', '/');

		try {
			byte[] r = REMAP.transform(null, internal, null, null, bytes);

			if (r != null) bytes = r;
		} catch (Throwable e) {
			Log.warn(LogCategory.KNOT, "Call-site remap failed for " + name, e);
		}

		if (!name.startsWith("net.minecraft.") && !name.startsWith("org.bukkit.craftbukkit.") && !name.startsWith("io.papermc.paper.")
				&& !name.equals("com.mojang.datafixers.DataFixerUpper")) {
			return bytes;
		}

		for (ClassFileTransformer t : CHAIN) {
			try {
				byte[] r = t.transform(null, internal, null, null, bytes);

				if (r != null) bytes = r;
			} catch (Throwable e) {
				Log.warn(LogCategory.KNOT, "Compat patch " + t.getClass().getSimpleName() + " failed for " + name, e);
			}
		}

		return bytes;
	}
}
