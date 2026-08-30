package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.patch.Patches
import org.js.lolifamily.minecraftmcp.patch.Signatures
import java.io.BufferedReader
import java.io.FileReader

/**
 * Parses the runtime mappings bundle — tiny v2 (namespace ORDER read from the header, see [Parser.parseTinyV2]) or
 * forge TSRG2 — into the named->runtime lookups [mapClass] / [mapMethod] / [mapField], so name strings written in
 * mojmap (REPL scripts that pass class/method names to [Patches.onEnter] / [Patches.onExit], plus the mod's own
 * reflective lookups — auth probe, command compat) can be translated to the runtime namespace on a non-mojmap
 * production runtime.
 *
 * Needed even though script bytecode is remapped: remap rewrites symbol references, NOT string constants. A
 * patch name is a string, so it stays mojmap after remap and matches nothing on an intermediary runtime.
 * Not closed in the remapper instead (rewriting string constants too, as Sinytra attempts): a remapper cannot
 * tell a class name from data, and `"minecraft:stone"` is data.
 *
 * Loaded once from the provisioned [RemapBundle] when the runtime isn't mojmap; a no-op (never loaded) on
 * 26.1+ (unobfuscated), in dev and on NeoForge production, where names already match.
 */
class Mappings private constructor() {

    private val classN2I = HashMap<String, String>() // named internal -> intermediary internal

    // "namedOwnerInternal#namedMethod" -> every overload's runtime name. Overloads share one mojmap name but
    // have DISTINCT intermediary/srg names, so a single-value map would lose all but the last parsed.
    private val methodN2I = HashMap<String, MutableList<String>>()

    // "namedOwnerInternal#namedField" -> runtime name. Single-valued where methodN2I holds a list: overloading is
    // what forces that one, and no named namespace declares two fields alike on one class.
    private val fieldN2I = HashMap<String, String>()

    // Reverse maps, for demapping runtime exception stack traces back to mojmap on non-mojmap prod.
    private val classI2N = HashMap<String, String>() // intermediary internal -> named internal
    private val methodI2N = HashMap<String, String>() // runtime method -> named method; read via [reverseMethod]

    /** Runtime names claimed by more than one named method, which [reverseMethod] must refuse. Empty on
     *  intermediary/srg (unique by construction); populated on spigot, whose obf names repeat jar-wide. Only
     *  membership is kept — no caller reads what an ambiguous name could have been. */
    private val ambiguous = HashSet<String>()

    /**
     * "runtimeMethod namedOwnerInternal desc" -> named method: the EXACT reverse, holding precisely the rows
     * [reverseMethod] has to refuse. Owner alone would not identify them — proguard reuses one name for several
     * signatures of a single class — so the descriptor is part of the key.
     *
     * [Parser.pruneExact] cuts it down to those rows once parsing is done, so its size tracks the real collision
     * count rather than a guess: nearly every row survives on a spigot bundle, nearly none on intermediary/srg.
     * The runtime name comes FIRST so that prune is a plain prefix read.
     *
     * Keyed by the NAMED owner, so [Parser.recordMethod] needs no extra plumbing; [namedMethodOn] demaps the
     * caller's runtime one for them. `desc` is in the RUNTIME namespace: [Parser.pruneExact] rewrites the file's
     * own (named) one through [runtimeDesc] on the way in, because every caller arrives holding a descriptor
     * read off live reflection.
     */
    private val methodI2Nexact = HashMap<String, String>()

    /** Class rows either parser dropped for carrying too few columns. Reported once by [load], which has the path. */
    private var dropped = 0

