import net.minecraftforge.jarjar.gradle.JarJarExtension

plugins {
    // Must precede multiloader-loader: with that buildSrc precompiled script plugin applied first, the
    // externally-resolved FG7 jar never reaches this project's plugin classloader scope and the request dies
    // with "Plugin with id 'net.minecraftforge.gradle' not found" — on an intact, complete artifact cache.
    id("net.minecraftforge.gradle") version "[7.0.29,8.0)"   // FG7 (stateless, mavenized toolchain)
    id("multiloader-loader")
    id("net.minecraftforge.jarjar") version "0.2.3"          // FG7: jar-in-jar is a standalone plugin
    id("net.minecraftforge.renamer") version "1.1.7"         // FG7: feeds official→srg mappings to the mixin AP
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

val mcpVersions = the<mcp.McpVersions>()
val modId = providers.gradleProperty("mod_id").get()
val mcVersion = mc.versions.minecraft.get()
val forgeVersion = mc.versions.forge.get()

// FG7's net.minecraftforge.jarjar registers a `jarJar` extension (register/configure) AND a runtime `jarJar`
// configuration + task. Capture the extension explicitly so its methods don't collide with the config accessor
// inside dependencies{}. The `jarJar` CONFIGURATION is created by register() at runtime → no compile-time accessor,
// so deps below add to it via the string-invoke "jarJar"(...).
val jarJarExt = the<JarJarExtension>()

// Run JVM args: shared values via mcp.McpRun.jvmArgs; only replClassesDir differs. forge co-locates Kotlin+Java
// in build/sourcesSets/main (co-location fix). Built here at Project scope so `layout` resolves — inside
// runs.configureEach the receiver is an FG7 run config where `layout` won't. Fed through FG7's jvmArgs() below.
val mcpArgs = mcp.McpRun.jvmArgs(project, layout.buildDirectory.dir("sourcesSets/main").get().asFile.absolutePath)

// Same reason as mcpArgs: `sourceSets` doesn't resolve against the run-config receiver inside runs.configureEach.
val mainSourceSet = sourceSets["main"]

// Runtime-naming flag. Forge switched from Mixed-SRG to official (mojmap) names AT RUNTIME in Forge 50.0.0 /
// MC 1.20.6. So ≤1.20.4 (Forge ≤49) runs on SRG field/method names and needs the mixin AP's refmap + the
// whole-jar reobf below; 1.20.6+ and unobfuscated 26.1+ run on shipped mojmap names, so that wiring is skipped
// and we ship the plain mojmap jar (a stray SRG refmap misdirects @Inject to a nonexistent m_ name → crash).
// Default OFF; the SRG node (1.18.2) opts in with `srg_runtime=true`.
val srgRuntime = mcpVersions.flag("srg_runtime").get()

// org.jetbrains:annotations excluded from the RUNTIME classpath only, so @Nullable stays visible to kotlinc at
// compile but the module clash is gone at boot.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains", module = "annotations")
}

