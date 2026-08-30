package org.js.lolifamily.minecraftmcp.patch

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcpbridge.PatchBridge
import java.lang.instrument.Instrumentation
import java.util.concurrent.atomic.AtomicBoolean

/** Everything undoing an install takes, the way [Patch.Weave] is everything applying one takes. */
internal class Unweave(
    private val transformer: ResettableClassFileTransformer,
    private val inst: Instrumentation,
    /** Runtime name of the patched type. Resolved to live copies at removal rather than snapshotted at install
     *  — a pending install's class may have loaded since — and a name is what [retransform] batches on. */
    val className: String,
) {
    /** Deregister only; the advice stays woven until a [retransform] covering [className] runs. Split from the
     *  retransform because that is where the cost is — a safepoint and a class reload — and N patches need one
     *  of those, not N.
     *  @return false if it was not registered, i.e. an earlier reset already took it. */
    fun detach(): Boolean = transformer.reset(inst, AgentBuilder.RedefinitionStrategy.DISABLED)

    /** Whether any copy of [className] is loaded right now. Live rather than an install-time flag, for the
     *  same reason [retransform] re-resolves by name: a pending patch's class can arrive at any time. Read
     *  only to explain a report, which keeps the scan off every other path. */
    fun targetLoaded(): Boolean = inst.allLoadedClasses.any { it.name == className }

    companion object {
        /**
         * Drop the advice every [detach]ed transformer left on the types [names] denotes, in ONE pass: the
         * varargs call is a single safepoint however many classes it carries, so a batch costs what one class
         * does and the saving grows with the sweep.
         *
         * Live copies, not an install-time snapshot, so a pending patch whose class arrived later is covered.
         * Transformers still registered are re-applied by the same pass, which is what lets a partial sweep
         * leave the rest woven.
         */
        fun retransform(inst: Instrumentation, names: Set<String>) {
            if (names.isEmpty()) return
            // Unmodifiable copies are skipped, not passed along: `retransformClasses` refuses the WHOLE batch
            // if one member is unmodifiable, which would stop every other patch in the sweep from coming off.
            // Skipping loses nothing — a class the JVM will not retransform was never woven either.
            val live = inst.allLoadedClasses.filter { it.name in names && inst.isModifiableClass(it) }
            if (live.isNotEmpty()) Retransform.of(inst, live.toTypedArray())
        }
    }
}

/** One woven method: the label [PatchHandle.targets] shows, and the runtime parameter types it was rendered
 *  from — kept structured so [Patches.removeEnter] can filter through [Signatures.matches], the comparison
 *  that picked the target, instead of reading the label back apart. */
internal class Woven(val params: List<String>, val label: String) {
    override fun toString(): String = label
}

/**
 * One live patch: its id, what it wove, and how it has behaved since. Returned by [Patches.onEnter] /
 * [Patches.onExit] and read back later by [Patches.handle].
 *
 * A live view, not a snapshot — [targets] fills itself in when a [pending] patch's class finally loads, so
 * re-reading is the point rather than a caveat. Removal is not on it: [Patches.remove] is the way out, so
 * the registry that lists a patch is also what unweaves it — and [detached] is what one that threw halfway
 * leaves behind.
 */
