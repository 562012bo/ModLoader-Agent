package ankhangbo.fabricloader;

import net.fabricmc.loader.impl.launch.knot.KnotCompatibilityClassLoader;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stand-in for the bootstrap loader's main() — either io.papermc.paperclip.Paperclip (Paper,
 * installed by {@link ankhangbo.fabricloader.asm.PaperclipDelegationTransformer}) or
 * cn.dreeam.leaper.QuantumLeaper (Leaf's own paperclip fork, installed by
 * {@link ankhangbo.fabricloader.asm.LeafclipDelegationTransformer}). Both bootstrap classes
 * expose the exact same private static {@code setupClasspath(): URL[]} /
 * {@code findMainClass(): String} method shapes (Leaf's QuantumLeaper is itself derived from
 * upstream Paperclip and keeps that same contract), so a single implementation below — driven by
 * whichever {@code bootstrapClassName} the calling transformer passes in — covers both without
 * any per-distribution branching beyond that one class name.
 *
 * Sequence:
 *   1. Reflectively call the bootstrap class's own (now-public) setupClasspath()/findMainClass()
 *      to get the server's real classpath URLs and entrypoint class name — this is the ONLY thing
 *      the original bootstrap class's own code still contributes; nothing else in it ever runs.
 *   2. Construct a SINGLE {@link KnotCompatibilityClassLoader} — real (lightly reworked, see its
 *      own class javadoc) Fabric Loader source, not a separate loader of this project's own — with
 *      Paper's own classpath (minus Paper's own ASM module jars, see findAsmJarUrls()). This exact
 *      SAME instance is later reused, never replaced, as Knot's own classloader (see
 *      {@code Knot.externalClassLoader}) once Knot itself knows enough (envType/GameProvider) to
 *      wire up real transformation/mixin logic on it. This is what makes it truly ONE classloader
 *      for the whole game/Paper domain, rather than two loaders that merely delegate to each other.
 *   3. Set it as this (new) thread's context classloader.
 *   4. Reflectively invoke net.fabricmc.loader.impl.launch.server.FabricServerLauncher.main(args)
 *      THROUGH it — this is real, unmodified Fabric Loader's own real entrypoint; it runs Knot,
 *      which runs the real (unmodified) MinecraftGameProvider — pointed at Paper's own server jar
 *      via the fabric.gameJarPath.server system property set below — which eventually invokes
 *      Paper's real org.bukkit.craftbukkit.Main.main(...). Paperclip is never touched again after
 *      step 1.
 */
public final class Pclip {

    private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.Pclip");

    private Pclip() {
    }

    /** Entry point for Paper's own bootstrap — installed by {@code PaperclipDelegationTransformer}. */
    public static void main(String[] args) throws Exception {
        run("io.papermc.paperclip.Paperclip", args);
    }

    /** Entry point for Leaf's own bootstrap fork — installed by {@code LeafclipDelegationTransformer}. */
    public static void mainLeaf(String[] args) throws Exception {
        run("cn.dreeam.leaper.QuantumLeaper", args);
    }

