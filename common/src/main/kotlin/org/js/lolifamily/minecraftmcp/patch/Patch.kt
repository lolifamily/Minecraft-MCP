package org.js.lolifamily.minecraftmcp.patch

import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.scaffold.MethodGraph
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.utility.JavaModule
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.js.lolifamily.minecraftmcp.repl.NamespaceProbe
import org.js.lolifamily.minecraftmcpbridge.Handler
import org.js.lolifamily.minecraftmcpbridge.PatchBridge
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime method patching: weaves a ByteBuddy [Advice] into a method named at runtime. Used only by
 * `execute_code` scripts, via [Patches].
 *
 * [onEnter] inlines `PatchBridge.fireEnter(slot, key, this, args)` at the method's head; [onExit] inlines
 * `PatchBridge.fireExit(slot, key, this, args, returned, thrown)` at every exit, including the exceptional
 * one. The bridge indexes [SEQ]'s slot to reach the registered game-side callback.
 *
 * [intercept] and [modify] weave the writable blueprints instead — see [Phase] for what separates them, and
 * [PatchInterceptAdvice] / [PatchModifyAdvice] for the bytecode each produces.
 *
 * ## How a target is chosen
 *
 * [PatchTargets] does the picking; this is why it picks that way. When the class is already loaded — the
 * normal case — the target is resolved against the LIVE class: its
 * hierarchy supplies the owners, and the mojmap name the script wrote is translated FORWARD over them
 * ([Signatures.runtimeNamesOf]). Reflection seeing ground truth buys three things:
 *
 * - An override declared on a MOD class resolves. Its own name is in no mapping row, but climbing to the MC
 *   class it was inherited from reaches the row that does name it — which a single forward lookup on the
 *   script's class would miss, and which a reverse demap of the runtime name cannot do at all on an obf
 *   runtime, where one name serves methods jar-wide (see [Mappings.reverseMethod]).
 * - The weave matcher is built from the resolved method's exact `(name, descriptor)`, so there is no second
 *   signature-comparison implementation that could disagree with the one used to pick the target.
 * - "No such method" and "ambiguous" are found BEFORE anything is installed, so they throw as clean script
 *   errors listing the real candidates, instead of silently weaving nothing.
 *
 * When the class is NOT loaded yet the patch still installs and waits — mod classes load late — but nothing
 * can be verified at that point. See [PatchHandle.targets] for how that is reported.
 *
 * Bridge injection lives in [Instrumentations] — see its class doc for the load-order constraint.
 */
internal object Patch {

    /** Minted per INSTALL, and it is BOTH the key's suffix and the bridge's registry slot — one number, so the
     *  two can never come to name different patches. Freshness, not collision avoidance: an id kept across a
     *  re-install answers null / -1 instead of the replacement's state, and a Probe channel named after it
     *  starts empty. It equally strands advice [Patches.remove] could not unweave, whose slot stays burned and
     *  reads null. Starts at 1, which is what leaves 0 free for [warm]'s never-registered blueprints. */
    private val SEQ = AtomicInteger()

    /** Key for the bootstrap loader in [install]'s woven map: that loader is null, which ConcurrentHashMap refuses. */
    private val BOOTSTRAP = Any()

    /** The two points a patch is observable at. */
    enum class End { HEAD, RETURN }

    /** Which advice blueprint is woven. [at] is where the decision is made, [affects] which ends a write of
     *  it can be seen at; [conflictsWith] derives from those two and nothing else. Named `slug` and not
     *  `tag`, which on [Patches] means the caller's coexistence discriminator. */
    enum class Phase(val slug: String, val at: End, val affects: Set<End>) {
        ENTER("enter", End.HEAD, emptySet()),
        EXIT("exit", End.RETURN, emptySet()),
        INTERCEPT("intercept", End.HEAD, setOf(End.HEAD, End.RETURN)), // may skip the body, so both ends move
        AROUND("around", End.HEAD, setOf(End.HEAD, End.RETURN)), // INTERCEPT plus a read-only return observer
        MODIFY("modify", End.RETURN, setOf(End.RETURN)),
        ;

