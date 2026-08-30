package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Who put each compile-classpath jar there, for the access-widen overlay to shard by.
 *
 * User space (mods, plugins, and the libraries they carry) gets one shard per jar, so updating one rebuilds
 * one. Everything else — the loader, its dependencies, the server libraries, our own jar-in-jar, mc-symbols —
 * is the environment: rarely touched, and when it is touched every shard's bytes change anyway, so it is one
 * merged shard whose staleness forces a full rebuild.
 *
 * Filled by the collectors as they resolve; read by `widenClasspath`. Must stay in `...repl`, not `repl.impl`:
 * the collectors are game-loader owned and the overlay runs on the masking loader.
 */
object CodeOrigin {

    /** jar -> the loader's own id for it. Absent for anything the loaders don't name; the overlay falls back
     *  to a type-set key, which is stable across a version bump the same way an id is. */
    val identities: MutableMap<File, String> = ConcurrentHashMap()

    /** Jars a user installs and updates. [self] is deliberately NOT one: the widen logic ships in it, so a
     *  change there invalidates every shard, which is exactly what the environment shard already does. */
    val userSpace: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /** Our own jar. Located off [Constants] — root package, so the masking loader delegates and both sides
     *  answer with one value — and through [JarLocator], since a Forge union URI is not a `file:` one. */
    val self: File? by lazy {
        runCatching {
            JarLocator.toJarFile(Constants::class.java.protectionDomain?.codeSource?.location)?.absoluteFile
        }.getOrNull()
    }

    /** A cache file named after [id]. Prefix sanitized and capped — a JPMS module name is neither
     *  `[a-z0-9_-]` nor bounded. Hash over the ORIGINAL case: it is what keeps `MixinExtras` and
     *  `mixinextras` two files on a case-insensitive filesystem. */
    fun cacheFileName(id: String): String {
        val safe = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return "%s-%08x.jar".format(Locale.ROOT, safe.take(48), id.hashCode())
    }

    /** Record a resolved user-space jar and, where the loader named it, its id. */
    fun mark(jar: File, id: String?) {
        val f = jar.absoluteFile
        if (f != self) userSpace.add(f)
        if (id != null) identities[f] = id
    }
}
