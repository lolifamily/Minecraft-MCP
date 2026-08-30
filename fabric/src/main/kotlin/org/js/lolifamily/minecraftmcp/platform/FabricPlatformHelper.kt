package org.js.lolifamily.minecraftmcp.platform

import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import org.js.lolifamily.minecraftmcp.platform.services.ModId
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import java.nio.file.Path

class FabricPlatformHelper : IPlatformHelper {

    /** Literal brand — Quilt ships its own loader id rather than reusing this helper, and a Fabric-on-Bukkit
     *  hybrid is caught by RemapCache's Bukkit probe. */
    override val platformId: String
        get() = "Fabric/" + FabricLoader.getInstance().getModContainer("fabricloader")
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")

    override fun isModLoaded(modId: String): Boolean = FabricLoader.getInstance().isModLoaded(modId)

    override val isDedicatedServer: Boolean
        get() = FabricLoader.getInstance().environmentType == EnvType.SERVER

    override val cacheDir: Path
        get() = FabricLoader.getInstance().gameDir.resolve(Constants.CACHE_DIR_NAME)

    override val configPath: Path
        get() = FabricLoader.getInstance().configDir.resolve("${Constants.MOD_ID}.json")

    override fun modCodePaths(): List<ModCode> {
        // Fully native: getAllMods() already flattens nested / jar-in-jar mods into their own ModContainers,
        // each exposing getRootPaths().
        //
        // A ZipFS root rather than the jar itself, but always over a REAL jar — Fabric extracts every nested mod
        // to `.fabric/processedMods/` first, so JarLocator resolves the plain `jar:file:...!/`. getOrigin() is not
        // a substitute: it reports Kind.NESTED with no paths for exactly those mods.
        //
        // Skip the runtime MC mod when the mojmap symbol jar replaces it — thousands of useless entries to index.
        val dropMc = RemapBundle.current() != null
        val out = ArrayList<ModCode>()
        for (mc in FabricLoader.getInstance().allMods) {
            if (dropMc && mc.metadata.id == "minecraft") continue
            val ids = listOf(ModId(mc.metadata.id, mc.metadata.version.friendlyString))
            for (p in mc.rootPaths) out.add(ModCode(p, ids))
        }
        return out
    }

    override val minecraftVersion: String
        get() = FabricLoader.getInstance().getModContainer("minecraft").get()
            .metadata.version.friendlyString

    override val modVersion: String
        get() = FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
}
