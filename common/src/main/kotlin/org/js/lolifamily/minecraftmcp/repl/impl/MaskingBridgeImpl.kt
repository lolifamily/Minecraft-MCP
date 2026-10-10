package org.js.lolifamily.minecraftmcp.repl.impl

import org.js.lolifamily.minecraftmcp.exec.Capture
import org.js.lolifamily.minecraftmcp.exec.GuardLane
import org.js.lolifamily.minecraftmcp.repl.MaskingBridge
import java.io.File
import java.util.function.BooleanSupplier

/**
 * Masking-owned [MaskingBridge]: forwards REPL calls to [ReplHost] and remap-cache calls to
 * [RemapCacheBuilder], both `object` singletons on this same loader.
 */
class MaskingBridgeImpl : MaskingBridge {

    override fun preload(): Boolean = ReplHost.preload()
    override fun warm() { ReplHost.warm() }
    override fun recordWorkingSet(path: String) { ReplHost.recordWorkingSet(path) }
    override fun compile(code: String, cpFiles: List<File>, guardLane: GuardLane?, evalId: Int, abandoned: BooleanSupplier): Any =
        ReplHost.compile(code, cpFiles, guardLane, evalId, abandoned)
    override fun execute(handle: Any, code: String, out: Capture): Any = ReplHost.execute(handle as PlainEngine.Compiled, code, out)
    override fun buildCompiler(cpFiles: List<File>) { ReplHost.buildCompiler(cpFiles) }

    override fun assembleMappings(clientTxt: String, secondSource: String, outMappings: String) {
        RemapCacheBuilder.assembleMappings(clientTxt, secondSource, outMappings)
    }

    override fun buildSymbols(runtimeMcUri: String, mappings: String, outSymbolsDir: String) {
        RemapCacheBuilder.buildSymbols(runtimeMcUri, mappings, outSymbolsDir)
    }
}
