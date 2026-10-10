import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

// What the root's allprojects{} used to do. Isolated Projects forbids a project configuring its children, so each
// project applies this itself: :bridge and the root directly, everything else through multiloader-common.
plugins {
    id("io.github.ben-manes.versions")
    // On every project, Kotlin or not: each has a build script, the root settings.gradle.kts as well, and ktlint lints
    // the *.kts in the project directory with or without a Kotlin plugin. Kotlin projects add their source sets.
    id("org.jlleitschuh.gradle.ktlint")
}

// Dependency-update reporter. Usage: ./gradlew dependencyUpdates --no-parallel --no-configuration-cache — it
// resolves other projects' configurations at execution time with no ordering edge, and reaches Task.project.
tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    // Built-in since 0.64.0, marker-based (alpha/beta/rc/snapshot...): drops a pre-release candidate only while the
    // CURRENT version is a release, so a -SNAPSHOT pin like loom's still surfaces its updates; also defaults
    // gradleReleaseChannel to "current". NOT a rejectVersionIf calling a helper declared in this script: that lambda
    // captures the script object, and 0.64.0 then withholds the root report from the configuration cache.
    rejectPreReleases = true
}

// Without this javac follows the build jvm's default charset, and our .java sources hold non-ASCII.
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

// Kotlin linters — ktlint (formatting) + detekt (code smells / complexity). Each project lints only its own
// src/main/kotlin — common's sources reach the loaders as a compileKotlin TASK input (not a sourceSet srcDir), so
// ktlint/detekt scan common exactly once, in :common.

// The ktlint ENGINE version moves independently of the plugin's, and the plugin's default engine lags behind.
// Pin it, or `dependencyUpdates` reports an upgrade no version bump can take.
ktlint {
    version.set("1.8.0")
}

// detekt reactively, not from plugins{} like ktlint: the root and :bridge carry no Kotlin, and build scripts are
// ktlint's alone.
plugins.withId("org.jetbrains.kotlin.jvm") {
    apply(plugin = "dev.detekt")

    // Both settings must live here: detekt never auto-discovers config/detekt/detekt.yml, and without
    // buildUponDefaultConfig a custom config REPLACES the defaults instead of layering onto them. That file
    // holds ONLY our deviations from detekt's built-in defaults.
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(layout.settingsDirectory.file("config/detekt/detekt.yml"))
    }
}

// Java linter — Checkstyle (google_checks base + Fabric Mixin rules; see checkstyle.xml). Reacts to the
// `java` plugin, so it hits :common and :bridge; on the loaders src/**/*.java is empty (common's java is a
// compileJava input, not a srcDir), so it scans common exactly once, in :common — mirrors the kotlin story.
plugins.withId("java") {
    apply(plugin = "checkstyle")

    configure<CheckstyleExtension> {
        // MUST pin: Gradle's default Checkstyle predates checkstyle.xml, which is from checkstyle master
        // (SWITCH_RULE / LITERAL_WHEN tokens, TextBlockGoogleStyleFormatting) — an older engine fails with
        // "Unable to create Root Module". 14.0.0 was the first matching release.
        toolVersion = "14.1.0"
        configFile = layout.settingsDirectory.file("checkstyle.xml").asFile
        // google_checks reports every violation at severity=warning and Gradle only fails on errors, so
        // without this the linter is advisory and nothing it finds ever blocks a build.
        maxWarnings = 0
    }
}
