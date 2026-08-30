package org.js.lolifamily.minecraftmcp.exec

import java.util.concurrent.CompletableFuture

/**
 * A receipt for one eval submitted to a [Lane]: the block-until-done result future, plus the ability
 * to cancel the eval (the client-initiated counterpart to the lane's own reaping; see [cancel]).
 * Handed out by [Lane.submit]; the MCP layer holds it against the in-flight request id so a
 * `notifications/cancelled` can find and stop it.
 */
class EvalHandle internal constructor(private val task: EvalTask) {

    fun future(): CompletableFuture<Outcome> = task.future

    /** Client-initiated cancellation: completes [future] immediately with whatever the eval had printed, tagged
     *  `(cancelled)`. Idempotent and safe from any thread; a no-op if the eval already finished. */
    fun cancel() {
        task.cancel()
    }
}
