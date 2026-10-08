package ankhangbo.fabricloader;

import java.lang.instrument.Instrumentation;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FabricLoaderAgent {

    private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.FabricLoaderAgent");

    private FabricLoaderAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        installPrettyLogging();

        // As early as possible: neither Paperclip.class (Paper) nor QuantumLeaper.class (Leaf)
        // has been loaded by the JVM yet at this point (the JVM only reads Main-Class out of the
        // manifest to decide what to invoke; it does not load/link that class until immediately
        // before calling its main() method), so plain addTransformer (no retransformClasses) is
        // enough — this is guaranteed to catch whichever one of them the running server actually
        // uses on its FIRST and only load. Only one of the two classes will ever be present on
        // any given server's classpath (Paper ships Paperclip; Leaf, a Paper fork, ships its own
        // "cn.dreeam:quantumleaper" bootstrap instead — see LeafclipDelegationTransformer's own
        // javadoc), so registering both transformers unconditionally is safe: whichever one's
        // target class never actually loads simply never does anything.
        inst.addTransformer(new ankhangbo.fabricloader.asm.PaperclipDelegationTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.LeafclipDelegationTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.NullSafeMaterialTransformer(), false);
        // Must see EVERY class from the very start (see this transformer's own javadoc for why:
        // it builds up a className->superName map incrementally, and relies on ancestors always
        // having been observed before their descendants are defined), same as the three above.
        inst.addTransformer(new ankhangbo.fabricloader.asm.AbstractContainerMenuBukkitViewTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.ContainerMaxStackSizeTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.LenientDefaultBlockStateTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.ContainerBukkitCompatTransformer(), false);
        inst.addTransformer(new ankhangbo.fabricloader.asm.ItemStackHurtAndBreakCompatTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.GrowingPlantHeadBlockMaxGrowthAgeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftBlockStatesNullMaterialTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.TreeGrowerUnknownFeatureTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftEntityPlaceEventNullSafeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftEntityTypeMinecraftToBukkitNullSafeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftEntityGenericFallbackTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftBoatGenericFallbackTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftAbstractHorseGenericFallbackTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.EntityMoveVehicleBlockNullSafeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.FileToIdConverterNullManagerTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.ReloadableServerRegistriesRefabricatedFixTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.ActivityConstructorCompatTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.TagLoaderLegacyBuildBridgeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.ValueInputContainsCompatTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftProjectileGenericFallbackTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.LivingEntityActuallyHurtCompatTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.FabricBlockEntityTypeAddValidBlockPatchTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.BlockEntityTypeModdedValidBlockTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.ContainerInterfaceDefaultsTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftBlockStatesSignFallbackTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftRegistryNullSafeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.FabricModsAsPluginsTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.ClientboundCustomPayloadFabricRegisterTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.PaperConsoleDiagnosticTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftSpawnCategoryUnknownMobCategoryTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.EntityAddDeferralTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CommandSourceStackPermissionContextTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.Log4jInterpolatorQuietTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftEventFactoryQuietStatisticTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.StatisticEnumExtensionTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftStatisticMutableMapTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.MobUnknownTargetReasonQuietTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CraftEventFactoryNullItemsTransformer(), false);
		// Both are idempotent (a second pass finds nothing to change), so they are registered here AND run from GameClassPatcher:
		// whichever sees the class first fixes it (the agent can be skipped for classes defined inside another transformer).
		inst.addTransformer(new ankhangbo.fabricloader.asm.LeafApiBridgeTransformer(), false);
		inst.addTransformer(new ankhangbo.fabricloader.asm.CallSiteRemapTransformer(), false);

        LOGGER.info("AGENT BUILD v15: registered LeafApiBridge + CallSiteRemap + BlockEntityTypeModdedValid + ContainerInterfaceDefaults + CraftBlockStatesSignFallback + CraftRegistryNullSafe + FabricModsAsPlugins + ClientboundCustomPayloadFabricRegister + MobUnknownTargetReasonQuiet");
        LOGGER.info("PaperclipTransformer/LeafclipTransformer/NullSafeMaterialTransformer/"
                + "AbstractContainerMenuBukkitViewTransformer/ContainerMaxStackSizeTransformer/"
                + "LenientDefaultBlockStateTransformer/ContainerBukkitCompatTransformer/"
                + "ItemStackHurtAndBreakCompatTransformer installed - waiting for "
                + "io.papermc.paperclip.Paperclip (Paper) or cn.dreeam.leaper.QuantumLeaper "
                + "(Leaf) to load.");

        // Paperclip.main()/QuantumLeaper.main() (rewritten by whichever transformer above matches
        // the running server) runs Pclip.main()/Pclip.mainLeaf() on its own "ServerMain" thread
        // and returns immediately — nothing else needs to happen here. Neither bootstrap class
        // ever runs its own startup logic beyond that; org.bukkit.craftbukkit.Main.main() ends up
        // invoked, at the very end of the real Fabric Loader launch sequence, by Fabric Loader's
        // own MinecraftGameProvider — never called directly from this agent.
    }

    /**
     * Replaces java.util.logging's default two-line format on every handler already attached to
     * the root logger (typically a single ConsoleHandler at this point, since premain() runs
     * before Paper/Bukkit's own logging setup exists yet) with {@link PrettyLogFormatter}'s
     * single-line style. Also ensures a console handler exists at all, in case none is present
     * yet this early.
     */
    private static void installPrettyLogging() {
        Logger root = Logger.getLogger("");
        Handler[] existing = root.getHandlers();
        if (existing.length == 0) {
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.ALL);
            handler.setFormatter(new PrettyLogFormatter());
            root.addHandler(handler);
        } else {
            for (Handler handler : existing) {
                handler.setFormatter(new PrettyLogFormatter());
            }
        }
        root.setLevel(Level.ALL);
    }
}