package org.js.lolifamily.minecraftmcp.patch

import org.js.lolifamily.minecraftmcp.Constants
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-eval patch registry, the lifecycle layer over [Patch]. An `execute_code` snippet is stateless per
 * call, so live patches are kept in a game-loader static map — a later eval can [handle], [remove] or
 * [removeAll] them.
 *
 * The id is minted per INSTALL and comes back on the [PatchHandle] — it is also the key a handler is passed,
 * so a handler can name its own patch. Re-installing a target mints a new one, which is what makes an id kept
 * across a re-install answer null / -1 rather than report the replacement under the old name. [handles] lists
 * what is live if one was not kept.
 *
 * Only user patches live here — the mod's own hooks are Mixins, so [removeAll] is safe.
 *
 * A handler outlives its eval, and so does the scriptguard woven into it — but that guard answers only to that
 * eval's id, which is never raised again once the eval ends. Past that point nothing stops a handler that loops:
 * cheap and non-blocking is the contract, and it is unenforced.
 *
 * Install from the parallel lane: the retransform's safepoint stops the game either way, but on a tick lane it
 * also spends that tick's budget, so the eval reports a timeout over a patch that went in fine. A suggestion,
 * not a rule: a script that reads tick-affine state to decide what to weave has nowhere else to go.
 *
 * ## Selecting an overload
 *
 * `params` names the parameter types of the ONE overload to patch, or is omitted to patch every overload of
 * the name. Each entry may be written as `*` (any type in that slot), a bare simple name (`ItemStack`,
 * `Properties`), the source spelling of a nested class (`Item.Properties`), or the full mojmap FQN — all in
 * mojmap regardless of the runtime's actual namespace. They are JVM type names, matched case-sensitively —
 * `int`, not Kotlin's `Int` — and array suffixes are kept (`ItemStack[]`).
 *
 * When `params` is given, exactly one SIGNATURE must match or the call throws with the real candidates
 * listed — so `listOf("*", "*")` is a usable probe: it either resolves the only 2-argument overload, or
 * fails with the list to pick from. Copies of one signature carried by several classloaders are not an
 * ambiguity; all of them are woven.
 *
 * Class, method and parameter names are all mojmap, translated forward through the loaded mapping table.
 * Where no table is loaded the translation is the identity — so on a non-mojmap runtime whose remap
 * provisioning failed, the runtime's own names resolve, weave, and come back in the candidate lists; they
 * are how you patch there.
 *
 * ## Coexisting
 *
 * Two patches share a method only while neither can change what it does. Observers stack freely; a writable
 * one ([intercept], [modify]) evicts everything else there, because a cancelled body makes the rest fire or
 * not depending on weave order. Give BOTH sides a `tag` to override that — it says "I know there are several,
 * and I accept that they run in install order".
 *
 * ## Removing
 *
 * [remove] takes an id. [removeEnter] / [removeExit] / [removeIntercept] / [removeModify] take what the
 * matching install took — the install line with its callback dropped — and mirror it one for one: an install
 * names a phase, so an uninstall does too.
 *
 * `params` there is the filter above, run against what a patch WOVE, so the two calls need not spell a
 * signature alike: `listOf("Level", "int")` and `listOf("*", "*")` remove each other's patches. A patch whose
 * class has yet to load wove nothing, so its own filter answers instead — matching there is by spelling, and
 * only `*` bridges the two sides.
 */
