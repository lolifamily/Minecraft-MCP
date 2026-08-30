package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.backend.common.CompilationException
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.Services
import java.nio.file.Files
import java.util.UUID

/*
 * What a compile-stage crash reports, and where the part of it too big to report goes. Kept out of ReplHost
 * because it touches no REPL state — only the throwable it is handed and the cache directory.
 *
 * Only the compile stage spills. A runtime throwable's message is whatever the script chose to construct, and
 * the script's own output channel already carries it; a compiler's is unpredictable, unbounded, and the one
 * thing here worth keeping past the process that produced it.
 */

/** Prepended so it lands above the compiler's own `Please report this problem https://kotl.in/issue`, which is
 *  wrong here: this REPL installs its own IR plugins and rewrites snippet bytecode, so a backend fault is the
 *  mod's until one of those is ruled out, and upstream cannot act on the report either way. */
private const val BLAME = "backend crash — report this to the mod, not to JetBrains\n"

/** How much of an oversized payload stays in the message. The rest is in the file. */
private const val MAX_INLINE = 2000

/**
 * Replace a compile-stage throwable whose message carries an IR dump; anything else is returned untouched.
 *
 * Two shapes reach here, and they differ in whether the dump can be lifted out by identity:
 *  - [CompilationException] (lowering, via `Lower.kt`) exposes it as `content`, so it is CUT OUT of the message
 *    by value — no assumption about the wording built around it, everything else survives verbatim.
 *  - `FunctionCodegen.generate` (codegen) concatenates `irFunction.dump()` into a plain [RuntimeException] at
 *    the throw, leaving no field to lift it out of, so that one is bounded by length instead.
 */
internal fun spilling(t: Throwable, evalId: Int, sourceName: String, code: String): Throwable {
    if (t is CompilationException) return cutDump(t, evalId, sourceName, code)
    val msg = t.message
    if (msg == null || msg.length <= MAX_INLINE) return t
    // The whole message goes to the file, not just the tail: split across two places it would read as neither.
    val marker = spillMarker(msg, evalId, sourceName, code, t.javaClass.name)
    return replacing(t, BLAME + msg.take(MAX_INLINE) + marker)
}

/** The [CompilationException] half: its `content` is the very substring its `message` getter embedded. */
private fun cutDump(e: CompilationException, evalId: Int, sourceName: String, code: String): Throwable {
    val where = e.path?.let { "$it:${e.line}:${e.column}" } ?: sourceName
    val dump = e.content
    // Guarded: the getter rebuilds the whole blob on every access and throws rather than degrading if that build
    // fails. Unguarded, it would replace the very report it feeds.
    val blob = runCatching { e.message }.getOrNull()
    val text = when {
        blob == null -> "at $where (the compiler's own message could not be built)"
        dump == null -> blob
        else -> blob.replace(dump, spillMarker(dump, evalId, where, code, e.javaClass.name))
    }
    return replacing(e, BLAME + text)
}

/** Same stack and same cause, different text — the only part of a throwable that cannot be edited in place. */
private fun replacing(t: Throwable, text: String) = RuntimeException(text, t.cause).apply { stackTrace = t.stackTrace }

/** What the payload is replaced by: its file, or — if nothing could be written — a note saying so. Worded to
 *  read the same whether it is substituted into the message or appended after a truncation. */
private fun spillMarker(payload: String, evalId: Int, where: String, code: String, kind: String): String {
    val file = spill(payload, evalId, where, code, kind)
    val n = payload.length
    return if (file != null) "\n<$n chars elided -> $file>" else "\n<$n chars elided, could not be written to disk>"
}

/**
 * Write [payload] under `<cacheDir>/errors/`; canonical path, or null if that failed.
 *
 * [code] goes in beside it: a dump says what the compiler choked on but not what to feed it to see that again,
 * and reproducing is the only reason to keep one. Canonical, not absolute — the Forge layout's working
 * directory ends in `\.`.
 *
 * A file, not a probe channel: redeeming a probe handle takes a working compiler, which is the one thing this
 * report says is missing.
 */
private fun spill(payload: String, evalId: Int, where: String, code: String, kind: String): String? = runCatching {
    val dir = Services.PLATFORM.cacheDir.resolve("errors")
    Files.createDirectories(dir)
    // Random, never the eval id: that counter restarts each launch, so it would overwrite the last crash's
    // evidence. Nothing parses these names; retention goes by mtime.
    val dest = dir.resolve("compile-crash-${UUID.randomUUID()}.txt")
    val body = "eval=$evalId at=$where kind=$kind " +
        "mod=${Services.PLATFORM.modVersion} mc=${Services.PLATFORM.minecraftVersion}\n" +
        "\n--- script source ---\n$code\n\n--- compiler payload ---\n$payload\n"
    AtomicFiles.publishing(dest) { tmp -> Files.writeString(tmp, body) }
    dest.toRealPath().toString()
}.onFailure { Constants.LOG.warn("[mcp-repl] could not write the compile-crash payload to disk", it) }.getOrNull()
