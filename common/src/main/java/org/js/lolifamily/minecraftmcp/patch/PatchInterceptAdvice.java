package org.js.lolifamily.minecraftmcp.patch;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.js.lolifamily.minecraftmcpbridge.PatchBridge;

import java.lang.invoke.MethodType;

/**
 * Head-side decision: rewrite arguments, or skip the body and return a substitute.
 *
 * <p>Both ends are woven, but only {@link #enter} calls the bridge — {@code skipOn} is just a jump, and
 * ByteBuddy forbids writing the return value from enter advice, so {@link #exit} exists only to move the
 * decision into the return slot. The callback still fires once, at the head.
 *
 * @see PatchModifyAdvice for the return-side decision
 */
public final class PatchInterceptAdvice {
    private PatchInterceptAdvice() {}

    /**
     * Read once into a local, hand that to the bridge, store it back — none of which is decoration.
     * {@link Advice.AllArguments} has no backing variable: every READ builds a fresh array out of the
     * parameter slots, and the write distributes whatever array is on the stack into them. So a re-read would
     * hand back the original values ({@code args = args} is a perfect round trip), and only the array the
     * bridge actually mutated may be stored.
     *
     * <p>The distribution casts each element and aborts on the first bad one — inside the suppression
     * handler, so nothing escapes, but the slots ahead of it are already written. Validating in
     * {@code Patch.InterceptHandler} first is what keeps the rewrite all-or-nothing.
     *
     * @return null to run the body, or an {@code Object[1]} carrying the substitute return value
     */
    @SuppressWarnings({"UnusedAssignment", "DataFlowIssue"}) // the store IS the write-back
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Object enter(@PatchSlot int slot,
                               @PatchKey String key,
                               @Advice.Origin MethodType sig,
                               @Advice.This(optional = true) Object self,
                               @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
        // null == run the body, the answer a missing handler already gets. Ahead of the write-back below, so
        // a disarmed patch never distributes at all.
        if (!PatchBridge.ARMED) return null;
        Object[] live = args;
        Object carrier = PatchBridge.fireIntercept(slot, key, sig, self, live);
        args = live;
        return carrier;
    }

    /**
     * No bridge call, no allocation. {@code skipOnDefaultValue = true} covers all three cases: no skip keeps
     * the body's value; skip with a value assigns it; skip with null leaves the slot at the default, which
     * IS null / 0 because the body did not run. The carrier is {@code Object[]} because this cast is inlined
     * into a Minecraft class that may live on any loader, and only a JDK type resolves the same everywhere.
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Advice.AssignReturned.AsScalar()
    @Advice.AssignReturned.ToReturned(typing = Assigner.Typing.DYNAMIC)
    public static Object exit(@Advice.Enter Object carrier) {
        return carrier == null ? null : ((Object[]) carrier)[0];
    }
}
