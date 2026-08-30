package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.server.MinecraftServer;
import org.js.lolifamily.minecraftmcp.Constants;
import org.js.lolifamily.minecraftmcp.exec.Lanes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Server-lane heartbeat + the server-stop reap, as a mixin.
 *
 * <p>{@code priority = Integer.MAX_VALUE}: the heartbeat pumps at the tick's {@code RETURN} (postfix) so the
 * eval sees the fully-settled post-tick server state, and max priority makes it fire as late as possible
 * relative to other mods' RETURN injectors and keeps our injection alive if another mod overwrites
 * {@code tickServer} (a lower-priority overwrite is applied before us).
 */
@Mixin(value = MinecraftServer.class, priority = Integer.MAX_VALUE)
class MixinMinecraftServer {

    /**
     * Pump the server lane once per tick, at the tick's RETURN.
     *
     * <p>No {@code require = 0}: a missing target should fail the loader loudly rather than limp into a
     * silent not-ready.
     */
    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void mcp$serverHeartbeat(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Lanes.SERVER.pump(this);
    }

    /**
     * Positive server-stop signal: reap in-flight evals when the server stops (integrated quit-to-title or
     * dedicated /stop). Required like the heartbeat: the evals already queued when it stops have no later pump
     * to catch them, so a silently missing target would strand them and the callers blocked on them.
     */
    @Inject(method = "stopServer()V", at = @At("HEAD"))
    private void mcp$serverStop(CallbackInfo ci) {
        long n = Lanes.SERVER.reapOnStop("server stopped");
        if (n > 0) Constants.LOG.info("[exec] server stopped — reaped {} eval(s)", n);
    }
}
