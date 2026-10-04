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
 * <p>The heartbeat pumps at the tick's {@code RETURN} (postfix) so the eval sees the fully-settled post-tick
 * server state. RETURN callbacks run in the order their injectors are applied, and a high priority applies ours
 * after other mods' of the same injector {@code order} — best-effort, since from Mixin 0.8.7 a higher
 * {@code order} outranks any priority.
 *
 * <p>{@code 1_000_000_000}, not {@code Integer.MAX_VALUE}: upstream Mixin, which Forge ships, sorts by
 * {@code this.priority - other.priority}, and MAX_VALUE overflows that against any negative priority, which can
 * break the ordering of every mixin on the class. This value can't overflow against a priority down to
 * -1_147_483_647. Priority plays no part against an {@code @Overwrite} of {@code tickServer}: all mixins merge
 * their members before any injector scans for targets, so the injection survives an overwrite of any priority.
 */
@Mixin(value = MinecraftServer.class, priority = 1_000_000_000)
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