// A facade's function count is API surface, not complexity.
@Suppress("TooManyFunctions")
object Patches {
    /** The install call a patch answers to, and — being a data class — "the same install call" is its own
     *  equality. A hand-written comparison goes stale the first time a field is added here. */
    private data class Target(
        val className: String,
        val methodName: String,
        val params: List<String>?,
        val phase: Patch.Phase,
        val tag: String?,
    ) {

        /** Weave, under an id [Patch] mints. */
        fun install(make: () -> PatchHandle): PatchHandle {
            // Must precede any reference to Patch — see Instrumentations for why. A self-attach failure throws
            // here as a clean script error; the mod keeps running.
            Instrumentations.ensure()
            // The old advice must really be gone before a second transformer goes on the same method, or it
            // stays woven with no handle left. [sweep] throws when it could not be, which stops the install.
            // UNCONDITIONAL, and it commits here: a [make] that throws leaves the target evicted and nothing
            // installed. Deferring it to keep the old patch on failure would make one call mean two states.
            val evicted = sweep { evicts(it) }
            if (evicted.isNotEmpty()) Constants.LOG.info("[patch] installing {} evicted {}", this, evicted)
            val h = make()
            ACTIVE[h.key] = Live(h, this)
            return h
        }

        /** Whether installing this must first take [live] off: it is the same install call again (idempotence,
         *  strict equality — `listOf("*")` must not evict what `listOf("int")` installed) or a neighbor whose
         *  behavior would depend on weave order (correctness, signature overlap is enough — a miss there is
         *  silent). Two explicit [tag]s exempt both; a null tag is "didn't think about it", not consent. */
        private fun evicts(live: Live): Boolean {
            val old = live.target
            if (old.className != className || old.methodName != methodName) return false
            if (tag != null && old.tag != null && old.tag != tag) return false
            return old == this || (phase.conflictsWith(old.phase) && live.mayOverlap(params))
        }
    }

    /** A live patch and the install call behind it. */
    private class Live(val handle: PatchHandle, val target: Target) {

        /** Null [want] / [ph] is unfiltered. Woven signatures are the truth — [Signatures.matches] is what
         *  selected them — and a patch that wove several overloads answers to any one of them, being atomic.
         *  A patch that wove NONE — pending, or resolved to an ambiguous match [Patch] refused — has no
         *  signature to answer with, so its own filter stands in on the left, which is why `*` matches there
         *  in both directions. Empty and null are the same case here: both mean "nothing woven to compare". */
        fun matches(cls: String, method: String, want: List<String>?, ph: Patch.Phase?): Boolean {
            if (target.className != cls || target.methodName != method) return false
            if (ph != null && target.phase != ph) return false
            if (want == null) return true
            handle.wovenParams()?.takeIf { it.isNotEmpty() }?.let { woven -> return woven.any { Signatures.matches(it, want) } }
            return target.params == null || Signatures.matches(target.params, want)
        }

        /** Could an install of [want] weave the method this patch wove? Exact where something WAS woven —
         *  [Signatures.matches] is what selected it. Where nothing was it cannot answer, and [Target.evicts]
         *  wants "yes" in doubt; evicting a patch that wove nothing is free anyway. */
        fun mayOverlap(want: List<String>?): Boolean {
            if (want == null || target.params == null) return true
            val woven = handle.wovenParams()?.takeIf { it.isNotEmpty() } ?: return true
            return woven.any { Signatures.matches(it, want) }
        }
    }

    private val ACTIVE = ConcurrentHashMap<String, Live>()

    /** The one answer carrying no value, so it needs no allocation. */
    private val PROCEED = PatchDecision(false, null)

    /**
     * Patch method entry. Re-installing the same target — same class, method, `params` and [tag] — removes
     * what held it first, so re-running a snippet doesn't stack transformers on the method. [tag] is how two
     * patches coexist on one method instead.
     */
    @Synchronized
    fun onEnter(
        className: String,
        methodName: String,
        params: List<String>? = null,
        tag: String? = null,
        cb: PatchEnterCallback,
    ): PatchHandle = Target(className, methodName, params, Patch.Phase.ENTER, tag)
        .install { Patch.onEnter(className, methodName, params, tag, cb) }

