package org.js.lolifamily.minecraftmcp.repl.impl

import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.TinyUtils
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import java.nio.file.Paths

// ============================================================================================
// The runtime -> mojmap CLASS rename the access-widen overlay applies wherever the runtime spells MC classes
// differently (fabric's class_N, spigot's BlockPosition), so mod/plugin signatures and mc-symbols.jar name the
// same types. Entry point is classRename, called from widenClasspath; ClasspathWiden owns the overlay itself.
// ============================================================================================

/** Minecraft's package root, as the bytes [mentionsMinecraft] scans for. Namespace-independent, unlike the
 *  spelling below it: fabric's `class_2338`, spigot's `BlockPosition` and mojmap's `BlockPos` all live here. */
private val MC_BYTES = "net/minecraft/".toByteArray(Charsets.US_ASCII)

/**
 * Renames CLASSES only — [mapMethodName] / [mapFieldName] stay at ASM's identity, which is also how [KmRemap]
 * knows to leave member names alone. Correct here regardless: ScriptRemap's flat remapper maps the classes back
 * on the way out and its tables key on mojmap names, so an already-intermediary member name passes it untouched.
 */
internal class OverlayRename(private val table: Map<String, String>) : Remapper(Opcodes.ASM9) {

    val size: Int get() = table.size

    override fun map(internalName: String): String = table[internalName] ?: internalName

    /** Whether [internalName] is one of the names this rename moves. */
    fun renames(internalName: String): Boolean = table.containsKey(internalName)

    /** The inverse of [map]. The overlay knows a class by its renamed name, but Mixin only answers to the
     *  intermediary one, so asking it anything needs the mapping read backwards. */
    val reverse: Map<String, String> by lazy {
        HashMap<String, String>(table.size * 2).apply { table.forEach { (from, to) -> put(to, from) } }
    }
}

/**
 * Build the rename, or null when this runtime renames no classes.
 *
 * Fabric production and spigot both rename CLASSES — `class_2338` and `BlockPosition` for the same `BlockPos`.
 * Forge Mixed-SRG does not: it obfuscates members only, so its rows are identities — dropped below, leaving an
 * empty table and a null that keeps that runtime on the overlay's untouched copy path.
 *
 * Reads the bundle's mappings FILE rather than the Mappings singleton: the file is on disk as soon as
 * RemapBundle publishes, so this inherits buildClasspath's existing remap dependency and opens no new window.
 */
internal fun classRename(): OverlayRename? {
    val path = (RemapBundle.current() ?: return null).mappings.toString()
    if (path.endsWith(".tsrg")) return null // forge: class names identical on both sides
    val table = HashMap<String, String>(8192)
    try {
        TinyUtils.createTinyMappingProvider(Paths.get(path), "intermediary", "named")
            .load(object : IMappingProvider.MappingAcceptor {
                override fun acceptClass(srcName: String, dstName: String) {
                    if (srcName != dstName) table[srcName] = dstName
                }
                override fun acceptMethod(method: IMappingProvider.Member, dstName: String) = Unit
                override fun acceptField(field: IMappingProvider.Member, dstName: String) = Unit
                override fun acceptMethodArg(method: IMappingProvider.Member, lvIndex: Int, dstName: String) = Unit
                override fun acceptMethodVar(
                    method: IMappingProvider.Member,
                    lvIndex: Int,
                    startOpIdx: Int,
                    asmIndex: Int,
                    dstName: String,
                ) = Unit
            })
    } catch (t: Throwable) {
        Constants.LOG.warn("[mcp-aw] rename table unreadable ({}) — overlay keeps runtime class names", path, t)
        return null
    }
    return if (table.isEmpty()) null else OverlayRename(table)
}

/** Whether [buf]'s first [len] bytes mention Minecraft at all — a raw scan, no parse, to keep every MC-free
 *  class off the remap path. Constant-pool UTF8 leaves ASCII verbatim, so the prefix appears literally wherever
 *  a type reference does; a false positive (the text sitting in a string constant, or a reference to an MC class
 *  this table does not rename) only costs a rename pass that changes nothing. */
internal fun mentionsMinecraft(buf: ByteArray, len: Int): Boolean {
    val n = MC_BYTES
    outer@ for (i in 0..len - n.size) {
        for (j in n.indices) if (buf[i + j] != n[j]) continue@outer
        return true
    }
    return false
}
