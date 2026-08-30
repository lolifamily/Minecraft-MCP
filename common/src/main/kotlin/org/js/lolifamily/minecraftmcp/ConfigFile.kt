package org.js.lolifamily.minecraftmcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.js.lolifamily.minecraftmcp.platform.Services
import java.nio.file.Files
import java.nio.file.Path

/**
 * [Props]' lowest-priority source: a JSON object whose keys ARE the `-D` property names, so the flag tables in
 * the README are its whole schema and there is no second vocabulary to keep in step.
 *
 * Read at init and not watched afterwards: by the time an edit could be noticed the port is bound and the
 * token is baked into the endpoint, so a reload would change nothing.
 *
 * Values are strings, as `-D` values are. Gson coerces an unquoted number or boolean, so `25599` and `"25599"`
 * both work; one it cannot coerce fails the whole file rather than that one key.
 */
object ConfigFile {

    @Volatile
    private var entries: Map<String, String?> = emptyMap()

    /**
     * Why the file could not be parsed, or null.
     *
     * Non-null forbids two things: starting the endpoint, and rewriting the file. Regenerating over a file we
     * failed to read would cost the user their settings AND the token their MCP client holds — both
     * unrecoverable, from one bad edit.
     */
    @Volatile
    var failure: String? = null
        private set

    /** Parse the file if it is there. Absent is not a failure — it is written on demand. The one writer of
     *  [failure]: [persist] runs after both its readers, so a failure there has to throw instead. */
    fun load() {
        val p = Services.PLATFORM.configPath
        read(p).fold(
            onSuccess = { entries = it },
            onFailure = {
                failure = "$it"
                Constants.LOG.error("[mcp] {} could not be read ({}) — the MCP endpoint will not start", p, "$it")
            },
        )
    }

    /** Verbatim, nulls and blanks included: [Props] owns the one unset rule across all three sources. */
    fun entries(): Map<String, String?> = entries

    /**
     * Merge [generated] into the file and publish it atomically, creating it if it isn't there.
     *
     * Only what this launch had to INVENT belongs in [generated]: a value supplied by `-D` or the environment
     * was put out of band on purpose, and writing it to disk would let the two disagree from the next launch on.
     *
     * [seed] names settings the file should MENTION without setting: written as JSON null where neither side
     * has it, which [Props] reads as unset — so the file spells the key without choosing a value.
     */
    fun persist(generated: Map<String, String>, seed: List<String>) {
        val p = Services.PLATFORM.configPath
        // Re-read rather than merge over the init-time snapshot: this is the only write, and the file may have
        // appeared or been hand-edited since. Whatever is on disk wins for every key we are not writing.
        val current = read(p).getOrElse { error("$p is not readable ($it) — refusing to overwrite it") }
        val merged = LinkedHashMap(current).apply {
            putAll(generated)
            // putIfAbsent: a hand-written value must not be blanked by its own seed.
            seed.forEach { putIfAbsent(it, null) }
        }
        // serializeNulls: off by default, which would drop every seeded key on the way out.
        val json = GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(merged)
        Files.createDirectories(p.parent)
        AtomicFiles.publishing(p) { tmp -> Files.writeString(tmp, json) }
        entries = merged
    }

    /** The file's entries — an empty map when it is absent — or what stopped the parse. A query, so the whole
     *  answer is the return value: what a failure costs is the caller's, and the two callers disagree. */
    private fun read(p: Path): Result<Map<String, String?>> {
        if (!Files.isRegularFile(p)) return Result.success(emptyMap())
        return runCatching {
            val mapType = object : TypeToken<Map<String, String?>>() {}.type
            val parsed: Map<String, String?>? = Files.newBufferedReader(p).use { Gson().fromJson(it, mapType) }
            parsed.orEmpty()
        }
    }
}