    /**
     * Shared implementation behind both {@link #main(String[])} (Paper) and
     * {@link #mainLeaf(String[])} (Leaf) — {@code bootstrapClassName} is the only thing that
     * differs between the two call sites; everything from here on is 100% distribution-agnostic,
     * since both bootstrap classes expose the same setupClasspath()/findMainClass() shape and
     * both produce a classpath containing a "versions" jar (the actual patched server jar) plus
     * per-module "libraries" jars (including Paper's/Leaf's own separate ASM module jars, handled
     * identically below regardless of which one produced them).
     */
    private static void run(String bootstrapClassName, String[] args) throws Exception {
        // Paper/Leaf - patch mods/modsreal/*.jar (user-provided originals, untouched) -> mods/*.jar
        // (what Fabric Loader actually discovers/loads), driven by mappings/mappings.tiny bundled
        // inside this agent's own jar. Must run before anything else — Fabric Loader's own mod
        // discovery scans the "mods" directory deep inside its own startup, well before this
        // project gets any other chance to intervene.
        preprocessModsReal();

        Class<?> bootstrap = Class.forName(bootstrapClassName);

        Method setupClasspath = bootstrap.getDeclaredMethod("setupClasspath");
        setupClasspath.setAccessible(true);
        URL[] paperUrls = (URL[]) setupClasspath.invoke(null);

        // findMainClass() is called here purely so any errors in locating the real server jar
        // surface immediately, with a clear stack trace, instead of silently later inside
        // Fabric Loader's own MinecraftGameProvider.locateGame().
        Method findMainClass = bootstrap.getDeclaredMethod("findMainClass");
        findMainClass.setAccessible(true);
        String mainClassName = (String) findMainClass.invoke(null);
        LOGGER.info("Pclip (via " + bootstrapClassName + ") sẽ chạy Main " + mainClassName);

        // Real, unmodified net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider reads
        // this system property directly
        // (GameProviderHelper.getEnvGameJar -> SystemProperties.GAME_JAR_PATH_SERVER =
        // "fabric.gameJarPath.server") instead of needing to auto-detect the game jar by
        // scanning FabricLauncher's OWN tracked classpath — which is empty this early anyway,
        // since unlockClassPath() (the call that would normally populate it) doesn't run until
        // AFTER locateGame() already needs this. Find the one paperUrls entry that actually
        // contains Minecraft server marker classes (same classes real McLibrary.MC_SERVER
        // itself checks for) and point Fabric Loader at it directly.
        String serverJarPath = findServerJarPath(paperUrls);
        if (serverJarPath != null) {
            System.setProperty("fabric.gameJarPath.server", serverJarPath);
            LOGGER.info("Set -Dfabric.gameJarPath.server=" + serverJarPath);
        } else {
            LOGGER.warning("Could not find a jar containing net/minecraft/server/Main.class or "
                    + "net/minecraft/server/MinecraftServer.class among " + bootstrapClassName
                    + "'s own classpath — MinecraftGameProvider.locateGame() will likely fail. "
                    + "paperUrls was: " + java.util.Arrays.toString(paperUrls));
        }

        // Paper - find and EXCLUDE every one of Paper's own bundled ASM module jars from the
        // classloader's own URL list. Paper ships ASM as several SEPARATE, per-module jars
        // (Maven-style: asm, asm-tree, asm-commons, asm-analysis, asm-util each their own jar
        // file under libraries/org/ow2/asm/<module>/<version>/), not one single bundled jar —
        // this agent's own jar already bundles the COMPLETE, 100%-sufficient ASM Fabric Loader
        // actually requires, so ALL of Paper's separate copies are not just unnecessary but
        // actively harmful: real Fabric Loader's own Knot.<clinit> -> LoaderUtil.verifyClasspath()
        // hard-fails (no bypass flag exists) the instant it finds org/objectweb/asm/ClassReader
        // .class more than once anywhere reachable from the one classloader's FULL delegation
        // chain (self + parent) via getResources() — and even where that specific check doesn't
        // fire, the JVM's own loader-constraint enforcement independently throws a LinkageError
        // the moment any OTHER class (e.g. a mod's bundled MixinExtras library) ends up resolving
        // a SPECIFIC ASM class (e.g. org.objectweb.asm.tree.ClassNode) through two different,
        // inconsistent paths — exactly the same underlying problem this whole one-classloader
        // design exists to prevent, just recurring per ASM module instead of once overall.
        // Excluding EVERY one of Paper's separate ASM module jars here (rather than stripping the
        // agent's own) is what lets the agent's own, complete 100% copy be the sole, only copy of
        // ALL ASM classes anywhere in the chain.
        java.util.Set<URL> paperAsmJarUrls = findAsmJarUrls(paperUrls);
        URL[] loaderUrls;
        if (!paperAsmJarUrls.isEmpty()) {
            LOGGER.info("Excluding " + paperAsmJarUrls.size() + " of Paper's own (partial) ASM "
                    + "module jar(s) from the classloader's own classpath, in favor of this "
                    + "agent's own complete ASM: " + paperAsmJarUrls);
            java.util.List<URL> filtered = new java.util.ArrayList<>(paperUrls.length);
            for (URL url : paperUrls) {
                if (!paperAsmJarUrls.contains(url)) filtered.add(url);
            }
            loaderUrls = filtered.toArray(new URL[0]);
        } else {
            loaderUrls = paperUrls;
        }

        // Paper - THE one and only classloader for the whole game/Paper domain (net.minecraft.*,
        // org.bukkit.*, mods, etc.) for this whole server process. Real Fabric Loader source
        // (net/fabricmc/loader/impl/launch/knot/KnotCompatibilityClassLoader.java), reworked so it
        // can be built this early — before Knot/envType/GameProvider exist — already carrying
        // Paper's own full classpath (minus its partial ASM copy, see above). See
        // runFabricServerLauncher() below for how Knot itself is told to reuse this exact instance
        // instead of constructing a second one.
        //
        // parent = the loader that already loaded THIS very class (Pclip) and, by extension,
        // already defined KnotCompatibilityClassLoader.class itself the moment the constructor
        // below runs (a class describing the loader is not a game/Paper class, and some
        // already-existing loader must always define it first — unavoidable in any JVM
        // classloader design, same as the JVM's own bootstrap loader sitting beneath the system
        // loader). Using it (rather than skipping to ITS OWN parent) matters concretely: once
        // Knot's own field of this exact type needs resolving later (see runFabricServerLauncher),
        // theOneClassLoader's parent-first delegation must find THIS SAME already-loaded class
        // object here, or the field-set fails with an IllegalArgumentException (two same-named,
        // different-identity classes). It also happens to be where this agent's OWN complete ASM
        // (and every other vendored Fabric Loader class this project bundles — Knot, GamePatch,
        // MinecraftGameProvider, etc.) already lives, needing no separate copy on theOneClassLoader's
        // own URL list at all.
        ClassLoader parentClassLoader = Pclip.class.getClassLoader();
        KnotCompatibilityClassLoader theOneClassLoader =
                new KnotCompatibilityClassLoader(loaderUrls, parentClassLoader);

        Thread runThread = new Thread(() -> runFabricServerLauncher(theOneClassLoader, args), "ServerMain");
        runThread.setContextClassLoader(theOneClassLoader);
        runThread.start();
    }

