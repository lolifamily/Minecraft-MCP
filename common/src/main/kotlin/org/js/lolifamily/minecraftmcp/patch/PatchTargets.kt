package org.js.lolifamily.minecraftmcp.patch

import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.matcher.ElementMatchers.named
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.js.lolifamily.minecraftmcp.repl.NamespaceProbe
import org.js.lolifamily.minecraftmcpbridge.PatchBridge
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

/**
 * Which methods an install call names, and the matcher that reaches them. Split from [Patch] because it
 * answers a different question: [Patch] decides what to weave INTO a method, this decides WHICH method.
 *
 * Two paths, by whether the class is loaded — see [Patch]'s class doc for why the loaded one is preferred and
 * what it buys.
 */
internal object PatchTargets {

    /**
     * The methods to weave, or a thrown [IllegalArgumentException] naming what IS there.
     *
     * [params] null means every overload of the name. Otherwise exactly one SIGNATURE must match: copies of
     * one signature carried by several loaders are NOT an ambiguity, because their parameter types are
     * identical and so a callback can neither tell them apart nor be harmed by the difference.
     */
    fun resolve(loaded: List<Class<*>>, cls: String, methodName: String, params: List<String>?, phase: Patch.Phase): List<Method> {
        if (loaded.size > 1) {
            Constants.LOG.warn(
                "[patch] {} is defined on {} loaders ({}) — every copy will be woven",
                cls, loaded.size, loaded.joinToString { PatchDiagnostics.loaderName(it.classLoader) },
            )
        }
        val declared = loaded.flatMap { it.declaredMethods.asIterable() }.filter { !it.isBridge && !it.isSynthetic }
        // FORWARD, not a reverse demap of each declared name: the reverse table collides on an obf runtime and
        // there refuses to answer, which would make every patch report "no such method". Also cheaper — one
        // hierarchy walk per loaded copy instead of a table lookup per declared method.
        val wanted = loaded.flatMapTo(HashSet()) { Signatures.runtimeNamesOf(it, methodName) }
        // The name alone is not the answer on an obf runtime: proguard collapses many mojmap methods onto ONE
        // name, so `tickServer` -> `a` also reaches halt, saveAllChunks and a couple dozen more. Demapping picks
        // the real one back out; where it CANNOT decide it answers with the runtime name, and that one stays.
        val byName = declared.filter { m ->
            m.name in wanted && Signatures.methodToMojmap(m).let { it == methodName || it == m.name }
        }
        if (byName.isEmpty()) {
            // A name that resolved against the hierarchy but declares nothing HERE is inherited, not misspelled
            // — a different mistake, and the ranked near-miss list is the wrong answer to it.
            val up = loaded.firstNotNullOfOrNull { declarerOf(it, wanted) }
            throw up?.let { (owner, ms) -> PatchDiagnostics.inheritedElsewhere(cls, methodName, owner.name, ms) }
                ?: PatchDiagnostics.noSuchMethod(cls, methodName, declared)
        }

        // Abstract and native methods carry no Code attribute, so ByteBuddy's Advice silently skips them.
        // Dropping them HERE rather than letting them through is what keeps the reported target list honest:
        // weaving one would be reported as a success that can never fire.
        val weavable = byName.filterNot { Modifier.isAbstract(it.modifiers) || Modifier.isNative(it.modifiers) }
        if (weavable.isEmpty()) throw PatchDiagnostics.notWeavable(cls, methodName, byName)

        val chosen = if (params == null) weavable else selectOne(cls, methodName, params, weavable)
        refuseDispatchPath(cls, chosen, phase)
        return chosen
    }

    /** Veto, run last on what would actually be woven. All or nothing, since a name-only install weaves every
     *  overload — so the survivors are listed, which turns the refusal into a narrower retry rather than a
     *  dead end. Built here rather than in [PatchDiagnostics]: one call site, and [refusal]'s reasons already
     *  are the diagnosis. */
    private fun refuseDispatchPath(cls: String, chosen: List<Method>, phase: Patch.Phase) {
        val refused = chosen.mapNotNull { m -> refusal(m, phase)?.let { m to it } }
        if (refused.isEmpty()) return
        // Both are one method's overloads — single digit, where building the Set costs more than the scan it replaces.
        @Suppress("ConvertArgumentToSet")
        val rest = chosen - refused.map { it.first }
        throw IllegalArgumentException(
            "patch: weaving $cls would break every other patch — it is the engine's own dispatch path:\n" +
                refused.distinctBy { PatchDiagnostics.sigOf(it.first) }
                    .joinToString("\n") { (m, why) -> "  ${PatchDiagnostics.sigOf(m)} — $why" } +
                if (rest.isEmpty()) "" else "\nname the parameter types for one of these instead:\n" + PatchDiagnostics.listing(rest),
        )
    }

