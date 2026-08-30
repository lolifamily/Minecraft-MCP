package org.js.lolifamily.minecraftmcp.repl;

import org.js.lolifamily.minecraftmcp.Constants;
import org.js.lolifamily.minecraftmcp.Props;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classloader for the REPL's Kotlin scripting stack.
 *
 * <p>It hosts the entire Kotlin scripting stack (kotlin-compiler-embeddable + scripting-* + stdlib +
 * reflect + script-runtime) plus the REPL impl's own Kotlin classes, on a self-managed
 * URLClassLoader that lives OFF the FML/securejarhandler module path. That is mandatory on NeoForge:
 * putting kotlin-compiler-embeddable on the module path makes securejarhandler throw
 * "Invalid package name: 'native' is not a Java identifier" at boot (the compiler ships a package
 * {@code org.jetbrains.kotlin.native.interop}, and {@code native} is a Java keyword).
 *
 * <p>The compiler ({@code org.jetbrains.kotlin.*}), the shaded intellij/etc. inside compiler-embeddable,
 * and the REPL impl classes under {@code ...repl.impl.*} are ALWAYS child-first (masking-owned). The JDK,
 * MC, and the mod's own non-impl classes are ALWAYS parent-first so they keep a single shared identity
 * with the game loader.
 *
 * <h2>The kotlin RUNTIME ({@code kotlin.*} stdlib/reflect) is version-regime dependent</h2>
 * A REPL snippet must exchange kotlin objects (lambdas = {@code Function0}, {@code Pair}, {@code Sequence},
 * …) with the game's mods, which use the GAME loader's kotlin. If the masking loader defines its OWN copy
 * of those classes, a snippet value is a DIFFERENT class from the mod's parameter type → {@code LinkageError}.
 * One shared kotlin identity is the only fully-safe unification, and it needs the versions to line up — hence
 * {@link Regime}, overridable with {@code mcp.kotlin.regime=auto|share|split}.
 *
 * <h2>A feature that would otherwise need a JVM agent</h2>
 * Because every masking-owned class is defined right here in {@link #findClass}, this loader is the single
 * door those classes' bytes come through — which makes {@code java.lang.instrument} unnecessary for anything
 * scoped to them. So the preload working-set capture works on a JVM launched WITHOUT
 * {@code -Djdk.attach.allowAttachSelf} / {@code -XX:+EnableDynamicAgentLoading}: {@link #definedClassNames()}
 * answers "what did this loader define?" from its own bookkeeping.
 *
 * <p>The live-patch engine ({@code Patches.onEnter/onExit}) still needs the agent and still dies without those flags:
 * it retransforms already-loaded {@code net.minecraft.*} classes on the GAME loader and injects a jar into
 * the bootstrap loader — neither is something a classloader can do for itself.
 */
public final class MaskingClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** Kotlin-identity regime, decided once from (our bundled kotlin C, the parent/game kotlin P). */
    public enum Regime {
        /** P present and P &gt;= C: delegate the {@code kotlin.*} runtime (see
         *  {@link MaskingClassLoader#isKotlinRuntime}) to the parent — ONE kotlin identity shared with the
         *  game's mods, falling back to our own copy for anything the parent lacks. The compiler then also runs
         *  on the parent's (&gt;= our) stdlib, which is the kotlin-GUARANTEED backward direction (code compiled
         *  against C runs on &gt;= C). */
        SHARE_ALL,
        /** P &lt; C: the compiler stays on our own C (masking child-first for {@code kotlin.*}), so it never runs
         *  on an older stdlib (which would be the un-guaranteed forward direction). Only the SNIPPET loader
         *  ({@link MaskingClassLoader#snippetParentLoader()}) redirects {@code kotlin.*} to the parent's P, and
         *  snippets pin {@code -api-version} to P so they only use APIs P actually has. */
        SPLIT
    }

    private final Regime regime;
    private final int parentKotlin;              // P packed (see kotlinVersionOf); -1 if the game loader has no kotlin
    public final int ownKotlin;                  // C packed; -1 if our own urls have no kotlin
    private volatile ClassLoader snippetLoader;  // lazily built snippet parent chain (see snippetParentLoader)
    private final Set<String> defined = ConcurrentHashMap.newKeySet();  // see definedClassNames()

    /**
     * Creates the masking loader over {@code urls}, delegating to {@code parent} (the game loader).
     *
     * @param urls   the masking classpath (our extracted kotlin stack + repl.impl + mod jar)
     * @param parent the game loader
     */
    MaskingClassLoader(URL[] urls, ClassLoader parent) {
        super("mcp-masking", urls, parent);
        this.parentKotlin = kotlinVersionOf(parent);
        this.ownKotlin = probeKotlinVersion(urls);
        this.regime = decideRegime(ownKotlin, parentKotlin);
        Constants.LOG.info("[mcp-mask] kotlin regime = {} (ours C={}, game P={})",
                regime, verString(ownKotlin), verString(parentKotlin));
    }

    // ---- regime decision ---------------------------------------------------------------------

    /** Packed 2.0.0 — the 2.4.x compiler's {@code -api-version} floor. */
    private static final int V2_0 = 2 << 16;

    private static Regime decideRegime(int ours, int parent) {
        String forced = Props.str("mcp.kotlin.regime");   // auto|share|split
        if (forced != null) {
            String f = forced.toLowerCase(Locale.ROOT);
            switch (f) {
                case "share": return Regime.SHARE_ALL;
                case "split": return Regime.SPLIT;
                default:
                    // "auto" is the documented spelling of the default; anything else is a typo, not a choice.
                    if (!"auto".equals(f)) {
                        Constants.LOG.warn("[mcp-mask] unknown mcp.kotlin.regime={}; using auto", f);
                    }
                    break;
            }
        }

        if (ours < 0) return Regime.SHARE_ALL;                       // C unknown: assume a matched pack, share
        // P == -1 only means the read failed — this mod's own classes are kotlin and load on that very loader,
        // so a game loader without kotlin cannot reach here. It falls to SPLIT, which is the safe answer.
        return parent >= ours ? Regime.SHARE_ALL : Regime.SPLIT;
    }

    public Regime regime() {
        return regime;
    }

    /**
     * P as {@code "major.minor"} for the snippet compiler's {@code -api-version}/{@code -language-version} in
     * SPLIT; {@code null} in SHARE_ALL, where the two kotlins are one and no pin is needed.
     *
     * <p>Floored at {@link #V2_0}, which the compiler will not go below. Pinning ABOVE P costs nothing: a
     * snippet compiles against the same kotlin jar it then runs on — the game's — so the compiler cannot offer
     * an API the runtime lacks, whatever the pin allows.
     */
    public String parentApiVersion() {
        if (regime != Regime.SPLIT || parentKotlin < 0) return null;
        if (parentKotlin < V2_0) return "2.0";
        return (parentKotlin >>> 16) + "." + ((parentKotlin >>> 8) & 0xFF);
    }

    /**
     * Parent loader for a compiled snippet: a single {@link SnippetLoader} over masking. ReplHost passes this
     * as the eval {@code baseClassLoader}.
     */
    public ClassLoader snippetParentLoader() {
        ClassLoader s = snippetLoader;
        if (s == null) {
            synchronized (this) {
                s = snippetLoader;
                if (s == null) {
                    s = new SnippetLoader(this, getParent(), regime == Regime.SPLIT);
                    snippetLoader = s;
                }
            }
        }
        return s;
    }

    // ---- loadClass ---------------------------------------------------------------------------

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (mustDelegateToParent(name)) {
                    c = getParent().loadClass(name);
                } else if (regime == Regime.SHARE_ALL && isKotlinRuntime(name)) {
                    // one kotlin identity with the mods; fall back to our own copy for anything the parent lacks —
                    // or can no longer answer for, its loader being closed (see SnippetLoader#load).
                    try {
                        c = getParent().loadClass(name);
                    } catch (ClassNotFoundException | IllegalStateException notInParent) {
                        c = findClass(name);
                    }
                } else {
                    try {
                        c = findClass(name);             // child-first: our own urls (compiler + own kotlin)
                    } catch (ClassNotFoundException e) {
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    // ---- findClass: the single definition site ------------------------------------------------

    /**
     * Every masking-owned class is defined HERE (see the class doc for what that buys), which is also what
     * makes every successfully defined name recordable for {@link #definedClassNames()}.
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> c = super.findClass(name);
        defined.add(name);                          // success only — a CNFE from above never reaches here
        return c;
    }

    /**
     * Every binary name this loader has DEFINED (not merely delegated), for the preload working-set capture.
     * {@link #findClass} is this loader's only definition site.
     *
     * <p>Jar-entry classes only: names {@code loadClass} delegated to the parent are absent (those are the
     * game loader's classes), and JVM-synthesized lambda / hidden classes never come through findClass, so no
     * {@code $$} / {@code /} name filtering is needed.
     */
    public Set<String> definedClassNames() {
        return Set.copyOf(defined);
    }

    /**
     * Unconditionally child-first, unlike {@link ClassLoader#getResource}'s parent-first default — and unlike
     * {@link #loadClass(String, boolean)}, which stays parent-first for whole namespaces. {@code KotlinJars}
     * locates kotlin-stdlib / -script-runtime / -reflect via {@code Class.getResource("<marker>.class")}
     * (JvmStatic / ScriptTemplateWithArgs / KClasses), which routes through THIS loader. Parent-first hands
     * back Forge's copy of the marker, whose class lives under a securejarhandler {@code union:} URI that
     * {@code KotlinJars} cannot turn into a File → "Unable to find kotlin stdlib". Our urls hold only the
     * kotlin stack + repl.impl (+ the mod jar), so MC/JDK resources still fall through to the parent.
     *
     * <p>getResources (plural) is left parent-first: it feeds service/plugin discovery that reads URL CONTENT
     * (never File), where a {@code union:} URL is harmless.
     */
    @Override
    public URL getResource(String name) {
        URL u = findResource(name);              // child-first: our own file: urls (extracted kotlin jars)
        if (u != null) return u;
        ClassLoader p = getParent();
        return (p != null) ? p.getResource(name) : super.getResource(name);
    }

    /**
     * The REPL impl (script host + template) MUST come from our urls so it links the scripting API we
     * own; everything else that needs a single shared identity with the game/JDK goes to the parent.
     */
    private static boolean mustDelegateToParent(String n) {
        if (n.startsWith("org.js.lolifamily.minecraftmcp.repl.impl.")) return false; // masking-owned
        return n.startsWith("java.")
                || n.startsWith("jdk.")
                || n.startsWith("sun.")
                || n.startsWith("javax.")
                || n.startsWith("net.minecraft.")
                || n.startsWith("org.js.lolifamily.minecraftmcp."); // all other mod classes = game-loader identity
    }

    /**
     * The kotlin RUNTIME namespace that SHARE_ALL delegates to the parent and SPLIT redirects to the game:
     * the stdlib + reflect, NOT the compiler ({@code org.jetbrains.kotlin.*}, always masking-owned). Excludes
     * the scripting API ({@code kotlin.script.*}) and kotlin-metadata-jvm ({@code kotlin.metadata.*}) — the
     * game ships neither, so delegating or redirecting them would hit a loader that lacks them. They stay
     * masking-owned via the normal child-first path.
     */
    static boolean isKotlinRuntime(String n) {
        return n.startsWith("kotlin.")
                && !n.startsWith("kotlin.script.")
                && !n.startsWith("kotlin.metadata.");
    }

    // ---- kotlin version reading --------------------------------------------------------------

    /**
     * {@code KotlinVersion.CURRENT} as seen through {@code cl}, packed into one int
     * ({@code major<<16 | minor<<8 | patch}); {@code -1} if that loader has no kotlin — reflective, so a
     * no-kotlin game yields -1 instead of a link error. Used for the game's version via the parent.
     *
     * <p>Packed to an int because P and C are read through DIFFERENT loaders: {@code KotlinVersion} implements
     * {@link Comparable}, but across loaders they are different classes, so {@code compareTo} between them is
     * a {@code ClassCastException}.
     *
     * <p>The byte-wide shifts are kotlin's own packing — {@code KotlinVersion.MAX_COMPONENT_VALUE} is 255, so
     * no component can overflow its byte, and the packed values compare in exactly version order.
     */
    private static int kotlinVersionOf(ClassLoader cl) {
        if (cl == null) return -1;
        try {
            Class<?> kv = Class.forName("kotlin.KotlinVersion", true, cl);
            Object cur = kv.getField("CURRENT").get(null);
            int major = (Integer) kv.getMethod("getMajor").invoke(cur);
            int minor = (Integer) kv.getMethod("getMinor").invoke(cur);
            int patch = (Integer) kv.getMethod("getPatch").invoke(cur);
            return (major << 16) | (minor << 8) | patch;
        } catch (Throwable noKotlin) {
            return -1;
        }
    }

    /** C: our bundled kotlin version, packed as in {@link #kotlinVersionOf}. Read through a throwaway
     *  {@code parent=null} loader over our own urls — reading it through masking would be circular, since
     *  whether masking serves our kotlin or the parent's IS the regime being decided. */
    private static int probeKotlinVersion(URL[] urls) {
        try (URLClassLoader probe = new URLClassLoader(urls, null)) {
            return kotlinVersionOf(probe);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Packed -> {@code "major.minor.patch"} for logging; {@code null} for an absent version. */
    private static String verString(int v) {
        return v < 0 ? null : (v >>> 16) + "." + ((v >>> 8) & 0xFF) + "." + (v & 0xFF);
    }

    // ---- the snippet loader ------------------------------------------------------------------

    /**
     * The whole snippet chain in ONE hop: game-identity names to the game loader, the rest to masking, and
     * only a miss in both retries a hybrid server's plugins ({@link PluginBridge}). One loader, not one per
     * concern: every snippet class load walks this, and each level costs a lock + {@code findLoadedClass}.
     *
     * <p>Plugins stay last (so MC/mods/{@code org.bukkit.*} keep the server's identity, not a shaded copy)
     * and out of {@link MaskingClassLoader#loadClass(String)}, whose compiler lookups miss by design.
     */
    static final class SnippetLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final ClassLoader game;
        private final boolean redirectKotlin;

        SnippetLoader(MaskingClassLoader masking, ClassLoader game, boolean redirectKotlin) {
            super("mcp-snippet", masking);
            this.game = game;
            this.redirectKotlin = redirectKotlin;
        }

        /**
         * Names that must be the GAME's copy, or a snippet value is a different class at the seam →
         * {@code LinkageError}. ASM always (snippets hand {@code ClassNode} to Mixin); masking keeps its own
         * newest copy, since {@code ScriptWeave} reads bytecode emitted at the running JDK — past some
         * loaders' ASM. Kotlin only in SPLIT; SHARE_ALL already settled it ({@link Regime}).
         *
         * <p>kotlinx.* is not stdlib, so {@link MaskingClassLoader#isKotlinRuntime} misses it — and widening
         * that would also hit {@link MaskingClassLoader#loadClass(String)}, where the compiler's intellij core needs
         * OURS. Whole namespace, since ours arrive as unasked compiler transitives; {@code _COROUTINE} is
         * coroutines-core's own package and splits with it.
         */
        private boolean gameIdentity(String n) {
            return n.startsWith("org.objectweb.asm.")
                    || n.startsWith("kotlinx.")
                    || n.startsWith("_COROUTINE.")
                    || (redirectKotlin && isKotlinRuntime(n));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = load(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        /**
         * Two shapes mean "not mine": the ordinary {@code ClassNotFoundException}, and a CLOSED loader — Bukkit
         * closes a plugin's the moment its {@code onDisable} returns, and Paper's then report the shut jar from
         * {@code findClass} as an {@code IllegalStateException}, which nothing downstream catches (not its own
         * {@code libraryLoader} fallback either). Catching it is what still reaches {@link PluginBridge} past a
         * disabled plugin — OURS included, after an unsupported {@code /reload}; a name only that loader could
         * define still fails, now with the zip error as cause.
         */
        private Class<?> load(String name) throws ClassNotFoundException {
            if (game != null && gameIdentity(name)) {
                try {
                    return game.loadClass(name);
                } catch (ClassNotFoundException | IllegalStateException notInGame) {
                    // game lacks it (no ASM on plain Bukkit, a kotlin class the mods don't ship), or its loader
                    // is closed — use ours
                }
            }
            try {
                return getParent().loadClass(name);          // masking: compiler, scripting, repl.impl, mod, MC, JDK
            } catch (ClassNotFoundException | IllegalStateException notInHost) {
                Class<?> p = PluginBridge.load(name);
                if (p == null) throw new ClassNotFoundException(name, notInHost);
                return p;
            }
        }
    }
}
