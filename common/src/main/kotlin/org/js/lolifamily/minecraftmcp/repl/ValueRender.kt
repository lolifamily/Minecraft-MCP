package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.repl.scope.McpScope
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The `=> type = value` line, for both the single-tick result and every cross-tick [McpScope.yield] — one
 * function, so the two cannot drift apart.
 *
 * `toString()` is the fallback and is right for almost everything. It is replaced only where the JVM
 * representation is an address or a lie (an unsigned array prints its signed storage). Value classes are
 * reachable by `is` here only when they arrive boxed, which `yield`'s `reified` parameter guarantees; a
 * single-tick value-class RESULT does not, since it is read back off an unboxed backing field.
 *
 * A container is walked only when the walk CHANGES something, so a type keeps its own `toString()` unless it
 * holds something opaque. A container reached from inside itself renders as [CYCLE], and that counts as a
 * change — so a cyclic value never falls back to the `toString()` that would follow the cycle forever.
 *
 * Everything appends into ONE builder and answers with a Boolean, so the payload is materialized exactly once.
 * A String per node costs one copy per level of nesting plus one for the head — at depth 1, the whole payload.
 */
internal object ValueRender {

    /** A container reached from inside itself; the spelling `Arrays.deepToString` uses for the same thing. */
    private const val CYCLE = "[...]"

    /** Nesting past [MAX_NESTING]: there is more here, it just isn't worth the stack. Angle-bracketed like
     *  `<lambda>` and [threw] — this file's mark for the renderer speaking rather than showing a value. */
    private const val TOO_DEEP = "<too deep>"

    /** A hard stop on nesting. This walk spends several frames per level — where the `contentDeepToString` it
     *  replaced spent one — and it runs on the game thread, so depth has to be bounded somewhere. The chain is
     *  the ancestor list, so its size IS the depth; no counter to thread through. */
    private const val MAX_NESTING = 64

    /** The builder, not a String: the head is already in it when the payload lands, so it costs no copy of its
     *  own. Both callers consume it at once — one `toString()`, one `append`. */
    fun line(type: String, value: Any?): StringBuilder {
        val sb = StringBuilder("=> ").append(type).append(" = ")
        if (value == null) return sb.append("null")
        Sink(sb).render(value)
        return sb
    }

    /** One [line] call's state: the buffer and the ancestors on the way down — one lifetime, so they travel as
     *  fields rather than through every signature. */
    private class Sink(private val sb: StringBuilder) {

        /** The containers between the root and the current element, by IDENTITY: `equals`/`hashCode` are caller
         *  code that recurses over the structure being walked, so equality would blow the stack this protects. */
        private val chain: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

        /** Append [v]'s rendering, falling back to its own `toString()` and containing whatever it throws. */
        fun render(v: Any) {
            val mark = sb.length
            try {
                if (!shape(v)) sb.append(v)
            } catch (t: Throwable) {
                sb.setLength(mark)
                sb.append(threw(v, t))
            }
        }

        /**
         * Append [v]'s repair; false when `toString()` is already the best answer.
         *
         * On false the buffer is left as it was found — [descend] rolls a walk back, every other branch appends
         * nothing until it has decided, and only a THROW leaves a partial render, which [element] undoes. A new
         * branch owes the same.
         */
        fun shape(v: Any): Boolean = flatArray(v) || when (v) {
            is String -> ambiguousBare(v).also { if (it) sb.append('"').append(v).append('"') }
            // Walked like any other container, NOT contentDeepToString(): that repairs nested ARRAYS and
            // nothing else, so one Array<*> on the path stranded everything beneath it on its own toString().
            // `always`: an array's toString() is an address, so the walk always changes it.
            is Array<*> -> walk(v, v.asList(), '[', ']', always = true)
            is Collection<*> -> walk(v, v, '[', ']', always = false)
            is Map<*, *> -> walkMap(v)
            // Data classes, so no other branch reaches them, and `a to b` is too common a way to hand back two
            // things for an array inside one to stay opaque. Walked by their components under their own identity,
            // so a tuple holding what holds it terminates on the tuple too.
            is Pair<*, *> -> walk(v, listOf(v.first, v.second), '(', ')', always = false)
            is Triple<*, *, *> -> walk(v, listOf(v.first, v.second, v.third), '(', ')', always = false)
            // Not kotlinc's `<function$arity>`: a function reference's toString names the function it points at,
            // and that arity counts a suspend lambda's hidden Continuation (`suspend () -> Unit` reads as
            // `<function1>`). Under the SPLIT kotlin regime `is Function` answers false for a snippet's own lambda,
            // which keeps its address — a miss, not a fault.
            is Function<*> -> (!overridesToString(v)).also { if (it) sb.append("<lambda>") }
            else -> false
        }

