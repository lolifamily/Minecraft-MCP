package org.js.lolifamily.minecraftmcp.repl.impl

import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.MixinProbe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// ============================================================================================
// The interfaces and members Mixin merges into its targets, put on the COMPILE classpath. None of it exists
// in the jars on disk — it is produced at class-load — so without this a snippet cannot see IForgeEntity on
// Entity or getAttached on Level, and has to cast to reach them.
//
// Entry point is mixinOverlayPlan, called from widenClasspath; the plan is written as one jar prepended
// ahead of the shards.
//
// Mostly it is one entry in a target's `implements`: what an added interface declares already lives on that
// interface's own class file, which is an ordinary classpath entry. Members are grafted only where a mixin
// added them with no interface to reach them through. What Mixin RENAMES on merge never appears here at all
// — see MixinProbe, which decides that and hands this plain data.
// ============================================================================================

/** What the mixin overlay will contain, resolved before any jar is written so this one builds alongside
 *  the shards. */
internal class MixinPlan(
    /** Entry names this jar owns, always whole nest groups. The shards carry them too and that is fine — this
     *  jar sits ahead of them in classpath order and wins, exactly as the overlay already wins over the source
     *  jars behind it. What K2 rejects is a nest group SPLIT across two units, never a duplicated one. */
    val claimed: Set<String>,
    // jar -> [(entry, mixin target name, or null for a nestmate carried along)]
    private val work: Map<File, List<Pair<String, String?>>>,
    /** Internal names the compile classpath carries. A grafted `implements` naming anything else makes the
     *  TARGET unusable — "cannot access X which is a supertype of Y" — and a runtime-synthesized mixin's
     *  interface is in no jar. */
    private val present: Set<String>,
    /** The rename as a TABLE: reading Mixin's intermediary answers back into mojmap. Global by nature — it
     *  translates names, and a name means the same thing whichever jar it was read from. */
    private val rename: OverlayRename?,
    /** The rename as a DECISION: whether this jar's bytes get rewritten at all. Per jar, because mc-symbols is
     *  already mojmap — [rename] would find nothing to map in it, but only after parsing every method body and
     *  rebuilding every constant pool. 79% of what this overlay grafts comes from that one jar. */
    private val renameFor: RenameFor,
) {
    val size: Int get() = claimed.size

    /** Graft each target's added declarations onto its own source bytes and write the result to [tmp].
     *  Returns the number of classes written. */
    fun build(tmp: Path): Int {
        var written = 0
        JarOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp.toFile()), 1 shl 16)).use { jos ->
            for ((jar, items) in work) written += graftJar(jar, items, jos)
        }
        return written
    }

    /** A failing jar costs only its own targets — what it already wrote stays, and the caller's count reports
     *  the shortfall against the plan. */
    private fun graftJar(jar: File, items: List<Pair<String, String?>>, jos: JarOutputStream): Int = try {
        ZipFile(jar).use { zf -> graftEntries(zf, renameFor(jar), items, jos) }
    } catch (t: Throwable) {
        Constants.LOG.warn("[mcp-aw/mixin] {} skipped: {}", jar.name, "$t")
        0
    }

    private fun graftEntries(zf: ZipFile, own: OverlayRename?, items: List<Pair<String, String?>>, jos: JarOutputStream): Int {
        var written = 0
        for ((entry, target) in items) {
            val base = zf.getEntry(entry)?.let { e -> zf.getInputStream(e).use { it.readAllBytes() } } ?: continue
            writeStored(jos, entry, rebuild(base, target, own))
            written++
        }
        return written
    }

    private fun writeStored(jos: JarOutputStream, entry: String, bytes: ByteArray) {
        val je = JarEntry(entry)
        je.method = ZipEntry.STORED
        je.size = bytes.size.toLong()
        je.compressedSize = bytes.size.toLong()
        je.crc = CRC32().apply { update(bytes) }.value
        jos.putNextEntry(je); jos.write(bytes); jos.closeEntry()
    }

    /** Every claimed entry is emitted, grafted or not: a nest group arriving half from here and half from the
     *  shard behind us is the split K2 rejects. The widen matches the shard's, so a duplicate costs its bytes
     *  and nothing else. */
    private fun rebuild(base: ByteArray, target: String?, own: OverlayRename?): ByteArray {
        val widened = widenClassFile(base, base.size, own)
        val added = target?.let { MixinProbe.addedFor(it) } ?: return widened
        val grafted = emitGraft(widened, added, added.interfaces)
        // Second pass only for a class that shadowed something, which the graft already had to notice: one in
        // this pack's 2639. Anything cheaper than "ask the class first" would pay to read interfaces it never
        // needed — the interfaces are the only place left that can still name what the class no longer can.
        if (grafted.shadowed.isEmpty()) return grafted.bytes
        val keep = added.interfaces.filterNot { declaresAny(it, grafted.shadowed) }
        if (keep.size == added.interfaces.size) return grafted.bytes
        return emitGraft(widened, added, keep).bytes
    }

    /** Whether [iface] or anything it extends declares one of [keys], as [emitGraft] keys them. */
    private fun declaresAny(iface: String, keys: Set<String>): Boolean {
        val seen = HashSet<String>()
        fun walk(name: String): Boolean {
            if (!seen.add(name)) return false
            val d = MixinProbe.declarationsOf(name.replace('/', '.')) ?: return false
            for (m in d.methods) {
                if (m.access and Opcodes.ACC_STATIC != 0) continue
                val desc = rename?.mapMethodDesc(m.desc) ?: m.desc
                if (m.name + desc.substringBefore(')') in keys) return true
            }
            return d.interfaces.any { walk(it) }
        }
        return walk(iface)
    }

    /** Re-emit [base] with the added declarations appended, opened by [revealAccess] — they are added after
     *  [widenClassFile] ran, so nothing else would open them.
     *  Descriptors come from the intermediary side and are renamed on the way in; bodies are
     *  `aconst_null; athrow`, since this jar is only ever compiled against.
     *
     *  A graft may land on a name the class already carries — Mixin merges Forge-compat and fluent-wrapper
     *  methods under names the rename also produces. Both maps below are seeded from [base], i.e. AFTER the
     *  rename, since that is the only namespace the two sides share; the base seeds them first, so an added
     *  member never takes a name off the class it was grafted onto. Added members are walked open-first for
     *  the same reason — between two grafts, the one already visible keeps the name. */
    private fun emitGraft(base: ByteArray, added: MixinProbe.Decls, interfaces: List<String>): Graft {
        val cr = ClassReader(base)
        val cw = ClassWriter(cr, 0)
        val shadowed = HashSet<String>()
        cr.accept(
            object : ClassVisitor(Opcodes.ASM9, cw) {
                /** name + descriptor: already declared, so re-emitting it would only duplicate the member. */
                private val emitted = HashSet<String>()

                /** What a source caller can write -> what it returns. Key is name + parameters for a method,
                 *  name alone for a field; a second member on one key is one Kotlin cannot be told to pick. */
                private val nameable = HashMap<String, String>()

                override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, its: Array<String>?) {
                    val had = its?.asList().orEmpty()
                    // ...or opaque, which [present] omits by construction while the classpath carries it.
                    val added = interfaces.map { rename?.map(it) ?: it }.distinct().filterNot { it in had }
                        .filter { it in present || !AccessWideningVisitor.shouldWiden(it) }
                    // The signature carries the supertypes too, and a compiler reads THAT where it exists —
                    // the interfaces array alone is only what the JVM links against. Leave it untouched and
                    // the graft is invisible to source. What mixins add takes no type arguments, so each one
                    // appends as a bare `L...;`.
                    val grown = if (sig == null || added.isEmpty()) sig else sig + added.joinToString("") { "L$it;" }
                    super.visit(v, access, name, grown, superName, (had + added).toTypedArray())
                }

                override fun visitField(access: Int, n: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                    emitted += n + desc
                    nameable[n] = desc
                    return super.visitField(access, n, desc, sig, value)
                }

                override fun visitMethod(access: Int, n: String, desc: String, sig: String?, exc: Array<String>?): MethodVisitor? {
                    emitted += n + desc
                    nameable[n + desc.substringBefore(')')] = Type.getReturnType(desc).descriptor
                    return super.visitMethod(access, n, desc, sig, exc)
                }

                override fun visitEnd() {
                    for (f in added.fields.sortedBy { it.access and Opcodes.ACC_SYNTHETIC }) {
                        val mapped = rename?.mapDesc(f.desc) ?: f.desc
                        if (!emitted.add(f.name + mapped)) continue
                        val open = revealAccess(f.access, nameable.put(f.name, mapped) != null)
                        cw.visitField(open, f.name, mapped, null, null)?.visitEnd()
                    }
                    for (m in added.methods.sortedBy { it.access and Opcodes.ACC_SYNTHETIC }) {
                        val mapped = rename?.mapMethodDesc(m.desc) ?: m.desc
                        if (!emitted.add(m.name + mapped)) continue
                        val key = m.name + mapped.substringBefore(')')
                        val ret = Type.getReturnType(mapped).descriptor
                        val held = nameable.put(key, ret)
                        // Closing this one is enough unless K2 keeps both as distinct members, and then only an
                        // interface can still be carrying the name it just took.
                        if (held != null && !oneMemberToK2(held, ret)) shadowed += key
                        val open = revealAccess(m.access, held != null)
                        val mv = cw.visitMethod(open, m.name, mapped, null, null) ?: continue
                        if (open and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0) {
                            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ATHROW); mv.visitMaxs(1, 0)
                        }
                        mv.visitEnd()
                    }
                    super.visitEnd()
                }
            },
            DECLARATIONS_ONLY,
        )
        return Graft(cw.toByteArray(), shadowed)
    }
}

