package org.js.lolifamily.minecraftmcp.repl.impl

import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.TinyUtils
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.withLock
import kotlin.metadata.jvm.KotlinClassMetadata

// ============================================================================================
// Runtime mojmap -> intermediary/srg remap of compiled-script bytecode.
// On a non-mojmap production runtime the compiled script references mojmap names the runtime does
// not have; the remapper rewrites its class/method/field refs to the runtime namespace before the class
// is defined. The in-memory compiled classes (KJvmCompiledModuleInMemory.compilerOutputFiles) are read
// inline in weaveClasses by a direct typed cast (impl types are compileOnly).
// ============================================================================================

/** The mapping tables + hierarchy index built ONCE, reused across every eval — a pure in-memory ASM
 *  ClassRemapper in place of tiny-remapper's per-eval readClassPath (~1.5s). Built lazily on first remap
 *  (needs mappingsPath + classpath in hand); immutable/concurrent afterward. */
@Volatile
private var flatRemapper: FlatRemapper? = null
private val flatLock = java.util.concurrent.locks.ReentrantLock()

/** Remap the script's compiled classes [fromNs]->[toNs]. */
internal fun remapModule(
    outputFiles: Map<String, ByteArray>,
    mappingsPath: String,
    classpath: List<File>,
    fromNs: String,
    toNs: String,
): Map<String, ByteArray> = flatRemapperFor(mappingsPath, classpath, fromNs, toNs).remap(outputFiles)

private fun flatRemapperFor(mappingsPath: String, classpath: List<File>, fromNs: String, toNs: String): FlatRemapper {
    flatRemapper?.let { return it }
    return flatLock.withLock {
        flatRemapper ?: buildFlatRemapper(mappingsPath, classpath, fromNs, toNs).also { flatRemapper = it }
    }
}

/** Consume the SAME IMappingProvider tiny-remapper would (identical namespaces/descriptors), dumping its
 *  class/method/field mappings into flat tables keyed by "owner name desc". The MC hierarchy is read
 *  lazily from [classpath] (mc-symbols) inside [FlatRemapper]; the tables are immutable afterward. */
private fun buildFlatRemapper(mappingsPath: String, classpath: List<File>, fromNs: String, toNs: String): FlatRemapper {
    val tsrg = mappingsPath.endsWith(".tsrg")
    // named(right) -> srg(left) is the only direction a Mixed-SRG runtime needs for script bytecode; the
    // symbol-jar build takes the same walk the other way (see tsrgProvider).
    val provider = if (tsrg) {
        tsrgProvider(Paths.get(mappingsPath), "right", "left")
    } else {
        TinyUtils.createTinyMappingProvider(Paths.get(mappingsPath), fromNs, toNs)
    }
    val classMap = HashMap<String, String>()
    val methodMap = HashMap<String, String>()
    val fieldMap = HashMap<String, String>()
    provider.load(object : IMappingProvider.MappingAcceptor {
        override fun acceptClass(srcName: String, dstName: String) { classMap[srcName] = dstName }
        override fun acceptMethod(method: IMappingProvider.Member, dstName: String) {
            methodMap["${method.owner} ${method.name} ${method.desc}"] = dstName
        }
        override fun acceptField(field: IMappingProvider.Member, dstName: String) {
            fieldMap[if (tsrg) "${field.owner} ${field.name}" else "${field.owner} ${field.name} ${field.desc}"] = dstName
        }
        override fun acceptMethodArg(method: IMappingProvider.Member, lvIndex: Int, dstName: String) {}
        override fun acceptMethodVar(method: IMappingProvider.Member, lvIndex: Int, startOpIdx: Int, asmIndex: Int, dstName: String) {}
    })
    val fr = FlatRemapper(classMap, methodMap, fieldMap, tsrg, SymbolIndex(classpath))
    Constants.LOG.info(
        "[mcp-remap] flat remapper built: {} classes / {} methods / {} fields ({})",
        classMap.size, methodMap.size, fieldMap.size, if (tsrg) "tsrg" else "tiny",
    )
    return fr
}