// One install's whole result; a parameter object would just be this class again.
@Suppress("LongParameterList")
class PatchHandle internal constructor(
    /** The id [Patches] registers this patch as, and the label its fires carry — see [Patch.SEQ]. */
    internal val key: String,
    /** The bridge slot the woven advice dispatches through; [key]'s suffix as an int, from the same mint. */
    private val slot: Int,
    private val handler: Patch.CountingHandler,
    private val unweave: Unweave,
    /** Live view, keyed by defining loader — one entry per class copy, filled in if the target class loads
     *  later and rewritten on every retransform of that copy. */
    private val woven: Map<Any, List<Woven>>,
    /** Set the first time the target type is transformed, i.e. once the answer is actually known. */
    private val seen: AtomicBoolean,
    /** Stays registered after install, so a pending patch that fails when its class arrives lands here too. */
    private val listener: Patch.WeaveListener,
) {

    /** How far removal has got. DETACHED — transformer deregistered, advice still woven — is what a sweep
     *  whose retransform threw leaves behind, and what lets the next one resume instead of starting over. */
    private enum class Stage { LIVE, DETACHED, REMOVED }

    @Volatile
    private var stage = Stage.LIVE

    /** What [Patches.handle] and [Patches.remove] take: the install call echoed back in the caller's own
     *  spelling, not the methods it resolved to — those are [targets]. Minted per install, so an id kept
     *  across a re-install reads as gone instead of naming the replacement. */
    val id: String get() = key

    /** How many times the advice ran. */
    val fires: Long get() = handler.count.get()

    /** Of those, how many ended in the callback throwing. A swallowed throw leaves the patched method intact
     *  and the fire counted, so a wholly broken handler reads exactly like a working one until this is read —
     *  it is what separates "never fired" from "fired and blew up every time". */
    val failures: Long get() = handler.failures.get()

    /** The last one, whole; null if the callback never threw. Its `toString` is the single line [toString]
     *  shows — the frames are here for whoever wants them. */
    val lastError: Throwable? get() = handler.lastError

    /** What ByteBuddy threw while weaving, or null if the advice went in. Non-null means [targets] names what
     *  [Patch.weave] picked before the failure, not methods the advice actually reached. */
    val weaveError: Throwable? get() = listener.error

    /**
     * The methods actually woven, each as `signature @loader`, or null while the target class has not been
     * transformed yet (it isn't loaded — the patch is installed and waiting).
     *
     * Non-null but EMPTY is its own answer: the class loaded and nothing was woven — either the matcher hit
     * no weavable method on it, or a signature-filtered patch resolved to several and was refused, which
     * [Patch] logs at ERROR. It is deliberately not conflated with "not known yet".
     *
     * Abstract and native methods are excluded upstream rather than counted here: they have no bytecode, so
     * reporting one as woven would describe a patch that can never fire.
     */
    val targets: List<String>? get() = if (seen.get()) flat().map { it.label } else null

    /** True while no weave has been confirmed. Usually that means the target class has not loaded — but not
     *  always, which is why [toString] checks before it says so. Left as the mechanical fact: making it ask
     *  would put a walk of every loaded class behind a property read. */
    val pending: Boolean get() = !seen.get()

    /** A removal that got as far as deregistering and then threw: still listed, still woven, still firing.
     *  Nothing else here says so — [fires] keeps climbing and [targets] still names the methods — and the
     *  fix is to repeat the [Patches.remove] that threw. */
    val detached: Boolean get() = stage == Stage.DETACHED

    /** [targets] in the form a signature filter takes, null under the same "not known yet" condition. */
    internal fun wovenParams(): List<List<String>>? = if (seen.get()) flat().map { it.params } else null

    /** Every copy's entry in one list, sorted: the map orders by nothing a caller should see. */
    private fun flat(): List<Woven> = woven.values.flatten().sortedBy { it.label }

    /** The type this patch was installed against, as [Unweave.retransform] batches on it. */
    internal val targetName: String get() = unweave.className

    /** Phase 1 of removal: deregister, leaving the advice woven. Pair with [finish], one
     *  [Unweave.retransform] over every detached patch's class in between — that is what makes a sweep of N
     *  patches cost one retransform instead of N.
     *
     *  Idempotent, and cannot fail: `reset` answers "was it registered", not "did it work", and a transformer
     *  someone else already took is just as deregistered — that retransform drops its advice either way. */
    @Synchronized
    internal fun detach() {
        if (stage != Stage.LIVE) return
        if (!unweave.detach()) Constants.LOG.warn("[patch] {} was already deregistered — resuming removal", key)
        stage = Stage.DETACHED
    }

    /** Phase 3: the advice is gone, so the bridge entry can go too. Unregistering any earlier would leave the
     *  still-woven advice boxing its arguments for a lookup that can never hit. */
    @Synchronized
    internal fun finish() {
        if (stage == Stage.REMOVED) return
        stage = Stage.REMOVED
        PatchBridge.unregister(slot)
        Constants.LOG.info("[patch] removed {} (fired {}x)", key, fires)
    }

    override fun toString(): String {
        // Local only because [targets] has a custom getter, which the compiler won't smart-cast in place.
        // [failures] needs none: it only ever climbs, so reading it twice cannot contradict itself.
        val labels = targets
        val failed = weaveError
        return buildString {
            append(
                when {
                    // First: `targets` is non-null in this case too, so any test on it would report a weave
                    // that never happened.
                    failed != null -> "$key: WEAVE FAILED, nothing patched — $failed"
                    // Only asked once the answer is "nothing woven yet", and only to keep the line below from
                    // naming a cause it never checked.
                    labels == null && unweave.targetLoaded() ->
                        "$key: not woven — the target type is loaded, so this will not resolve later"
                    labels == null -> "$key: pending (target class not loaded)"
                    labels.isEmpty() -> "$key: nothing woven"
                    else -> "$key: woven ${labels.size} — ${labels.joinToString(", ")}"
                },
            )
            // `|`, not another comma: the woven list above is already comma-joined.
            if (failures > 0) append(" | $failures handler failure(s), last: $lastError")
            // DETACHED reads exactly like LIVE otherwise — still listed, still firing — so it has to say so.
            when (stage) {
                Stage.LIVE -> {}
                Stage.DETACHED -> append(" | detached — advice still woven, retry Patches.remove")
                Stage.REMOVED -> append(" | removed")
            }
        }
    }
}
