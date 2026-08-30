package org.js.lolifamily.minecraftmcp

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * How every file this mod writes is published: built under a staging name, then renamed into place. Its readers
 * decide by existence — a cache hit, a config file — so a path must never hold a half-written file; and the
 * cache files stay OPEN for the process (the masking loader's urls, the symbol jars the compiler indexes), so a
 * rewrite must not truncate what a reader still holds.
 *
 * Root package, so the masking loader delegates it to the game loader and the writers on both sides reach this
 * one copy.
 */
object AtomicFiles {

    /** Writes one file's bytes to the staging path it is handed. */
    fun interface Writer {
        @Throws(IOException::class)
        fun write(tmp: Path)
    }

    /**
     * Build [dest] through a staging file, then rename it into place.
     *
     * The staging path is in [dest]'s own directory — a cross-filesystem move is a full copy, and these are the
     * largest files this mod writes — and unique per call, so two instances over one game dir race only on the
     * rename, which is atomic. On POSIX `createTempFile` also makes it owner-only and the rename keeps that
     * mode, which is what the token file's permissions rest on.
     *
     * The two flushes are what carry that across a CRASH rather than only across a concurrent reader:
     * `ATOMIC_MOVE` orders the swap, it does not commit the bytes. Worst case is the config file, which
     * [ConfigFile] refuses to regenerate over once it fails to read — so a torn one is permanent.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun publishing(dest: Path, write: Writer) {
        val dir = dest.toAbsolutePath().parent
        val tmp = Files.createTempFile(dir, dest.fileName.toString() + ".", ".tmp")
        try {
            write.write(tmp)
            fsync(tmp)
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            // The directory entry the rename made. Unix only — a directory has no writable handle, and Windows
            // will not open one as a channel at all, where NTFS journals the rename itself. Silent because
            // nothing can tell the two apart: a read-only flush reports success having done nothing.
            runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(tmp) // no-op once the rename took it
        }
    }

    /**
     * Commit [p]'s bytes. A handle of its own is enough — the flush is per-file, not per-descriptor — so this
     * runs after whoever wrote [p] closed their own stream.
     *
     * Durability without atomicity, for a write that deliberately skips [publishing]: what it buys is an
     * ORDER, [p] on disk before whatever is written next.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun fsync(p: Path) {
        FileChannel.open(p, StandardOpenOption.WRITE).use { it.force(true) }
    }
}
