package org.js.lolifamily.minecraftmcp.patch;

/**
 * Game-side head-side decision callback, SAM-converted from a Kotlin lambda:
 * {@code Patches.intercept("...", "...") { key, self, args -> ... }}. Runs on whatever thread the patched
 * method runs on — cheap and non-blocking is the contract.
 *
 * <p>Fires before the body. Writing into {@code args} rewrites the method's arguments; the returned
 * {@link PatchDecision} says whether the body runs at all.
 *
 * @see PatchModifyCallback for the return-side decision
 */
@FunctionalInterface
public interface PatchInterceptCallback {
    /**
     * Called before the patched method's body runs.
     *
     * @param key  the patch key that fired
     * @param self the receiver of the patched method ({@code null} for static methods)
     * @param args the arguments, boxed; assignments are written back into the parameter slots
     * @return {@code Patches.proceed()} to run the body, or {@code Patches.returns(v)} to skip it and
     *         return {@code v}. A value of the wrong type is refused and counted as a handler failure,
     *         leaving the method to run normally.
     */
    PatchDecision intercept(String key, Object self, Object[] args);
}
