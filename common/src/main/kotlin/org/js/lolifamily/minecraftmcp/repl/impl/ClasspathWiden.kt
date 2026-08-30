package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.config.LanguageVersion
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.repl.MixinProbe
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.moduleName
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility

// ============================================================================================
// Access widening (JVM ACC flags) for the COMPILE classpath: everything here edits OTHER people's class
// files so the K2 frontend will let a snippet reach their non-public members. Entry point is
// widenClasspath, called from assembleCompileClasspath.
//
// Kotlin `internal` is NOT opened here — PlainEngine makes every classpath jar a friend module instead. The
// snippet's OWN bytecode is rewritten for the JVM in weaveClasses.
// ============================================================================================

/** Whether a given jar's bytes get renamed at all, as opposed to the rename TABLE. Per jar because
 *  mc-symbols is already mojmap: the table would find nothing to map in it, but only after parsing every
 *  method body and rebuilding every constant pool. */
internal typealias RenameFor = (File) -> OverlayRename?

/** Widen compile classpath: every .class needing an access flag change goes into a STORED (uncompressed)
 *  shard — one per user-installed jar, one for everything else — and the shards are prepended in classpath
 *  order, so the compiler finds widened versions first and untouched classes fall through to the original
 *  jars. Names are deduplicated within a shard, never across them: shards win in classpath order, exactly
 *  as the jars behind them do.
 *
 *  This opens JVM private/protected only. Kotlin `internal` needs friend module data instead, which
 *  PlainEngine builds over the whole classpath. */
internal fun widenClasspath(entries: List<CpEntry>, pinned: LanguageVersion?): List<File> {
    val files = entries.map { it.file }
    val dir = Services.PLATFORM.cacheDir.resolve("overlay").toFile()
    dir.mkdirs()
    val mixinOverlay = File(dir, "mixin-overlay.jar")
    val shards = shardsOf(entries)
    val wantEnv = envRows(shards.filterIsInstance<Shard.Env>().firstOrNull()?.members.orEmpty(), pinned)
    val shardPlan = resolveShards(dir, shards, wantEnv)
    // !available() tells a deleted mixin jar from a platform that never builds one.
    if (shardPlan.stale.isEmpty() && (mixinOverlay.isFile || !MixinProbe.available())) {
        // The quiet path says so out loud: it opens no zip and writes nothing, so with no line of its own a
        // working cache and a pass that never ran look identical in the log.
        Constants.LOG.info("[mcp-aw] {} shard(s) reused, nothing rebuilt", shards.size)
        return listOfNotNull(mixinOverlay.takeIf { it.isFile }) + shardPlan.out.values + files
    }

    // Past the hit check: a hit must not pay for parsing the mappings.
    val rename = classRename()
    // Namespace is a property of each ENTRY. mc-symbols.jar is already mojmap — the artifact generated FROM
    // these mappings — so renaming it again mistranslates the names that exist in BOTH namespaces meaning
    // different classes (Registry, Fluid, MobEffect, WorldData, ...). Every other jar spells MC the runtime
    // way and does need it. Asked of the bundle, which produced it, not sniffed from its bytes.
    val alreadyNamed = RemapBundle.current()?.symbolsJar?.toFile()?.canonicalFile
    val renameFor: RenameFor = { f -> if (f.canonicalFile == alreadyNamed) null else rename }
    // Jars that would not open. Per file rather than one flag: only the shards carrying one are held back
    // from the stamp, so the rest still cache and the next launch retries just those.
    val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    // Every jar, not just the stale ones: the mixin pass groups by nest across the whole classpath, and it
    // rebuilds whenever anything does — nothing describes its contents but the live Mixin state, so no stamp
    // can vouch for it. Enumeration reads central directories only, and the shards need their slice anyway.
    val jarEntries = widenTargets(files, renameFor, failed)
    val mixin = mixinOverlayPlan(dedupeEntries(jarEntries), rename, renameFor)

    var written = 0
    val withMixin = alongsideMixinOverlay(mixin, mixinOverlay) {
        written = buildShards(shardPlan.stale, shardPlan.out, OverlayBuild(jarEntries, renameFor, pinned, failed))
    }

    sweepStale(dir, publishStamps(dir, shards, shardPlan.out, failed, wantEnv) + mixinOverlay.name)
    Constants.LOG.info(
        "[mcp-aw] {} of {} shard(s) rebuilt: {} classes{}",
        shardPlan.stale.size, shards.size, written,
        rename?.let { " (renamed against ${it.size} runtime class names)" }.orEmpty(),
    )
    return listOfNotNull(mixinOverlay.takeIf { withMixin }) + shardPlan.out.values.filter { it.isFile } + files
}

