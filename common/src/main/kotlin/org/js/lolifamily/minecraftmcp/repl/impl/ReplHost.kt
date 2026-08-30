package org.js.lolifamily.minecraftmcp.repl.impl

import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.exec.Capture
import org.js.lolifamily.minecraftmcp.exec.IterEval
import org.js.lolifamily.minecraftmcp.exec.Outcome
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.repl.EvalRender
import org.js.lolifamily.minecraftmcp.repl.MaskingClassLoader
import org.js.lolifamily.minecraftmcp.repl.ReplBridge
import org.js.lolifamily.minecraftmcp.repl.ValueRender
import org.js.lolifamily.minecraftmcp.repl.scope.McpScope
import org.js.lolifamily.minecraftmcp.repl.scope.ScriptScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The REPL host. Loaded BY the masking loader (child-first) and reached from
 * [ReplBridge] on the game loader through the [MaskingBridgeImpl] adapter
 * (the one reflective hop) — so it can link the Kotlin compiler that lives on the masking loader's urls even
 * though those jars are NOT on the game runtime classpath. After the hop, everything here is ordinary Kotlin.
 *
 * Compilation and execution are separate ([compile] / [execute]) so a lane can compile OFF the tick
 * and run ON it. Each execution runs against a fresh [ScriptScope] implicit receiver, so the snippet's
 * shadowed `println` is captured and returned alongside the last expression's value.
 */
object ReplHost {
    /** The compile classpath the last [buildCompiler] ran with, so [warm] can drive a full
     *  compile+remap warmup without the gate having to pass it again. Stable across evals (same enumerated
     *  game classpath). Real compiles take their own [compile] argument — this is not an input to them. */
    @Volatile
    internal var lastClasspath: List<File>? = null
        private set

    /** This class's own loader — in dev too: ReplBridge puts the repl classes dir on the masking urls so
     *  `repl.impl.*` resolves child-first. Null only when repl.impl was not masking-loaded (dev without
     *  mcp.repl.classes, unit tests), where the regime machinery is inert. See [MaskingClassLoader.Regime]. */
    private val masking: MaskingClassLoader? by lazy {
        ReplHost::class.java.classLoader as? MaskingClassLoader
    }

    /** Enum identity comparison is safe here: mustDelegateToParent routes MaskingClassLoader — and its nested
     *  Regime — to the game loader, so exactly one Regime class can ever exist. */
    private fun isSplitRegime(): Boolean = masking?.regime() == MaskingClassLoader.Regime.SPLIT

    @Volatile private var preloadResult: Boolean? = null   // idempotency latch for preload() (null == not run yet)
    private val preloadLock = Any()

    /** Profile-guided preload of the compiler working set. Returns true on a TRAINING launch — preload skipped
     *  entirely, so the compiler build loads exactly the true working set for [recordWorkingSet] to capture;
     *  preloading first would make the working set indistinguishable from dead classes. Training is every
     *  launch with nothing to replay: no recorded list, a list stamped with another kotlin version, or
     *  `mcp.preload=false` — the only lever that retrains a list the stamp still calls current, for a classpath
     *  change the kotlin version cannot see. False only where a list really was preloaded. Idempotent. */
    fun preload(): Boolean {
        preloadResult?.let { return it }
        synchronized(preloadLock) {
            preloadResult?.let { return it }
            val r = doPreload()
            preloadResult = r
            return r
        }
    }

    private fun stampFile() = Services.PLATFORM.cacheDir.resolve("preload-classlist.stamp").toFile()

