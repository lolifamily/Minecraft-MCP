package org.js.lolifamily.minecraftmcp.patch

import net.bytebuddy.description.method.MethodDescription
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.js.lolifamily.minecraftmcp.repl.NamespaceProbe
import java.lang.reflect.Method

/**
 * Name and signature normalization, shared by patch resolution ([Patch]) and by the diagnostics it throws.
 *
 * Two directions, split by what each one can guarantee:
 *
 * - **runtime -> mojmap** ([typeToMojmap], [methodToMojmap], [render]) — DISPLAY. The only direction that works
 *   for the short names a script writes (`ItemStack`, `Properties`), since the forward map is FQN-keyed. Type
 *   names demap on their own; method names need an owner, so the overloads holding one answer where the
 *   owner-free [Mappings.reverseMethod] must refuse. A wrong name here is cosmetic.
 * - **mojmap -> runtime** ([runtimeNamesOf]) — RESOLUTION, where a wrong answer silently targets nothing. This
 *   one is load-bearing, and it is deliberately NOT the reverse direction; see its own doc.
 *
 * On a mojmap runtime there are no mappings at all, so both degenerate to the identity and dev /
 * Fabric-production / Forge-SRG / spigot collapse into one code path with no branches.
 */
internal object Signatures {

    /** The live mappings, or null when the runtime is already mojmap — in which case every conversion
     *  below is the identity. */
    private fun mp(): Mappings? = if (NamespaceProbe.needsRemap()) Mappings.current() else null

    /**
     * Runtime type name -> mojmap, array dimensions preserved (`class_1799[]` -> `…ItemStack[]`). Unmapped
     * names — primitives, JDK, mod and library classes — pass through unchanged, which is correct for them:
     * they are already written the same way in both namespaces.
     */
    fun typeToMojmap(runtime: String): String {
        val dims = runtime.length - runtime.trimEnd('[', ']').length
        return (mp()?.reverseClass(runtime.dropLast(dims)) ?: runtime.dropLast(dims)) + runtime.takeLast(dims)
    }

    /**
     * [m]'s mojmap name, using the owner the string overload does not have. The cheap owner-free answer where it
     * is unambiguous; otherwise [Mappings.namedMethodOn], climbed because an override's mapping row lives
     * under the class it was inherited from, not under the class that declares the override.
     *
     * Still display-only, and still degrades to the runtime name rather than guessing: a covariant-return
     * override carries a descriptor no row matches, so it lands there.
     */
    fun methodToMojmap(m: Method): String {
        val mp = mp() ?: return m.name
        val loose = mp.reverseMethod(m.name)
        if (loose != m.name) return loose // unambiguous, or unmapped — either way the exact index adds nothing
        val desc = MethodDescription.ForLoadedMethod(m).descriptor
        for (c in hierarchy(m.declaringClass)) {
            mp.namedMethodOn(c.name, m.name, desc)?.let { return it }
        }
        return m.name
    }

    /**
     * A stack frame's mojmap name, from what [StackTraceElement] carries. Three tiers, cheapest first: the
     * owner-free answer where it holds; else the owner's same-named methods demapped, taken when they AGREE,
     * since a frame prints no parameters and overloads collapsed onto one obf name are not ambiguous here; else
     * the one [LineTable] puts at [line]. Synthetics go first, or a bridge — whose descriptor matches no row —
     * demaps to itself and outvotes the real method.
     */
    fun methodToMojmap(runtimeOwner: String, runtimeMethod: String, line: Int): String {
        val mp = mp() ?: return runtimeMethod
        val loose = mp.reverseMethod(runtimeMethod)
        if (loose != runtimeMethod) return loose
        return try {
            val candidates = Class.forName(runtimeOwner, false, Constants.MC_LOADER).declaredMethods
                .filter { it.name == runtimeMethod && !it.isSynthetic }
            candidates.map { methodToMojmap(it) }.distinct().singleOrNull()
                ?: LineTable.descAt(runtimeOwner.replace('.', '/'), runtimeMethod, line)
                    ?.let { d -> candidates.firstOrNull { MethodDescription.ForLoadedMethod(it).descriptor == d } }
                    ?.let { methodToMojmap(it) }
        } catch (_: Throwable) { null } ?: runtimeMethod
    }