    /** Why [m] may not be patched, or null. Each entry is on the path EVERY patch of that phase dispatches
     *  through, so weaving it breaks patches its installer never wrote — unlike a handler that loops back into
     *  its own target, which is the caller's. Keyed on the declaring class, which inheritance cannot route
     *  around: a box is INVOKESTATIC, and the wrappers are final and each declares its own accessor. The two
     *  names are JLS 5.1.7/5.1.8, so only the wrapper is looked up. */
    private fun refusal(m: Method, phase: Patch.Phase): String? {
        val owner = m.declaringClass
        val own = Patch::class.java.name
        val prim = MethodType.methodType(owner).unwrap().returnType() // a wrapper's primitive, else not one
        return when {
            // Also in Patch.IGNORED, which stops the weave but reports it as a miss. Named here so both halves
            // of the trampoline answer alike — the game-side one below has no other guard at all.
            owner.name.startsWith(PatchBridge::class.java.packageName + ".") -> "bootstrap trampoline"
            owner.name == own || owner.name.startsWith("$own$") -> "dispatch handler"
            owner.name == AtomicLong::class.java.name && m.name == "incrementAndGet" -> "fire counter"
            !prim.isPrimitive -> null
            m.name == "valueOf" && m.parameterTypes.contentEquals(arrayOf(prim)) -> "argument boxing"
            // Read-only phases never unbox: they take the boxed value and hand it on.
            phase.writable && m.name == "${prim.name}Value" && m.parameterCount == 0 -> "return unboxing"
            else -> null
        }
    }

    /** The nearest ancestor of [target] declaring a weavable method named in [runtimeNames], with those methods.
     *  Abstract and native are dropped for the reason [resolve] drops them: naming one redirects the caller to a
     *  class that fails the same way. The interface closure is walked too — a default method carries bytecode. */
    private fun declarerOf(target: Class<*>, runtimeNames: Set<String>): Pair<Class<*>, List<Method>>? {
        for (c in Signatures.hierarchy(target)) {
            if (c === target) continue
            val hits = c.declaredMethods.filter {
                it.name in runtimeNames && !it.isBridge && !it.isSynthetic &&
                    !Modifier.isAbstract(it.modifiers) && !Modifier.isNative(it.modifiers)
            }
            if (hits.isNotEmpty()) return c to hits
        }
        return null
    }

    /** Narrow [weavable] to the ONE signature [params] asks for. Several loaders' copies of that signature
     *  all come back — their parameter types are identical, so a callback can neither tell them apart nor be
     *  harmed by the difference, which is the only thing the uniqueness rule protects. */
    private fun selectOne(cls: String, methodName: String, params: List<String>, weavable: List<Method>): List<Method> {
        val hits = weavable.filter { Signatures.matches(Signatures.paramsOf(it), params) }
        val distinct = hits.distinctBy(PatchDiagnostics::sigOf)
        if (distinct.isEmpty()) throw PatchDiagnostics.noSuchSignature(cls, methodName, params, weavable)
        if (distinct.size > 1) throw PatchDiagnostics.ambiguous(cls, methodName, params, distinct)
        return hits
    }

    /** Exactly the resolved methods, by runtime name + descriptor. Deduped because copies of one class on
     *  several loaders yield the same pair, and one term matches them all. */
    fun exactMatcher(targets: List<Method>): ElementMatcher<MethodDescription> =
        targets.map { it.name to MethodDescription.ForLoadedMethod(it).descriptor }
            .distinct()
            .map { (n, d) -> named<MethodDescription>(n).and(ElementMatchers.hasDescriptor(d)) }
            .reduce { a, b -> a.or(b) }

    /** Class not loaded, so there is nothing to reflect over: fall back to forward name translation (one
     *  mojmap name maps to one runtime name per overload), narrowed by the wanted signature if there is one. */
    fun pendingMatcher(className: String, methodName: String, params: List<String>?): ElementMatcher.Junction<MethodDescription> {
        val mp = if (NamespaceProbe.needsRemap()) Mappings.current() else null
        val names = mp?.mapMethodAll(className, methodName) ?: listOf(methodName)
        val byName = names.map { named<MethodDescription>(it) }.reduce { a, b -> a.or(b) }
        if (params == null) return byName
        return byName.and(ElementMatcher { md -> Signatures.matches(Signatures.paramsOf(md), params) })
    }
}
