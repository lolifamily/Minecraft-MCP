package org.js.lolifamily.minecraftmcp

import net.neoforged.fml.common.Mod

// NeoForge entry point. javafml instantiates the mod class via a public constructor, so this must stay a
// `class` — a Kotlin `object` has a private ctor + INSTANCE, which javafml can't call. That one public ctor
// may take any of javafml's injectable types (IEventBus, ModContainer, Dist, ...) or none; the loader only
// requires exactly one public ctor and injects each param, so an empty arg list calls this no-arg one. This
// mod registers its listeners via mixins/services, so it needs the mod bus nowhere and takes no params.
@Mod(Constants.MOD_ID)
class MinecraftMcp {
    init {
        Constants.LOG.info("Hello NeoForge world!")
        CommonClass.init()
    }
}
