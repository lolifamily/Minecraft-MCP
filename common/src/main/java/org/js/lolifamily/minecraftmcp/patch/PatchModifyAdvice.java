package org.js.lolifamily.minecraftmcp.patch;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.js.lolifamily.minecraftmcpbridge.PatchBridge;

import java.lang.invoke.MethodType;

/**
 * Return-side decision: replace the return value. Only the exit end is woven.
 *
 * <p>{@code skipOnDefaultValue = false}, the opposite of {@link PatchInterceptAdvice}: the body ran, so the
 * slot holds a real value and "returned null" cannot mean "leave it alone" without making
 * replacement-with-null unexpressible. The bridge hands {@code returned} back instead, and every exit pays
 * one assignment.
 *
 * <p>{@link Advice.Thrown} is read-only: a throwing method still throws, and the substitute is discarded.
 *
 * @see PatchInterceptAdvice for the head-side decision
 */
public final class PatchModifyAdvice {
    private PatchModifyAdvice() {}

    /**
     * The inlined {@code @OnMethodExit} body: hand the exit to {@link PatchBridge#fireModify} and let the
     * post-processor assign whatever comes back.
     *
     * @return the method's new return value; {@code returned} itself means "unchanged"
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    @Advice.AssignReturned.AsScalar(skipOnDefaultValue = false)
    @Advice.AssignReturned.ToReturned(typing = Assigner.Typing.DYNAMIC)
    public static Object exit(@PatchSlot int slot,
                              @PatchKey String key,
                              @Advice.Origin MethodType sig,
                              @Advice.This(optional = true) Object self,
                              @Advice.AllArguments Object[] args,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable thrown) {
        // `returned`, NOT null: skipOnDefaultValue = false assigns whatever comes back, so null here would
        // REPLACE the return value rather than leave it. Same answer a missing handler gets.
        if (!PatchBridge.ARMED) return returned;
        return PatchBridge.fireModify(slot, key, sig, self, args, returned, thrown);
    }
}
