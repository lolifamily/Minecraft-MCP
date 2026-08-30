package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import org.js.lolifamily.minecraftmcp.platform.services.ModId
import java.io.File
import java.lang.module.ResolvedModule
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.jvm.optionals.getOrNull

/**
 * Enumerate the backing jars of every LOADED mod — including jar-in-jar / nested modules — so a script can
 * import mod APIs (fabric-api, NeoForge/Forge mods, and any mod's bundled libraries) at compile time.
 *
 * Separate from [ClasspathCollector]: Fabric's mods live on Knot (off `java.class.path` and off every
 * module layer), and jar-in-jar can be missed on any loader.
 *
 * Two sources: [IPlatformHelper.modCodePaths] for what ModList knows, [orphanModuleRoots] for the JiJ
 * libraries it doesn't. A root already backed by a real file is used as-is, one inside a jar/union/memory
 * filesystem goes through [JarLocator], and only a root with no file behind it at all reaches [ModJarCache].
 */
object ModJarCollector {

    /** Real backing jars / repacked class jars for every loaded mod (+ nested), deduped by absolute path. */
    @JvmStatic
    fun collect(): List<File> {
        val out = LinkedHashSet<File>()
        val old = ModJarCache.readStamps()
        val now = LinkedHashMap<String, String>()
        var roots = 0
        val platform = try {
            Services.PLATFORM.modCodePaths()
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] mod root enumeration failed", t)
            emptyList()
        }
        val orphans = try {
            orphanModuleRoots(platform)
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] orphan module enumeration failed", t)
            emptyList()
        }
        for (mc in platform + orphans) {
            if (resolveRoot(mc, out, old, now)) roots++
        }
        addKiltMods(out)
        ModJarCache.writeStamps(now)
        Constants.LOG.info(
            "[mcp-repl/mods] {} mod jar(s) for compile cp ({} root(s), {} non-mod JiJ, via {})",
            out.size, roots, orphans.size, Services.PLATFORM.platformId,
        )
        return ArrayList(out)
    }

    /**
     * Kilt runs NeoForge mods on Fabric, but keeps its guests in `KiltLoader.mods` instead of registering them,
     * so [collect]'s platform enumeration never sees them. Probed by class presence, Kilt being no dependency of
     * ours. `NeoForgeMod.getModFile()` already points at the remapped (intermediary) jar, so what lands on the
     * compile classpath is in the namespace the runtime uses.
     */
    private fun addKiltMods(out: MutableSet<File>) {
        val kilt = try {
            Class.forName("xyz.bluspring.kilt.Kilt", false, Constants.GAME_LOADER)
        } catch (_: Throwable) {
            return // not running under Kilt
        }
        try {
            val companion = kilt.getField("Companion").get(null)
            val loader = companion.javaClass.getMethod("getLoader").invoke(companion) // KiltLoader
            val mods = loader.javaClass.getMethod("getMods").invoke(loader) as Collection<*>
            var added = 0
            for (mod in mods) {
                val jar = mod?.javaClass?.getMethod("getModFile")?.invoke(mod) as? File ?: continue
                // No id: KiltLoader names its guests nowhere this reflection reaches, so the shard falls back
                // to the file name — which for a mod jar is stable enough between updates that keep the name.
                if (jar.isFile && out.add(jar.absoluteFile)) { CodeOrigin.mark(jar, null); added++ }
            }
            Constants.LOG.info("[mcp-repl/mods] Kilt detected: added {} guest mod jar(s) to compile cp", added)
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] Kilt present but mod enumeration failed; its mods won't be importable", t)
        }
    }

    /**
     * Module-layer roots ModList never sees: JiJ jars with no `mods.toml` (Registrate, kfflib, commonmark...).
     * Ones [JarLocator] resolves are skipped — [ClasspathCollector] has them.
     *
     * Coverage is by FILESYSTEM IDENTITY, not path equality: one union jar is root `""` via `getSecureJar()`
     * and `"/"` via the module location — not `equals`, same UnionFileSystem instance. Identity comes from
     * [java.lang.module.ModuleDescriptor], never the location URI: that carries a per-launch `#<n>` index.
     */
    private fun orphanModuleRoots(claimed: List<ModCode>): List<ModCode> {
        val seenFs = Collections.newSetFromMap(IdentityHashMap<FileSystem, Boolean>())
        claimed.forEach { seenFs.add(it.path.fileSystem) }
        val out = ArrayList<ModCode>()
        for (rm in resolvedModules()) {
            val uri = rm.reference().location().orElse(null) ?: continue
            if ("jrt".equals(uri.scheme, ignoreCase = true)) continue
            if (JarLocator.toJarFile(uri) != null) continue
            val root = try { Paths.get(uri) } catch (_: Throwable) { continue }
            if (!seenFs.add(root.fileSystem)) continue
            val d = rm.reference().descriptor()
            out.add(ModCode(root, listOf(ModId(d.name(), d.rawVersion().getOrNull().orEmpty()))))
        }
        return out
    }

    /** Every resolved module of the boot layer and of our own (the game layer), parents included. */
    private fun resolvedModules(): List<ResolvedModule> {
        val layers = LinkedHashSet<ModuleLayer>()
        fun walk(l: ModuleLayer?) {
            if (l != null && layers.add(l)) l.parents().forEach { walk(it) }
        }
        walk(ModuleLayer.boot())
        walk(ModJarCollector::class.java.module.layer)
        return layers.flatMap { it.configuration().modules() }.distinctBy { it.name() }
    }

    /** Turn a mod code path into a compiler-readable File; false if none of the three strategies works. */
    private fun resolveRoot(mc: ModCode, out: MutableSet<File>, old: Map<String, String>, now: MutableMap<String, String>): Boolean {
        val root = mc.path
        // Same key ModJarCache names its repacks by, for the same reason: it survives a version bump, so the
        // overlay shard is overwritten rather than orphaned. Null where the loader named nothing.
        val id = mc.mods.minOfOrNull { it.id }
        // 1) real dir or jar on the default filesystem. Gated on the FS, not on toFile() throwing: Forge's
        // `jij:` provider breaks Path.toFile()'s contract and returns File("") rather than throwing, and
        // File("").exists() is true — it resolves to the cwd. Ask the invariant instead of catching a symptom.
        if (root.fileSystem == FileSystems.getDefault()) {
            try {
                val f = root.toFile()
                if (f.exists()) { out.add(f.absoluteFile); CodeOrigin.mark(f, id); return true }
            } catch (_: Throwable) { /* unresolvable — fall through */ }
        }
        // 2) backing real jar recovered from the root URI. Through JarLocator, never a local parse: a union URI
        // carries no `file:` (makeKey strips the scheme and appends a per-launch `#<n>`), so a `file:`-keyed
        // parse misses every Forge / NeoForge<=21.1 mod. JarLocator handles all three shapes — see it.
        val backing = JarLocator.toJarFile(root.toUri())
        if (backing != null && backing.isFile) { out.add(backing.absoluteFile); CodeOrigin.mark(backing, id); return true }
        // 3) repack the class tree into a jar (NOT a dir: widenClasspath only access-widens .jar entries).
        // Only a root with no file behind it anywhere gets here — a Forge jar-in-jar mod lives in an in-memory
        // roimfs and never touches disk.
        val jar = ModJarCache.repack(mc, old, now)
        if (jar != null) { out.add(jar); CodeOrigin.mark(jar, id); return true }
        return false
    }
}
