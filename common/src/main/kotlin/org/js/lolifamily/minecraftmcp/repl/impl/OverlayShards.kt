package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.config.LanguageVersion
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.repl.CodeOrigin
import java.io.File

// ============================================================================================
// How the access-widen overlay is cut into cache units. One jar per user-space entry, so updating one mod
// rewrites one shard instead of the whole 250-600MB overlay; everything else merges into one environment
// shard, whose staleness forces a full rebuild. Read by widenClasspath in ClasspathWiden.
// ============================================================================================

internal const val ENV_SHARD = "env.jar"
internal const val ENV_STAMP = "env.stamp"
internal const val MODS_STAMP = "mods.stamp"

/** One jar the overlay writes. */
internal sealed class Shard(val name: String, val members: List<CpEntry>) {

    /** One user-installed jar, on its own so that updating it rebuilds only this. */
    class User(name: String, val src: CpEntry) : Shard(name, listOf(src))

    /** Everything the user did not install — a change in here changes every shard's bytes anyway. */
    class Env(members: List<CpEntry>) : Shard(ENV_SHARD, members)
}

/** Where each shard's jar is, and which of them have to be written. */
internal class ShardPlan(val out: Map<Shard, File>, val stale: List<Shard>)

/** One mods.stamp line. Named rather than a Triple: three same-typed strings in positional form is an
 *  argument order nobody can get wrong at a glance, and getting it wrong here reads back as a valid file. */
internal class StampRow(val contentKey: String, val shard: String, val source: String)

/**
 * Cut [entries] into the environment shard plus one shard per user-space jar.
 *
 * The environment shard is inserted where its first member sat, which in practice is the front — ReplBridge
 * prepends mc-symbols. That does move the rest of the environment ahead of any mod it used to trail, but on
 * Forge those all come out of `Configuration.modules()`, an unordered Set, so there was no order to preserve;
 * elsewhere it only lifts our own jar-in-jar, whose runtime copy is the one identity anyway.
 */
internal fun shardsOf(entries: List<CpEntry>): List<Shard> {
    val out = ArrayList<Shard>()
    val env = ArrayList<CpEntry>()
    var slot = -1
    for (e in entries) {
        if (e.file.absoluteFile in CodeOrigin.userSpace) {
            // No contentKey means no `.class` in there (or an unreadable jar): nothing to widen, nothing to
            // stamp it by. A shard would be an empty jar that misses every launch and gets rebuilt every launch.
            if (e.contentKey != null) out += Shard.User(shardName(e), e)
        } else {
            if (slot < 0) slot = out.size
            env += e
        }
    }
    if (env.isNotEmpty()) out.add(slot, Shard.Env(env))
    return out
}

/**
 * Match [shards] against what [dir] already holds.
 *
 * A changed environment changes what EVERY shard's bytes mean. The delete is housekeeping ONLY — a file
 * another instance holds open survives it on Windows — so correctness rides on `envChanged` by itself: it
 * forces every lookup to miss, and the rebuild overwrites whatever the delete left behind. Reading the old
 * rows back after a failed delete is exactly how every shard would falsely hit.
 */
internal fun resolveShards(dir: File, shards: List<Shard>, wantEnv: List<String>): ShardPlan {
    val envChanged = readEnvRows(File(dir, ENV_STAMP)) != wantEnv
    if (envChanged) dir.listFiles()?.forEach { runCatching { it.delete() } }
    val known = if (envChanged) emptyMap() else readModsStamp(File(dir, MODS_STAMP))
    val out = LinkedHashMap<Shard, File>()
    val stale = ArrayList<Shard>()
    for (s in shards) {
        val hit = if (envChanged) {
            null
        } else {
            when (s) {
                is Shard.Env -> File(dir, ENV_SHARD).takeIf { it.isFile }
                is Shard.User -> s.src.contentKey?.let { known[it] }?.let { File(dir, it) }?.takeIf { it.isFile }
            }
        }
        out[s] = hit ?: File(dir, s.name)
        if (hit == null) stale += s
    }
    return ShardPlan(out, stale)
}

/**
 * Record what this run produced; returns the file names worth keeping.
 *
 * A shard whose source jar would not open is left out, so the next launch retries that one alone. The
 * environment stamp is written last and only when its own shard came out whole: it is the token vouching
 * for every other row, so stamping it over a gap would make that gap permanent.
 */
