package org.js.lolifamily.minecraftmcp.patch;

import net.bytebuddy.asm.Advice;
import org.js.lolifamily.minecraftmcpbridge.PatchBridge;

/**
 * The blueprint whose {@code @OnMethodEnter} body ByteBuddy copies (inlines) into the head of every
 * entry-patched method. One blueprint serves all entry patches — the per-patch {@link PatchKey} is bound
 * and {@link PatchSlot} are bound to constants at weave time, so the inlined call is
 * {@code PatchBridge.fireEnter(slot, key, this, args)}.
 *
 * @see PatchExitAdvice for the exit phase
 */
public final class PatchEnterAdvice {
    private PatchEnterAdvice() {}

    /**
     * The inlined {@code @OnMethodEnter} body: forward the fired patch to {@link PatchBridge#fireEnter}.
     *
     * @param slot the registry slot, bound as a constant at weave time (see {@link PatchSlot})
     * @param key  the patch key, bound as a constant at weave time (see {@link PatchKey})
     * @param self the receiver, or {@code null} for a static method
     * @param args the arguments the method was called with, boxed
     */
    @Advice.OnMethodEnter
    public static void enter(@PatchSlot int slot,
                             @PatchKey String key,
                             @Advice.This(optional = true) Object self,
                             @Advice.AllArguments Object[] args) {
        if (!PatchBridge.ARMED) return;
        PatchBridge.fireEnter(slot, key, self, args);
    }
}
