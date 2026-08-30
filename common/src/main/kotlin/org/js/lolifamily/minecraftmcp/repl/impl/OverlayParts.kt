package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.config.LanguageVersion
import java.util.concurrent.ConcurrentHashMap
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KmModule
import kotlin.metadata.jvm.KmPackageParts
import kotlin.metadata.jvm.KotlinModuleMetadata
import kotlin.metadata.jvm.UnstableMetadataApi

/** `@Metadata.k` for a file facade and for one part of a multi-file one. */
private const val KIND_FILE_FACADE = 2
private const val KIND_MULTIFILE_PART = 5

/**
 * Rebuilds the `package -> file facade` map that a jar's `META-INF` kotlin_module carries, from the
 * `@Metadata` the overlay pass already parses.
 *
 * A shaded jar's own copy is regularly broken: Shadow's default duplicates strategy leaves it a 24-byte
 * empty shell, and `relocate` rewrites class packages without touching the package names encoded inside it.
 * Either way the frontend stops resolving that package's top-level declarations even though the facade class
 * is right there — classes import fine, top-level functions do not.
 *
 * Recorded from where the classes ACTUALLY are, so a stale name and a missing entry need no telling apart,
 * and a healthy jar just contributes a duplicate of its own truth. Shipped as one extra overlay entry under a
 * name of ours: the provider unions package parts across modules, so ours adds to whatever the real jars say
 * rather than racing them for a filename.
 */
internal class OverlayParts(private val pinned: LanguageVersion?) {

    private val facades = ConcurrentHashMap<String, MutableSet<String>>()
    private val multiParts = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** [internalName] as it appears as a jar entry (`a/b/FooKt`); [kind] and [facade] straight off `@Metadata`.
     *  Both are stored as JVM internal names — the frontend resolves a part by loading exactly this string. */
    fun record(internalName: String, kind: Int, facade: String?) {
        val pkg = internalName.substringBeforeLast('/', "").replace('/', '.')
        // stdlib/kotlinx is never shaded, so nothing to repair; a partial list of ours would only shadow theirs.
        if (pkg == "kotlin" || pkg.startsWith("kotlin.") || pkg.startsWith("kotlinx.")) return
        when (kind) {
            KIND_FILE_FACADE -> facades.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(internalName)
            KIND_MULTIFILE_PART ->
                multiParts.computeIfAbsent(pkg) { ConcurrentHashMap() }[internalName] = facade ?: return
        }
    }

    /** The module file's bytes, or null if nothing was recorded (or the writer refused the shape). */
    @OptIn(UnstableMetadataApi::class)
    fun toBytes(): ByteArray? {
        if (facades.isEmpty() && multiParts.isEmpty()) return null
        val module = KmModule()
        for (pkg in facades.keys + multiParts.keys) {
            module.packageParts[pkg] = KmPackageParts(
                facades[pkg]?.sorted()?.toMutableList() ?: mutableListOf(),
                multiParts[pkg]?.toSortedMap() ?: mutableMapOf(),
            )
        }
        return runCatching { KotlinModuleMetadata(module, metadataVersion()).write() }.getOrNull()
    }

    /** The level the frontend was pinned to: it rejects a module file newer than that, and does it silently.
     *  Null [pinned] means nothing pinned it, so our own compiler's level stands. */
    private fun metadataVersion(): JvmMetadataVersion {
        val latest = JvmMetadataVersion.LATEST_STABLE_SUPPORTED
        val v = pinned?.let { JvmMetadataVersion(it.major, it.minor, 0) } ?: return latest
        return if (v < latest) v else latest
    }
}
