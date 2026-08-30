package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.repl.impl.RemapCacheBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The mojmap<->runtime remap bundle in force: a mappings file, and the symbol dir holding [MC_SYMBOLS] (plus
 * [DEPS_LIST] where one was harvested). Private constructor — mappings without symbols leaves scripts
 * compiling against the runtime jar, where no mojmap name resolves, so neither factory below can produce
 * that pair.
 *
 * The dir's layout lives here rather than in its readers because [ClasspathCollector] drops the runtime MC jar
 * on the mere EXISTENCE of a bundle: what makes a dir a bundle has to have exactly one answer.
 *
 * Must stay in `...repl`, NOT `repl.impl`: `MaskingClassLoader` delegates this package to the parent, which is
 * what lets the masking-loaded `ScriptWeave` / [RemapCacheBuilder] and the game-loaded `ReplBridge` read one copy.
 */
class RemapBundle private constructor(val mappings: Path, val symbols: Path) {

    val symbolsJar: Path get() = symbols.resolve(MC_SYMBOLS)

    val deps: Path get() = symbols.resolve(DEPS_LIST)

    companion object {

        /** Reverse-remapped mojmap MC symbols — what snippets are compiled against. */
        const val MC_SYMBOLS = "mc-symbols.jar"

        /** API-dep probe list harvested from [MC_SYMBOLS]'s signatures, written beside it. */
        const val DEPS_LIST = "deps.txt"

        /** The [IPlatformHelper.platformId] [MC_SYMBOLS] was built under. Read by [RemapCache], not by
         *  [isComplete]: it answers "built by THIS loader", not "is this dir a bundle". */
        const val LOADER_STAMP = "loader.stamp"

        @Volatile
        private var active: RemapBundle? = null

        /** The bundle, or null for a mojmap runtime / unsupported namespace / failed provisioning. Written by
         *  [RemapCache.provision] before it releases the warmup gate, so to every reader null means
         *  "decided, and there is none" rather than "not yet". */
        @JvmStatic
        fun current(): RemapBundle? = active

        /** The minimum that is a bundle at all. */
        fun hasSymbols(dir: Path): Boolean = Files.isRegularFile(dir.resolve(MC_SYMBOLS))

        /** [hasSymbols] plus [DEPS_LIST]: the bar for a REUSABLE auto-cache, so one written before deps.txt
         *  existed rebuilds rather than silently dropping the API-dep jars. Stricter on purpose — the auto
         *  path can rebuild what it lacks, a hand-supplied bundle cannot, so [fromFlags] holds to
         *  [hasSymbols]. */
        fun isComplete(dir: Path): Boolean = hasSymbols(dir) && Files.isRegularFile(dir.resolve(DEPS_LIST))

        /** Auto-cache output; [RemapCache.checkArtifacts] has already verified it. */
        internal fun fromCache(mappings: Path, symbols: Path) = RemapBundle(mappings, symbols).also { active = it }

        /** The `mcp.remap.*` pair, or null when the flags don't describe a usable bundle. Half a pair, or a
         *  symbol dir with no [MC_SYMBOLS] in it, is rejected rather than half-honored, so the caller falls
         *  through to the auto-cache. */
        internal fun fromFlags(): RemapBundle? {
            val m = Props.str("mcp.remap.mappings")
            val s = Props.str("mcp.remap.classpath")
            if (m.isNullOrEmpty() && s.isNullOrEmpty()) return null
            if (m.isNullOrEmpty() || s.isNullOrEmpty()) {
                Constants.LOG.error(
                    "[mcp-remap] mcp.remap.{} set without mcp.remap.{} — both or neither; ignoring both",
                    if (m.isNullOrEmpty()) "classpath" else "mappings",
                    if (m.isNullOrEmpty()) "mappings" else "classpath",
                )
                return null
            }
            val mp = Paths.get(m)
            val sp = Paths.get(s)
            if (!Files.isRegularFile(mp) || !Files.isDirectory(sp)) {
                Constants.LOG.error(
                    "[mcp-remap] mcp.remap.mappings must be a file ({}) and mcp.remap.classpath a dir ({}) " +
                        "— ignoring both",
                    mp, sp,
                )
                return null
            }
            if (!hasSymbols(sp)) {
                Constants.LOG.error(
                    "[mcp-remap] mcp.remap.classpath {} contains no {} — ignoring both; the auto-cache runs instead",
                    sp, MC_SYMBOLS,
                )
                return null
            }
            return RemapBundle(mp, sp).also { active = it }
        }
    }
}
