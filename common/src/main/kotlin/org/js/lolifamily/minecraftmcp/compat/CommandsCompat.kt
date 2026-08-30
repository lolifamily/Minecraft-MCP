package org.js.lolifamily.minecraftmcp.compat

import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import org.js.lolifamily.minecraftmcp.Constants
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Cross-version seam for `Commands#performPrefixedCommand(CommandSourceStack, String)`.
 *
 * Renamed at 1.19 (`performCommand(CommandSourceStack, String)` -> `performPrefixedCommand(...)`) and its
 * return went `int` -> `void` at the 1.20.2 command-execution rewrite — both INSIDE the 1.18.2 node's
 * declared `[1.18.2, 1.20.5)`, so no single compiled call site spans it. Bind reflectively, discard the
 * return.
 *
 * Matched by parameter types, not name: name strings aren't reobf'd (so `"performPrefixedCommand"` stays
 * mojmap and misses on Fabric intermediary / Forge Mixed-SRG), while the parameter `Class` references are.
 * `(CommandSourceStack, String)` uniquely identifies the method on `Commands`, and 1.18.2's pre-rename
 * `performCommand` carries the identical signature, so one match spans the rename too. The return type is
 * excluded from the match — it is exactly what differs across the range.
 */
object CommandsCompat {

    /** `null` if this runtime has no `(CommandSourceStack, String)` method on `Commands`, or if resolution
     *  threw. */
    private val method: Method? by lazy { resolve() }

    private fun resolve(): Method? = try {
        val css = CommandSourceStack::class.java
        Commands::class.java.methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == css &&
                it.parameterTypes[1] == String::class.java
        }
    } catch (t: Throwable) {
        Constants.LOG.warn("[compat] resolving Commands#performPrefixedCommand failed", t)
        null
    }

    /**
     * Invoke `performPrefixedCommand(source, command)` on [commands], discarding its version-dependent
     * return value. [command] is passed through as-is (the vanilla method tolerates a leading `/` either way).
     *
     * @return `true` if the vanilla method was found and invoked; `false` if it could not be located on this
     *         runtime (an unsupported Minecraft version, or a resolution failure logged by [resolve]).
     */
    fun performPrefixedCommand(commands: Commands, source: CommandSourceStack, command: String): Boolean {
        val m = method ?: return false
        try {
            m.invoke(commands, source, command) // Integer (<=1.20.1) or null (void)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e // surface the command's real throwable, like a direct invokevirtual would
        }
        return true
    }
}
