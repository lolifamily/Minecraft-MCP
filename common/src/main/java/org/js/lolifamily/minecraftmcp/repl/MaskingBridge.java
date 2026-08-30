package org.js.lolifamily.minecraftmcp.repl;

import org.js.lolifamily.minecraftmcp.exec.Capture;

import java.io.File;
import java.util.List;

/**
 * Type-safe gateway across the game-loader ↔ masking-loader boundary. Aggregates every operation that can
 * ONLY run on the masking loader — the Kotlin REPL host ({@code repl.impl.ReplHost}) and the remap-cache
 * builder ({@code repl.impl.RemapCacheBuilder}) — behind one interface, so the game loader never has to
 * reflect a masking-only class per call. Every method below forwards to one of those two backends; see
 * {@link ReplBridge} for the boundary rules and how the single implementation instance is obtained.
 *
 * <p>The {@code compile} handle stays {@link Object} because its real type
 * ({@code kotlin.script...ResultWithDiagnostics}) exists only on the masking loader; {@code execute} returns
 * {@code exec.Outcome} or {@code exec.IterEval}, which share no common supertype, so it is {@link Object} too.
 */
public interface MaskingBridge {

    // ---- REPL host — repl.impl.ReplHost ---------------------------------------------------------

    /**
     * Load the compiler working set.
     *
     * @return {@code true} on a training launch (no recorded working-set list yet)
     */
    boolean preload();

    /** Compile and run one dummy snippet, warming the whole eval path. */
    void warm();

    /**
     * Write the classes the masking loader has defined so far to {@code path}, plus a stamp of the kotlin
     * version they were recorded under. Training launches only; {@link #preload()} reads it back next launch.
     */
    void recordWorkingSet(String path);

    /**
     * Compile a snippet to an opaque handle. The handle is a masking-only type, so it is only ever passed
     * back to {@link #execute}.
     *
     * @param code        the snippet source
     * @param cpFiles     the compile classpath (game cp + mods + JiJ libs + mojmap symbols)
     * @param killIdField name of the target lane's scriptguard kill-id field to instrument the snippet against,
     *                    or {@code ""} for the off-tick lane (no watchdog, no guard instrumentation)
     * @param evalId      the value that field must equal for THIS eval's woven check to fire
     * @return an opaque compiled-script handle for {@link #execute}
     */
    Object compile(String code, List<File> cpFiles, String killIdField, int evalId);

    /**
     * Run a compiled handle.
     *
     * @param handle a handle from {@link #compile}
     * @param code   the original source, so compile diagnostics can echo the offending line
     * @param out    the sink the snippet's {@code println} writes to; the caller owns it, so a kill, cancel or
     *               timeout can still report what was printed before the eval ended
     * @return an {@code exec.Outcome}, or an {@code exec.IterEval} when the snippet yields a cross-tick iterator
     */
    Object execute(Object handle, String code, Capture out);

    /**
     * Build the Kotlin compiler state (overlay + {@code createState}).
     *
     * @param cpFiles the compile classpath to build the compiler against
     */
    void buildCompiler(List<File> cpFiles);

    // ---- Remap cache builder — repl.impl.RemapCacheBuilder --------------------------------------

    /**
     * Assemble the remap mappings and reverse-remap {@code runtimeMcUri} into a mojmap symbol jar.
     * See {@link ReplBridge#buildRemapArtifacts} for the argument contract.
     */
    void buildArtifacts(String clientTxt, String secondSource, String runtimeMcUri, String outMappings, String outSymbolsDir);

    /**
     * Forge Mixed-SRG analog of {@link #buildArtifacts}: assemble {@code srg_to_official} from the MCPConfig
     * {@code joinedTsrg} + Mojang mappings, then reverse-remap the Mixed-SRG runtime jar into a mojmap symbol jar.
     */
    void buildForgeArtifacts(String joinedTsrg, String clientTxt, String runtimeMcUri, String outMappings, String outSymbolsDir);
}
