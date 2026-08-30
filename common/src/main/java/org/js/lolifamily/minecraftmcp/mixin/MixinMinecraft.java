package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.client.Minecraft;
import org.js.lolifamily.minecraftmcp.exec.Lanes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client + render lane heartbeats, as a mixin. A client mixin, so it is never applied on a dedicated server.
 *
 * <p>{@code priority = Integer.MAX_VALUE}: pump at {@code RETURN} (postfix) as late as possible; see
 * {@link MixinMinecraftServer} for the full rationale (last-at-return + overwrite survival, best-effort).
 */
@Mixin(value = Minecraft.class, priority = Integer.MAX_VALUE)
class MixinMinecraft {

    /**
     * Client logical tick (~20/s).
     *
     * <p>No {@code require = 0}: a missing target should fail the loader on a client.
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void mcp$clientHeartbeat(CallbackInfo ci) {
        Lanes.CLIENT.pump(this);
    }

    /**
     * Render frame (framerate). {@code runTick(boolean)} is PRIVATE — Mixin injects it at the bytecode level
     * regardless of access. Required like the client tick: headless clients stub LWJGL, not the game loop, so
     * the method is there to hit on every runtime we support.
     */
    @Inject(method = "runTick(Z)V", at = @At("RETURN"))
    private void mcp$renderHeartbeat(boolean renderLevel, CallbackInfo ci) {
        Lanes.RENDER.pump(this);
    }
}