        /** Whether this phase can change what the method does. Those bind a return type as a constant, so
         *  they must resolve to exactly one method. */
        val writable: Boolean get() = affects.isNotEmpty()

        /** Whether two patches on one method would behave differently depending on weave order — which is
         *  install order, which a script cannot control across evals. Reflexive for [writable] phases, so a
         *  method carries at most one of each. */
        fun conflictsWith(other: Phase): Boolean = other.at in affects || at in other.affects
    }

    /**
     * Patch the method(s) named [methodName] on [className] to fire [cb] on entry. [params] selects a single
     * overload — see [Patches.onEnter] for its spelling — or null to weave every overload of that name.
     * Callers pass mojmap class/method names. Returns a handle exposing the fire count, the woven targets,
     * and the id [Patches.remove] takes to restore the original bytecode.
     */
    fun onEnter(className: String, methodName: String, params: List<String>?, tag: String?, cb: PatchEnterCallback): PatchHandle =
        install(className, methodName, params, tag, EnterHandler(cb))

    /** As [onEnter], but woven at every exit — normal return and thrown exception alike. */
    fun onExit(className: String, methodName: String, params: List<String>?, tag: String?, cb: PatchExitCallback): PatchHandle =
        install(className, methodName, params, tag, ExitHandler(cb))

    /** As [onEnter], but [cb] may rewrite the arguments or skip the body — see [PatchInterceptCallback]. */
    fun intercept(className: String, methodName: String, params: List<String>?, tag: String?, cb: PatchInterceptCallback): PatchHandle =
        install(className, methodName, params, tag, InterceptHandler(cb))

    /** [intercept] plus [onReturn], which observes what the body produced. It does not fire when [cb] skipped
     *  the body — the advice carrying it is woven INSIDE the one that skips, so the jump clears it too. */
    @Suppress("LongParameterList") // two callbacks on top of a target is what this phase IS
    fun around(
        className: String,
        methodName: String,
        params: List<String>?,
        tag: String?,
        cb: PatchInterceptCallback,
        onReturn: PatchExitCallback,
    ): PatchHandle = install(className, methodName, params, tag, AroundHandler(cb, onReturn))

    /** As [onExit], but [cb] may replace the return value — see [PatchModifyCallback]. */
    fun modify(className: String, methodName: String, params: List<String>?, tag: String?, cb: PatchModifyCallback): PatchHandle =
        install(className, methodName, params, tag, ModifyHandler(cb))

    // ---- install -----------------------------------------------------------------------------

