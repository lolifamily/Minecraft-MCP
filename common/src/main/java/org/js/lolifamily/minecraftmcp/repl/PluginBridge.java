package org.js.lolifamily.minecraftmcp.repl;

import org.js.lolifamily.minecraftmcp.Constants;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Class lookup across the classloaders of a hybrid server (Silkard, Youer, ...), reached from
 * {@link MaskingClassLoader.SnippetLoader} once nothing in the host has the name.
 *
 * <p>Reflective throughout: common links only the JDK and Minecraft, while Bukkit either exists on the game
 * loader at runtime or does not exist at all. The reflective handles are resolved once; the plugin list never
 * is — it is read live per lookup, so enabling, disabling or reloading a plugin needs no invalidation.
 *
 * <p>There are more loaders than plugins. Two shapes hang one off the side of a plugin loader, both CHILDREN
 * of it, so walking parents misses both: {@code libraryLoader} (the plugin's Maven {@code libraries:}, a field
 * on the loader) and jar-in-jar (a nested implementation jar extracted at startup, then fed downloaded
 * dependencies at runtime — only the plugin object holds it). {@link #collectFieldLoaders} finds both by
 * reading each field VALUE and asking which loader defined it. Not the field TYPE: LuckPerms declares its
 * bootstrap field as {@code LoaderBootstrap}, so a type-based search finds nothing.
 *
 * <p>Only loaders the host cannot already reach belong here — this runs last, after masking, the game loader
 * and its parents have all missed. The scan subtracts that chain explicitly, because a plugin loader's
 * {@code JavaPluginLoader} field is defined by the server loader: left in, it drags the whole server classpath
 * along behind it.
 */
final class PluginBridge {

    private PluginBridge() {}

    /** Live view of the loaders behind the currently loaded plugins. */
    private interface Plugins {
        List<ClassLoader> loaders();
    }

    private static volatile Plugins plugins;
    private static volatile boolean probed;

    /** The last {@link #expand}, keyed by the plugin loaders it was derived from. */
    private record Scan(List<ClassLoader> from, List<ClassLoader> all) {}

    private static volatile Scan scan;

    /**
     * The class as one of those loaders DEFINES it, or {@code null} when none does — including the ordinary
     * mod runtime, where there are no plugins at all.
     *
     * <p>{@code findResource} is the design: public on {@link URLClassLoader}, searches that loader's own urls,
     * does not delegate. It buys three things at once.
     * <ul>
     * <li>It fits every shape here. Aiming at the plugin loaders' four-arg
     *     {@code loadClass(name, resolve, checkGlobal, checkLibraries)} would not: a jar-in-jar loader has no
     *     such method, and its plain {@code loadClass} delegates to the plugin loader, which answers for every
     *     OTHER plugin and makes Spigot log "not a depend or softdepend" against the wrong one. {@code
     *     findClass} is no way out either — inherited from {@code java.net.URLClassLoader}, so
     *     {@code setAccessible} throws {@code InaccessibleObjectException} without an {@code --add-opens}.</li>
     * <li>It makes {@code checkGlobal} moot instead of something to pass {@code false}: past the gate the name
     *     IS in this loader's urls, so {@code loadClass} hits locally before any global search.</li>
     * <li>~0.5µs, so a miss — the common case — loads nothing.</li>
     * </ul>
     *
     * <p>The identity check enforces what the gate promised. A plugin loader is parent-first, so a name the
     * game loader also has comes back as the game's copy — which the caller already tried and rejected.
     */
    static Class<?> load(String name) {
        Plugins p = plugins();
        if (p == null) return null;
        String path = name.replace('.', '/') + ".class";
        for (ClassLoader loader : p.loaders()) {
            if (!(loader instanceof URLClassLoader u)) continue;
            if (u.findResource(path) == null) continue;          // not this loader's to answer
            try {
                Class<?> c = u.loadClass(name);
                if (c.getClassLoader() == u) return c;
            } catch (Throwable wontLink) {
                // a jar entry that cannot link here (missing transitive, bad bytecode) — try the next loader
            }
        }
        return null;
    }

    /**
     * The same loaders {@link #load} searches, for {@link PluginJarCollector}'s compile classpath. The two MUST
     * agree: a name the compiler resolves and the snippet loader then cannot find is a
     * {@code NoClassDefFoundError}, strictly worse than the compile error it replaced.
     *
     * <p>Loaders, not urls — a jar-in-jar loader keeps adding urls after startup, so a cached url list goes
     * stale where a cached loader does not.
     */
    static List<ClassLoader> loaders() {
        Plugins p = plugins();
        return p == null ? List.of() : p.loaders();
    }

    private static Plugins plugins() {
        if (probed) return plugins;
        synchronized (PluginBridge.class) {
            if (probed) return plugins;
            plugins = detect();
            probed = true;
            return plugins;
        }
    }

    /**
     * Bukkit's own {@code PluginManager} covers both plugin families: on Paper the legacy
     * {@code SimplePluginManager} forwards {@code getPlugins()} to the Paper implementation, whose list holds
     * legacy and Paper plugins alike. {@code null} when the game loader has no Bukkit, i.e. not a hybrid server.
     */
    private static Plugins detect() {
        final Method getPluginManager;
        final Method getPlugins;
        try {
            getPluginManager = Class.forName("org.bukkit.Bukkit", false, Constants.GAME_LOADER).getMethod("getPluginManager");
            getPlugins = Class.forName("org.bukkit.plugin.PluginManager", false, Constants.GAME_LOADER).getMethod("getPlugins");
        } catch (Throwable noBukkit) {
            return null;
        }
        Constants.LOG.info("[mcp-repl/plugins] hybrid server detected — snippets can reach plugin classes");
        final Set<ClassLoader> host = new LinkedHashSet<>();
        for (ClassLoader c = Constants.GAME_LOADER; c != null; c = c.getParent()) host.add(c);
        return () -> {
            try {
                Object manager = getPluginManager.invoke(null);
                Object[] found = (Object[]) getPlugins.invoke(manager);
                List<ClassLoader> owners = new ArrayList<>(found.length);
                for (Object plugin : found) {
                    if (plugin == null) continue;
                    ClassLoader cl = plugin.getClass().getClassLoader();
                    if (cl != null && !host.contains(cl) && !owners.contains(cl)) owners.add(cl);
                }
                return expand(found, owners, host);
            } catch (Throwable serverNotUpYet) {
                // plugins load long after the mods do, so an eval can legitimately land before there are any
                return List.of();
            }
        };
    }

    /**
     * The plugin loaders plus whatever hangs off their side, cached against the plugin loaders themselves.
     * {@code getPlugins()} is cheap enough per lookup — that is what keeps the list live and invalidation-free
     * — but the field scan is not. Keying on the plugin loader list keeps both: enabling, disabling or
     * reloading a plugin changes that list, misses the cache and rescans on its own.
     *
     * <p>Plugin loaders first, so a plugin's own jar outranks a library or nested-jar copy of the same name.
     * Order only decides genuine duplicates — the gate in {@link #load} makes every other lookup exclusive.
     */
    private static List<ClassLoader> expand(Object[] found, List<ClassLoader> owners, Set<ClassLoader> host) {
        Scan s = scan;
        if (s != null && s.from().equals(owners)) return s.all();

        LinkedHashSet<ClassLoader> all = new LinkedHashSet<>(owners);
        for (Object plugin : found) {
            if (plugin == null) continue;
            collectFieldLoaders(plugin, Object.class, all);                         // jar-in-jar
            ClassLoader owner = plugin.getClass().getClassLoader();
            if (owner != null) collectFieldLoaders(owner, ClassLoader.class, all);  // libraryLoader
        }
        all.removeAll(host);
        List<ClassLoader> out = List.copyOf(all);
        scan = new Scan(owners, out);
        if (out.size() > owners.size()) {
            Constants.LOG.info("[mcp-repl/plugins] {} plugin loader(s) + {} alongside (jar-in-jar, plugin libraries)",
                    owners.size(), out.size() - owners.size());
        }
        return out;
    }

    /**
     * Every loader one field deep from {@code o}: per field, the loader that defined its value — or the value
     * itself, when it already is a loader.
     *
     * <p>One field deep is the whole search, not a depth limit on a graph walk. Both shapes put their loader in
     * a field of the object that exists to hold it, and going deeper needs cycle detection and array/collection
     * traversal for the same answer: measured live, depth 1 reaches 151 objects and depth 10 reaches 134k, both
     * finding exactly the same loaders.
     *
     * <p>Fields, not getters — a field read has no side effects, and this walks another plugin's internals.
     * {@code stopAt} keeps it inside classes that can be opened: {@code ClassLoader.class} for a loader, since
     * {@code setAccessible} on {@code java.lang.ClassLoader}'s own fields throws.
     */
    private static void collectFieldLoaders(Object o, Class<?> stopAt, Set<ClassLoader> out) {
        for (Class<?> k = o.getClass(); k != null && k != stopAt && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                final Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(Modifier.isStatic(f.getModifiers()) ? null : o);
                } catch (Throwable closedOrBroken) {
                    continue;
                }
                if (v == null) continue;
                ClassLoader cl = (v instanceof ClassLoader c) ? c : v.getClass().getClassLoader();
                if (cl != null) out.add(cl);
            }
        }
    }
}
