package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.repl.impl.ReplHost
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [ReplHost.execute] hands back for a cross-tick snippet: the iterator to drive one step per tick. Both
 * output channels — `println`, and the `=> type = value` each `yield` renders — land in the per-eval `out` sink
 * [EvalTask] owns and passed down, so the two arrive interleaved in the order they happened.
 *
 * [errorFlag] is set by the masking-side iterator guard if a step throws — surfaced as [errored], so the lane
 * can return the partial output and still mark the eval's [Outcome] as an error.
 */
class IterEval(val iterator: Iterator<*>, private val errorFlag: AtomicBoolean) {

    /** Whether a step threw while the lane was driving [iterator]. */
    val errored: Boolean get() = errorFlag.get()
}
