package mcp

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.util.Properties

/**
 * Reads a `.properties` file into a `Map<String, String>` as a configuration-cache-correct Provider input.
 *
 * The PARSED RESULT (not the raw bytes) is the tracked configuration-cache input, so comment/whitespace edits to a
 * version node's gradle.properties don't needlessly invalidate the cache.
 *
 * A missing file yields an empty map (not an error) — the caller ([McpVersions.required] / [McpVersions.optional])
 * decides whether a given key is mandatory.
 */
abstract class PropertiesFileValueSource :
    ValueSource<Map<String, String>, PropertiesFileValueSource.Params> {

    interface Params : ValueSourceParameters {
        val file: RegularFileProperty
    }

    override fun obtain(): Map<String, String> {
        val f = parameters.file.get().asFile
        if (!f.exists()) return emptyMap()
        val props = Properties()
        f.inputStream().use { props.load(it) }
        return props.entries.associate { it.key.toString() to it.value.toString() }
    }
}
