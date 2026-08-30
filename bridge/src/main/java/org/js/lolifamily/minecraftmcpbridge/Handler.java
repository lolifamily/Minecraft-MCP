package org.js.lolifamily.minecraftmcpbridge;

import java.lang.invoke.MethodType;

/**
 * Bootstrap-resident handler interface.
 *
 * <p>Pure JDK signature — it must resolve identically on bootstrap and on the game loader.
 *
 * <p>Every method defaults to a no-op: a patch key belongs to exactly ONE phase (the key carries a
 * sequence number, so no two patches share one), and the implementation registered under it only ever
 * receives that phase's callback. Defaulting keeps a single interface and a single registry rather than
 * splitting the bridge four ways.
 */
public interface Handler {

    /**
     * Entry phase: the patched method was just entered, before its body runs.
     *
     * @param key  the patch key that fired
     * @param self the receiver of the patched method ({@code null} for static methods)
     * @param args the patched method's arguments, boxed
     */
    default void onEnter(String key, Object self, Object[] args) {
    }

    /**
     * Exit phase: the patched method is leaving, by return or by throw.
     *
     * @param key      the patch key that fired
     * @param self     the receiver of the patched method ({@code null} for static methods)
     * @param args     the argument slots as they stand at exit, boxed — the body may have reassigned
     *                 them, so these are not necessarily the values the method was called with
     * @param returned the return value, boxed; {@code null} for {@code void}, and meaningless when
     *                 {@code thrown} is non-null (a throwing method has no return value)
     * @param thrown   the exception leaving the method, or {@code null} on a normal return
     */
    default void onExit(String key, Object self, Object[] args, Object returned, Throwable thrown) {
    }

    /**
     * Head-side decision, before the body runs. Writing into {@code args} rewrites the method's arguments.
     *
     * @param key  the patch key that fired
     * @param sig  the patched method's signature, woven in per copy as a constant — parameter types validate
     *             an argument rewrite, the return type validates a substitute. Its parameter 0 is the
     *             receiver on an instance method, which {@code args} does not carry
     * @param self the receiver ({@code null} for static methods)
     * @param args the arguments, boxed; written back into the method's parameter slots on return
     * @return {@code null} to run the body, or an {@code Object[1]} whose element 0 replaces the return
     *         value — the carrier is a JDK array so the cast in the woven advice resolves on every loader
     */
    default Object onIntercept(String key, MethodType sig, Object self, Object[] args) {
        return null;
    }

    /**
     * Return-side decision, after the body ran. The value returned is always assigned, so "leave it alone"
     * is spelled by handing {@code returned} straight back — that is what makes replacing it with
     * {@code null} expressible.
     *
     * @param key      the patch key that fired
     * @param sig      the patched method's signature; only its return type matters here
     * @param self     the receiver ({@code null} for static methods)
     * @param args     the argument slots as they stand at exit, boxed
     * @param returned the return value, boxed; {@code null} for {@code void} and meaningless when
     *                 {@code thrown} is non-null
     * @param thrown   the exception leaving the method, or {@code null} on a normal return. Assigning a
     *                 return value does NOT suppress it — a throwing method still throws
     * @return the method's new return value
     */
    default Object onModify(String key, MethodType sig, Object self, Object[] args,
                            Object returned, Throwable thrown) {
        return returned;
    }
}
