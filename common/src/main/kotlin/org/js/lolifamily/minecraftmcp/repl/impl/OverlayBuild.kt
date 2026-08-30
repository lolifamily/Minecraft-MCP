package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.config.LanguageVersion
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// ============================================================================================
// Writing the shards OverlayShards laid out. Each shard owns its file, so nothing here is synchronized.
// Entry point is buildShards, called from widenClasspath.
// ============================================================================================

/** What a shard build reads: the entries to widen, whether each jar's bytes get renamed, the metadata level
 *  for the rebuilt kotlin_module, and where to record a jar that would not open. */
internal class OverlayBuild(
    val jarEntries: Map<File, List<String>>,
    val renameFor: RenameFor,
    val pinned: LanguageVersion?,
    val failed: MutableSet<File>,
)

/**
 * Build every stale shard in parallel; returns the classes written across all of them.
 *
 * One worker per SHARD, not per jar: each shard is its own file, so writes never contend. Largest first,
 * which puts the environment shard (the only one with many members) at the front so its tail runs alongside
 * the others instead of after them. Reserve 2 cores for the game.
 */
internal fun buildShards(stale: List<Shard>, out: Map<Shard, File>, ctx: OverlayBuild): Int {
    if (stale.isEmpty()) return 0 // only the mixin jar was missing
    val pool = stale.sortedByDescending { it.members.size }
    val written = AtomicInteger(0)
    val cursor = AtomicInteger(0)
    val nThreads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, pool.size)
    val workers = (0 until nThreads).map { t ->
        Thread({
            var i = cursor.getAndIncrement()
            while (i < pool.size) {
                val s = pool[i]
                // Nothing published for a shard that throws, and its members are marked so the stamp skips
                // it — the next launch retries this shard alone.
                try {
                    written.addAndGet(buildShard(s, out.getValue(s), ctx))
                } catch (e: Throwable) {
                    s.members.forEach { ctx.failed += it.file }
                    Constants.LOG.warn("[mcp-aw] shard {} failed: {}", s.name, "$e")
                }
                i = cursor.getAndIncrement()
            }
        }, "mcp-aw-$t").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
            contextClassLoader = Constants.GAME_LOADER
            start()
        }
    }
    workers.forEach { it.join() }
    return written.get()
}

/**
 * Write one shard: its members' widened classes, plus the kotlin_module rebuilt from what they carried.
 *
 * Entry names are deduped WITHIN the shard — two members claiming one name would collide inside a single
 * jar. Across shards they are left alone: the shards sit in classpath order and the first wins, exactly as
 * the source jars behind them already do.
 */
private fun buildShard(shard: Shard, out: File, ctx: OverlayBuild): Int {
    val own = LinkedHashMap<File, List<String>>()
    for (m in shard.members) ctx.jarEntries[m.file]?.let { own[m.file] = it }
    val parts = OverlayParts(ctx.pinned)
    var written = 0
    AtomicFiles.publishing(out.toPath()) { tmp ->
        JarOutputStream(java.io.BufferedOutputStream(FileOutputStream(tmp.toFile()), 1 shl 16)).use { jos ->
            val worker = OverlayWorker(jos, ctx.renameFor, parts)
            for ((f, names) in dedupeEntries(own)) {
                // A failing member costs only its own classes; the rest of the shard still lands, and the
                // mark keeps this shard out of the stamp.
                try {
                    worker.widenJar(f, names)
                } catch (e: Throwable) {
                    ctx.failed += f
                    Constants.LOG.warn("[mcp-aw] widen jar {} failed: {}", f.name, "$e")
                }
            }
            written = worker.written
            writeModuleFile(jos, parts, shard.name)
        }
    }
    return written
}

/** Append the rebuilt `kotlin_module`. Named per shard: the provider unions package parts across modules,
 *  so distinct names simply add up, while one shared name would have shards racing for a single path. */
private fun writeModuleFile(jos: JarOutputStream, parts: OverlayParts, shard: String) {
    val bytes = parts.toBytes() ?: return
    // The directory entry is load-bearing: the provider reaches module files by walking to META-INF, and a
    // jar built entry-by-entry has none at all. Classes are unaffected — looked up by full path, never walked.
    jos.putNextEntry(JarEntry("META-INF/").apply { method = java.util.zip.ZipEntry.STORED; size = 0; crc = 0 })
    jos.closeEntry()
    val e = JarEntry("META-INF/mcp-overlay-${shard.removeSuffix(".jar")}.kotlin_module").apply {
        method = java.util.zip.ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = java.util.zip.CRC32().apply { update(bytes) }.value
    }
    jos.putNextEntry(e)
    jos.write(bytes)
    jos.closeEntry()
}

/** One shard's writer: the inflate buffer it reuses across that shard's jars (a fresh 1MB each would be pure
 *  allocation churn), and the count of classes it contributed. Nothing is synchronized — a shard owns its
 *  output file outright, so the lock the single-overlay version needed is gone. */
private class OverlayWorker(private val jos: JarOutputStream, private val renameFor: RenameFor, private val parts: OverlayParts) {

    private var readBuf = ByteArray(1 shl 20) // grows for oversized classes, then kept at that size

    var written = 0
        private set

    /** Widen every [names] entry of [jar] into this shard. Opens its OWN ZipFile, so workers on other shards
     *  inflate independently — sharing one would serialize them all on that ZipFile's internal lock. */
    fun widenJar(jar: File, names: List<String>) {
        val rename = renameFor(jar) // one worker pulls many jars, so this is per-jar, not per-worker
        java.util.zip.ZipFile(jar).use { zf ->
            for (name in names) {
                val entry = zf.getEntry(name) ?: continue
                val size = entry.size.toInt()
                if (size > readBuf.size) readBuf = ByteArray(size)
                val read = zf.getInputStream(entry).use { it.readNBytes(readBuf, 0, size) }
                // readBuf is reused uncleared, so a short read splices the previous class onto this one.
                check(read == size) { "short read on ${jar.name}!$name: $read of $size declared bytes" }
                val out = widenClassFile(readBuf, size, rename, parts)
                val je = JarEntry(name)
                je.method = java.util.zip.ZipEntry.STORED
                je.size = out.size.toLong()
                je.compressedSize = out.size.toLong()
                je.crc = java.util.zip.CRC32().apply { update(out) }.value
                jos.putNextEntry(je)
                jos.write(out)
                jos.closeEntry()
                written++
            }
        }
    }
}