    /** As [onEnter], woven at every exit — a normal return and an exception leaving the method alike. */
    @Synchronized
    fun onExit(
        className: String,
        methodName: String,
        params: List<String>? = null,
        tag: String? = null,
        cb: PatchExitCallback,
    ): PatchHandle = Target(className, methodName, params, Patch.Phase.EXIT, tag)
        .install { Patch.onExit(className, methodName, params, tag, cb) }

    /**
     * As [onEnter], but the callback decides: it may rewrite `args`, or skip the body and supply the return
     * value. Pass [onReturn] to also observe what the body produced — read-only, and silent on a skipped call.
     *
     * A method carries at most one writable patch, and installing one evicts observers on it too: a skipped
     * body makes their firing depend on weave order. Give both sides a [tag] to override that.
     */
    // A parameter object would hide the count, not lower it.
    @Suppress("LongParameterList")
    @Synchronized
    fun intercept(
        className: String,
        methodName: String,
        params: List<String>? = null,
        tag: String? = null,
        onReturn: PatchExitCallback? = null,
        cb: PatchInterceptCallback,
    ): PatchHandle {
        // Two blueprints rather than one with a dead branch: without [onReturn] the woven exit is three local
        // instructions, which is what keeps "skip it to measure the cost" honest.
        val phase = if (onReturn == null) Patch.Phase.INTERCEPT else Patch.Phase.AROUND
        return Target(className, methodName, params, phase, tag).install {
            if (onReturn == null) {
                Patch.intercept(className, methodName, params, tag, cb)
            } else {
                Patch.around(className, methodName, params, tag, cb, onReturn)
            }
        }
    }

    /** As [onExit], but the callback may replace the return value. It cannot stop the body, so side effects
     *  have already happened. Constructors are out of reach for every phase, this one included: resolution
     *  reads `getDeclaredMethods`, which does not list them. */
    @Synchronized
    fun modify(
        className: String,
        methodName: String,
        params: List<String>? = null,
        tag: String? = null,
        cb: PatchModifyCallback,
    ): PatchHandle = Target(className, methodName, params, Patch.Phase.MODIFY, tag)
        .install { Patch.modify(className, methodName, params, tag, cb) }

    /** Run the body / keep the return value: the answer that changes nothing. */
    fun proceed(): PatchDecision = PROCEED

    /** Skip the body and return [value], or replace the return value with it. A value the patched method
     *  cannot return is refused and counted as a handler failure, leaving the method untouched. */
    fun returns(value: Any?): PatchDecision = PatchDecision(true, value)

    /** The live patch under [id], or null if none holds it — including one replaced by a re-install, which
     *  mints its own id. Worth re-reading for a patch that was [PatchHandle.pending] at install: its targets
     *  fill in once the class loads, as do [PatchHandle.fires] and [PatchHandle.failures]. */
    fun handle(id: String): PatchHandle? = ACTIVE[id]?.handle

    /**
     * Remove the patches named, restoring the original bytecode. Variadic so a partial removal batches the way
     * [removeAll] does: the whole set costs one retransform, not one each.
     *
     * @return the ids removed, sorted — [removeEnter]'s shape, and the only one that can say WHICH of several
     *         ids was live. An id nobody holds is simply absent.
     * @throws IllegalStateException if a named patch could not be unwoven — it stays listed for a retry.
     */
    @Synchronized
    fun remove(vararg ids: String): List<String> = ids.toSet().let { want -> sweep { it.handle.key in want } }

    /**
     * [onEnter] undone by copying its own first line, whatever id the patch holds. [params] narrows to one
     * overload selection; omit it to take every one.
     *
     * @return the ids removed, sorted. Ids and not a count: the caller named a method rather than a patch, so
     *         a number cannot say whether it hit the intended one.
     * @throws IllegalStateException if a matched patch could not be unwoven — see [sweep].
     */
    @Synchronized
    fun removeEnter(className: String, methodName: String, params: List<String>? = null): List<String> =
        sweep { it.matches(className, methodName, params, Patch.Phase.ENTER) }

