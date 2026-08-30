package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.patch.Patches
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Which per-thread kill-id field an instrumented script reads. The server lane runs on the Server thread; the
 * client and render lanes BOTH run on the Render thread and share one field (they never run concurrently). Each
 * case names the static field the inlined `GETSTATIC` reads. A future lane needing its own thread costs one case
 * here, one field, and one branch in [TimeoutGuard.setKillId] — the rest of the guard state is per-ordinal.
 */
enum class GuardLane(val killIdField: String) {
    SERVER("serverKillId"),
    RENDER("renderKillId"),
}

/**
 * Inlined per-tick time guard. `ScriptWeave.instrument` inlines `if (killId == myEvalId) throw timeout`
 * (GETSTATIC <killIdField> + LDC <evalId> + IF_ICMPNE + GETSTATIC timeout + ATHROW) at every method entry, loop
 * back-edge and (non-self-ref) catch handler entry of a script's own bytecode. Being inlined it pushes NO new
 * frame, so it runs even at the stack-full edge — inside the catch handler's existing frame — where an
 * `INVOKESTATIC` guard would itself StackOverflowError. One mechanism handles BOTH dead loops (back-edges) and
 * dead recursion (the checks fire as SOE unwinds through catch handlers).
 *
 * <p>The budget bounds the INTERVAL BETWEEN HEARTBEATS, not the wall time one eval spends on the lane thread —
 * the two come apart where vanilla re-enters the pump ([endFrame]). A lane whose heartbeat keeps firing is a
 * game that keeps running, whatever a script is doing inside it; frozen means the loop stopped advancing —
 * what `ServerWatchdog` kills a dedicated server over — and nothing short of that counts.
 *
 * <p>One kill-id field per `GuardLane` — a GETSTATIC-cheap field can't be thread-local, so instead of one shared
 * field that the server and render threads would trample, there is one per lane-thread. Which one a script reads
 * is decided at instrument time from the target lane, so compilation bakes in the right GETSTATIC with no runtime
 * thread sniffing. Those two fields are the only per-lane STATE here; everything else lives in a `GuardState`
 * indexed by the lane's `ordinal`, so this file has exactly one lane branch ([setKillId]).
 *
 * <p>An ID rather than a flag, because instrumented code OUTLIVES its eval: a [Patches] handler and a
 * script-spawned thread both keep running after the eval that compiled them is gone, and a per-lane flag would
 * kill them whenever any LATER eval on that lane went over budget. The id is baked into the bytes at instrument
 * time, so it travels with the code and can only ever match the eval it came from. What remains is code
 * outliving an eval that WAS killed reading its own id — its own eval's business, and bounded by [exitStep].
 * Past that clear the id is never raised again, so such code runs unguarded for good — see [Patches].
 */
object TimeoutGuard {
    private const val DEFAULT_BUDGET_MS = 1000L

    // Non-positive would arm the watchdog to fire at once, killing every eval on its first inlined guard
    // check. Fall back rather than clamp to 1ms: `0` reads as "turn the limit off", not as its opposite.
    private val BUDGET_MS: Long = Props.long("mcp.eval.step.budget.ms", DEFAULT_BUDGET_MS).let { v ->
        if (v > 0) {
            v
        } else {
            Constants.LOG.warn("[mcp] mcp.eval.step.budget.ms={} must be > 0 — using {}", v, DEFAULT_BUDGET_MS)
            DEFAULT_BUDGET_MS
        }
    }

