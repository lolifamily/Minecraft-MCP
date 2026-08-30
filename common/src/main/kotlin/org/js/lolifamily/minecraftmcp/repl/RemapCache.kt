package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.CommonClass
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * First-launch remap-bundle provisioning for a NON-mojmap production runtime. The mod jar carries no
 * per-version mapping data. On a Fabric intermediary runtime with no `mcp.remap.*` flags set, this
 * downloads the two per-version artifacts that ARE published (Mojang client mappings + fabric intermediary),
 * assembles named<->intermediary, and reverse-remaps the runtime jar into a mojmap symbol jar — caching
 * everything under the mod's cache dir, one subdirectory per MC version. On later launches it's a cache hit.
 *
 * Runs on the game loader (network + JSON via MC's bundled Gson live here). The two library operations that
 * need mapping-io + tiny-remapper (assemble + reverse-remap) are done on the masking loader via
 * [ReplBridge.buildRemapArtifacts] / [ReplBridge.buildForgeArtifacts].
 */
object RemapCache {

    /** Where the concurrent halves of a build run. Named, so `supplyAsync` cannot fall back to the common
     *  ForkJoinPool: that one is sized for short CPU work — a download holds a worker for up to [Downloads]'
     *  two-minute body timeout — and its threads carry the system class loader instead of [Constants.GAME_LOADER],
     *  which every thread this mod starts sets for the reason that field documents. */
    private val FETCH = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-remap-fetch").apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }
    }

    /**
     * The remap bundle for a non-mojmap runtime, building the cache on first launch. Null on mojmap
     * (dev / NeoForge production / Fabric dev), on an unsupported namespace, and on failure — which degrades
     * this process to no-remap; the next relaunch rebuilds (an incomplete cache re-downloads).
     *
     * Called once from [CommonClass.init] on the `mcp-remap-init` thread; a second call hands back what the
     * first published rather than rebuilding.
     *
     * @param mcAnchor a loaded MC class whose CodeSource is the runtime MC jar (e.g. `Blocks.class`)
     */
    fun provision(mcAnchor: Class<*>): RemapBundle? {
        if (!NamespaceProbe.needsRemap()) return null // mojmap: source names == runtime names
        RemapBundle.current()?.let { return it }
        RemapBundle.fromFlags()?.let {
            // flags win (dev / manual / shipped bundle)
            Constants.LOG.info("[mcp-remap] using the mcp.remap.* bundle; skipping auto cache")
            return it
        }
        val ns = NamespaceProbe.current()

        try {
            // The CodeSource URI, NOT JarLocator.toJarFile(): that resolves a union DOWN to its primary backing
            // jar, which on FML is pure vanilla — the binpatches live in a second jar the union overlays on top,
            // so a symbol jar built from the file is missing every loader addition to an MC class. Travels as a
            // URI because UnionPath.toString() is "/", which no path string can be rebuilt from.
            val mcUri = mcAnchor.protectionDomain?.codeSource?.location?.toURI()?.toString()
                ?: error("cannot resolve runtime MC code source from anchor ${mcAnchor.name}")
            // Version from the loader, NOT version.json: Forge's FG7 srg jar and other processed runtime jars
            // don't carry it.
            val version = Services.PLATFORM.minecraftVersion
            val cacheDir = Services.PLATFORM.cacheDir.resolve(version)
            // Forge ships TSRG2 (srg_to_official.tsrg), Fabric and Spigot ship tiny v2 — ScriptRemap picks the
            // remapper by extension, Mappings by header line.
            val tsrg = ns == NamespaceProbe.Namespace.MIXED_SRG
            val mappings = cacheDir.resolve(if (tsrg) "mappings.tsrg" else "mappings.tiny")
            val symbolsDir = cacheDir.resolve("symbols")

            // isComplete, not hasSymbols: a cache written before deps.txt existed has to REBUILD rather than be
            // reused, or the API-dependency jars it never harvested stay silently missing from the compile cp.
            // The stamp is a third term rather than part of isComplete: that one answers "is this dir a bundle",
            // which ClasspathCollector depends on too; this one answers "was it built by THIS loader".
            if (Files.isRegularFile(mappings) && RemapBundle.isComplete(symbolsDir) &&
                runCatching { Files.readString(symbolsDir.resolve(RemapBundle.LOADER_STAMP)) }.getOrNull() == loaderStamp
            ) {
                Constants.LOG.info("[mcp-remap] reusing cached remap bundle at {}", cacheDir)
            } else {
                when (ns) {
                    NamespaceProbe.Namespace.INTERMEDIARY -> build(mcUri, version, cacheDir, mappings, symbolsDir)
                    NamespaceProbe.Namespace.MIXED_SRG -> buildForge(mcUri, version, cacheDir, mappings, symbolsDir)
                    NamespaceProbe.Namespace.SPIGOT -> buildSpigot(mcUri, version, cacheDir, mappings, symbolsDir)
                    else -> {
                        Constants.LOG.warn(
                            "[mcp-remap] {} auto-cache unsupported; supply mcp.remap.mappings + mcp.remap.classpath manually",
                            ns,
                        )
                        return null
                    }
                }
                // After the builders' own checkArtifacts, never before: a stamp over half-written artifacts is a
                // cache hit nothing can invalidate.
                AtomicFiles.publishing(symbolsDir.resolve(RemapBundle.LOADER_STAMP)) { it.toFile().writeText(loaderStamp) }
            }

            Constants.LOG.info("[mcp-remap] remap bundle ready (mappings + symbols) under {}", cacheDir)
            return RemapBundle.fromCache(mappings.toAbsolutePath(), symbolsDir.toAbsolutePath())
        } catch (t: Throwable) {
            Constants.LOG.error(
                "[mcp-remap] auto-cache failed; patches/scripts will NOT remap on this non-mojmap runtime. " +
                    "Fix networking, or supply mcp.remap.mappings + mcp.remap.classpath manually.",
                t,
            )
            return null
        }
    }

    /** What the symbol jar's bytes depend on: the loader's binpatches are baked into it, so its identity is part
     *  of the cache key. Bukkit's brand AND build ride along because a hybrid (Youer, Silkard) patches a
     *  four-figure number of MC classes on top of its base loader while reporting only the base loader's version
     *  — and its brand is a constant across its own builds, so the build half is the load-bearing one. */
    private val loaderStamp: String
        get() {
            val bukkit = runCatching {
                val c = Class.forName("org.bukkit.Bukkit", false, Constants.GAME_LOADER)
                c.getMethod("getName").invoke(null).toString() + "/" + c.getMethod("getVersion").invoke(null)
            }.getOrNull()
            return Services.PLATFORM.platformId + if (bukkit != null) "/$bukkit" else ""
        }

    /** Download the per-version sources, then hop to the masking loader to assemble + reverse-remap. */
    private fun build(mcUri: String, version: String, cacheDir: Path, mappings: Path, symbolsDir: Path) {
        Constants.LOG.warn(
            "[mcp-remap] FIRST LAUNCH on a Fabric intermediary runtime: downloading + building the remap " +
                "bundle for MC {} (one-time, ~10s + a few MB)...",
            version,
        )
        val raw = cacheDir.resolve("raw")
        Files.createDirectories(raw)
        // client_mappings, not server_mappings: modern MC's client jar carries the FULL server class set, so
        // Mojang's client proguard is a strict superset of the server one — a dedicated-server runtime is fully covered.
        // Two independent servers — download concurrently; the intermediary URL is derived from `version`
        // alone, so it needs no manifest lookup and starts immediately.
        val interFuture = java.util.concurrent.CompletableFuture.supplyAsync(
            { Downloads.download(Downloads.intermediaryUrl(version), raw.resolve("intermediary-$version-v2.jar")) },
            FETCH,
        )
        val clientTxt = Downloads.downloadClientMappings(version, raw)
        val interJar = interFuture.join()
        Constants.LOG.info(
            "[mcp-remap] downloaded client_mappings ({}B) + intermediary ({}B); assembling...",
            Files.size(clientTxt), Files.size(interJar),
        )

        ReplBridge.buildRemapArtifacts(
            clientTxt.toString(), interJar.toString(), mcUri, mappings.toString(), symbolsDir.toString(),
        )

        checkArtifacts("build", mappings, symbolsDir)
        Constants.LOG.info("[mcp-remap] built remap bundle: mappings={}B, symbols dir={}", Files.size(mappings), symbolsDir)
    }

    /** Forge Mixed-SRG variant: download MCPConfig (obf->srg) + Mojang (named<->obf), then hop to the masking
     *  loader to assemble srg_to_official.tsrg + reverse-remap the Mixed-SRG runtime jar into a mojmap symbol jar. */
    private fun buildForge(mcUri: String, version: String, cacheDir: Path, mappings: Path, symbolsDir: Path) {
        Constants.LOG.warn(
            "[mcp-remap] FIRST LAUNCH on a forge Mixed-SRG runtime: downloading MCPConfig + Mojang mappings " +
                "for MC {} (one-time)...",
            version,
        )
        val raw = cacheDir.resolve("raw")
        Files.createDirectories(raw)
        val zip = Downloads.download(Downloads.mcpConfigUrl(version), raw.resolve("mcp_config-$version.zip"))
        val joined = raw.resolve("joined.tsrg")
        Downloads.extractZipEntry(zip, "config/joined.tsrg", joined)
        val clientTxt = Downloads.downloadClientMappings(version, raw)
        Constants.LOG.info(
            "[mcp-remap] downloaded MCPConfig ({}B) + client_mappings ({}B); assembling srg<->named...",
            Files.size(zip), Files.size(clientTxt),
        )

        ReplBridge.buildForgeArtifacts(
            joined.toString(), clientTxt.toString(), mcUri, mappings.toString(), symbolsDir.toString(),
        )

        checkArtifacts("forge build", mappings, symbolsDir)
        Constants.LOG.info("[mcp-remap] built forge remap bundle: mappings={}B, symbols dir={}", Files.size(mappings), symbolsDir)
    }

    /** Spigot variant: Mojang's client_mappings (named<->obf) + BuildData's class csrg (obf->spigot), pivoted on
     *  obf. Two sources for the same reason fabric needs two — BOTH axes move on a spigot runtime. Members are
     *  the obf names, so proguard carries them; classes are spigot's OWN names (`BlockPos` is `BlockPosition`),
     *  which no Mojang artifact can express, so they take the second source. */
    private fun buildSpigot(mcUri: String, version: String, cacheDir: Path, mappings: Path, symbolsDir: Path) {
        Constants.LOG.warn(
            "[mcp-remap] FIRST LAUNCH on a spigot-mapped runtime: downloading Mojang + BuildData mappings " +
                "for MC {} (one-time)...",
            version,
        )
        val raw = cacheDir.resolve("raw")
        Files.createDirectories(raw)
        // Two independent hosts — download concurrently.
        val csrgFuture = java.util.concurrent.CompletableFuture.supplyAsync(
            { Downloads.downloadSpigotClassMappings(version, raw) },
            FETCH,
        )
        val clientTxt = Downloads.downloadClientMappings(version, raw)
        val clCsrg = csrgFuture.join()
        Constants.LOG.info(
            "[mcp-remap] downloaded client_mappings ({}B) + spigot class mappings ({}B); assembling...",
            Files.size(clientTxt), Files.size(clCsrg),
        )

        ReplBridge.buildRemapArtifacts(clientTxt.toString(), clCsrg.toString(), mcUri, mappings.toString(), symbolsDir.toString())

        checkArtifacts("spigot build", mappings, symbolsDir)
        Constants.LOG.info("[mcp-remap] built spigot remap bundle: mappings={}B, symbols dir={}", Files.size(mappings), symbolsDir)
    }

    /** The builders hop to the masking loader and return void, so the artifacts landing on disk is their only
     *  success signal. [label] names the variant in the failure. */
    private fun checkArtifacts(label: String, mappings: Path, symbolsDir: Path) {
        check(Files.isRegularFile(mappings) && RemapBundle.isComplete(symbolsDir)) {
            "$label produced no artifacts " +
                "(mappings=${Files.isRegularFile(mappings)}, symbols=${RemapBundle.isComplete(symbolsDir)})"
        }
    }
}
