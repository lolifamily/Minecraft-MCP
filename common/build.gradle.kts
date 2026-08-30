import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import xyz.wagyourtail.unimined.api.UniminedExtension

// Unimined 1.4.1 bundles ASM 9.7.1, which throws "Unsupported class file major version 69" when it merges the
// Minecraft 26.1+ jar (Java 25). ASM is an unshaded transitive of Unimined, so apply Unimined via buildscript{}
// here and FORCE ASM 9.10.1 on that same classpath — a plugins{}-DSL application can't override a plugin's own
// transitive.
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.wagyourtail.xyz/releases") }   // Unimined + its mapping library
        maven { url = uri("https://maven.fabricmc.net/") }              // tiny-remapper, access-widener
        maven { url = uri("https://maven.minecraftforge.net/") }        // binarypatcher
    }
    dependencies {
        classpath("xyz.wagyourtail.unimined:unimined:1.4.1")
        // Shadow, for relocateAnalysisApi. Same version Kotlin builds compiler-embeddable with, so its relocator
        // behaves identically on the jars we are matching. Here rather than on the buildSrc classpath: that one is
        // shared by every subproject and documents its own conflict hazards.
        classpath("com.gradleup.shadow:shadow-gradle-plugin:9.3.2")
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-tree:9.10.1")
        classpath("org.ow2.asm:asm-commons:9.10.1")
        classpath("org.ow2.asm:asm-analysis:9.10.1")
        classpath("org.ow2.asm:asm-util:9.10.1")
    }
    configurations.named("classpath") {
        resolutionStrategy.force(
            "org.ow2.asm:asm:9.10.1", "org.ow2.asm:asm-tree:9.10.1",
            "org.ow2.asm:asm-commons:9.10.1", "org.ow2.asm:asm-analysis:9.10.1", "org.ow2.asm:asm-util:9.10.1",
        )
    }
}

plugins {
    id("multiloader-common")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}
// common's MC provider is Unimined (applied here, from buildscript{} above — see the ASM note), not moddev:
// loader-neutral and spans legacy->26.x. common only needs plain mojmap MC to compile the shared net.minecraft.*
// refs against; the loaders still reobf the injected common SOURCE against their own runtime.
apply(plugin = "xyz.wagyourtail.unimined")

val mcpVersions = the<mcp.McpVersions>()

// Deobfuscation flag, read from the active node's gradle.properties. 26.1+ ships an UNOBFUSCATED Minecraft jar
// (readable mojmap names already), and there is NO net.minecraft:client-mappings artifact for it — so mojmap()
// would 404. Obfuscated nodes still remap official→mojmap as before.
// `unobfuscated` is absent on obfuscated nodes -> flag() yields false.
val unobf: Boolean = mcpVersions.flag("unobfuscated").get()
val mcVersion: String = mc.versions.minecraft.get()
configure<UniminedExtension> {
    minecraft {
        // Plain mojmap Minecraft for the active version, no mod-loader block (vanilla).
        version(mcVersion)
        // common is never shipped as a mod jar (the loaders recompile its SOURCE against their own mappings), so
        // Unimined's default remapJar/remapSourcesJar are dead weight. Keep them OFF: it is not just a speed win —
        // Unimined only serializes cleanly for the configuration cache with remapJar disabled (see gradle.properties).
        defaultRemapJar = false
        defaultRemapSourcesJar = false
        if (!unobf) {
            mappings {
                mojmap()
                parchment(mcpVersions.version("parchmentMinecraft").get(), mcpVersions.version("parchment").get())
            }
        } else {
            // Unimined 1.4.1's mojmap() predates unobf handling (it still calls mojmapIvy → 404s on
            // client-mappings:26.1.2), so set the namespace explicitly.
            mappings {
                devNamespace("official")
            }
        }
    }
}

dependencies {
    compileOnly("org.spongepowered:mixin:0.8.7")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.10")
}

// Consumable side of common's source injection into each loader (java / resources / kotlin dirs). The loaders'
// resolvable counterparts + wiring live in multiloader-loader.
configurations.create("commonJava") {
    isCanBeResolved = false
    isCanBeConsumed = true
}
configurations.create("commonResources") {
    isCanBeResolved = false
    isCanBeConsumed = true
}
configurations.create("commonKotlin") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

artifacts {
    add("commonJava", sourceSets["main"].java.sourceDirectories.singleFile)
    add("commonResources", sourceSets["main"].resources.sourceDirectories.singleFile)
    add("commonKotlin", file("src/main/kotlin"))
}

