package org.js.lolifamily.minecraftmcp.platform

import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import org.js.lolifamily.minecraftmcp.platform.services.ModId
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import java.nio.file.FileSystems
import java.nio.file.Path

class NeoForgePlatformHelper : IPlatformHelper {

    /** Literal brand: FML publishes none this early. Version off the loader's own mod container rather than
     *  `FMLLoader.versionInfo()` — the same singleton [modVersion] uses, so no second reflective shape. */
    override val platformId: String
        get() = "NeoForge/" + ModList.get().getModContainerById("neoforge")
            .map { it.modInfo.version.toString() }
            .orElse("unknown")

    override fun isModLoaded(modId: String): Boolean = ModList.get().isLoaded(modId)

    override val isDedicatedServer: Boolean
        // Resolved reflectively across FML shapes — see fmlDist(). A genuinely unresolvable Dist is broken
        // packaging → propagate (never silently mis-gate auth).
        get() {
            val dist = fmlDist()
            return dist.javaClass.getMethod("isDedicatedServer").invoke(dist) as Boolean
        }

    // Not reflective, unlike fmlDist() below: FMLPaths kept its static shape across the 26.1 FML rework, so
    // both calls span the whole supported range.
    override val cacheDir: Path
        get() = FMLPaths.GAMEDIR.get().resolve(Constants.CACHE_DIR_NAME)

    override val configPath: Path
        get() = FMLPaths.CONFIGDIR.get().resolve("${Constants.MOD_ID}.json")

    /** Static `className.getDist()`, or null if the class/method is absent or non-static (invoke(null) on an
     *  instance method throws — that just means "this shape doesn't apply, try the next"). */
    private fun tryStaticDist(className: String): Any? =
        try { Class.forName(className).getMethod("getDist").invoke(null) } catch (_: Throwable) { null }

    /** FMLLoader.getCurrent().getDist() — the 26.1+ instance shape. */
    private fun tryCurrentInstanceDist(): Any? = try {
        val fml = Class.forName("net.neoforged.fml.loading.FMLLoader")
        fml.getMethod("getDist").invoke(fml.getMethod("getCurrent").invoke(null))
    } catch (_: Throwable) { null }

    /** Physical side [net.neoforged.api.distmarker.Dist]: static FMLLoader.getDist() (<=21.1) -> static
     *  FMLEnvironment.getDist() (26.1+) -> instance FMLLoader.getCurrent().getDist() (26.1+). */
    private fun fmlDist(): Any = tryStaticDist("net.neoforged.fml.loading.FMLLoader")
        ?: tryStaticDist("net.neoforged.fml.loading.FMLEnvironment")
        ?: tryCurrentInstanceDist()
        ?: error("neoforge: no resolvable Dist accessor (FMLLoader/FMLEnvironment.getDist)")

    override fun modCodePaths(): List<ModCode> = runCatching {
        // getModFiles()/getFile()/getFilePath() are all native (ModList stays a singleton across the whole
        // NeoForge range). Only getContents() is reflective — it does not exist before 26.1.
        //   26.1 (FML 11): getContents().getContentRoots() — real on-disk roots (JiJ is extracted to a disk cache,
        //                  so nested mods resolve too). getSecureJar() was removed here.
        //   <=21.1:        getFilePath() — the real jar, EXCEPT for a jar-in-jar mod (FML 4.x shares Forge's
        //                  JarInJarDependencyLocator verbatim), where it is the `jij:` single-file root and
        //                  toFile() returns File(""). getSecureJar().getRootPath() is that jar's union view:
        //                  a walkable class tree, which ModJarCollector repacks.
        // Skip the runtime MC mod: it would shadow the mojmap symbol jar.
        val dropMc = RemapBundle.current() != null
        ModList.get().modFiles
            .filterNot { mfi -> dropMc && mfi.mods.any { it.modId == "minecraft" } }
            .flatMap { mfi ->
                val modFile = mfi.file
                val viaContents = runCatching {
                    val c = modFile.javaClass.getMethod("getContents").invoke(modFile)
                    @Suppress("UNCHECKED_CAST")
                    (c.javaClass.getMethod("getContentRoots").invoke(c) as Collection<Path>).toList()
                }.getOrNull()
                // Empty counts as absent: a mod that contributes no content root would otherwise vanish from
                // the compile cp.
                val paths = viaContents?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(
                        modFile.filePath.takeIf { it.fileSystem == FileSystems.getDefault() }
                            ?: secureJarRoot(modFile),
                    )
                val ids = mfi.mods.map { ModId(it.modId, it.version.toString()) }
                paths.map { ModCode(it, ids) }
            }
    }.getOrDefault(emptyList())

    /** `getSecureJar().getRootPath()`, reflectively — FML 11 (26.1+) dropped getSecureJar(). */
    private fun secureJarRoot(modFile: Any): Path? = runCatching {
        val sj = modFile.javaClass.getMethod("getSecureJar").apply { isAccessible = true }.invoke(modFile)
        sj.javaClass.getMethod("getRootPath").apply { isAccessible = true }.invoke(sj) as Path
    }.getOrNull()

    // Defaulted, unlike fmlDist(): McpJson.instructions() reads this on every initialize, so a throw here is a 500
    // on the handshake. RemapCache, the one caller that needs a real answer, never runs on a mojmap runtime.
    override val minecraftVersion: String
        get() = runCatching {
            val fml = Class.forName("net.neoforged.fml.loading.FMLLoader")
            val vi = try {
                fml.getMethod("versionInfo").invoke(null)                                         // static (<=21.1)
            } catch (_: Throwable) {
                fml.getMethod("getVersionInfo").invoke(fml.getMethod("getCurrent").invoke(null))  // instance (26.1+)
            }
            vi.javaClass.getMethod("mcVersion").invoke(vi) as String
        }.getOrDefault("unknown")

    // Native: ModList stays a singleton with an instance getModContainerById() across the whole NeoForge range
    // (same singleton isModLoaded() uses above). IModInfo.getVersion() is a maven ArtifactVersion; its toString()
    // is the version string.
    override val modVersion: String
        get() = ModList.get().getModContainerById(Constants.MOD_ID)
            .map { it.modInfo.version.toString() }
            .orElse("unknown")
}