    /** [onExit] undone the same way; [removeEnter]'s contract, other phase. */
    @Synchronized
    fun removeExit(className: String, methodName: String, params: List<String>? = null): List<String> =
        sweep { it.matches(className, methodName, params, Patch.Phase.EXIT) }

    /** [intercept] undone the same way. Both blueprints, since which one was woven depended on an `onReturn`
     *  an uninstall has no reason to repeat. */
    @Synchronized
    fun removeIntercept(className: String, methodName: String, params: List<String>? = null): List<String> = sweep {
        it.matches(className, methodName, params, Patch.Phase.INTERCEPT) ||
            it.matches(className, methodName, params, Patch.Phase.AROUND)
    }

    /** [modify] undone the same way; [removeEnter]'s contract, other phase. */
    @Synchronized
    fun removeModify(className: String, methodName: String, params: List<String>? = null): List<String> =
        sweep { it.matches(className, methodName, params, Patch.Phase.MODIFY) }

    /** Remove every user patch; returns how many came off. Throws if any would not — see [sweep]. */
    @Synchronized
    fun removeAll(): Int {
        val n = sweep { true }.size
        Constants.LOG.info("[patch] removeAll removed {} user patch(es)", n)
        return n
    }

    /**
     * Unweave every patch [pick] selects. An entry leaves the map only once its advice is really gone, so a
     * sweep that threw leaves every patch it picked listed, woven and firing — and retryable.
     *
     * Three phases rather than N removals, because the retransform is where the cost is and it batches:
     * deregister everything first (free), drop all their advice in ONE pass, then let the bridge entries go.
     * Removing serially spends a safepoint per patch; this spends one for the whole sweep.
     *
     * Phase 2 owns the whole failure, being the only phase that can fail — 1 is a field write, 3 is two map
     * removals. Stopping there is what keeps woven advice and its registered handler paired, the invariant
     * [PatchHandle.finish] exists to protect.
     *
     * @return the ids removed, sorted.
     * @throws IllegalStateException if the retransform threw. Nothing came off; repeat the call to resume.
     */
    private fun sweep(pick: (Live) -> Boolean): List<String> {
        // Ahead of everything: ensure() is an argument below and it self-attaches an agent, which an empty
        // sweep must not pay for.
        val picked = ACTIVE.entries.filter { pick(it.value) }
        if (picked.isEmpty()) return emptyList()
        // 1. Deregister. Idempotent, so a pick an earlier sweep left detached costs nothing here.
        picked.forEach { it.value.handle.detach() }
        // 2. One retransform for the batch. Patches sharing a class collapse into one entry; the varargs call
        //    is a single safepoint either way, so this is flat in both the patch count and the class count.
        try {
            Unweave.retransform(Instrumentations.ensure(), picked.mapTo(HashSet()) { it.value.handle.targetName })
        } catch (t: Throwable) {
            // Re-runnable however far the JVM got: a class already clean retransforms to the same bytes, its
            // transformer being gone. The cause rides along — it IS the diagnosis, and nothing else carries it.
            throw IllegalStateException(
                "retransform failed; ${picked.map { it.key }.sorted()} stay woven and keep firing — " +
                    "repeat the call to resume, or Patches.handles() to inspect",
                t,
            )
        }
        // 3. The advice is gone, so the bridge entries go and the ids leave the map.
        val removed = ArrayList<String>(picked.size)
        for ((id, live) in picked) {
            live.handle.finish()
            ACTIVE.remove(id)
            removed += id
        }
        removed.sort()
        return removed
    }

    /** Every live patch, sorted like [sweep]'s return. A handle carries its own [PatchHandle.id], so a map
     *  keyed by it would store the name twice — [handle] is the lookup by one, this is the walk over all. */
    fun handles(): List<PatchHandle> = ACTIVE.values.map { it.handle }.sortedBy { it.id }
}