// ============================================================================================
// Flat remapper: pre-built tables + a lazily-grown hierarchy, reused across evals.
//
// tiny-remapper's class map is monotonic and its input bytes are single-use (freed after the first apply), so
// its expensive readClassPath — the mc-symbols inheritance index, ~1.5s — cannot be reused across evals: a
// second apply trips over the nulled data of a prior eval's input. Instead, we build the mapping tables + a lazy
// hierarchy once, and each eval is a plain ASM ClassRemapper doing flat lookups with a memoized climb.
//
// Climbing to ANY ancestor that declares (name,desc) yields the same target: an obfuscator must give two
// same-signature interface methods one name as soon as some class implements both, so "same signature,
// different runtime names" survives only for type pairs the game itself never joins. A mod or snippet type
// that joins such a pair has no valid runtime-namespace form anyway — two methods where the source wrote one.
// ============================================================================================

/** Byte source over the mc-symbols jars/dirs: internal class name -> class bytes, read on demand. Jars are
 *  opened once and kept open (read-only symbols, process lifetime). The whole class file is inflated per
 *  read, but only its header (super+interfaces) is parsed, and only for the classes an eval actually
 *  touches (memoized in FlatRemapper.superCache). */
private class SymbolIndex(classpath: List<File>) {
    private val readers = HashMap<String, () -> ByteArray?>()
    init {
        for (f in classpath) {
            if (f.isFile) {
                val zf = java.util.zip.ZipFile(f)   // kept open on purpose
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val ze = e.nextElement()
                    if (ze.name.endsWith(".class")) {
                        // First classpath entry wins, as on a real classloader.
                        val name = ze.name.substring(0, ze.name.length - 6)
                        if (name !in readers) readers[name] = { zf.getInputStream(ze).use { it.readBytes() } }
                    }
                }
            } else if (f.isDirectory) {
                f.walkTopDown().forEach { cf ->
                    if (cf.isFile && cf.name.endsWith(".class")) {
                        val rel = cf.relativeTo(f).invariantSeparatorsPath
                        val name = rel.substring(0, rel.length - 6)
                        if (name !in readers) readers[name] = { cf.readBytes() }
                    }
                }
            }
        }
    }
    fun read(name: String): ByteArray? = readers[name]?.invoke()
}

/** Immutable mapping tables + a lazily-built immutable MC hierarchy; each eval is a plain ASM ClassRemapper.
 *  Member lookups climb owner→ancestors (super first, then interfaces) and memoize. All state is immutable or
 *  a ConcurrentHashMap memo, so many lanes may remap concurrently. */
