package org.js.lolifamily.minecraftmcp.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.js.lolifamily.minecraftmcp.ConfigFile
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.Props
import org.js.lolifamily.minecraftmcp.exec.EvalHandle
import org.js.lolifamily.minecraftmcp.exec.Lanes
import org.js.lolifamily.minecraftmcp.exec.Outcome
import org.js.lolifamily.minecraftmcp.mcp.McpJson.error
import org.js.lolifamily.minecraftmcp.mcp.McpJson.instructions
import org.js.lolifamily.minecraftmcp.mcp.McpJson.jsonObject
import org.js.lolifamily.minecraftmcp.mcp.McpJson.success
import org.js.lolifamily.minecraftmcp.mcp.McpJson.toolImage
import org.js.lolifamily.minecraftmcp.mcp.McpJson.toolText
import org.js.lolifamily.minecraftmcp.mcp.McpJson.toolsList
import org.js.lolifamily.minecraftmcp.platform.Services
import org.js.lolifamily.minecraftmcp.repl.Mappings
import org.js.lolifamily.minecraftmcp.repl.ReplBridge
import org.js.lolifamily.minecraftmcp.security.AuthGate
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** `isString`, not `isJsonPrimitive`: gson's `asString` stringifies a number or boolean and cannot be told not
 *  to, so `{"code": 123}` would compile and run `123`. Absent (or JSON null) reads as [missing]; a `null` return
 *  means present with the wrong type, which the caller refuses rather than folding into [missing]. */
private fun JsonObject.stringOr(key: String, missing: String?): String? {
    val e = get(key)
    if (e == null || e.isJsonNull) return missing
    return (e as? JsonPrimitive)?.takeIf { it.isString }?.asString
}

/** [stringOr]'s contract for booleans: `"false"` is a string, not a false, and folding the two would turn a
 *  typo into a policy. */
