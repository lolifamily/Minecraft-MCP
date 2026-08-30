package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.Constants

/**
 * Accumulate, materialize once, seal. Backs the per-eval `out` and the `run_command target=client` chat window.
 *
 * Sealing is the point, not an optimization: the append side reaches callers as a closure, and whoever holds it
 * may be asynchronous and outlive this sink — after which it writes forever, unbounded, into something nobody
 * will read, on whatever thread it happens to run on. [take] ends that.
 *
 * Nothing is cached past [take]: the caller holds the only reference to the result, so an outliving writer pins
 * no text.
 */
class Capture {
    private val lock = Any()

    /** Accumulator and seal flag in one: non-null == open. Volatile so [write] can check it WITHOUT the lock —
     *  the path an outliving writer takes for the rest of the process. Only the REFERENCE is volatile; the
     *  builder itself is still mutated under [lock]. */
    @Volatile
    private var sb: StringBuilder? = StringBuilder()

    /** Materialized by the caller, so no arbitrary `toString()` runs under the monitor, where it would stall
     *  every other writer. A mutable sequence is read at lock time — hand it over and drop it. */
    fun append(x: CharSequence) = write(x, newline = false)

    fun appendLine(x: CharSequence) = write(x, newline = true)

    /** Consuming: a second call returns `""` and logs. Retaining a copy to answer it would be exactly the pin
     *  this type exists to avoid. */
    fun take(): String = synchronized(lock) {
        // Bound to a local first: `sb` is volatile, so it cannot smart-cast — reading it again below would type
        // as nullable and silently stringify to "null" instead of failing.
        val b = sb
        if (b == null) {
            Constants.LOG.warn("[capture] take() on an already-sealed capture — returning empty")
            return@synchronized ""
        }
        // Seal BEFORE the copy: the volatile write means a writer arriving during the O(size) toString() never
        // reaches for the lock at all. Swap these two lines and every writer queues behind the copy.
        sb = null
        b.toString()
    }

    private fun write(x: CharSequence, newline: Boolean) {
        if (sb == null) return
        synchronized(lock) {
            val b = sb ?: return
            b.append(x)
            if (newline) b.append('\n')
        }
    }
}
