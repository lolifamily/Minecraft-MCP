package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.js.lolifamily.minecraftmcp.repl.ReplBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The plugin gate: a hybrid server loads and enables its plugins inside this method, so from its RETURN a mod
 * host's plugin classpath is finally complete. Everything else — vanilla, a plain mod server — fires it too
 * and finds the latch already open.
 *
 * <p>Not {@code MinecraftServer.loadLevel}, which is nearer the plugin enable but which CraftBukkit gives a
 * {@code String} parameter: the refmap pins the descriptor the AP resolved, so a selector written against
 * vanilla stops matching there. This signature no hybrid touches.
 */
@Mixin(DedicatedServer.class)
class MixinDedicatedServer {

    @Inject(method = "initServer()Z", at = @At("RETURN"))
    private void mcp$pluginsReady(CallbackInfoReturnable<Boolean> cir) {
        ReplBridge.pluginsLatch.countDown();
    }
}
