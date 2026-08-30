package org.js.lolifamily.minecraftmcp.patch;

/**
 * Game-side exit-patch callback. A Kotlin lambda written in an {@code execute_code} snippet becomes one of
 * these via SAM conversion:
 * {@code Patches.onExit("...", "...") { key, self, args, returned, thrown -> ... }}. Runs on whatever
 * thread the patched method runs on — cheap and non-blocking is the contract.
 *
 * <p>Fires on BOTH exit paths: a normal return ({@code thrown == null}) and an exception leaving the
 * method ({@code thrown != null}).
 *
 * @see PatchEnterCallback for the entry phase
 */
@FunctionalInterface
public interface PatchExitCallback {
    /**
     * Called as the patched method leaves.
     *
     * @param key      the patch key that fired
     * @param self     the receiver of the patched method ({@code null} for static methods)
     * @param args     the argument slots as they stand at exit, boxed — the method body may have
     *                 reassigned them, so these are not necessarily the values it was called with
     * @param returned the return value, boxed; {@code null} for {@code void} methods, and meaningless
     *                 when {@code thrown} is non-null (a throwing method has no return value)
     * @param thrown   the exception leaving the method, or {@code null} on a normal return
     */
    void onExit(String key, Object self, Object[] args, Object returned, Throwable thrown);
}