/** One class's grafted bytes, plus the keys an added member took off a member already on the class. */
private class Graft(val bytes: ByteArray, val shadowed: Set<String>)

/** Whether K2 folds two same-name same-parameter members into one, which is the only reason closing the
 *  class-side member is enough on its own. Two reference types read as a covariant override and fold; a
 *  primitive or `void` against anything is two Kotlin types and does not. A primitive against its own box
 *  folds too — Kotlin has one type for the pair — but nothing reaches here that way: Mixin puts the boxed
 *  member on the INTERFACE as a default, and a default is not copied onto the class, so no key collides. */
private fun oneMemberToK2(a: String, b: String) = a == b || (a.isReference() && b.isReference())

/** Reference, i.e. object or array — [Type.ARRAY] and [Type.OBJECT] are the two sorts above every primitive. */
private fun String.isReference() = Type.getType(this).sort >= Type.ARRAY

/** [widenAccess], plus ACC_SYNTHETIC made to mean exactly one thing on a grafted member: source cannot name
 *  it — which is what K2 reads it as when it drops the member from a Java use-site scope. Mixin sets the flag
 *  on the `@Accessor`/`@Invoker` bodies it generates and not on what it merges from mixin source, and neither
 *  tracks whether the name survives the rename, so the graft decides it here instead of inheriting it. An
 *  [ambiguous] member is still reachable through the interface Mixin grafts alongside it; the base member it
 *  collided with has no such second name, which is why that one keeps it. */