minecraft {
    if (mcpVersions.hasVersion("parchment")) {
        val parchmentMc = mcpVersions.version("parchmentMinecraft").get()
        val parchmentVer = mcpVersions.version("parchment").get()
        mappings("parchment", "$parchmentVer-$parchmentMc")
    } else {
        mappings("official", mcVersion)
    }

    runs {
        configureEach {
            workingDir.convention(layout.projectDirectory.dir("run"))
            jvmArgs(mcpArgs)
            // FG7 builds MOD_CLASSES from this; with no mods{} the entries lose their `modid%%` prefix and FML
            // groups them by guess. Inert on newer Forge, which scans the runtime classpath instead.
            mods { create(modId).source(mainSourceSet) }
            // FG7 mixin: pass the config on the command line.
            args("--mixin.config=$modId.mixins.json")
        }
        create("client") {
            workingDir.set(file("runs/client"))
        }
        create("server") {
            workingDir.set(file("runs/server"))
        }
        create("data") {
            workingDir.set(file("runs/data"))
            args(
                "--mod", modId, "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }
    }
}

// FG7 jar-in-jar: register jarJar as the shippable all-in-one jar (classifier null = it becomes the main jar).
// register(Action<JarJar>) is the only overload that exposes archiveClassifier.
@Suppress("UnstableApiUsage")
jarJarExt.register { archiveClassifier.set(null as String?) }

sourceSets["main"].resources.srcDir("src/generated/resources")

repositories {
    minecraft.mavenizer(this)   // FG7: the mavenizer repo that supplies the patched MC artifacts
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["MixinConfigs"] = "$modId.mixins.json"
    }
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$mcVersion-$forgeVersion"))
    // SRG-runtime only: the mixin AP generates the mojmap->SRG refmap.
    if (srgRuntime) {
        annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
    }

    // ByteBuddy (core-vs-agent split: see mcp.McpRun). Compile comes from multiloader-common's compileOnly, so the
    // leaf only needs the RUNTIME half for DEV runs → runtimeOnly. jarJar below carries both into the production
    // jar; a real server gets them from there, not from this dev-only config.
    runtimeOnly("net.bytebuddy:byte-buddy:1.18.12")
    runtimeOnly("net.bytebuddy:byte-buddy-agent:1.18.12")

    // Packaging: jar-in-jar for production-only runtime libs. byte-buddy-agent stays an intact nested jar
    // (manifest Agent-Class unchanged) so self-attach works.
    // -PnoKotlinJij skips the kotlin pair, leaving it to the platform's provider — deliberately without declaring
    // a dependency on it in exchange.
    if (!providers.gradleProperty("noKotlinJij").isPresent) {
        "jarJar"("org.jetbrains.kotlin:kotlin-stdlib:2.4.10") {
            jarJarExt.configure(this) { setVersion("2.4.10") }
            // Drop annotations (kotlin-stdlib's transitive) — its module clashes with MC's on forge's module path.
            exclude(group = "org.jetbrains", module = "annotations")
        }
        // kotlin-reflect too: kotlin reflection resolves its impl through the stdlib's OWN loader, so reflect must
        // sit on the SAME loader as stdlib — a stdlib-only game loader breaks reflection (see MaskingClassLoader).
        "jarJar"("org.jetbrains.kotlin:kotlin-reflect:2.4.10") {
            jarJarExt.configure(this) { setVersion("2.4.10") }
            exclude(group = "org.jetbrains", module = "annotations")
        }
    }
    "jarJar"("net.bytebuddy:byte-buddy:1.18.12") { jarJarExt.configure(this) { setVersion("1.18.12") } }
    "jarJar"("net.bytebuddy:byte-buddy-agent:1.18.12") { jarJarExt.configure(this) { setVersion("1.18.12") } }
}

// The renamer supplies the official→srg mappings the mixin AP needs, plus the whole-jar reobf.
if (srgRuntime) {
    renamer.enableMixinRefmaps {
        config("$modId.mixins.json")
        source(sourceSets["main"]) { refMap.set("$modId.refmap.json") }
        jar(tasks.named<Jar>("jarJar"))
    }
    renamer.mappings(minecraft.dependency.toSrg)
    renamer.classes(tasks.named<Jar>("jarJar")) {
        mappings(renamer.mixin.generatedMappings)
        archiveClassifier.set("srg")
    }
    // Edge the renamer forgot: mergeMixinMappings reads compileJava's @Shadow tsrg but declares no dep on it
    // (Util.toConfiguration strips the producer) — a latent race.
    tasks.named("mergeMixinMappings") { dependsOn("compileJava") }
}

// Co-location: Java + Kotlin classes + resources (incl. META-INF/mods.toml) must ALL land in the
// single build/sourcesSets/main dir, or FML treats a lone classes dir as an automatic JPMS module "main".
sourceSets.all {
    val dir = layout.buildDirectory.dir("sourcesSets/$name")
    output.setResourcesDir(dir)
    java.destinationDirectory.set(dir)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    destinationDirectory.set(layout.buildDirectory.dir("sourcesSets/main"))
    // Without this kotlinc resolves common's Java from the shared dir above — the PREVIOUS compileJava's
    // output, stale the moment common's Java API changes.
    source(configurations["commonJava"])
}
tasks.named("compileJava") {
    dependsOn("processResources")
}
// co-location: compileKotlin wipes the shared dir on entry, so mods.toml/mixins.json must be written AFTER it.
tasks.named("processResources") {
    mustRunAfter("compileKotlin")
}
