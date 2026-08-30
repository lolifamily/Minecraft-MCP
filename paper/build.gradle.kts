import xyz.wagyourtail.unimined.api.UniminedExtension

// Versions inline, as forge does for its three FG plugins. Unlike common, this needs no buildscript{}: that block
// exists there only to force ASM on the classpath Unimined rides in on, for MERGING the Java-25 Minecraft jar with
// Unimined's bundled 9.7.1. :common's Minecraft is always materialized before :paper compiles — its jar is a
// compileOnly dependency here — so by the time we look the merged artifact is a cache hit.
plugins {
    id("multiloader-loader")
    id("xyz.wagyourtail.unimined") version "1.4.1"
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val mcpVersions = the<mcp.McpVersions>()
val unobf: Boolean = mcpVersions.flag("unobfuscated").get()
val mcVersion: String = mc.versions.minecraft.get()

// Run JVM args: shared values via mcp.McpRun.jvmArgs; only replClassesDir differs. paper's Kotlin classes land in
// build/classes/kotlin/main. multiloader-loader already names `runServer` in runTaskNames, so copyMcpKotlin,
// :bridge:jar and the JVM-version pin come for free.
val mcpArgs = mcp.McpRun.jvmArgs(project, layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)

// Minecraft is here only to COMPILE common's injected source, a third of which references net.minecraft. Version and
// mappings are byte-for-byte common's — any divergence forks the cache key and turns a hit into a real remap.
configure<UniminedExtension> {
    minecraft {
        version(mcVersion)
        defaultRemapJar = false
        defaultRemapSourcesJar = false
        // Unimined's run configs launch VANILLA Minecraft, useless for a plugin, and its `runServer` would collide
        // with run-paper's. run-paper owns the dev run here.
        runs {
            off = true
        }
        if (!unobf) {
            mappings {
                mojmap()
                parchment(mcpVersions.version("parchmentMinecraft").get(), mcpVersions.version("parchment").get())
            }
        } else {
            mappings {
                devNamespace("official")
            }
        }
    }
}

repositories {
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${mc.versions.paperApi.get()}")
}

// No runtime/packaging block for the Kotlin + ByteBuddy the other loaders route through additionalRuntimeClasspath
// and jarJar/include: plugin.yml's `libraries:` covers both, so the dev run exercises the production mechanism.
tasks.runServer {
    minecraftVersion(mcVersion)
    jvmArgs(mcpArgs)
}

// Nothing on a Bukkit host reads a mixin, so common's three are not compiled at all — that, not an exclude on the
// jar, is what keeps org.spongepowered:mixin off this module's classpath. Their config file goes with them.
tasks.named<JavaCompile>("compileJava") {
    exclude("**/minecraftmcp/mixin/**")
}
tasks.named<ProcessResources>("processResources") {
    exclude("*.mixins.json")
}