private class FlatRemapper(
    // Params, not properties: their only reader is the object expression below, which captures them directly.
    classMap: Map<String, String>,
    methodMap: Map<String, String>,
    fieldMap: Map<String, String>,
    private val ignoreFieldDesc: Boolean,
    private val symbols: SymbolIndex,
) {
    private val superCache = java.util.concurrent.ConcurrentHashMap<String, Array<String>>()
    private val methodMemo = java.util.concurrent.ConcurrentHashMap<String, String>()   // "" ⇒ unmapped
    private val fieldMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val none = emptyArray<String>()

    private val remapper = object : org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
        override fun map(internalName: String): String = classMap[internalName] ?: internalName
        override fun mapMethodName(owner: String, name: String, descriptor: String): String =
            methodMemo.getOrPut("$owner $name $descriptor") {
                resolve(owner, name, descriptor, methodMap, false).orEmpty()
            }.ifEmpty { name }
        override fun mapFieldName(owner: String, name: String, descriptor: String): String =
            fieldMemo.getOrPut("$owner $name $descriptor") {
                resolve(owner, name, descriptor, fieldMap, true).orEmpty()
            }.ifEmpty { name }
    }

    /** Remap every .class in [input] — bytecode AND `@Metadata`. Sidecar files (.kotlin_module etc.) ride
     *  along unchanged. */
    fun remap(input: Map<String, ByteArray>): Map<String, ByteArray> {
        // Seed this module's own classes first: `class X : Item(...)` is an owner whose supers decide how its
        // members map, and it exists in no jar [supersOf] can reach.
        for ((path, bytes) in input) {
            if (path.endsWith(".class")) ClassReader(bytes).let { superCache[it.className] = directSupers(it) }
        }
        val out = LinkedHashMap<String, ByteArray>(input.size)
        for ((path, bytes) in input) {
            if (!path.endsWith(".class")) { out[path] = bytes; continue }
            val cr = ClassReader(bytes)
            val cw = ClassWriter(0)   // pure rename: frames/maxs stay valid, ClassRemapper remaps frame types
            cr.accept(MetaRewriter(org.objectweb.asm.commons.ClassRemapper(cw, remapper)), 0)
            out[remapper.map(cr.className) + ".class"] = cw.toByteArray()
        }
        return out
    }

    /** Rewrites the `@Metadata` proto alongside the bytecode. Sits ABOVE the ClassRemapper, so the proto handed
     *  down is already in the runtime namespace: ASM remaps an annotation's Type values and nothing else, and
     *  `d1`/`d2` are plain strings it would pass straight through — which is what left kotlin-reflect resolving
     *  mojmap classifiers no non-mojmap runtime has, degrading to `???` or throwing with the name nowhere in the
     *  message. */
    private inner class MetaRewriter(sink: ClassVisitor) : ClassVisitor(Opcodes.ASM9, sink) {
        private var owner = "?"
        private var meta: MetaCapture? = null

        override fun visit(v: Int, access: Int, name: String, sig: String?, superName: String?, ifaces: Array<String>?) {
            owner = name
            super.visit(v, access, name, sig, superName, ifaces)
        }

        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
            if (desc == "Lkotlin/Metadata;") return MetaCapture().also { meta = it }   // defer to visitEnd
            return super.visitAnnotation(desc, visible)
        }

        override fun visitEnd() {
            meta?.let { cap -> cap.emit(super.visitAnnotation("Lkotlin/Metadata;", true), rewrite(cap, owner)) }
            super.visitEnd()
        }
    }

    /**
     * The rewritten `@Metadata` fields, or null to re-emit the original verbatim.
     *
     * Kind gate: a snippet only ever compiles to Class(1) — its top-level declarations land ON the script class
     * rather than a file facade, and its lambdas are indy, so they carry no `@Metadata` at all. Anything else is
     * left alone, which is also what a proto this library cannot read gets: the bytecode is still correctly
     * remapped, only reflection over that one class stays mojmap-blind. A throw must not escape — it would cost
     * the whole module its bytecode remap, which is the larger of the two.
     */
    private fun rewrite(cap: MetaCapture, owner: String): Map<String, Any?>? {
        if (cap.kind != 1) return null
        return try {
            val parsed = KotlinClassMetadata.readStrict(cap.toMetadata()) as? KotlinClassMetadata.Class
                ?: return null
            KmRemap(remapper).rename(parsed.kmClass)
            val written = parsed.write()
            cap.fieldNames().associateWith { written.fieldOr(it, cap.original(it)) }
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-remap] {} metadata left unmapped; reflection over it will not resolve MC types", owner, t)
            null
        }
    }

    /** [cr]'s direct supertypes, super first then interfaces; a null super is java/lang/Object. */
    @Suppress("SpreadOperator") // the copy IS the product here: a fresh array, cached in superCache
    private fun directSupers(cr: ClassReader): Array<String> =
        cr.superName?.let { arrayOf(it, *cr.interfaces) } ?: cr.interfaces

    /** [name]'s direct supertypes, cached. Three sources: the snippet's own classes, prefilled by [remap];
     *  mc-symbols; and the game loader, which holds the only copy of a mod / loader type that subclasses MC.
     *  A name none of them answers has no known supers → empty. */
    private fun supersOf(name: String): Array<String> = superCache.getOrPut(name) {
        symbols.read(name)?.let { return@getOrPut directSupers(ClassReader(it)) }
        // runCatching because a miss is this function's normal answer, and a throw would abort the whole remap.
        val b = runCatching {
            ReplHost::class.java.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
        }.getOrNull() ?: return@getOrPut none
        // Game-loader bytes name their supers in the RUNTIME namespace, and only a named name matches a table
        // row. A no-op on mixed-SRG / spigot, whose class names are already mojmap.
        val supers = directSupers(ClassReader(b))
        Mappings.current()?.let { m ->
            for (i in supers.indices) supers[i] = m.reverseClassInternal(supers[i])
        }
        supers
    }

    /** owner + all ancestors, transitively; first table hit wins (see class note on why first==correct). */
    private fun resolve(owner: String, name: String, desc: String, table: Map<String, String>, field: Boolean): String? {
        val seen = HashSet<String>(8)
        val stack = ArrayDeque<String>()
        stack.addLast(owner)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (!seen.add(c)) continue
            table[if (field && ignoreFieldDesc) "$c $name" else "$c $name $desc"]?.let { return it }
            // Reversed so the stack pops the superclass before the interfaces — JVMS 5.4.3.3's order, which
            // decides it for a type whose superclass and an interface share a signature under two runtime names.
            val supers = supersOf(c)
            for (i in supers.indices.reversed()) stack.addLast(supers[i])
        }
        return null
    }
}
