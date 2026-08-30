package org.js.lolifamily.minecraftmcp.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.js.lolifamily.minecraftmcp.MinecraftMcp
import org.js.lolifamily.minecraftmcp.exec.Capture
import org.js.lolifamily.minecraftmcp.mcp.CommandRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * The plugin-side `run_command target=server`, standing in for the mod path's [CommandRunner]. Same shape —
 * console-level source, feedback captured instead of printed — but built from Bukkit API only, because Paper's
 * runtime is spigot-named below 1.20.5 and the mojmap NMS path would not link there.
 */
internal object PaperCommandRunner {

    /** Prefix on every line THIS class inserts, as the mod path does, so a reader can tell our notes from real
     *  command feedback, which is never prefixed. */
    private const val NOTE = "[mcp]"

    /** How long feedback is collected after the commands go out. Not zero: a plugin command that answers from
     *  an async task replies a tick later at the earliest. */
    private const val CAPTURE_WINDOW_MS = 500L

    private val plugin: JavaPlugin
        get() = JavaPlugin.getProvidingPlugin(PaperCommandRunner::class.java)

    /**
     * Run [command] — possibly multiple lines, executed in order like a `.mcfunction` — on the server thread
     * and return the captured feedback. Blank lines and `#` comments are skipped, a failing line does not stop
     * the rest. Never throws: returns an explanatory string on any failure.
     */
    fun run(command: String): String {
        val cmds = command.split("\r?\n".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (cmds.isEmpty()) return "$NOTE no command given"
        if (!MinecraftMcp.running || Bukkit.isStopping()) return "$NOTE server is stopping"

        val plain = PlainTextComponentSerializer.plainText()
        val sinks = List(cmds.size) { Capture() }
        val dispatch = Runnable {
            for (i in cmds.indices) {
                val fb = sinks[i]
                // createCommandSender is the only feedback-capturing sender VanillaCommandWrapper#getListener
                // accepts (as FeedbackForwardingSender) — a hand-rolled CommandSender throws there, so vanilla
                // commands would not run at all. Its permissions are the console's, i.e. level 4.
                val sender = Bukkit.createCommandSender { c: Component -> fb.appendLine(plain.serialize(c)) }
                try {
                    // dispatchCommand takes the label bare: a leading '/' would be parsed as part of it and
                    // match nothing. The mod path's performPrefixedCommand tolerated either.
                    Bukkit.dispatchCommand(sender, cmds[i].removePrefix("/"))
                } catch (t: Throwable) {
                    fb.appendLine("$NOTE threw: $t")
                }
            }
        }

        // What silence MEANS is decided on the branch that knows, rather than carried to the render below: the
        // inline path never sleeps, so it has no window to have waited out.
        val silent: String
        // The lane pump already runs here, and so would a caller that reached us from one. No window: the wait
        // would be the tick loop's.
        if (Bukkit.isPrimaryThread()) {
            dispatch.run()
            silent = "$NOTE no feedback (not waited)"
        } else {
            val failure = dispatchOnServerThread(dispatch)
            if (failure != null) return failure
            silent = "$NOTE no feedback within ${CAPTURE_WINDOW_MS}ms"
        }

        // Single line -> just its feedback; multiple -> each echoed as `> cmd` above its output, as the mod path.
        val multi = cmds.size > 1
        return cmds.indices.joinToString("\n") { i ->
            val out = sinks[i].take().trim().ifEmpty { silent }
            if (multi) "> ${cmds[i]}\n$out" else out
        }
    }

    /**
     * Hand [dispatch] to the server thread, wait for it to run, then hold the capture window open. Split from
     * [run] because scheduling is not command semantics: everything here is about GETTING to the server thread,
     * and none of it varies with what the commands are.
     *
     * @return null once the window has closed, or the `[mcp]`-prefixed reason it did not — [run] returns that
     *         verbatim, which is how its "never throws" contract survives the split.
     */
    private fun dispatchOnServerThread(dispatch: Runnable): String? {
        val f = CompletableFuture<Unit>()
        try {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    try {
                        dispatch.run()
                        f.complete(Unit)
                    } catch (t: Throwable) {
                        f.completeExceptionally(t)
                    }
                },
            )
        } catch (t: Throwable) {
            return "$NOTE failed to schedule on the server thread: $t"
        }
        // Untimed, as on the mod path. A frozen or stepped tick is a first-class scenario here, so any
        // deadline would kill commands that were going to run; the only way the task is dropped instead is a
        // disabled (`CraftScheduler#cancelTasks`), which on a stop means the JVM is on its way out anyway.
        try {
            f.get()
            Thread.sleep(CAPTURE_WINDOW_MS)
        } catch (e: ExecutionException) {
            return "$NOTE failed: ${e.cause ?: e}"
        } catch (_: InterruptedException) {
            // Unreachable — nothing interrupts an mcp-http thread (see McpServer's pool). Kept so [run]'s
            // never-throws contract holds literally, which McpServer relies on.
            Thread.currentThread().interrupt()
            return "$NOTE interrupted"
        }
        return null
    }
}
