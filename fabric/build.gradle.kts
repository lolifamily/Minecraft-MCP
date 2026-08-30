import net.fabricmc.loom.api.LoomGradleExtensionAPI

// Fabric Loom is applied via buildscript{} + apply(plugin=...) (NOT the plugins{} DSL) because its plugin id
// switches at the DEOBFUSCATION boundary: net.fabricmc.fabric-loom (no-remap) for MC 26.1+ vs classic
// fabric-loom for ≤1.21 — and the restricted plugins{} DSL can't branch a plugin id. Because it's applied (not in
// plugins{}), Loom's configurations get no generated kts accessors, so minecraft/mappings/modImplementation/include
// are addressed by name (string-invoke) and the extension via configure<LoomGradleExtensionAPI>.
//
// Both eras share ONE artifact (net.fabricmc:fabric-loom) at the SAME version, so it's a plain constant here;
// only the plugin ID differs, chosen below from the node's `unobfuscated` flag.
buildscript {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:1.17-SNAPSHOT")
    }
}

plugins {
    id("multiloader-loader")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

val mcpVersions = the<mcp.McpVersions>()
val modId = providers.gradleProperty("mod_id").get()

// Deobfuscation flag, from the active node's gradle.properties. 26.1+ ships unobfuscated → Loom must NOT remap
// (no mappings, plain `jar`); ≤1.21 stays obfuscated (mappings + remapJar). Branches the loom apply (id), and the
// dependency/mixin blocks below.
val unobf = mcpVersions.flag("unobfuscated").get()
apply(plugin = if (unobf) "net.fabricmc.fabric-loom" else "fabric-loom")
val loom = the<LoomGradleExtensionAPI>()

// Run JVM args: shared values via mcp.McpRun.jvmArgs; only replClassesDir differs. fabric's Kotlin classes
// land in build/classes/kotlin/main. Built here at Project scope so `layout` resolves — inside runs.configureEach
// the receiver is a Loom run config where `layout` won't. Fed through Loom's jvmArguments below.
val mcpArgs = mcp.McpRun.jvmArgs(project, layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)

val mcVersion = mc.versions.minecraft.get()
val fabricLoaderVersion = mc.versions.fabricLoader.get()

dependencies {
    "minecraft"("com.mojang:minecraft:$mcVersion")
    if (!unobf) {
        // Obfuscated (≤1.21): mojmap + Parchment as the mapping set Loom deobfuscates against; mods get remapped
        // (modImplementation). loom's layered-mappings DSL is @ApiStatus.Experimental with no stable alternative.
        @Suppress("UnstableApiUsage", "ktlint:standard:wrapping")
        "mappings"(loom.layered {
            officialMojangMappings()
            val parchmentMc = mcpVersions.version("parchmentMinecraft").get()
            val parchmentVer = mcpVersions.version("parchment").get()
            parchment("org.parchmentmc.data:parchment-$parchmentMc:$parchmentVer@zip")
        })
        "modImplementation"("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    } else {
        // Unobfuscated (26.1+): NO mappings (readable names ship in the jar); mods are plain deps — the
        // no-remap Loom doesn't remap them (modImplementation → implementation, per the 26.1 porting guide).
        implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    }

    // ByteBuddy (core-vs-agent split: see mcp.McpRun). Compile comes from multiloader-common's compileOnly; the
    // DEV run needs them at runtime → runtimeOnly (loom's run classpath includes runtimeOnly).
    runtimeOnly("net.bytebuddy:byte-buddy:1.18.12")
    runtimeOnly("net.bytebuddy:byte-buddy-agent:1.18.12")
    // Bundle both into the mod jar (jar-in-jar). dev loom puts `implementation` deps on the game classpath; a
    // production server does not, so without this the shipped mod has no ByteBuddy at all and both the friend
    // weave and the patch engine die. (Lane heartbeats do NOT — they are Mixins, not patches.)
    "include"("net.bytebuddy:byte-buddy:1.18.12")
    "include"("net.bytebuddy:byte-buddy-agent:1.18.12")

    // Kotlin runtime, as on forge/neoforge. Loom's generated id (org_jetbrains_kotlin_*) is the one FLK's own
    // nested copies carry, so a pack with FLK dedups by id instead of loading both.
    // -PnoKotlinJij skips these, leaving kotlin to the platform's provider — deliberately without declaring a
    // dependency on it in exchange.
    if (!providers.gradleProperty("noKotlinJij").isPresent) {
        "include"("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        "include"("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    }
}

configure<LoomGradleExtensionAPI> {
    // Mixin refmap is an OBFUSCATION artifact (mojmap→intermediary remap). Unobfuscated runtimes (26.1+)
    // resolve mixin targets by their shipped names directly, so no refmap/legacy-AP is needed — and the
    // no-remap Loom may not even expose useLegacyMixinAp. Only configure it for obfuscated nodes.
    if (!unobf) {
        // loom's MixinExtensionAPI (mixin { }, defaultRefmapName) is @ApiStatus.Experimental — suppress.
        @Suppress("UnstableApiUsage")
        mixin {
            // loom 1.12+ disables the mixin AP by default; this project still relies on it to generate the
            // refmap (required for non-mojmap production remap), so keep the legacy AP behavior explicitly.
            useLegacyMixinAp = true
            defaultRefmapName.set("$modId.refmap.json")
        }
    }
    runs {
        configureEach {
            jvmArguments.addAll(mcpArgs)
        }
        named("client") {
            client()
            displayName.set("Fabric Client")
            generateRunConfig.set(true)
            runDirectory.set(file("runs/client"))
        }
        named("server") {
            server()
            displayName.set("Fabric Server")
            generateRunConfig.set(true)
            runDirectory.set(file("runs/server"))
        }
    }
}
