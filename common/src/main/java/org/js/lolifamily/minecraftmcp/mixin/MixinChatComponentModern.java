package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.js.lolifamily.minecraftmcp.mcp.ChatCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat capture for {@code 26.1+}, which split the one entry into three siblings — two system, one player — that
 * each funnel into a private method. Siblings, not a chain, so all three capture once and none doubles.
 *
 * <p>{@code remap = false} throughout: these names exist only on 26.1+, which is unobfuscated, and the obf-build
 * AP errors on names it cannot map. {@link McpMixinPlugin} applies this mixin only where it found
 * {@code addPlayerMessage}; the other two arrived in the same Mojang change, so a missing one is an API move
 * worth a startup failure rather than a silent half-capture.
 */
@Mixin(ChatComponent.class)
class MixinChatComponentModern {

    /** The one entry that is player chat BY CONSTRUCTION — no content heuristic can be as certain. */
    @Inject(method = "addPlayerMessage", at = @At("HEAD"), remap = false)
    private void mcp$capturePlayer(Component message, @Coerce Object signature, @Coerce Object tag, CallbackInfo ci) {
        ChatCapture.append(message, true);
    }

    @Inject(method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), remap = false)
    private void mcp$captureServerSystem(Component message, CallbackInfo ci) {
        ChatCapture.append(message);
    }

    @Inject(method = "addClientSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), remap = false)
    private void mcp$captureClientSystem(Component message, CallbackInfo ci) {
        ChatCapture.append(message);
    }
}
