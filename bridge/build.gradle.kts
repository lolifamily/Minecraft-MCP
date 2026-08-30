// Pure-JDK bootstrap bridge. Compiled to a standalone jar that is injected into the BOOTSTRAP
// classloader at runtime (Instrumentation.appendToBootstrapClassLoaderSearch). It must never
// reference net.minecraft.* or any loader class — only java.* — so the single copy on the bootstrap
// loader has one identity visible both to patched MC methods (which resolve it via parent delegation
// to bootstrap) and to the game-side registration/dispatch calls, which link to PatchBridge DIRECTLY
// (INVOKESTATIC) because the game loader delegates the org.js.lolifamily.minecraftmcpbridge package to bootstrap.
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    `java-library`
    id("org.jetbrains.dokka")
    // bridge cannot apply multiloader-common (see below), so it picks up the shared base directly.
    id("mcp-base")
}

val modId = providers.gradleProperty("mod_id").get()
val javaVersion = providers.gradleProperty("java_version").get().toInt()

base {
    archivesName.set("$modId-bridge")
}

// mavenCentral only, and only for Dokka's own analysis stack — bridge declares no dependencies.
repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    withJavadocJar()
}

// Javadoc jar from Dokka HTML, hand-wired: bridge cannot apply multiloader-common, which declares
// compileOnly(project(":bridge")) — inheriting it would make this project depend on itself. Its dependency block
// would also put kotlin-compiler/ByteBuddy/tiny-remapper on a classpath that must stay java.*-only, and its
// archivesName is version-stamped, which would move the jar path the loaders pin with -Dmcp.bridge.jar.
tasks.named("javadoc") { enabled = false }
tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.settingsDirectory.file("LICENSE"))
}

// Not a library — the docs are for whoever reads the code, so document everything, not just public.
dokka {
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(VisibilityModifier.entries)
    }
}

// Fixed at 17 — the floor for MC 1.18+, and a 17-class runs fine on 21/25 games.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Fixed name so the loaders can point -Dmcp.bridge.jar at a deterministic path.
tasks.named<Jar>("jar") {
    archiveFileName.set("minecraft_mcp-bridge.jar")
    from(layout.settingsDirectory.file("LICENSE"))
}