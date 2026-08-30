import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    `maven-publish`
    id("mcp-base")   // dependencyUpdates + javac encoding + checkstyle, formerly the root's allprojects{}
}

// Version coordinates for the active node — full contract on McpVersions. Registered as an extension so leaf
// scripts reach it with the<McpVersions>(); this convention uses the local instance directly.
val mcpVersions = mcp.McpVersions(project)
extensions.add("mcpVersions", mcpVersions)

// Version-independent identity (root gradle.properties).
val modId = providers.gradleProperty("mod_id").get()
val modName = providers.gradleProperty("mod_name").get()
val modAuthor = providers.gradleProperty("mod_author").get()
val licenseName = providers.gradleProperty("license").get()
val credits = providers.gradleProperty("credits").getOrElse("")
val homepage = providers.gradleProperty("homepage").getOrElse("")
val issues = providers.gradleProperty("issues").getOrElse("")
val javaVersion = providers.gradleProperty("java_version").get().toInt()

// Convention plugins get no generated `mc.versions.*` accessor, so go through the facade.
val mcVersion = mcpVersions.version("minecraft").get()

// -PnoKotlinJij yields different jar contents, so it must yield a different name — same path = silent overwrite.
// On archivesName, not a classifier: every jar task follows for free, and the classifier slot is taken (forge's
// jarJar needs null to be the shippable artifact, loom owns fabric's dev/remap pair).
val jijSuffix = if (providers.gradleProperty("noKotlinJij").isPresent) "-nokotlinjij" else ""

base {
    archivesName.set("$modId-${project.name}-$mcVersion$jijSuffix")
}

// Emitted class-file version = what the TARGET MC needs, not the build JDK. Per node: `bytecode_version`
// (neoforge may override with `neoforge_bytecode_version`).
val bytecodeVersion = (
    if (project.name == "neoforge")
        mcpVersions.optional("neoforge_bytecode_version").orElse(mcpVersions.required("bytecode_version"))
    else
        mcpVersions.required("bytecode_version")
    ).get().toInt()

// Same level as Kotlin spells it: "1.8" for 8, the bare number from 9 up. Both JvmTarget entry points match that
// string exactly — valueOf("JVM_8") and fromTarget("8") each throw — so normalize once, here.
val kotlinJvmTarget: String = if (bytecodeVersion == 8) "1.8" else "$bytecodeVersion"

// Mixin's declared compatibilityLevel (the mixins.json template's JAVA_${mixin_compat}) guards the bytecode-level
// features the mixin CLASSES use, not the JVM, so it may sit below bytecode_version. Forge's bundled Mixin caps
// lower than ours — each node sets forge_mixin_compat.
val mixinCompat = if (project.name == "forge")
    mcpVersions.optional("forge_mixin_compat").map { it.toInt() }.getOrElse(bytecodeVersion)
else
    bytecodeVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
    withJavadocJar()
}
// --release, not -target: javac also rejects JDK APIs newer than the emitted level.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeVersion)
}

// Pack javadocJar from Dokka HTML (the JDK Javadoc task is Kotlin-blind). Guarded by plugins.withId so it fires
// only where Dokka is applied (common + the three loaders).
plugins.withId("org.jetbrains.dokka") {
    tasks.named("javadoc") { enabled = false }
    tasks.named<Jar>("javadocJar") {
        from(tasks.named("dokkaGeneratePublicationHtml"))
        from(layout.settingsDirectory.file("LICENSE"))
    }
    // Not a library — the docs are for whoever reads the code, so document everything, not just public.
    configure<DokkaExtension> {
        dokkaSourceSets.configureEach {
            documentedVisibilities.set(VisibilityModifier.entries)
        }
    }
}

