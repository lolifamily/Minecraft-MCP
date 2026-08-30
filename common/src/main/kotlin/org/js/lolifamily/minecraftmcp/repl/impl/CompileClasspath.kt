package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.config.LanguageVersion
import org.js.lolifamily.minecraftmcp.Constants
import java.io.File
import java.net.URLClassLoader

/**
 * Turn the enumerated game classpath into the one the K2 frontend is handed: drop the jars that poison its
 * index, fold duplicate libraries, and prepend an access-widened overlay ([widenClasspath]).
 */
internal fun assembleCompileClasspath(cpFiles: List<File>, baseLoader: ClassLoader, pinned: LanguageVersion?): List<File> {
    val cleanCp   = dropIndexPoison(cpFiles)
    val entries   = dedupByTypeSet(cleanCp, ourLoaderJars(baseLoader))
    val widenedCp = widenClasspath(entries, pinned)
    logClasspath(widenedCp)
    return widenedCp
}

/**
 * One classpath entry with the two digests a single central-directory walk yields.
 *
 * [typeSetKey] is the set of `.class` NAMES, for the dedup below — blind to content by design, so two builds
 * of one library fold together. [contentKey] adds each entry's CRC and size, and is what the overlay keys a
 * shard's staleness on: it catches a recompile that kept the class list, and it is blind to path and mtime,
 * so a jar re-extracted under a new temp name is not a change. Both cover `.class` only — editing a lang file
 * or repacking is not a change either. Null on a jar with no classes, or an unreadable one.
 */
internal class CpEntry(val file: File, val typeSetKey: String?, val contentKey: String?)

/** The masking loader's own urls: our extracted Kotlin stack, and in production the mod jar `repl.impl` is
 *  loaded from. */
private fun ourLoaderJars(baseLoader: ClassLoader): List<File> = (baseLoader as? URLClassLoader)?.urLs?.mapNotNull { url ->
    runCatching { File(url.toURI()) }.getOrNull()?.takeIf { it.exists() }
}.orEmpty()

/** The enumerated classpath can include the vanilla obfuscated MC jar, with thousands of classes in the
 *  default (unnamed) package. Such a jar poisons the Kotlin scripting host's classpath index: with it on the
 *  compile cp the K2 frontend silently fails to resolve every other jar's packages (only net.minecraft
 *  survives, via mc-symbols). One default-package class is harmless; thousands break it. It's useless here
 *  anyway — we compile mojmap names against mc-symbols.jar. Identified by content (unnamed-package class
 *  count), never by filename, so it's launcher-independent; a mojmap-named MC jar is left untouched. */
private fun dropIndexPoison(cpFiles: List<File>): List<File> {
    // CONTENT-based, never filename, so it's launcher-independent. `take` short-circuits at the limit, so a
    // huge jar is not walked to the end; a read failure means "not our poison, keep it". Dirs (dev classes
    // dirs) are kept by a filesystem check, not a name check.
    fun isObfuscatedGameJar(f: File): Boolean = f.isFile &&
        try {
            java.util.zip.ZipFile(f).use { zf ->
                zf.entries().asSequence()
                    .filter { it.name.endsWith(".class") && it.name.indexOf('/') < 0 && it.name != "module-info.class" }
                    .take(DEFAULT_PACKAGE_LIMIT)
                    .count() == DEFAULT_PACKAGE_LIMIT
            }
        } catch (_: Throwable) { false }

    val (poison, clean) = cpFiles.partition { isObfuscatedGameJar(it) }
    if (poison.isNotEmpty()) {
        Constants.LOG.info(
            "[mcp-repl/build] dropped {} obfuscated jar(s) from compile cp (bulk default-package classes poison the K2 script index): {}",
            poison.size, poison.joinToString { it.name },
        )
    }
    return clean
}

/** Merge the enumerated compile cp with the masking loader's Kotlin jars, deduplicating by the SET OF
 *  TYPES each jar defines: the sorted set of its zip .class entry names, hashed. Read from the central
 *  directory — no decompression, never the raw bytes, never the filename. That set is exactly what decides
 *  whether two jars collide on the K2 index (duplicate classes), so it's the right identity: same type-set
 *  ⇒ same library ⇒ keep one. It also folds two builds of one artifact that differ only in packaging (zip
 *  timestamps/order), which a byte hash would miss. A null key (no classes, or unreadable) is always kept —
 *  it collides with nothing. The two stdlib copies (mcp-kotlin/ staging + our jar-in-jar extraction) share a
 *  type-set and collapse to one.
 *
 *  Returns [CpEntry], not files: the walk that computes the dedup key yields the overlay's staleness key from
 *  the same central directory, so paying for it twice would be the only alternative. */
private fun dedupByTypeSet(cleanCp: List<File>, kotlinFiles: List<File>): List<CpEntry> {
    // Held across jars: getInstance is a provider lookup, and at 500+ entries that costs about what the
    // hashing does. The loop below is serial, so one instance each is enough.
    val mdType = java.security.MessageDigest.getInstance("SHA-256")
    val mdContent = java.security.MessageDigest.getInstance("SHA-256")

    fun digest(md: java.security.MessageDigest, lines: List<String>): String {
        md.reset()
        for (l in lines) { md.update(l.toByteArray(Charsets.UTF_8)); md.update('\n'.code.toByte()) }
        return java.math.BigInteger(1, md.digest()).toString(16)
    }

    // crc/size come off the central directory record already parsed to open the zip — no extra IO, and no
    // class byte is read. See CpEntry for what each digest is for.
    fun scan(f: File): CpEntry = try {
        java.util.zip.ZipFile(f).use { zf ->
            val names = ArrayList<String>()
            val rows = ArrayList<String>()
            val e = zf.entries()
            while (e.hasMoreElements()) {
                val z = e.nextElement()
                if (!z.name.endsWith(".class")) continue
                names.add(z.name)
                rows.add("${z.name}|${z.crc}|${z.size}")
            }
            if (names.isEmpty()) {
                CpEntry(f, null, null)
            } else {
                names.sort()
                rows.sort()
                CpEntry(f, digest(mdType, names), digest(mdContent, rows))
            }
        }
    } catch (_: Throwable) { CpEntry(f, null, null) }   // unreadable ⇒ keep (never silently drop a compile dependency)

    // First wins, and which one is first is ReplBridge's call: this drops entries, it never reorders them.
    val seen = HashSet<String>()
    return (cleanCp + kotlinFiles).map(::scan).filter { it.typeSetKey?.let(seen::add) ?: true }
}

private fun logClasspath(cp: List<File>) {
    Constants.LOG.info(
        "[mcp-repl/build] compile classpath = {} entries:\n  {}",
        cp.size,
        cp.joinToString("\n  ") { f ->
            val size = when {
                f.isFile -> "${f.length() / 1024}"
                f.isDirectory -> "dir"
                else -> "?"
            }
            "${f.name} ($size)"
        },
    )
}

/** How many default-package classes make a jar structurally "the obfuscated vanilla MC jar". One is
 *  harmless (a stray top-level class); thousands break the K2 index. */
private const val DEFAULT_PACKAGE_LIMIT = 16
