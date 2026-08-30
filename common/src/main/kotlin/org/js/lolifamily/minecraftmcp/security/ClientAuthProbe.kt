package org.js.lolifamily.minecraftmcp.security

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.storage.WorldData
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.exec.Lane
import org.js.lolifamily.minecraftmcp.repl.Mappings
import java.lang.reflect.InvocationTargetException

/**
 * Client-only half of the [AuthGate]: computes the session-derived authorization decision from live
 * client state, on the client thread, every client tick, and publishes it to [AuthGate]'s volatile.
 * Touching `Minecraft` here keeps that link out of [AuthGate], which loads on a dedicated server; there
 * [Lane.pump] never takes the client branch, so this object is never reached.
 *
 * Rides the client-lane heartbeat, which fires every tick regardless of active evals, so the gate stays
 * fresh even when idle.
 *
 * The client-known op level can read 0 for an instant right after connecting, which merely denies for that
 * instant and self-heals on the next tick.
 */
object ClientAuthProbe {

    /** One-shot latch for the probe-failure log. [observe] runs ~20x/s, so a deterministic failure would
     *  otherwise emit a stack trace every client tick. */
    @Volatile
    private var probeFailureLogged = false

    /** Same, for the unresolved path — which [compute] reaches without throwing. */
    @Volatile
    private var probeUnresolvedLogged = false

    private val ALLOW_COMMANDS_NAMES = arrayOf("isAllowCommands", "getAllowCommands")

    /**
     * A reflective probe resolved at most once. [get] answers null until [Mappings.namesResolvable]: before
     * that, every mojmap name is a guaranteed miss, and probing anyway burns 2-3 thrown exceptions per client
     * tick — for the whole session if the mapping load failed. A resolution FAILURE is cached as a re-throw;
     * past that point the mapping table and the runtime classes are both fixed, so the miss is permanent.
     *
     * The resolution is cached, never the answer — the caller re-invokes every tick, so an op level that reads
     * 0 for an instant right after connecting still self-heals.
     */
    private class LazyProbe(private val build: () -> (Any) -> Boolean) {

        @Volatile
        private var resolved: ((Any) -> Boolean)? = null

        fun get(): ((Any) -> Boolean)? {
            resolved?.let { return it }
            if (!Mappings.namesResolvable()) return null
            val p: (Any) -> Boolean = try {
                build()
            } catch (t: Throwable) {
                { _: Any -> throw t } // re-throw the one we already have, don't build a new one per tick
            }
            resolved = p
            return p
        }
    }

    /**
     * WorldData's single-player "Allow Cheats" flag. The getter was renamed getAllowCommands() (<1.20.5) ->
     * isAllowCommands() (>=1.20.5) — inside the 1.20 jar's own version range, so no compile-time choice spans
     * it. Resolving the live method at runtime lets ONE jar span the rename.
     */
    private val cheatsProbe = LazyProbe {
        val mappings = Mappings.current()
        for (mojmap in ALLOW_COMMANDS_NAMES) {
            val name = mappings?.mapMethod("net.minecraft.world.level.storage.WorldData", mojmap) ?: mojmap
            val m = try {
                WorldData::class.java.getMethod(name) // same owner-not-javaClass rule as opProbe
            } catch (_: NoSuchMethodException) {
                continue
            }
            return@LazyProbe { wd -> m.invoke(wd) as Boolean }
        }
        throw NoSuchMethodException("WorldData: no isAllowCommands/getAllowCommands on this runtime")
    }

