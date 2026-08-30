package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import java.io.File
import java.net.URLClassLoader

/**
 * Plugin jars for the compile classpath: whatever the host enumerates ([Services.PLATFORM]) plus the urls of
 * the loaders behind the plugins. [PluginBridge] searches those same loaders at runtime, and the two MUST
 * agree — a name the compiler resolves and the snippet loader then misses is a `NoClassDefFoundError`.
 */
object PluginJarCollector {

    /** Plugin + library + jar-in-jar jars, deduped by absolute path. Empty wherever there are no plugins. */
    @JvmStatic
    fun collect(): List<File> {
        val out = LinkedHashSet<File>()
        // null on a mod host — the loaders below are the only source there. See IPlatformHelper.
        val paths = runCatching { Services.PLATFORM.pluginCodePaths() }
            .onFailure { Constants.LOG.warn("[mcp-repl/plugins] plugin enumeration failed", it) }
            .getOrNull().orEmpty()
        for (path in paths) {
            runCatching { path.toFile() }.getOrNull()?.takeIf { it.isFile }?.let { out.add(it.absoluteFile) }
        }
        val enumerated = out.size
        out.addAll(fromLoaders())
        if (out.isNotEmpty()) {
            Constants.LOG.info(
                "[mcp-repl/plugins] {} plugin/library jar(s) for compile cp ({} live-loader only)",
                out.size, out.size - enumerated,
            )
        }
        // Plugins and everything hanging off their loaders are user space, so each gets its own overlay shard.
        // Null id: the host records the plugin's own name as it enumerates (see PaperPlatformHelper), and a
        // null here never overwrites that — the libraries behind it have no name to record at all.
        out.forEach { CodeOrigin.mark(it, null) }
        return ArrayList(out)
    }

    /**
     * Jars only the live loaders name: a jar-in-jar payload extracted to a temp file named at random per boot,
     * and the dependencies such a plugin downloads onto its own loader after startup. Neither is on any path a
     * directory walk could predict.
     */
    private fun fromLoaders(): List<File> {
        val out = ArrayList<File>()
        for (loader in runCatching { PluginBridge.loaders() }.getOrDefault(emptyList())) {
            if (loader !is URLClassLoader) continue
            for (url in loader.urLs) {
                JarLocator.toJarFile(url)?.takeIf { it.isFile }?.let { out.add(it.absoluteFile) }
            }
        }
        return out
    }
}