internal fun publishStamps(dir: File, shards: List<Shard>, out: Map<Shard, File>, failed: Set<File>, wantEnv: List<String>): Set<String> {
    val rows = shards.filterIsInstance<Shard.User>().mapNotNull { s ->
        val key = s.src.contentKey ?: return@mapNotNull null
        val jar = out.getValue(s)
        if (s.src.file in failed || !jar.isFile) return@mapNotNull null
        StampRow(key, jar.name, s.src.file.name)
    }
    writeModsStamp(File(dir, MODS_STAMP), rows)
    val env = shards.filterIsInstance<Shard.Env>().firstOrNull()
    if (env == null || (env.members.none { it.file in failed } && out.getValue(env).isFile)) {
        writeEnvRows(File(dir, ENV_STAMP), wantEnv)
    }
    return rows.mapTo(HashSet()) { it.shard } + setOf(ENV_SHARD, ENV_STAMP, MODS_STAMP)
}

/** Delete whatever this run did not name — a mod that is gone, or a shard whose name moved. Cheap; a file
 *  another instance still holds open just survives to the next sweep. */
internal fun sweepStale(dir: File, keep: Set<String>) {
    dir.listFiles()?.forEach { if (it.name !in keep) runCatching { it.delete() } }
}

/** A user shard's file name: the loader's id where there is one, so a version bump overwrites the shard
 *  instead of orphaning it, and only an uninstall leaves anything for the sweep. Where no id exists (a
 *  plugin's bundled library) the file name stands in; it need not be stable, since staleness is looked up
 *  by contentKey, not by name. */
private fun shardName(e: CpEntry): String =
    CodeOrigin.cacheFileName(CodeOrigin.identities[e.file.absoluteFile] ?: e.file.nameWithoutExtension)

/**
 * The environment shard's inputs: [pinned] first, then one sorted `name|contentKey` line per member.
 *
 * Compared whole, not per line — the environment is ONE shard, so a member changing and a member vanishing
 * are the same event. Members sort because Forge enumerates its module layer out of an unordered Set;
 * [pinned] leads instead, unfindable if buried among a few hundred jar lines. It is here at all because it
 * sets every shard's `kotlin_module` metadata version from outside the environment — on Fabric the game's
 * stdlib is jar-in-jar'd by fabric-language-kotlin, which is user space.
 */
internal fun envRows(env: List<CpEntry>, pinned: LanguageVersion?): List<String> =
    listOf("pinned|${pinned?.versionString ?: "-"}") + env.map { "${it.file.name}|${it.contentKey ?: "-"}" }.sorted()

/**
 * env.stamp's own pair, deliberately not the mods.stamp one: the two disagree on line shape, on what column
 * one holds, on whole-file against per-line comparison, and on what a mismatch costs. Either parser would
 * read the other's file without complaint and mean something else.
 *
 * No file reads as no rows, which never equals [envRows] — so the rebuild path is the default.
 */
internal fun readEnvRows(f: File): List<String> = if (!f.isFile) emptyList() else runCatching { f.readLines() }.getOrDefault(emptyList())

internal fun writeEnvRows(f: File, rows: List<String>) {
    runCatching { AtomicFiles.publishing(f.toPath()) { tmp -> tmp.toFile().writeText(rows.joinToString("\n")) } }
}

/**
 * `contentKey -> shard file name` for the user shards.
 *
 * Keyed on contentKey, NOT on the shard name: a jar re-extracted under a fresh temp name every boot (Paper
 * does this for a plugin's bundled jars) is unrecognizable by name and unchanged by content. Third column is
 * the source jar, written for whoever has to read this file, never parsed.
 */
internal fun readModsStamp(f: File): Map<String, String> = if (!f.isFile) {
    emptyMap()
} else {
    runCatching {
        f.readLines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 2 || p[0].isEmpty()) null else p[0] to p[1]
        }.toMap()
    }.getOrDefault(emptyMap())
}

/** Write [rows] as `contentKey|shard|source`, published by atomic rename like every other cache file. */
internal fun writeModsStamp(f: File, rows: List<StampRow>) {
    runCatching {
        AtomicFiles.publishing(f.toPath()) { tmp ->
            tmp.toFile().writeText(rows.joinToString("\n") { "${it.contentKey}|${it.shard}|${it.source}" })
        }
    }
}
