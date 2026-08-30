package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.exec.ScriptTimeoutError
import org.js.lolifamily.minecraftmcp.exec.TimeoutGuard
import org.js.lolifamily.minecraftmcp.patch.Signatures
import org.js.lolifamily.minecraftmcp.repl.impl.SCRIPT_EXT

/**
 * The value/output join and the mojmap reverse-mapping of throwables. Pure functions over their arguments — no
 * REPL state. Game loader, so the lane can render without reaching across the masking seam.
 */
internal object EvalRender {

    /** outText + valueText, joined by a newline; `"(no output)"` when both are empty. */
    fun combine(outText: String, valueText: String): String {
        val o = outText.trimEnd('\n')
        return when {
            o.isEmpty() && valueText.isEmpty() -> "(no output)"
            o.isEmpty() -> valueText
            valueText.isEmpty() -> o
            else -> o + "\n" + valueText
        }
    }

    /**
     * Render a throwable's stack trace, reverse-mapping runtime (intermediary/srg) names back to mojmap when a
     * non-mojmap runtime is active — so a script that threw inside MC shows the class/method names the LLM
     * actually wrote (`ResourceLocation`, not `class_2960`). On a mojmap runtime (dev / NeoForge production)
     * [Mappings.current] is null and every name passes through untouched. Line numbers are unchanged by remap,
     * so they still point at the true source lines.
     *
     * Call it on the thread that ran the step: a [ScriptTimeoutError]'s frames are thread-matched.
     */
    fun stack(t: Throwable): String {
        val m = Mappings.current()
        val sb = StringBuilder()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())

        // Local so the walk carries only what actually varies down the chain; m/sb/seen are fixed for it.
        fun render(e: Throwable, caption: String, prefix: String, enclosing: Array<StackTraceElement>) {
            val cn = m?.reverseClass(e.javaClass.name) ?: e.javaClass.name
            if (!seen.add(e)) { sb.append(prefix).append(caption).append("[CIRCULAR ").append(cn).append("]\n"); return }
            sb.append(prefix).append(caption).append(cn)
            // Verbatim: the message is the thrower's own words, not ours to rewrite. Only names WE resolve —
            // the exception class above, the frames below — are demapped.
            e.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            val trace = e.stackTrace
            val shared = commonTail(trace, enclosing)
            for (line in frameLines(trace.take(trace.size - shared), m)) sb.append(prefix).append(line).append('\n')
            if (shared > 0) sb.append(prefix).append("\t... ").append(shared).append(" more\n")
            // Suppressed BEFORE cause, one level deeper: they belong to `e`, while a cause opens a level nothing
            // closes — emitted after, they read as the DEEPEST cause's, blaming the root for a `use {}` up here.
            for (s in e.suppressed) render(s, "Suppressed: ", "$prefix\t", trace)
            e.cause?.let { render(it, "Caused by: ", prefix, trace) }
        }