    private val SCHEDULER = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "mcp-eval-watchdog").apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }
    }.apply { removeOnCancelPolicy = true }

    // The ABI pair, read by the inlined GETSTATIC (each named by a GuardLane.killIdField): the id of the eval to
    // kill, or 0 for none. An opaque token — only ever compared for equality, never ordered.
    @JvmField @Volatile
    var serverKillId = 0

    @JvmField @Volatile
    var renderKillId = 0

    @JvmField
    val timeout = ScriptTimeoutError(BUDGET_MS) // cached; inlined ATHROW (no NEW, no frame)

    /** The one lane branch left: a kill id has to be a STATIC field for the woven GETSTATIC to reach it without a
     *  frame, so it cannot move into `GuardState` with everything else. */
    private fun setKillId(lane: GuardLane, v: Int) {
        when (lane) {
            GuardLane.SERVER -> serverKillId = v
            GuardLane.RENDER -> renderKillId = v
        }
    }

    /**
     * Where the watchdog found a lane thread standing when it spent that lane's budget. [ScriptTimeoutError] is an
     * immutable process-wide singleton carrying no stack of its own, so this is the side channel a timeout report
     * reads instead — and it is the better answer anyway: the offending code, not wherever the inlined guard next
     * happened to fire. One immutable pair behind one volatile, so [caughtHere] reads thread and trace coherently.
     * A materialized trace holds only strings, so retaining it pins no snippet classloader.
     */
    private class Caught(val thread: Thread, val trace: Array<StackTraceElement>)

    /** One lane-thread's watchdog state — everything except that lane's ABI kill-id field. */
    private class GuardState {
        /** Frame generation, bumped on every arm and disarm. Lane thread is the sole writer, so `++` needs no
         *  atomic. Only ever compared for equality, so wrap is a non-event. */
        @Volatile var gen = 0

        /** The generation whose budget the watchdog spent, or -1 (never a live generation). Compared for EQUALITY
         *  against [gen], so a write from a body armed for an older frame carries that older number and reads as
         *  "not tripped" all by itself — which is what makes a watchdog racing a concurrent [disarm] a non-event. */
        @Volatile var deadGen = -1

        /** Which eval is on this lane's stack right now, or 0. Changed only under this state's monitor, which is
         *  what pairs a step boundary with [arm]'s kill write; volatile for the one read taken outside it. */
        @Volatile var stepEval = 0

        @Volatile var caught: Caught? = null

        /** Lane thread only, like [depth] — never read off-lane, so plain fields. */
        var timer: ScheduledFuture<*>? = null

        /** Frame nesting, so [endFrame] can tell an inner frame from the outermost one. */
        var depth = 0
    }

    /** Indexed by the [GuardLane] `ordinal`. Fixed length, elements never replaced. */
    private val STATES = Array(GuardLane.entries.size) { GuardState() }

    private fun st(lane: GuardLane): GuardState = STATES[lane.ordinal]

    /** Whether [lane]'s budget is already spent. Read by the pump before it steps the next eval, so an eval that
     *  never got its turn can be ended with that reason instead of the offender's. The two reads are coherent
     *  without a lock: `gen` is written only by the lane thread, which is also this method's only caller. */
    fun tripped(lane: GuardLane): Boolean = st(lane).let { it.deadGen == it.gen }

    /**
     * The stack caught on THIS thread, or null. Matching on the caller's own thread is what keeps a report from
     * picking up the other lane's stack, and makes an off-lane render (a deferred [Outcome] text built on the HTTP
     * thread) omit the block rather than attach a wrong one.
     */
    fun caughtHere(): Array<StackTraceElement>? {
        val me = Thread.currentThread()
        for (s in STATES) s.caught?.let { if (it.thread === me) return it.trace }
        return null
    }

    /** Lane pump, start of a frame: arm this lane's budget. Frames nest — see [endFrame]. */
    fun beginFrame(lane: GuardLane) {
        st(lane).depth++
        arm(lane)
    }

    /**
     * Lane pump, end of a frame. Disarms only when the OUTERMOST frame ends; an inner frame re-arms a FULL budget
     * for the one it returns to. Vanilla re-enters the pump — `Minecraft#disconnect` and `#doWorldLoad` spin `runTick(false)`
     * while a step is on the stack — and disarming there would leave that step running unguarded for the rest of
     * its turn. Pair with [beginFrame] in a `try`/`finally`, or the depth leaks and the budget is never disarmed.
     */
    fun endFrame(lane: GuardLane) {
        if (--st(lane).depth > 0) arm(lane) else disarm(lane)
    }

    /** Lane pump that runs no frame (empty queue): reset the guard, so nothing a torn-down frame left behind
     *  stands until the next eval is submitted — an idle lane has no frame of its own to clear it. No-op inside a
     *  frame, for [endFrame]'s reason. */
    fun idle(lane: GuardLane) {
        if (st(lane).depth == 0) disarm(lane)
    }

    /** Lane pump, around one eval's step: publish [evalId] as who is on this lane's stack, so the watchdog knows
     *  who to name. Returns the id it displaced — hand that back to [exitStep]. Save/restore rather than clear,
     *  because steps NEST for [endFrame]'s reason, and the restore is what carries the enclosing step across a
     *  nested frame.
     *
     *  Under [GuardState]'s monitor with [arm]'s kill write, so a step registering after the budget was already
     *  spent arms itself and is over the limit from its first instruction. */
    fun enterStep(lane: GuardLane, evalId: Int): Int {
        val s = st(lane)
        return synchronized(s) {
            val prev = s.stepEval
            s.stepEval = evalId
            if (s.deadGen == s.gen) setKillId(lane, evalId)
            prev
        }
    }

    /** Pair with [enterStep] in a `finally`, under the same monitor. Restoring to 0 means the OUTERMOST step
     *  ended, and that is where the kill id is cleared: left set, that eval's escaped code would keep reading its
     *  own id and throw on every fire from here on. */
    fun exitStep(lane: GuardLane, prevEvalId: Int) {
        val s = st(lane)
        synchronized(s) {
            s.stepEval = prevEvalId
            if (prevEvalId == 0) setKillId(lane, 0)
        }
    }

    /**
     * Arm runs ON the lane thread (from [beginFrame]/[endFrame]), which is what lets it hand the watchdog the
     * thread to photograph. [disarm] deliberately does NOT clear the capture — a report may render after the
     * frame ends, and a stale one can only ever attach to a real later timeout, which arms (and so clears) first.
     *
     * `cancel(false)` cannot stop a body that already started, so the generation is what retires one instead. The
     * body reads its victim FIRST and checks the generation SECOND: one that still matches proves the victim just
     * read was published by THIS frame. Swap the two and a step from a later frame can be killed.
     *
     * That read and the kill write hold [GuardState]'s monitor, which [enterStep] takes too: the id armed is the
     * one on the lane's stack right then, and a step that registers afterwards arms itself. The stack walk stays
     * OUTSIDE — it is the slow half, needing a safepoint that can outlast the step it caught, so a shot the
     * victim no longer matches is dropped rather than reported against them.
     */
    private fun arm(lane: GuardLane) {
        val s = st(lane)
        val laneThread = Thread.currentThread()
        val gen = ++s.gen   // before the clears below, so a body still in flight loses the race to write
        s.timer?.cancel(false)
        s.caught = null
        setKillId(lane, 0)
        s.timer = SCHEDULER.schedule({
            if (gen != s.gen) return@schedule   // retired while queued — nothing here is worth doing
            val subject = s.stepEval            // who the walk below is about to catch
            val shot = Caught(laneThread, laneThread.stackTrace)
            synchronized(s) {
                val victim = s.stepEval
                if (gen != s.gen) return@schedule
                // Dropped when the step moved on under the walk: [caughtHere] then omits the frame block, which
                // is the shape an off-lane render already produces.
                s.caught = if (victim == subject) shot else null
                // Kill id BEFORE deadGen: that volatile write is the release paired with [tripped]'s acquire, so a
                // pump that sees the trip is guaranteed to see the id the woven guard throws on.
                setKillId(lane, victim)
                s.deadGen = gen
            }
        }, BUDGET_MS, TimeUnit.MILLISECONDS)
    }

    private fun disarm(lane: GuardLane) {
        val s = st(lane)
        s.gen++   // retires deadGen, and any body still running
        s.timer?.cancel(false)
        s.timer = null
        setKillId(lane, 0)
    }
}