    private fun doPreload(): Boolean {
        // Skipping the preload is exactly what makes a launch recordable, so the disable flag is a training
        // launch like any other — and the only lever that retrains a list the stamp still calls current.
        if (!Props.bool("mcp.preload", true)) {
            Constants.LOG.info("[mcp-repl/preload] mcp.preload=false — TRAINING launch (preload skipped; recording after warm)")
            return true
        }
        val classlist = Services.PLATFORM.cacheDir.resolve("preload-classlist.txt").toFile()
        if (!classlist.isFile) {
            Constants.LOG.info("[mcp-repl/preload] no working-set list yet — TRAINING launch (preload skipped; recording after warm)")
            return true
        }
        // Recording runs on training launches only, so a list from an older kotlin can never learn what a bump
        // ADDED — it would under-cover silently, logging a healthy count the whole time.
        val key = masking?.ownKotlin ?: -1
        val trained = stampFile().takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (trained != key) {
            Constants.LOG.info("[mcp-repl/preload] list trained on kotlin {}, now {} — RETRAINING", trained, key)
            return true
        }
        val names = ArrayList<String>(12000)
        classlist.forEachLine { val s = it.trim(); if (s.isNotEmpty()) names.add(s) }
        if (names.isEmpty()) return true
        val loader = ReplHost::class.java.classLoader
        // Single dominant compiler jar (ZipFile-lock-bound) saturates ~4 threads; reserve 2 for the game and run
        // below-normal since this fires during game startup.
        val nThreads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
        val cursor = AtomicInteger(0)
        val t0 = System.nanoTime()
        val pool = (0 until nThreads).map { t ->
            Thread({
                var i = cursor.getAndIncrement()
                while (i < names.size) {
                    try {
                        Class.forName(names[i], false, loader)
                    } catch (_: LinkageError) {
                    } catch (_: ClassNotFoundException) { // stale list entry (renamed class) — harmless
                    } catch (_: RuntimeException) {}
                    i = cursor.getAndIncrement()
                }
            }, "mcp-preload-$t").apply {
                isDaemon = true; priority = Thread.NORM_PRIORITY - 2; contextClassLoader = Constants.GAME_LOADER
                start()
            }
        }
        pool.forEach { it.join() }
        Constants.LOG.info(
            "[mcp-repl/preload] preloaded {} working-set classes ({}ms, {} threads)",
            names.size, (System.nanoTime() - t0) / 1_000_000, nThreads,
        )
        return false
    }

