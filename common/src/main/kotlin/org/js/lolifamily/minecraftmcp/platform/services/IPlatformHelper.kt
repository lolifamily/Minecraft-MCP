package org.js.lolifamily.minecraftmcp.platform.services

import net.minecraft.server.MinecraftServer
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.mcp.CommandRunner
import org.js.lolifamily.minecraftmcp.repl.ModJarCollector
import java.nio.file.Path

data class ModId(val id: String, val version: String)

/** [mods] is a list because one mod FILE may declare several, and empty where the host has no mod identity
 *  to give at all (Paper's own server jar). */
data class ModCode(val path: Path, val mods: List<ModId>)

interface IPlatformHelper {

    /** Fork brand + loader build, e.g. "Purpur/git-Purpur-2062" — compared with `==`, never parsed. Keys the
     *  mojmap symbol cache, so it must change whenever the runtime's `net.minecraft` bytes could (take the
     *  brand from the runtime wherever one is published) and must never change within one build. */
    val platformId: String

    fun isModLoaded(modId: String): Boolean

    /** Physical side, a launch-time constant. Opens the auth gate once at init on a dedicated server. */
    val isDedicatedServer: Boolean

    /** The server lane's readiness probe, defaulted to the `MinecraftServer` every loader's heartbeat hands
     *  in. Here rather than inlined in `Lanes` so that class — loaded on every platform — stays free of the
     *  type, and a host that pumps the lane with something else can override. */
    fun isServerRunning(handle: Any?): Boolean = handle is MinecraftServer && handle.isRunning

    /** `run_command target=server`: run [command] (possibly multi-line) on the server thread and return its
     *  captured feedback. [handle] is the server lane's tick source. Defaulted to the NMS path, which a host
     *  whose runtime is not mojmap-named must replace wholesale rather than remap. */
    fun runCommands(handle: Any, command: String): String = CommandRunner.run(handle, command)

    /** A directory this mod owns and may write to — every REPL cache lives under it. Asked of the loader, not
     *  derived from `user.dir`: that is the process cwd, which only happens to be the game dir. Pure — callers
     *  create what they need. */
    val cacheDir: Path

    /** This mod's config FILE, laid out where each loader's users look for one. Creates neither the file nor its parent directory. */
    val configPath: Path

    /**
     * Where every loaded mod's code lives (including jar-in-jar / nested modules), for the REPL compile
     * classpath. Fabric via `FabricLoader.getAllMods()`; Forge and NeoForge via ModList -> `IModFile`.
     *
     * Return the most direct path the loader can name — a real jar or directory, not a virtual root derived
     * from one. [ModJarCollector] falls back to URI recovery and to repacking, both lossy.
     *
     * @return The code roots of all loaded mods, or an empty list if the platform can't enumerate them.
     * With a remap bundle in force, the `minecraft` mod is excluded where the host can identify it —
     * best-effort; the mojmap symbol jar replaces it as the compile reference either way.
     */
    fun modCodePaths(): List<ModCode> = emptyList()

    /**
     * Where a hybrid server's plugins live, so a script can import plugin APIs. A plugin host overrides this
     * and asks the plugin manager.
     *
     * @return null where the host cannot enumerate plugins at init, because its own init runs before the first
     * plugin loads — an empty list would be indistinguishable from "no plugins". `ReplBridge` gates the
     * compile classpath on exactly that null; the jars themselves come off the live loaders either way.
     */
    fun pluginCodePaths(): List<Path>? = null

    /** The running Minecraft version string (e.g. "1.18.2"), read from the loader — each loader knows it
     *  natively. Used to fetch version-matched remap mappings on a non-mojmap (Fabric intermediary / Forge SRG)
     *  runtime, where the processed runtime MC jar carries no version.json to read it from. */
    val minecraftVersion: String

    /** This mod's own version string, looked up FROM the loader by [Constants.MOD_ID] — the same value Gradle
     *  injected into each loader's metadata file (`fabric.mod.json` / `mods.toml` / `neoforge.mods.toml`) from
     *  `gradle.properties`. "unknown" if the loader can't resolve our own container. */
    val modVersion: String
}