/** jar -> the entry names the overlay carries for it, in classpath order, NOT deduplicated — see
 *  [dedupeEntries] for why that is the caller's call. Jars contributing nothing are absent. A jar that fails
 *  to open contributes nothing, is logged, and lands in [failed], which holds back the stamp of whichever
 *  shard was carrying it so the next launch retries that one alone.
 *
 *  Membership is decided by NAME, before a class byte is read — that is what keeps the build one streaming
 *  pass. So every shouldWiden class is carried, widened or original bytes alike. Filtering by OUTCOME instead
 *  ("drop what widening did not change") is not a per-class call: an unchanged nested member left behind
 *  splits its nest group across two classpath units, and the K2 library session rejects that. Doing it right
 *  needs group-aware buffering, and what that would save — unchanged AND without nest siblings — is a small
 *  enough slice of the overlay to leave on the table.
 *
 *  [renameFor] drops the classes it renames: nothing in a renamed overlay refers to them under those names, so
 *  carrying a second view of the same 7k classes is dead weight. They stay resolvable behind the overlay for
 *  source that spells one out. Per-jar, because a jar already IN the target namespace is not renamed at all —
 *  its names are not the ones that move, however much the table's keys look like them. */
private fun widenTargets(files: List<File>, renameFor: RenameFor, failed: MutableSet<File>): Map<File, List<String>> {
    val targets = LinkedHashMap<File, List<String>>()
    for (f in files) {
        if (!f.isFile || !f.name.endsWith(".jar")) continue
        val rename = renameFor(f)
        val names = ArrayList<String>()
        try {
            java.util.zip.ZipFile(f).use { zf ->
                zf.entries().asSequence().filter { e ->
                    e.name.endsWith(".class") &&
                        e.size >= 0 &&
                        e.name.removeSuffix(".class").let { n ->
                            rename?.renames(n) != true && AccessWideningVisitor.shouldWiden(n)
                        }
                }.mapTo(names) { it.name }
            }
        } catch (t: Throwable) {
            failed += f
            Constants.LOG.warn("[mcp-aw] enumerate {} failed: {}", f.name, "$t")
        }
        if (names.isNotEmpty()) targets[f] = names
    }
    return targets
}

/** Keep the first jar to claim each entry name and drop the rest, in the map's (classpath) order.
 *
 *  Applied per SHARD, not once over the whole classpath: a shard is written as one jar, so two members
 *  claiming one name would collide inside it, while two SHARDS carrying the same name is exactly how the
 *  overlay already works — they sit in classpath order and the first wins, same as the jars behind them.
 *  The mixin pass dedupes globally instead, since it writes a single jar spanning every shard. */
internal fun dedupeEntries(targets: Map<File, List<String>>): Map<File, List<String>> {
    val seen = HashSet<String>()
    return targets.mapValues { (_, names) -> names.filter { seen.add(it) } }.filterValues { it.isNotEmpty() }
}

/** The overlay's bytes for one class, for every producer of it — the shard writer and the mixin graft alike.
 *
 *  Renames only what actually names a Minecraft class: the scan is a memcmp against the parse it saves,
 *  and it keeps every MC-free class (and every class on a non-renaming runtime) on the copy path. Total by
 *  design — a class that needs no change, or that cannot be parsed, comes back as its own bytes, so a caller
 *  never has to decide what "no result" means. */