    /**
     * Every RUNTIME name [mojmapMethod] can carry on [target] or any ancestor — the forward direction, and the
     * only sound way to pick a patch target on an obf runtime.
     *
     * [methodToMojmap] cannot do this job: its table is keyed by the runtime name alone, which is unique on
     * intermediary/srg but not on spigot, where it refuses to answer at all. The forward table is keyed by
     * (owner, mojmap name) — unique in every namespace — and the owner is free, because the script named it.
     *
     * The climb is what the owner-free reverse lookup was standing in for: an override declared on a MOD class
     * carries the runtime name of the MC method it overrides, and only the MC declarer has a mapping row.
     * Converting the OWNER per level is safe where converting the member is not: no namespace can spell two
     * classes the same way, so the class map is injective in all of them — which is exactly what obf member
     * names are not, collapsing many mojmap methods onto one.
     *
     * O(hierarchy depth), NOT O(declared methods): the wanted name is fixed, so each level is two hash lookups.
     * An unmapped level passes [mojmapMethod] through, which is what a mod's own same-named method needs.
     */
    fun runtimeNamesOf(target: Class<*>, mojmapMethod: String): Set<String> {
        val mp = mp() ?: return setOf(mojmapMethod) // mojmap runtime: source names ARE runtime names
        val out = HashSet<String>(4)
        for (c in hierarchy(target)) out += mp.runtimeNamesOn(c.name, mojmapMethod)
        return out
    }

    /** [target] + every superclass + the transitive interface closure, breadth-first. Deliberately uncached: a
     *  cache keyed by Class would strong-reference it and pin its loader, and snippet classes DO unload (ReplHost
     *  builds a fresh evaluator per eval precisely so they can). One walk per patch install is microseconds. */
    fun hierarchy(target: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>(8)
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.addLast(target)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            out.add(c)
            c.superclass?.let { queue.addLast(it) }
            for (i in c.interfaces) queue.addLast(i)
        }
        return out
    }

    /** The parameter types of a loaded method, in runtime names (`Foo[]` / `Outer$Inner` spelling). */
    fun paramsOf(m: Method): List<String> = m.parameterTypes.map { it.typeName }

    /** As above for ByteBuddy's view. `actualName`, NOT `name`: the latter is the binary form (`[LFoo;`),
     *  which would not compare equal to [Method]'s `typeName` and would silently match nothing. */
    fun paramsOf(md: MethodDescription): List<String> = md.parameters.map { it.type.asErasure().actualName }

    /**
     * Does one already-mojmap parameter type satisfy one wanted slot?
     *
     * `*` matches anything, on either side — [Patches.removeEnter] puts a pending patch's own filter on the left.
     *
     * Otherwise the wanted name may be the full mojmap FQN or any trailing part of it, so `Properties`,
     * `Item.Properties` and the full name all select `net.minecraft.world.item.Item$Properties`.
     * Nested-class `$` is normalized to `.` on both sides, so a name from `Class.getName()` works too.
     *
     * JVM type names are case-sensitive, so this comparison is too — `int`, not Kotlin's `Int`. A looser
     * compare can match two distinct types and raise an ambiguity no caller can spell their way out of; a
     * wrong case costs one retry, since the throw lists the real spellings.
     */
    fun slotMatches(actualMojmap: String, want: String): Boolean {
        if (want == "*" || actualMojmap == "*") return true
        val a = actualMojmap.replace('$', '.')
        val w = want.replace('$', '.')
        return a == w || a.endsWith(".$w")
    }

    /** Does a method's runtime parameter list satisfy every wanted slot? */
    fun matches(actualRuntime: List<String>, want: List<String>): Boolean = actualRuntime.size == want.size &&
        actualRuntime.indices.all { slotMatches(typeToMojmap(actualRuntime[it]), want[it]) }

    /**
     * A method's mojmap signature text, e.g. `use(Level, Player)`. This is BOTH the dedup key for the
     * "exactly one signature" rule and one line of the candidate list in an error message, and it must stay
     * one function: what the rule collapses is exactly what the error must not list twice.
     *
     * Deliberately excludes the return type. Two methods differing only there — a covariant-return bridge
     * that escaped the `ACC_BRIDGE` filter, or aggressively-overloaded obfuscated bytecode — are
     * indistinguishable to a callback, so they are not ambiguous in the sense this rule protects: entry
     * advice never sees a return value, and exit advice boxes it to `Object` regardless. Including the
     * return type would raise an "ambiguous" error the caller cannot possibly resolve, because the API has
     * no return-type selector.
     *
     * That reasoning is a live dependency: if exit advice ever exposes a TYPED return value, this must grow
     * a return type and the API must grow a way to select on it.
     *
     * [mojmapName] arrives ALREADY demapped, rather than being looked up here: one caller holds the name the
     * script asked for and needs no lookup at all, and the other has a loaded [Method] to demap from, which is
     * strictly better than the owner-free lookup this could do. Neither shape fits inside one signature.
     */
    fun render(mojmapName: String, runtimeParams: List<String>): String =
        mojmapName + runtimeParams.joinToString(", ", "(", ")") { typeToMojmap(it) }
}
