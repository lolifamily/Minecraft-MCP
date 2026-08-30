package org.js.lolifamily.minecraftmcp.repl.impl

import org.js.lolifamily.minecraftmcp.repl.EvalRender
import kotlin.script.experimental.api.ScriptDiagnostic

/*
 * The compiler-diagnostic format of an eval's TEXT result. Pure functions over their arguments — no REPL
 * state — so they are read and changed without touching the host.
 */

/** Diagnostics rendered in full; the rest are left to the count line. Each one costs a source line and a caret
 *  line of its own, so it is the count that bounds this report. */
private const val MAX_DIAGNOSTICS = 8

/**
 * Render diagnostics in the standard compiler format (`source:line:col: severity: message`, the offending
 * source line, then a caret under the column) — that prefix is what editors make clickable. Locations are
 * relative to the source handed to the compiler, which is the original snippet, so line N maps straight back
 * to what was written.
 */
internal fun renderFailure(reports: List<ScriptDiagnostic>, code: String): String {
    val sourceName = "mcp-eval.kts"
    val srcLines = code.split("\n")
    val shown = reports.filter { it.severity >= ScriptDiagnostic.Severity.WARNING || it.exception != null }
    // Severity both picks what to drop and leads the report: in a list cut this short, a run of warnings must
    // not bury the one error that actually failed the eval. The sort is stable, so within one severity the
    // compiler's source order survives untouched.
    val rendered = shown.sortedByDescending { it.severity }.take(MAX_DIAGNOSTICS)
    val sb = StringBuilder()
    // Named access, not destructuring — the IDE offers to destructure `r` here; don't take it. ScriptDiagnostic
    // is a data class from kotlin.script.experimental.api, so componentN binds by POSITION: an upstream field
    // insert or reorder would still compile and silently render the wrong values. We want 4 of its 6 fields.
    for (@Suppress("DestructuringDeclaration") r in rendered) {
        val sev = when (r.severity) {
            ScriptDiagnostic.Severity.FATAL, ScriptDiagnostic.Severity.ERROR -> "error"
            ScriptDiagnostic.Severity.WARNING -> "warning"
            else -> "info"
        }
        val start = r.location?.start
        if (start != null) {
            sb.append(sourceName).append(':').append(start.line).append(':').append(start.col)
                .append(": ").append(sev).append(": ").append(r.message).append('\n')
            srcLines.getOrNull(start.line - 1)?.let { line ->
                sb.append(line).append('\n')
                    .append(" ".repeat((start.col - 1).coerceAtLeast(0))).append("^\n")
            }
        } else {
            sb.append(sourceName).append(": ").append(sev).append(": ").append(r.message).append('\n')
        }
        r.exception?.let { sb.append(EvalRender.stack(it)) }
    }
    return sb.append(renderTail(shown, rendered.size)).toString().trimEnd()
}

/** What did not fit, then the totals — counted over everything the compiler reported, not over what was shown. */
private fun renderTail(shown: List<ScriptDiagnostic>, renderedCount: Int): String {
    val sb = StringBuilder()
    val omitted = shown.size - renderedCount
    if (omitted > 0) sb.append("... ").append(omitted).append(" more diagnostic(s) not shown\n")
    val errors = shown.count { it.severity >= ScriptDiagnostic.Severity.ERROR }
    val warnings = shown.count { it.severity == ScriptDiagnostic.Severity.WARNING }
    return sb.append(
        listOfNotNull(
            if (errors > 0) "$errors error${if (errors > 1) "s" else ""}" else null,
            if (warnings > 0) "$warnings warning${if (warnings > 1) "s" else ""}" else null,
        ).joinToString(", "),
    ).toString()
}