private fun revealAccess(access: Int, ambiguous: Boolean): Int {
    val open = widenAccess(access)
    return if (ambiguous) open or Opcodes.ACC_SYNTHETIC else open and Opcodes.ACC_SYNTHETIC.inv()
}

/**
 * Resolve what the mixin overlay would contain, or null when there is nothing to build.
 *
 * [jarEntries] is the enumeration the shards are about to write, so a target is claimed only if a shard was
 * going to carry it anyway — which also supplies the graft base: the mojmap stub for a Minecraft class, the
 * mod's own class file for everything else. See [MixinPlan] on why both rename forms are needed.
 */
internal fun mixinOverlayPlan(jarEntries: Map<File, List<String>>, rename: OverlayRename?, renameFor: RenameFor): MixinPlan? {
    if (!MixinProbe.available()) return null
    val targets = MixinProbe.targets()
    val reverse = rename?.reverse.orEmpty()

    // Mixin only answers to intermediary names; the overlay entry is already renamed. Non-Minecraft classes
    // are absent from the table and pass through, which is the identity they need.
    fun targetOf(entry: String): String? {
        val internal = entry.removeSuffix(".class")
        return (reverse[internal] ?: internal).replace('/', '.').takeIf { it in targets }
    }
    // Claim by nest group, not by target: a target's outer class and nestmates come along even when they are
    // not targets themselves, so the group never lands half here and half in the main overlay.
    val nests = LinkedHashMap<String, MutableList<Pair<File, String>>>()
    for ((jar, entries) in jarEntries) {
        for (entry in entries) nests.getOrPut(entry.removeSuffix(".class").substringBefore('$')) { ArrayList() } += jar to entry
    }
    val claimed = HashSet<String>()
    val work = LinkedHashMap<File, MutableList<Pair<String, String?>>>()
    for (group in nests.values) {
        if (group.none { targetOf(it.second) != null }) continue
        for ((jar, entry) in group) {
            work.getOrPut(jar) { ArrayList() } += entry to targetOf(entry)
            claimed += entry
        }
    }
    if (claimed.isEmpty()) return null
    // Same enumeration the shards already did, so this costs no I/O.
    return MixinPlan(claimed, work, jarEntries.values.flatten().mapTo(HashSet()) { it.removeSuffix(".class") }, rename, renameFor)
}

