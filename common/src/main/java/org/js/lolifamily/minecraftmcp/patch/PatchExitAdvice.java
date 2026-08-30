package org.js.lolifamily.minecraftmcp.patch;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.js.lolifamily.minecraftmcpbridge.PatchBridge;

/**
 * The blueprint whose {@code @OnMethodExit} body ByteBuddy copies (inlines) into every exit of an
 * exit-patched method. One blueprint serves all exit patches — the per-patch {@link PatchKey} is bound to
 * a constant at weave time, so the inlined call is
 * {@code PatchBridge.fireExit(slot, key, this, args, returned, thrown)}. Nothing ever calls {@link #exit}
 * itself — only its body is copied.
 *
 * <p>Two annotation details are load-bearing, not decoration:
 * <ul>
 *   <li>{@code onThrowable = Throwable.class} — without it the advice runs ONLY on a normal return, and
 *       "did this method throw?" is precisely one of the things an exit patch exists to answer. Setting it
 *       is also what makes {@link Advice.Thrown} legal. ByteBuddy implements it by wrapping the body in a
 *       try/catch, which changes the exception table and stack sizes — all inside the Code attribute, so it
 *       stays within what {@code disableClassFormatChanges()} + retransformation permit.</li>
 *   <li>{@code typing = DYNAMIC} on {@link Advice.Return} — return types vary per method and include
 *       primitives and {@code void}; only dynamic assignment collapses them all into one {@code Object}
 *       parameter. Without it ByteBuddy rejects the weave outright.</li>
 * </ul>
 *
 * @see PatchEnterAdvice for the entry phase
 */
public final class PatchExitAdvice {
    private PatchExitAdvice() {}

    /**
     * The inlined {@code @OnMethodExit} body: forward the fired patch to {@link PatchBridge#fireExit}.
     *
     * @param slot     the registry slot, bound as a constant at weave time (see {@link PatchSlot})
     * @param key      the patch key, bound as a constant at weave time (see {@link PatchKey})
     * @param self     the receiver, or {@code null} for a static method
     * @param args     the argument slots as they stand at exit, boxed — the body may have reassigned them
     * @param returned the return value, boxed; {@code null} for {@code void} and meaningless when
     *                 {@code thrown} is non-null
     * @param thrown   the exception leaving the method, or {@code null} on a normal return
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@PatchSlot int slot,
                            @PatchKey String key,
                            @Advice.This(optional = true) Object self,
                            @Advice.AllArguments Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                            @Advice.Thrown Throwable thrown) {
        if (!PatchBridge.ARMED) return;
        PatchBridge.fireExit(slot, key, self, args, returned, thrown);
    }
}
