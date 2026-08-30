package org.js.lolifamily.minecraftmcp.security

import org.js.lolifamily.minecraftmcp.exec.Lane
import org.js.lolifamily.minecraftmcp.exec.Lanes
import org.js.lolifamily.minecraftmcp.mcp.McpServer
import org.js.lolifamily.minecraftmcp.patch.Instrumentations

/**
 * Process-wide authorization gate for MCP tool calls that exercise authority above the player —
 * `execute_code` (arbitrary Kotlin/JVM = root, on any lane) and `run_command target=server` (runs at op
 * level 4 in the local server, above the world's cheat setting). Complements [McpServer]'s transport auth
 * (loopback bind + bearer token + Origin check) with a world/server authority check, derived from the live
 * session:
 *  - dedicated server → always allowed (the operator owns the box and set the token/launch flags;
 *    there is no world to grief that they don't already control);
 *  - client + local authoritative server (single-player / opened-to-LAN) → only if the world has
 *    cheats enabled (`allowCommands`);
 *  - client on a remote server → only if the local player is OP permission level >= 3;
 *  - client with no world loaded → allowed (nothing to grief; arbitrary-Kotlin/JVM access is
 *    fenced by token + loopback, same as the dedicated-server case).
 *
 * `run_command target=client` grants nothing above the player and never consults this gate — see
 * [McpServer.runCommand].
 *
 * The decision is produced on the game thread (the client tick via [ClientAuthProbe], or once at init on a
 * dedicated server) and read from the HTTP handler and the lane threads — hence a volatile, and one, since
 * authorization is a single process-wide fact.
 *
 * Revokes reach already-running work two ways: [McpServer] and [Lane.pump] read [allowed] themselves, and
 * [publish] pushes to the two that cannot — the parallel lane and installed patches.
 *
 * No client types here: this object links on a dedicated server, and the verifier force-loads any class a
 * verified method references — so touching `net.minecraft.client.Minecraft` et al. would
 * `NoClassDefFoundError` there. Every client-typed read lives in [ClientAuthProbe], which is never linked on
 * a dedicated server.
 */
object AuthGate {

    /**
     * An authorization decision: whether the tools may run, and — when not — a caller-facing reason.
     */
    class Decision(val allowed: Boolean, val reason: String?)

    // Cached singletons — the client heartbeat republishes ~20x/s, so keep the recompute garbage-free.
    internal val ALLOW = Decision(true, null)
    internal val DENY_INIT = Decision(
        false,
        "authorization not yet determined — the game is still starting up",
    )
    internal val DENY_SP_CHEATS = Decision(
        false,
        "single-player world has cheats disabled — enable cheats to run code or target=server commands " +
            "(run_command target=client still runs as your player regardless)",
    )
    internal val DENY_REMOTE_OP = Decision(
        false,
        "connected server requires OP permission level >= 3 to run code " +
            "(run_command target=client still runs as your player regardless)",
    )

    /** The probe reached no session-derived answer. Distinct from [DENY_INIT]: that one means "not determined
     *  YET" and clears on its own, this one means "could not be determined" and may not. */
    internal val DENY_PROBE_FAILED = Decision(
        false,
        "authorization could not be determined — the permission probe failed (see the game log)",
    )

    /** [DENY_PROBE_FAILED]'s common cause, named separately so the reason points at the mapping subsystem
     *  rather than at the player's permissions. */
    internal val DENY_NO_MAPPINGS = Decision(
        false,
        "authorization could not be determined — runtime mappings are not loaded (see the game log)",
    )

    /** Fail-closed until the first [publish] — the client heartbeat, or init on a dedicated server. */
    @Volatile
    private var current: Decision = DENY_INIT

    /** The current decision — a SINGLE volatile read, so [Decision.allowed] and [Decision.reason] are coherent
     *  (no torn read where a caller sees `allowed==false` but a stale/flipped reason). */
    val decision: Decision get() = current

    /** Whether above-player tool calls may run right now. */
    val allowed: Boolean get() = current.allowed

    /** The one writer. Level-triggered, not edge: the repeat is what re-interrupts a parallel worker that
     *  outlived its first kill. Both pushes no-op when nothing changed. */
    fun publish(d: Decision) {
        current = d
        Instrumentations.arm(d.allowed)
        if (!d.allowed) Lanes.PARALLEL.killAll("authorization revoked")
    }
}
