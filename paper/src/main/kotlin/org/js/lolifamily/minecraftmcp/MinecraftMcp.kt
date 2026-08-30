package org.js.lolifamily.minecraftmcp

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.js.lolifamily.minecraftmcp.exec.Lanes
import org.js.lolifamily.minecraftmcp.platform.PaperPlatformHelper
import org.js.lolifamily.minecraftmcp.repl.ReplBridge

// Paper entry point. Bukkit constructs the main class via its no-arg constructor, so this must stay a `class`.
class MinecraftMcp :
    JavaPlugin(),
    Listener {

    override fun onEnable() {
        // First statement: a re-enter that skipped onDisable() inherits a true flag, and nothing ever clears
        // Lane.tickSource, so until this runs the server lane reads ready with no heartbeat behind it.
        running = false
        Constants.LOG.info("Hello Paper world!")
        // init() claims the JVM as its first act and logs why it stood down, so a false here means nothing
        // was started. disablePlugin() is synchronous and closes our PluginClassLoader on the way out.
        if (!CommonClass.init()) {
            server.pluginManager.disablePlugin(this)
            return
        }
        // Before registerEvents: too early is unreadable (nothing pumps yet), too late is not.
        running = true
        // Unconditional: the heartbeat is needed either way, and a later ServerLoadEvent counting down an
        // already-open latch is a no-op.
        server.pluginManager.registerEvents(this, this)
        if (loadedIntoRunningServer()) {
            Constants.LOG.warn("[mcp] hot-loaded (unsupported) — plugin gate opened now; later plugin urls are not importable")
            ReplBridge.pluginsLatch.countDown()
        }
    }

    /** Hot-loaded into a running server (PlugMan et al) rather than booted with it — an UNSUPPORTED path,
     *  covered only because it is the one that breaks outright: [onServerLoad] is this host's sole opener of
     *  [ReplBridge.pluginsLatch] and never fires there, hanging every eval rather than degrading one.
     *  Heuristic and best-effort, sound only while plugin.yml says `load: STARTUP` — that is what puts
     *  `onEnable` ahead of world loading. Wrong low is the old behavior; wrong high costs a script importing
     *  a plugin that attached its urls after this. */
    private fun loadedIntoRunningServer(): Boolean = Bukkit.getWorlds().isNotEmpty()

    override fun onDisable() {
        running = false
        // Stands in for the mod path's `stopServer` HEAD injection — `disablePlugins()` runs at the top of
        // stopServer, before the network stops and before players are saved. It also fires on a plain plugin
        // unload, so the reason can't claim the server stopped; both paths are on the server thread, which
        // reapOnStop's epoch bump requires. This lane only: a parallel eval rides its own worker, so no
        // heartbeat ending can strand it.
        val n = Lanes.SERVER.reapOnStop("plugin disabled")
        if (n > 0) Constants.LOG.info("[exec] plugin disabled — reaped {} eval(s)", n)
        // The endpoint deliberately stays up. This window — teardown, saves, whatever hangs in them — is one of
        // the most worth observing, and closing it to tidy up after an unsupported /reload would trade that for
        // nothing: CommonClass.JVM_CLAIM already refuses the re-enter, and its ERROR line is the notice. What a
        // reload leaves is this instance minus its heartbeat: server lane permanently not-ready, parallel lane
        // live, snippet loading already carried past our own closed loader (SnippetLoader.load). No flag set
        // here could do better — a manager that closes the loader outright never calls onDisable at all.
    }

    /**
     * Server-lane heartbeat, standing in for the mod path's `tickServer` RETURN injection.
     *
     * `ServerTickEndEvent` fires after `runAllTasks()` and before the loop's `waitUntilNextTick()`, so an eval
     * sees the settled tick within the same tick. A `runTaskTimer(0, 1)` pump would observe the same state but
     * only after that sleep — `50ms - MSPT` later, i.e. worst on an idle server.
     *
     * MONITOR runs this after every other plugin listener. It cannot order us against mods, which inject at the
     * method's RETURN and therefore still run after this event.
     */
    // Paper resolves the event to register FROM this parameter's type, and a signature it can't read is skipped
    // with a log line rather than an error — dropping the parameter would silently kill the heartbeat.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onServerTickEnd(@Suppress("UnusedParameter") event: ServerTickEndEvent) {
        Lanes.SERVER.pump(Bukkit.getServer())
    }

    /**
     * Plugin gate, standing in for the mod path's `MixinDedicatedServer#initServer` RETURN — fired right after
     * `enablePlugins(POSTWORLD)`, so every plugin's `onEnable` has returned and the urls it attached to its own
     * loader are on it (see [ReplBridge.pluginsLatch]). MONITOR is the last priority BUCKET, not last outright:
     * order within it is registration order, and nothing bounds a plugin that attaches later still.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onServerLoad(@Suppress("UnusedParameter") event: ServerLoadEvent) {
        ReplBridge.pluginsLatch.countDown()
    }

    companion object {
        /** Server-lane liveness, read by [PaperPlatformHelper.isServerRunning]. Written only here. */
        @Volatile
        internal var running: Boolean = false
    }
}
