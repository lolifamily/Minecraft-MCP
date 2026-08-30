package org.js.lolifamily.minecraftmcp.repl.impl

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import org.js.lolifamily.minecraftmcp.repl.RemapCache
import org.js.lolifamily.minecraftmcp.repl.ReplBridge
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Masking-loader side of the first-launch remap-bundle build. Lives in `repl.impl` so the masking loader owns
 * it (child-first) and it can link mapping-io + tiny-remapper, which sit ONLY on the masking urls (off the
 * game/module path). The game-loader side ([RemapCache]) does version detection + network download + JSON
 * parsing (MC's bundled Gson lives there); this side does the two operations that need those libraries,
 * reached from [ReplBridge] through the shared MaskingBridge adapter.
 *
 * Pipeline: pivot proguard(named<->official) and fabric intermediary(official<->intermediary) on the shared
 * official names (mapping-io's descriptor-aware merge — overload-safe), then reverse-remap the runtime MC jar
 * into a mojmap symbol jar (the compile classpath a production runtime otherwise lacks).
 */
object RemapCacheBuilder {

    /**
     * Assemble [outMappings] (tiny v2, named<->intermediary) from the downloaded [clientTxt] (Mojang proguard)
     * + [secondSource], then reverse-remap [runtimeMcUri] (the loader's view of the runtime jar) into
     * `<outSymbolsDir>/mc-symbols.jar` (mojmap names, for the Kotlin compiler). Callers gate on the outputs
     * already existing.
     *
     * [secondSource] carries the runtime's CLASS names, which proguard cannot: a fabric intermediary jar, or
     * a spigot `.csrg`, told apart by extension. Both runtimes move both axes, so both need one.
     */
    fun buildArtifacts(clientTxt: String, secondSource: String, runtimeMcUri: String, outMappings: String, outSymbolsDir: String) {
        val mappings = Paths.get(outMappings)
        mappings.parent?.let { Files.createDirectories(it) }
        val t0 = System.nanoTime()
        when {
            secondSource.endsWith(".csrg") -> assembleSpigot(Paths.get(clientTxt), Paths.get(secondSource), mappings)
            secondSource.isNotEmpty() -> assemble(Paths.get(clientTxt), Paths.get(secondSource), mappings)
            else -> error("buildArtifacts needs a second mapping source (fabric intermediary jar or spigot .csrg)")
        }
        val t1 = System.nanoTime()

        val symDir = File(outSymbolsDir)
        symDir.mkdirs()
        val symJar = File(symDir, RemapBundle.MC_SYMBOLS).toPath()
        reverseRemapJar(
            Paths.get(URI.create(runtimeMcUri)), symJar,
            TinyUtils.createTinyMappingProvider(mappings, "intermediary", "named"), false, "reverseRemap",
        )
        org.js.lolifamily.minecraftmcp.Constants.LOG.info(
            "[mcp-remap] symbol build split: assemble={}ms reverseRemapJar={}ms",
            (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000,
        )
        writeDeps(symJar)
    }

    /** named<->intermediary via official pivot (mapping-io merge), written as tiny v2 (src=named). */
    private fun assemble(proguard: Path, interV2: Path, out: Path) {
        // proguard: src=named-ish, dst=[official-ish]. Rename to canonical named/official for the merge.
        val pgRaw = MemoryMappingTree()
        MappingReader.read(proguard, MappingFormat.PROGUARD_FILE, pgRaw)
        val rename = hashMapOf(pgRaw.srcNamespace to "named", pgRaw.dstNamespaces[0] to "official")
        val pg = MemoryMappingTree()
        pgRaw.accept(MappingNsRenamer(pg, rename))
        // switch source named->official so it merges on official (the pivot)
        val pgOfficial = MemoryMappingTree()
        pg.accept(MappingSourceNsSwitch(pgOfficial, "official"))            // official -> [named]

        // intermediary jar carries mappings/mappings.tiny: src=official, dst=[intermediary]. Format auto-detected,
        // unlike the explicit reads elsewhere here — the entry has been tiny v1 and v2 across versions. Detection
        // needs mark/reset, which MappingReader arranges itself, so a bare InputStreamReader is enough.
        val tree = MemoryMappingTree()
        JarFile(interV2.toFile()).use { jf ->
            val entry = "mappings/mappings.tiny"
            val e = jf.getJarEntry(entry) ?: error("no $entry in $interV2")
            jf.getInputStream(e).reader().use { MappingReader.read(it, tree) }
        }

        // merge proguard(official->named) in: both src=official, matched by official name+desc (overload-safe)
        pgOfficial.accept(tree)                                            // official -> [intermediary, named]

        // switch source to named for output; mapping-io recomputes descriptors in named terms
        val named = MemoryMappingTree()
        tree.accept(MappingSourceNsSwitch(named, "named"))                 // named -> [intermediary, official]
        AtomicFiles.publishing(out) { tmp -> MappingWriter.create(tmp, MappingFormat.TINY_2_FILE).use { named.accept(it) } }
    }

    /**
     * Mojang proguard + BuildData's class csrg -> tiny v2 (src=named), pivoting on the obf names both sides
     * key on. A spigot runtime is fabric's shape, not forge's: BOTH axes move. Members keep the obf names
     * (spigot has published no `-members.csrg` since 1.17), so proguard alone carries that axis; classes wear
     * spigot's OWN names — `BlockPos` is `BlockPosition` — a rename invented by spigot and derivable from no
     * Mojang artifact, which is why the class axis needs [clCsrg].
     *
     * A TOP-LEVEL class with no csrg row was never renamed and keeps the name proguard's source column holds.
     * That covers spigot's `.exclude` list (datagen and friends) and every class Mojang never obfuscated,
     * `MinecraftServer` among them. A nested class is not like that — see the derivation below.
     *
     * The obf column is named "intermediary" because that string is this pipeline's slot for "the runtime
     * namespace" (Mappings.locateNamespaces, ScriptWeave) — not a claim that these are fabric names.
     */
    private fun assembleSpigot(proguard: Path, clCsrg: Path, out: Path) {
        val pgRaw = MemoryMappingTree()
        MappingReader.read(proguard, MappingFormat.PROGUARD_FILE, pgRaw)
        val tree = MemoryMappingTree()
        pgRaw.accept(MappingNsRenamer(tree, hashMapOf(pgRaw.srcNamespace to "named", pgRaw.dstNamespaces[0] to "intermediary")))

        val obf2spigot = readCsrgClasses(clCsrg)

        // The csrg lists a nested class only where spigot gave its INNER name a word of its own (`aal$a` ->
        // `CommandXp$Unit`, 292 rows against 2028 nested classes). Every other nested class inherits the outer
        // rename and keeps the obf inner name, so a miss has to be DERIVED, not read as "never renamed" — null
        // only when the outer was never renamed either, and there the fallback below is already the answer.
        fun spigot(obf: String): String? {
            obf2spigot[obf]?.let { return it }
            val cut = obf.lastIndexOf('$')
            if (cut < 0) return null
            return spigot(obf.substring(0, cut))?.plus(obf.substring(cut))
        }

        val interNs = tree.getNamespaceId("intermediary")
        for (c in tree.classes) {
            val named = c.getName(MappingTree.SRC_NAMESPACE_ID) ?: continue
            c.setDstName(c.getName(interNs)?.let { spigot(it) } ?: named, interNs)
        }

        AtomicFiles.publishing(out) { tmp -> MappingWriter.create(tmp, MappingFormat.TINY_2_FILE).use { tree.accept(it) } }
    }

    /** BuildData's class csrg: `<obf> <spigot-internal-name>` per line, '#' comments. Read straight into a
     *  lookup rather than through mapping-io, which would build a second tree for [assembleSpigot] to walk.
     *
     *  Exactly one space is what makes it a class row; member rows carry three or four columns. Not a guard
     *  against nothing: a field row admitted here would key on its CLASS and overwrite that class's mapping. */
    private fun readCsrgClasses(path: Path): Map<String, String> {
        val out = HashMap<String, String>(4096)
        Files.newBufferedReader(path).use { r ->
            while (true) {
                val line = (r.readLine() ?: break).trim()
                if (line.isEmpty() || line[0] == '#') continue
                val sp = line.indexOf(' ')
                if (sp > 0 && line.indexOf(' ', sp + 1) < 0) out[line.substring(0, sp)] = line.substring(sp + 1)
            }
        }
        return out
    }

    /** Whole-tree reverse remap into the compile-only mojmap symbol jar at [out]; shared by the fabric and forge
     *  branches, which differ only in [provider] and [ignoreFieldDesc] (TSRG2 fields carry no descriptor) —
     *  [tag] labels the timing log. [input] is a loader view, not a file, so it is the union's whole class tree
     *  and all supers resolve internally; ignoreConflicts absorbs forge-style override divergence. */
    private fun reverseRemapJar(input: Path, out: Path, provider: IMappingProvider, ignoreFieldDesc: Boolean, tag: String) {
        // Strip method BODIES before the remapper runs (see BodyStripper for why that's safe): remap then only
        // touches signatures, and the stub jar is faster to write here + cheaper for the overlay/index later.
        val tr = TinyRemapper.newRemapper().withMappings(provider)
            .ignoreConflicts(true).ignoreFieldDesc(ignoreFieldDesc)
            .extraPreApplyVisitor { _, next -> BodyStripper(next) }
            // Reserve 2 cores for the game: this build runs on a background thread DURING game startup, and
            // tiny-remapper's default (all cores) would contend with the game's load/mixin/render threads
            // (~3% slower than all-cores). Same "reserve 2" policy as preload + the overlay pool.
            .threads((Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1))
            .build()
        try {
            val tr0 = System.nanoTime()
            tr.readInputs(input)
            val tr1 = System.nanoTime()
            // Write STORED (uncompressed), NOT via OutputConsumerPath's fixed DEFLATE. This is a read-once
            // compile-classpath cache: DEFLATE is pure waste here, and STORED also skips inflate when the compiler
            // indexes it. Only remapped .class files (the compiler needs signatures, not the jar's non-class
            // resources — so no addNonClassFiles). apply() is parallel → the sink is called from many threads, so
            // serialize the JarOutputStream under a lock. Same STORED writer pattern as CompileClasspath.widenClasspath.
            val lock = Any()
            val crc = java.util.zip.CRC32()
            AtomicFiles.publishing(out) { tmp ->
                JarOutputStream(BufferedOutputStream(FileOutputStream(tmp.toFile()), 1 shl 16)).use { jos ->
                    jos.setMethod(java.util.zip.ZipEntry.STORED)
                    tr.apply { name, bytes ->
                        val e = JarEntry("$name.class")
                        e.method = java.util.zip.ZipEntry.STORED
                        e.size = bytes.size.toLong()
                        e.compressedSize = bytes.size.toLong()
                        synchronized(lock) {
                            crc.reset(); crc.update(bytes); e.crc = crc.value
                            jos.putNextEntry(e); jos.write(bytes); jos.closeEntry()
                        }
                    }
                }
            }
            org.js.lolifamily.minecraftmcp.Constants.LOG.info(
                "[mcp-remap]   {} split: readInputs={}ms apply+write(STORED,stub)={}ms",
                tag, (tr1 - tr0) / 1_000_000, (System.nanoTime() - tr1) / 1_000_000,
            )
        } finally {
            tr.finish()
        }
    }

    /**
     * Harvest the EXTERNAL types referenced by [symJar]'s class SIGNATURES and write them, one dotted class name
     * per line (sorted), to `<symJar dir>/deps.txt`.
     *
     * External means "not a class this jar defines" — the entry set, not a package prefix: MC is not all under
     * `net.minecraft` (`com.mojang.blaze3d`/`realmsclient`/`math` are MC too), and a name that slips through
     * resolves to the RUNTIME MC jar, putting a second intermediary-named Minecraft on the compile classpath.
     *
     * [symJar] is body-stripped ([BodyStripper]), so its constant pool holds only signature references — a raw
     * `CONSTANT_Class` scan therefore equals the signature closure, with NO `ClassVisitor`, no descriptor parsing
     * and no member walk. [ReplBridge] probes each name on the game loader to recover MC's API-dependency jars
     * (DFU/Brigadier/JOML/authlib/fastutil/...) that a production runtime's classpath enumeration misses. The
     * list is a superset of what a dedicated server needs (the symbol jar is client-mappings-derived):
     * client-only entries just fail to resolve there and are skipped. Published atomically so a half-written
     * list can never be seen as a cache hit.
     */
    private fun writeDeps(symJar: Path) {
        val names = java.util.TreeSet<String>()
        JarFile(symJar.toFile()).use { jf ->
            val own = HashSet<String>(1 shl 13)
            var en = jf.entries()
            while (en.hasMoreElements()) {
                val n = en.nextElement().name
                if (n.endsWith(".class")) own.add(n.removeSuffix(".class"))
            }
            en = jf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!e.name.endsWith(".class")) continue
                harvestClass(jf.getInputStream(e).use { it.readBytes() }, own, names)
            }
        }
        val out = symJar.resolveSibling(RemapBundle.DEPS_LIST)
        AtomicFiles.publishing(out) { tmp -> tmp.toFile().writeText(names.joinToString("\n")) }
        org.js.lolifamily.minecraftmcp.Constants.LOG.info("[mcp-remap] harvested {} API-dep probe(s) -> {}", names.size, out.fileName)
    }

    private const val CONSTANT_CLASS = 7

    /** The one thing the entry set cannot answer for: the JDK is on no classpath entry to recover. */
    private const val JDK_PREFIX = "java/"

    /** A `CONSTANT_Class` entry's internal name: `[[Lcom/foo/Bar;` and `Lcom/foo/Bar;` both -> `com/foo/Bar`. */
    private fun unwrapClassRef(raw: String): String {
        val lb = raw.lastIndexOf('[')
        val n = if (lb >= 0) raw.substring(lb + 1) else raw
        return if (n.startsWith("L") && n.endsWith(";")) n.substring(1, n.length - 1) else n
    }

    /** Add [b]'s `CONSTANT_Class` references to [out] as dotted names, minus the JDK and anything in [own].
     *  Raw constant-pool read via ASM's low-level accessors — no member/descriptor traversal. */
    private fun harvestClass(b: ByteArray, own: Set<String>, out: MutableSet<String>) {
        val cr = ClassReader(b)
        val buf = CharArray(cr.maxStringLength.coerceAtLeast(1))
        for (i in 1 until cr.itemCount) {
            val off = cr.getItem(i)
            if (off == 0) continue                            // second slot of a long/double entry
            if (cr.readByte(off - 1) != CONSTANT_CLASS) continue
            val name = unwrapClassRef(cr.readUTF8(off, buf) ?: continue)
            if (name.isEmpty() || name.startsWith(JDK_PREFIX) || name in own) continue
            out.add(name.replace('/', '.'))
        }
    }

    // ============================================================================================
    // Forge Mixed-SRG branch (≤1.20.5 runtimes).
    // ============================================================================================

    /**
     * Assemble [outMappings] (TSRG2 srg_to_official: left=srg-hybrid right=named) from MCPConfig [joinedTsrg]
     * (obf->srg) + Mojang [clientTxt] (named<->obf), then reverse-remap the Mixed-SRG [runtimeMcUri] (readable
     * class + srg member) into `<outSymbolsDir>/mc-symbols.jar` (readable class + named member) for the
     * Kotlin compiler.
     */
    fun buildForgeArtifacts(joinedTsrg: String, clientTxt: String, runtimeMcUri: String, outMappings: String, outSymbolsDir: String) {
        val mappings = Paths.get(outMappings)
        mappings.parent?.let { Files.createDirectories(it) }
        assembleForge(Paths.get(joinedTsrg), Paths.get(clientTxt), mappings)

        val symDir = File(outSymbolsDir)
        symDir.mkdirs()
        val symJar = File(symDir, RemapBundle.MC_SYMBOLS).toPath()
        reverseRemapJar(
            Paths.get(URI.create(runtimeMcUri)), symJar,
            tsrgProvider(mappings, "left", "right"), true, "reverseRemapForge",
        )
        writeDeps(symJar)
    }

    /** srg<->named via obf pivot, emitting the hybrid TSRG2 forge_gradle uses: readable class names on BOTH
     *  sides (from Mojang — MCPConfig's own srg class names are obfuscated) + f_/m_ members on the left, named
     *  members on the right. mapping-io's uniform source-switch can't express that per-element hybrid, so we
     *  take classes from `named` and members from `srg` and emit TSRG2 directly (the format is trivial). */
    private fun assembleForge(joinedPath: Path, proguard: Path, out: Path) {
        AtomicFiles.publishing(out) { tmp -> tmp.toFile().writeText(HybridTsrg2.emit(mergeOnObfPivot(joinedPath, proguard))) }
    }

    /** Merge MCPConfig's joined.tsrg (obf -> [srg, id]) with Mojang's proguard (named<->obf) over their shared
     *  obf pivot, then source-switch to named. Result: named -> [srg, id, obf], i.e. readable class names as
     *  the source with srg member names riding in the "srg" namespace. */
    private fun mergeOnObfPivot(joinedPath: Path, proguard: Path): MemoryMappingTree {
        // joined.tsrg: obf -> [srg, id]
        val joined = MemoryMappingTree()
        MappingReader.read(joinedPath, MappingFormat.TSRG_2_FILE, joined)
        // Mojang proguard: named<->obf; rename canonical, switch source to obf (the pivot shared with joined)
        val pgRaw = MemoryMappingTree()
        MappingReader.read(proguard, MappingFormat.PROGUARD_FILE, pgRaw)
        val rename = hashMapOf(pgRaw.srcNamespace to "named", pgRaw.dstNamespaces[0] to "obf")
        val pg = MemoryMappingTree()
        pgRaw.accept(MappingNsRenamer(pg, rename))
        val pgObf = MemoryMappingTree()
        pg.accept(MappingSourceNsSwitch(pgObf, "obf"))
        pgObf.accept(joined)                                     // obf -> [srg, id, named]
        // switch source to named (readable classes); srg member names ride in the "srg" dst namespace
        val asm = MemoryMappingTree()
        joined.accept(MappingSourceNsSwitch(asm, "named"))       // named -> [srg, id, obf]
        return asm
    }
}

/** Serializer for the hybrid TSRG2 [RemapCacheBuilder.assembleForge] writes. Its own object because it is pure
 *  text emission over a finished mapping tree — no downloads, no jars, no cache layout. */
private object HybridTsrg2 {

    /** Serialize [asm] as the hybrid TSRG2 forge_gradle reads: a class identity line (readable on both sides)
     *  followed by that class's tab-indented member lines. */
    fun emit(asm: MemoryMappingTree): String {
        val srgNs = asm.getNamespaceId("srg")
        val sb = StringBuilder("tsrg2 left right\n")
        for (c in asm.classes) {
            val readable = c.getName(MappingTree.SRC_NAMESPACE_ID) ?: continue
            sb.append(readable).append(' ').append(readable).append('\n')   // class identity (readable both sides)
            appendMembers(sb, c, srgNs)
        }
        return sb.toString()
    }

    /** [c]'s tab-indented member lines: `<srg> <named>` for fields, `<srg> <desc> <named>` for methods. An
     *  element missing either name — or a method missing its descriptor — is skipped: it has nothing to map. */
    private fun appendMembers(sb: StringBuilder, c: MappingTree.ClassMapping, srgNs: Int) {
        for (f in c.fields) {
            val srg = f.getName(srgNs) ?: continue
            val named = f.getName(MappingTree.SRC_NAMESPACE_ID) ?: continue
            sb.append('\t').append(srg).append(' ').append(named).append('\n')
        }
        for (m in c.methods) {
            val srg = m.getName(srgNs) ?: continue
            val named = m.getName(MappingTree.SRC_NAMESPACE_ID) ?: continue
            val desc = m.getDesc(MappingTree.SRC_NAMESPACE_ID) ?: continue
            sb.append('\t').append(srg).append(' ').append(desc).append(' ').append(named).append('\n')
        }
    }
}

/**
 * tiny-remapper provider over forge's TSRG2 (srg_to_official.tsrg), direction [fromNs] -> [toNs]. Its two
 * namespaces are "left" = Mixed-SRG runtime (class names mojmap, members m_/f_) and "right" = named (all mojmap).
 * Both directions are needed and are the SAME walk: [remapModule] remaps script bytecode named(right)->srg(left),
 * the symbol-jar build derives mojmap from the runtime jar srg(left)->named(right).
 *
 * Descriptors always come from the file's own src side (= "left", TSRG2's `tsrg2 left right` header) regardless of
 * direction — Mixed SRG keeps mojmap class names, so a desc is identical on both sides and matches the mojmap
 * descs the inputs reference.
 */
@Suppress("CyclomaticComplexMethod")
internal fun tsrgProvider(mappingsPath: Path, fromNs: String, toNs: String): IMappingProvider {
    val tree = MemoryMappingTree()
    MappingReader.read(mappingsPath, MappingFormat.TSRG_2_FILE, tree)
    val from = tree.getNamespaceId(fromNs)
    val to = tree.getNamespaceId(toNs)
    return IMappingProvider { out ->
        for (cls in tree.classes) {
            val owner = cls.getName(from) ?: continue           // readable either way (class identity)
            val ownerDst = cls.getName(to) ?: continue
            out.acceptClass(owner, ownerDst)
            for (m in cls.methods) {
                val src = m.getName(from) ?: continue
                val dst = m.getName(to) ?: continue
                out.acceptMethod(IMappingProvider.Member(owner, src, m.getDesc(MappingTree.SRC_NAMESPACE_ID)), dst)
            }
            for (f in cls.fields) {
                val src = f.getName(from) ?: continue
                val dst = f.getName(to) ?: continue
                out.acceptField(IMappingProvider.Member(owner, src, f.getDesc(MappingTree.SRC_NAMESPACE_ID)), dst)
            }
        }
    }
}

/**
 * ASM visitor that replaces every CONCRETE method's code with a 2-byte `aconst_null; athrow` stub, keeping the
 * method's signature, exceptions and declaration annotations (code/type annotations go with the body). Wired as
 * tiny-remapper's extraPreApplyVisitor so the whole pipeline (remap + write) processes stubs, not full bodies —
 * the compiler reads signatures/constants/annotations, never bodies, for a classpath class. The resulting symbol
 * jar is compile-only (never loaded/verified/run), so an under-sized maxLocals or a throwing constructor is
 * harmless. Abstract/native methods (no Code attribute) pass through untouched.
 */
private class BodyStripper(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return mv
        return object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitCode() {            // emit the entire stub body, then swallow every original body event
                super.visitCode()
                super.visitInsn(Opcodes.ACONST_NULL)
                super.visitInsn(Opcodes.ATHROW)
                super.visitMaxs(1, 0)
            }
            override fun visitFrame(t: Int, nl: Int, l: Array<out Any>?, ns: Int, s: Array<out Any>?) {}
            override fun visitInsn(opcode: Int) {}
            override fun visitIntInsn(opcode: Int, operand: Int) {}
            override fun visitVarInsn(opcode: Int, v: Int) {}
            override fun visitTypeInsn(opcode: Int, type: String?) {}
            override fun visitFieldInsn(opcode: Int, owner: String?, n: String?, d: String?) {}
            override fun visitMethodInsn(opcode: Int, owner: String?, n: String?, d: String?, itf: Boolean) {}
            override fun visitInvokeDynamicInsn(n: String?, d: String?, bsm: Handle?, vararg bsmArgs: Any?) {}
            override fun visitJumpInsn(opcode: Int, label: Label?) {}
            override fun visitLabel(label: Label?) {}
            override fun visitLdcInsn(value: Any?) {}
            override fun visitIincInsn(v: Int, inc: Int) {}
            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {}
            override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {}
            override fun visitMultiANewArrayInsn(d: String?, dims: Int) {}
            override fun visitTryCatchBlock(s: Label?, e: Label?, h: Label?, type: String?) {}
            override fun visitLocalVariable(n: String?, d: String?, sig: String?, s: Label?, e: Label?, i: Int) {}
            override fun visitLineNumber(line: Int, s: Label?) {}
            override fun visitMaxs(maxStack: Int, maxLocals: Int) {}
            override fun visitInsnAnnotation(tr: Int, tp: TypePath?, d: String?, vis: Boolean): AnnotationVisitor? = null
            override fun visitTryCatchAnnotation(tr: Int, tp: TypePath?, d: String?, vis: Boolean): AnnotationVisitor? = null
            override fun visitLocalVariableAnnotation(
                tr: Int,
                tp: TypePath?,
                st: Array<out Label>?,
                en: Array<out Label>?,
                idx: IntArray?,
                d: String?,
                vis: Boolean,
            ): AnnotationVisitor? = null
        }
    }
}