    private fun install(className: String, methodName: String, params: List<String>?, tag: String?, gh: CountingHandler): PatchHandle {
        val i = Instrumentations.ensure()
        // Script names are mojmap strings (bytecode remap doesn't touch string constants). The CLASS name is
        // still translated forward, because it is what selects the type to transform; member names are not,
        // because reflection below reads them straight off the live class.
        val cls = if (NamespaceProbe.needsRemap()) Mappings.current()?.mapClass(className) ?: className else className

        // Every loaded copy, across all loaders: one name can be defined more than once (MaskingClassLoader
        // does exactly that in this very process), and each copy is a distinct type that must be woven.
        val present = i.allLoadedClasses.filter { it.name == cls }
        // A copy the JVM refuses to retransform can be neither woven nor unwoven, so it is dropped before it
        // reaches anything: the resolved targets, the multi-loader warning and the sweep all stay honest.
        // Refusing outright when NO copy survives beats installing a patch that can never fire — and whose
        // removal would throw, stranding it for the life of the JVM.
        val loaded = present.filter { i.isModifiableClass(it) }
        require(present.isEmpty() || loaded.isNotEmpty()) {
            "patch: $cls is loaded but unmodifiable (hidden class?) — it cannot be patched"
        }
        val targets = if (loaded.isEmpty()) null else PatchTargets.resolve(loaded, cls, methodName, params, gh.phase)

        val matcher = targets?.let(PatchTargets::exactMatcher) ?: PatchTargets.pendingMatcher(className, methodName, params)
        val slot = SEQ.incrementAndGet()
        val key = keyFor(className, methodName, params, tag, gh.phase, slot)

        // Register before weaving, so a fire that lands mid-install still hits.
        PatchBridge.register(slot, gh)

        // Keyed by the loader defining the copy: the granularity [weave] is called at, and the only one it
        // speaks for. Not a flat list — that could only append, and [weave] reruns per retransform.
        val woven = ConcurrentHashMap<Any, List<Woven>>()
        val seen = AtomicBoolean()
        // make() applies the advice AFTER [weave] recorded its answer into `woven`, so a throw there leaves a
        // patch that reports targets and can never fire — and the default listener is NoOp, so it was silent.
        val listener = WeaveListener(cls)
        val transformer: ResettableClassFileTransformer = try {
            // One transformer per install, in the JVM's chain until [Patches.remove] resets it, so every later
            // class load walks all of them — a few string compares each, since [IGNORED] is name-only and a miss
            // never resolves the type description. Folding them into one shared transformer over a registry would
            // save exactly that and nothing else, [Unweave.detach] being per-transformer. Live patches are meant
            // to stay few all the same: each is a deliberate call, and bulk-installing them is not what this is for.
            // ForDeclaredMethods, not the full graph: [weave] only matches declared methods and
            // disableClassFormatChanges rules out rebasing, while the full graph throws on mixin-rewritten
            // interface lists (Level, Entity, BlockEntity, ItemStack all fail under Kilt).
            AgentBuilder.Default(ByteBuddy().with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE))
                .disableClassFormatChanges() // method-body only -> retransform-safe, never rebased
                .ignore(IGNORED)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(discoveryOf(loaded))
                .with(listener)
                // The woven call runs in the PATCHED class's module, and the bridge sits in bootstrap's unnamed one:
                // automatic modules (minecraft, mods) read that for free, explicit ones (java.base, kotlin.stdlib)
                // do not, and without the edge the inlined call throws IllegalAccessError. Per type, so a late load counts.
                .with(AgentBuilder.Listener.ModuleReadEdgeCompleting.of(i, false, PatchBridge::class.java))
                .type(named(cls))
                .transform { builder, td, loader, _, _ ->
                    seen.set(true)
                    weave(builder, td, loader, Weave(matcher, params, methodName, slot, key, gh.phase), woven)
                }
                .installOn(i) // RETRANSFORMATION => already-loaded target patched now
        } catch (t: Throwable) {
            // PatchHandle owns the only unregister() call and isn't built yet, so nothing else ever could:
            // the entry would pin the snippet's classloader on the bootstrap-resident slot table for the life
            // of the JVM. ByteBuddy removes its own transformer before throwing, so only this is left.
            PatchBridge.unregister(slot)
            throw t
        }