        render(t, "", "", emptyArray())
        appendWatchdogTrace(t, m, sb)
        return sb.toString().trimEnd('\n')
    }

    /** Trailing frames [trace] shares with [enclosing] — the tail a cause inherits from the throwable it is
     *  attached to, which `printStackTrace` renders as `... N more` instead of repeating it at every level. */
    private fun commonTail(trace: Array<StackTraceElement>, enclosing: Array<StackTraceElement>): Int =
        (1..minOf(trace.size, enclosing.size)).asSequence()
            .takeWhile { trace[trace.size - it] == enclosing[enclosing.size - it] }
            .count()

    /** Frame lines kept per stack, post-fold: above any real stack, below the JVM's 1024-frame recursion ceiling.
     *  Every stack, not just the timeout report — a throwable's own trace overflows the same way. */
    private const val MAX_FRAME_LINES = 128

    /**
     * A [ScriptTimeoutError] is an immutable singleton and carries no stack, so its frames come from the watchdog's
     * side channel instead: where the lane thread stood when the budget elapsed. Present only on the thread that
     * was caught ([TimeoutGuard.caughtHere]), so an off-lane render omits them.
     */
    private fun appendWatchdogTrace(t: Throwable, m: Mappings?, sb: StringBuilder) {
        if (t !is ScriptTimeoutError) return
        val frames = TimeoutGuard.caughtHere() ?: return
        for (line in frameLines(frames.asList(), m)) sb.append(line).append('\n')
    }

    /**
     * The frame block of one stack, ready to print: demapped, repeat-folded, then capped at [MAX_FRAME_LINES].
     * Shared by [stack]'s walk and [appendWatchdogTrace], which would otherwise hold the demap rules twice.
     *
     * Fold before cap: the recursion the cap bounds is what the fold collapses, so it rarely fires at all.
     * Capping first would drop 896 frames to save nothing and leave the fold's count understating the depth.
     *
     * What collapses is everything below the LAST script frame: the path INTO the script, fixed by this mod's
     * architecture rather than by anything the script did. The last, not the first — nested pumps put an outer
     * eval's script frame below an inner one's plumbing (vanilla re-enters the pump, see
     * [TimeoutGuard.endFrame]), and cutting at the first would swallow the reentrancy that explains the report.
     *
     * No script frame, no cut. That is also the whole of the compile stage: a compile crash is read from the
     * compiler's frames, and the run that called them is too short for a marker to pay for itself, so the same
     * rule against the last compiler frame was tried and dropped rather than kept as a branch that never fires.
     */
    private fun frameLines(frames: List<StackTraceElement>, m: Mappings?): List<String> {
        val lastScript = if (!FOLD) -1 else frames.indexOfLast(isScript)
        // "Nothing to fold" carried as an index past the end, not -1 — keeps the predicate a single compare.
        val cut = if (lastScript >= 0) lastScript else frames.size
        val folded = foldRepeats(foldRuns(frames, m) { it > cut })
        if (folded.size <= MAX_FRAME_LINES) return folded
        return folded.take(MAX_FRAME_LINES) + "\t... ${folded.size - MAX_FRAME_LINES} more line(s)"
    }

    /** Frames kept at each end of a collapsed run, so it stays visible who entered it (a reflective invoke on a
     *  first step, a coroutine resume on a later one) and where it bottoms out. */
    private const val FOLD_HEAD = 2
    private const val FOLD_FOOT = 2

    private val FOLD = Props.bool("mcp.stack.fold", true)

    /** A frame of the script's own code, keyed on the source name we hand the compiler. A loader/module test
     *  would also catch the game, whose loader is unnamed on a plugin host; a class-name test would guess at the
     *  compiler's mangling. Types the script declares itself compile from the same file, so they match too. */
    private val isScript = { f: StackTraceElement -> f.fileName?.endsWith(SCRIPT_EXT) == true }

    /** Collapse each maximal run the predicate accepts. A run too short for the marker to pay for itself is left
     *  whole — the same bar [foldRepeats] holds itself to. */
    private fun foldRuns(frames: List<StackTraceElement>, m: Mappings?, foldable: (Int) -> Boolean): List<String> {
        val out = ArrayList<String>(frames.size)
        var i = 0
        while (i < frames.size) {
            if (!foldable(i)) {
                out.add(renderFrame(frames[i], m))
                i++
                continue
            }
            var j = i
            while (j < frames.size && foldable(j)) j++
            if (j - i <= FOLD_HEAD + FOLD_FOOT + 1) {
                for (k in i until j) out.add(renderFrame(frames[k], m))
            } else {
                for (k in i until i + FOLD_HEAD) out.add(renderFrame(frames[k], m))
                out.add("\t... ${j - i - FOLD_HEAD - FOLD_FOOT} frames (mcp.stack.fold=false to keep them)")
                for (k in j - FOLD_FOOT until j) out.add(renderFrame(frames[k], m))
            }
            i = j
        }
        return out
    }

    /** Longest repeating block [foldRepeats] looks for: recursion cycles are short (1 direct, 2 mutual, a handful
     *  for a delegation loop), and the scan is bounded anyway by the JVM's 1024-frame trace ceiling. */
    private const val MAX_CYCLE = 32

    /**
     * Collapse consecutive repeats of a block of lines into one copy plus a count.
     *
     * Period-aware, which is the whole difficulty: folding only ADJACENT EQUAL lines catches direct recursion and
     * nothing else, since mutual recursion alternates two frames and never puts equal ones side by side.
     *
     * The winner at each position is the period covering the most frames, ties to the shortest — so `a a b` twice
     * folds as one 3-line block instead of `a` x2 plus an unfoldable remainder. It must also replace more than
     * `period + 1` lines, or the marker costs what it saves; that subsumes "repeats at least twice".
     *
     * Lines, not [StackTraceElement]s: what repeats for the reader is the rendered text, which also keeps the fold
     * independent of the mapping.
     */
    private fun foldRepeats(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var period = 0
            var reps = 0
            var cover = 0
            for (p in 1..minOf(MAX_CYCLE, (lines.size - i) / 2)) {
                val r = repeatsAt(lines, i, p)
                if (r * p > p + 1 && r * p > cover) { period = p; reps = r; cover = r * p }
            }
            if (period == 0) { out.add(lines[i]); i++; continue }
            for (k in 0 until period) out.add(lines[i + k])
            out.add("\t... last $period frame(s) x$reps ($cover frames)")
            i += cover
        }
        return out
    }

    /** How many times the block of [p] lines at [start] repeats back to back; 1 when it does not repeat. */
    private fun repeatsAt(lines: List<String>, start: Int, p: Int): Int {
        var n = 1
        var at = start + p
        while (at + p <= lines.size && (0 until p).all { lines[at + it] == lines[start + it] }) {
            n++
            at += p
        }
        return n
    }

    /**
     * One rendered frame: `net.minecraft.*` demapped to mojmap when [m] is non-null (a non-mojmap runtime), with
     * the source file derived from the demapped class so it stays consistent with it.
     *
     * The method name goes through [Signatures], not [Mappings.reverseMethod]: the owner-free reverse is unique
     * only on intermediary, and refusing every spigot obf name leaves the frame naming the throw site in obf.
     */
    private fun renderFrame(e: StackTraceElement, m: Mappings?): String {
        val demap = if (e.className.startsWith("net.minecraft.")) m else null
        val cn = demap?.reverseClass(e.className) ?: e.className
        val mn = if (demap == null) e.methodName else Signatures.methodToMojmap(e.className, e.methodName, e.lineNumber)
        // Past the `$` too: a nested class's SourceFile is its OUTER class's — Item$Properties lives in Item.java.
        val file = if (cn != e.className) cn.substringAfterLast('.').substringBefore('$') + ".java" else (e.fileName ?: "Unknown Source")
        val sb = StringBuilder("\tat ").append(cn).append('.').append(mn).append('(').append(file)
        if (e.lineNumber >= 0) sb.append(':').append(e.lineNumber)
        return sb.append(')').toString()
    }
}
