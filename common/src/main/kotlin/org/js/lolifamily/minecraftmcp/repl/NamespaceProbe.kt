package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.impl.ReplHost

/**
 * Detects, once at startup, which naming namespace the game runtime actually uses — so the REPL knows
 * whether a compiled (mojmap-named) script must be remapped before it can link against the running MC.
 *
 * Probe the runtime rather than the loader type: dev and production ship the SAME jar, and Fabric dev is
 * mojmap while Fabric production is intermediary, so a loader-type guess would remap dev and crash it.
 *
 * Probe by string, never by symbol reference: the mod's own symbol references (e.g. `Blocks.class`) are
 * remapped by Loom/reobf at package time, so on Fabric production they already point at `class_2246` and
 * would always "exist" — probing nothing. A string literal is NOT remapped.
 *
 * The namespace cannot change within a process, so this runs once and caches. It lives on the game loader;
 * the masking-loaded [ReplHost] reaches it by parent delegation (single shared identity).
 */
object NamespaceProbe {
    enum class Namespace {
        /** Runtime names == source (mojmap) names: every loader on 26.1+ (MC ships unobfuscated from there),
         *  NeoForge production, all-loader dev. No remap. */
        MOJMAP,

        /** Fabric production, ≤1.21 only: classes + members are intermediary (`class_/method_/field_`). Remap
         *  all. 26.1+ ships unobfuscated, so Loom stops remapping and Fabric is [MOJMAP] there. */
        INTERMEDIARY,

        /** Forge production <1.20.5 "Mixed SRG": class names mojmap, methods/fields SRG (`m_/f_`). Remap members. */
        MIXED_SRG,

        /** Spigot / Paper <1.20.5: class names mojmap, methods/fields official obf (`a`/`b`). Remap members.
         *  Unlike SRG, an obf name is unique only WITH its descriptor — hence tiny v2, not forge's TSRG2. */
        SPIGOT,

        /** Probe fell through — refuse to remap and say so, rather than guess and crash. */
        UNKNOWN,
    }

    // Stable anchor: Blocks + STONE exist in every modern MC, and their mojmap names don't change across versions.
    // class_2246 is Blocks' INTERMEDIARY name — hardcoding it is safe because Fabric intermediary names are
    // per-class STABLE across MC versions.
    private const val ANCHOR_MOJMAP_CLASS = "net.minecraft.world.level.block.Blocks"
    private const val ANCHOR_MOJMAP_FIELD = "STONE"
    private const val ANCHOR_INTERMEDIARY_CLASS = "net.minecraft.class_2246"

    // Forge Mixed-SRG member names look like f_50069_ (fields) / m_49966_ (methods).
    private val SRG_FIELD_NAME = Regex("f_\\d+.*")

    // Spigot member names are proguard's: 1-2 plain letters (a, b, aa, bZ). Probed by SHAPE, like the two above,
    // NOT by looking for org.bukkit.Bukkit — a hybrid (Mohist/Arclight/CatServer) carries Bukkit too while its
    // net.minecraft namespace is the mod loader's, so Bukkit's presence proves nothing about the names.
    private val OBF_FIELD_NAME = Regex("[a-zA-Z]{1,2}")

    @Volatile
    private var cached: Namespace? = null

    /** The runtime naming namespace — probed once on the first call and cached for the whole process. probe() is
     *  side-effect-free and deterministic, so a first-call race just re-probes harmlessly (no lock needed). */
    fun current(): Namespace = cached ?: probe().also {
        cached = it
        Constants.LOG.info("[mcp-remap] runtime namespace = {} (anchor {}.{})", it, ANCHOR_MOJMAP_CLASS, ANCHOR_MOJMAP_FIELD)
        // UNKNOWN and MOJMAP are indistinguishable from here on — both skip remapping — so this is the last
        // point that can tell them apart. Warn, not error: the fallthrough leaves mojmap CLASS names, which may
        // still link fine; it is the member half we could not confirm.
        if (it == Namespace.UNKNOWN) {
            Constants.LOG.warn(
                "[mcp-remap] runtime naming unrecognized — scripts and patches run UNMAPPED: net.minecraft.* is " +
                    "unresolved at compile time if class names differ, NoSuchMethodError at link time if only " +
                    "members do. mcp.remap.* cannot help here — it only overrides a RECOGNIZED namespace's bundle.",
            )
        }
    }

    /** True when compiled (mojmap) script bytecode must be remapped before it can link the runtime.
     *  `@JvmStatic`: called statically from the must-stay-Java `ReplBridge`. */
    @JvmStatic
    fun needsRemap(): Boolean {
        val ns = current()
        return ns != Namespace.MOJMAP && ns != Namespace.UNKNOWN
    }

    /** Load [name] as a live class on the loader that defines MC (no init), or null if absent. */
    private fun classOrNull(name: String): Class<*>? = try { Class.forName(name, false, Constants.MC_LOADER) } catch (_: Throwable) { null }

    private fun probe(): Namespace {
        val anchor = classOrNull(ANCHOR_MOJMAP_CLASS)
            ?: return if (classOrNull(ANCHOR_INTERMEDIARY_CLASS) != null) Namespace.INTERMEDIARY else Namespace.UNKNOWN
        // Class name resolved as mojmap → members are either mojmap (MOJMAP) or SRG (MIXED_SRG). Confirm SRG
        // positively (an f_NNNNN field) rather than reading "STONE absent" as proof — any other cause of STONE's
        // absence falls through to UNKNOWN → no remap, the safe action while the class name is still mojmap.
        val fields = try { anchor.declaredFields } catch (_: Throwable) { return Namespace.UNKNOWN }
        return when {
            fields.any { it.name == ANCHOR_MOJMAP_FIELD }  -> Namespace.MOJMAP
            fields.any { SRG_FIELD_NAME.matches(it.name) } -> Namespace.MIXED_SRG
            fields.any { OBF_FIELD_NAME.matches(it.name) } -> Namespace.SPIGOT
            else -> Namespace.UNKNOWN
        }
    }
}
