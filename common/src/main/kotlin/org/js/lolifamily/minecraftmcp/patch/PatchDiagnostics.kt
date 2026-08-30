package org.js.lolifamily.minecraftmcp.patch

import java.lang.reflect.Method

/**
 * How a failed or ambiguous patch target is described back to the caller.
 *
 * Split out of [Patch] because the audience is different: [Patch] decides what to weave, this decides what
 * to SAY when it can't. The caller is an `execute_code` snippet written by a model, so every rejection here
 * lists the real candidates — turning "installed, never fires, why?" into one corrected retry rather than
 * several rounds of guessing.
 */
internal object PatchDiagnostics {

    /** How many method names [noSuchMethod] lists before truncating. */
    private const val NAME_PREVIEW = 8

    /** A method's mojmap signature — the dedup key for the one-signature rule and one line of a candidate
     *  list, deliberately the same function for both. See [Signatures.render]. */
    fun sigOf(m: Method): String = Signatures.render(Signatures.methodToMojmap(m), Signatures.paramsOf(m))

    fun labelOf(m: Method): String = "${sigOf(m)} @${loaderName(m.declaringClass.classLoader)}"

    fun loaderName(cl: ClassLoader?): String = if (cl == null) "bootstrap" else cl.name ?: cl.javaClass.simpleName

    fun listing(ms: List<Method>): String = ms.distinctBy(::sigOf).sortedBy(::sigOf).joinToString("\n") { "  ${labelOf(it)}" }

    /**
     * The name resolved to nothing. Naming the near misses is what lets a model spot the typo or the
     * wrong-class mistake without another round trip.
     *
     * Ranked by edit distance, not alphabetically: an alphabetical window of a large class is a window onto
     * one letter, which rarely holds the name the caller meant. The total stays in the message because a
     * large one is itself the hint that the wrong CLASS was named, not the wrong method.
     */
    fun noSuchMethod(cls: String, methodName: String, declared: List<Method>): IllegalArgumentException {
        val names = declared.map { Signatures.methodToMojmap(it) }.distinct()
        // Precomputed rather than called from the comparator, which would recompute a pure function per compare.
        val distance = names.associateWith { editDistance(methodName, it) }
        val ranked = names.sortedWith(compareBy({ distance.getValue(it) }, { it })) // ties alphabetical, so it's stable
        return IllegalArgumentException(
            "patch: no method named '$methodName' on $cls — nearest of ${names.size} declared: ${preview(ranked)}",
        )
    }

    /** First [NAME_PREVIEW] names, plus what was left out. Callers order the list; this only trims it. */
    private fun preview(names: List<String>): String {
        val more = names.size - NAME_PREVIEW
        return names.take(NAME_PREVIEW).joinToString(", ") + if (more > 0) ", … ($more more)" else ""
    }

    /**
     * Case-insensitive Levenshtein distance, two-row DP.
     *
     * Unnormalized on purpose: the raw length term is what keeps short unrelated names away from a long
     * query, and dividing by length ranks them back up. Case-insensitive because a wrong-case name is a
     * typo, not a different method.
     */
    private fun editDistance(query: String, candidate: String): Int {
        val s = query.lowercase()
        val t = candidate.lowercase()
        var prev = IntArray(t.length + 1) { it } // distance from "" to t[0..j)
        var cur = IntArray(t.length + 1)
        for (i in 1..s.length) {
            cur[0] = i
            for (j in 1..t.length) {
                val substitute = prev[j - 1] + if (s[i - 1] == t[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, substitute)
            }
            val done = prev
            prev = cur
            cur = done // reuse the row we just finished reading, so the loop allocates nothing
        }
        return prev[t.length] // the last row written, whichever array that landed in
    }

    /** The name resolved, but on an ancestor: this class carries no bytecode for it. Redirecting silently
     *  would widen the patch to every subclass, so the caller is told and re-aims. */
    fun inheritedElsewhere(cls: String, methodName: String, owner: String, matches: List<Method>): IllegalArgumentException =
        IllegalArgumentException(
            "patch: $cls#$methodName is inherited, not declared — patch $owner instead (weaves for every subclass):\n" + listing(matches),
        )

    /** The name resolved, but every match is abstract or native. Naming an abstract base instead of the
     *  concrete implementation is an easy mistake, so say exactly that rather than "not found". */
    fun notWeavable(cls: String, methodName: String, byName: List<Method>): IllegalArgumentException = IllegalArgumentException(
        "patch: $cls#$methodName has no weavable body — every match is abstract or native, and neither " +
            "carries bytecode to weave. Patch a concrete implementation instead. Matched:\n" + listing(byName),
    )

    fun noSuchSignature(cls: String, methodName: String, params: List<String>, weavable: List<Method>): IllegalArgumentException =
        IllegalArgumentException(
            "patch: no signature matches $cls#$methodName(${params.joinToString(", ")}) — available:\n" + listing(weavable),
        )

    fun ambiguous(cls: String, methodName: String, params: List<String>, distinct: List<Method>): IllegalArgumentException =
        IllegalArgumentException(
            "patch: $cls#$methodName(${params.joinToString(", ")}) matches ${distinct.size} signatures — " +
                "name the parameter types to pick one:\n" + listing(distinct),
        )
}
