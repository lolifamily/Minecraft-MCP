package org.js.lolifamily.minecraftmcp.repl;

import org.js.lolifamily.minecraftmcp.AtomicFiles;
import org.js.lolifamily.minecraftmcp.Constants;
import org.js.lolifamily.minecraftmcp.Props;
import org.js.lolifamily.minecraftmcp.exec.Capture;
import org.js.lolifamily.minecraftmcp.patch.Instrumentations;
import org.js.lolifamily.minecraftmcp.platform.Services;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Game-loader side of the REPL. Builds the {@link MaskingClassLoader}, crosses into the masking world
 * with a single reflective bootstrap of {@code ...repl.impl.MaskingBridgeImpl} — reached type-safely
 * thereafter through the shared {@link MaskingBridge} interface — and caches the result so every
 * {@link #compile(String, String, int)} / {@link #execute(Object, String, Capture)} after the first is a warm call. The
 * same bridge also fronts the remap-cache builder ({@link #buildRemapArtifacts}), so ALL masking-only calls
 * share this one hop.
 *
 * <p>Why reflection: {@code MaskingBridgeImpl} and its backends ({@code ReplHost}, {@code RemapCacheBuilder})
 * link the Kotlin scripting / remapper APIs, which live only on the masking loader's urls (see
 * {@link MaskingClassLoader} for why they cannot sit on the game's module path). The game loader therefore
 * cannot link them by name, and this one bootstrap hop is the whole cost of that isolation.
 *
 * <p>Only types the masking loader delegates to the parent may cross the boundary: {@code java.*},
 * {@link Object}, and the mod's own classes outside {@code repl.impl} — which is also why
 * {@link MaskingBridge} itself must stay in {@code ...repl}; see {@link MaskingClassLoader}.
 */
public final class ReplBridge {

    private static final Object LOCK = new Object();
    // The masking-side backends (ReplHost + RemapCacheBuilder), via the one bootstrap in ensureMasking().
    private static volatile MaskingBridge host;
    private static volatile List<File> classpath;
    private static volatile MaskingClassLoader masking; // built once by maskingLoader()
    private static volatile boolean maskingReady = false; // masking loader built + MaskingBridge resolved

    // ---- Eager background warmup (see CommonClass.init + startBackgroundWarmup) -------------------------------
    // The whole first-warm is moved OFF the first execute_code and onto background threads at init, so the first
    // eval is already warm. Two independent init tasks run in parallel (they don't contend — remap is I/O/subprocess,
    // preload is CPU): a remap thread (fetch mojmap symbol jars) and a preload thread (load the compiler working
    // set). A gate thread waits for BOTH (warmupLatch), then builds the compiler + runs one dummy eval. NONE of
    // this runs on the game init thread — init() only spawns and returns, never blocks game startup.
    // The four ①②③④ timestamps feed one startup-breakdown log at the end of startBackgroundWarmup; warmDone
    // (read by McpServer) and warmupLatch are what control flow actually gates on.
    /** ①: {@code CommonClass.init} trigger — the baseline the other three timestamps are relative to. */
    public static volatile long initStartNanos = 0;
    /** ②: remap symbols ready ({@code ~= initStartNanos} on a mojmap runtime, where no remap is needed). */
    public static volatile long remapDoneNanos = 0;
    /** ③: compiler working set preloaded ({@code ~= initStartNanos} on a training launch). */
    public static volatile long preloadDoneNanos = 0;
    /** ④: compiler built + one dummy snippet compiled and run — the first eval is now warm. */
    public static volatile long warmDoneNanos = 0;
    /** True once warmup finishes; {@code McpServer} gates {@code execute_code} on this (set even on failure). */
    public static volatile boolean warmDone = false;
    /** No recorded working-set list at start ⇒ this launch trains one. */
    public static volatile boolean trainingMode = false;
    /** Counted down by the remap thread and the preload thread; the gate thread awaits both before building. */
    public static final CountDownLatch warmupLatch = new CountDownLatch(2);
    /** Gates {@link #buildClasspath} on a host that has plugins — not on their jars, which are all loaded before
     *  any is enabled, but on the urls a plugin attaches to its own loader inside {@code onEnable}, which
     *  {@link PluginJarCollector} reads live. Opened by {@code MixinDedicatedServer} at {@code initServer}'s
     *  RETURN, by paper's {@code ServerLoadEvent} listener, or here at warmup start where there is no Bukkit
     *  layer. Untimed: no constant tells a slow startup from a signal that will never come, so
     *  {@code mcp.plugins.gate=false} is the way past one that never does. */
    public static final CountDownLatch pluginsLatch = new CountDownLatch(1);

    private ReplBridge() {}

    /** The mod's own writable cache dir, asked of the loader — see {@code IPlatformHelper.cacheDir}. */
    private static File mcpCacheDir() {
        return Services.INSTANCE.getPLATFORM().getCacheDir().toFile();
    }

    /**
     * Compile {@code code} to an opaque handle, OFF any tick — pure computation, no game state. The
     * handle is a masking-loaded type the game loader only ever passes back to {@link #execute(Object, String, Capture)}.
     *
     * @param code        the snippet source to compile
     * @param killIdField name of the target lane's scriptguard kill-id field, woven into the snippet as a
     *                    per-tick timeout check, or {@code ""} for the off-tick parallel lane (no watchdog)
     * @param evalId      the value that field must equal for THIS eval's woven check to fire
     * @return an opaque compiled-script handle for {@link #execute(Object, String, Capture)}
     * @throws Exception if masking-loader init or compilation fails
     */
    public static Object compile(String code, String killIdField, int evalId) throws Exception {
        ensureInit();
        return host.compile(code, classpath, killIdField, evalId);
    }

    /**
     * Run a handle from {@link #compile(String, String, int)} and return an {@code exec.Outcome} (result text +
     * isError) or, if the snippet's value was a stdlib {@code iterator { ... yield() ... }}, an
     * {@code exec.IterEval} for the lane to drive one step per tick.
     *
     * @param handle a handle from {@link #compile(String, String, int)}
     * @param code   the original source, so compile diagnostics can echo the offending line
     * @param out    the sink the snippet's {@code println} writes to; the caller owns it, so a kill, cancel or
     *               timeout can still report what was printed before the eval ended
     * @return an {@code exec.Outcome}, or an {@code exec.IterEval} for a cross-tick iterator snippet
     */
    public static Object execute(Object handle, String code, Capture out) {
        try {
            return host.execute(handle, code, out);
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            Constants.LOG.error("[mcp-repl] execute bridge error", cause);
            String report = "REPL bridge error: " + cause;
            // Take `out` like every ReplHost error path does; deferred, so the copy stays off the tick thread.
            return new org.js.lolifamily.minecraftmcp.exec.Outcome(true, () -> EvalRender.INSTANCE.combine(out.take(), report));
        }
    }

    /** Build the masking loader + instantiate the MaskingBridge. Remap-independent, so the preload thread can run
     *  it while the remap download is still going. Idempotent. */
    // maskingLoader() returns the shared, cached `masking` field, alive for host's whole lifetime — it must
    // NOT be closed here. IDEA's resource check can't see that ownership, so the false positive is suppressed.
    @SuppressWarnings("resource")
    static void ensureMasking() throws Exception {
        if (maskingReady) return;
        synchronized (LOCK) {
            if (maskingReady) return;
            Class<?> impl = maskingLoader().loadClass("org.js.lolifamily.minecraftmcp.repl.impl.MaskingBridgeImpl");
            host = (MaskingBridge) impl.getDeclaredConstructor().newInstance();
            maskingReady = true;   // published last: makes masking + host visible together
        }
    }

    /** Assemble the compile classpath (game cp + all mods + JiJ libs + mojmap remap symbols + MC API deps).
     *  Remap-DEPENDENT: reads {@link RemapBundle}, which the remap thread publishes — so this must run AFTER
     *  the remap symbols are ready (the warmup gate enforces it). Idempotent. */
    static void buildClasspath() throws Exception {
        if (classpath != null) return;
        pluginsLatch.await();                // already open unless this host loads plugins after us
        synchronized (LOCK) {
            if (classpath != null) return;
            Class<?> anchor = net.minecraft.world.level.block.Blocks.class;
            final ClassLoader gl = anchor.getClassLoader();
            // A re-add never moves an existing entry, so insertion order IS classpath priority: first wins.
            LinkedHashSet<File> cp = ClasspathCollector.collect(anchor);
            // Add the backing jars of every loaded mod (fabric-api + all mods, incl. jar-in-jar) so scripts can
            // import mod APIs at compile time. Closes the Fabric gap (mods live on Knot, off java.class.path and
            // off any module layer) and jar-in-jar on every loader; ClasspathCollector already covers top-level
            // mods on NeoForge/Forge.
            cp.addAll(ModJarCollector.collect());
            // Plugins and their declared libraries on a plugin-bearing host; empty on a plain mod runtime.
            cp.addAll(PluginJarCollector.collect());
            // Extract our own mod jar's jar-in-jar libraries (fabric `include` -> META-INF/jars/, neoforge/forge
            // `jarJar` -> META-INF/jarjar/) into the compile cp. byte-buddy and friends ride the game loader at
            // runtime but sit off every enumeration path — not mods, not under .minecraft/libraries — so without
            // this a script's `import net.bytebuddy.*` won't compile. No dev guard: only a jar can carry nested
            // jars, so extractEmbedded returns empty from a classes dir (see selfJar) — and dev's enumeration
            // already sees these as plain deps. Kept out of `cp` because they are prepended below.
            LinkedHashSet<File> ours = new LinkedHashSet<>();
            for (String jijPrefix : new String[] { "META-INF/jarjar/", "META-INF/jars/" }) {
                // No filename filter — ReplHost.buildCompiler dedups by the set of types each jar defines,
                // so a duplicate stdlib collapses there.
                for (URL u : extractEmbedded(jijPrefix)) ours.add(new File(u.toURI()));
            }
            // Expected 0 in dev (no jar to extract from). A 0 in PRODUCTION is the symptom to chase if a
            // script's `import net.bytebuddy.*` stops compiling.
            Constants.LOG.info("[mcp-repl] jar-in-jar libs added to compile cp: {}", ours.size());
            // The patch bridge, by path: appendToBootstrapClassLoaderSearch publishes no CodeSource, no module
            // and no url list, so nothing above can enumerate it. Compile-cp only — the bootstrap copy stays the
            // one runtime identity, which loadDependencies(false) in ReplHost is what guarantees.
            File bridge = Instrumentations.getBridgeJar();
            if (bridge != null) cp.add(bridge);
            // On a non-mojmap runtime the enumerated MC jar is intermediary, so the Kotlin compiler can't resolve
            // mojmap script imports (BuiltInRegistries etc.) against it. Prepend the bundled mojmap symbol jars.
            // Non-null implies a non-mojmap runtime — RemapCache.provision publishes nothing on mojmap.
            RemapBundle bundle = RemapBundle.current();
            // The one place classpath priority is decided. Downstream may DROP an entry; it must never move one.
            LinkedHashSet<File> front = new LinkedHashSet<>();
            // FIRST, not last. An SRG runtime keeps MOJMAP CLASS NAMES and obfuscates only members, so its
            // MC jar declares the same FQNs as mc-symbols.jar. The classpath is first-wins: appended, the
            // runtime jar would answer for every mojmap class and every member read resolve to an obf name.
            if (bundle != null) front.add(bundle.getSymbolsJar().toFile());
            // Then ours: fabric-language-kotlin ships an srg-remapped kotlin-stdlib, and the compiler can only
            // link the kotlin it RUNS on.
            front.addAll(ours);
            front.addAll(cp);
            cp = front;
            if (bundle != null) {
                // The mojmap symbol jar's signatures reference MC's API deps (e.g. Registry extends
                // com.mojang.serialization.Keyable), which the production fabric bundler hides from the
                // enumeration above, so the compiler can't resolve those supertypes. RemapCacheBuilder harvested
                // exactly the types those signatures reference into <symbolsDir>/deps.txt; probe each on the game
                // loader to recover its backing jar (version-agnostic, no download — the runtime already links
                // them). The list is a superset of what a dedicated server needs (the symbol jar is client-
                // mappings-derived): client-only names simply fail to resolve here and are skipped by addRuntimeDep.
                appendApiDepJars(cp, gl, bundle);
            }

            classpath = new ArrayList<>(cp);   // published last: makes the whole classpath visible atomically
            Constants.LOG.info("[mcp-repl] REPL classpath ready ({} files)", cp.size());
        }
    }

    /**
     * Probe each type listed in the bundle's {@code deps.txt} on the game loader and add its backing jar to
     * {@code cp}, recovering the MC API-dep jars (e.g. mojang serialization) the production bundler hides from
     * enumeration. A missing or unreadable deps.txt is non-fatal (logged) — a partial cache, or simply a
     * hand-supplied bundle, which is not required to carry one; client-only names just don't resolve.
     */
    private static void appendApiDepJars(Set<File> cp, ClassLoader gl, RemapBundle bundle) {
        File depsFile = bundle.getDeps().toFile();
        if (!depsFile.isFile()) {
            Constants.LOG.warn("[mcp-remap] no {} — MC API-dep jars may be missing from the compile cp", depsFile);
            return;
        }
        int before = cp.size();
        try {
            for (String probe : Files.readAllLines(depsFile.toPath())) {
                String p = probe.trim();
                if (!p.isEmpty()) addRuntimeDep(cp, gl, p);
            }
        } catch (Exception e) {
            Constants.LOG.warn("[mcp-remap] failed reading {}; MC API-dep jars may be missing from cp", depsFile, e);
        }
        Constants.LOG.info("[mcp-remap] deps.txt probes added {} MC API-dep jar(s) to compile cp", cp.size() - before);
    }

    /** Lazy fallback: a direct compile/eval arriving before the eager background warmup finished ensures both
     *  halves itself. In the normal (eager) path both are already done, so these are no-ops. */
    private static void ensureInit() throws Exception {
        ensureMasking();
        buildClasspath();
    }

    /** Whether the runtime carries a Bukkit layer at all. A false negative only costs the plugin wait; a false
     *  positive waits for a level load, which every server does. */
    private static boolean bukkitPresent() {
        try {
            Class.forName("org.bukkit.Bukkit", false, Constants.GAME_LOADER);
            return true;
        } catch (Throwable noBukkit) {
            return false;
        }
    }

    /** Called by the remap init (CommonClass) the moment mojmap symbols are ready — or immediately on a mojmap
     *  runtime where no remap is needed. Releases the warmup gate's remap half. */
    public static void remapReady() {
        remapDoneNanos = System.nanoTime();
        warmupLatch.countDown();
    }

    /** Spawn the warmup threads described above: preload, and the gate that awaits both halves. Called once from
     *  CommonClass.init. Both run below-normal priority to yield to the game's own startup work. */
    public static void startBackgroundWarmup() {
        // Decided once, here. Anything carrying a Bukkit layer waits for its signal — see pluginsLatch for what
        // the wait is for, which is NOT whether the plugin jars are enumerable yet. No Bukkit means no plugins,
        // so there is nothing to wait on and the latch opens right here.
        //
        // isDedicatedServer too: every opener is a dedicated-server signal, so a CLIENT carrying Bukkit classes
        // (Cardboard) would wait on a latch nothing opens. Waiting would buy nothing either — a client-side layer
        // loads its plugins long after this snapshot, so they are reflection-only there whatever we do.
        //
        // The escape hatch, for a server that never finishes loading: the snapshot then lands wherever warmup
        // happens to reach it, so the overlay's env shard may differ between launches and rebuild. Asked for,
        // so not ours to stabilize.
        if (!bukkitPresent() || !Services.INSTANCE.getPLATFORM().isDedicatedServer() || !Props.bool("mcp.plugins.gate", true)) {
            pluginsLatch.countDown();
        }

        Thread p = new Thread(() -> {
            try {
                ensureMasking();
                trainingMode = host.preload();
            } catch (Throwable t) {
                Constants.LOG.warn("[mcp-repl/warm] preload failed (degraded)", t);
            } finally {
                preloadDoneNanos = System.nanoTime();
                warmupLatch.countDown();
            }
        }, "mcp-preload");
        p.setDaemon(true);
        p.setPriority(Thread.NORM_PRIORITY - 2);
        p.setContextClassLoader(Constants.GAME_LOADER);
        p.start();

        Thread g = new Thread(() -> {
            try {
                warmupLatch.await();
                buildClasspath();
                host.buildCompiler(classpath);
                host.warm();                                     // runs on a training launch too
                if (trainingMode) {                              // first launch: capture the true working set for next time
                    File list = new File(mcpCacheDir(), "preload-classlist.txt");
                    host.recordWorkingSet(list.getAbsolutePath());
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[mcp-repl/warm] compiler warmup failed (first eval will lazy-rebuild)", t);
            } finally {
                warmDoneNanos = System.nanoTime();
                warmDone = true;
                // The one read of the ①②③④ timestamps: log the startup breakdown. remap and preload ran in
                // parallel, so the gate opened at max(②,③) and the serial build phase is ④ − that.
                long gateOpen = Math.max(remapDoneNanos, preloadDoneNanos);
                Constants.LOG.info("[mcp-repl/warm] warmup done in {}ms (remap {}ms, preload {}ms, compiler build {}ms)",
                        (warmDoneNanos - initStartNanos) / 1_000_000,
                        (remapDoneNanos - initStartNanos) / 1_000_000,
                        (preloadDoneNanos - initStartNanos) / 1_000_000,
                        (warmDoneNanos - gateOpen) / 1_000_000);
            }
        }, "mcp-warmup-gate");
        g.setDaemon(true);
        g.setPriority(Thread.NORM_PRIORITY - 2);
        g.setContextClassLoader(Constants.GAME_LOADER);
        g.start();
    }

    /** The masking loader, built once and shared by every masking-only entry point. */
    private static MaskingClassLoader maskingLoader() throws Exception {
        MaskingClassLoader m = masking;
        if (m != null) return m;
        synchronized (LOCK) {
            if (masking != null) return masking;
            // GAME_LOADER, not MC_LOADER: on a plugin host the latter is the SERVER loader, the plugin loader's
            // PARENT, which cannot see our classes — and mustDelegateToParent sends them all there. Ours
            // resolves both; this class's own body links Blocks, so MC is never out of reach.
            ClassLoader gl = Constants.GAME_LOADER;
            URL[] urls = buildMaskingUrls();
            Constants.LOG.info("[mcp-repl] building masking loader over {} urls, parent={}", urls.length, gl);
            masking = new MaskingClassLoader(urls, gl);
            return masking;
        }
    }

    /**
     * Cross into the masking loader (mapping-io + tiny-remapper live only there) to assemble the
     * remap mappings and reverse-remap the runtime jar into a mojmap symbol jar. Called by {@link RemapCache}
     * during init on a non-mojmap runtime with no {@code mcp.remap.*} flags.
     *
     * @param clientTxt     path to the Mojang client mappings ({@code client.txt})
     * @param secondSource  the runtime's CLASS names, which {@code clientTxt} cannot supply — told apart by
     *                      extension. Either the fabric intermediary MAPPINGS jar
     *                      ({@code net.fabricmc:intermediary:<ver>:v2}), read for its
     *                      {@code mappings/mappings.tiny}, or spigot BuildData's {@code bukkit-<ver>-cl.csrg}.
     *                      NOT a Minecraft jar either way; the jar that gets reverse-remapped is {@code mcUri}.
     * @param mcUri         CodeSource URI of the runtime Minecraft jar — the LOADER's view of it, so on FML a
     *                      {@code union:} URI whose overlaid binpatches are part of what gets reverse-remapped
     * @param outMappings   output path for the assembled mappings
     * @param outSymbolsDir output directory for the generated mojmap symbol jar(s)
     * @throws Exception if masking-loader init or artifact building fails
     */
    public static void buildRemapArtifacts(String clientTxt, String secondSource, String mcUri,
                                           String outMappings, String outSymbolsDir) throws Exception {
        ensureMasking();
        host.buildArtifacts(clientTxt, secondSource, mcUri, outMappings, outSymbolsDir);
    }

    /**
     * Forge Mixed-SRG analog of {@link #buildRemapArtifacts}: assemble {@code srg_to_official.tsrg} from
     * MCPConfig {@code joinedTsrg} (obf → srg) + Mojang {@code clientTxt}, and reverse-remap the Mixed-SRG
     * runtime jar into a mojmap symbol jar.
     *
     * @param joinedTsrg    path to MCPConfig's {@code joined.tsrg} (obf → srg)
     * @param clientTxt     path to the Mojang client mappings ({@code client.txt})
     * @param mcUri         CodeSource URI of the Mixed-SRG runtime Minecraft jar — see
     *                      {@link #buildRemapArtifacts}
     * @param outMappings   output path for the assembled mappings
     * @param outSymbolsDir output directory for the generated mojmap symbol jar(s)
     * @throws Exception if masking-loader init or artifact building fails
     */
    public static void buildForgeArtifacts(String joinedTsrg, String clientTxt, String mcUri,
                                           String outMappings, String outSymbolsDir) throws Exception {
        ensureMasking();
        host.buildForgeArtifacts(joinedTsrg, clientTxt, mcUri, outMappings, outSymbolsDir);
    }

    /**
     * Masking-loader urls: the Kotlin scripting stack (never on the module path) + the source of the
     * {@code repl.impl} classes, so {@code ReplHost} resolves child-first.
     *
     * <p>Dev takes both from jvm args ({@code mcp.kotlin.libs} staging dir, {@code mcp.repl.classes}
     * classes dir); production extracts {@code mcp-kotlin/*.jar} out of our own mod jar and adds that mod
     * jar itself.
     */
    private static URL[] buildMaskingUrls() throws Exception {
        List<URL> urls = new ArrayList<>();

        String libs = Props.str("mcp.kotlin.libs");
        if (libs != null) {
            // dev: the scripting stack was staged to a dir by copyMcpKotlin and pointed at via flag.
            File dir = new File(libs);
            File[] jars = dir.listFiles(f -> f.getName().endsWith(".jar"));
            if (jars != null) {
                // listFiles is unordered by contract, and child-first loading settles a class two jars both
                // carry by URL order alone. Same canonical order as the production path's nested.sort.
                Arrays.sort(jars, Comparator.comparing(File::getName));
                for (File jar : jars) urls.add(jar.toURI().toURL());
            }
            Constants.LOG.info("[mcp-repl] kotlin libs: {} jars from {} (dev flag)", (jars == null ? 0 : jars.length), dir);
        } else {
            // production
            List<URL> embedded = extractEmbedded("mcp-kotlin/");
            urls.addAll(embedded);
            Constants.LOG.info("[mcp-repl] kotlin libs: {} jars extracted from mod jar (production)", embedded.size());
        }

        String classes = Props.str("mcp.repl.classes");
        if (classes != null) {
            urls.add(new File(classes).toURI().toURL()); // dir URI ends with '/', treated as a dir root
            Constants.LOG.info("[mcp-repl] repl classes dir: {} (dev flag)", classes);
        } else {
            // production: ReplHost lives in the mod jar itself; add it so the masking loader
            // resolves repl.impl.* child-first from there. Resolve the CodeSource to a real jar file first
            // (see JarLocator) — a URLClassLoader cannot read what NeoForge/Forge hand back.
            // NOT selfJar(): a URLClassLoader is equally happy with a classes DIR, which is what this
            // resolves to in dev with no mcp.repl.classes — there it stands in for the flag.
            File own = JarLocator.toJarFile(modJarLocation());
            if (own != null) {
                urls.add(own.toURI().toURL());
                Constants.LOG.info("[mcp-repl] repl classes from own location: {}", own);
            } else {
                Constants.LOG.warn("[mcp-repl] cannot locate mod jar for repl classes");
            }
        }

        return urls.toArray(new URL[0]);
    }

    /** Our own mod jar, via this class's CodeSource — the anchor for production self-extraction. */
    private static URL modJarLocation() {
        try {
            return ReplBridge.class.getProtectionDomain().getCodeSource().getLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Our own mod jar, or null when we run from a classes dir (dev): {@link JarLocator#toJarFile} resolves
     *  that location to the DIRECTORY, hence the isFile check. */
    private static File selfJar() {
        File f = JarLocator.toJarFile(modJarLocation());
        return (f != null && f.isFile()) ? f : null;
    }

    /** Add the jar backing {@code className} (via its CodeSource on the game loader) to the compile classpath,
     *  if it resolves to a real file not already present. Used to recover MC's API-dependency jars on a
     *  production runtime whose classpath enumeration is incomplete. Silently skips anything absent. */
    private static void addRuntimeDep(Set<File> cp, ClassLoader gl, String className) {
        try {
            Class<?> c = Class.forName(className, false, gl);
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile() && cp.add(f)) {
                    Constants.LOG.info("[mcp-remap] added runtime API dep {} -> {}", className, f.getName());
                }
            }
        } catch (Throwable ignored) {
            /* not present on this runtime — skip */
        }
    }

    /**
     * Production fallback: extract the nested jars under {@code prefix} (e.g. {@code mcp-kotlin/}) from our
     * own mod jar into a stable cache dir, revalidated by a CRC stamp so a rebuild re-extracts only what
     * actually changed. Returns their file URLs (for the masking loader), or an empty list in dev — there is
     * no jar to extract from, and the flags/enumeration already cover those paths.
     */
    private static List<URL> extractEmbedded(String prefix) throws Exception {
        List<URL> out = new ArrayList<>();
        File modJar = selfJar();
        if (modJar == null) {
            // Running from a classes dir (dev): nothing is embedded, so nothing to extract. Not an error —
            // the caller's own "extracted N jars" log is where a broken production launch shows up as 0.
            Constants.LOG.debug("[mcp-repl] not running from a jar; no embedded {} to extract", prefix);
            return out;
        }
        // Cache under the mod's cache dir alongside the overlay + remap caches, not java.io.tmpdir (the OS purges
        // tmp, forcing needless re-extract). Stable path, so the compile-cp identity holds across launches.
        // Validity is a per-dir stamp of "name|size|crc" per nested jar, read from the mod jar's central directory
        // (no decompression): a changed jar invalidates even at identical name+size, a byte-identical rebuild does not.
        File cacheDir = new File(mcpCacheDir(), "embedded/" + prefix);
        Files.createDirectories(cacheDir.toPath());
        File stampFile = new File(cacheDir, ".stamp");
        try (JarFile jf = new JarFile(modJar)) {
            List<JarEntry> nested = new ArrayList<>();
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith(prefix) && name.endsWith(".jar")) nested.add(e);
            }
            nested.sort(Comparator.comparing(JarEntry::getName));   // canonical order → deterministic stamp
            StringBuilder sb = new StringBuilder();
            for (JarEntry e : nested) {
                sb.append(e.getName()).append('|').append(e.getSize()).append('|').append(e.getCrc()).append('\n');
                out.add(new File(cacheDir, e.getName().substring(prefix.length())).toURI().toURL());
            }
            String expected = sb.toString();
            // Fresh iff the stamp matches AND every extracted file still exists — a manual delete would
            // otherwise leave a stale stamp behind.
            boolean fresh = stampFile.isFile()
                    && expected.equals(Files.readString(stampFile.toPath()));
            if (fresh) {
                for (JarEntry e : nested) {
                    if (!new File(cacheDir, e.getName().substring(prefix.length())).isFile()) {
                        fresh = false;
                        break;
                    }
                }
            }
            if (!fresh) {
                for (JarEntry e : nested) {
                    // No mkdirs: nothing nests under prefix, so dest's parent is cacheDir, created above.
                    File dest = new File(cacheDir, e.getName().substring(prefix.length()));
                    // Buffer the destination: transferTo()'s 8KB default would issue one write() syscall per chunk
                    // across the whole extraction. (The read side stays 8KB — those are in-memory inflater reads.)
                    AtomicFiles.publishing(dest.toPath(), tmp -> {
                        try (InputStream in = jf.getInputStream(e);
                                OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp.toFile()), 1 << 16)) {
                            in.transferTo(os);
                        }
                    });
                }
                Files.writeString(stampFile.toPath(), expected);
                AtomicFiles.fsync(stampFile.toPath());
            }
            Constants.LOG.info("[mcp-repl] {} {} jars from {} -> {}", fresh ? "reused" : "extracted", nested.size(), prefix, cacheDir);
        }
        return out;
    }
}
