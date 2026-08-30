package org.js.lolifamily.minecraftmcp

import org.js.lolifamily.minecraftmcp.platform.Services
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * One MCP per game dir — the cross-process half of [CommonClass]'s in-JVM claim. Two instances sharing a
 * cache dir rewrite each other's jars while the other still holds them open, so the loser starts nothing.
 */
object CacheLock {

    /** Held for the JVM's life: a collected lock closes its channel and releases. The FILE is never deleted
     *  — a second process holding that path open while a third recreates it leaves the two locking different
     *  files, both convinced they won. */
    private var held: FileLock? = null

    /** @return null once this process owns the cache dir, else why it must stand down. A filesystem that
     *  cannot answer is not contention (NFS, some FUSE): both failures below run unclaimed rather than
     *  refuse — that costs less than the case refusing would catch. */
    fun claim(): String? {
        val path = Services.PLATFORM.cacheDir.resolve(".lock")
        val ch = try {
            Files.createDirectories(path.parent)
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp] cannot open {} — running unclaimed", path, t)
            return null
        }
        // Only a won lock keeps the channel: it is what holds the fd off the cleaner, which would close it and
        // release the lock with it. The other two exits close here — swallowed, since a throw would escape
        // CommonClass.init() and take the whole mod load down, which the leaked fd never would.
        val lock = try {
            ch.tryLock()
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp] {} refuses locks on this filesystem — running unclaimed", path, t)
            runCatching { ch.close() }
            return null
        }
        if (lock == null) {
            runCatching { ch.close() }
            return "another process holds $path"
        }
        held = lock
        return null
    }
}