    /**
     * Content-based (not filename-based) search for EVERY paperUrls entry that is one of Paper's
     * own ASM module jars — i.e. contains any of ASM's per-module marker classes below — so this
     * works regardless of Paper's exact version/filename/jar-splitting choices for it (currently
     * separate per-module jars under libraries/org/ow2/asm/, per the logs, but not hardcoded here
     * in case that changes — e.g. if a future Paper version bundles them into one shaded jar
     * instead, that single jar would simply match more than one marker below and still get
     * caught, correctly, exactly once).
     */
    private static final String[] ASM_MODULE_MARKER_CLASSES = {
            "org/objectweb/asm/ClassReader.class",              // asm (core)
            "org/objectweb/asm/tree/ClassNode.class",            // asm-tree
            "org/objectweb/asm/commons/ClassRemapper.class",     // asm-commons
            "org/objectweb/asm/tree/analysis/Analyzer.class",    // asm-analysis
            "org/objectweb/asm/util/Printer.class",              // asm-util
    };

    private static java.util.Set<URL> findAsmJarUrls(URL[] paperUrls) {
        java.util.Set<URL> found = new java.util.LinkedHashSet<>();
        for (URL url : paperUrls) {
            try {
                java.io.File file = new java.io.File(url.toURI());
                if (!file.isFile()) continue;
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                    for (String marker : ASM_MODULE_MARKER_CLASSES) {
                        if (jar.getEntry(marker) != null) {
                            found.add(url);
                            break;
                        }
                    }
                } catch (java.io.IOException notAJar) {
                    // not every classpath URL is a zip/jar — skip silently
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not inspect classpath entry '" + url + "'", e);
            }
        }
        return found;
    }

