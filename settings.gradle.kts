pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.wagyourtail.xyz/releases") } // Unimined (common's MC provider)
        // Plain repos, not exclusiveContent{}: Gradle 9 forbids any buildscript.repositories when pluginManagement
        // uses exclusiveContent, and fabric/build.gradle.kts needs one to classpath Loom.
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net") }               // net.fabricmc.*, fabric-loom
        maven { name = "Sponge"; url = uri("https://repo.spongepowered.org/repository/maven-public") } // org.spongepowered.*
        maven { name = "Forge"; url = uri("https://maven.minecraftforge.net") }         // net.minecraftforge.*
    }
    // Plugin versions resolve build-wide from here; the projects that apply them do so with no version. Kotlin,
    // Dokka, ben-manes, ktlint and detekt (buildSrc classpath), Loom (fabric's own buildscript{}) and Unimined
    // (common's own buildscript{}) are deliberately absent — see those files. A plugin listed BOTH here and on the
    // buildSrc classpath loads twice, and the second scope dies with "No service of type ClassLoaderScope".
    plugins {
        id("net.neoforged.moddev") version "2.0.144"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Minecraft-MCP"
// Fabric + Forge exist for every MC version we target, so they're always here. common/bridge too.
include("common")
include("fabric")
include("forge")
include("bridge")
include("paper")

// NeoForge only exists from 1.20.1, so include :neoforge ONLY when the active node ships NeoForge coordinates — a
// node without it never configures the project and can't fail on missing coords. Resolution order mirrors
// McpVersions.
val active: String = (startParameter.projectProperties["mcVersion"]
    ?: System.getenv("MCP_MC_VERSION")
    ?: file("versions/current").let { if (it.exists()) it.readText() else "1.20.6" }
).trim()

// The `mc` catalog isn't queryable here (it's realized during project resolution, not settings evaluation), so
// gate on neoforge_loader_version_range — a NeoForge-only key that stays in the node's gradle.properties.
val nodeProps = java.util.Properties()
val nodeFile = file("versions/$active/gradle.properties")
if (nodeFile.exists()) nodeFile.inputStream().use { nodeProps.load(it) }
if (nodeProps.getProperty("neoforge_loader_version_range") != null) include("neoforge")

// Expose the active node's version NUMBERS as the `mc` version catalog, selected by the same active-version
// resolution used above. from() may be called only once per catalog — `active` is a single path, so fine.
dependencyResolutionManagement {
    versionCatalogs {
        create("mc") {
            from(files("versions/$active/libs.versions.toml"))
        }
    }
}