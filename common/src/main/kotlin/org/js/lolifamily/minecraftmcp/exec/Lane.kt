package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.security.AuthGate
import org.js.lolifamily.minecraftmcp.security.ClientAuthProbe
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One execution lane: a tick source (a game method a Mixin injects at RETURN — the "heartbeat") whose
 * every fire steps the lane's active evals once, on that source's own thread, so a step has exclusive
 * access to that side's game state. Three lanes exist ([Lanes]): server (server tick), client (client
 * tick), render (render frame) — identical machinery, different heartbeat.
 *
 * Readiness is asked, never timed: a [LiveProbe] answers from the physical side and from the tick source itself,
 * so a stall is not death — a paused game, an autosave or a slow `/tick rate` stays ready — and a source that
 * really has stopped says so at once, with no window in which a submission can join a queue nothing will pump
 * again. A remote-connected client has no local `MinecraftServer`, so its server lane is simply not ready.
 *
 * An eval is reaped for exactly ONE reason: its heartbeat will never fire again, which would hang the caller
 * blocked on its future. A world unload, a dimension change, a server switch all leave the heartbeat firing, so
 * the eval keeps stepping and ends on its own terms — finish, throw, watchdog, or cancel. That one boundary
 * can't be seen by the stopped pump itself, so it arrives from outside as [reapOnStop]
 * (`MinecraftServer#stopServer`; `onDisable` on Paper). Client and render have none short of JVM exit.
 */
class Lane internal constructor(override val name: String, private val liveProbe: LiveProbe) : ExecLane {

    private val active = ConcurrentLinkedQueue<EvalTask>()

    /** Evals stepped this tick that aren't done. Parked out of [active] for the rest of the pump so the drain
     *  loop can't hand a task straight back to itself, then flushed back in [stepAll]'s finally. Reaped and
     *  removed alongside [active] everywhere — an eval parked here is exactly as live as one still queued. */
    private val stepped = ConcurrentLinkedQueue<EvalTask>()

    /** What the last heartbeat handed in — the `MinecraftServer` on the server lane, the `Minecraft` on
     *  client/render; null before the first pump. Republished every pump because [isReady] and `run_command`
     *  read it from the HTTP thread, and both must get a current value on an idle lane too. */
    @Volatile
    var tickSource: Any? = null
        private set

    /** Advanced only by [reapOnStop]; [offerOrReap] compares against it to kill a straggler still compiling
     *  when the heartbeat stopped for good. */
    @Volatile
    private var currentEpoch: Long = 0

    override val isReady: Boolean
        get() = liveProbe.isLive(tickSource)

    /** Pump the lane once per heartbeat fire — called by the lane's Mixin (`MixinMinecraftServer` /
     *  `MixinMinecraft`) at the tick's RETURN, on that side's own thread. Republishes [self] as the lane's
     *  handle, then steps each active eval once. Public so the mixin (a different package) can call it.
     *
     *  Never throws: the caller is a tick injector with nowhere to handle one. */
    fun pump(self: Any?) {
        try {
            pumpBody(self)
        } catch (t: Throwable) {
            // Class name, not the throwable: formatting a script-defined toString() is part of what we contain.
            Constants.LOG.error("[exec] {} lane pump failed ({})", name, t.javaClass.name)
        }
    }