/**
 * Run [main] with the mixin overlay building next to it, and report whether that overlay published.
 *
 * Its own thread because it is jar I/O and outlasts the shard pool, so it starts first and is joined after —
 * it touches no Mixin state, [MixinProbe] having resolved all of that to plain data before the plan existed.
 * A failure costs only the declarations: the answer is false and nothing stale is prepended, while the shards
 * still stamp — snippets just have to cast to reach what Mixin merged, until the next rebuild retries.
 */
internal fun alongsideMixinOverlay(plan: MixinPlan?, out: File, main: () -> Unit): Boolean {
    if (plan == null) {
        out.delete() // a stamped build must not leave one behind for a later cache hit to prepend
        main()
        return false
    }
    var grafted = 0
    var published = false
    var elapsed = 0L
    val wall = System.nanoTime()
    val worker = Thread({
        val started = System.nanoTime()
        try {
            AtomicFiles.publishing(out.toPath()) { tmp -> grafted = plan.build(tmp) }
            published = true
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-aw/mixin] overlay failed, retried on the next shard rebuild: {}", "$t")
        }
        elapsed = (System.nanoTime() - started) / 1_000_000
    }, "mcp-aw-mixin").apply {
        isDaemon = true; priority = Thread.NORM_PRIORITY - 2; contextClassLoader = Constants.GAME_LOADER; start()
    }
    main()
    worker.join()
    if (published) {
        // Both numbers: this runs beside the shard pool, so how much of it the main overlay hid is what
        // decides whether the feature costs any wall clock at all.
        Constants.LOG.info(
            "[mcp-aw/mixin] declaration overlay: {} class(es) over {} claimed entries, {}KB, {}ms of a {}ms wall",
            grafted, plan.size, out.length() / 1024, elapsed, (System.nanoTime() - wall) / 1_000_000,
        )
    }
    return published
}