    /**
     * Client player's "permission level >= 3" — the `COMMANDS_ADMIN` tier that gates commands/cheats. Two
     * shapes, because the 26.1 permission rework left no single compiled name spanning the range:
     *
     *  - `<=1.21`: `Entity.hasPermissions(3)`.
     *  - `26.1+`  : the numeric level became a permission set, and op levels are hierarchical — so
     *    `permissions().hasPermission(COMMANDS_ADMIN)` IS "level >= 3".
     *
     * Neither shape resolving is a FAILURE, not a false: unknown must never read as "not an op".
     */
    private val opProbe = LazyProbe {
        // Resolved on the declaring owner — Entity, then Player — never the live player's class: getMethod on a
        // non-public runtime class returns a Method whose invoke() throws IllegalAccessException even for a method
        // declared public on a public supertype. Invocation still targets the player, so overrides dispatch.
        val legacy = Mappings.current()?.mapMethod("net.minecraft.world.entity.Entity", "hasPermissions") ?: "hasPermissions"
        val m = try {
            Entity::class.java.getMethod(legacy, Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            null // not this shape; 26.1+ below
        }
        if (m != null) return@LazyProbe { player -> m.invoke(player, 3) as Boolean }

        // Unmapped literals, unlike the legacy line above: 26.1 is the unobfuscated boundary, so reaching this
        // shape implies mojmap.
        val permIface = Class.forName("net.minecraft.server.permissions.Permission", false, Constants.MC_LOADER)
        val permSetIface = Class.forName("net.minecraft.server.permissions.PermissionSet", false, Constants.MC_LOADER)
        val perms = Class.forName("net.minecraft.server.permissions.Permissions", false, Constants.MC_LOADER)
        val perm = perms.getField("COMMANDS_ADMIN").get(null)
        val permissionsM = Player::class.java.getMethod("permissions")
        // On the interface, not a live permSet's class: a Method from one impl throws on every other.
        val hasPermM = permSetIface.getMethod("hasPermission", permIface)
        return@LazyProbe { player -> hasPermM.invoke(permissionsM.invoke(player), perm) as Boolean }
    }

    /** Compute + publish the gate. `self` is the `Minecraft` from the client-lane heartbeat. */
    fun observe(self: Any?) {
        val d = try {
            compute(self as Minecraft)
        } catch (t: Throwable) {
            // Deny rather than skip publish: skipping would freeze a possibly-ALLOW decision forever on a
            // deterministic failure. Escaping is Lane.pump's problem — it guards its whole body.
            if (!probeFailureLogged) {
                probeFailureLogged = true
                Constants.LOG.error("[auth] permission probe failed — denying until it recovers (logged once)", t)
            }
            AuthGate.DENY_PROBE_FAILED
        }
        AuthGate.publish(d)
    }

    private fun compute(mc: Minecraft): AuthGate.Decision {
        if (mc.hasSingleplayerServer()) {
            // Local authoritative server (single-player, incl. opened-to-LAN): gate on the world's cheat flag.
            // isAllowCommands() is the world "Allow Cheats" setting, distinct from isAllowCommandsForAllPlayers()
            // (the LAN-publish toggle).
            // Fail closed rather than !!: hasSingleplayerServer() and the field are two reads, and this is the
            // authorization gate — an impossible-in-practice null must deny, not NPE.
            val wd = (mc.singleplayerServer ?: return AuthGate.DENY_PROBE_FAILED).worldData
            val probe = cheatsProbe.get() ?: return AuthGate.DENY_NO_MAPPINGS
            return probeCall { probe(wd) }.fold(
                onSuccess = { if (it) AuthGate.ALLOW else AuthGate.DENY_SP_CHEATS },
                onFailure = { probeUnresolved(it) },
            )
        }
        if (mc.level != null) {
            // In a world with no local server = connected to a remote server: gate on this player's op level.
            val p = mc.player ?: return AuthGate.DENY_REMOTE_OP
            val probe = opProbe.get() ?: return AuthGate.DENY_NO_MAPPINGS
            return probeCall { probe(p) }.fold(
                onSuccess = { if (it) AuthGate.ALLOW else AuthGate.DENY_REMOTE_OP },
                onFailure = { probeUnresolved(it) },
            )
        }
        // No world to govern — this gate asks whether there is one to affect and whose it is, and `level`
        // answers exactly that. Nothing to grief; JVM access is fenced by token + loopback, as in the
        // dedicated-server case. The mappings gate sits on the two probing branches, not here — this answer
        // needs no name lookup.
        return AuthGate.ALLOW
    }

    /** Still a denial — this is the authorization gate — but named: names WERE resolvable, or [LazyProbe.get]
     *  would have returned null, so what doesn't match is the runtime's shape, not the mapping table. */
    private fun probeUnresolved(cause: Throwable): AuthGate.Decision {
        if (!probeUnresolvedLogged) {
            probeUnresolvedLogged = true
            Constants.LOG.error(
                "[auth] permission probe failed ({}) — denying until it recovers (logged once)",
                if (cause is NoSuchMethodException) {
                    "no matching name on this runtime"
                } else {
                    "name resolved, but the call failed — see cause"
                },
                cause,
            )
        }
        return AuthGate.DENY_PROBE_FAILED
    }

    /** One reflective probe step. Unwraps [InvocationTargetException] so the reflected method's OWN throwable
     *  is what gets logged — the wrapper carries no diagnosis. */
    private inline fun probeCall(block: () -> Boolean): Result<Boolean> = try {
        Result.success(block())
    } catch (e: InvocationTargetException) {
        Result.failure(e.targetException ?: e)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
