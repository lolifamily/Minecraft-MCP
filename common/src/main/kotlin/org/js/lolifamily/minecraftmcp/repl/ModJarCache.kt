package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.platform.services.ModCode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32

/**
 * The disk side of [ModJarCollector]: turns a code root with no file behind it into a repacked jar, and
 * decides when that jar is stale.
 *
 * Staleness is keyed on the CONTAINER jar, not on the mod version — a debug build that keeps its version
 * number still moves the container's `size|mtime`.
 */
internal object ModJarCache {

    /**
     * Repack a virtual root's `.class` tree into a JAR, keyed by [cacheName], published by atomic rename.
     *
     * Reused while the container jar's stamp still matches, so rebuilding a container rebuilds every JiJ it
     * carries — version bump or not, which is what a debug build keeping its version needs.
     */
    fun repack(mc: ModCode, old: Map<String, String>, now: MutableMap<String, String>): File? {
        val root = mc.path
        try {
            val base = Services.PLATFORM.cacheDir.resolve("mod-jars").toFile()
            base.mkdirs()
            val name = cacheName(mc)
            val jar = File(base, name)
            // No container: fall back to plain existence (the pre-stamp behavior) rather than record an
            // entry that can never be checked.
            val stamp = containerOf(root)?.let { stampOf(it) }
            if (!jar.isFile || (stamp != null && old[name] != stamp)) writeJar(root, jar)
            if (stamp != null) now[name] = stamp
            return jar
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] repack failed for {}", root, t)
            return null
        }
    }

    /** Every `.class` and `.kotlin_module` under [root], as a STORED jar published by atomic rename. */
    private fun writeJar(root: Path, dest: File) {
        val seen = HashSet<String>()
        AtomicFiles.publishing(dest.toPath()) { part ->
            JarOutputStream(BufferedOutputStream(FileOutputStream(part.toFile()), 1 shl 16)).use { jos ->
                // Load-bearing: a module file is only reached by walking to META-INF, and walking needs the
                // directory entry an entry-by-entry jar never gets. Classes go by full path, unaffected.
                jos.putNextEntry(JarEntry("META-INF/").apply { method = java.util.zip.ZipEntry.STORED; size = 0; crc = 0 })
                jos.closeEntry()
                seen.add("META-INF/")
                Files.walk(root).use { walk ->
                    // `.kotlin_module` too, or the frontend cannot see a file facade's top-level declarations
                    // (kfflib's FORGE_BUS, MOD_CONTEXT) even with the class right there.
                    walk.filter { p ->
                        val n = if (Files.isRegularFile(p)) p.fileName?.toString() else null
                        n != null && (n.endsWith(".class") || n.endsWith(".kotlin_module"))
                    }.forEach { src -> writeStoredEntry(jos, root, src, seen) }
                }
            }
        }
    }

    /** Cache file name. Carries no VERSION: the name must survive an update so the rebuild overwrites instead
     *  of orphaning. Staleness is the stamp's job. */
    private fun cacheName(mc: ModCode): String {
        // No identity to key on. Unreachable today: platform roots without mods are real files, orphans
        // always carry a module name.
        val key = mc.mods.minOfOrNull { it.id }
            ?: return "%08x.jar".format(Locale.ROOT, mc.path.toUri().hashCode())
        return CodeOrigin.cacheFileName(key)
    }

    /**
     * The real on-disk jar behind [root]. A JiJ root nests UnionFileSystem -> `jij:` PathFileSystem ->
     * UnionFileSystem -> real file; each layer names its backing path reflectively (loader classes, not on
     * the common compile classpath).
     *
     * The container's IDENTITY, for staleness only — never where a class is READ from. That is [JarLocator]'s
     * job, and it refuses to answer with the container on purpose (a different library's type set).
     */
    private fun containerOf(root: Path): File? {
        var p = root
        repeat(12) {
            val fs = p.fileSystem
            if (fs == FileSystems.getDefault()) {
                return runCatching { p.toFile().takeIf { f -> f.isFile } }.getOrNull()
            }
            val next = backingPath(fs, "getPrimaryPath") ?: backingPath(fs, "getTarget") ?: return null
            if (next == p) return null
            p = next
        }
        return null
    }

    private fun backingPath(fs: FileSystem, m: String): Path? = runCatching { fs.javaClass.getMethod(m).invoke(fs) as? Path }.getOrNull()

    /** `path|size|mtime`, the overlay stamp's shape. `File.lastModified()` is a filesystem timestamp, so
     *  unlike a zip entry's DOS field it needs no timezone and survives one changing. */
    private fun stampOf(c: File): String = "${c.canonicalPath}|${c.length()}|${c.lastModified()}"

    private fun stampFile(): File = Services.PLATFORM.cacheDir.resolve("mod-jars.stamp").toFile()

    /** cache jar name -> container stamp. One file, not a `.stamp` per jar: sidecars would go stale
     *  themselves, and nothing here deletes cache files. */
    fun readStamps(): Map<String, String> {
        val f = stampFile()
        if (!f.isFile) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val i = line.indexOf('|')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] stamp unreadable, rebuilding: {}", "$t")
            emptyMap()
        }
    }

    /** Only what THIS run resolved, so a jar whose key moved on stops being reused. The file itself stays. */
    fun writeStamps(now: Map<String, String>) {
        if (now.isEmpty()) return
        try {
            AtomicFiles.publishing(stampFile().toPath()) { tmp ->
                tmp.toFile().writeText(now.entries.joinToString("\n") { "${it.key}|${it.value}" })
            }
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl/mods] stamp write failed; caches rebuild next launch", t)
        }
    }

    /** Copy one class file into [jos] as a STORED entry. Duplicate paths (a union/overlay FS can surface the
     *  same class twice) are dropped — `putNextEntry` would throw — and an unreadable file is skipped rather
     *  than failing the whole repack. */
    private fun writeStoredEntry(jos: JarOutputStream, root: Path, src: Path, seen: MutableSet<String>) {
        try {
            val name = root.relativize(src).toString().replace('\\', '/')
            if (!seen.add(name)) return
            val bytes = Files.readAllBytes(src)
            val entry = JarEntry(name).apply {
                method = java.util.zip.ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
            jos.putNextEntry(entry); jos.write(bytes); jos.closeEntry()
        } catch (_: Throwable) { /* skip unreadable entry */ }
    }
}
