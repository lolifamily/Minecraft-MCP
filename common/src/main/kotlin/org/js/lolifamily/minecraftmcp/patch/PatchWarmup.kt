package org.js.lolifamily.minecraftmcp.patch

import org.js.lolifamily.minecraftmcp.Constants

/**
 * Warms the patch engine on a background thread at init, so the first script calling [Patches] doesn't pay the
 * self-attach and ByteBuddy's class loading on a tick thread — seconds of it where no flag permits a direct
 * self-attach and ByteBuddy falls back to spawning a second JVM to attach on its behalf.
 *
 * Unconditional and not configurable: there is nothing to opt out of. No class in the JVM is touched — see
 * [Patch.warm] — so all that is left is work the first [Patches] call would have done anyway, moved off a
 * tick thread that cannot interrupt it, plus on a server that never calls [Patches] the JDK's dynamic-agent
 * warning block. A flag defaulting to off would just mean nobody gets this.
 *
 * Its own object rather than a method on [Instrumentations], for the load-order reason that one documents:
 * this references [Patch].
 */
object PatchWarmup {

    fun start() {
        Thread({
            try {
                Instrumentations.ensure() // must precede any reference to Patch
                Patch.warm()
            } catch (t: Throwable) {
                // Loud but scoped: the cause lives in the chain (ENOSPC, fork limits, no attach provider), and
                // naming what still works is what keeps it from reading as a crash.
                Constants.LOG.warn("[patch] engine unavailable this launch — only Patches is affected", t)
            }
        }, "mcp-patch-warm").apply {
            isDaemon = true; priority = Thread.NORM_PRIORITY - 2; contextClassLoader = Constants.GAME_LOADER
        }.start()
    }
}