// No `kotlin { }` accessor here (kotlin.jvm isn't in this script's plugins{} block), so configure the extension
// by type.
plugins.withId("org.jetbrains.kotlin.jvm") {
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(javaVersion)
    }
    // Friend-path access to the scripting-compiler internals PlainEngine reaches: extractResultFields (what
    // the script lowering recorded about the result) and findExpressionForResultProperty (which trailing
    // statement becomes that result).
    val friendCfg = configurations.create("kotlinScriptingFriend") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies.add("kotlinScriptingFriend", "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.10")
    // Kotlin bytecode target = bytecodeVersion; the toolchain stays at java_version.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(kotlinJvmTarget))
            // Game-loaded classes run on the GAME's kotlin (FLK on fabric), so pin to 2.0 — that's what lets
            // fabric.mod.json require only FLK >= 1.11.0.
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
            // -Xfriend-paths resolved lazily (config resolves at execution, not configuration time).
            freeCompilerArgs.add(
                friendCfg.elements.map { locs -> "-Xfriend-paths=" + locs.joinToString(",") { it.asFile.absolutePath } }
            )
        }
    }
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "Sponge"
                url = uri("https://repo.spongepowered.org/repository/maven-public")
            }
        }
        // @Incubating, but the only clean way to say "Sponge hosts org.spongepowered and subgroups".
        @Suppress("UnstableApiUsage")
        filter { includeGroupAndSubgroups("org.spongepowered") }
    }
    exclusiveContent {
        forRepositories(
            maven {
                name = "ParchmentMC"
                url = uri("https://maven.parchmentmc.org/")
            },
            maven {
                name = "NeoForge"
                url = uri("https://maven.neoforged.net/releases")
            },
        )
        filter { includeGroup("org.parchmentmc.data") }
    }
    maven {
        name = "BlameJared"
        url = uri("https://maven.blamejared.com")
    }
    // tiny-remapper (compiled-script bytecode remap) lives on FabricMC's maven.
    maven {
        name = "FabricMC"
        url = uri("https://maven.fabricmc.net/")
    }
    // The Analysis API's `*-for-ide` jars, never published to Maven Central. content{} not exclusiveContent{}:
    // org.jetbrains.kotlin also resolves from mavenCentral, so claim only the modules this host actually has.
    maven {
        name = "IntelliJDependencies"
        url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        content {
            includeModuleByRegex("org\\.jetbrains\\.kotlin", ".*-for-ide")
            // JetBrains' coroutines fork, the only one carrying kotlinx.coroutines.internal.intellij.
            @Suppress("UnstableApiUsage")
            includeGroupAndSubgroups("org.jetbrains.intellij.deps")
        }
    }
    // com.jetbrains.intellij.platform:util — the Kotlin-authored platform facades intellij-core omits, so
    // compiler-embeddable lacks them too. Pinned to the intellijSdk of the Kotlin version we embed.
    exclusiveContent {
        forRepository {
            maven {
                name = "IntelliJPlatform"
                url = uri("https://www.jetbrains.com/intellij-repository/releases")
            }
        }
        @Suppress("UnstableApiUsage")
        filter { includeGroupAndSubgroups("com.jetbrains.intellij") }
    }
}

dependencies {

    // ══ DO NOT BUMP kotlin 2.4.10 OR byte-buddy 1.18.12 AS A ROUTINE DEPENDENCY UPDATE ══
    //
    // The two have nothing to do with each other. Each is pinned on its own, for the same reason: THIS CODE
    // REACHES DEEP INTO THAT LIBRARY'S INTERNALS — unstable, undocumented API that moves in patch releases.
    // Every site that does so carries its own note on what it reaches and why. Those notes live next to the
    // code and move with it, which is why there is no list of them here.
    //
    // A bump is an AUDIT, not an edit. That is also why these numbers are written out at every site instead of
    // living in a version catalog: a catalog entry turns this into a one-line bot PR, and such a PR COMPILES —
    // all of it is compileOnly — and only then fails, or degrades quietly.
    //
    // kotlin is pinned on three INDEPENDENT axes. Nothing in the build makes them agree:
    //   compiled against   buildSrc/multiloader-common.gradle.kts   compileOnly — which internal surface links
    //   masking runtime    buildSrc/multiloader-loader.gradle.kts   mcpKotlin, staged into mcp-kotlin/
    //   game runtime       fabric include | forge/neoforge jarJar | paper plugin.yml libraries:
    // byte-buddy has two of those (compiled against, game runtime); it never rides the masking loader.
    //
    // The skew to fear is masking vs game runtime. MaskingClassLoader.Regime is BUILT to absorb it, so it does
    // not fail: it lands in SPLIT, logs one INFO line, and stays there for good — looking exactly like a
    // legitimate SPLIT, which is a state this mod supports on purpose.

    // The Kotlin compiler, compile-only. Needed everywhere common Kotlin is compiled: common itself, and each
    // loader re-compiling the injected common sources. PlainEngine links these at compile time; at runtime they
    // live ONLY on the self-managed masking loader, never on the game/module classpath. Embeddable variants, to
    // match the runtime jars staged into mcp-kotlin. Of the scripting stack only two survive: `ScriptDiagnostic`
    // out of scripting-common, and the script IR lowering plus a few reporting helpers out of the plugin.
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-common:2.4.10")
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.10")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")

    // ByteBuddy (core-vs-agent split: see mcp.McpRun). Compile-only here; each loader puts them on its own
    // runtime classpath. The bootstrap bridge is compile-only everywhere.
    compileOnly("net.bytebuddy:byte-buddy:1.18.12")
    compileOnly("net.bytebuddy:byte-buddy-agent:1.18.12")
    compileOnly(project(":bridge"))

    // tiny-remapper + ASM, compile-only. ReplHost.kt links these to remap compiled-script bytecode
    // (mojmap -> runtime namespace) on non-mojmap production runtimes.
    compileOnly("net.fabricmc:tiny-remapper:0.14.1")
    compileOnly("org.ow2.asm:asm") { version { prefer("9.10.1") } }
    // ClassRemapper for the overlay's intermediary->mojmap rename (ClasspathWiden); tiny-remapper's own
    // transitive dep, runtime scope.
    compileOnly("org.ow2.asm:asm-commons") { version { prefer("9.10.1") } }
    // ClassNode for MixinProbe's declaration diff — Mixin's own transitive dep, so it is there whenever
    // that class has anything to do.
    compileOnly("org.ow2.asm:asm-tree") { version { prefer("9.10.1") } }
    // Kotlin private/protected access: the access-widen overlay (CompileClasspath.widenClassFile) flips the visibility in each
    // Kotlin class's @Metadata proto. kotlin-metadata-jvm is the stable read/modify/write API for that.
    compileOnly("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    // Forge Mixed SRG: RemapCacheBuilder.tsrgProvider reads forge's TSRG2 via mapping-io (tiny-remapper's
    // own transitive dep, runtime scope, so declare it explicitly for compile).
    compileOnly("net.fabricmc:mapping-io:0.9.1")
}

