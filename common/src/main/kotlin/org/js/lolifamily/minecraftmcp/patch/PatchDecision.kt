package org.js.lolifamily.minecraftmcp.patch

/**
 * What a writable patch's callback answers with. Built only by [Patches.proceed] / [Patches.returns], so a
 * script never names this type.
 *
 * It exists to make the callback's return type something other than `Any?`. With `Any?`, a Kotlin lambda
 * whose last expression is `args[0] = x` returns `Unit` — non-null, and therefore a short-circuit with a
 * garbage value. Typed, that lambda does not compile.
 */
class PatchDecision internal constructor(
    /** False for [Patches.proceed]: run the body / keep the return value. */
    internal val replaces: Boolean,
    /** The substitute return value. Ignored unless [replaces]; may itself be null. */
    internal val value: Any?,
) {
    override fun toString(): String = if (replaces) "returns($value)" else "proceed"
}
