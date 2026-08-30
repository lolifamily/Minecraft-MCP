package org.js.lolifamily.minecraftmcp.repl

import org.js.lolifamily.minecraftmcp.Constants
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolve a CodeSource or module-reference location to the real backing jar [File], across loaders.
 *
 * Fabric (Knot) and dev give a plain `file:` URL — `File(uri)` works. NeoForge/Forge wrap the mod jar in
 * securejarhandler's `union:` filesystem, whose URI `File(uri)` rejects with
 * `IllegalArgumentException: URI scheme is not "file"`. For those we ask the union filesystem for its real
 * backing path via its public `getPrimaryPath()`. It's reflective because
 * `cpw.mods.niofs.union.UnionFileSystem` is a loader class not on the vanilla/common compile classpath;
 * a string-parse of the wrapped URI is the last-resort fallback.
 */
object JarLocator {

    /** The real jar/dir File backing [loc], or null if it can't be resolved. */
    @JvmStatic
    fun toJarFile(loc: URL?): File? {
        if (loc == null) return null
        val uri: URI = try {
            loc.toURI()
        } catch (_: Throwable) {
            return null
        }
        return toJarFile(uri)
    }

    /**
     * The real jar/dir File backing a location [uri], or null. Module reference locations
     * ([java.lang.module.ModuleReference.location]) arrive as URIs, and a `union:`/`jar:` URI has no URL stream
     * handler (so `URI.toURL()` throws) — so resolve straight from the URI.
     */
    fun toJarFile(uri: URI?): File? {
        if (uri == null) return null
        // 1) plain file: (fabric, dev classes dir/jar)
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            try {
                return File(uri)
            } catch (_: Throwable) { /* fall through */ }
        }
        // 2) securejar union: (neoforge/forge) — the filesystem knows its real backing jar
        try {
            val p: Path = Paths.get(uri) // UnionPath: the union provider is installed on FML loaders
            val fs = p.fileSystem
            val primary = fs.javaClass.getMethod("getPrimaryPath").invoke(fs)
            if (primary is Path) {
                // A union over a jar-in-jar mod has a VIRTUAL primary (the `jij:` view of the nested jar).
                // No real file backs it, and the string parse below would answer with the CONTAINER — a
                // different library's type set. Give up here so the caller repacks the union tree instead.
                if (primary.fileSystem != FileSystems.getDefault()) return null
                val f = primary.toFile()
                if (f.isFile) return f
            }
        } catch (_: Throwable) {
            // not a union fs (or getPrimaryPath absent on this version) — fall through to string parsing
        }
        // 3) last resort — strip to a bare path so all three shapes (file:/..., jar:file:/...!/,
        //    union:/...#<n>) converge on one File(path) build.
        //    schemeSpecificPart is already %-decoded, so the securejar index arrives as #<n> (not %23<n>) and a '+'
        //    in the jar name stays literal — do NOT URLDecoder.decode, which turns '+' into a space and would
        //    corrupt names like "Connector-1.0.0-beta.49+1.20.1.jar". Being decoded is also why the text must NOT
        //    go back through URI.create(): a literal space (E:/my mods/foo.jar) would make it throw.
        try {
            var s = (uri.schemeSpecificPart ?: uri.toString())
                .substringAfter("file:")   // drop an embedded file: scheme; no match => unchanged
                .substringBefore("!/")     // jar-internal entry suffix
                .substringBefore("#")      // securejar union index
            // On Windows this reads "/E:/..." — strip the slash before the drive.
            if (s.length > 2 && s[0] == '/' && s[2] == ':') s = s.substring(1)
            val f = File(s)
            if (f.isFile) return f
        } catch (t: Throwable) {
            Constants.LOG.warn("[mcp-repl] cannot resolve jar file from URI {}", uri, t)
        }
        return null
    }
}