    /**
     * Scans each of Paper's own classpath entries and returns the one whose ACTUAL JAR CONTENTS
     * contain a Minecraft server marker class (same classes real Fabric Loader's own
     * {@code McLibrary.MC_SERVER} checks for: {@code net/minecraft/server/Main.class} or
     * {@code net/minecraft/server/MinecraftServer.class}) — deliberately content-based, not
     * filename-based, so this works regardless of Paper's exact bundler/patch-output naming
     * convention (e.g. {@code versions/<version>/paper-<version>.jar}, which is what Paperclip's
     * own patch-application step actually produces — but this doesn't hardcode that path shape,
     * in case a future Paper version names it differently).
     */
    private static String findServerJarPath(URL[] paperUrls) {
        for (URL url : paperUrls) {
            try {
                java.io.File file = new java.io.File(url.toURI());
                if (!file.isFile()) continue;
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                    if (jar.getEntry("net/minecraft/server/Main.class") != null
                            || jar.getEntry("net/minecraft/server/MinecraftServer.class") != null) {
                        return file.getAbsolutePath();
                    }
                } catch (java.io.IOException notAJar) {
                    // not every classpath URL is a zip/jar — skip silently
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not inspect classpath entry '" + url + "'", e);
            }
        }
        return null;
    }

    /**
     * mods/modsreal/ = where the user places their ORIGINAL, untouched mod .jar files.
     * mods/         = where the (possibly patched) result actually gets written for Fabric
     *                 Loader's own normal mod discovery to find, exactly as before.
     *
     * Re-run (and every existing file in mods/ overwritten) on every single startup — never
     * skipped/cached — specifically so that editing mappings/mappings.tiny (and rebuilding just
     * this agent's own jar; no other change needed) changes what gets produced here on the very
     * next start, matching the requirement that "the mappings file changes -> the fix changes".
     *
     * If mods/modsreal doesn't exist yet, it's created empty (with a log line telling the user
     * where to put their jars) and this run is a no-op — mods/ is left exactly as it already was.
     */
    private static void preprocessModsReal() throws java.io.IOException {
        java.nio.file.Path runDir = java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path modsRealDir = runDir.resolve("mods").resolve("modsreal");
        java.nio.file.Path modsDir = runDir.resolve("mods");

        if (!java.nio.file.Files.isDirectory(modsRealDir)) {
            java.nio.file.Files.createDirectories(modsRealDir);
            LOGGER.info("Created empty mods/modsreal/ — place your ORIGINAL (unmodified) mod "
                    + ".jar files there; this agent will patch them into mods/ automatically on "
                    + "next start");
            return;
        }

        java.nio.file.Files.createDirectories(modsDir);

        // Paper - mods/mods.txt: a cache of (jarFileName -> size, mtime, mappings.tiny fingerprint
        // at the time it was last patched), so unchanged jars aren't re-patched (and their
        // jar-in-jar contents re-scanned/re-remapped) on every single startup — only genuinely
        // new/changed jars, or ANY jar at all if mappings/mappings.tiny itself changed since the
        // cache entry was written, are actually reprocessed.
        java.nio.file.Path modsTxt = modsDir.resolve("mods.txt");
        String mappingsFingerprint = computeMappingsFingerprint();
        java.util.Map<String, String> cache = loadModsTxt(modsTxt);
        java.util.Map<String, String> newCache = new java.util.LinkedHashMap<>();
        int patchedCount = 0;
        int skippedCount = 0;

        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                java.nio.file.Files.newDirectoryStream(modsRealDir, "*.jar")) {
            for (java.nio.file.Path realJar : stream) {
                String fileName = realJar.getFileName().toString();
                java.nio.file.Path outJar = modsDir.resolve(fileName);
                long size = java.nio.file.Files.size(realJar);
                long mtime = java.nio.file.Files.getLastModifiedTime(realJar).toMillis();
                String fingerprint = size + "\t" + mtime + "\t" + mappingsFingerprint;

                if (fingerprint.equals(cache.get(fileName)) && java.nio.file.Files.isRegularFile(outJar)) {
                    skippedCount++;
                } else {
                    patchJar(realJar, outJar);
                    patchedCount++;
                }

                newCache.put(fileName, fingerprint);
            }
        }

        saveModsTxt(modsTxt, newCache);

