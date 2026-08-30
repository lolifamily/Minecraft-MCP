package org.js.lolifamily.minecraftmcp.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.js.lolifamily.minecraftmcp.mcp.ChatCapture;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat capture for {@code 1.19 - 1.21}: {@code addMessage(Component, MessageSignature, GuiMessageTag)}, the one
 * entry BOTH system messages and player chat pass through — {@code /say}, {@code /me} and other players never
 * reach the 1-arg entry, which is a pure delegate into this one.
 *
 * <p>Selected by descriptor shape, not by name, because neither of those two parameter types exists on 1.18.2,
 * which this same jar also runs on: naming them fails plain javac there, and the obf-build AP has nothing to map
 * them to. A pattern selector is never remapped, so it is matched against the RUNTIME's own descriptors, and
 * "three object parameters, void" survives srg, intermediary and mojmap alike. {@link McpMixinPlugin} applied
 * this mixin only after finding that shape in the same bytes, so the selector is required to match.
 */
@Mixin(ChatComponent.class)
class MixinChatComponentFunnel {

    // @Coerce: a handler captures ALL the target's arguments or none, and none would not reach the Component.
    // allow = 1: a shape selector could match a second entry on a later version and double every line.
    @Dynamic("1.19-1.21 chat funnel; absent on 1.18.2, renamed to addPlayerMessage on 26.1+")
    @Inject(method = "desc=/^\\(L[^;]+;L[^;]+;L[^;]+;\\)V$/", at = @At("HEAD"), allow = 1)
    private void mcp$capture(Component chatComponent, @Coerce Object headerSignature, @Coerce Object tag, CallbackInfo ci) {
        ChatCapture.append(chatComponent);
    }
}