internal fun widenClassFile(buf: ByteArray, len: Int, rename: OverlayRename?, parts: OverlayParts? = null): ByteArray {
    val rn = rename?.takeIf { mentionsMinecraft(buf, len) }
    return (try { widenPass(buf, len, rn, parts) } catch (_: Throwable) { null }) ?: buf.copyOf(len)
}

/** Read everything a signature needs and nothing a body does — the flags for every pass here that only edits
 *  declarations. */
internal const val DECLARATIONS_ONLY = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES

/** The ACC edit itself, shared with the members the mixin graft appends — those never pass through
 *  [widenClassFile], since they are added after it. Anything not public -> public (package-private too: a
 *  snippet never shares the target's runtime package), no final removal: the REPL needs access, not
 *  inheritance. */
internal fun widenAccess(access: Int): Int = if (access and Opcodes.ACC_PUBLIC != 0) {
    access
} else {
    (access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()) or Opcodes.ACC_PUBLIC
}

/** Widen a single class file so the REPL can reach non-public members. Two coordinated edits, one pass:
 *
 *   1. JVM ACC flags: anything not public → public (no final removal — REPL needs access, not inheritance).
 *      This is what the K2 frontend reads for JAVA classes (Minecraft), and it's what the runtime link
 *      would need too (though runtime actually goes through the indy AccessBridge, which ignores access).
 *   2. `@kotlin.Metadata` visibility: for KOTLIN classes the frontend reads member visibility from the
 *      metadata proto (DeserializedClassDescriptor / MemberDeserializer: `Flags.VISIBILITY.get(...)`), NOT
 *      from ACC flags — so an ACC-only widen leaves Kotlin `private`/`protected` still closed. flipping the
 *      proto (private/protected/private-to-this → public; `internal` is handled by PlainEngine's friend
 *      weave, `public` untouched) is the only lever. See rewriteMetadata.
 *
 *  A non-null [rename] adds intermediary -> mojmap class names as a third edit, in bytecode and metadata alike.
 *  It costs the copy path (rebuilt constant pool), and with no raw-byte transfer what is not parsed is not
 *  written — so CODE (Kotlin's inliner reads inline bodies from the class file) and FRAMES (they name types)
 *  must be read.
 *
 *  Returns widened bytes if anything changed, null if the class was already fully open (skip writing).
 *  ClassWriter(cr, 0) copy path when not renaming. Accepts a shared read buffer to avoid per-class allocation. */
