package org.js.lolifamily.minecraftmcp.patch;

/**
 * Game-side entry-patch callback. A Kotlin lambda written in an {@code execute_code} snippet becomes one
 * of these via SAM conversion:
 * {@code Patches.onEnter("...", "...") { key, self, args -> ... }}. Runs on whatever thread the patched
 * method runs on — cheap and non-blocking is the contract.
 *
 * @see PatchExitCallback for the exit phase
 */
@FunctionalInterface
public interface PatchEnterCallback {
    /**
     * Called as the patched method is entered, before its body runs.
     *
     * @param key  the patch key that fired
     * @param self the receiver of the patched method ({@code null} for static methods)
     * @param args the patched method's arguments, boxed
     */
    void onEnter(String key, Object self, Object[] args);
}
