package org.js.lolifamily.minecraftmcp.platform

import net.minecraftforge.fml.loading.FMLLoader
import net.minecraftforge.fml.loading.FMLPaths
import net.minecraftforge.forgespi.language.IModFileInfo
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import org.js.lolifamily.minecraftmcp.platform.services.ModId
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import java.nio.file.FileSystems
import java.nio.file.Path

class ForgePlatformHelper : IPlatformHelper {

    /** Literal brand: FML publishes none this early. */
    override val platformId: String
        get() = "Forge/" + FMLLoader.versionInfo().forgeVersion()

    override fun isModLoaded(modId: String): Boolean {
        // forge 64.x (26.1+) turned ModList into an all-static utility (the ModList.get() singleton is gone);
        // <=1.21 forge is ModList.get().isLoaded(id).
        val ml = Class.forName("net.minecraftforge.fml.ModList")
        return try {
            ml.getMethod("isLoaded", String::class.java).invoke(null, modId) as Boolean            // static (26.1+)
        } catch (_: Throwable) {
            val inst = ml.getMethod("get").invoke(null)                                             // singleton (<=1.21)
            inst.javaClass.getMethod("isLoaded", String::class.java).invoke(inst, modId) as Boolean
        }
    }

    override val isDedicatedServer: Boolean
        get() = FMLLoader.getDist().isDedicatedServer

    // Not reflective, unlike the ModList calls below: FMLPaths kept its static shape across the 26.1 rework
    // (verified against the fmlloader sources at tag 26.1.2), so both calls span the whole supported range.
    override val cacheDir: Path
        get() = FMLPaths.GAMEDIR.get().resolve(Constants.CACHE_DIR_NAME)

    override val configPath: Path
        get() = FMLPaths.CONFIGDIR.get().resolve("${Constants.MOD_ID}.json")

    override fun modCodePaths(): List<ModCode> = runCatching {
        // getModFiles() is static on 64.x, instance on <=1.21, so that one call is reflective. The
        // getFile()->getFilePath() tail is compile-visible (forgespi) and unchanged across the whole range.
        val ml = Class.forName("net.minecraftforge.fml.ModList")
        val modFiles = try {
            ml.getMethod("getModFiles").invoke(null)                       // static (26.1+)
        } catch (_: Throwable) {
            val inst = ml.getMethod("get").invoke(null)                    // singleton (<=1.21)
            inst.javaClass.getMethod("getModFiles").invoke(inst)
        } as List<*>
        // Skip the runtime MC mod: its srg members would shadow the mojmap symbol jar.
        val dropMc = RemapBundle.current() != null
        modFiles.mapNotNull { mfi ->
            (mfi as? IModFileInfo)?.let { info ->
                if (dropMc && info.mods.any { it.modId == "minecraft" }) return@mapNotNull null
                // filePath is the real jar for a mods-folder mod, but a jar-in-jar mod's is the `jij:` root:
                // a SINGLE-FILE view of the nested jar, and its toFile() returns File("") (exists() == true,
                // being the cwd). getSecureJar().getRootPath() is that same jar's union view — a walkable
                // class tree, which ModJarCollector repacks.
                val path = runCatching { info.file.filePath }.getOrNull()
                    ?.takeIf { it.fileSystem == FileSystems.getDefault() }
                    ?: secureJarRoot(info.file) ?: return@mapNotNull null
                ModCode(path, info.mods.map { ModId(it.modId, it.version.toString()) })
            }
        }
    }.getOrDefault(emptyList())

    /** `getSecureJar().getRootPath()`, reflectively — forge 64.x (26.1+) dropped getSecureJar(). */
    private fun secureJarRoot(modFile: Any): Path? = runCatching {
        val sj = modFile.javaClass.getMethod("getSecureJar").apply { isAccessible = true }.invoke(modFile)
        sj.javaClass.getMethod("getRootPath").apply { isAccessible = true }.invoke(sj) as Path
    }.getOrNull()

    override val minecraftVersion: String
        get() = FMLLoader.versionInfo().mcVersion()

    // Same ModList static-vs-singleton split as isModLoaded. The ModContainer->IModInfo->ArtifactVersion tail is
    // invoked reflectively too; its toString() is the version string.
    override val modVersion: String
        get() = runCatching {
            val ml = Class.forName("net.minecraftforge.fml.ModList")
            val opt = try {
                ml.getMethod("getModContainerById", String::class.java).invoke(null, Constants.MOD_ID)         // static (26.1+)
            } catch (_: Throwable) {
                val inst = ml.getMethod("get").invoke(null)                                                     // singleton (<=1.21)
                inst.javaClass.getMethod("getModContainerById", String::class.java).invoke(inst, Constants.MOD_ID)
            } as java.util.Optional<*>
            val container = opt.orElse(null) ?: return@runCatching "unknown"
            val info = container.javaClass.getMethod("getModInfo").invoke(container)
            info.javaClass.getMethod("getVersion").invoke(info).toString()
        }.getOrDefault("unknown")
}
