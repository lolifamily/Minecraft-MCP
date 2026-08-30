package mcp

import org.gradle.api.Project

/**
 * Shared dev-run JVM args for all three loaders. The VALUES are identical everywhere — REPL masking-loader
 * inputs (`-Dmcp.kotlin.libs` / `-Dmcp.repl.classes`), patch-engine self-attach
 * (`-Djdk.attach.allowAttachSelf` / `-XX:+EnableDynamicAgentLoading` — needed by `Patches.onEnter/onExit` ONLY; the REPL
 * and the preload training both work without them), and
 * the bootstrap bridge jar (`-Dmcp.bridge.jar`). Only `replClassesDir` varies (forge co-locates its Kotlin
 * classes in build/sourcesSets/main; loom/MDG use build/classes/kotlin/main).
 *
 * That same split governs the two ByteBuddy jars the loaders ship: byte-buddy (core) drives the Advice
 * weaving and byte-buddy-agent is self-attach — both now used by the patch engine alone.
 *
 * No `--add-modules=jdk.httpserver` on purpose — see the README's Configuration section.
 *
 * Each loader maps the returned list through its OWN run DSL (loom vmArg / FG jvmArgs / MDG jvmArgument); the
 * run-config types share no interface.
 */
@Suppress("unused") // called from the loader leaf scripts (fabric/forge/neoforge), invisible to buildSrc's own analysis
object McpRun {
    @JvmStatic
    fun jvmArgs(project: Project, replClassesDir: String): List<String> = listOf(
        "-Dmcp.kotlin.libs=" + project.layout.buildDirectory.dir("mcp-kotlin").get().asFile.absolutePath,
        "-Dmcp.repl.classes=$replClassesDir",
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading",
        // settingsDirectory, not rootProject.file — same path, reached without touching another Project.
        "-Dmcp.bridge.jar=" + project.layout.settingsDirectory.file("bridge/build/libs/minecraft_mcp-bridge.jar").asFile.absolutePath,
    )
}