    private fun pumpBody(self: Any?) {
        // Auth-gate compute, client lane ONLY: the server lane's self is a MinecraftServer (ClientAuthProbe's
        // `self as Minecraft` would CCE), and render's self — also a Minecraft — would merely recompute the
        // same decision 60-300x/s instead of ~20x/s. Only the client thread may read live client session
        // state. Runs BEFORE the empty-queue early-return below, so the gate stays fresh every client tick even
        // when idle (the HTTP entry check needs a current value for the first eval). The call to the client-only
        // ClientAuthProbe (GETSTATIC INSTANCE + INVOKEVIRTUAL — it's a Kotlin object) never executes on a
        // dedicated server (name is never "client" there), so neither it nor Minecraft is ever resolved or
        // loaded — see AuthGate / ClientAuthProbe.
        // A stalled client tick freezes the decision rather than re-deriving it, and that is left unguarded:
        // this same pump steps the lane's evals, so a frozen tick stops them with it, and the off-tick lanes
        // that outlive it reach game state only through `Minecraft#submit`, which queues onto that dead thread.
        // A staleness timer would buy nothing and would deny during a slow resource load.
        if ("client" == name) ClientAuthProbe.observe(self)

        // Same reason as the gate above for sitting ahead of the early-return: isReady and tickSource are read
        // from the HTTP thread, and an idle lane is exactly when the session's first submission arrives.
        tickSource = self

        if (active.isEmpty()) { // isEmpty, not size == 0: ConcurrentLinkedQueue.size() walks the chain
            TimeoutGuard.idle(guardLane())
            return
        }

        // Auth-gate enforce, every lane, before any eval steps: if authorization dropped (cheats turned off,
        // deopped, disconnected), kill every active eval here rather than let it run another line. Read once per
        // pump, not per step — a revoke landing mid-loop still lets an eval not yet polled take one more step,
        // which is bounded by the shared timeout budget below, and the next heartbeat reaps the rest.
        if (!AuthGate.allowed) {
            val revoked = reapAll("authorization revoked")
            if (revoked > 0) Constants.LOG.info("[exec] {} lane authorization revoked — reaped {} eval(s)", name, revoked)
            return
        }

        stepAll()
    }

    /** Step every queued eval once, in order, under ONE timeout budget for this tick: they run serially on this
     *  one tick thread, so their combined time is what would freeze it. The guard inlined into each script reads
     *  this lane's kill-id field, which beginFrame's watchdog writes when the budget elapses (see TimeoutGuard).
     *  Drains to empty, so an eval that finishes compiling mid-pump runs this tick instead of waiting out a
     *  heartbeat.
     *
     *  Whatever this loop polls is epoch-current: [offerOrReap] is the only door into `active` and refuses a
     *  stale task, and [currentEpoch]'s sole writer ([reapOnStop]) runs on this thread outside any pump. */
    private fun stepAll() {
        val gl = guardLane()
        try {
            TimeoutGuard.beginFrame(gl) // inside the try: begin/end must pair or the frame depth leaks
            while (true) {
                val t = active.poll() ?: break
                // Budget already gone, so this eval gets no step this tick. End it rather than defer it: a
                // skipped tick would silently gap a cross-tick series, and a loud failure beats a silent gap.
                // Its own bytecode never ran, so it must not read the offender's "spread the work" advice.
                if (TimeoutGuard.tripped(gl)) {
                    t.kill("tick budget spent by other evals before its turn — retry")
                    continue
                }
                val startedAt = System.nanoTime()
                val prevStep = TimeoutGuard.enterStep(gl, t.id)
                val done: Boolean = try {
                    t.pumpStep()
                } catch (th: Throwable) {
                    // A tripped frame owns the report even here: what escaped is downstream of the
                    // interruption, and naming it would replace "the tick stood frozen" with a symptom.
                    if (TimeoutGuard.tripped(gl)) t.reportTimeout(System.nanoTime() - startedAt) else t.fail(th)
                    continue
                } finally {
                    TimeoutGuard.exitStep(gl, prevStep)   // restore, not clear: steps nest — see TimeoutGuard
                }
                // The budget elapsed inside that step — the check above proves it hadn't before. It ends as
                // a timeout whether or not the guard actually got to fire — see EvalTask.reportTimeout.
                if (TimeoutGuard.tripped(gl) && !t.errored) {
                    t.reportTimeout(System.nanoTime() - startedAt)
                    continue
                }
                if (done) t.finish() else stepped.offer(t)
            }
        } finally {
            // Ahead of endFrame, which can throw: `stepped` has no other exit, and what stays there is never
            // pumped again — the next heartbeat finds `active` empty and returns before reapAll could catch it.
            // Through offerOrReap, not a bare offer: between the poll and the re-queue a task is in neither
            // queue, and only its publish-then-recheck catches a boundary that landed in that window.
            generateSequence { stepped.poll() }.forEach(::offerOrReap)
            TimeoutGuard.endFrame(gl)
        }
    }