        /** Tried BEFORE the container branches: a boxed unsigned array implements `java.util.Collection`, so
         *  walked as one it would repair nothing and fall back to the signed storage its `toString()` prints. */
        @OptIn(ExperimentalUnsignedTypes::class)
        private fun flatArray(v: Any): Boolean {
            val s = when (v) {
                is ByteArray -> v.contentToString()
                is ShortArray -> v.contentToString()
                is IntArray -> v.contentToString()
                is LongArray -> v.contentToString()
                is FloatArray -> v.contentToString()
                is DoubleArray -> v.contentToString()
                is CharArray -> v.contentToString()
                is BooleanArray -> v.contentToString()
                is UByteArray -> v.contentToString()
                is UShortArray -> v.contentToString()
                is UIntArray -> v.contentToString()
                is ULongArray -> v.contentToString()
                else -> return false
            }
            sb.append(s)
            return true
        }

        /** [v] is on the chain for the duration of [body], and the buffer is rolled back when [body] repaired
         *  nothing. Removed on the way out: the same list twice as SIBLINGS is not a cycle. Depth is measured
         *  here rather than per element, since only a container can be descended into — a scalar sitting at the
         *  limit is a leaf, and reads as itself. */
        private inline fun descend(v: Any, body: () -> Boolean): Boolean {
            if (chain.size >= MAX_NESTING) {
                sb.append(TOO_DEEP)
                return true
            }
            val mark = sb.length
            chain.add(v)
            try {
                if (body()) return true
                sb.setLength(mark)
                return false
            } finally {
                chain.remove(v)
            }
        }

        /** [owner] is what joins the chain; [items] is what gets rendered — the same object for a collection, an
         *  array view for an array, its components for a tuple. */
        private fun walk(owner: Any, items: Iterable<*>, open: Char, close: Char, always: Boolean): Boolean = descend(owner) {
            var repaired = always
            sb.append(open)
            var first = true
            for (e in items) {
                if (!first) sb.append(", ")
                first = false
                if (element(e)) repaired = true
            }
            sb.append(close)
            repaired
        }

        /** `AbstractMap`'s own `{k=v, k2=v2}`, so a walked map reads the same as an unwalked one. */
        private fun walkMap(map: Map<*, *>): Boolean = descend(map) {
            var repaired = false
            sb.append('{')
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append(", ")
                first = false
                if (element(k)) repaired = true
                sb.append('=')
                if (element(v)) repaired = true
            }
            sb.append('}')
            repaired
        }

        private fun element(e: Any?): Boolean {
            if (e == null) {
                sb.append("null")
                return false
            }
            // Before shape(), and a repair in its own right: reporting the cycle stops the descent, counting it
            // as a repair keeps the containers above it off their own toString().
            if (e in chain) {
                sb.append(CYCLE)
                return true
            }
            val mark = sb.length
            return try {
                if (shape(e)) true else { sb.append(e); false }
            } catch (t: Throwable) {
                // Roll back first: a throw is the one thing that leaves half a subtree behind. A caught
                // toString() IS a repair — count it as none and the container falls back to its own toString(),
                // which throws again for this very element.
                sb.setLength(mark)
                sb.append(threw(e, t))
                true
            }
        }
    }

    /** `toString()` is caller code and may throw; a value that cannot render must not fail the eval that made it.
     *  Per ELEMENT so one bad entry costs its slot, not the line — [Sink.render] backstops what walking can't reach. */
    private fun threw(v: Any, t: Throwable): String =
        "<${v.javaClass.name}.toString() threw ${t.javaClass.name}: ${runCatching { t.message }.getOrNull()}>"

    /**
     * Whether [v] read bare would say something it is not — nothing at all, or a null. Not every string:
     * quoting costs two characters per element inside a container. Edges tested directly, not through
     * `trim()`, which copies the whole string exactly when the check matches. `isEmpty` first, or `v[0]` throws.
     */
    private fun ambiguousBare(v: String): Boolean = v.isEmpty() || v == "null" ||
        v[0].isWhitespace() || v[v.length - 1].isWhitespace()

    /** Whether [v]'s class says anything of its own, or inherits `Object`'s `Type@hexHash`. `Any::class.java`
     *  IS `java.lang.Object.class` — the comparison is against the real declaring class, not a Kotlin alias. */
    private fun overridesToString(v: Any): Boolean =
        runCatching { v.javaClass.getMethod("toString").declaringClass != Any::class.java }.getOrDefault(false)
}
