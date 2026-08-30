package org.js.lolifamily.minecraftmcp.exec

import java.util.function.Supplier

/**
 * The result of one eval: its text plus whether it ended in error, which the MCP layer maps to tools/call
 * `isError`.
 *
 * [text] is built on first read: draining the output [Capture] is O(size) and that sink is unbounded, so the
 * copy belongs on the HTTP thread already blocked on this result. The COPY only — a value is rendered eagerly,
 * on the thread that ran the eval, because `toString()` is caller code and a game object's reads game state.
 * `Supplier` rather than a Kotlin function type because this crosses the masking-loader seam, where only
 * `java.*` has a single identity.
 */
class Outcome(val isError: Boolean, textFn: Supplier<String>) {

    /** Constant text — nothing to defer. Also, the constructor `ReplBridge` calls from Java. */
    constructor(text: String, isError: Boolean) : this(isError, Supplier { text })

    /** Read once, by the one caller the result is for: every supplier behind it drains the eval's output
     *  [Capture], which seals on that take and answers `""` from then on. */
    val text: String by lazy { textFn.get() }

    /** NOT [text]: that read is the one-shot drain above, so a log line or a string template that spent it
     *  would leave the caller an empty result — a silent loss, since the sink only logs a warning. */
    override fun toString(): String = "Outcome(isError=$isError)"
}