    /** Warm the eval path — compile AND run — so the first real eval pays none of it on the tick thread.
     *  `iterator { yield(1) }` is the widest single shape: it compiles a suspend state machine (a superset of a
     *  scalar snippet) and is the only one that reaches the snippet loader and the cross-tick path.
     *  Running it, not just compiling it, is also what puts those classes in front of [recordWorkingSet] — what
     *  never runs here never enters the preload classlist, and is never preloaded on any later launch either. */
    fun warm() {
        val cp = lastClasspath ?: return   // set by buildCompiler; the gate always builds before warming
        val src = "iterator { yield(1) }"
        try {
            // Not an IterEval => compile or eval failed (both return an Outcome rather than throw), so nothing
            // past the compiler got warmed. Silence there would read as success.
            val r = execute(compile(src, cp, "", 0), src, Capture())
            if (r is IterEval) {
                r.iterator.hasNext()
            } else {
                // `.text`, not the Outcome itself: its toString withholds that read, which drains the eval's
                // sink. Draining THIS one costs nothing — the Capture above is a throwaway nobody else reads.
                Constants.LOG.warn("[mcp-repl/warm] warm eval failed: {}", (r as Outcome).text)
            }
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/warm] warm eval threw", t)
        }
    }

    /** Training-launch capture: write every kotlin / kotlin-compiler class the masking loader has defined (= the
     *  true compiler working set, since a training launch runs with preload off) to [path], plus the kotlin
     *  version stamp [doPreload] gates on. The set comes from [MaskingClassLoader.definedClassNames]. */
    fun recordWorkingSet(path: String) {
        try {
            val ml = masking
            if (ml == null) {
                Constants.LOG.warn("[mcp-repl/warm] not on the masking loader; cannot record the working set")
                return
            }
            val found = sortedSetOf<String>()
            for (n in ml.definedClassNames()) {
                if (n.startsWith("org.jetbrains.kotlin.") || n.startsWith("kotlin.")) found.add(n)
            }
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(found.joinToString("\n"))
            AtomicFiles.fsync(f.toPath())
            val stamp = stampFile()
            stamp.writeText(ml.ownKotlin.toString())   // after the list: a crash between the two retrains
            AtomicFiles.fsync(stamp.toPath())
            Constants.LOG.info("[mcp-repl/warm] recorded {} working-set classes -> {}", found.size, f.absolutePath)
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/warm] recordWorkingSet failed (next launch retrains)", t)
        }
    }

    /** Per-eval unique id → unique source name → unique script class, and the label a spilled dump is filed
     *  under. Minted here so the compiler and the failure report can never disagree about which eval it was. */
    private val evalSeq = AtomicLong()

    /** Compile [code]. Off-tick; [PlainEngine] serializes compilation. */
    internal fun compile(code: String, cpFiles: List<File>, killIdField: String, evalId: Int): PlainEngine.Compiled {
        lastClasspath = cpFiles
        buildCompiler(cpFiles)
        val sourceName = "mcp_eval_${evalSeq.incrementAndGet()}"
        try {
            return PlainEngine.compile(code, sourceName, killIdField, evalId)
        } catch (t: Throwable) {
            // Always rethrows — [spilling] hands back the original untouched unless it is carrying an IR dump,
            // so a control-flow throwable passes through as if this catch were not here.
            throw spilling(t, evalId, sourceName, code)
        }
    }

    /**
     * Run a handle from [compile], writing the snippet's `println` into [out], and return `captured out` +
     * `=> value`. On-tick (may yield). The script body IS the class constructor, so constructing it runs it,
     * and [PlainEngine.Compiled.Ok.resultType] already carries its own `?` — nothing here re-adds one.
     */
    internal fun execute(compiled: PlainEngine.Compiled, code: String, out: Capture): Any = when (compiled) {
        is PlainEngine.Compiled.Failed -> Outcome(renderFailure(compiled.reports, code), true)
        is PlainEngine.Compiled.Ok -> {
            val loader = masking?.snippetParentLoader() ?: ReplHost::class.java.classLoader
            val value = try {
                PlainEngine.execute(compiled, ScriptScope(out), loader)
            } catch (t: Throwable) {
                // The body runs in a constructor, so anything it throws arrives wrapped by reflection.
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
                if (cause is LinkageError && isSplitRegime()) {
                    Constants.LOG.warn(
                        "[mcp-repl] SPLIT snippet↔host seam LinkageError — if this recurs, try mcp.kotlin.regime=share",
                        cause,
                    )
                }
                return Outcome(true) { EvalRender.combine(out.take(), "script threw:\n" + EvalRender.stack(cause)) }
            }
            if (value is Iterator<*> && isBuilderIterator(value)) {
                val errored = AtomicBoolean(false)
                IterEval(guardIterator(value, out, errored), errored)
            } else {
                // Eager, unlike the drain below: `toString()` is caller code and a game object's reads game state,
                // so it belongs on the thread that ran the eval. The cost is that a huge value spends that tick —
                // unguarded, the timeout check being woven into script bytecode and never into ours.
                val text = compiled.resultType?.let { ValueRender.line(it, value).toString() }.orEmpty()
                Outcome(false) { EvalRender.combine(out.take(), text) }
            }
        }
    }

    /** True for a cross-tick builder — ours, or the stdlib's when a snippet fully-qualified past the shadow — and
     *  false for `listOf(...).iterator()` and friends, whose value would otherwise be driven away as a generator
     *  and returned as "(no output)".
     *
     *  [McpScope] by `is`: the value comes from the snippet and the test is here, so the two have to agree on
     *  the class — they do, `repl.*` being delegated to the game loader from both sides.
     *  `SequenceScope` by NAME, not `is`: that one is `kotlin.*`, so in
     *  SPLIT the snippet's copy and ours are different classes and `is` would answer false for every builder. */
    private fun isBuilderIterator(v: Any): Boolean {
        if (v is McpScope<*>) return true
        var c: Class<*>? = v.javaClass
        while (c != null) {
            if (c.name == "kotlin.sequences.SequenceScope") return true
            c = c.superclass
        }
        return false
    }

    /**
     * Wrap the snippet's cross-tick iterator so a throw mid-drive doesn't discard what it already printed:
     * the (mojmap-remapped) stack is appended to `out` and the iterator ends cleanly, so the lane returns the
     * partial output together with the error. Without this, a throw in next() would propagate to the lane
     * pump, which completes the future with just the exception text and loses the buffered output. The throw
     * goes through stack() so it's mojmap-remapped like every other throw.
     */
    private fun guardIterator(raw: Iterator<*>, out: Capture, errored: AtomicBoolean): Iterator<Any?> = object : Iterator<Any?> {
        private var done = false
        override fun hasNext(): Boolean = !done &&
            try {
                raw.hasNext()
            } catch (t: Throwable) {
                fail(t); false
            }
        override fun next(): Any? {
            if (done) throw NoSuchElementException()
            return try {
                raw.next()
            } catch (t: Throwable) {
                fail(t); null   // this step's value is ignored; the next hasNext() returns false to end cleanly
            }
        }
        private fun fail(t: Throwable) {
            done = true
            errored.set(true)
            out.append("\nscript threw:\n" + EvalRender.stack(t))
        }
    }

    /** Build the warm K2 compiler (overlay + createCompilationState). Assumes [preload] already ran (eager path:
     *  the preload thread; lazy path: the idempotent call below). */
    @Synchronized
    fun buildCompiler(cpFiles: List<File>) {
        // remember it so warm() can drive a full compile+remap warmup (the gate always builds
        // before warming); real evals overwrite with the same value.
        lastClasspath = cpFiles
        preload()   // idempotent — a no-op when the preload thread already ran it (eager path)
        // parentApiVersion is non-null only in SPLIT, where the snippet compiles on our compiler but runs on
        // the game's older stdlib — pin it to that API level so it can't reference APIs the game lacks.
        PlainEngine.warmUp(cpFiles, masking?.parentApiVersion())
    }
}
