package org.js.lolifamily.minecraftmcp

import java.util.Locale

/**
 * One setting, resolved across three sources and then parsed: `-D` beats the environment beats
 * [ConfigFile] — explicit for this launch, then ambient, then persisted. At every source a value that trims to
 * empty counts as unset and falls through, so a blank env var (what an unrendered compose template leaves
 * behind) cannot shadow the file.
 *
 * Range checks stay at the call site, the only place that knows what a number means — same split as netty's
 * `SystemPropertyUtil`, which this is shaped after.
 *
 * Root package, so the masking loader delegates it to the game loader and both sides read one copy.
 */
object Props {

    /** Which source answered. */
    enum class Source { PROPERTY, ENV, FILE }

    /** Key, value and origin from ONE lookup, so a warning can never name a source the value didn't come from.
     *  Renders as the value's own source spells it, which is what makes a correction copy-pasteable. */
    class Resolved(val key: String, val value: String, val source: Source) {
        override fun toString(): String = when (source) {
            Source.PROPERTY -> "-D$key=$value"
            Source.ENV -> "${envName(key)}=$value"
            Source.FILE -> "\"$key\": \"$value\" in the config file"
        }
    }

    private val TRUE = setOf("true", "yes", "on", "1")
    private val FALSE = setOf("false", "no", "off", "0")

    fun resolve(key: String): Resolved? {
        pick(System.getProperty(key))?.let { return Resolved(key, it, Source.PROPERTY) }
        pick(System.getenv(envName(key)))?.let { return Resolved(key, it, Source.ENV) }
        pick(ConfigFile.entries()[key])?.let { return Resolved(key, it, Source.FILE) }
        return null
    }

    /** Trimmed, or null when unset — and empty IS unset, at every source. */
    @JvmStatic
    fun str(key: String): String? = resolve(key)?.value

    fun long(key: String, def: Long): Long {
        val r = resolve(key) ?: return def
        r.value.toLongOrNull()?.let { return it }
        Constants.LOG.warn("[mcp] {} is not a number — using {}", r, def)
        return def
    }

    /** `true|yes|on|1` / `false|no|off|0`, case-insensitive. */
    @JvmStatic
    fun bool(key: String, def: Boolean): Boolean {
        val r = resolve(key) ?: return def
        val v = r.value.lowercase(Locale.ROOT)
        if (v in TRUE) return true
        if (v in FALSE) return false
        Constants.LOG.warn("[mcp] {} is not a boolean — using {}", r, def)
        return def
    }

    /** `mcp.eval.step.budget.ms` -> `MCP_EVAL_STEP_BUDGET_MS`. Derived, so there is no table to drift. */
    private fun envName(key: String): String = key.uppercase(Locale.ROOT).replace('.', '_')

    private fun pick(raw: String?): String? = raw?.trim()?.ifEmpty { null }
}
