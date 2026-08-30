package org.js.lolifamily.minecraftmcp.repl.scope

import org.js.lolifamily.minecraftmcp.exec.Capture

/**
 * Implicit receiver for every `execute_code` snippet: a shadowing `println` / `print` writing into the
 * per-eval sink returned with the result. Kotlin resolves implicit-receiver members ahead of default-imported
 * `kotlin.io` functions, so bare `println(...)` is captured for every argument type — while fully-qualified
 * `kotlin.io.println` / `System.out.println` bypass the shadow onto the JVM's real stdout, and out of this
 * eval's result.
 *
 * [iterator] rides the same rule against `kotlin.sequences.iterator`, handing the snippet a [McpScope] whose
 * `yield` reports its value instead of discarding it. Fully qualifying `kotlin.sequences.iterator` still gets the
 * stdlib builder, and the lane still drives it — silently, as before.
 *
 * Two real `println` overloads, not one `(Any? = null)`: a default can't distinguish `println()` from
 * `println(null)`.
 *
 * Members stay functions and [sink] stays private: exposing [Capture] exposes `take()`, which seals the sink
 * and silently drops the rest of the eval's output. Keeping them functions was also what worked around a
 * frontend gap on the old REPL-snippet path, where a `val` here went invisible to any snippet declaring a
 * class; unverified on the script path, so it has not been relaxed.
 */
@Suppress("unused")
class ScriptScope(private val sink: Capture) {
    fun println() {
        sink.appendLine("")
    }

    fun println(value: Any?) {
        sink.appendLine("$value")
    }

    fun print(value: Any?) {
        sink.append("$value")
    }

    /** Cross-tick builder, shadowing `kotlin.sequences.iterator`. */
    fun <T> iterator(block: suspend McpScope<T>.() -> Unit): Iterator<T> = McpScope<T>(sink).apply { begin(block) }
}
