package org.js.lolifamily.minecraftmcp

import net.minecraftforge.fml.common.Mod

// Forge entry point. The javafml mod language provider constructs this via the no-arg constructor, so keep
// it a `class` — an `object` would require kotlinforforge.
@Mod(Constants.MOD_ID)
class MinecraftMcp {
    init {
        Constants.LOG.info("Hello Forge world!")
        CommonClass.init()
    }
}
