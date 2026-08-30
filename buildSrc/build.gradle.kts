plugins {
    // `kotlin-dsl` compiles the src/main/kotlin helpers (mcp.McpVersions / McpRun / PropertiesFileValueSource) AND
    // the precompiled *.gradle.kts convention plugins together, in one source set — so the convention scripts see the
    // helper classes with no extra classpath wiring.
    `kotlin-dsl`
    // Versioned: the dependency below feeds the main build's buildscript, not buildSrc's own. Keep both in sync.
    id("org.jetbrains.dokka") version "2.2.0"
}

// The Kotlin Gradle plugin is a buildSrc DEPENDENCY, not applied: multiloader-common references JvmTarget /
// KotlinCompile directly, and buildSrc's classpath is the one classloader every subproject shares — so all four get
// a SINGLE plugin load, no "plugin loaded multiple times in different subprojects" warning. Subprojects declare it
// with no version. (`kotlin-dsl` applies Gradle's embedded Kotlin only to compile buildSrc; the mod's own kotlin
// is this 2.4.10.)
repositories {
    gradlePluginPortal()
    mavenCentral()
}

// HTML only — buildSrc publishes nothing, so no javadocJar (DGPv2 enables just HTML by default).
// ./gradlew -p buildSrc dokkaGeneratePublicationHtml -> buildSrc/build/dokka/html
dokka {
    dokkaSourceSets.named("main") {
        // `kotlin-dsl` puts its generated adapters in this same source set and suppressGeneratedFiles misses
        // build/generated-sources — without this the docs carry a [root] package of *Plugin glue.
        sourceRoots.setFrom(file("src/main/kotlin"))
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    // Dokka — same buildSrc-classpath route as the Kotlin plugin above, so the multiloader-common convention's
    // `plugins.withId("org.jetbrains.dokka")` reacts to leaves applying it.
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    // Same route, so the mcp-base convention can reference DependencyUpdatesTask / KtlintExtension /
    // DetektExtension directly. The rule for all three: on this classpath AND out of settings.gradle.kts's
    // pluginManagement — listed in both, a plugin loads twice and the second scope dies with
    // "No service of type ClassLoaderScope". None of them drags a kotlin-gradle-plugin of its own.
    implementation("io.github.ben-manes:gradle-versions-plugin:0.58.0")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
    // 2.0.0-alpha line (new plugin id 'dev.detekt'): stable 1.23.8 rejects our JDK-25 build's --jvm-target 25.
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
}