        // `loaded` is already in hand, so the third case costs nothing: unwoven WITH the class present is the
        // one thing "pending, class not loaded" cannot mean, and it never resolves later either. The weave
        // error is tested FIRST because `seen` is true in that case too — it is set before the throw.
        val failed = listener.error
        val outcome = when {
            failed != null -> "WEAVE FAILED, nothing patched — $failed"
            seen.get() -> "${woven.values.flatten()}"
            loaded.isEmpty() -> "pending, class not loaded"
            else -> "NOT WOVEN — class is loaded, so this will not resolve later"
        }
        // `cls` and not just `key`: the id carries the caller's spelling, which on a mojmap script is not the
        // name a stack trace or crash report will show.
        if (failed != null) {
            Constants.LOG.error("[patch] {} installed on {} ({})", key, cls, outcome)
        } else {
            Constants.LOG.info("[patch] {} installed on {} ({})", key, cls, outcome)
        }
        return PatchHandle(key, slot, gh, Unweave(transformer, i, cls), woven, seen, listener)
    }

    /** Holds the throwable ByteBuddy hands a listener, for [PatchHandle.weaveError]. Filtered to the patched
     *  type: one install's redefinition pass walks every loaded class. */
    internal class WeaveListener(private val cls: String) : AgentBuilder.Listener.Adapter() {

        @Volatile
        var error: Throwable? = null
            private set

        override fun onError(typeName: String, loader: ClassLoader?, module: JavaModule?, loaded: Boolean, t: Throwable) {
            if (typeName == cls) error = t
        }
    }

    /** Which types the install's redefinition pass walks: the default walks `getAllLoadedClasses()` and matches
     *  every entry, so a pending install — which has no `Class` to name yet — still catches its target the
     *  moment it loads. Unweaving does not come through here; [Unweave] re-resolves the name against the live
     *  classes instead, so it sees whatever a pending patch eventually wove. */
    internal fun discoveryOf(loaded: List<Class<*>>): AgentBuilder.RedefinitionStrategy.DiscoveryStrategy = if (loaded.isEmpty()) {
        AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.SinglePass.INSTANCE
    } else {
        AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(loaded.toSet())
    }

    /** Everything the per-type transform body needs that isn't the type itself. [methodName] is the mojmap name
     *  the script asked for, carried rather than looked back up: it is exact by construction, and on an obf
     *  runtime it is the only way the reported signature can name the method at all. */
    private class Weave(
        val matcher: ElementMatcher<MethodDescription>,
        val params: List<String>?,
        val methodName: String,
        val slot: Int,
        val key: String,
        val phase: Phase,
    )

    /**
     * The per-type transform body. It re-derives the matching methods from the type being transformed, so
     * the PENDING path — which had nothing to check at install time — still enforces the one-signature rule
     * here. It cannot throw usefully at this point: a `ClassFileTransformer` throwable is swallowed by the
     * JVM and the class loads untransformed, and the caller returned long ago. So an ambiguous late
     * resolution refuses to weave and says so, and [PatchHandle.targets] reports the empty result.
     */
    private fun weave(
        builder: DynamicType.Builder<*>,
        td: TypeDescription,
        loader: ClassLoader?,
        w: Weave,
        woven: MutableMap<Any, List<Woven>>,
    ): DynamicType.Builder<*> {
        val hits = td.declaredMethods
            .filter { !it.isBridge && !it.isSynthetic && !it.isAbstract && !it.isNative && w.matcher.matches(it) }
        // Signature -> the params it rendered from: the dedup the one-signature rule always did, keeping the
        // structured form [Woven] carries for [Patches.removeEnter]'s filter.
        val sigs = LinkedHashMap<String, List<String>>()
        for (m in hits) Signatures.paramsOf(m).let { sigs.putIfAbsent(Signatures.render(w.methodName, it), it) }
        // A writable patch binds ONE return type as a constant and hands one substitute to whatever fires, so
        // it must resolve to a single method — the return type included, which the signature key omits.
        val rts = hits.mapTo(LinkedHashSet()) { it.returnType.asErasure() }
        val ambiguous = sigs.size > 1 && (w.params != null || w.phase.writable)
        if (ambiguous || (w.phase.writable && rts.size > 1)) {
            Constants.LOG.error(
                "[patch] {} resolved to {} signature(s) / {} return type(s) on loader {} — refusing to weave an ambiguous match: {}",
                w.key, sigs.size, rts.size, PatchDiagnostics.loaderName(loader), sigs.keys,
            )
            return builder
        }
        val ln = PatchDiagnostics.loaderName(loader)
        // Put, not append: this rederives the whole answer for one copy, and the JVM reruns it on every
        // retransform of that copy — which every later install or remove on the class triggers.
        woven[loader ?: BOOTSTRAP] = sigs.map { (sig, params) -> Woven(params, "$sig @$ln") }
        return builder.visit(adviceFor(w.phase, w.slot, w.key, w.matcher, rts.firstOrNull()) ?: return builder)
    }

    /** The visitor for [phase], or null when there is nothing left to weave.
     *
     *  AROUND is a composition, not a blueprint of its own: the observe advice goes INSIDE the intercept, so
     *  the short-circuit jump clears it along with the body — `skipOn` cannot skip an exit advice of its own
     *  advice, but it does skip a nested one. Order is load-bearing and verified against the woven bytecode:
     *  [AsmVisitorWrapper.Compound]'s FIRST element ends up outermost, the last hugging the original body. */
    private fun adviceFor(
        phase: Phase,
        slot: Int,
        key: String,
        matcher: ElementMatcher<MethodDescription>,
        rt: TypeDescription?,
    ): AsmVisitorWrapper? {
        var mapping = Advice.withCustomMapping().bind(PatchKey::class.java, key).bind(PatchSlot::class.java, slot)
        if (phase.writable) {
            if (rt == null) return null   // matched nothing here, so there is no substitute to assign
            // The cast a substitute is assigned through runs inside the patched method; suppressed, a
            // value that slipped past validation leaves the original standing instead of throwing there.
            mapping = mapping.with(Advice.AssignReturned.Factory().withSuppressed(Throwable::class.java))
        }
        return when (phase) {
            Phase.ENTER -> mapping.to(PatchEnterAdvice::class.java).on(matcher)
            Phase.EXIT -> mapping.to(PatchExitAdvice::class.java).on(matcher)
            Phase.INTERCEPT -> mapping.to(PatchInterceptAdvice::class.java).on(matcher)
            Phase.MODIFY -> mapping.to(PatchModifyAdvice::class.java).on(matcher)
            Phase.AROUND -> AsmVisitorWrapper.Compound(
                mapping.to(PatchInterceptAdvice::class.java).on(matcher),
                mapping.to(PatchExitAdvice::class.java).on(matcher),
            )
        }
    }

    // ---- matchers ----------------------------------------------------------------------------

    /** ByteBuddy's default ignore minus two clauses. Its classloader one (`isNull() or PlatformClassLoader`) drops
     *  every bootstrap/platform target as `onIgnored`, which reaches [PatchHandle] as `pending`, unreadable against
     *  a class that never loaded. `isSynthetic()` reads modifiers, and the description this matches against
     *  resolves its NAME lazily and nothing else — so it would parse the class file of everything the name clauses
     *  below miss, i.e. every class the game loads, once per installed patch. Both matchers select one method on
     *  one FQN, so nothing synthetic reaches them anyway. The rest is verbatim: weaving ByteBuddy or the reflection
     *  accessors recurses into the weave, and [PatchBridge] — bootstrap-resident, so reachable only now — joins it
     *  for that same reason, being what every woven advice calls. */
    private val IGNORED: ElementMatcher.Junction<TypeDescription> =
        ElementMatchers.nameStartsWith<TypeDescription>("net.bytebuddy.")
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("net.bytebuddy.renamed.")))
            .or(ElementMatchers.nameStartsWith("sun.reflect."))
            .or(ElementMatchers.nameStartsWith("jdk.internal.reflect."))
            .or(ElementMatchers.nameStartsWith(PatchBridge::class.java.packageName + "."))

    /** Warm target: never called, only woven against. Non-void so the writable blueprints build a real
     *  return assigner. */
    @Suppress("unused", "FunctionOnlyReturningConstant", "SameReturnValue")
    private object WarmTarget {
        fun warm(): Int = 0
    }

    /**
     * Load and parse everything a first install would, WITHOUT a retransform — that is a stop-the-world pause
     * proportional to the number of classes the JVM has loaded, and it warms nothing this does not.
     *
     * Every piece here is cold and unreachable another way: the blueprints, of which the first drags in
     * ByteBuddy itself; ASM's weaving path, which is why this weaves offline rather than only parsing; and
     * [AgentBuilder]'s chain, built and dropped — `installOn` is the one thing it must not do.
     */
    internal fun warm() {
        val target = WarmTarget::class.java
        val matcher = named<MethodDescription>("warm")
        val returnType = TypeDescription.ForLoadedType.of(target.getDeclaredMethod("warm").returnType)
        for (phase in Phase.entries) {
            // Slot 0: SEQ starts at 1, so nothing is ever registered there — this weaves, and never fires.
            ByteBuddy().redefine(target).visit(adviceFor(phase, 0, "warm", matcher, returnType) ?: continue).make()
        }
        AgentBuilder.Default(ByteBuddy().with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE))
            .disableClassFormatChanges()
            .ignore(IGNORED)
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(named(target.name))
    }

    /** The install call echoed back, deliberately NOT the resolved methods: the caller reads their own spelling
     *  with no namespace to undo, and the id cannot outgrow what they typed. What was woven is [PatchHandle.targets].
     *
     *  [tag] is echoed: with two patches on one method it is the only thing telling them apart. A null
     *  [params] renders `(...)` — not `(*)`, which `listOf("*")` already takes for a different meaning. */
    @Suppress("LongParameterList") // the key IS the install call rendered, so every part of it has to arrive
    private fun keyFor(className: String, methodName: String, params: List<String>?, tag: String?, phase: Phase, seq: Int): String {
        val sig = params?.joinToString(", ", "(", ")") ?: "(...)"
        return "$className#$methodName$sig@${phase.slug}${tag?.let { "[$it]" }.orEmpty()}#$seq"
    }

    // ---- handlers ----------------------------------------------------------------------------

    /** Game-side handler: counts fires and forwards to the user callback. Implements the bootstrap
     *  [Handler], whose phase methods all default to no-ops, so each subclass overrides only the ones its
     *  patch was woven for. */
    internal sealed class CountingHandler : Handler {

        /** The blueprint this handler answers for. Carried here rather than passed beside it: the two are one
         *  choice, and a separate parameter could disagree with the subclass. */
        abstract val phase: Phase

        val count = AtomicLong()
        val failures = AtomicLong()

        /** The throwable itself, not a rendering: [PatchHandle.lastError] hands it out whole and only the
         *  status line shortens it. */
        @Volatile
        var lastError: Throwable? = null

        /** A throwing callback is swallowed — the hook only observes, so the patched method must still run.
         *  Recording it is therefore the only thing that keeps a broken handler visible to [Patches.handle].
         *  Two writes and no log line, deliberately: a handler on a hot method throws per call, and logging
         *  a stack that often costs more than whatever it was patched to watch. */
        fun failed(t: Throwable) {
            failures.incrementAndGet()
            lastError = t
        }
    }

    internal class EnterHandler(private val cb: PatchEnterCallback) : CountingHandler() {
        override val phase get() = Phase.ENTER

        override fun onEnter(key: String, self: Any?, args: Array<Any?>?) {
            count.incrementAndGet()
            try {
                cb.onEnter(key, self, args)
            } catch (t: Throwable) {
                failed(t)
            }
        }
    }

    internal class ExitHandler(private val cb: PatchExitCallback) : CountingHandler() {
        override val phase get() = Phase.EXIT

        override fun onExit(key: String, self: Any?, args: Array<Any?>?, returned: Any?, thrown: Throwable?) {
            count.incrementAndGet()
            try {
                cb.onExit(key, self, args, returned, thrown)
            } catch (t: Throwable) {
                failed(t)
            }
        }
    }

    /** Head-side decision. Open only so [AroundHandler] can add the return observer to it. */
    internal open class InterceptHandler(private val cb: PatchInterceptCallback) : CountingHandler() {
        override val phase get() = Phase.INTERCEPT

        final override fun onIntercept(key: String, sig: MethodType, self: Any?, args: Array<Any?>): Any? {
            count.incrementAndGet()
            val before = args.copyOf()
            return try {
                val d = cb.intercept(key, self, args)
                // Origin prepends the receiver and `args` never carries it: 1 on an instance method, 0 on a
                // static one. Derived rather than branched on staticness — the difference IS the alignment.
                val off = sig.parameterCount() - args.size
                // Changed slots only — an untouched value is the one the method itself passed in.
                for (i in args.indices) if (args[i] !== before[i]) checked(sig.parameterType(i + off), args[i])
                if (d.replaces) arrayOf(checked(sig.returnType(), d.value)) else null
            } catch (t: Throwable) {
                failed(t)
                // Exactly what the write-back distributes, so a refusal above leaves every argument standing
                // rather than the prefix it had already accepted.
                System.arraycopy(before, 0, args, 0, before.size)
                null
            }
        }
    }

    /** [InterceptHandler] plus a read-only return observer, woven as the inner advice so a skipped body takes
     *  it with it. Fires are counted at the head only — one per call, as for every other phase. */
    internal class AroundHandler(cb: PatchInterceptCallback, private val onReturn: PatchExitCallback) : InterceptHandler(cb) {
        override val phase get() = Phase.AROUND

        override fun onExit(key: String, self: Any?, args: Array<Any?>?, returned: Any?, thrown: Throwable?) {
            try {
                onReturn.onExit(key, self, args, returned, thrown)
            } catch (t: Throwable) {
                failed(t)
            }
        }
    }

    internal class ModifyHandler(private val cb: PatchModifyCallback) : CountingHandler() {
        override val phase get() = Phase.MODIFY

        override fun onModify(key: String, sig: MethodType, self: Any?, args: Array<Any?>?, returned: Any?, thrown: Throwable?): Any? {
            count.incrementAndGet()
            return try {
                val d = cb.modify(key, self, args, returned, thrown)
                if (d.replaces) checked(sig.returnType(), d.value) else returned
            } catch (t: Throwable) {
                failed(t)
                returned
            }
        }
    }
}

