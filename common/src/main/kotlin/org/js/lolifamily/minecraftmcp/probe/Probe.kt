package org.js.lolifamily.minecraftmcp.probe

import java.util.concurrent.ConcurrentHashMap

/**
 * Named, persistent output channels, keyed and long-lived. Any caller on any thread does
 * `Probe.emit("hits", ...)` and a later `execute_code` reads it with `Probe.segments("hits")` — a patch
 * handler is the motivating case, not the contract, and nothing here references Patches. Channels live in
 * the game loader (single identity) and survive across evals, so what a channel holds accumulates until
 * explicitly cleared.
 *
 * Reads hand back immutable chunks, never one joined string, so the O(channel) cost of materializing is
 * written where it is paid. Nothing is dropped or annotated on the way out either: the consumer is script
 * CODE, and an injected note would land in whatever parses the result.
 *
 * Channels are unbounded and nothing reclaims them — a probe on a hot method grows without limit, and is
 * warned about in the game log (see [Buffer]). Reclaiming is the caller's: [mute] freezes a channel (new
 * writes dropped, content kept), [clear] empties one, [resetAll] drops them all.
 */
// All public members are REPL-facing API, called at runtime by compiled patch handlers /
// execute_code snippets (not by static references here) and advertised in the tool schema + README.
// The IDE's "never used" is a false positive — same as AccessBridge's bytecode-linked bootstraps.
@Suppress("unused")
object Probe {

    /** A named channel: its buffer plus a muted flag. Muting drops new writes but keeps existing content. */
    private class Channel(id: String) {
        val buf = Buffer(id)

        @Volatile var muted = false
    }

    private val CHANNELS = ConcurrentHashMap<String, Channel>()

    /** Append [value] as a line to the named channel, creating it on first use. No-op while [mute]d. */
    fun emit(id: String, value: Any?) {
        val ch = CHANNELS.computeIfAbsent(id) { Channel(it) }
        if (!ch.muted) ch.buf.appendLine(value)
    }

    /** A channel's content as immutable chunks, oldest first; empty if unused. Non-destructive, and a
     *  snapshot rather than a live view. A chunk boundary never falls inside a line — every chunk ends at
     *  one of the newlines [emit] wrote. */
    fun segments(id: String): Sequence<String> = CHANNELS[id]?.buf?.segments().orEmpty()

    /** [segments] and empty in one step, so an emit landing mid-consume isn't lost the way a [segments] +
     *  [clear] pair loses it. Keeps the channel and its [mute] state. */
    fun take(id: String): Sequence<String> = CHANNELS[id]?.buf?.drain().orEmpty()

    /** A snapshot: the backing key set is mutable and live, so handing it out would let a caller drop
     *  channels without going through [clear], and would let iteration see channels a handler adds mid-loop. */
    fun ids(): Set<String> = CHANNELS.keys.toSet()

    /**
     * Freeze [id]: new emits are dropped, existing content is kept and stays readable. Creates the
     * channel if absent, so a channel can be muted before its first emit.
     */
    fun mute(id: String) {
        CHANNELS.computeIfAbsent(id) { Channel(it) }.muted = true
    }

    /** Resume accepting emits on [id]. No-op if the channel doesn't exist. */
    fun unmute(id: String) {
        CHANNELS[id]?.muted = false
    }

    /** Reclaim [id]'s content, keeping the channel so a [mute] survives it. Dropping the channel instead
     *  would let the next emit recreate it unmuted, silently resuming the growth the mute had stopped. */
    fun clear(id: String) {
        CHANNELS[id]?.buf?.clear()
    }

    fun resetAll() {
        CHANNELS.clear()
    }
}
