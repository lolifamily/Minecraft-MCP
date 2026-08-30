package org.js.lolifamily.minecraftmcp

import net.fabricmc.api.ModInitializer

// Fabric entry point. The loader's "default" (Java) language adapter constructs this via the no-arg
// constructor, so it MUST stay a `class` — an `object` (private ctor + INSTANCE) would need an
// "adapter":"kotlin" entrypoint.
class MinecraftMcp : ModInitializer {
    override fun onInitialize() {
        Constants.LOG.info("Hello Fabric world!")
        CommonClass.init()
    }
}