    /** Which timeout kill-id lane this is: the server lane runs on the Server thread; the client and render
     *  lanes share the Render thread → RENDER. Known from the lane name at submit time, so it's baked into the
     *  script's inlined GETSTATIC at compile/instrument time (no runtime thread sniffing). */
    private fun guardLane(): GuardLane = if ("server" == name) GuardLane.SERVER else GuardLane.RENDER

    /** Kill and drop every active eval. Safe from any thread: drains concurrent queues; `kill` is idempotent.
     *  Two callers: an auth revoke (on the pump thread, which keeps pumping and so self-heals any straggler
     *  still compiling), and [reapOnStop]. */
    private fun reapAll(reason: String): Long {
        var count = 0L
        // [stepped] first: a pump flushing concurrently moves tasks stepped -> active, so taking active last
        // still catches whatever crossed over.
        for (q in arrayOf(stepped, active)) {
            while (true) {
                val t = q.poll() ?: break
                t.kill(reason)
                count++
            }
        }
        return count
    }

    /**
     * The lane's one terminal boundary, signaled from outside the pump: `MinecraftServer#stopServer` (Paper:
     * `onDisable`). Past it the heartbeat never fires again, so every queued eval would hang the request blocked
     * on it — including one still compiling, which will [offerOrReap] itself into the queue a moment later.
     * Advance the epoch before draining — see [offerOrReap] for why that pairing strands nothing. Server thread
     * only, and the sole writer of [currentEpoch].
     */
    fun reapOnStop(reason: String): Long {
        currentEpoch++
        return reapAll(reason)
    }

    /**
     * Schedule `code` on this lane. It compiles off the tick, joins the lane once compiled, and is stepped every
     * heartbeat until done. It's bound to the epoch current at submit: if the heartbeat has stopped for good by
     * the time it finishes compiling, it's killed rather than queued forever. The returned [EvalHandle] carries
     * the result future (callers block on it — block-until-done) and can cancel the eval (the client-initiated
     * `notifications/cancelled` path).
     */
    override fun submit(code: String, beforeStart: (EvalHandle) -> Unit): EvalHandle {
        val t = EvalTask(code, ::offerOrReap, currentEpoch, guardLane())
        val h = EvalHandle(t)
        beforeStart(h)   // register for cancellation before the eval can run
        t.start()
        return h
    }

    /**
     * Every route into `active`: the compile-completion handoff on the short-lived compile thread once
     * [EvalTask.start] finishes compiling, and [stepAll]'s flush of an eval it stepped but didn't finish.
     * Publish the task into `active`, then re-read the epoch it was bound to at submit. Epoch unchanged: it waits
     * for the next pump. Epoch advanced while it was compiling: it's stale, so pull it back out and kill it here,
     * completing its future — otherwise the blocked caller hangs on a queue that will never be pumped again.
     *
     * Publish-before-recheck pairs with [reapOnStop]'s epoch-bump-before-drain: a straggler either lands in
     * `active` in time for that drain, or misses it and sees the advanced epoch here. `active.remove` returning
     * false means a concurrent reap already took it; `kill` is idempotent, so the future completes exactly once.
     *
     * Readiness is rechecked alongside the epoch because the two boundaries don't coincide: a server's liveness
     * flag drops at `halt()`, but the epoch only advances at `stopServer()` — a straggler landing between them
     * sees an unchanged epoch and a source that will never pump again.
     */
    private fun offerOrReap(t: EvalTask) {
        active.offer(t)
        if ((t.epoch != currentEpoch || !isReady) && active.remove(t)) {
            t.kill("lane boundary reached")
        }
    }
}
