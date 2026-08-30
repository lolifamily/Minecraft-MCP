package mcp

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider

/**
 * One front door to the ACTIVE MC version's coordinates.
 *
 * Two sources, deliberately split by data shape:
 *  - VERSION NUMBERS (minecraft / forge / neoforge / fabric-loader / parchment) come from the `mc` version catalog
 *    (`versions/<active>/libs.versions.toml`, selected in settings.gradle.kts) — accessed here via [version] /
 *    [versionOr] / [hasVersion]. Leaf build scripts may equivalently use the generated `mc.versions.*` accessors;
 *    convention plugins can't (no accessor there), so they use these methods.
 *  - EVERYTHING ELSE (ranges, booleans, bytecode/mixin levels) comes from `versions/<active>/gradle.properties`,
 *    read lazily via [PropertiesFileValueSource] — accessed via [required] / [optional] / [flag].
 *
 * Active version resolves highest-priority-first through
 * `-PmcVersion` > `env MCP_MC_VERSION` > `versions/current` > `"1.20.6"`. Everything flows through
 * `providers.*` / a ValueSource / the catalog, so it is configuration-cache correct.
 */
class McpVersions(project: Project) {
    private val providers = project.providers
    // settingsDirectory, not rootProject.layout — same dir, reached without touching another Project.
    private val root = project.layout.settingsDirectory
    private val catalog: VersionCatalog by lazy {
        project.extensions.getByType(VersionCatalogsExtension::class.java).named("mc")
    }

    // ---- active version ----

    /** Must stay in step with settings.gradle.kts, which resolves the same chain at settings time. */
    val activeVersion: Provider<String> =
        providers.gradleProperty("mcVersion")
            .orElse(providers.environmentVariable("MCP_MC_VERSION"))
            .orElse(providers.fileContents(root.file("versions/current")).asText.map { it.trim() })
            .orElse("1.20.6")

    // ---- non-version-number coordinates (ranges / booleans / bytecode+mixin levels) ----

    /** The active node's gradle.properties, parsed exactly once and shared by every accessor below. */
    private val extras: Provider<Map<String, String>> =
        activeVersion.flatMap { v ->
            providers.of(PropertiesFileValueSource::class.java) {
                parameters.file.set(root.file("versions/$v/gradle.properties"))
            }
        }

    /** A coordinate that must exist on the active node; fails naming the file otherwise. */
    fun required(key: String): Provider<String> =
        activeVersion.zip(extras) { v, m ->
            m[key] ?: throw GradleException("versions/$v/gradle.properties is missing required key '$key'")
        }

    /** A coordinate that MAY be absent (e.g. `neoforge_loader_version_range` on 1.18); absent Provider if missing. */
    fun optional(key: String): Provider<String> = extras.map { it[key] }

    /** A boolean flag (`unobfuscated` / `srg_runtime`); absent -> `false`. */
    fun flag(key: String): Provider<Boolean> = optional(key).map { it.toBoolean() }.orElse(false)

    // ---- version numbers (from the `mc` catalog) ----

    /** Required catalog version; throws if the active node's libs.versions.toml lacks [alias]. */
    fun version(alias: String): Provider<String> =
        providers.provider {
            catalog.findVersion(alias)
                .orElseThrow { GradleException("version catalog 'mc' is missing version '$alias'") }
                .requiredVersion
        }

    /** Optional catalog version with a fallback (absent [alias] -> [default]). */
    fun versionOr(alias: String, default: String): Provider<String> =
        providers.provider {
            val found = catalog.findVersion(alias)
            if (found.isPresent) found.get().requiredVersion else default
        }

    /** Presence check for gating whole blocks (parchment / neoforge). Eager: the catalog is a settings-time constant. */
    fun hasVersion(alias: String): Boolean = catalog.findVersion(alias).isPresent
}
