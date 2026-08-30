package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.typeApproximator
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.js.lolifamily.minecraftmcp.Constants

// ============================================================================================
// Compile-time `typeName` for McpScope.yield, so a cross-tick step reports its type the way the snippet spelled
// it — mojmap, platform bounds and all — rather than the runtime namespace `typeOf<V>()` can only answer in.
//
// The string is computable only on the FIR side (the cone, and the renderer that spells a flexible bound `X!`)
// and deliverable only on the IR side (the call). Nothing maps one tree's node to the other's, but both inherit
// source offsets from the same parse — so the span joins them, as integers, never as text.
// ============================================================================================

/** `sequence { yield(x) }` resolves to the stdlib's `yield`, which carries no `typeName` to fill. */
private const val SCOPE = "org.js.lolifamily.minecraftmcp.repl.scope.McpScope"
private const val YIELD = "yield"
private const val TYPE_NAME = "typeName"

/** What the frontend itself applies when an inferred type has to become a declared one. */
private val APPROXIMATION = TypeApproximatorConfiguration.PublicDeclaration.SaveAnonymousTypes

/** Source span -> rendered type, for every [SCOPE] `yield` call in [fir]. Read after resolution: the type
 *  argument is inferred from the value, so before it there is nothing to render.
 *
 *  Approximated as a declared type would be. Inference hands back forms no declaration can carry —
 *  `listOf(1, "a")` resolves to `List<Comparable<*> & Serializable>` — and a single-tick result already reports
 *  the approximated one, its property type having gone through this same rule on the way to being declared. */
internal fun yieldTypes(fir: List<FirFile>, session: FirSession): Map<Pair<Int, Int>, String> {
    val out = HashMap<Pair<Int, Int>, String>()
    val visitor = object : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            if (element is FirFunctionCall && element.calleeReference.name.asString() == YIELD) {
                val symbol = (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
                val cone = (element.typeArguments.firstOrNull() as? FirTypeProjectionWithVariance)
                    ?.typeRef?.coneTypeOrNull
                val src = element.source
                if (cone != null && src != null &&
                    (symbol as? FirNamedFunctionSymbol)?.callableId?.classId?.asFqNameString() == SCOPE
                ) {
                    val declarable = session.typeApproximator.approximateToSuperType(cone, APPROXIMATION) ?: cone
                    out[src.startOffset to src.endOffset] = declarable.renderReadableWithFqNames()
                }
            }
            element.acceptChildren(this)
        }
    }
    fir.forEach { it.accept(visitor) }
    return out
}

/**
 * Writes [types] into each `yield` call's `typeName` argument.
 *
 * A call this misses keeps the argument absent, and `yield`'s default answers at runtime — correct, just in the
 * runtime namespace. That is why the shortfall is logged: a join that stops matching costs nothing visible.
 */
internal class YieldTypeExtension(private val types: Map<Pair<Int, Int>, String>) : IrGenerationExtension {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (types.isEmpty()) return
        var filled = 0
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrCall) fill(element)
                element.acceptChildren(this, null)
            }

            fun fill(call: IrCall) {
                val owner = call.symbol.owner
                if (owner.name.asString() != YIELD) return
                if (owner.parentClassOrNull?.kotlinFqName?.asString() != SCOPE) return
                val slot = owner.parameters.indexOfFirst { it.name.asString() == TYPE_NAME }
                val type = types[call.startOffset to call.endOffset] ?: return
                if (slot < 0) return
                call.arguments[slot] =
                    IrConstImpl.string(call.startOffset, call.endOffset, pluginContext.irBuiltIns.stringType, type)
                filled++
            }
        }
        moduleFragment.files.forEach { it.acceptChildren(visitor, null) }
        if (filled < types.size) {
            Constants.LOG.warn(
                "[mcp-plain] yield type: filled {} of {} call site(s); the rest report the runtime namespace",
                filled, types.size,
            )
        }
    }
}