/** [v] if a slot of type [rt] can hold it, else a throw — which the caller's own catch turns into a recorded
 *  failure, so a bad value and a throwing callback share one failure path.
 *
 *  Exact by necessity: ByteBuddy casts the `Object` to the TARGET primitive's wrapper before unboxing, so an
 *  `Integer` never reaches a `long`. Wrappers being final, `isInstance` is that exactness for free. */
private fun checked(rt: Class<*>, v: Any?): Any? {
    val ok = when {
        rt == Void.TYPE -> true // AssignReturned.ToReturned drops the assignment; nothing reads the value
        v == null -> !rt.isPrimitive
        else -> wrapperOf(rt).isInstance(v)
    }
    if (!ok) error("cannot use ${v?.javaClass?.typeName ?: "null"} where ${rt.typeName} is expected")
    return v
}

/** [rt]'s wrapper if it is primitive, else [rt] itself. `MethodType.methodType(rt).wrap()` answers the same,
 *  but interns a MethodType per call on a path that runs per fire — and it puts MethodType on that path, where
 *  patching it would take every writable patch with it. `===` and not `==`, so no `Object.equals` joins it
 *  there either; `javaObjectType` reads like a getter but the compiler folds it to an `LDC`. */
private fun wrapperOf(rt: Class<*>): Class<*> = when {
    rt === Integer.TYPE -> Int::class.javaObjectType
    rt === Character.TYPE -> Char::class.javaObjectType
    rt === java.lang.Long.TYPE -> Long::class.javaObjectType
    rt === java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
    rt === java.lang.Double.TYPE -> Double::class.javaObjectType
    rt === java.lang.Float.TYPE -> Float::class.javaObjectType
    rt === java.lang.Byte.TYPE -> Byte::class.javaObjectType
    rt === java.lang.Short.TYPE -> Short::class.javaObjectType
    else -> rt
}
