package org.js.lolifamily.minecraftmcp.exec

import org.js.lolifamily.minecraftmcp.mcp.McpServer

/**
 * The minimal lane contract [McpServer] depends on to run an eval: is it runnable right now, and submit code
 * to it. Implemented by [Lane] and [ParallelLane], which share the eval machinery and differ only in what
 * drives each eval's steps — a heartbeat, or a worker loop.
 */
interface ExecLane {
    val name: String
    val isReady: Boolean

    /** [beforeStart] gets the handle after it's built but before the eval starts — where the caller registers
     *  it for cancellation, so no running eval is ever unregistered. */
    fun submit(code: String, beforeStart: (EvalHandle) -> Unit): EvalHandle
}
