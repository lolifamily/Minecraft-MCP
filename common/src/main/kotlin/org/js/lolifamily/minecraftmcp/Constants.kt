package org.js.lolifamily.minecraftmcp

import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Constants {

    // const val: MOD_ID is an annotation argument (forge/neoforge `@Mod(Constants.MOD_ID)`), which needs a
    // compile-time constant.
    const val MOD_ID = "minecraft_mcp"
    const val MOD_NAME = "Minecraft MCP"

    /** The mod's cache directory, relative to the game dir. The one place this name is written — every reader
     *  goes through `Services.PLATFORM.cacheDir`. */
    const val CACHE_DIR_NAME = ".mcp-cache"

    // @JvmField keeps `Constants.LOG` a plain static field, so the must-stay-Java readers (the mixins,
    // ReplBridge, the masking loader) can read it as a field rather than through a getLOG() accessor.
    @JvmField
    val LOG: Logger = LoggerFactory.getLogger(MOD_NAME)

    /** The loader our own code lives on — which can always see the game, being it or a child of it (on a plugin
     *  host this is the plugin loader, whose parent is the server's). Every mcp thread sets its
     *  [Thread.contextClassLoader] to it: a thread inherits that from whoever created it, and the FML worker our
     *  init runs on carries the app loader, which sees neither MC nor any mod library — so a library resolving
     *  through the context loader fails on our threads and nowhere else. Read off this class, in the root
     *  package, so the masking loader delegates and both sides answer with one value. */
    @JvmField
    val GAME_LOADER: ClassLoader = Constants::class.java.classLoader

    /** The loader that DEFINES the game's classes — not [GAME_LOADER], which on a plugin host only delegates to
     *  it. The symbol reference is deliberate: reobf remaps it per platform, so it always names the running MC. */
    @JvmField
    val MC_LOADER: ClassLoader = net.minecraft.world.level.block.Blocks::class.java.classLoader
}
