package org.js.lolifamily.minecraftmcp.patch

import com.sun.management.HotSpotDiagnosticMXBean
import net.bytebuddy.agent.ByteBuddyAgent
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.repl.JarLocator
import org.js.lolifamily.minecraftmcpbridge.PatchBridge
import java.io.File
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory
import java.util.jar.JarFile

/**
 * Self-attach + bootstrap-bridge injection, isolated from any class that references a bridge type.
 *
 * Load-order critical: HotSpot verifies every method of a class when the class is linked, and linking [Patch]
 * loads `org.js.lolifamily.minecraftmcpbridge.Handler` (super-interface of [Patch.CountingHandler]). So the
 * bridge jar must already be on the bootstrap loader before [Patch] links — otherwise linking it fails with
 * NoClassDefFoundError before its body, which would have done the injection, ever runs.
 *
 * This object names no bridge type in a signature or supertype, so it links on its own — [Patches.onEnter] /
 * [Patches.onExit] call it before touching [Patch]. [arm]'s field write is not that: a PUTSTATIC owner resolves
 * at first execution, which its guard keeps behind the injection.
 */
object Instrumentations {

    @Volatile
    private var inst: Instrumentation? = null

    /**
     * Mirror the authorization gate into [PatchBridge.ARMED], where every woven advice reads it. What silencing
     * a patch does and does not cover is on the field.
     *
     * A no-op before [ensure]: the same `inst` that says the bridge is injected says nothing is woven yet, so
     * there is one fact here rather than a flag about it.
     */
    fun arm(on: Boolean) {
        if (inst == null || PatchBridge.ARMED == on) return
        PatchBridge.ARMED = on
        // The revoke only. A gate that flickers — an op level reading 0 for one tick after connecting — would
        // otherwise pair every line with a restore nobody debugs.
        if (!on) Constants.LOG.warn("[patch] authorization revoked — user patches inert (still woven)")
    }

    /** Idempotent: self-attach and inject the bridge jar to bootstrap, once. */
    fun ensure(): Instrumentation {
        inst?.let { return it }
        // Before the attach, which cannot be undone and buys nothing without the bridge.
        val f = bridge.getOrElse { throw IllegalStateException("Patches unavailable: no bridge jar", it) }
        // NOT `jdk.attach.allowAttachSelf`, which picks the attach route rather than whether one exists.
        // Unreadable ⇒ allow: this skips an attach known to fail, it never disables the feature (OpenJ9).
        val opt = runCatching {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                ?.getVMOption("EnableDynamicAgentLoading")?.value
        }.getOrNull()
        if (opt == "false") {
            error("this JVM disallows dynamic agent loading — add -XX:+EnableDynamicAgentLoading to use Patches")
        }
        return synchronized(this) {
            inst?.let { return it }
            try {
                val i = ByteBuddyAgent.install()
                // Closed at once, though the jar stays on the boot classpath: the JDK takes only getName(), and
                // JVMTI opens its own zip entry from that path. Ours is an fd nothing ever reads again.
                JarFile(f).use { i.appendToBootstrapClassLoaderSearch(it) }
                Constants.LOG.info("[patch] instrumentation ready; bridge injected from {}", f)
                inst = i
                i
            } catch (e: Exception) {
                throw IllegalStateException("patch bootstrap failed: $e", e)
            }
        }
    }

    /**
     * The bridge jar to inject: the dev flag if set, else the copy embedded in our own mod jar.
     *
     * Lazy because [arm] runs on the client tick and never needs the jar. [runCatching] INSIDE the lambda
     * because a `by lazy` whose initializer throws caches nothing and re-runs on every later access.
     */
    private val bridge: Result<File> by lazy {
        runCatching {
            // dev: flag points at bridge/build/libs/minecraft_mcp-bridge.jar
            val flag = Props.str("mcp.bridge.jar") ?: return@runCatching extractEmbeddedBridge()
            File(flag).also { check(it.isFile) { "mcp.bridge.jar=$it does not exist" } }
        }.onFailure { Constants.LOG.warn("[patch] no bridge jar — Patches unavailable this launch", it) }
    }

    /** For the compile classpath, where absence is survivable; [ensure] takes [bridge] itself, for the cause. */
    @JvmStatic
    val bridgeJar: File? get() = bridge.getOrNull()

    /** Production fallback: extract the bridge jar embedded under mcp-bridge/ in our own mod jar. Fixed path,
     *  no cache key — two classes are cheaper to rewrite than to validate, and a key in the PATH (the old
     *  `tmpdir/<jar>-<mtime>/`) minted a new dir per mod install that nothing ever deleted. */
    private fun extractEmbeddedBridge(): File {
        val loc = Instrumentations::class.java.protectionDomain.codeSource.location
        // Resolve across loaders: NeoForge/Forge wrap the mod jar in a securejar union: URI that
        // File(uri) rejects ("URI scheme is not file"); JarLocator goes through the union FS.
        val modJar = JarLocator.toJarFile(loc)?.takeIf { it.isFile }
            ?: error("mcp.bridge.jar unset and this is not a mod jar ($loc)")
        val cacheDir = Services.PLATFORM.cacheDir.resolve("embedded").resolve("mcp-bridge").toFile()
        cacheDir.mkdirs()
        return JarFile(modJar).use { jf ->
            val e = jf.entries().asSequence().firstOrNull {
                it.name.startsWith("mcp-bridge/") && it.name.endsWith(".jar")
            } ?: error("no mcp-bridge/*.jar embedded in $modJar")
            val dest = File(cacheDir, e.name.substring("mcp-bridge/".length))
            AtomicFiles.publishing(dest.toPath()) { tmp ->
                jf.getInputStream(e).use { input ->
                    tmp.toFile().outputStream().use { out -> input.copyTo(out) }
                }
            }
            Constants.LOG.info("[patch] extracted embedded bridge -> {}", dest)
            dest
        }
    }
}
