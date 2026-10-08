package net.fabricmc.loader.impl.launch.knot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Tracks every {@code net.minecraft.*} class that Knot defines BEFORE Mixin has done its first
 * transform (= before Mixin's "select" phase prepares all mod mixin configs).
 *
 * <p>Why: Mixin's {@code MixinInfo.readDeclaredTargets} throws
 * {@code MixinTargetAlreadyLoadedException: ... target X was loaded too early} when
 * {@code classTracker.isClassLoaded(X)} is true at prepare time. Stock Fabric never hits this,
 * because nothing touches game classes before preLaunch. In this Paper/Leaf + Fabric hybrid something
 * does, and the error then shows up on whichever mod's mixin is prepared first (betterwalls, Create Fly,
 * Lithium, FerriteCore ... - they are only the first victims, not the cause).
 *
 * <p>This class does two things:
 * <ol>
 *   <li><b>Diagnosis</b>: logs a stack trace for the first early class (the "patient zero" - the stack
 *       shows exactly who triggered loading of game classes) and for every class listed in
 *       {@code -Dankhangbo.traceEarly=a.b.C,d.e.F}. {@code FenceBlock} is always traced.</li>
 *   <li><b>No crash</b>: {@link #shouldHideFromMixin(String)} lets {@code MixinServiceKnot.isClassLoaded}
 *       answer "not loaded" for classes that were defined early, so Mixin prepares the config instead of
 *       aborting the whole server. Mixins that target those already-defined classes can not be applied
 *       (the class is already defined without them); one WARN lists how many. Disable with
 *       {@code -Dankhangbo.mixin.allowEarlyLoaded=false} to get Mixin's strict behaviour back.</li>
 * </ol>
 */
public final class EarlyLoadGuard {
	private static final boolean ALLOW_EARLY = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.mixin.allowEarlyLoaded"));
	private static final Set<String> EXTRA_TRACE = new java.util.HashSet<>(Arrays.asList(
			System.getProperty("ankhangbo.traceEarly", "").split(",")));

	private static final Set<String> EARLY = ConcurrentHashMap.newKeySet();
	private static volatile boolean mixinStarted;
	private static volatile boolean patientZeroLogged;

	static {
		EXTRA_TRACE.add("net.minecraft.world.level.block.FenceBlock");
	}

	private EarlyLoadGuard() { }

	/** Mixin's first (outermost) transform has finished = its select/prepare phase is over. */
	private static volatile boolean selectDone;
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static int traced;

	/** Called for every class load request on the Knot loader (any path: own jar, parent, define). */
	public static void onLoadRequest(String name) {
		if (selectDone || !(name.startsWith("net.minecraft.") || name.startsWith("com.mojang."))) return;

		if (!EARLY.add(name)) return;

		boolean trace;

		synchronized (EarlyLoadGuard.class) {
			trace = traced < 3 || EXTRA_TRACE.contains(name);

			if (trace) traced++;
		}

		if (trace) {
			Log.warn(LogCategory.KNOT, "EARLY LOAD: " + name + " requested " + (DEPTH.get()[0] > 0
					? "DURING Mixin's select/prepare phase (a mixin plugin or Mixin itself did it)"
					: "BEFORE Mixin started") + ". Mixins targeting it will not be applied. Who asked:",
					new Throwable("who loaded " + name));
		}
	}

	/** Kept for the define hook. */
	public static void onDefine(String name) {
		onLoadRequest(name);
	}

	/** Wrap Mixin's transformClassBytes: the first outermost return closes the early-load window. */
	public static void markMixinStarted() {
		mixinStarted = true;
		DEPTH.get()[0]++;
	}

	public static void markMixinFinished() {
		int[] d = DEPTH.get();

		if (--d[0] > 0 || selectDone) return;

		selectDone = true;

		if (!EARLY.isEmpty()) {
			List<String> sample = new ArrayList<>(EARLY);
			Collections.sort(sample);

			Log.warn(LogCategory.KNOT, "%d Minecraft class(es) were loaded before/while Mixin prepared its configs (e.g. %s). %s",
					sample.size(), sample.subList(0, Math.min(8, sample.size())),
					ALLOW_EARLY ? "Mixin will NOT fail on them, but mixins targeting them are skipped."
							: "allowEarlyLoaded=false: Mixin aborts on the first one.");
		}
	}

	/** True if Mixin should be told this class is NOT loaded yet, so that it does not abort. */
	public static boolean shouldHideFromMixin(String name) {
		return ALLOW_EARLY && EARLY.contains(name.replace('/', '.'));
	}
}
