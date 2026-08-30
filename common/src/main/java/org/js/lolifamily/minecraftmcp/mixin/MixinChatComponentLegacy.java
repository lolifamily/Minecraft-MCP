package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.js.lolifamily.minecraftmcp.mcp.ChatCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat capture for {@code <=1.18.2}, where chat has no system/player split: the 1-arg entry is the only one and
 * sees everything. {@link McpMixinPlugin} applies this ONLY where no player-chat entry exists, so it never
 * coexists with {@link MixinChatComponentFunnel} — which would double every system message, the 1-arg being a
 * pure delegate into the funnel from 1.19 on.
 */
@Mixin(ChatComponent.class)
class MixinChatComponentLegacy {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void mcp$capture(Component chatComponent, CallbackInfo ci) {
        ChatCapture.append(chatComponent);
    }
}
