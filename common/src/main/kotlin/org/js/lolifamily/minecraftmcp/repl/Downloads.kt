package org.js.lolifamily.minecraftmcp.repl

import com.google.gson.JsonParser
import org.js.lolifamily.minecraftmcp.AtomicFiles
import org.js.lolifamily.minecraftmcp.Constants
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.zip.ZipFile

/**
 * Fetching the per-version mapping artifacts [RemapCache] provisions from: where they live, how to retry a
 * transient failure, and how to verify what came back. Nothing here knows about the cache layout or the
 * masking-loader hop — it hands back files.
 *
 * Runs on the game loader, like its caller: MC's bundled Gson and the JDK HttpClient both live there.
 */
internal object Downloads {

    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    /** BuildTools' own per-version lookup. Required, not a convenience: BuildData REPLACES `mappings/` each
     *  version rather than accumulating, so a version's csrg exists only at the commit `refs.BuildData` pins
     *  — master carries none of them. The commit is also the integrity gate, since BuildData publishes no hash. */
    private const val SPIGOT_VERSIONS = "https://hub.spigotmc.org/versions"

    /** The REST API, NOT `/stash/projects/.../raw/...`: the raw path sits behind Cloudflare's interactive
     *  challenge and answers a plain GET with an HTML page. REST is not challenged. */
    private const val SPIGOT_BUILDDATA = "https://hub.spigotmc.org/stash/rest/api/1.0/projects/SPIGOT/repos/builddata"

    private const val ATTEMPTS = 3

    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

    // connectTimeout does not cover the response body: without these, a host that connects and then goes
    // silent blocks send() forever.
    private val JSON_TIMEOUT: Duration = Duration.ofSeconds(30)
    private val FILE_TIMEOUT: Duration = Duration.ofMinutes(2)

    private val HTTP: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    /** The MCP here is Mod Coder Pack (forge's obf->srg mappings), not this mod's Model Context Protocol. */
    fun mcpConfigUrl(v: String): String = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/$v/mcp_config-$v.zip"

    fun intermediaryUrl(v: String): String = "https://maven.fabricmc.net/net/fabricmc/intermediary/$v/intermediary-$v-v2.jar"

    fun extractZipEntry(zip: Path, entry: String, dest: Path) {
        ZipFile(zip.toFile()).use { zf ->
            val e = zf.getEntry(entry) ?: error("no $entry in $zip")
            AtomicFiles.publishing(dest) { tmp ->
                zf.getInputStream(e).use { input ->
                    tmp.toFile().outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
            }
        }
    }

    /** Mojang manifest -> the version's JSON -> client_mappings url. */
    fun downloadClientMappings(version: String, dir: Path): Path {
        val manifest = JsonParser.parseString(get(MANIFEST)).asJsonObject
        var versionUrl: String? = null
        for (el in manifest.getAsJsonArray("versions")) {
            val vo = el.asJsonObject
            if (version == vo.get("id").asString) {
                versionUrl = vo.get("url").asString
                break
            }
        }
        if (versionUrl == null) error("version $version not in Mojang manifest")
        val vj = JsonParser.parseString(get(versionUrl)).asJsonObject
        // The sha1 lives in the same downloads.client_mappings node as the url — a free integrity gate, no extra
        // request. (The intermediary jar and mcp_config zip pass no hash: a corrupt one there fails the build.)
        val cm = vj.getAsJsonObject("downloads").getAsJsonObject("client_mappings")
        return download(cm.get("url").asString, dir.resolve("client_mappings.txt"), cm.get("sha1").asString)
    }

    /**
     * SpigotMC BuildData's per-version CLASS mappings (`obf -> spigot`), the axis Mojang's proguard cannot
     * supply: spigot's class names are spigot's own invention, derivable from no Mojang artifact.
     *
     * Only class mappings are published from 1.17 on — there is no `-members.csrg`, which is the same fact
     * seen from the other side as "a spigot runtime's member names ARE the obf names".
     *
     * The `archive` endpoint returns the whole file in one request; REST `browse` would page it as JSON lines.
     */
    fun downloadSpigotClassMappings(version: String, dir: Path): Path {
        val refs = JsonParser.parseString(get("$SPIGOT_VERSIONS/$version.json")).asJsonObject
            .getAsJsonObject("refs") ?: error("no refs in spigot versions/$version.json")
        val ref = refs.get("BuildData")?.asString ?: error("no refs.BuildData in spigot versions/$version.json")
        // Both the zip's entry name and the request's path filter — Stash preserves the repo-relative path.
        val entry = "mappings/bukkit-$version-cl.csrg"
        val zip = download("$SPIGOT_BUILDDATA/archive?at=$ref&format=zip&path=$entry", dir.resolve("builddata-$version.zip"))
        val out = dir.resolve("bukkit-$version-cl.csrg")
        extractZipEntry(zip, entry, out)
        return out
    }

    fun download(url: String, dest: Path, expectedSha1: String? = null): Path = withRetry("download $url") {
        AtomicFiles.publishing(dest) { tmp ->
            val handler = HttpResponse.BodyHandlers.ofFile(tmp, StandardOpenOption.WRITE)
            val r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(FILE_TIMEOUT).build(), handler)
            checkStatus(r.statusCode(), url)
            // Verify against the publisher's hash when one is known: a corrupt/truncated body can still return 200
            // (e.g. a CDN abort mid-stream), which the write itself cannot detect. Throw IOException so withRetry
            // re-fetches on a fresh connection instead of caching corrupt input forever.
            if (expectedSha1 != null) {
                val actual = sha1Of(tmp)
                if (!actual.equals(expectedSha1, ignoreCase = true)) {
                    throw java.io.IOException("sha1 mismatch for $url: expected $expectedSha1, got $actual")
                }
            }
        }
        dest
    }

    private fun get(url: String): String = withRetry("GET $url") {
        val r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(JSON_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
        checkStatus(r.statusCode(), url)
        r.body()
    }

    /** Retry a synchronous, idempotent GET on TRANSIENT failures only: IOException (connection reset/abort/
     *  timeout) and 429/5xx. 4xx is permanent → not retried. 3 attempts, exponential backoff. The JDK
     *  HttpClient's own retry is connection-establishment-only. */
    private fun <T> withRetry(what: String, block: () -> T): T {
        repeat(ATTEMPTS - 1) { i ->
            try { return block() } catch (e: java.io.IOException) {
                Constants.LOG.warn("[mcp-remap] {} attempt {}/{} failed, retrying...", what, i + 1, ATTEMPTS, e)
                Thread.sleep(400L shl i)   // 400ms, 800ms
            }
        }
        return block()   // last attempt: nothing left to retry with, so its IOException IS the answer
    }

    /** Map a retryable HTTP status (429 / 5xx) to an IOException so [withRetry] retries it; leave 4xx permanent. */
    private fun checkStatus(code: Int, url: String) {
        if (code == 429 || code >= 500) throw java.io.IOException("HTTP $code for $url (transient)")
        if (code != 200) error("HTTP $code for $url")
    }

    /** SHA-1 of [file], streamed (client_mappings is ~9MB). Hex, compared case-insensitively against Mojang's
     *  published `downloads.*.sha1`. */
    private fun sha1Of(file: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest())
    }
}
