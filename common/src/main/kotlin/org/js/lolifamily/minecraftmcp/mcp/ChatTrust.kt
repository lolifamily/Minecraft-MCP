package org.js.lolifamily.minecraftmcp.mcp

import net.minecraft.network.chat.Component
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import java.lang.reflect.Method

/**
 * Whether a captured chat line is something `run_command target=client` may hand back by default.
 *
 * The signal is the translation key: command feedback is translatable (`commands.time.query`, Brigadier's
 * `command.unknown.command`, an i18n'd mod's own key), while player-influenced chat either carries a chat key
 * or none at all — a formatting or cross-server plugin builds its lines with MiniMessage / legacy codes, whose
 * output is literal text. That last case is what earns the machinery: forwarded chat arrives as a SYSTEM
 * message on every version, so no structural signal sees it.
 *
 * Best effort, deliberately: the point is keeping unrelated chatter out of a command's result, and a hostile
 * server can wrap anything in any key anyway — `allow_untrusted_chat` is the boundary, this is the noise
 * filter. What still gets through is a player name in a join or death line, a dozen `[A-Za-z0-9_]` characters,
 * too small to carry an instruction; anything long enough (a book, a sign) has to be reached for and never
 * arrives unbidden.
 *
 * Reflection because no typed call spans the range: `getContents` is the only structural accessor and its
 * return type moved (`String` -> `ComponentContents`) at 1.19. `Mappings` is already what `run_command` refuses
 * to run without, for this same by-name reason.
 */
object ChatTrust {

    private const val COMPONENT = "net.minecraft.network.chat.Component"
    private const val TRANSLATABLE_CONTENTS = "net.minecraft.network.chat.contents.TranslatableContents"
    private const val TRANSLATABLE_COMPONENT = "net.minecraft.network.chat.TranslatableComponent"

    private val DENY = listOf("chat.type.", "commands.message.display.")

    /** The component is the server's to shape, and this walk runs on the client thread. */
    private const val MAX_DEPTH = 16

    /** [getContents] is null on 1.18.2, where the component IS the translatable rather than holding one. */
    private class Reader(private val getContents: Method?, private val translatable: Class<*>, private val getKey: Method) {

        fun keyOf(c: Component): String? = try {
            val holder: Any? = if (getContents == null) c else getContents.invoke(c)
            if (translatable.isInstance(holder)) getKey.invoke(holder) as? String else null
        } catch (_: Throwable) {
            null
        }
    }

    @Volatile
    private var reader: Reader? = null

    /** Call once the mapping table is as loaded as it will get: a by-name lookup before that misses for a
     *  reason that goes away on its own, so resolving on first use would cache the wrong verdict. */
    fun init() {
        reader = resolve()
        if (reader == null) {
            Constants.LOG.warn("[mcp] chat unclassifiable — withheld unless allow_untrusted_chat")
        }
    }

    /** Fail-closed: an unresolved runtime, an unreadable component and a plugin's literal line all answer the
     *  same, and `allow_untrusted_chat` is the way back. */
    @JvmStatic
    fun isUntrusted(c: Component): Boolean {
        val r = reader ?: return true
        return scan(c, r, 0) <= 0
    }

    /** -1 denied, 0 no key anywhere, 1 keyed and clean. A denied key ANYWHERE wins, so this cannot return on
     *  the first clean one. */
    private fun scan(c: Component, r: Reader, depth: Int): Int {
        if (depth >= MAX_DEPTH) return -1
        var state = 0
        r.keyOf(c)?.let { k ->
            if (DENY.any { k.startsWith(it) }) return -1
            state = 1
        }
        for (s in c.siblings) {
            val sub = scan(s, r, depth + 1)
            if (sub < 0) return -1
            if (sub > 0) state = 1
        }
        return state
    }

    private fun resolve(): Reader? {
        val m = Mappings.current()
        fun cls(named: String): Class<*>? =
            runCatching { Class.forName(m?.mapClass(named) ?: named, false, Constants.GAME_LOADER) }.getOrNull()
        fun method(ownerNamed: String, owner: Class<*>, named: String): Method? =
            runCatching { owner.getMethod(m?.mapMethod(ownerNamed, named) ?: named) }.getOrNull()

        // 1.19+
        cls(TRANSLATABLE_CONTENTS)?.let { tc ->
            val component = cls(COMPONENT) ?: return null
            return Reader(
                method(COMPONENT, component, "getContents") ?: return null,
                tc,
                method(TRANSLATABLE_CONTENTS, tc, "getKey") ?: return null,
            )
        }
        // 1.18.2
        val legacy = cls(TRANSLATABLE_COMPONENT) ?: return null
        return Reader(null, legacy, method(TRANSLATABLE_COMPONENT, legacy, "getKey") ?: return null)
    }
}
