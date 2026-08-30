package org.js.lolifamily.minecraftmcp

import net.minecraft.world.level.block.Blocks
import org.js.lolifamily.minecraftmcp.mcp.ChatTrust
import org.js.lolifamily.minecraftmcp.mcp.McpServer
import org.js.lolifamily.minecraftmcp.patch.PatchWarmup
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.js.lolifamily.minecraftmcp.repl.NamespaceProbe
import org.js.lolifamily.minecraftmcp.repl.RemapCache
import org.js.lolifamily.minecraftmcp.repl.ReplBridge
import org.js.lolifamily.minecraftmcp.security.AuthGate

// Shared across loaders: no loader-specific APIs here.
object CommonClass {

    /**
     * One MCP per JVM, claimed by whichever host initializes first. A system property rather than a static
     * because a host that reloads swaps the classloader underneath us and resets every static; never
     * released, since releasing it on disable would re-admit the re-entry it rejects. It lives in common
     * rather than in one host's entry point so it also catches two builds starting up in the same server.
     */
    private const val JVM_CLAIM = "org.js.lolifamily.minecraftmcp.jvmClaim"

    // Every host's entry point calls this to bootstrap the common code. False means nothing was started —
    // another host got there first, or the config is unreadable — and the caller stands down.
    fun init(): Boolean {
        // First statement: everything below is process-global and non-idempotent (an HTTP port, a ByteBuddy
        // agent, one cache dir), so a loser must not have run any of it.
        if (System.getProperties().putIfAbsent(JVM_CLAIM, "1") != null) {
            Constants.LOG.error(
                "[mcp] already initialized in this JVM — a second host (two builds installed side by side, " +
                    "or a host reload) is not supported. Standing down; restart with one.",
            )
            return false
        }

        // Same claim, across processes: everything below writes cache files and then holds them open for
        // the launch, which a second instance over this game dir would rewrite underneath us.
        CacheLock.claim()?.let {
            Constants.LOG.error("[mcp] {} — standing down. One game dir, one instance.", it)
            return false
        }

        // Set at the very top so it captures the whole eager-warmup span — everything warmup-related
        // below only SPAWNS threads.
        ReplBridge.initStartNanos = System.nanoTime()

        // Ahead of every Props reader: the file is its lowest-priority source, so a read that beats this one
        // silently misses it.
        ConfigFile.load()

        // McpServer refuses to bind on this, so everything below would warm for a request that never arrives.
        // read() logged the cause.
        if (ConfigFile.failure != null) return false

        // Physical side (a launch constant): drives the auth posture just below.
        val dedicated = Services.PLATFORM.isDedicatedServer

        // Dedicated server: open the gate once here. On a client ClientAuthProbe recomputes it per tick —
        // exactly one writer per process either way (dist is mutually exclusive), no volatile race.
        if (dedicated) {
            AuthGate.publish(AuthGate.ALLOW)
            Constants.LOG.info("[mcp-auth] dedicated server — authorization gate open")
        }

        // Independent of both remap and the compiler, so it starts here rather than inside
        // startBackgroundWarmup, whose gate thread would delay warmDone by however long the attach takes.
        PatchWarmup.start()

        // The first needsRemap() here probes the runtime naming namespace ONCE, before any script compiles, and
        // caches it for the process. Governs whether the REPL remaps mojmap script bytecode to the runtime
        // namespace (Fabric intermediary / Forge SRG) or runs it as-is (dev / NeoForge production, where
        // runtime names == source).
        if (NamespaceProbe.needsRemap()) {
            // Background thread: download + assemble + load mappings without blocking game startup. On finish, it
            // counts down the warmup gate's remap half (ReplBridge.remapReady); the gate then builds the compiler
            // once both remap + preload are ready, and execute_code stays gated on warmDone until it is.
            Thread({
                try {
                    val bundle = RemapCache.provision(Blocks::class.java)
                    if (bundle != null) {
                        Mappings.load(bundle.mappings.toString())
                    } else {
                        // provision already logged the cause; this is what it costs this launch.
                        Constants.LOG.warn("[mcp-remap] no remap bundle — scripts and patches will NOT remap")
                    }
                } catch (t: Throwable) {
                    Constants.LOG.warn("[mcp-remap] background remap init failed (degraded — patches won't remap)", t)
                } finally {
                    // In finally: the gate has to open even when provisioning failed, or buildClasspath waits forever.
                    ReplBridge.remapReady()
                    ChatTrust.init()   // by-name lookups resolve from here on, or never
                }
            }, "mcp-remap-init").apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }.start()
        } else {
            ReplBridge.remapReady()           // mojmap runtime: no remap, the gate's remap half opens immediately
            ChatTrust.init()
        }

        ReplBridge.startBackgroundWarmup()

        // Start the localhost MCP endpoint (execute_code -> in-game Kotlin REPL).
        McpServer.start()
        return true
    }
}
