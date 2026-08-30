package org.js.lolifamily.minecraftmcp.platform

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.java.JavaPlugin
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.MinecraftMcp
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import org.js.lolifamily.minecraftmcp.repl.CodeOrigin
import java.nio.file.Path

class PaperPlatformHelper : IPlatformHelper {

    /** ServiceLoader builds this with a no-arg ctor, so the plugin instance is resolved from our own class. */
    private val self: JavaPlugin
        get() = JavaPlugin.getProvidingPlugin(PaperPlatformHelper::class.java)

    /** Forks override both halves: `getName()` is "Purpur"/"Folia", `getVersion()` carries the build. NOT
     *  `getBukkitVersion()` — that reads "1.20.1-R0.1-SNAPSHOT" on every fork of every build. */
    override val platformId: String
        get() = "${Bukkit.getName()}/${Bukkit.getVersion()}"

    override fun isModLoaded(modId: String): Boolean = Bukkit.getPluginManager().getPlugin(modId) != null

    /** CraftBukkit and every fork of it only ever run a dedicated server, so this is a constant here. */
    override val isDedicatedServer: Boolean
        get() = true

    override val cacheDir: Path
        get() = self.dataFolder.toPath().resolve(Constants.CACHE_DIR_NAME)

    override val configPath: Path
        get() = self.dataFolder.toPath().resolve("config.json")

    /**
     * The server jar — where `net.minecraft.*` and the CraftBukkit implementation both live, so that one entry
     * covers what `minecraft` + the loader jars cover on a mod host. Plugins come through [pluginCodePaths].
     *
     * No `dropMc` here, unlike the mod hosts: MC and Bukkit share this jar. mc-symbols wins by classpath order.
     */
    override fun modCodePaths(): List<ModCode> = listOfNotNull(codeSource(Bukkit.getServer().javaClass)).map { ModCode(it, emptyList()) }

    /**
     * Every loaded plugin's jar, off the plugin's CodeSource — `JavaPlugin#getFile` is protected and
     * `PluginDescriptionFile` never carries the path.
     *
     * Asking the plugin manager beats the inherited disk scan: it names the plugins that actually loaded,
     * not every jar sitting in the directory. Their declared libraries and any jar-in-jar payload are NOT
     * here — those hang off child loaders, which `PluginJarCollector` reads live off the loaders themselves
     * so the compile classpath and `PluginBridge`'s runtime lookup share one discovery.
     */
    override fun pluginCodePaths(): List<Path> {
        val roots = LinkedHashSet<Path>()
        for (plugin in Bukkit.getPluginManager().plugins) {
            val p = codeSource(plugin.javaClass) ?: continue
            // The name is Bukkit's own plugin id, and this loop is the only place it sits next to the jar it
            // belongs to — the return type drops it. Recorded so the overlay shard survives a version bump.
            if (roots.add(p)) runCatching { CodeOrigin.mark(p.toFile(), plugin.name) }
        }
        return roots.toList()
    }

    private fun codeSource(cl: Class<*>): Path? = runCatching {
        Path.of(cl.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    override val minecraftVersion: String
        get() = Bukkit.getMinecraftVersion()

    /** `getPluginMeta()`, which deprecates this, does not exist on 1.18. `getDescription()` binds on every node
     *  in range, and its deprecation carries no `forRemoval`, so a removal would surface as a compile error. */
    @Suppress("DEPRECATION")
    override val modVersion: String
        get() = self.description.version

    /**
     * The lane's self token here is the Bukkit [Server], not a `MinecraftServer`: Paper's runtime is only
     * mojmap-named from 1.20.5, so the inherited default would not link on the 1.18 node.
     *
     * Both clauses are load-bearing. `isStopping()` reads `hasStopped`, set by the double-stop guard on
     * `stopServer`'s first line — the earliest shutdown signal a plugin can see, and the closest thing to the
     * mod path's `isRunning()`. It says nothing about a plain plugin unload, though, where the server keeps
     * ticking but our heartbeat is gone; that is what the enable flag covers.
     */
    override fun isServerRunning(handle: Any?): Boolean = handle is Server && MinecraftMcp.running && !Bukkit.isStopping()

    override fun runCommands(handle: Any, command: String): String = PaperCommandRunner.run(command)
}