private fun JsonObject.boolOr(key: String, missing: Boolean): Boolean? {
    val e = get(key)
    if (e == null || e.isJsonNull) return missing
    return (e as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
}

/** A nested object, or an empty one: a missing member and a non-object one both mean "no fields to read". */
private fun JsonObject.objOr(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

/** Stable string form of a JSON-RPC id for the in-flight key: `2` -> `"2"`, `"a"` -> `"\"a\""`. The
 *  cancellation's requestId is stringified the same way, so the two match. */
private fun idKey(id: JsonElement?): String = if (id == null || id.isJsonNull) "null" else id.toString()

private fun notReady(target: String): String = "$target lane not ready. Ready targets: ${Lanes.readyTargets()}."

/**
 * The single MCP endpoint the mod exposes: Streamable HTTP, bound to loopback, Bearer-token gated.
 * Three tools: `execute_code` (runs Kotlin inside the running game via [ReplBridge]), `run_command` (runs a
 * Minecraft command) and `take_screenshot` (the client's framebuffer, as an image).
 *
 * One JSON-RPC response per POST — no SSE server->client stream. `Mcp-Session-Id` is required on everything
 * but `initialize` and scopes cancellation. Issued ids are deliberately NOT recorded: one that outlived a game
 * restart still works, where the spec's 404-unknown-session would send the model back through `initialize` and
 * cost it its conversation context and its prompt cache. `execute_code` submits to an execution lane and blocks
 * this thread on the eval's future while the lane steps it. The safety model: loopback-only bind, a constant-time
 * compare of the one credential the request carried — `Authorization: Bearer <token>` or `?token=<token>`,
 * never both ([Auth]) — and [reject], which answers nothing at all to anything that is not a plausible client.
 *
 * Gson and the JDK's `com.sun.net.httpserver` both ship with the game — no new dependency, and no JVM flag
 * (see the README's JVM-flags section).
 */
class McpServer private constructor() {
    /** Null when nothing supplied one and we had to mint it. */
    private val suppliedToken: String? = Props.str("mcp.token")

    private val token: String = suppliedToken ?: UUID.randomUUID().toString().replace("-", "")

    /** Whether the url form can carry [token]. On [URL_SAFE_TOKEN] percent- and form-decoding are both the
     *  identity, so the url value compares as it arrived; off it they disagree on `+` and nothing between the
     *  config file and the url is an encoder that could settle it. The header carries any token, so only this
     *  spelling drops. */
    private val urlTokenUsable: Boolean = URL_SAFE_TOKEN.matches(token)

    /**
     * In-flight cancellable evals, keyed `<session>:<requestId>`. The session segment is the client-supplied
     * `Mcp-Session-Id`, so two clients can't cancel each other's ids — which the request id alone cannot
     * promise, every client numbering its own from 1.
     *
     * `execute_code` only: the other two block on the game and cannot be cancelled.
     */
    private val inflight = ConcurrentHashMap<String, EvalHandle>()

    /** Bound exactly or not at all; null lets the default sweep climb. Nothing ever writes a port back, so a
     *  value here was put there by a human — "set" and "meant it" are the same thing. */
    private val suppliedPort: Int? = Props.resolve("mcp.port")?.let { r ->
        // `0` is not "pick one for me": the sweep binds ONE shared port across every loopback address, and
        // the kernel would hand out a different ephemeral one per address.
        val n = r.value.toIntOrNull()
        if (n != null && n in 1..65535) return@let n
        Constants.LOG.warn("[mcp] {} is not a port in 1..65535 — using the default", r)
        null
    }

    // "localhost" is a SET of addresses (127.0.0.1 + ::1), and which one a client dials is its resolver's
    // business — glibc prefers ::1, the JDK prefers 127.0.0.1 and resolves only that one, with no fallback.
    // There is no "any loopback" address to bind and the wildcard would leave loopback, so bind both.
    private val servers: List<HttpServer> =
        if (suppliedPort != null) bindAll(suppliedPort, 1) else bindAll(DEFAULT_PORT, DEFAULT_TRIES)

    // Shared by every listener, so reject()'s Host check has one answer. Read back because bindAll()
    // may have stepped past a busy port.
    private val port: Int = servers[0].address.port

    /** The `Host` values [reject] accepts. Two on port 80: http's default, which a conforming client may
     *  normalize away (curl, JDK, python) or send verbatim (go) — both spell this one endpoint. */
    private val hostAuthorities: Set<String> = if (port == 80) setOf("localhost:80", "localhost") else setOf("localhost:$port")

    /** Which [Reject] kinds have been logged. Keyed by the kind, never by the offending value: a scanner picks
     *  the value, so keying on it would grow this without bound. */
    private val logged = ConcurrentHashMap.newKeySet<Reject>()

    // bindAll() already took the ports and a field initializer cannot be rolled back, so everything fallible
    // after it sits in one try. Nothing between `servers` and here can throw (`port` is two non-null reads), so
    // this covers the whole exposed window — in practice the config write below, on an unwritable config dir.
    init {
        try {
            // One pool across every listener. Cached: handlers block on the eval's block-until-done future, so a
            // fixed/small pool would let a few in-flight evals starve new requests.
            // Never stored, and `HttpServer.stop()` leaves a user executor alone — so nothing can interrupt an
            // mcp-http thread, which is why the blocking `get()`s below catch no InterruptedException.
            val n = AtomicInteger()
            val pool = Executors.newCachedThreadPool { r ->
                Thread(r, "mcp-http-${n.incrementAndGet()}")
                    .apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }
            }
            // "/", not PATH: a request matching no context is answered by the JDK itself
            // (ServerImpl.Exchange.run -> reject(HTTP_NOT_FOUND)) BEFORE dispatch, so a handler registered
            // deeper could not stay silent on `/` — the first thing anything scanning this port asks for.
            for (s in servers) {
                s.createContext("/") { ex -> handle(ex) }
                s.executor = pool
            }

            // Before the listeners: a token the file never received is one nobody can read. The port is not
            // saved — read back it is indistinguishable from a chosen one, which [suppliedPort] binds exactly.
            if (suppliedToken == null) ConfigFile.persist(mapOf("mcp.token" to token), seed = listOf("mcp.port"))

            onDaemonThread("mcp-http-start") { servers.forEach(HttpServer::start) }   // binding happened in bindAll()

            Constants.LOG.info(
                "[MCP] listening on http://localhost:{}{} ({})",
                port, PATH, servers.joinToString { it.address.address.hostAddress },
            )
            if (suppliedToken == null) {
                Constants.LOG.info("[MCP] bearer token written to {} — point your MCP client at it", Services.PLATFORM.configPath)
            }
            if (!urlTokenUsable) {
                Constants.LOG.warn(
                    "[MCP] mcp.token has characters outside [A-Za-z0-9._~-] — the ?token= url form is DISABLED " +
                        "for it (Authorization: Bearer is unaffected). Repin it from that alphabet to re-enable.",
                )
            }
        } catch (t: Throwable) {
            // Or the ports outlive this half-built object: a NIO channel is not GC-closed, so nothing would ever
            // give them back and `start()`'s "failed to start" would read as if nothing had been taken.
            release(servers)
            throw t
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    private fun handle(ex: HttpExchange) {
        try {
            reject(ex)?.let {
                logRejected(it, ex)
                return // no sendResponseHeaders -> ExchangeImpl.close() shuts the socket having written nothing
            }
            when (authorize(ex)) {
                Auth.OK -> {}
                // Malformed request, not a refused one — a bare 403 here reads as "wrong token" and the client
                // retries the same pair forever.
                Auth.AMBIGUOUS -> {
                    sendJson(ex, 400, error(null, -32600, "send the token once: Authorization: Bearer OR ?token=, not both"))
                    return
                }
                // 403, not the usual 401: a 401 MUST carry WWW-Authenticate, and that challenge sends a conforming
                // client probing /.well-known/oauth-* — GETs [reject] answers with silence. No OAuth here to find.
                Auth.UNAUTHORIZED -> {
                    sendEmpty(ex, 403)
                    return
                }
            }

            // Unbounded read: auth ran above, and the bearer token already grants arbitrary code execution in
            // this JVM — a large POST is not the threat. Keep every auth check above this line.
            val body = String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            val parsed: JsonElement = try {
                JsonParser.parseString(body)
            } catch (e: JsonParseException) {
                sendJson(ex, 400, error(null, -32700, "Parse error: ${e.message}"))
                return
            }

            // No JSON-RPC batches.
            if (!parsed.isJsonObject) {
                sendJson(ex, 400, error(null, -32600, "Expected a single JSON-RPC object"))
                return
            }

            val req = parsed.asJsonObject
            // A `method` that is present but not a string is not "initialize" either, so it is refused below
            // rather than reaching dispatch.
            val isInitialize = "initialize" == req.stringOr("method", "")
            val reqSession = ex.requestHeaders.getFirst("Mcp-Session-Id").orEmpty()
            if (reqSession.isEmpty() && !isInitialize) {
                sendJson(ex, 400, error(req.get("id"), -32600, "Mcp-Session-Id required — send the one initialize returned"))
                return
            }
            val response = dispatch(req, reqSession)
            if (response == null) {
                sendEmpty(ex, 202) // notification: accepted, no body
            } else {
                // Minted, not recorded: the next request's id is taken as it arrives.
                if (isInitialize) {
                    ex.responseHeaders.add("Mcp-Session-Id", UUID.randomUUID().toString())
                }
                sendJson(ex, 200, response)
            }
        } catch (t: Throwable) {
            Constants.LOG.error("[MCP] handler error", t)
            try {
                sendJson(ex, 500, error(null, -32603, "Internal error: $t"))
            } catch (_: Throwable) {
            }
        } finally {
            ex.close()
        }
    }

    /** Why a request gets no reply. */
    private enum class Reject { METHOD, ORIGIN, HOST, PATH }

    /**
     * The browser-facing half of the safety model, and the whole reason nothing here answers with a status:
     *
     * A cross-origin `fetch` cannot read our status or body, but it CAN tell a resolved promise from a
     * rejected one — so ANY reply, 403 included, is the one bit a page needs to fingerprint this port. Writing
     * nothing makes us indistinguishable from a closed port. Nobody legitimate lands here: a real client is a
     * POST that sets no `Origin` (browsers always do, cross-origin) and spells [hostAuthorities] exactly, which
     * is what turns a rebinding attack away — it arrives carrying the attacker's own domain in `Host`.
     *
     * `127.0.0.1:<port>`, `LOCALHOST:<port>` and `/mcp` are refused with the rest, deliberately: one blessed
     * spelling, matched as literal bytes, with no normalization to reason about.
     *
     * @return the kind, or null to proceed.
     */
    private fun reject(ex: HttpExchange): Reject? {
        // GET would open an SSE stream in full Streamable HTTP; this endpoint has no server->client stream.
        if (!"POST".equals(ex.requestMethod, ignoreCase = true)) return Reject.METHOD
        if (!ex.requestHeaders.getFirst("Origin").isNullOrEmpty()) return Reject.ORIGIN
        // Bound first: an absent Host is not a member, and `in` on a platform-typed null is not worth relying on.
        val host = ex.requestHeaders.getFirst("Host")
        if (host == null || host !in hostAuthorities) return Reject.HOST
        if (ex.requestURI.path != PATH) return Reject.PATH
        return null
    }

    /** The ONLY channel a refusal has, the wire carrying nothing — so it prints every field that decides one.
     *  Once per [Reject] kind: whoever needs this reads it while setting the client up, and past that the same
     *  line is a scanner's to repeat. */
    private fun logRejected(kind: Reject, ex: HttpExchange) {
        if (!logged.add(kind)) return
        Constants.LOG.warn(
            "[MCP] refused ({}), no reply sent: {} {} Host={} Origin={} — this endpoint is POST http://localhost:{}{}",
            kind, ex.requestMethod, ex.requestURI.path,
            ex.requestHeaders.getFirst("Host"), ex.requestHeaders.getFirst("Origin"), port, PATH,
        )
    }

    private enum class Auth { OK, UNAUTHORIZED, AMBIGUOUS }

    /**
     * The token is spelled either `Authorization: Bearer <token>` or `?token=<token>` — not every MCP client can
     * attach a custom header, and a url is the one thing all of them take. Both at once is [Auth.AMBIGUOUS]
     * rather than a precedence rule: picking a winner would authorize under a token the caller may not have
     * meant to send. Any `Authorization` header counts as that one, whatever it spells: reading past a
     * malformed one to the url would authorize a request whose header half is broken.
     *
     * The url value is never decoded — see [urlTokenUsable].
     */
    private fun authorize(ex: HttpExchange): Auth {
        val header: String? = ex.requestHeaders.getFirst("Authorization")
        // rawQuery, not URI.getQuery(): that decodes. `&` is a separator here and never token content — it is
        // outside [URL_SAFE_TOKEN], so a token carrying one cannot reach this branch anyway.
        val rawToken = ex.requestURI.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("token=") }?.drop(6)
        if (header != null && rawToken != null) return Auth.AMBIGUOUS
        // Selects rather than falls back: the check above leaves at most one side non-null, so a header that
        // isn't `Bearer` presents nothing and lands on UNAUTHORIZED instead of on the url.
        val presented = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            ?: rawToken?.takeIf { urlTokenUsable }
            ?: return Auth.UNAUTHORIZED
        val ok = MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )
        return if (ok) Auth.OK else Auth.UNAUTHORIZED
    }

    // ---- JSON-RPC / MCP ------------------------------------------------------------------------

    /**
     * @return the JSON-RPC response object, or `null` when nothing should be sent back.
     *
     * A notification is a request with no `id`, regardless of method — hence the single gate at the bottom
     * rather than one per branch: an id-less `tools/call` must execute-but-not-reply like any other.
     */
    private fun dispatch(req: JsonObject, reqSession: String): JsonObject? {
        val id = req.get("id")
        val isNotification = id == null || id.isJsonNull
        val method = req.stringOr("method", "").orEmpty()
        val params = req.objOr("params")

        val result = when (method) {
            "initialize" -> {
                @Suppress("ktlint:standard:wrapping")
                success(id, jsonObject {
                    addProperty("protocolVersion", PROTOCOL_VERSION)
                    add("capabilities", jsonObject { add("tools", JsonObject()) })
                    add("serverInfo", jsonObject {
                        addProperty("name", Constants.MOD_ID)
                        addProperty("version", Services.PLATFORM.modVersion)
                    })
                    addProperty("instructions", instructions())
                })
            }
            "notifications/initialized" -> null
            "notifications/cancelled" -> {
                handleCancel(params, reqSession)
                null
            }
            "ping" -> success(id, JsonObject())
            "tools/list" -> success(id, jsonObject { add("tools", toolsList()) })
            "tools/call" -> toolsCall(id, params, reqSession)
            else -> error(id, -32601, "Method not found: $method")
        }

        return if (isNotification) null else result
    }

    private fun toolsCall(id: JsonElement?, params: JsonObject, reqSession: String): JsonObject? {
        val name = params.stringOr("name", "").orEmpty()
        val args = params.objOr("arguments")
        return when (name) {
            "execute_code" -> executeCode(id, args, reqSession)
            "run_command" -> runCommand(id, args)
            "take_screenshot" -> takeScreenshot(id)
            else -> error(id, -32602, "Unknown tool: $name")
        }
    }

    private fun executeCode(id: JsonElement?, args: JsonObject, reqSession: String): JsonObject {
        val code = args.stringOr("code", null)
            ?: return error(id, -32602, "Argument 'code' is missing or not a string")
        // Refused, not defaulted: an unreadable target must not silently pick a lane.
        val target = args.stringOr("target", "")
            ?: return error(id, -32602, "Argument 'target' is not a string")
        val lane = Lanes.byTarget(target)
            ?: return error(id, -32602, "unknown target '$target' — use one of: ${Lanes.targets().joinToString(", ")}")

        // World/server authority; the token/loopback/Origin checks are transport auth. Fast reject only —
        // the lanes re-check.
        val gate = AuthGate.decision
        if (!gate.allowed) return error(id, -32002, "not authorized: ${gate.reason}")

        // warmDone opens once the compiler is built (or warmup failed and compile() will lazy-rebuild). Two
        // answers, because the plugin gate clears on the server's timescale rather than the compiler's.
        if (!ReplBridge.warmDone) {
            val why = if (ReplBridge.pluginsLatch.count > 0L) {
                "server has not finished loading worlds — retry when it has"
            } else {
                "REPL warming up (compiler build in progress) — try again in a few seconds"
            }
            return error(id, -32002, why)
        }

        // Run on the chosen lane, block until done.
        if (!lane.isReady) {
            return error(id, -32002, notReady(lane.name))
        }

        // Registered inside submit(), before the eval starts, so a cancel for an already-running eval always
        // finds it. remove(key, handle) below clears only our own entry — client reusing an id, newer put wins.
        val key = "$reqSession:${idKey(id)}"
        // submit() registers and only then starts a thread, so it can throw with the entry already in — and the
        // finally below starts a line too late to take it back. Bare key: submit never returned, so the entry
        // under it is ours.
        val handle = try {
            lane.submit(code) { inflight[key] = it }
        } catch (t: Throwable) {
            inflight.remove(key)
            throw t
        }
        val outcome: Outcome = try {
            // No timeout: an eval ends by finishing, by client cancellation, or by the lane reaping it
            // (server stop, authorization revoke). Cancellation COMPLETES the future (EvalTask.cancel) rather
            // than cancelling it, so get() never raises CancellationException here.
            handle.future().get()
        } catch (e: ExecutionException) {
            Outcome("execute_code failed: ${e.cause ?: e}", true)
        } finally {
            inflight.remove(key, handle)
        }
        return toolText(id, outcome.text, outcome.isError)
    }

    /**
     * A `notifications/cancelled` for an in-flight execute_code: cancel the eval registered under
     * `<session>:<requestId>`. Its future completes immediately, tagged `(cancelled)` with whatever it had
     * already printed, and the pump stops driving it. Unknown ids are silently ignored per the MCP spec.
     */
    private fun handleCancel(params: JsonObject, reqSession: String) {
        if (!params.has("requestId") || params.get("requestId").isJsonNull) return
        val h = inflight["$reqSession:${idKey(params.get("requestId"))}"]
        h?.cancel()
    }

    private fun runCommand(id: JsonElement?, args: JsonObject): JsonObject {
        val command = args.stringOr("command", null)
            ?: return error(id, -32602, "Argument 'command' is missing or not a string")
        // Both targets resolve game members by NAME, and a miss returns a wrong answer rather than failing.
        // Scoped to this tool deliberately: a tool that does no name lookup must not inherit the gate.
        if (!Mappings.namesResolvable()) {
            return error(id, -32002, "runtime mappings not loaded — command feedback would be silently dropped")
        }
        // Refused, not defaulted: an unreadable target must not silently reach the level-4 server path.
        val target = args.stringOr("target", Lanes.DEFAULT.name)
            ?: return error(id, -32602, "Argument 'target' is not a string")
        if (target !in Lanes.commandTargets()) {
            return error(id, -32602, "unknown target '$target' — use one of: ${Lanes.commandTargets().joinToString(", ")}")
        }
        val allowUntrusted = args.boolOr("allow_untrusted_chat", false)
            ?: return error(id, -32602, "Argument 'allow_untrusted_chat' is not a boolean")
        return when (target) {
            "client" -> {
                // Runs as the player through the vanilla chat path, at the player's own permission — client-only
                // mod commands run locally, everything else goes to the connected server, which enforces its own
                // rules. No elevation is granted, so the AuthGate does not apply. This is what lets a non-OP
                // player on a remote server (or a single-player world with cheats off) run commands via MCP.
                if (!Lanes.CLIENT.isReady) return error(id, -32002, notReady("client"))
                val r = ClientCommandRunner.run(command, allowUntrusted)
                    ?: return error(id, -32002, "client not connected to a server. Ready targets: ${Lanes.readyTargets()}.")
                toolText(id, r, false)
            }
            else -> {
                // `server` — the only name the check above leaves.
                // Runs in the local server at permission level 4 — above the player's own — so the AuthGate
                // applies. run_command never reaches the lane pump, so this is its only enforcement point.
                // Needs a local server: a pure client on a remote server has none, hence the isReady check.
                if (!Lanes.SERVER.isReady) return error(id, -32002, notReady("server"))
                val gate = AuthGate.decision
                if (!gate.allowed) return error(id, -32002, "not authorized: ${gate.reason}")
                val server = Lanes.SERVER.tickSource
                    ?: return error(id, -32002, notReady("server"))
                toolText(id, Services.PLATFORM.runCommands(server, command), false)
            }
        }
    }

    /** No [AuthGate]: this returns what the player is already looking at, so it confers nothing they don't
     *  already have — the same reasoning that exempts `run_command target="client"`. */
    private fun takeScreenshot(id: JsonElement?): JsonObject {
        // Ahead of everything else: past this line the client-only ScreenshotRunner resolves, which on a
        // dedicated server is a NoClassDefFoundError.
        if (!Lanes.RENDER.isReady) return error(id, -32002, notReady("render"))

        val image = try {
            ScreenshotRunner.grab()
        } catch (e: ExecutionException) {
            return error(id, -32002, "take_screenshot failed: ${e.cause ?: e}")
        }
        if (image == null) {
            return error(id, -32002, "no image — the client has no render target, or this runtime has no usable screenshot API")
        }
        return toolImage(id, image)
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-03-26"
        private const val DEFAULT_PORT = 25599

        /** The one path served, matched exactly — `/mcp` without it is refused like any other miss. */
        private const val PATH = "/mcp/"

        /** How far the DEFAULT port may climb past [DEFAULT_PORT]; an explicit port never climbs. */
        private const val DEFAULT_TRIES = 10

        /** RFC 3986 unreserved — see [urlTokenUsable]. A generated token (32 hex chars) always matches. */
        private val URL_SAFE_TOKEN = Regex("[A-Za-z0-9._~-]+")

        /** No HTML escaping: it rewrites `=` as a `\u` escape, and JVM `toString()` output is full of `=` —
         *  that bloats the response and leaves its reader decoding noise. The escape only matters inside HTML. */
        private val GSON = GsonBuilder().disableHtmlEscaping().create()

        // Literals, never a "localhost" lookup: its result varies by host and JVM, and the point is to cover
        // every spelling rather than pick one. The complete set ever bound; not configurable.
        private val LOOPBACKS: List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))

        /** The list as a message spells it: `127.0.0.1, 0:0:0:0:0:0:0:1`. */
        private fun List<InetAddress>.hostAddresses(): String = joinToString { it.hostAddress }

        /**
         * Bind every loopback address this host HAS on ONE shared port, trying [tries] ports from [start],
         * and really give back a port we turn down.
         *
         * A port counts as free only when it is free on every one of them: a half-taken port belongs to
         * another process, and a client whose resolver picks that address would hand it our bearer token. So
         * contention is a hard failure.
         *
         * An address no interface carries can be dialed by nobody, so it was never in the set to own —
         * [configured] settles that BEFORE the sweep, which leaves no exception to make afterwards. The sweep
         * itself is still never preceded by a probing BIND: that one answers about one port at one moment,
         * with nothing holding it true in between.
         *
         * `tries == 1` means that exact port or nothing: an explicit port is the caller's to own.
         */
        private fun bindAll(start: Int, tries: Int): List<HttpServer> {
            val present = LOOPBACKS.filter(::configured)
            if (present.isEmpty()) {
                throw IOException(
                    "no loopback address is configured on this host (${LOOPBACKS.hostAddresses()}) — is the loopback interface up?",
                )
            }
            if (present.size < LOOPBACKS.size) {
                Constants.LOG.warn(
                    "[MCP] this host has no {} — a client resolving localhost to it will not reach us",
                    (LOOPBACKS - present.toSet()).hostAddresses(),
                )
            }
            return sweep(present, start, tries) ?: throw IOException(
                "no port in $start..${start + tries - 1} is free on every loopback address (${present.hostAddresses()})",
            )
        }

        /**
         * Whether [a] is configured on an interface of this host — i.e. whether any client's resolver can be
         * sent there at all. The interface table, not a probing bind: a bind answers "can THIS JVM take it",
         * which is a different question, and it needs an ephemeral port that is not always there.
         *
         * Unreadable reads as PRESENT: this answer only ever RELAXES the rule in [bindAll], so an uncertainty
         * has to resolve to not relaxing it.
         */
        private fun configured(a: InetAddress): Boolean = try {
            NetworkInterface.getByInetAddress(a) != null
        } catch (_: SocketException) {
            true
        }

        /** One pass over the range: the first port free on every [addrs] wins, and a port that binds only
         *  some of them is given back before moving on. Null once the range is exhausted. Each refusal is
         *  logged at DEBUG rather than kept — climbing past a taken port is what this is for, not an
         *  anomaly, and a single retained exception would be an arbitrary one of many. */
        private fun sweep(addrs: List<InetAddress>, start: Int, tries: Int): List<HttpServer>? {
            for (p in start until start + tries) {
                val got = addrs.mapNotNull { a ->
                    try {
                        HttpServer.create(InetSocketAddress(a, p), 0)
                    } catch (e: IOException) {
                        Constants.LOG.debug("[MCP] {}:{} unavailable ({})", a.hostAddress, p, "$e")
                        null
                    }
                }
                if (got.size == addrs.size) return got
                if (got.isNotEmpty()) release(got)   // give the rest back before moving on
            }
            return null
        }

        /** Give the bound ports back, started or not: `stop()` on an unstarted server keeps the port, and
         *  `start()` on a started one throws. */
        private fun release(servers: List<HttpServer>) {
            onDaemonThread("mcp-http-release") {
                for (s in servers) {
                    runCatching { s.start() }
                    runCatching { s.stop(0) }
                }
            }
        }

        /** Run [body] on a daemon thread and wait. `HttpServer`'s dispatcher copies the daemon flag of
         *  whichever thread calls `start()`, and mod init is not one — a dispatcher started from there would
         *  keep the JVM alive after /stop. `stop(0)` joins it, so a released server leaves nothing behind. */
        private fun onDaemonThread(name: String, body: () -> Unit) {
            var failure: Throwable? = null
            val t = Thread({ runCatching(body).onFailure { failure = it } }, name)
                .apply { isDaemon = true; contextClassLoader = Constants.GAME_LOADER }
            t.start()
            t.join()
            failure?.let { throw it }
        }

        @Volatile
        private var instance: McpServer? = null

        /** Idempotent. Binds the endpoint and logs the port + token; never crashes the game on failure. */
        @Synchronized
        fun start() {
            if (instance != null) return
            // ConfigFile already logged why. Starting anyway would mint a token it cannot receive.
            if (ConfigFile.failure != null) return
            try {
                instance = McpServer()
            } catch (t: Throwable) {
                Constants.LOG.error("[MCP] failed to start", t)
            }
        }

        /** Chunked, not buffered: `toJson(body)` materializes the document as a String and again as a byte[],
         *  and a String also caps how large a response can ever be. Fixing the status at the first byte only
         *  costs a 500 an OOM here could not have sent anyway. Orthogonal to SSE — the content type still
         *  selects the client's non-SSE path. */
        private fun sendJson(ex: HttpExchange, status: Int, body: JsonObject) {
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, 0L) // 0 => chunked
            OutputStreamWriter(ex.responseBody, StandardCharsets.UTF_8).use { GSON.toJson(body, it) }
        }

        private fun sendEmpty(ex: HttpExchange, status: Int) {
            ex.sendResponseHeaders(status, -1L)
        }
    }
}