private fun widenPass(buf: ByteArray, len: Int, rename: OverlayRename?, parts: OverlayParts?): ByteArray? {
    val cr = ClassReader(buf, 0, len)
    val cw = if (rename == null) ClassWriter(cr, 0) else ClassWriter(0)
    val readFlags = if (rename == null) DECLARATIONS_ONLY else ClassReader.SKIP_DEBUG
    val sink: ClassVisitor = if (rename == null) cw else ClassRemapper(cw, rename)
    // Renamed classes are always rewritten — the scan that selected it already saw an intermediary name.
    var changed = rename != null

    fun widen(access: Int): Int {
        val opened = widenAccess(access)
        if (opened != access) changed = true
        return opened
    }

    // @kotlin.Metadata is captured (NOT forwarded) and re-emitted at visitEnd — flipped for Kotlin
    // classes/file-facades/multifile-parts, verbatim otherwise. Deferred because a flip REPLACES the
    // annotation, and once ASM forwards it to the ClassWriter it can't be retracted. Java classes carry
    // no @Metadata, so `meta` stays null and their path is the ACC-only widen (one desc compare/anno).
    var meta: MetaCapture? = null
    var owner: String? = null

    @Suppress("ktlint:standard:wrapping")
    cr.accept(object : ClassVisitor(Opcodes.ASM9, sink) {
        override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, ifaces: Array<String>?) {
            owner = name
            super.visit(v, widen(access), name, sig, superName, ifaces)
        }
        override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? =
            super.visitField(widen(access), name, desc, sig, value)
        override fun visitMethod(access: Int, name: String, desc: String, sig: String?, exc: Array<String>?): MethodVisitor? =
            super.visitMethod(widen(access), name, desc, sig, exc)
        override fun visitInnerClass(name: String, outer: String?, inner: String?, access: Int) =
            super.visitInnerClass(name, outer, inner, widen(access))
        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
            if (desc == "Lkotlin/Metadata;") return MetaCapture().also { meta = it }   // defer to visitEnd
            return super.visitAnnotation(desc, visible)                                 // other annos forward as-is
        }
        override fun visitEnd() {
            meta?.let { cap ->
                // Recorded before the flip: the module file maps packages to facades, which a visibility
                // rewrite never changes. See OverlayParts for why we ship our own.
                owner?.let { parts?.record(it, cap.kind, cap.original("xs") as? String) }
                // Kind gate (perf): only Class(1)/FileFacade(2)/MultiFileClassPart(5) hold openable member
                // visibilities. Skip decoding SyntheticClass(3, every lambda — the numerous kind) and
                // MultiFileClassFacade(4); their metadata is re-emitted verbatim. try/catch: metadata newer
                // than this lib (or older than 1.4) throws on read/write — leave that class closed (no
                // regression), same best-effort posture as the ACC widen.
                val flipped = if (cap.kind == 1 || cap.kind == 2 || cap.kind == 5) {
                    // orEmpty only satisfies the type: ASM runs visit() before visitEnd(), so owner is set.
                    try { rewriteMetadata(cap, owner.orEmpty(), rename) } catch (_: Throwable) { null }
                } else {
                    null
                }
                cap.emit(super.visitAnnotation("Lkotlin/Metadata;", true), flipped)
                if (flipped != null) changed = true
            }
            super.visitEnd()
        }
        // Read flags: without a rename we only touch access flags + the class-level @Metadata, and bodies /
        // debug tables / frames are copied verbatim by the ClassWriter(cr,0) path, so skipping their parse is
        // pure savings. Renaming forfeits that — see the KDoc.
    }, readFlags)
    return if (changed) cw.toByteArray() else null
}

/** What a widen opens: `internal` is PlainEngine's friend-all job, `public` needs nothing. */
private val OPENABLE = java.util.EnumSet.of(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS, Visibility.PROTECTED)

/** Verdict per module name — what to emit for it, with the key itself meaning "already aligned". The answer
 *  is a property of the jar, not of the class, so the member scan below runs once per module rather than on
 *  each of the thousands of classes that share one. */
private val ALIGNMENT = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * Realign `moduleName` with the `$suffix` this declaration's own internal members actually carry.
 *
 * A call to an internal member is mangled `name$sanitize(moduleName)`, and the compiler reads moduleName from
 * the metadata proto — so a jar whose published member names were renamed without updating that field links
 * against a method that does not exist, and it fails at the call site rather than at compile time. The
 * JvmMethodSignature is renamed correctly, which makes it the reference. Aligned jars stop at the comparison;
 * as of 2.4.10 only kotlin-metadata-jvm needs the rewrite.
 */
private fun alignModuleName(current: String?, fns: List<KmFunction>, props: List<KmProperty>, set: (String) -> Unit): Boolean {
    // `NameUtils.sanitizeAsJavaIdentifier`'s rule: every char that is not a letter or digit becomes `_`.
    fun sanitize(module: String): String {
        val sb = StringBuilder(module.length)
        for (c in module) sb.append(if (c.isLetterOrDigit()) c else '_')
        return sb.toString()
    }
    if (current == null) return false
    val cached = ALIGNMENT[current]
    if (cached != null) {
        if (cached == current) return false
        set(cached)
        return true
    }
    // One mangled member is enough — every declaration in a class shares the class's module. A class that has
    // none is not cached: the next one sharing this module may still carry the answer.
    val actual = (
        fns.asSequence().filter { it.visibility == Visibility.INTERNAL }.mapNotNull { it.signature?.name } +
            props.asSequence().filter { it.visibility == Visibility.INTERNAL }.mapNotNull { it.getterSignature?.name }
        ).firstNotNullOfOrNull { it.substringAfterLast('$', "").ifEmpty { null } } ?: return false
    val verdict = if (sanitize(current) == actual) current else actual
    ALIGNMENT[current] = verdict
    if (verdict == current) return false
    set(verdict)                                          // already a valid identifier; sanitize leaves it alone
    return true
}

