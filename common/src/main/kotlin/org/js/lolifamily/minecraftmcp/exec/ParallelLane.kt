package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.security.AuthGate
import org.js.lolifamily.minecraftmcp.security.ClientAuthProbe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Off-tick execution lane. Unlike the heartbeat-pumped tick lanes ([Lane]), which serialize every eval
 * through one pump on one game thread under the 1s [TimeoutGuard], this runs each eval CONCURRENTLY on its
 * own worker thread — no tick affinity, no cross-tick yield, no scriptguard. For work that must NOT touch
 * live game state under a tick thread's identity: pure computation, blocking I/O, anything that would
 * freeze a tick or trip the watchdog. Always [isReady] (there is no heartbeat to observe).
 *
 * Reuses [EvalTask] verbatim — the only difference from a tick lane is the DRIVER of [EvalTask.pumpStep]:
 * a tick lane's heartbeat steps it once per RETURN; here a worker thread drives it to completion in a tight
 * loop (a `yield()` in the snippet resumes immediately — there is no tick boundary to wait for).
 *
 * Nothing reaps this lane: with no heartbeat there is no boundary past which an eval could be stranded. The
 * stop signals are per-request [EvalHandle.cancel], an authorization revoke ([killAll], pushed from
 * [ClientAuthProbe]), and JVM exit (daemon threads, so shutdown never waits). Each lands one `interrupt()`;
 * [nudgeAll] is the best-effort retry behind them.
 */
class ParallelLane(override val name: String) : ExecLane {

    override val isReady: Boolean get() = true

    /** In-flight evals. The worker's own `finally` is the only site that removes one, so presence here means
     *  "that thread has not unwound yet". */
    private val live = ConcurrentLinkedQueue<EvalTask>()

    // Below `live`, which the sweep reads: initializers run in declaration order, so it is assigned before
    // `this` escapes to the scheduler.
    init {
        NUDGER.scheduleWithFixedDelay(::nudgeAll, NUDGE_MS, NUDGE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Kill every in-flight eval on this lane. PUSHED from [ClientAuthProbe.observe] — on every client tick the
     * gate reads denied — rather than polled by the eval itself: this lane bakes in no scriptguard, and a snippet
     * that never yields runs to completion inside ONE [EvalTask.pumpStep], so there may be no boundary at which
     * the eval could ever notice a revoke.
     *
     * Entries stay in `live` until their worker unwinds, so an already-killed task is [EvalTask.nudge]d instead:
     * one `interrupt()` is not reliably enough, and [nudgeAll] is the retry. A step that swallows every
     * interrupt and never blocks cannot be stopped — the JVM's ceiling, not this lane's.
     *
     * @return how many evals are still live
     */
    fun killAll(reason: String): Long {
        if (live.isEmpty()) return 0L // the common case at 20Hz — skip the iterator allocation
        var n = 0L
        for (t in live) {
            t.kill(reason) // no-op once its future is complete...
            t.nudge() // ...which is why the re-interrupt is a separate call
            n++
        }
        return n
    }

    /** One retry pass. Nothing may escape: `scheduleWithFixedDelay` SILENTLY cancels the whole schedule on the
     *  first throwable. */
    private fun nudgeAll() {
        if (live.isEmpty()) return // the common case at 4Hz — skip the iterator allocation, as killAll does
        for (t in live) {
            runCatching { t.nudge() }.onFailure {
                Constants.LOG.warn("[exec] {} lane nudge failed ({})", name, it.javaClass.name)
            }
        }
    }

    override fun submit(code: String, beforeStart: (EvalHandle) -> Unit): EvalHandle {
        // guardLane = null => no kill-id baked in; ReplHost skips guard instrumentation entirely.
        val t = EvalTask(code, onCompiled = ::drive, epoch = 0, guardLane = null)
        val h = EvalHandle(t)
        beforeStart(h)   // register for cancellation before the eval can run
        t.start() // compiles off-thread (as every eval does), then calls drive() once compiled
        return h
    }

    /**
     * Run the compiled eval to completion on ONE dedicated daemon thread, draining any cross-tick iterator
     * in a tight loop. Mirrors [Lane.pump]'s per-task completion contract.
     *
     * Thread-per-task, not a pool: this lane's evals are long, so reuse buys nothing, and a shared pool would
     * let [EvalTask.cancel]'s `interrupt()` hit a reused thread running a different task, or leak a stale
     * interrupt flag onto the next one. The worker is published via [EvalTask.bindWorker] before `start()`, so
     * a cancel racing the first step still has a thread to interrupt.
     */
    private fun drive(t: EvalTask) {
        @Suppress("ktlint:standard:wrapping")
        val worker = Thread(Runnable {
            try {
                // Re-check before the first execution: McpServer gated at entry, but compilation can take seconds.
                // After this the eval never polls the gate — a revoke reaches it by push, through [killAll].
                // INSIDE the try: `finally` is this worker's only unregister site, so every exit it can
                // take has to pass through it.
                if (!AuthGate.allowed) { t.kill("authorization revoked before first step"); return@Runnable }
                while (!t.pumpStep()) { /* resume immediately; no tick, no guard */ }
                t.finish()
            } catch (th: Throwable) {
                // A cancel's interrupt also lands here as the worker unwinds a blocking step; fail() bails on
                // the done future, so the cancel's own text stands.
                t.fail(th)
            } finally {
                live.remove(t)
            }
        }, "mcp-parallel-${SEQ.incrementAndGet()}")
        worker.isDaemon = true
        worker.contextClassLoader = Constants.GAME_LOADER
        t.bindWorker(worker)
        live.add(t) // BEFORE start(): a revoke racing the first step must still find this eval to kill it
        try {
            worker.start()
        } catch (e: Throwable) {
            live.remove(t) // the release is the worker's `finally`, and a thread that never ran has none
            throw e
        }
    }

    companion object {
        private val SEQ = AtomicInteger()

        /** How long between retries. Only ever paid by a worker that outlived an interrupt — one that unwinds
         *  on the first is off `live` before the next pass. */
        private const val NUDGE_MS = 250L

        /** Drives [nudgeAll]. One daemon thread for the process; the pass itself is a walk of a queue that is
         *  empty whenever nothing has been killed. */
        private val NUDGER = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "mcp-parallel-nudge").apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }
        }
    }
}