// The Analysis API, as published for the IntelliJ Kotlin plugin: compiled against the FULL platform, so its
// bytecode names com.intellij.* — where compiler-embeddable relocated the same classes to
// org.jetbrains.kotlin.com.intellij.*. Relocated to match before anything links against it.
//
// isTransitive = false throughout: each -for-ide jar declares the whole unshaded compiler, which embeddable
// already carries under the very names we are relocating TO.
//
// Versions are not free choices. The -for-ide jars and kotlin-compiler-embeddable are built from one Kotlin
// source tree against one `:dependencies:intellij-core`, so both must be 2.4.10; intellij.platform:util must be
// the intellijSdk that 2.4.10's gradle/versions.properties pins (251.27812.49).
configurations.create("analysisApiRaw") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    listOf(
        "analysis-api-for-ide",
        "analysis-api-platform-interface-for-ide",
        "analysis-api-impl-base-for-ide",
        "analysis-api-k2-for-ide",
        "analysis-api-standalone-for-ide",
        "low-level-api-fir-for-ide",
        "symbol-light-classes-for-ide",
    ).forEach { "analysisApiRaw"("org.jetbrains.kotlin:$it:2.4.10") { isTransitive = false } }
    // Fills the Kotlin-authored platform facades intellij-core omits; ordered after the jars above. `util` alone
    // is not enough — ArrayUtil, CollectionFactory, FileUtilRt and friends live in its siblings, and without them
    // those calls link against intellij-core's thinner namesakes and throw NoSuchMethodError on first use.
    listOf("util", "util-rt", "util-base", "util-xml-dom", "util-jdom", "util-class-loader")
        .forEach { "analysisApiRaw"("com.jetbrains.intellij.platform:$it:251.27812.49") { isTransitive = false } }
    // util-base's CollectionFactory links against the full fastutil; embeddable's shaded copy is minimized to
    // its own thinner one's needs and lacks eight of them. Also the it.unimi.dsi.fastutil relocation's only input.
    "analysisApiRaw"("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.18-jb1") { isTransitive = false }
}

// Same tool and same rules Kotlin builds compiler-embeddable with (repo/gradle-build-conventions/
// gradle-plugins-common/src/main/kotlin/embeddable.kt), so the output lands on exactly the names the compiler
// already has on the masking loader. org.jetbrains.kotlin.* is deliberately NOT relocated — that half already
// matches — and neither is ASM: the compiler's is org.jetbrains.org.objectweb.asm upstream, never shaded.
val relocateAnalysisApi = tasks.register<ShadowJar>("relocateAnalysisApi") {
    description = "Relocates the Analysis API into kotlin-compiler-embeddable's shaded namespace."
    group = "build"
    configurations.set(listOf(project.configurations["analysisApiRaw"]))
    destinationDirectory.set(layout.buildDirectory.dir("mcp-analysis"))
    archiveFileName.set("analysis-api-relocated.jar")

    // Mirrors configureEmbeddableCompilerRelocation() verbatim. Deliberately the WHOLE list, not the subset the
    // Analysis API's own imports need: intellij.platform:util drags third-party refs of its own, and any prefix
    // left alone here resolves to a package embeddable does not have (fastutil is how that first showed up).
    // Order matters — com.google.protobuf must precede com.google.
    relocate("com.google.protobuf", "org.jetbrains.kotlin.protobuf")
    relocate("com.intellij", "org.jetbrains.kotlin.com.intellij") {
        // Not packages: extension-point names the IntelliJ xml reader matches as strings.
        exclude("com.intellij.projectService")
        exclude("com.intellij.applicationService")
    }
    listOf(
        "com.google", "com.sampullara", "org.apache", "org.jdom", "org.picocontainer", "org.jline",
        "net.jpountz", "one.util.streamex", "it.unimi.dsi.fastutil", "kotlinx.collections.immutable",
        "com.fasterxml", "org.codehaus", "io.opentelemetry", "io.vavr", "org.antlr", "org.tukaani.xz",
    ).forEach { relocate(it, "org.jetbrains.kotlin.$it") }
    relocate("javax.inject", "org.jetbrains.kotlin.javax.inject")
    relocate("org.fusesource", "org.jetbrains.kotlin.org.fusesource") {
        exclude("org.fusesource.jansi.internal.CLibrary")
    }

    mergeServiceFiles()
    enableKotlinModuleRemapping.set(false)   // GradleUp/shadow#1929, as Kotlin does
}

// Consumable side, for the loaders: they re-compile common's injected sources, so they need the same jar on
// their compile classpath, and it rides into the mod jar alongside the scripting runtime.
configurations.create("analysisApi") {
    isCanBeResolved = false
    isCanBeConsumed = true
}

artifacts {
    add("analysisApi", relocateAnalysisApi)
}

dependencies {
    compileOnly(files(relocateAnalysisApi))
}
repositories {
    mavenCentral()
}