    /**
     * Reads one mappings FILE into the enclosing table. Its own class because format knowledge — tiny v2's
     * column math, TSRG2's indentation rules — is not what the lookup API below is about, and the two change
     * for different reasons. `inner`, so the accumulating tables need no plumbing.
     */
    private inner class Parser {

        /** Parse [r] to exhaustion, then settle the tables. The header line is already consumed. */
        fun read(header: String, isTsrg: Boolean, r: BufferedReader) {
            if (isTsrg) parseTsrg2(r) else parseTinyV2(header, r)
            pruneExact()
        }

        // ---- shared write layer: both parsers below feed every table through these three, and nothing else
        // ---- touches them. Keeping the write rules in ONE place is the point: a second copy is a second chance
        // ---- to get the unmapped-name cases subtly wrong.

        /**
         * Record one class row. Returns the named owner the member rows following it belong to — null for a
         * nameless class, which MUST clear the owner or those members would attach to the PREVIOUS class.
         *
         * A named class with no runtime name still becomes the owner: its members can map even when the class name
         * itself doesn't. Both formats write "" for an unmapped element, and storing that would make [mapClass] /
         * [reverseClass] return "" instead of passing the input through.
         */
        private fun recordClass(named: String, runtime: String): String? {
            if (named.isEmpty()) return null
            if (runtime.isNotEmpty()) {
                classN2I[named] = runtime
                classI2N[runtime] = named // reverse
            }
            return named
        }

        /** Record one method row under [owner]. Overloads share a mojmap name but carry DISTINCT runtime names, so
         *  the forward direction accumulates into a list. The reverse direction is single-valued and so COLLIDES on
         *  an obf runtime: a second, differing row poisons the entry into [ambiguous] instead of overwriting it. */
        private fun recordMethod(owner: String, named: String, runtime: String, desc: String) {
            if (named.isEmpty() || runtime.isEmpty()) return
            val overloads = methodN2I.getOrPut("$owner#$named") { ArrayList(1) }
            if (runtime !in overloads) overloads.add(runtime)
            val prev = methodI2N.putIfAbsent(runtime, named) // reverse, loose
            if (prev != null && prev != named) ambiguous.add(runtime)
            if (desc.isNotEmpty()) methodI2Nexact["$runtime $owner $desc"] = named // reverse, exact
        }

        /** Record one field row under [owner]. Forward only: the reverse tables exist to demap stack traces, and
         *  a frame names a method, never a field. */
        private fun recordField(owner: String, named: String, runtime: String) {
            if (named.isEmpty() || runtime.isEmpty()) return
            fieldN2I["$owner#$named"] = runtime
        }

        /** Settle the exact-reverse index. Run by [read] once the file is exhausted — the earliest point where
         *  [ambiguous] and [classN2I] are both final, which is what both edits below need. One linear pass over
         *  rows we just parsed, cheaper than the parse itself, and both sit behind a download on a background
         *  thread.
         *
         *  - Drop every row whose runtime name is NOT ambiguous: [reverseMethod] answers those on its own, so
         *    the row would be a table entry nothing can ever read. A separator-less key goes too.
         *  - Rewrite the descriptor into the runtime namespace ([runtimeDesc]): the file writes it in the file's
         *    own (named) terms, while every caller arrives holding one read off live reflection. */
        private fun pruneExact() {
            val kept = HashMap<String, String>(methodI2Nexact.size)
            for ((k, v) in methodI2Nexact) {
                val sep = k.indexOf(' ')
                if (sep < 0 || k.substring(0, sep) !in ambiguous) continue
                // "<runtime> <owner> <desc>" — neither name can contain a space, so the LAST one opens the desc.
                val at = k.lastIndexOf(' ')
                kept[k.substring(0, at + 1) + runtimeDesc(k.substring(at + 1))] = v
            }
            methodI2Nexact.clear()
            methodI2Nexact.putAll(kept)
        }

        /** The 'named' and 'intermediary' column indices of a tiny v2 header
         *  ("tiny \t 2 \t 0 \t <ns0> \t <ns1> \t ..." — namespace names start at column 3, and the returned indices
         *  are relative to that). Null when either namespace is absent. */
        private fun locateNamespaces(header: String): Pair<Int, Int>? {
            val h = header.split("\t")
            var named = -1
            var inter = -1
            for (i in 3 until h.size) {
                if ("named" == h[i]) {
                    named = i - 3
                } else if ("intermediary" == h[i]) {
                    inter = i - 3
                }
            }
            return if (named < 0 || inter < 0) null else named to inter
        }

        /**
         * tiny v2 -> named->intermediary. The namespace ORDER is read from the header, not assumed: loom emits
         * `official intermediary named` but the auto-assembled bundle emits `named intermediary official`. Locating
         * the 'named'/'intermediary' columns by name makes both parse correctly. The header line is already consumed.
         */
        private fun parseTinyV2(header: String, r: BufferedReader) {
            val ns = locateNamespaces(header)
            if (ns == null) {
                Constants.LOG.warn("[mcp-remap] tiny header lacks named/intermediary ns: {}", header)
                return
            }
            val (namedNs, interNs) = ns
            val maxNs = maxOf(namedNs, interNs)
            var curNamedOwner: String? = null
            while (true) {
                val line = r.readLine() ?: break
                // Columns are offset by the row tag: 1 for "c", 3 for the "" + "m"/"f" + <desc> of a member row.
                if (line.startsWith("c\t")) {
                    val p = line.split("\t")   // c \t <ns0> \t <ns1> \t ...
                    if (p.size < 2 + maxNs) {
                        // It opened a class we can't name; clear the owner or its members land on the previous class.
                        curNamedOwner = null
                        dropped++
                        continue
                    }
                    curNamedOwner = recordClass(p[1 + namedNs], p[1 + interNs])
                } else if (line.startsWith("\tm\t") || line.startsWith("\tf\t")) {
                    recordTinyMember(curNamedOwner ?: continue, line, namedNs, interNs, maxNs)
                }
            }
        }

        /** One tiny v2 member row under [owner]: `\t<m|f>\t<desc>\t<ns0>\t<ns1>...`. Both kinds carry that one
         *  shape, so the tag char is the whole discriminator, and a field's type column goes unread — the field
         *  table keys on name alone. Split out for the reason [recordTsrg2Member] is, on the other parser. */
        private fun recordTinyMember(owner: String, line: String, namedNs: Int, interNs: Int, maxNs: Int) {
            val p = line.split("\t")
            if (p.size < 4 + maxNs) return
            if (line[1] == 'm') {
                // p[2] is ns0's desc, which pruneExact reads as NAMED, while the name columns are located dynamically.
                // Sound because only spigot reads that index back and only assembleSpigot writes its bundle (named first);
                // a yarn-order file (named last) leaves `ambiguous` empty, so pruneExact drops the table whole.
                recordMethod(owner, p[3 + namedNs], p[3 + interNs], p[2])
            } else {
                recordField(owner, p[3 + namedNs], p[3 + interNs])
            }
        }

        /** One TSRG2 member line belonging to [owner] — the leading tab is already established by the caller.
         *  The column count discriminates: 3 is a method, whose middle column is the descriptor, and 2 is a
         *  field. Skipped: depth >= 2, a param row or the `static` marker. Since we are past the first tab,
         *  testing `line[1]` IS the depth test. */
        private fun recordTsrg2Member(owner: String, line: String) {
            if (line.length >= 2 && line[1] == '\t') return
            val p = line.substring(1).split(" ")
            if (p.size == 2) {
                recordField(owner, p[1], p[0]) // "<srg-field> <named-field>"
                return
            }
            if (p.size != 3) return
            recordMethod(owner, p[2], p[0], p[1]) // "<srg-method> <desc> <named-method>"
        }

        /** forge TSRG2 ("tsrg2 left right": col0=left=SRG runtime, col1=right=named; space-separated columns,
         *  tab-indented members) -> named->srg. Which member rows survive is [recordTsrg2Member]'s call. */
        private fun parseTsrg2(r: BufferedReader) {
            var curNamedOwner: String? = null
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                if (line[0] != '\t') {
                    val p = line.split(" ") // "<srg-class> <named-class>"
                    if (p.size < 2) {
                        curNamedOwner = null // same rule as parseTinyV2: a class we can't name must not adopt the previous owner
                        dropped++
                        continue
                    }
                    curNamedOwner = recordClass(p[1], p[0]) // named -> srg (identity: p[0] == p[1])
                } else {
                    val owner = curNamedOwner ?: continue
                    recordTsrg2Member(owner, line)
                }
            }
        }
    }

    /**
     * A NAMED method descriptor with its class references rewritten into the runtime namespace, so it can be
     * compared against one read off live reflection. Unmapped names — JDK, library, mod types — pass through,
     * which is correct: they are spelled the same in both namespaces.
     *
     * Needed because the two namespaces disagree about CLASS names on every runtime that renames them (spigot's
     * `BlockPos` is `BlockPosition`, fabric's is `class_2338`). Forge is the one where this is the identity.
     */
    private fun runtimeDesc(named: String): String {
        if (named.indexOf('L') < 0) return named // primitives and voids only — identical either way
        val sb = StringBuilder(named.length)
        var i = 0
        while (i < named.length) {
            val c = named[i]
            sb.append(c)
            i++
            if (c != 'L') continue
            val end = named.indexOf(';', i)
            if (end < 0) return named // malformed: hand back what came in rather than a half-rewrite
            sb.append(classN2I[named.substring(i, end)] ?: named.substring(i, end)).append(';')
            i = end + 1
        }
        return sb.toString()
    }

    /** named FQN (dot-separated) -> runtime (intermediary/srg) FQN, or the input unchanged if unmapped. */
    fun mapClass(namedFqn: String): String {
        val inter = classN2I[namedFqn.replace('.', '/')]
        return inter?.replace('/', '.') ?: namedFqn
    }

    /** named method on a named owner FQN -> a SINGLE runtime method name (the last-declared overload), or the
     *  input unchanged if unmapped. For reflective single-target callers (command compat, auth probes) that
     *  resolve the exact overload themselves by argument types. Patch weaving must use [mapMethodAll] instead,
     *  or it silently drops every overload but this one. */
    fun mapMethod(namedOwnerFqn: String, namedMethod: String): String =
        methodN2I[namedOwnerFqn.replace('.', '/') + "#" + namedMethod]?.lastOrNull() ?: namedMethod

    /** named method on a named owner FQN -> EVERY runtime name it maps to. Overloads share one mojmap name but
     *  carry distinct runtime names, so weaving a name-only patch must target all of them — exactly as ByteBuddy
     *  `named()` already matches every overload on a mojmap runtime. Returns the input name alone when unmapped,
     *  so a dev / unmapped runtime still weaves by the mojmap name. */
    fun mapMethodAll(namedOwnerFqn: String, namedMethod: String): List<String> =
        methodN2I[namedOwnerFqn.replace('.', '/') + "#" + namedMethod] ?: listOf(namedMethod)

    /** named field on a named owner FQN -> its runtime name, or the input unchanged if unmapped. Single-valued
     *  where [mapMethod] needs [mapMethodAll] beside it: overloads are what split one mojmap name across several
     *  runtime ones, and fields do not overload. [namedOwnerFqn] must DECLARE the field — no row exists under an
     *  inheritor and this does not climb, the same constraint `Class.getDeclaredField` puts on its caller. */
    fun mapField(namedOwnerFqn: String, namedField: String): String =
        fieldN2I[namedOwnerFqn.replace('.', '/') + "#" + namedField] ?: namedField

    /** runtime (intermediary/srg) FQN -> named FQN — reverse of [mapClass], for demapping stack traces
     *  and exception messages back to the mojmap names the script was written in. Unmapped input passes through. */
    fun reverseClass(runtimeFqn: String): String {
        val named = classI2N[runtimeFqn.replace('.', '/')]
        return named?.replace('/', '.') ?: runtimeFqn
    }

    /** [reverseClass] for a caller already holding the internal form — the table's own, so nothing converts. */
    internal fun reverseClassInternal(runtimeInternal: String): String = classI2N[runtimeInternal] ?: runtimeInternal

    /**
     * runtime method name -> named method name, owner-free — which is what lets a stack-trace frame demap
     * without resolving its class. Unmapped names (constructors, library methods) pass through unchanged, and so
     * do AMBIGUOUS ones: guessing one of them would be a lie in the one place that cannot check it.
     *
     * DISPLAY ONLY. A caller that needs a name it can rely on goes forward instead, through [mapMethodAll] over
     * the class hierarchy — see [Signatures.runtimeNamesOf].
     */
    fun reverseMethod(runtimeMethod: String): String =
        if (runtimeMethod in ambiguous) runtimeMethod else methodI2N[runtimeMethod] ?: runtimeMethod

    /** [mapMethodAll] against a RUNTIME owner, composed here so the internal name never tours through dotted form. */
    fun runtimeNamesOn(runtimeOwnerFqn: String, mojmapMethod: String): List<String> =
        methodN2I[reverseClassInternal(runtimeOwnerFqn.replace('.', '/')) + "#" + mojmapMethod] ?: listOf(mojmapMethod)

    /** The exact counterpart of [reverseMethod]: [runtimeMethod] with descriptor [desc] on the RUNTIME owner
     *  [runtimeOwnerFqn]. Null when unmapped — including where the index was pruned away, which is exactly where
     *  [reverseMethod] answers on its own. Callers climb: an override's row lives under the class it inherited from. */
    fun namedMethodOn(runtimeOwnerFqn: String, runtimeMethod: String, desc: String): String? =
        methodI2Nexact[runtimeMethod + " " + reverseClassInternal(runtimeOwnerFqn.replace('.', '/')) + " " + desc]

    companion object {
        @Volatile
        private var loaded: Mappings? = null

        /** The loaded mappings, or null (dev / NeoForge prod / not configured). */
        fun current(): Mappings? = loaded

        /** Whether a by-name lookup resolves correctly yet — false on a non-mojmap runtime until [load] runs. */
        fun namesResolvable(): Boolean = !NamespaceProbe.needsRemap() || loaded != null

        /** Parse the mappings file — tiny v2 (fabric) or TSRG2 (forge), dispatched on the header line; caches as
         *  [current]. Returns null on any failure (patch falls back to raw names). */
        fun load(path: String): Mappings? {
            try {
                // Charset pinned: the no-charset FileReader follows Charset.defaultCharset(), so a cached
                // bundle would decode by the host's locale. Every other read in the mod is already UTF-8.
                BufferedReader(FileReader(path, Charsets.UTF_8)).use { r ->
                    val m = Mappings()
                    val header = r.readLine() ?: return null
                    val isTsrg = header.startsWith("tsrg2")
                    m.Parser().read(header, isTsrg, r)
                    if (m.dropped > 0) {
                        Constants.LOG.warn(
                            "[mcp-remap] {} malformed class row(s) in {} — those classes and their members are unmapped",
                            m.dropped, path,
                        )
                    }
                    // Empty table = the parse bailed. Publishing it reads as "loaded" to every current()
                    // null-check while mapping nothing, so every by-name lookup misses silently.
                    if (m.classN2I.isEmpty()) {
                        Constants.LOG.warn("[mcp-remap] mappings parsed 0 classes from {} — not publishing", path)
                        return null
                    }
                    loaded = m
                    Constants.LOG.info(
                        "[mcp-remap] mappings loaded ({}): {} classes, {} methods, {} fields",
                        if (isTsrg) "tsrg2/srg" else "tiny/intermediary", m.classN2I.size, m.methodN2I.size, m.fieldN2I.size,
                    )
                    return m
                }
            } catch (t: Throwable) {
                Constants.LOG.warn("[mcp-remap] mappings load failed ({})", path, t)
                return null
            }
        }
    }
}