listOf("apiElements", "runtimeElements", "sourcesElements", "javadocElements").forEach { variant ->
    configurations.named(variant) {
        outgoing {
            capability("${project.group}:${base.archivesName.get()}:${project.version}")
            capability("${project.group}:$modId-${project.name}-$mcVersion:${project.version}")
            capability("${project.group}:$modId:${project.version}")
        }
    }
    publishing.publications.withType<MavenPublication>().configureEach {
        suppressPomMetadataWarningsFor(variant)
    }
}

// LICENSE ships inside both jars — MIT requires it in "all copies", and a jar is a copy.
tasks.named<Jar>("sourcesJar") {
    from(layout.settingsDirectory.file("LICENSE"))
    from(layout.settingsDirectory.file("README.md"))
}

tasks.named<Jar>("jar") {
    from(layout.settingsDirectory.file("LICENSE"))
    from(layout.settingsDirectory.file("README.md"))

    manifest {
        attributes(
            mapOf(
                "Specification-Title" to modName,
                "Specification-Vendor" to modAuthor,
                "Specification-Version" to archiveVersion,
                "Implementation-Title" to project.name,
                "Implementation-Version" to archiveVersion,
                "Implementation-Vendor" to modAuthor,
                "Built-On-Minecraft" to mcVersion,
            )
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    // forge/neoforge take the Maven "[a,b)" range verbatim; fabric.mod.json needs semver (">=a <b"), so each
    // version node hand-writes `fabric_minecraft_version_range`.
    val expandProps = mapOf(
        "version" to project.version,
        "group" to project.group,
        "minecraft_version" to mcVersion,
        "minecraft_version_range" to mcpVersions.required("minecraft_version_range").get(),
        "fabric_minecraft_version_range" to mcpVersions.required("fabric_minecraft_version_range").get(),
        "fabric_loader_version" to mcpVersions.version("fabricLoader").get(),
        "mod_name" to modName,
        "mod_author" to modAuthor,
        "mod_id" to modId,
        "license" to licenseName,
        "description" to project.description,
        // NeoForge coords are absent on nodes without NeoForge (e.g. 1.18) — default to "" so this map (built for
        // common/fabric/forge too) doesn't throw. Only neoforge.mods.toml consumes them, and that file is only in
        // the :neoforge subproject, which such nodes don't build.
        "neoforge_version" to mcpVersions.versionOr("neoforge", "").get(),
        "neoforge_loader_version_range" to mcpVersions.optional("neoforge_loader_version_range").getOrElse(""),
        "forge_version" to mcpVersions.version("forge").get(),
        "forge_loader_version_range" to mcpVersions.required("forge_loader_version_range").get(),
        "paper_api_level" to mcpVersions.version("paperApiLevel").get(),
        "credits" to credits,
        "homepage" to homepage,
        "issues" to issues,
        "java_version" to javaVersion,
        "bytecode_version" to bytecodeVersion,
        "mixin_compat" to mixinCompat,
    )

    // pack.mcmeta only needs to EXIST — that alone silences Forge's fml.modloading.brokenresources, a full-screen
    // ModLoadingStage.ERROR gate. pack_format is pinned, not per-version: readMetaAndCreate never rejects on it, and
    // no single value is correct anyway (1.18.2 wants 8 for resources, 9 for data; 26.1.2's range is open-ended).
    filesMatching(
        listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "*.mixins.json", "plugin.yml", "pack.mcmeta"),
    ) {
        expand(expandProps)
    }
    inputs.properties(expandProps)
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            artifactId = base.archivesName.get()
            from(components["java"])

            pom {
                licenses {
                    license {
                        name.set(licenseName)
                        url.set("https://spdx.org/licenses/$licenseName.html")
                    }
                }
            }
        }
    }
    repositories {
        // Only wire the maven repo when the env var is set — publishing isn't part of a normal build, and uri(null)
        // would fail configuration for every task.
        System.getenv("local_maven_url")?.let { repoUrl ->
            maven {
                url = uri(repoUrl)
            }
        }
    }
}
