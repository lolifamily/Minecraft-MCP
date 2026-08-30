plugins {
    id("multiloader-loader")
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

val mcpVersions = the<mcp.McpVersions>()
val modId = providers.gradleProperty("mod_id").get()
val projectPath = project.path

// Run JVM args: shared values via mcp.McpRun.jvmArgs; only replClassesDir differs. neoforge's Kotlin classes
// land in build/classes/kotlin/main. Built here at script top level (Project scope) so `layout` resolves — inside
// runs.configureEach the receiver is RunModel where `layout`/`project` won't. Fed through MDG's jvmArgument() below.
val mcpArgs = mcp.McpRun.jvmArgs(project, layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)

// Deobfuscation flag, from the active node's gradle.properties.
val unobf = mcpVersions.flag("unobfuscated").get()

neoForge {
    version = mc.versions.neoforge.get()
    // Parchment is an OBFUSCATION-era aid (param names layered on mojmap). Unobfuscated nodes (26.1+) ship
    // Mojang's own param names and omit parchment_* — guard so those nodes don't fail on the missing coords.
    if (mcpVersions.hasVersion("parchment")) {
        parchment {
            minecraftVersion = mcpVersions.version("parchmentMinecraft").get()
            mappingsVersion = mcpVersions.version("parchment").get()
        }
    }
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            ideName = "NeoForge ${name.replaceFirstChar { it.uppercase() }} ($projectPath)"
            mcpArgs.forEach { jvmArgument(it) }
        }
        create("client") {
            client()
        }
        create("data") {
            data()

            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }
        create("server") {
            server()
        }
    }
    mods {
        create(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

sourceSets["main"].resources.srcDir("src/generated/resources")

dependencies {
    // FML does not put `implementation` deps on the game runtime classpath, so the Kotlin stdlib the mod links
    // against is missing at runtime (NoClassDefFoundError: kotlin/jvm/internal/Intrinsics), as is ByteBuddy.
    // Pre-26.1 moddev exposes `additionalRuntimeClasspath` for exactly this; 26.1+ DROPPED it, so route the same
    // deps through runtimeOnly there. Referenced by name (string-invoke) not accessor: on unobf nodes the config
    // doesn't exist, so a generated accessor wouldn't compile even in this dead branch.
    if (!unobf) {
        "additionalRuntimeClasspath"("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        "additionalRuntimeClasspath"("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
        "additionalRuntimeClasspath"("net.bytebuddy:byte-buddy:1.18.12")
        "additionalRuntimeClasspath"("net.bytebuddy:byte-buddy-agent:1.18.12")
    } else {
        runtimeOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
        runtimeOnly("net.bytebuddy:byte-buddy:1.18.12")
        runtimeOnly("net.bytebuddy:byte-buddy-agent:1.18.12")
    }

    // Packaging: jar-in-jar for production. kotlin reflection resolves its impl through the stdlib's OWN loader,
    // so reflect must sit on the SAME loader as stdlib — see MaskingClassLoader's SHARE_ALL note.
    // -PnoKotlinJij skips these, leaving kotlin to the platform's provider — deliberately without declaring a
    // dependency on it in exchange.
    if (!providers.gradleProperty("noKotlinJij").isPresent) {
        jarJar("org.jetbrains.kotlin:kotlin-stdlib") { version { strictly("2.4.10") } }
        jarJar("org.jetbrains.kotlin:kotlin-reflect") { version { strictly("2.4.10") } }
    }
    jarJar("net.bytebuddy:byte-buddy") { version { strictly("1.18.12") } }
    jarJar("net.bytebuddy:byte-buddy-agent") { version { strictly("1.18.12") } }
}
