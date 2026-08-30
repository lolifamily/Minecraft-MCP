package org.js.lolifamily.minecraftmcp.probe

import org.js.lolifamily.minecraftmcp.Constants

/**
 * Append-only text store behind the named, persistent [Probe] channels — its only consumer — held as
 * immutable segments. Content is never dropped; the channel stays unbounded.
 *
 * A single StringBuilder puts two O(channel) operations on the WRITER's thread, which is a patch handler's,
 * i.e. a tick thread: the doubling realloc, and the Latin-1 -> UTF-16 inflate that the first non-ASCII char
 * forces over the whole array. Freezing at [SEGMENT_CHARS] bounds both by that constant.
 *
 * The size check runs AFTER the append, so no write is split: every segment ends at the newline [appendLine]
 * put there, and a consumer splitting segments into lines never sees a torn one.
 *
 * A runaway channel is reported to [Constants.LOG] and nowhere else: [Probe]'s consumer is script CODE, so an
 * injected note would land in whatever parses the result, and a cap would make every read ask "was that all?".
 */
class Buffer(private val id: String) {

    private val lock = Any()

    /** Frozen segments, oldest first, immutable once added. A plain list read under [lock], not a
     *  copy-on-write one: freezes are frequent and reads rare, so COW would charge the writer O(segments)
     *  per freeze to save the reader a snapshot. */
    private val frozen = ArrayList<String>()

    private var sb = StringBuilder()

    /** Next [frozen] size to warn at, doubling after each one so a runaway escalates instead of flooding.
     *  Under [lock]. */
    private var warnAt = WARN_CHUNKS

    fun appendLine(x: Any?) {
        val s = "$x\n"   // stringify before the lock: `x` is arbitrary caller code
        // Warned outside the lock: a log write can hit disk, and tick threads hold this. Both early returns
        // land before `chunks`, so an ordinary append pays one comparison.
        val chunks = synchronized(lock) {
            // A line that fills a segment by itself is frozen AS the String it already is: appending it would
            // grow the builder to fit and `toString` would copy it straight back out, both O(line). The tail
            // freezes first so order holds, and both pieces end at a newline like every other segment.
            if (s.length >= SEGMENT_CHARS) {
                freezeTail()
                frozen.add(s)
            } else {
                sb.append(s)
                if (sb.length < SEGMENT_CHARS) return
                freezeTail()
            }
            if (frozen.size < warnAt) return
            warnAt *= 2
            frozen.size
        }
        Constants.LOG.warn("[mcp-probe] channel '{}' at {} chunks (≥{}MB) — Probe.mute(\"{}\") to stop it", id, chunks, chunks / 4, id)
    }

    /** Snapshot, oldest first — not a live view. Copies segment REFERENCES; only the unfrozen tail is
     *  materialized, bounded by [SEGMENT_CHARS]. */
    fun segments(): Sequence<String> = synchronized(lock) { snapshotLocked() }.asSequence()

    /** [segments] and empty in one step, so an append landing mid-consume is not lost the way a
     *  `segments()` + [clear] pair loses it. */
    fun drain(): Sequence<String> = synchronized(lock) {
        val out = snapshotLocked()
        reset()
        out
    }.asSequence()

    fun clear() {
        synchronized(lock) { reset() }
    }

    private fun snapshotLocked(): List<String> {
        val tail = sb.toString()
        val out = ArrayList<String>(frozen.size + 1)
        out.addAll(frozen)
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Move the unfrozen tail into [frozen], if there is one. Under [lock]. */
    private fun freezeTail() {
        if (sb.isEmpty()) return
        frozen.add(sb.toString())
        sb = StringBuilder(SEGMENT_CHARS + SEGMENT_SLACK)
    }

    /** Back to the initial shape, small builder included: a drained channel may never fill again. */
    private fun reset() {
        frozen.clear()
        sb = StringBuilder()
        warnAt = WARN_CHUNKS // a drained channel that fills again is still a runaway
    }

    private companion object {
        /** Freeze threshold, sized by the allocation a freeze makes: 256K chars is 512KB as UTF-16, which
         *  stays under G1's humongous cutoff (half a region; a 4GB heap gets 2MB ones), so a freeze never
         *  allocates into old gen. A backstop, not a working mechanism — thousands of log lines, so an
         *  ordinary channel never freezes once. */
        const val SEGMENT_CHARS = 1 shl 18

        /** Room for the append that crosses the threshold, since the check runs after it — without this a
         *  segment ends over capacity and StringBuilder doubles, once per segment. */
        const val SEGMENT_SLACK = 1 shl 13

        /** First [frozen] size worth a warning. Its line reports `chunks / 4` MB, a floor on both counts: a
         *  segment is a `String`, so Latin-1 content costs one byte per char and anything else two; and a
         *  segment freezes AFTER the append that crossed [SEGMENT_CHARS], so it ends over it — by under one
         *  more segment, or by a whole line where [appendLine] froze that line alone. */
        const val WARN_CHUNKS = 128
    }
}
