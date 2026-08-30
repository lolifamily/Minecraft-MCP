package org.js.lolifamily.minecraftmcp.patch

import org.js.lolifamily.minecraftmcp.Constants
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.concurrent.ConcurrentHashMap

/**
 * Which method owns a source line, read from the class file's LineNumberTable — the descriptor a
 * [StackTraceElement] does not carry, and the only thing that separates same-named obf overloads.
 *
 * Only spigot gets here (every other namespace answers owner-free), and there the jar's table is the live one:
 * ByteBuddy advice leaves the target method's lines untouched, and Mixin offsets merged code into a range ABOVE
 * the target's instead of renumbering it — added methods included, a line table being per-method to begin with.
 * A line that matches nothing yields null, so an unknown rewriter costs the obf name we already showed.
 */
internal object LineTable {

    /** Parsed per owner, and only for one that actually reached an ambiguous frame. */
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    /** The descriptor of the method named [method] whose line table holds [line], or null. */
    fun descAt(internalOwner: String, method: String, line: Int): String? =
        cache.getOrPut(internalOwner) { parse(internalOwner) }[key(method, line)]

    private fun key(method: String, line: Int) = "$method $line"

    /** Bridges are skipped: a covariant pair shares one line, so it is the one shape that collides here.
     *  Failure caches as empty rather than retrying per frame — an unreadable class stays unreadable. */
    private fun parse(internalOwner: String): Map<String, String> {
        val out = HashMap<String, String>()
        val seen = HashSet<String>()
        val overloaded = HashSet<String>()
        try {
            val bytes = Constants.MC_LOADER.getResourceAsStream("$internalOwner.class")?.use { it.readBytes() }
            // NOT SKIP_CODE: the line table lives inside the code attribute. SKIP_FRAMES drops only StackMapTable.
            ClassReader(bytes ?: return out).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(acc: Int, name: String, desc: String, sig: String?, ex: Array<String>?): MethodVisitor? {
                        if (acc and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0) return null
                        if (!seen.add(name)) overloaded.add(name) // a name can only repeat under a new descriptor
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitLineNumber(line: Int, start: Label) { out[key(name, line)] = desc }
                        }
                    }
                },
                ClassReader.SKIP_FRAMES,
            )
        } catch (_: Throwable) {
            out.clear()
        }
        // A lone name is settled by the caller's agreement tier, so it is never asked about here.
        out.keys.removeAll { it.substringBeforeLast(' ') !in overloaded }
        return out
    }
}
