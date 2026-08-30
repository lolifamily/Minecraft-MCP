import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

// What the root's allprojects{} used to do. Isolated Projects forbids a project configuring its children, so each
// project applies this itself: :bridge and the root directly, everything else through multiloader-common.
// The Kotlin linters can NOT live here — see settings.gradle.kts on why they stay off the buildSrc classpath.
plugins {
    id("io.github.ben-manes.versions")
}

// GA/stable candidates only — ben-manes has no built-in flag; this is its README's snippet.
fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    return !stableKeyword && !version.matches(regex)
}

// Dependency-update reporter. Usage: ./gradlew dependencyUpdates --no-parallel --no-configuration-cache — it
// resolves other projects' configurations at execution time with no ordering edge, and reaches Task.project.
tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    gradleReleaseChannel = "current"   // Gradle self-check: GA only, not the -rc line
    // Reject an unstable candidate only when the CURRENT version is stable — so a project already pinned to
    // an unstable version (loom 1.17-SNAPSHOT) still surfaces its updates instead of going mute.
    rejectVersionIf { isNonStable(candidate.version) && !isNonStable(currentVersion) }
}

// Without this javac follows the build jvm's default charset, and our .java sources hold non-ASCII.
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

// Kotlin linters — ktlint (formatting) + detekt (code smells / complexity). Applied reactively, not from this
// script's plugins{}: the root and :bridge carry no Kotlin. Each project lints only its own src/main/kotlin —
// common's sources reach the loaders as a compileKotlin TASK input (not a sourceSet srcDir), so ktlint/detekt
// scan common exactly once, in :common.
plugins.withId("org.jetbrains.kotlin.jvm") {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    // The ktlint ENGINE version moves independently of the plugin's: 14.2.0 is the current plugin and still
    // defaults its engine to 1.5.0. Pin it, or `dependencyUpdates` reports an upgrade no version bump can take.
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.8.0")
    }

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
        // MUST pin: Gradle's DEFAULT_CHECKSTYLE_VERSION is 10.24.0, but checkstyle.xml is from
        // checkstyle master (SWITCH_RULE / LITERAL_WHEN tokens, TextBlockGoogleStyleFormatting)
        // which 10.x cannot load -> "Unable to create Root Module". 14.0.0 = matching release.
        toolVersion = "14.0.0"
        configFile = layout.settingsDirectory.file("checkstyle.xml").asFile
        // google_checks reports every violation at severity=warning and Gradle only fails on errors, so
        // without this the linter is advisory and nothing it finds ever blocks a build.
        maxWarnings = 0
    }
}