        LOGGER.info("Processed mods/modsreal -> mods: " + patchedCount + " patched, "
                + skippedCount + " unchanged (skipped, per mods/mods.txt) "
                + "(driven by mappings/mappings.tiny)");
    }

    /** {@code fileName -> "size\tmtimeMillis\tmappingsFingerprint"}, one line per entry. */
    private static java.util.Map<String, String> loadModsTxt(java.nio.file.Path modsTxt) throws java.io.IOException {
        java.util.Map<String, String> cache = new java.util.LinkedHashMap<>();
        if (!java.nio.file.Files.isRegularFile(modsTxt)) return cache;

        for (String line : java.nio.file.Files.readAllLines(modsTxt, java.nio.charset.StandardCharsets.UTF_8)) {
            int firstTab = line.indexOf('\t');
            if (firstTab < 0) continue;
            cache.put(line.substring(0, firstTab), line.substring(firstTab + 1));
        }

        return cache;
    }

    private static void saveModsTxt(java.nio.file.Path modsTxt, java.util.Map<String, String> cache) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated by modloader-agent. Tracks which mods/modsreal/*.jar have already\n");
        sb.append("# been patched into mods/, so unchanged jars aren't redundantly re-patched on every\n");
        sb.append("# startup. Safe to delete this file to force everything to be re-patched once.\n");
        for (java.util.Map.Entry<String, String> entry : cache.entrySet()) {
            sb.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        java.nio.file.Files.write(modsTxt, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Cheap fingerprint of mappings/mappings.tiny's own current content (size + CRC32), so any
     * edit to that file invalidates mods/mods.txt's cache for every mod jar, forcing a full
     * re-patch — otherwise editing mappings.tiny would silently have no effect on already-cached
     * (skipped) jars.
     */
    private static String computeMappingsFingerprint() throws java.io.IOException {
        try (java.io.InputStream in = Pclip.class.getClassLoader().getResourceAsStream("mappings/mappings.tiny")) {
            if (in == null) return "absent";

            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                crc.update(buf, 0, n);
                total += n;
            }
            return total + "-" + crc.getValue();
        }
    }

    /**
     * Reads {@code inputJar} fully into memory, patches it (see {@link #patchJarBytes}), and
     * writes the result to {@code outputJar}.
     */
    private static void patchJar(java.nio.file.Path inputJar, java.nio.file.Path outputJar) throws java.io.IOException {
        byte[] inputBytes = java.nio.file.Files.readAllBytes(inputJar);
        byte[] outputBytes = patchJarBytes(inputBytes, inputJar.getFileName().toString());
        java.nio.file.Files.write(outputJar, outputBytes);
        LOGGER.info("Patched " + inputJar.getFileName() + " -> mods/" + outputJar.getFileName());
    }

    /**
     * Patches a jar held entirely in memory: every {@code .class} entry goes through
     * {@link net.fabricmc.loader.impl.transformer.PaperModSignatureCompat#fixIfNeeded}; every
     * NESTED jar entry — Fabric's own "jar-in-jar" convention, typically
     * {@code META-INF/jars/*.jar}, declared in a mod's {@code fabric.mod.json}'s {@code "jars"}
     * array, used by mods that bundle shaded libraries (e.g. mixinextras) or sibling mods
     * directly inside their own jar — is recursively patched too, to any nesting depth, since a
     * mod's own {@code @Mixin} class needing this fix might live inside one of those instead of
     * at the outer jar's top level. Everything else is copied through byte-for-byte, unchanged.
     *
     * <p>Uses {@link java.util.zip.ZipFile} (random-access, reads the Central Directory) rather
     * than {@link java.util.zip.ZipInputStream}/{@link java.util.jar.JarInputStream} (streaming,
     * reads Local File Headers). Both of JDK's streaming ZIP readers share
     * {@code ZipInputStream.readLOC()}, which throws
     * {@code ZipException: only DEFLATED entries can have EXT descriptor} for any STORED entry
     * that also uses a Data Descriptor (general-purpose bit 3) — a combination produced by
     * several common jar-building tools (Gradle Shadow with certain settings, 7-Zip repacks,
     * older Ant/Maven configurations). {@code ZipFile} sidesteps this entirely by reading the
     * Central Directory up front, where every entry's real offset/size/method is recorded
     * unambiguously. The trade-off is that {@code ZipFile} needs a real file on disk, so
     * {@code jarBytes} is first written to a temporary file (deleted in {@code finally}).
     *
     * <p>Every output entry is written as DEFLATED (ZipOutputStream's default), so even a
     * STORED+EXT input entry round-trips cleanly, and a subsequent re-patch of the same jar
     * won't hit the original error again. The manifest, being just another entry from ZipFile's
     * point of view, is preserved automatically — no manual manifest handling needed.
     */
    private static byte[] patchJarBytes(byte[] jarBytes, String displayPath) throws java.io.IOException {
        // ZipFile requires a real file on disk — write to a temp file first.
        java.nio.file.Path tempIn = java.nio.file.Files.createTempFile("pclip-in-", ".jar");
        java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream();

        try {
            java.nio.file.Files.write(tempIn, jarBytes);

            try (java.util.zip.ZipFile zipIn = new java.util.zip.ZipFile(tempIn.toFile())) {
                try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(byteOut)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipIn.entries();

                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        byte[] data = entry.isDirectory() ? null : readAll(zipIn.getInputStream(entry));
                        String name = entry.getName();

                        if (data != null && name.endsWith(".class")) {
                            String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                            data = net.fabricmc.loader.impl.transformer.PaperModSignatureCompat.fixIfNeeded(className, data);
                        } else if (data != null && name.endsWith(".jar")) {
                            LOGGER.fine("Recursing into nested jar-in-jar entry: " + displayPath + "!/" + name);
                            data = patchJarBytes(data, displayPath + "!/" + name);
                        }

                        java.util.zip.ZipEntry newEntry = new java.util.zip.ZipEntry(name);
                        // Copy the original timestamp (so reproducible builds / mod caches that
                        // key on mtime keep working). Deliberately DON'T copy setMethod()/
                        // setSize()/setCompressedSize() from the input entry: forcing DEFLATED
                        // (the default for ZipOutputStream) is what prevents re-introducing the
                        // exact EXT-descriptor problem this method exists to work around.
                        if (entry.getTime() != -1) {
                            newEntry.setTime(entry.getTime());
                        }
                        out.putNextEntry(newEntry);
                        if (data != null) out.write(data);
                        out.closeEntry();
                    }
                }
            }
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(tempIn);
            } catch (java.io.IOException ignored) {
                // best-effort cleanup only
            }
        }

        return byteOut.toByteArray();
    }

    /** Reads the given InputStream's full content into a byte array. */
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static void runFabricServerLauncher(KnotCompatibilityClassLoader theOneClassLoader, String[] args) {
        try {
            // Paper - set Knot's own externalClassLoader field to THIS SAME instance (via
            // reflection, THROUGH theOneClassLoader) before Knot.init() ever runs, so Knot reuses
            // it instead of constructing a second, brand new loader object. Reflection is required
            // here (rather than a plain static field assignment) so that "Knot" resolves to the
            // exact same Class object that FabricServerLauncher.main() below will itself use
            // internally — both loaded through theOneClassLoader. A plain compile-time
            // "Knot.externalClassLoader = ..." from this file would instead resolve Knot.class
            // through whichever loader defined Pclip.class itself, a different loader entirely,
            // silently setting the field on an unrelated, never-used copy of Knot.
            Class<?> knotClass = Class.forName(
                    "net.fabricmc.loader.impl.launch.knot.Knot", true, theOneClassLoader);
            knotClass.getField("externalClassLoader").set(null, theOneClassLoader);

            // Loading FabricServerLauncher THROUGH theOneClassLoader is what makes it become
            // Fabric Loader's own "originalLoader"/classloader identity everywhere downstream.
            Class<?> launcherClass = Class.forName(
                    "net.fabricmc.loader.impl.launch.server.FabricServerLauncher", true, theOneClassLoader);
            Method mainMethod = launcherClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Fatal error running FabricServerLauncher", t);
            sneakyThrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}