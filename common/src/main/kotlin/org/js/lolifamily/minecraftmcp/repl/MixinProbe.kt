package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

/**
 * What Mixin merges into its targets, read off Mixin's own model — the prepared configs and the `MixinInfo`s
 * under them — rather than by applying anything to a class.
 *
 * Game-loader side: the [ClassNode] Mixin hands back belongs to the host's ASM, and the masking loader has its
 * own copy of that class, so only [Decls] crosses the seam.
 *
 * ## Why so little is carried
 *
 * Mixin renames a merged member exactly when nothing outside could name it: an injector handler becomes
 * `handler$<uid>$…`, a non-public `@Unique` becomes `md<session>$…`, and a static `@Accessor` lands on the
 * target as `…_$md$<session>$<n>` — where `session` is a fresh UUID per launch, so a name recorded today is
 * wrong tomorrow. The converse is what this rests on: **what Mixin does not rename is exactly what source is
 * allowed to write**, because renaming it would break the callers.
 *
 * So the overlay carries two things:
 *  - the INTERFACES a mixin adds. Their members are already declared on the interface's own class file, which
 *    is an ordinary classpath entry, so the target needs the `implements` entry and nothing more — and an
 *    interface name is never renamed.
 *  - PUBLIC merged members, for a mixin that adds them with no interface to reach them through.
 *
 * and nothing else. Handlers are modifications, not additions. Static `@Accessor`/`@Invoker` are called as
 * `Iface.foo()`, so the interface alone already compiles. Anything below public is out of a snippet's reach
 * regardless — it is neither in the package nor a subclass.
 *
 * Every touch is reflective and none of it is API. Any failure degrades to "no overlay", which costs a
 * snippet a cast.
 */
object MixinProbe {

    /** Kill switch for the declaration overlay. */
    const val ENABLED = "mcp.mixin.overlay"

    /** One declared member. */
    class Member(val name: String, val desc: String, val access: Int)

    /** A class's declarations. Declared here, in a package the masking loader delegates, so both sides see
     *  one type and nothing has to be flattened to cross. */
    class Decls(val interfaces: List<String>, val methods: List<Member>, val fields: List<Member>) {
        val isEmpty: Boolean get() = interfaces.isEmpty() && methods.isEmpty() && fields.isEmpty()
    }

    /** Target class name (dotted, as Mixin keys it) -> what mixins add to it. Derived once: by the time
     *  anything asks, every config has long been prepared. */
    private val added: Map<String, Decls> by lazy { derive() }

    fun available(): Boolean = added.isNotEmpty()

    /** The targets with something to graft — not every registered target, since one that adds nothing would
     *  only claim entries the overlay then copies unchanged. */
    fun targets(): Set<String> = added.keys

    fun addedFor(target: String): Decls? = added[target]

    /** [name]'s own declarations, read straight off its class file. No Mixin involved: this is asked about an
     *  added INTERFACE, which is an ordinary classpath class. Null when it cannot be read. */
    fun declarationsOf(name: String): Decls? = runCatching {
        Constants.MC_LOADER.getResourceAsStream(name.replace('.', '/') + ".class")?.use { input ->
            val node = ClassNode()
            ClassReader(input.readBytes()).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            Decls(
                node.interfaces.toList(),
                node.methods.map { Member(it.name, it.desc, it.access) },
                node.fields.map { Member(it.name, it.desc, it.access) },
            )
        }
    }.getOrNull()

    // ---- derivation ---------------------------------------------------------------------------

