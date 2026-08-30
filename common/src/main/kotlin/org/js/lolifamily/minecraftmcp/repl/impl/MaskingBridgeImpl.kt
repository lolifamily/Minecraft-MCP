package org.js.lolifamily.minecraftmcp.repl.impl

import org.js.lolifamily.minecraftmcp.exec.Capture
import org.js.lolifamily.minecraftmcp.repl.MaskingBridge
import java.io.File

/**
 * Masking-owned [MaskingBridge]: forwards REPL calls to [ReplHost] and remap-cache calls to
 * [RemapCacheBuilder], both `object` singletons on this same loader.
 */
class MaskingBridgeImpl : MaskingBridge {

    override fun preload(): Boolean = ReplHost.preload()
    override fun warm() { ReplHost.warm() }
    override fun recordWorkingSet(path: String) { ReplHost.recordWorkingSet(path) }
    override fun compile(code: String, cpFiles: List<File>, killIdField: String, evalId: Int): Any =
        ReplHost.compile(code, cpFiles, killIdField, evalId)
    override fun execute(handle: Any, code: String, out: Capture): Any = ReplHost.execute(handle as PlainEngine.Compiled, code, out)
    override fun buildCompiler(cpFiles: List<File>) { ReplHost.buildCompiler(cpFiles) }

    override fun buildArtifacts(clientTxt: String, secondSource: String, runtimeMcUri: String, outMappings: String, outSymbolsDir: String) {
        RemapCacheBuilder.buildArtifacts(clientTxt, secondSource, runtimeMcUri, outMappings, outSymbolsDir)
    }

    override fun buildForgeArtifacts(
        joinedTsrg: String,
        clientTxt: String,
        runtimeMcUri: String,
        outMappings: String,
        outSymbolsDir: String,
    ) {
        RemapCacheBuilder.buildForgeArtifacts(joinedTsrg, clientTxt, runtimeMcUri, outMappings, outSymbolsDir)
    }
}