/** Run [open] over every visibility a Kotlin package or class body carries. */
private fun openMembers(fns: List<KmFunction>, props: List<KmProperty>, tas: List<KmTypeAlias>, open: (Visibility) -> Visibility) {
    fns.forEach { it.visibility = open(it.visibility) }
    props.forEach { p ->
        p.visibility = open(p.visibility)
        p.getter.visibility = open(p.getter.visibility)          // getter/setter visibilities are
        p.setter?.let { it.visibility = open(it.visibility) }    // stored separately — open them too
    }
    tas.forEach { it.visibility = open(it.visibility) }
}

/** Open + realign + rename one file facade or multi-file part; returns whether [alignModuleName] changed it.
 *  The two metadata kinds carry the same [KmPackage] body and differ only in which case matched. [owner] is the
 *  facade class, which a [KmPackage] does not carry and [KmRemap] needs to look its members up by. */
private fun openPackage(p: KmPackage, owner: String, open: (Visibility) -> Visibility, rename: OverlayRename?): Boolean {
    openMembers(p.functions, p.properties, p.typeAliases, open)
    val aligned = alignModuleName(p.moduleName, p.functions, p.properties) { p.moduleName = it }
    rename?.let { KmRemap(it).rename(p, owner) }
    return aligned
}

/** Open private/protected/private-to-this → public in a Kotlin class's `@Metadata` proto, realign its
 *  moduleName (see [alignModuleName]), and — when [rename] is set — rewrite its runtime classifiers to
 *  mojmap so the proto agrees with the bytecode ClassRemapper just rewrote. Returns the fields to re-emit if
 *  anything changed, null otherwise. Uses kotlin-metadata-jvm's STRICT read: the lenient read forbids
 *  write-back, and strict read/write throw for metadata older than 1.4 or newer than this lib + 1 minor —
 *  caught by the caller, leaving that class closed. */
private fun rewriteMetadata(cap: MetaCapture, owner: String, rename: OverlayRename?): Map<String, Any?>? {
    var opened = false
    var aligned = false
    val open: (Visibility) -> Visibility = { v ->
        if (v in OPENABLE) { opened = true; Visibility.PUBLIC } else v
    }
    val parsed = KotlinClassMetadata.readStrict(cap.toMetadata())
    when (parsed) {
        is KotlinClassMetadata.Class -> parsed.kmClass.let { c ->
            c.visibility = open(c.visibility)
            c.constructors.forEach { it.visibility = open(it.visibility) }
            openMembers(c.functions, c.properties, c.typeAliases, open)
            aligned = alignModuleName(c.moduleName, c.functions, c.properties) { c.moduleName = it }
            rename?.let { KmRemap(it).rename(c) }
        }
        is KotlinClassMetadata.FileFacade -> aligned = openPackage(parsed.kmPackage, owner, open, rename)
        is KotlinClassMetadata.MultiFileClassPart -> aligned = openPackage(parsed.kmPackage, owner, open, rename)
        else -> return null   // SyntheticClass / MultiFileClassFacade / Unknown — nothing openable
    }
    if (!opened && !aligned && rename == null) return null
    val written = parsed.write()   // hoisted: write() re-encodes the whole proto, and the fold below is per FIELD
    return cap.fieldNames().associateWith { written.fieldOr(it, cap.original(it)) }
}
