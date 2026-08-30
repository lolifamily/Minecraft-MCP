package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import java.io.File
import java.lang.module.ResolvedModule
import java.net.URI
import java.net.URLClassLoader
import java.security.CodeSource

/**
 * Enumerate the real backing jars for `net.minecraft.*` at runtime, so the REPL can feed them
 * to the Kotlin compiler as its classpath.
 *
 * `java.class.path` does NOT contain the MC jar on a module-based loader — BootstrapLauncher deliberately
 * ignores it and loads MC into the MC-BOOTSTRAP module layer. So we take a union (dedup by absolute path)
 * of the five sources numbered below, and one of them always wins per loader.
 */
object ClasspathCollector {

    @JvmStatic
    fun collect(mcAnchor: Class<*>): LinkedHashSet<File> {
        val out = LinkedHashSet<File>()
        log(
            "collect() start anchor=" + mcAnchor.name +
                " loader=" + mcAnchor.classLoader +
                " module=" + mcAnchor.module.name,
        )

        // 1) flat classpath
        val cp = System.getProperty("java.class.path", "")
        for (p in cp.split(File.pathSeparator)) {
            if (p.isNotEmpty()) add(out, File(p))
        }

        // On a remap runtime the runtime MC jar is redundant — mc-symbols.jar supplies net.minecraft — so drop it.
        // Index size only: ReplBridge PREPENDS the symbol jars, so a copy that survives here can't shadow them.
        // Two identities, because Module.getName() is null for an UNNAMED module: the name catches Forge/NeoForge
        // (securejarhandler's "minecraft") without resolving a union: URI, the file catches Knot and plugin hosts,
        // where a name-only test reads null and silently never fires.
        val dropMc = RemapBundle.current() != null
        val skipMcModule: String? = if (dropMc) mcAnchor.module.name else null
        val skipMcFile: File? = if (dropMc) {
            runCatching { JarLocator.toJarFile(mcAnchor.protectionDomain?.codeSource?.location) }.getOrNull()?.absoluteFile
        } else {
            null
        }

        // 2) anchor module layer (+ parents) — where MC lives on NeoForge/Forge
        val anchorLayer = mcAnchor.module.layer
        if (anchorLayer != null) {
            collectLayer(anchorLayer, out, LinkedHashSet(), skipMcModule)
        }

        // 3) boot layer (defensive)
        collectLayer(ModuleLayer.boot(), out, LinkedHashSet(), skipMcModule)

        // 4) anchor code source — the runtime MC jar itself
        if (!dropMc) {
            try {
                val cs: CodeSource? = mcAnchor.protectionDomain.codeSource
                if (cs?.location != null) {
                    addUri(out, cs.location.toURI())
                }
            } catch (t: Throwable) {
                log("anchor CodeSource failed: $t")
            }
        }

        // 5) URLs the loaders themselves hold. A plugin host's launcher can keep the server's libraries on a
        // URLClassLoader of its own and publish them nowhere else — not on java.class.path, not in any layer.
        collectLoaderUrls(mcAnchor.classLoader, out)

        // One removal at the funnel exit, not a guard at each of the five collection points: add() is the single
        // door every File enters through, and steps 1/2/3/5 can each surface the same jar.
        if (skipMcFile != null && out.remove(skipMcFile)) log("dropped runtime MC jar (mc-symbols supplies net.minecraft): $skipMcFile")

        // by-name heuristic, for the log line only
        val mcNameHints = listOf("minecraft", "client-extra", "forge", "joined", "mojmap")
        val hasMc = out.any { f ->
            val n = f.name.lowercase()
            mcNameHints.any { n.contains(it) }
        }
        log("collect() done: " + out.size + " files, MC-jar-present(by-name)=" + hasMc)
        return out
    }

    private fun collectLoaderUrls(start: ClassLoader?, out: MutableSet<File>) {
        var loader = start
        while (loader != null) {
            if (loader is URLClassLoader) {
                for (url in loader.urLs) JarLocator.toJarFile(url)?.let { add(out, it) }
            }
            loader = loader.parent
        }
    }

    private fun collectLayer(layer: ModuleLayer?, out: MutableSet<File>, seen: MutableSet<ModuleLayer>, skipModule: String?) {
        if (layer == null || !seen.add(layer)) return
        for (rm: ResolvedModule in layer.configuration().modules()) {
            if (skipModule != null && rm.name() == skipModule) continue   // the runtime MC module (remap runtime)
            val ref = rm.reference()
            ref.location().ifPresent { uri -> addUri(out, uri) }
        }
        for (parent in layer.parents()) {
            collectLayer(parent, out, seen, skipModule)
        }
    }

    /** Turn a module/codesource URI into a File — see [JarLocator]. */
    private fun addUri(out: MutableSet<File>, uri: URI) {
        // jrt:/<module> is a JDK platform module: it lives in the lib/modules image, so no File backs it, and the
        // compiler reads those itself off jdkHome. Drop it here so the unresolved log only carries real failures.
        if ("jrt".equals(uri.scheme, ignoreCase = true)) return
        val f = JarLocator.toJarFile(uri)
        if (f != null) {
            add(out, f)
        } else {
            // A JiJ union resolves to nothing here BY DESIGN — JarLocator refuses to answer with the container.
            // ModJarCollector repacks the ones that are mods; what's left is mixin/adapter plumbing (verified:
            // MixinExtras, adapter runtime, mixin's relocated guava) that no script imports. Division of labor,
            // not a failure — so debug, and the INFO summary in collect() is where a real shortfall shows up.
            Constants.LOG.debug("[mcp-repl/cp] (no File here, covered elsewhere) {}", uri)
        }
    }

    private fun add(out: MutableSet<File>, f: File) {
        try {
            val abs = f.absoluteFile
            if (abs.exists()) {
                out.add(abs)
            }
        } catch (_: Throwable) {
        }
    }

    private fun log(m: String) {
        Constants.LOG.info("[mcp-repl/cp] {}", m)
    }
}
