import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("multiloader-common")
}

val mcpVersions = the<mcp.McpVersions>()
val modId = providers.gradleProperty("mod_id").get()

// Resolvable sides of common's source injection (java / resources / kotlin dirs) + the REPL scripting runtime
// staged to build/mcp-kotlin. The consumable sides live in common/build.gradle.kts.
configurations.create("commonJava") { isCanBeResolved = true }
configurations.create("commonResources") { isCanBeResolved = true }
configurations.create("commonKotlin") { isCanBeResolved = true }
// The bootstrap bridge jar as an ARTIFACT. project(":bridge").tasks would reach into another project's task
// container (an Isolated Projects violation); a configuration carries the same build edge declaratively.
configurations.create("bridgeJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
// Kotlin scripting runtime, staged to build/mcp-kotlin and loaded by the masking loader — deliberately OFF the FML
// module path (compiler-embeddable's `org.jetbrains.kotlin.native.interop` package would make securejarhandler
// reject the automatic module at boot: 'native' is a Java keyword).
configurations.create("mcpKotlin") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // tiny-remapper's transitive ASM (9.9.1) caps at class-file major 69, past which every REPL bytecode pass
    // degrades silently. All three that survive the asm-util exclude: ASM minors are not mixable.
    resolutionStrategy.force(
        "org.ow2.asm:asm:9.10.1", "org.ow2.asm:asm-tree:9.10.1", "org.ow2.asm:asm-commons:9.10.1",
    )
    // Substitution rather than exclude-plus-add: the two carry different group ids, so Gradle would stage both
    // and leave load order to pick a winner.
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"))
            .using(module("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.10.2-intellij-1"))
            .because(
                "IntelliJ's ThreadContext calls kotlinx.coroutines.internal.intellij.IntellijCoroutines, which " +
                    "only JetBrains' fork carries. The stock artifact arrives transitively from " +
                    "compiler-embeddable, and without the fork the analysis session's pooled threads die on it.",
            )
    }
}

