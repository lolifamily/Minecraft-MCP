package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.EvalRender
import org.js.lolifamily.minecraftmcp.repl.ReplBridge
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * One eval scheduled on a lane. Compilation runs OFF the tick (pure computation) on a short-lived thread;
 * once compiled the task enters the lane and is EXECUTED ON THE TICK THREAD by the pump — so the snippet
 * runs with the real server/client/render thread identity (`ensureOnSameThread` passes), not a
 * surrogate thread.
 *
 * Cross-tick work is native Kotlin: a snippet whose value is an `iterator { ... yield() ... }` hands back an
 * [IterEval], and the pump advances that iterator one element per tick on the tick thread, so `yield()`
 * suspends the coroutine and resumes it on that same thread. `println` and whatever `yield` renders both
 * accumulate in `out`, which is returned when the iterator is exhausted.
 *
 * The result is an [Outcome] (text + isError), so every way an eval can end — clean finish, runtime
 * throw, compile failure, cancellation, reap — reports the right tools/call `isError` AND keeps whatever
 * was printed before it ended. Partial output is never dropped.
 */
internal class EvalTask(
    private val code: String,
    private val onCompiled: (EvalTask) -> Unit, // enqueue into the lane once compiled
    /** The lane epoch this task was submitted against; if its heartbeat stopped for good while it was still
     *  compiling, it's reaped unrun. */
    val epoch: Long,
    /** Which timeout kill-id field this lane's scripts read (baked in at compile), or `null` for no guard at
     *  all — the off-tick [ParallelLane], which has no watchdog. */
    private val guardLane: GuardLane?,
) {

    /** This eval's token, baked into its woven guard so only THIS eval's code answers to a kill. 1-based, which
     *  is what leaves 0 free as [TimeoutGuard]'s "nobody". */
    val id = SEQ.incrementAndGet()
    val future = CompletableFuture<Outcome>()

    @Volatile
    private var handle: Any? = null // compiled script (opaque)

    @Volatile
    private var started = false // has the first (execute) step run?

    @Volatile
    private var iterEval: IterEval? = null // set if the snippet returned a cross-tick iterator

    /** This eval's println sink, taken once at the end. Owned here, not inside the REPL host, so that BOTH
     *  shapes — single-tick and cross-tick — can still report partial output on a kill, cancel or timeout. */
    private val out = Capture()

    @Volatile
    private var finalResult: Outcome? = null

    @Volatile
    private var dead = false

    /** The dedicated worker thread driving this eval off-tick ([ParallelLane]); `null` for the heartbeat
     *  lanes, whose evals run on the (never-interruptible) tick thread. Set once, before the worker starts. */
    @Volatile
    private var worker: Thread? = null

    /** Compile off the tick, then enqueue. The pump does the rest, on the tick thread. */
    fun start() {
        @Suppress("ktlint:standard:wrapping")
        val th = Thread({
            // This thread owns the future until onCompiled hands the task to a driver.
            try {
                handle = ReplBridge.compile(code, guardLane?.killIdField.orEmpty(), id) // OFF-TICK: instrument+remap
                onCompiled(this) // now eligible to be executed on the tick
            } catch (t: Throwable) {
                // Full chain, not "$t": a compiler internal error names only itself at the top ("Exception while
                // generating code for: <IR dump>") and carries the actual fault two causes down. toString() drops
                // every cause and every frame, so the one line that identifies the bug never reaches the caller.
                future.complete(Outcome("eval failed to start:\n" + EvalRender.stack(t), true))
            }
        }, "mcp-compile-$id")
        th.isDaemon = true
        th.contextClassLoader = Constants.GAME_LOADER
        th.start()
    }

    /**
     * Advance one step, ON the tick thread (the pump calls this). The first call executes the snippet; if
     * it returned an iterator, each later call drives one element (resuming the coroutine on this thread),
     * with output going to `out` via `println`. @return true when finished.
     */
    fun pumpStep(): Boolean {
        if (dead) return true
        if (!started) {
            started = true
            val r = ReplBridge.execute(handle, code, out) // runs on THIS (tick) thread
            if (r is IterEval) { // cross-tick: drive one element per tick
                iterEval = r
                return false
            }
            finalResult = r as Outcome // single-tick: Outcome (normal / throw / compile-fail)
            return true
        }
        // Non-null once `started`: the first step returns true (ending the pump) unless it set this.
        val ie = checkNotNull(iterEval) { "pumpStep resumed with no iterator" }
        val it = ie.iterator
        if (it.hasNext()) {
            it.next() // resume on this thread; output -> out via println
            return false
        }
        // Iterator exhausted — or ended by the guard after a step threw. Either way return the accumulated
        // output, marked isError iff a step threw (the guard already appended the stack to out).
        finalResult = Outcome(ie.errored) { out.take().trim().ifEmpty { "(no output)" } }
        return true
    }

    /** Normal end, called by the driver once [pumpStep] reports done. A no-op after a [kill], [cancel],
     *  [fail] or [reportTimeout]: those complete the future directly and leave [finalResult] null. */
    fun finish() {
        finalResult?.let { future.complete(it) }
    }

    /** Reap: this eval's lane will never step it again. Stop driving and complete with a "killed" result —
     *  isError rather than silence, which would leak the blocked request — keeping whatever it had printed.
     *  Stops the eval by the same two mechanisms as [cancel]. */
    fun kill(reason: String) {
        if (future.isDone) return
        dead = true
        future.complete(Outcome(true) { withOutput("killed: $reason") })
        worker?.interrupt()
    }

    /**
     * Client-initiated cancellation (`notifications/cancelled`). Completes the future now, not next tick, with
     * whatever the eval had printed, tagged `(cancelled)` and marked isError. Idempotent; a no-op if the eval
     * already finished.
     *
     * Two mechanisms stop the eval, and [kill] uses the same pair:
     *  - `dead = true` — observed at the next [pumpStep] entry. Enough for the heartbeat lanes and for a
     *    cross-tick iterator between elements.
     *  - `worker?.interrupt()` — an off-tick [ParallelLane] worker can be blocked inside a single long step
     *    (I/O, sleep, park) whose `pumpStep` boundary never arrives; interrupt unblocks it. Null for the
     *    heartbeat lanes, whose tick thread must never be interrupted. `dead` is set before the interrupt, so
     *    the woken worker sees a dead task and unwinds. Not a hard kill: a step that swallows the interrupt or
     *    spins without ever blocking can't be stopped — the JVM offers no safe forcible stop.
     */
    fun cancel() {
        if (future.isDone) return
        dead = true
        future.complete(Outcome(true) { withOutput("(cancelled)") })
        worker?.interrupt()
    }

    /** Whether this step already ended in a reported error — on a tripped frame, that the scriptguard's throw
     *  survived and the normal completion path owns the report. */
    val errored: Boolean get() = iterEval?.errored ?: (finalResult?.isError == true)

    /** The step threw and no inner guard owned it — in practice [EvalRender.stack] itself failing, since every
     *  script throw is answered before this. Completing the future is the whole job; the alternative is a caller
     *  blocked on it forever. So NOTHING here may throw: the render is deferred onto that caller, which unlike
     *  a tick thread has somewhere to put a second failure. Here rather than in the driver because `out` is
     *  private, and no ending may drop it. */
    fun fail(t: Throwable) {
        if (future.isDone) return
        dead = true
        future.complete(Outcome(true) { EvalRender.combine(out.take(), "eval step failed:\n" + EvalRender.stack(t)) })
    }

    /**
     * The scriptguard threw into the step that just ran and nothing reported it, so something swallowed it: the
     * run carried on past an interruption and whatever it produced means nothing. End the eval with the
     * watchdog's report, keeping what it had printed. Not always a swallow, though — a blocking call
     * (`Thread.sleep`, a lock, IO) gives the guard no point to fire at, so the step can return intact; that
     * value is dropped all the same, on purpose — handing it back is the one answer that leaves the caller
     * no way to learn the tick stood frozen for the whole step.
     *
     * [stepNanos] is how long this step itself ran. The budget is shared by every eval stepped in the tick, so
     * a small value means another eval spent it and this one merely happened to be running when it ran out.
     *
     * Tick thread only: [EvalRender.stack] reads the watchdog's frames off a thread-matched side channel.
     */
    fun reportTimeout(stepNanos: Long) {
        if (future.isDone) return
        dead = true
        // No more specific than this: Patches records no installing eval, an install can happen on any thread,
        // and an escaped handler has no tool call of its own to report into.
        val report = "script interrupted ${stepNanos / 1_000_000}ms into this step; handlers it installed " +
            "(Patches, spawned threads) may also have been hit:\n" +
            EvalRender.stack(TimeoutGuard.timeout)
        future.complete(Outcome(true) { EvalRender.combine(out.take(), report) })
    }

    /** Re-interrupt a worker that outlived its [kill]. [kill] / [cancel] bail on an already-completed future, so
     *  their single `interrupt()` is all an eval ever gets — and a step that swallowed it, or wasn't blocked
     *  yet, needs another. Only pokes an already-dead task; a live one is still authorized. */
    fun nudge() {
        if (dead) worker?.interrupt()
    }

    /** Publish the dedicated off-tick worker before it starts, so a [cancel] racing the first step has a
     *  thread to interrupt. Only [ParallelLane] calls this; the heartbeat lanes leave [worker] null. */
    fun bindWorker(t: Thread) { worker = t }

    /** Captured output so far + a terminal `tail` marker, so a reap or cancel returns the same shape the
     *  normal-finish and mid-drive-throw paths do. Exactly one of those paths ever runs: the loser's deferred
     *  text supplier is never invoked, because completing an already-done future is a no-op. */
    private fun withOutput(tail: String): String {
        val captured = out.take().trim()
        return if (captured.isEmpty()) tail else "$captured\n$tail"
    }

    companion object {
        private val SEQ = AtomicInteger()
    }
}
