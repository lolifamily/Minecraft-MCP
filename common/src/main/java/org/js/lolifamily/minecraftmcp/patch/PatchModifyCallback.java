package org.js.lolifamily.minecraftmcp.patch;

/**
 * Game-side return-side decision callback, SAM-converted from a Kotlin lambda:
 * {@code Patches.modify("...", "...") { key, self, args, returned, thrown -> ... }}. Runs on whatever
 * thread the patched method runs on — cheap and non-blocking is the contract.
 *
 * <p>Fires after the body, on BOTH exit paths. Assigning a return value does not suppress a pending
 * exception: a throwing method still throws, and the substitute is discarded.
 *
 * @see PatchInterceptCallback for the head-side decision
 */
@FunctionalInterface
public interface PatchModifyCallback {
    /**
     * Called as the patched method leaves, on both exit paths.
     *
     * @param key      the patch key that fired
     * @param self     the receiver of the patched method ({@code null} for static methods)
     * @param args     the argument slots as they stand at exit, boxed. Read-only here — the body has
     *                 already run, so writing them changes nothing
     * @param returned the return value, boxed; {@code null} for {@code void} methods, and meaningless
     *                 when {@code thrown} is non-null
     * @param thrown   the exception leaving the method, or {@code null} on a normal return
     * @return {@code Patches.proceed()} to keep {@code returned}, or {@code Patches.returns(v)} to replace
     *         it. A value of the wrong type is refused and counted as a handler failure.
     */
    PatchDecision modify(String key, Object self, Object[] args, Object returned, Throwable thrown);
}