    private fun derive(): Map<String, Decls> {
        if (!Props.bool(ENABLED, true)) {
            Constants.LOG.info("[mcp-aw/mixin] declaration overlay off: {}=false", ENABLED)
            return emptyMap()
        }
        // Absent by platform rather than by breakage — no Mixin at all. Stay quiet.
        val processor = runCatching {
            val env = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, Constants.GAME_LOADER)
            field(env.getMethod("getActiveTransformer").invoke(env.getMethod("getCurrentEnvironment").invoke(null)), "processor")
        }.getOrNull() ?: return emptyMap()
        // Past here Mixin IS present, so a failure means its internals moved. Report it: the alternative is
        // shipping a compile classpath that silently lacks declarations the runtime has.
        return runCatching { collect(processor) }
            .onFailure { Constants.LOG.warn("[mcp-aw/mixin] Mixin internals unreadable, declaration overlay off: {}", "$it") }
            .getOrDefault(emptyMap())
    }

    private fun collect(processor: Any): Map<String, Decls> {
        val notAdded = disqualifying()
        // On the public interface: MixinInfo is package-private, so a Method resolved on it throws
        // IllegalAccessException on invoke even for a method declared public.
        val getClassNode = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinInfo", false, Constants.GAME_LOADER)
            .getMethod("getClassNode", Int::class.javaPrimitiveType)
        val byTarget = snapshot(processor)
        // Which interfaces reach the target is ASKED, not derived: this is the same collection
        // `MixinApplicatorStandard.applyInterfaces` adds, and only Mixin knows it. An accessor mixin answers
        // with its OWN name, a plain interface mixin with the interfaces it extends and not itself (its
        // members are merged into the target interface instead), a class mixin with what it implements.
        // Package-private owner, so it is opened rather than taken off IMixinInfo, which does not carry it.
        val sample = byTarget.values.firstOrNull()?.firstOrNull() ?: return emptyMap()
        val getInterfaces = sample.javaClass.getDeclaredMethod("getInterfaces").apply { isAccessible = true }
        val getClassInfo = sample.javaClass.getDeclaredMethod("getClassInfo").apply { isAccessible = true }
        val classInfo = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", false, Constants.GAME_LOADER)
        val getMethods = classInfo.getDeclaredMethod("getMethods").apply { isAccessible = true }
        val method = classInfo.declaredClasses.first { it.simpleName == "Method" }
        val originalName = method.getMethod("getOriginalName")
        val isRenamed = method.getMethod("isRenamed")
        val parts = LinkedHashMap<String, MutableList<Decls>>()
        for ((target, infos) in byTarget) {
            // getClassNode is an in-memory copy of bytes the MixinInfo has held since prepare — no class load,
            // no bytecode provider, and it answers for a target that has not loaded yet.
            val into = parts.getOrPut(target) { ArrayList() }
            for (info in infos) {
                @Suppress("UNCHECKED_CAST")
                val ifaces = getInterfaces.invoke(info) as Collection<String>
                val renamed = (getMethods.invoke(getClassInfo.invoke(info)) as Collection<*>).filterNotNull()
                    .filter { isRenamed.invoke(it) as Boolean }
                    .mapTo(HashSet()) { originalName.invoke(it) as String }
                into += contribution(getClassNode.invoke(info, 0) as ClassNode, ifaces, renamed, notAdded)
            }
        }
        return parts.mapValues { (_, p) ->
            Decls(p.flatMap { it.interfaces }.distinct(), p.flatMap { it.methods }, p.flatMap { it.fields })
        }.filterValues { !it.isEmpty }
    }

    /**
     * target -> the mixins listed under it, copied out from under the processor's monitor.
     *
     * The monitor is needed because `applyMixins` is synchronized on it and mutates both `configs` (an
     * ArrayList, sorted in place — a concurrent walk skips entries and says nothing) and each `mixinMapping`.
     *
     * Nothing else may run under it. Class loading here goes class-lock -> the launch plugin's `processors`
     * -> the processor, so holding the processor and resolving any not-yet-touched class inverts that against
     * the render thread and deadlocks. A first touch is invisible in the source, hence the throwaway unlocked
     * pass: it resolves everything the locked one would have, over the same bytecode and the same branches.
     * Its failure is NOT caught — a pass that did not finish resolved only part of the path, and taking the
     * monitor after that is the deadlock. [derive] turns the throw into "no overlay", which costs a cast.
     */
    private fun snapshot(processor: Any): Map<String, List<Any>> {
        val copy = {
            val out = LinkedHashMap<String, MutableList<Any>>()
            for (config in field(processor, "configs") as List<*>) {
                if (config != null) {
                    @Suppress("UNCHECKED_CAST")
                    val mapping = field(config, "mixinMapping") as Map<String, List<Any>>
                    for ((target, infos) in mapping) out.getOrPut(target) { ArrayList() }.addAll(infos)
                }
            }
            out
        }
        copy()
        return synchronized(processor) { copy() }
    }

    /**
     * What ONE mixin adds to whichever target it was listed under. [ifaces] is Mixin's own answer; the members
     * are its own declarations that survive [adds] — none for an accessor mixin, whose `@Accessor`/`@Invoker`
     * are reached through [ifaces] instead.
     *
     * [renamed] are the members Mixin conformed to a different name on the target — an injector handler, an
     * `@Implements` prefix stripped off, a non-public `@Unique`. It renames only what no source could name, so
     * the declared name is one nothing carries. Recorded during preprocessing, so a target that has not been
     * applied yet contributes none of these and the annotation rules in [adds] stand alone.
     */
    private fun contribution(node: ClassNode, ifaces: Collection<String>, renamed: Set<String>, notAdded: Set<String>): Decls = Decls(
        ifaces.toList(),
        node.methods
            .filter { it.name != "<init>" && it.name != "<clinit>" && it.name !in renamed }
            .filter { adds(it.access, it.desc, notAdded, it.visibleAnnotations, it.invisibleAnnotations) }
            .map { Member(it.name, it.desc, it.access) },
        node.fields
            .filter { adds(it.access, null, notAdded, it.visibleAnnotations, it.invisibleAnnotations) }
            .map { Member(it.name, it.desc, it.access) },
    )

    /**
     * Whether a mixin's own member is an ADDITION the target then carries under this name.
     *
     * Public only: everything below it is renamed when `@Unique` and out of a snippet's reach either way.
     * [desc] is a method descriptor, or null for a field — a trailing `CallbackInfo` is a handler by
     * construction, and that is the backstop for one whose annotation did not survive its own build (Quark
     * ships such a method; it lands renamed like every other handler).
     *
     * Third-party annotations are NOT disqualifying and must not be — `@Nullable` and a mod's own markers sit
     * on merged members freely. Only what Mixin itself reads decides how a member merges.
     */
    private fun adds(access: Int, desc: String?, notAdded: Set<String>, vararg annotations: List<AnnotationNode>?): Boolean {
        if (access and Opcodes.ACC_PUBLIC == 0 || access and Opcodes.ACC_SYNTHETIC != 0) return false
        if (desc != null && CALLBACK.any { desc.contains(it) }) return false
        return annotations.asSequence().filterNotNull().flatten().none { it.desc in notAdded }
    }

    /**
     * Annotation descriptors that mark a member as something other than a plain addition.
     *
     * The injector half is ASKED of Mixin, not listed: `InjectionInfo` holds the registry every injector
     * registers itself into. That covers MixinExtras' whole surface, and injector annotations a single mod
     * declares for itself — which no list written here could have known about.
     *
     * [CORE_NOT_ADDED] is the rest: Mixin's own vocabulary for what a member IS, which is the language of
     * mixins rather than of injectors and does not grow with third parties.
     *
     * Throws rather than degrades if the registry cannot be read — [derive] turns that into "no overlay",
     * which is the safe answer: a member wrongly declared here compiles and then fails at runtime, while a
     * missing one only costs a cast.
     */
    private fun disqualifying(): Set<String> {
        val registered = Class.forName("org.spongepowered.asm.mixin.injection.struct.InjectionInfo", false, Constants.GAME_LOADER)
            .getMethod("getRegisteredAnnotations").invoke(null) as Set<*>
        return registered.filterIsInstance<Class<*>>()
            .mapTo(HashSet(CORE_NOT_ADDED)) { "L${it.name.replace('.', '/')};" }
    }

    private val CORE_NOT_ADDED = setOf(
        "Lorg/spongepowered/asm/mixin/Shadow;", // already on the target
        "Lorg/spongepowered/asm/mixin/Overwrite;", // replaces, does not add
        "Lorg/spongepowered/asm/mixin/Intrinsic;", // merged only where the target lacks it
        "Lorg/spongepowered/asm/mixin/gen/Accessor;", // reached through the interface Mixin adds alongside
        "Lorg/spongepowered/asm/mixin/gen/Invoker;",
    )

    private val CALLBACK = listOf(
        "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
        "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;",
    )

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
