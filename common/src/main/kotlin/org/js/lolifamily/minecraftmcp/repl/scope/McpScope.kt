package org.js.lolifamily.minecraftmcp.repl.scope

import org.js.lolifamily.minecraftmcp.exec.Capture
import org.js.lolifamily.minecraftmcp.repl.ValueRender
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.reflect.typeOf

/**
 * Receiver of a cross-tick `iterator { ... }` snippet — scope and iterator in one object, as the stdlib's
 * `SequenceBuilderIterator` is. Replaces the stdlib builder for two reasons:
 *
 * - **[yield] renders what it is handed.** The lane drives the iterator to exhaustion and discards every element,
 *   so under the stdlib builder `iterator { ...; yield(answer) }` reported `"(no output)"` — the one snippet shape
 *   that could not answer, silently. Rendering must happen at the yield, not at the driver: by then the value is a
 *   bare `Object`, and a runtime type is no substitute (`listOf(1, 2)` reads as `java.util.Arrays$ArrayList`). Only
 *   a `reified` parameter, resolved per call site, keeps `kotlin.collections.List<kotlin.Int>` — and a reified
 *   `yield` must be `inline`, which rules out overriding the stdlib's `abstract` one.
 * - **[RestrictsSuspension] carries its weight.** It is what makes [yield] the only suspension point in the body,
 *   so a step always resumes on the thread that called [hasNext]. Without it a snippet can
 *   `kotlinx.coroutines.delay(..)` — on the game loader here — and wake on a foreign thread while the lane still
 *   believes it owns the tick. The annotation is public, so we keep that guard without extending `SequenceScope`.
 *
 * No `yieldAll`: its only remaining meaning would be "yield in bulk, silently", which is the opposite of the point.
 * `for (v in xs) yield(v)` is one line and reports each element.
 */
@RestrictsSuspension
class McpScope<T> internal constructor(
    /** Never exposed, not even as `@PublishedApi`: handing a snippet the [Capture] hands it `take()`, which seals
     *  the sink and drops the rest of the eval's output. [emitValue] is the write-only door [yield] needs. */
    private val sink: Capture,
) : Iterator<T>,
    Continuation<Unit> {

    /** Five states: the stdlib's six, less the two that go with `yieldAll`'s delegate-pull, plus the split of its
     *  single `Failed` — see [Resuming]. */
    private enum class Step {
        /** Buffer empty: run the body forward to its next [yield]. */
        NeedsValue,

        /** [pending] holds a value [next] has not taken. */
        HasValue,

        /** Body ran to completion. */
        Exhausted,

        /** Body threw. That throwable surfaced out of the [hasNext] that resumed it, so this only answers a
         *  re-entry — and is what lets [Resuming] mean what it says. */
        Failed,

        /**
         * Held only across `resume`, and overwritten before it returns — by [yieldRaw], by completion, or by
         * [Failed]. Seeing it afterwards means the body suspended on something that is not [yield] and will wake
         * on a foreign thread; [RestrictsSuspension] makes that unreachable, so this is an assertion. The stdlib
         * merges this case with [Failed] and reports both as `"Iterator has failed."`.
         */
        Resuming,
    }

    private var step = Step.NeedsValue
    private var pending: T? = null
    private var cont: Continuation<Unit>? = null

    /** Split from the constructor because `createCoroutineUnintercepted` needs the receiver instance — the same
     *  two-step the stdlib builder does. */
    internal fun begin(block: suspend McpScope<T>.() -> Unit) {
        cont = block.createCoroutineUnintercepted(receiver = this, completion = this)
    }

    override fun hasNext(): Boolean {
        while (true) {
            when (step) {
                Step.HasValue -> return true
                Step.Exhausted -> return false
                Step.Failed -> error("cross-tick body threw; this iterator is spent")
                Step.Resuming -> error(
                    "cross-tick body suspended on something that is not yield — it would resume off the tick thread",
                )
                Step.NeedsValue -> {
                    step = Step.Resuming
                    // Taken and cleared before the resume: the body parks its NEXT continuation here on the way to
                    // the following yield, and if it finishes instead, the one just spent must not stay pinned to a
                    // frame nobody will resume.
                    val c = checkNotNull(cont) { "NeedsValue with no continuation — begin() never ran" }
                    cont = null
                    c.resume(Unit)
                }
            }
        }
    }

    /** One precondition, not the stdlib's `next()` ⇄ `nextNotReady()` recursion: [hasNext] already answers every
     *  state, so asking it IS "is there a value". */
    override fun next(): T {
        if (step != Step.HasValue && !hasNext()) throw NoSuchElementException()
        step = Step.NeedsValue
        @Suppress("UNCHECKED_CAST")
        val value = pending as T
        pending = null
        return value
    }

    /** The suspension itself — everything [yield] does apart from rendering. `@PublishedApi` for the inline call. */
    @PublishedApi
    internal suspend fun yieldRaw(value: T) {
        pending = value
        step = Step.HasValue
        return suspendCoroutineUninterceptedOrReturn { c ->
            cont = c
            COROUTINE_SUSPENDED
        }
    }

    /** The same [ValueRender.line] a single-tick result goes through, so the two cannot report differently. */
    @PublishedApi
    internal fun emitValue(type: String, value: Any?) {
        sink.appendLine(ValueRender.line(type, value))
    }

    /**
     * Suspend until the next tick, reporting [value] as this step's result.
     *
     * `reified V` reads the type at THIS call site, so a body yielding an `Int`, then a `List<Int>`, then a
     * `BlockPos` reports each correctly — the scope's [T] would flatten all three to a common supertype and be
     * wrong about every one. `V : T` is the only place [T] is constrained; without it [T] cannot be inferred at
     * all. `Unit` prints nothing, so `yield(Unit)` stays the pure "resume me next tick" signal.
     *
     * [typeName] is filled in at compile time by YieldTypeExtension, which spells MC the way the snippet did.
     * The default is the same type read at runtime, where only the runtime namespace exists.
     */
    suspend inline fun <reified V : T> yield(value: V, typeName: String = typeOf<V>().toString()) {
        if (value !is Unit) emitValue(typeName, value)
        yieldRaw(value)
    }

    /** Rethrow so a throw surfaces out of the [hasNext] that resumed it — the lane's guard turns it into partial
     *  output plus a stack. State FIRST: `getOrThrow` does not return on a failure, so setting it after would
     *  strand [Step.Resuming] and misreport the next entry as a suspension that never happened. */
    override fun resumeWith(result: Result<Unit>) {
        step = if (result.isFailure) Step.Failed else Step.Exhausted
        result.getOrThrow()
    }

    override val context: CoroutineContext get() = EmptyCoroutineContext
}
