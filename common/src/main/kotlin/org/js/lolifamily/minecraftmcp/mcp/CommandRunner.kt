package org.js.lolifamily.minecraftmcp.mcp

import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.js.lolifamily.minecraftmcp.compat.CommandsCompat
import org.js.lolifamily.minecraftmcp.repl.Mappings
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Runs a Minecraft command on the server thread and captures its chat feedback, for the `run_command`
 * MCP tool. Vanilla mechanism throughout: a custom [CommandSource] collects the feedback the command would
 * normally print, attached to a server-level (OP) [CommandSourceStack], executed via
 * `Commands#performPrefixedCommand`.
 *
 * [CommandSource.acceptsSuccess] and `acceptsFailure` must both return `true`: `sendSuccess`/`sendFailure`
 * check them first and silently drop the message otherwise, so feedback would never reach the sink method
 * (`sendSystemMessage`, or `sendMessage` on 1.18.2). Returning true also bypasses the `sendCommandFeedback`
 * gamerule (our source decides, not the world).
 */
object CommandRunner {

    /** Prefix on every line THIS class inserts, as `ClientCommandRunner` does for the client path, so a reader
     *  (the LLM) can tell our notes from real command feedback, which is never prefixed. */
    private const val NOTE = "[mcp]"

    /**
     * Schedule [command] on the server thread (commands touch game state and Brigadier asserts the server
     * thread) and block until its captured output arrives. Never throws — returns an explanatory string on
     * any failure.
     *
     * @param serverObj the live `MinecraftServer` (from the server lane's handle)
     */
    fun run(serverObj: Any?, command: String): String {
        if (serverObj !is MinecraftServer) {
            return "$NOTE no live server (server lane not ready)"
        }
        // Asked here, next to execute(), not left to McpServer's lane gate: it guards the window between the
        // two. `stopped` is set BEFORE stopServer(), and past it execute() no longer queues — it runs the task
        // INLINE on the caller, i.e. this HTTP thread would parse and mutate the world while the server thread
        // is saving it. isRunning() is false from halt() onward, so it covers that whole window.
        if (!serverObj.isRunning) {
            return "$NOTE server is stopping"
        }
        val f = CompletableFuture<String>()
        try {
            serverObj.execute {
                try {
                    f.complete(capture(serverObj, command))
                } catch (t: Throwable) {
                    f.complete("$NOTE threw on the server thread: $t")
                }
            }
        } catch (t: Throwable) {
            return "$NOTE failed to schedule on the server thread: $t"
        }
        // Untimed. The run loop can only exit after waitUntilNextTick(), which drains the queue on entry, so a
        // task queued while running is always run; past `stopped` execute() ran it inline above, before we got
        // here. `f` is local and only ever completed, never canceled, so get() raises no CancellationException.
        return try {
            f.get()
        } catch (e: ExecutionException) {
            "$NOTE failed: ${e.cause ?: e}"
        } catch (_: InterruptedException) {
            // Unreachable — nothing interrupts an mcp-http thread (see McpServer's pool). Kept so [run]'s
            // never-throws contract holds literally, which McpServer relies on.
            "$NOTE interrupted"
        }
    }

    /**
     * Runs ON the server thread. [command] may be multiple lines — executed in order like a `.mcfunction`:
     * blank lines and `#` comments are skipped, a failing line does NOT stop the rest. All lines run in this
     * single server-thread task, so they share one tick snapshot. Single line -> just its feedback; multiple
     * -> each line echoed as `> cmd` above its output.
     */
    private fun capture(server: MinecraftServer, command: String): String {
        val cmds = command.split("\r?\n".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (cmds.isEmpty()) return "$NOTE no command given"
        val multi = cmds.size > 1

        // Reflection reports RUNTIME names (intermediary on Fabric, SRG on Forge) and string literals aren't
        // remapped, so translate each via the loaded Mappings (identity on a mojmap runtime). Without this
        // acceptsSuccess() falls to the else branch and returns false, so sendSuccess() drops every message and
        // every line reports no output. Feedback name differs: sendMessage (1.18.2) vs sendSystemMessage (1.19+).
        // Keyed by name with no descriptor, which needs the namespace to be injective: intermediary and SRG are.
        // Spigot obf is not, and never arrives — Paper overrides runCommands, and a hybrid remaps to its loader's.
        val map = Mappings.current()
        fun rt(mojmap: String): String = map?.mapMethod("net.minecraft.commands.CommandSource", mojmap) ?: mojmap
        val nFeedback = setOf(rt("sendSystemMessage"), rt("sendMessage"))
        val nAccepts = setOf(rt("acceptsSuccess"), rt("acceptsFailure"))
        val nInform = rt("shouldInformAdmins")

        val sb = StringBuilder()
        for (cmd in cmds) {
            val fb = ArrayList<Component>()
            // createCommandSourceStack() = server console source (permission level 4); withSource swaps only
            // the message sink, keeping that level. performPrefixedCommand tolerates a leading '/' and the
            // feedback messages are the output. Renamed and return-type-changed mid-range, so route through
            // CommandsCompat.
            val sink = feedbackSource(nFeedback, nAccepts, nInform, fb)
            val source: CommandSourceStack = server.createCommandSourceStack().withSource(sink)
            if (!CommandsCompat.performPrefixedCommand(server.commands, source, cmd)) {
                return "$NOTE Commands#performPrefixedCommand not found on this runtime " +
                    "(unsupported Minecraft version?)"
            }
            // flatten to plain text (drop styling) for the LLM
            val out = fb.joinToString("\n") { it.string }.ifEmpty { "$NOTE no output" }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(if (multi) "> $cmd\n$out" else out)
        }
        return sb.toString()
    }

    /** A [CommandSource] proxy that appends every feedback Component to [sink]; the three name sets decide
     *  which reflected method is which on this runtime. */
    private fun feedbackSource(feedback: Set<String>, accepts: Set<String>, inform: String, sink: MutableList<Component>): CommandSource =
        java.lang.reflect.Proxy.newProxyInstance(
            CommandSource::class.java.classLoader, arrayOf(CommandSource::class.java),
        ) { proxy, m, args ->
            when {
                // Object's hashCode/equals/toString reach the handler exactly like interface methods (see
                // Proxy's Javadoc), and the name sets below never name them — so hashCode() would fall through
                // to a null return, which is an NPE for an int. Identity semantics: this sink IS its identity.
                m.declaringClass === Any::class.java -> when (m.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> "mcp-feedback-source" // toString
                }
                m.name in feedback -> { (args?.getOrNull(0) as? Component)?.let(sink::add); null }
                m.name in accepts -> true   // must be true (see class doc)
                m.name == inform -> false
                else -> neutral(m.returnType) // any newer CommandSource method (getGameProfile, ...)
            }
        } as CommandSource

    /**
     * The neutral return value for [t]. An `InvocationHandler` returning null for a PRIMITIVE return type
     * throws NPE (its own javadoc says so), so this has to be TOTAL over every type the interface can
     * declare — not a ladder that grows a case per MC release. A primitive's neutral value is exactly the
     * JVM's default for a field of that type, read off a fresh one-element array, so there is no table that
     * can go stale (`boolean -> false` falls out of it, which is why it is no longer written down). `void`
     * reports isPrimitive == true but has no array type, and a null return for it is ignored by the proxy.
     */
    private fun neutral(t: Class<*>): Any? = when {
        t == java.util.Optional::class.java -> java.util.Optional.empty<Any>()
        t.isPrimitive && t != Void.TYPE -> java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(t, 1), 0)
        else -> null
    }
}
