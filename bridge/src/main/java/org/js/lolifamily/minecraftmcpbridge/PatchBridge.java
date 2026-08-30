package org.js.lolifamily.minecraftmcpbridge;

import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Bootstrap-resident trampoline. Injected into the bootstrap classloader via
 * {@code Instrumentation.appendToBootstrapClassLoaderSearch}. A patched vanilla method carries an
 * inlined {@code INVOKESTATIC PatchBridge.fireEnter(slot, key, self, args)} at its head, or
 * {@code PatchBridge.fireExit(slot, key, ...)} at every exit (woven by ByteBuddy Advice); {@code slot}
 * indexes the {@link Handler} the game classloader registered, {@code key} is only carried through to it.
 *
 * <p>One registry serves every phase: slot and key are both minted per INSTALL, so the game side hands the
 * key back as the patch id while the slot stays this class's business.
 *
 * <p>{@link #fireEnter} / {@link #fireExit} observe; {@link #fireIntercept} / {@link #fireModify} return a
 * value the advice assigns. All four share one rule: a handler that throws is caught here, and the answer
 * degrades to whatever leaves the patched method untouched.
 *
 * <p><b>Re-entrancy is unguarded, deliberately.</b> A handler that reaches the method it patched fires it
 * again until the stack ends — the caller's mistake, and their own stack trace names it. A latch would
 * also drop the nested fires a patch on a recursive method exists to see, and would cost every patch to
 * protect one from itself. The exception is the dispatch path below, which every OTHER patch runs
 * through: weaving that breaks patches its installer never wrote, so {@code Patches} refuses it.
 *
 * <p>Pure JDK only — must never reference any {@code net.minecraft.*} / loader class, so that the one
 * copy on the bootstrap loader keeps a single identity across every module layer that patched MC
 * methods live in. NeoForge {@code ModuleClassLoader} and Fabric {@code KnotClassLoader} both delegate
 * this package to bootstrap.
 */
public final class PatchBridge {

    /**
     * Whether user patches may take effect. Cleared on an authorization revoke: every woven advice early-exits
     * and the patched method behaves exactly as if it had never been patched.
     *
     * <p>Gates the EFFECT, not the SIGNATURE. The advice stays woven, the agent stays attached and the class
     * bytes stay modified, so whatever detects this mod detects it exactly as before. Not a security boundary
     * either — the token is already full RCE.
     *
     * <p>A field, not an accessor: each advice reads it as an inlined {@code GETSTATIC}, ahead of the argument
     * boxing that read exists to skip.
     */
    public static volatile boolean ARMED = true;

    /**
     * Handlers by woven slot. Hand-rolled copy-on-write, not {@code ConcurrentHashMap} /
     * {@code CopyOnWriteArrayList} / {@code AtomicReferenceArray}: each puts a class — and a hash of the key
     * with it — on the path EVERY patch dispatches through, so patching that one class would break patches
     * its installer never wrote. A volatile array read names none of them.
     *
     * <p>Slots are never reused. Advice a failed unweave left behind keeps firing, and a recycled slot would
     * hand it to whoever took the index next; burned, it reads null and no-ops. So this grows with installs
     * ever made — each of which costs a stop-the-world retransform, which is what bounds it.
     */
    private static volatile Handler[] SLOTS = new Handler[0];

    private PatchBridge() {
        throw new AssertionError("no instances");
    }

    /**
     * Register the handler for a patch slot.
     *
     * <p>Copy-on-write even when the array already fits: a plain store into the live array gives a reader no
     * happens-before, and only the volatile publish below does. Installs are rare, so copying is cheaper than
     * having to reason about when a fire would see the store.
     *
     * @param slot the index minted for this install, inlined into the woven advice as a constant
     * @param h    the game-side handler to invoke when that slot fires
     */
    public static synchronized void register(int slot, Handler h) {
        Handler[] cur = SLOTS;
        int len = slot < cur.length ? cur.length : Math.max(slot + 1, cur.length * 2);
        Handler[] next = Arrays.copyOf(cur, len);
        next[slot] = h;
        SLOTS = next;
    }

    /** Drop the handler for a patch slot, so a removed patch leaves nothing behind on the bootstrap loader.
     *  The slot itself stays burned — see {@link #SLOTS}. */
    public static synchronized void unregister(int slot) {
        Handler[] cur = SLOTS;
        if (slot >= cur.length || cur[slot] == null) {
            return;
        }
        Handler[] next = Arrays.copyOf(cur, cur.length);
        next[slot] = null;
        SLOTS = next;
    }

    /** The handler in {@code slot}: null once unregistered, and for advice stranded past its own removal. */
    private static Handler handler(int slot) {
        Handler[] s = SLOTS;   // one volatile read
        return slot < s.length ? s[slot] : null;
    }

    /**
     * Target of the inlined {@code INVOKESTATIC} woven at the head of an entry-patched method. Cheap, and
     * never throws out of the patched method — a handler's throwable is caught and logged here, not
     * propagated to the caller.
     */
    public static void fireEnter(int slot, String key, Object self, Object[] args) {
        Handler h = handler(slot);
        if (h != null) {
            try {
                h.onEnter(key, self, args);
            } catch (Throwable t) {
                System.err.println("[PatchBridge] enter handler " + key + " threw: " + t);
            }
        }
    }

    /**
     * Target of the inlined {@code INVOKESTATIC} woven at every exit of an exit-patched method, including the
     * exceptional one. Same contract as {@link #fireEnter}: cheap, and a handler's throwable never escapes —
     * which matters more here, since escaping would replace the exception the method was already throwing.
     *
     * @param returned the return value, boxed; {@code null} for {@code void} and meaningless when
     *                 {@code thrown} is non-null
     * @param thrown   the exception leaving the method, or {@code null} on a normal return
     */
    public static void fireExit(int slot, String key, Object self, Object[] args, Object returned, Throwable thrown) {
        Handler h = handler(slot);
        if (h != null) {
            try {
                h.onExit(key, self, args, returned, thrown);
            } catch (Throwable t) {
                System.err.println("[PatchBridge] exit handler " + key + " threw: " + t);
            }
        }
    }

    /**
     * Target of the inlined {@code INVOKESTATIC} woven at the head of an intercept-patched method. A throwing
     * or missing handler answers {@code null}, so the body runs exactly as if nothing were patched — the
     * contract that a broken handler cannot change behavior still holds.
     *
     * @return {@code null} to run the body, or an {@code Object[1]} carrying the substitute return value
     */
    public static Object fireIntercept(int slot, String key, MethodType sig, Object self, Object[] args) {
        Handler h = handler(slot);
        if (h == null) {
            return null;
        }
        try {
            return h.onIntercept(key, sig, self, args);
        } catch (Throwable t) {
            System.err.println("[PatchBridge] intercept handler " + key + " threw: " + t);
            return null;
        }
    }

    /**
     * Target of the inlined {@code INVOKESTATIC} woven at every exit of a modify-patched method. A throwing
     * or missing handler answers {@code returned}, which is assigned back unchanged.
     *
     * @return the method's new return value
     */
    public static Object fireModify(int slot, String key, MethodType sig, Object self, Object[] args,
                                    Object returned, Throwable thrown) {
        Handler h = handler(slot);
        if (h == null) {
            return returned;
        }
        try {
            return h.onModify(key, sig, self, args, returned, thrown);
        } catch (Throwable t) {
            System.err.println("[PatchBridge] modify handler " + key + " threw: " + t);
            return returned;
        }
    }
}
