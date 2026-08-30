package org.js.lolifamily.minecraftmcp.exec

/**
 * Whether a lane's tick source is up, asked of that source on demand rather than tracked in a field — nothing
 * to keep in sync, and one left over from a source that has since stopped answers `false` on its own. The
 * client and render lanes answer from the physical side alone; the server lane asks the `MinecraftServer`,
 * whose own liveness flag covers the integrated server coming and going on a client.
 */
internal fun interface LiveProbe {
    fun isLive(tickSource: Any?): Boolean
}