dependencies {
    compileOnly(project(":common")) {
        capabilities {
            requireCapability("${project.group}:$modId")
        }
    }
    "commonJava"(project(mapOf("path" to ":common", "configuration" to "commonJava")))
    "commonResources"(project(mapOf("path" to ":common", "configuration" to "commonResources")))
    "commonKotlin"(project(mapOf("path" to ":common", "configuration" to "commonKotlin")))
    "bridgeJar"(project(":bridge"))
    // 2.4.10 is required for execute_code compilation on a Java 25+ runtime.
    "mcpKotlin"("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.4.10") {
        // compiler-embeddable declares it for the CLI daemon path; an in-process host never reaches it.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-daemon-embeddable")
    }
    // kotlin-compiler-embeddable:2.4.10 drags in its BOOTSTRAP kotlin-reflect (1.6.10) transitively — a version skew
    // against the 2.4.10 stdlib. Pin reflect to 2.4.10; Gradle's highest-version conflict resolution evicts 1.6.10.
    "mcpKotlin"("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    // tiny-remapper (+ asm + mapping-io transitively) for runtime mojmap->intermediary remap of compiled script
    // bytecode on non-mojmap production runtimes. Staged into build/mcp-kotlin alongside the scripting stack.
    "mcpKotlin"("net.fabricmc:tiny-remapper:0.14.1") {
        // Declared, but nothing in tiny-remapper reaches it; asm-analysis arrives on it and leaves with it.
        exclude(group = "org.ow2.asm", module = "asm-util")
    }
    // Kotlin private/protected access: kotlin-metadata-jvm read/modify/writes the @Metadata visibility in the
    // access-widen overlay (see CompileClasspath.widenClassFile). Masking-loader tool lib, rides in mcp-kotlin.
    "mcpKotlin"("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    // The Analysis API, relocated by :common into compiler-embeddable's namespace. Compile-only for the same
    // reason as the compiler above (this loader re-compiles common's injected sources), and staged into
    // mcp-kotlin so the masking loader picks it up with no loader-side change.
    compileOnly(project(path = ":common", configuration = "analysisApi"))
    "mcpKotlin"(project(path = ":common", configuration = "analysisApi"))
    // embeddable's relocated com.intellij xmlb is @Serializable against UNRELOCATED kotlinx.serialization, which
    // Kotlin's own relocation leaves alone and embeddable never bundles — without it the Analysis API cannot read
    // its own plugin descriptors. json, not core: xmlb uses Json directly, core rides along.
    "mcpKotlin"("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    // The Analysis API's cache layer links against caffeine; no -for-ide jar carries one. Both raw-named: neither
    // belongs in relocateAnalysisApi.
    "mcpKotlin"("com.github.ben-manes.caffeine:caffeine:3.2.4")
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(configurations["commonJava"])
    source(configurations["commonJava"])
}

// Inject common's Kotlin source into this loader's compileKotlin. Guarded by plugins.withId so the convention
// stays applicable to a Kotlin-free loader.
plugins.withId("org.jetbrains.kotlin.jvm") {
    tasks.named<KotlinCompile>("compileKotlin") {
        dependsOn(configurations["commonKotlin"])
        source(configurations["commonKotlin"])
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(configurations["commonResources"])
    from(configurations["commonResources"])
}

// Inert while Dokka is applied (multiloader-common disables javadoc); fallback for a Dokka-free loader.
tasks.named<Javadoc>("javadoc") {
    dependsOn(configurations["commonJava"])
    source(configurations["commonJava"])
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(configurations["commonJava"])
    from(configurations["commonJava"])
    dependsOn(configurations["commonResources"])
    from(configurations["commonResources"])
}

// Stage the Kotlin scripting runtime into build/mcp-kotlin so the masking loader can load it off the module
// path at runtime. Sync (not Copy): mirrors the resolved mcpKotlin set into the dir, DELETING stale jars, so a kotlin
// version change doesn't leave old-version jars lingering (two kotlin versions on the masking loader = conflict).
tasks.register<Sync>("copyMcpKotlin") {
    description = "Stages the Kotlin scripting runtime (+ tiny-remapper) into build/mcp-kotlin for the REPL masking loader."
    group = "build"
    from(configurations["mcpKotlin"])
    into(layout.buildDirectory.dir("mcp-kotlin"))
}

// Run the GAME on a JDK matching the EMITTED bytecode, not the build JDK — a loader's bundled Mixin/ASM can't
// read Java 25 (class major version 69).
val runBytecodeVersion = (
    if (project.name == "neoforge")
        mcpVersions.optional("neoforge_bytecode_version").orElse(mcpVersions.required("bytecode_version"))
    else
        mcpVersions.required("bytecode_version")
    ).get().toInt()
val runLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(runBytecodeVersion))
}
val runTaskNames = listOf("runServer", "runClient", "runData", "runGameTestServer", "Server", "Client", "Data")
tasks.matching { it.name in runTaskNames }.configureEach {
    dependsOn("copyMcpKotlin")
    dependsOn(":bridge:jar")   // the bootstrap bridge jar must exist before a run injects it
}
// Pin the run JVM to the bytecode version. Done in afterEvaluate so it WINS over neoforge's MDG, whose
// ModDevRunWorkflow sets the run task's javaLauncher to the PROJECT toolchain (25) at plugin-apply time — a later
// .set() overrides that. All three loaders' run tasks are JavaExec-based (the only ones exposing javaLauncher).
project.afterEvaluate {
    tasks.withType<JavaExec>().matching { it.name in runTaskNames }.configureEach {
        javaLauncher.set(runLauncher)
    }
}

// Embed the Kotlin runtime + bridge jar as plain resources so a production server needs only the mod jar. On forge
// the shippable artifact is jarJar (classifier null); fabric/neoforge use `jar`. tasks.matching is lazy — forge's
// jarJar is created later by jarJarExt.register().
val embedTask = if (project.name == "forge") "jarJar" else "jar"
tasks.withType<Jar>().matching { it.name == embedTask }.configureEach {
    // Embed from the copyMcpKotlin STAGING DIR, not `from configurations.mcpKotlin`: forge's jarJar task silently
    // ignores `from <configuration>` (it only bundles the jarJar config as nested META-INF/jarjar jars). A plain dir
    // copy works uniformly on jar + jarJar.
    dependsOn("copyMcpKotlin")
    into("mcp-kotlin") { from(layout.buildDirectory.dir("mcp-kotlin")) }
    into("mcp-bridge") { from(configurations["bridgeJar"]) }
}
