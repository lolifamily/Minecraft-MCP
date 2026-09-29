package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrScriptConfiguratorExtension
import org.jetbrains.kotlin.fir.builder.Context
import org.jetbrains.kotlin.fir.builder.FirScriptConfiguratorExtension
import org.jetbrains.kotlin.fir.builder.asReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.builder.FirFileBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirScriptBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildImport
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildScriptReceiverParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.builder.buildAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.builder.buildSimpleNamedReference
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirScriptSymbol
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirImplicitTypeRefImplWithoutSource
import org.jetbrains.kotlin.fir.types.impl.FirQualifierPartImpl
import org.jetbrains.kotlin.fir.types.impl.FirTypeArgumentListImpl
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Configures an `execute_code` snippet as a [FirScript] whose whole body is the body of one lambda, invoked on the
 * spot: `({ body })()`, the inside of a `run { }`. [SnippetParser] parses the text under function-body rules and
 * wraps all of it in a single script initializer, which the stock light-tree converter turns into one block of
 * local declarations and statements; this moves that block into the lambda. There, data flow runs from each
 * statement into the next and a `var` is a local — the two things a smart cast needs, and neither of which a
 * script's class-body top level gives it.
 *
 * Imports are added at the FIR level, never by prepending text: a source rewrite would shift every
 * diagnostic's line number off what the user typed.
 */
internal class McpScriptConfigurator(session: FirSession) : FirScriptConfiguratorExtension(session) {

    override fun accepts(sourceFile: KtSourceFile?, scriptSource: KtSourceElement): Boolean = sourceFile?.name?.endsWith(SCRIPT_EXT) == true

    override fun FirScriptBuilder.configure(sourceFile: KtSourceFile?, context: Context<*>) {
        val receiverIndex = receivers.size
        receivers.add(
            buildScriptReceiverParameter {
                // A user type ref: the TYPES phase resolves it exactly like a type the user wrote, and the
                // name is fully qualified so nothing about the snippet's own imports can shadow it.
                typeRef = buildUserTypeRef {
                    source = this@configure.source.fakeElement(KtFakeSourceElementKind.ScriptParameter.ImplicitReceiver(receiverIndex))
                    isMarkedNullable = false
                    FqName(SCRIPT_SCOPE).pathSegments().mapTo(qualifier) {
                        FirQualifierPartImpl(null, it, FirTypeArgumentListImpl(null))
                    }
                }
                isBaseClassReceiver = false
                symbol = FirReceiverParameterSymbol()
                moduleData = session.moduleData
                origin = FirDeclarationOrigin.ScriptCustomization.Parameter
                containingDeclarationSymbol = this@configure.symbol
            },
        )

        // Nothing else lives at the top level: every declaration is local to this one body, so there is no
        // member visibility to adjust either.
        val snippet = declarations.singleOrNull() as? FirAnonymousInitializer
            ?: error("the snippet did not arrive as SnippetParser's single script initializer")
        // Imports alone: nothing to run, and nothing to report.
        val snippetBody = snippet.body?.takeIf { it.statements.isNotEmpty() } ?: return
        val snippetSource = checkNotNull(snippet.source) { "the snippet's script initializer has no source" }
        declarations.clear()
        declarations.add(resultProperty(invokedOnTheSpot(snippetBody, snippetSource), snippetSource, context.packageFqName))
        // `resultPropertyName` only LABELS a property; building it is ours.
        resultPropertyName = RESULT_PROPERTY
    }

    /**
     * `({ body })()` — the body of a lambda, as in `run { }`, invoked on the spot. A lambda is a root for the data-flow
     * analysis: what decides whether a `var` a closure writes may still smart-cast. A property initializer is no
     * root, so it would let such a smart cast through; the anonymous initializer the body arrives in is one, but
     * fir2ir flattens it into the script's top level, where every local variable becomes a property. The backend
     * inlines a lambda invoked on the spot, so the body still runs straight in the constructor.
     */
    private fun invokedOnTheSpot(body: FirBlock, source: KtSourceElement): FirFunctionCall {
        val lambdaSymbol = FirAnonymousFunctionSymbol()
        return buildFunctionCall {
            this.source = source
            explicitReceiver = buildAnonymousFunctionExpression {
                this.source = source
                anonymousFunction = buildAnonymousFunction {
                    this.source = source
                    moduleData = session.moduleData
                    origin = FirDeclarationOrigin.Source
                    returnTypeRef = FirImplicitTypeRefImplWithoutSource
                    receiverParameter = source.asReceiverParameter(session.moduleData, lambdaSymbol)
                    symbol = lambdaSymbol
                    isLambda = true
                    hasExplicitParameterList = false
                    this.body = body
                }
            }
            // What the converter builds for `(expr)()` itself.
            calleeReference = buildSimpleNamedReference {
                this.source = source.fakeElement(KtFakeSourceElementKind.ImplicitInvokeCall)
                name = OperatorNameConventions.INVOKE
            }
        }
    }

    /**
     * The property holding the snippet's value: [value]'s, the body's last expression under the type it resolved
     * to. A body ending on a statement makes that Unit, and fir2ir then emits the invocation as a plain statement
     * and no property at all — so a snippet has a result exactly when it ends on a value, with nothing here
     * deciding which.
     *
     * Private keeps the type the value has: from Kotlin 2.4 a public property approximates a local class — every
     * class a snippet declares — to a supertype, `Any` for most. It is also the rule YieldType renders with, so a
     * single-tick result and a yielded one report alike. The field is read back reflectively either way.
     */
    private fun resultProperty(value: FirExpression, source: KtSourceElement, packageFqName: FqName): FirProperty = buildProperty {
        name = RESULT_PROPERTY
        symbol = FirRegularPropertySymbol(CallableId(packageFqName, name))
        this.source = source
        moduleData = session.moduleData
        origin = FirDeclarationOrigin.ScriptCustomization.ResultProperty
        initializer = value
        returnTypeRef = FirImplicitTypeRefImplWithoutSource
        getter = FirDefaultPropertyGetter(
            source = source.fakeElement(KtFakeSourceElementKind.DefaultAccessor.Getter),
            moduleData = session.moduleData,
            origin = FirDeclarationOrigin.ScriptCustomization.ResultProperty,
            propertyTypeRef = FirImplicitTypeRefImplWithoutSource,
            visibility = Visibilities.Private,
            propertySymbol = symbol,
            modality = Modality.FINAL,
        )
        status = FirDeclarationStatusImpl(Visibilities.Private, Modality.FINAL)
        isLocal = false
        isVar = false
    }

    override fun FirScriptBuilder.configureContainingFile(fileBuilder: FirFileBuilder) {
        for (fq in DEFAULT_IMPORTS) {
            fileBuilder.imports.add(
                buildImport {
                    importedFqName = FqName(fq)
                    isAllUnder = false
                },
            )
        }
    }
}

/** Nothing to add on the IR side; the extension point must exist for scripts to convert at all. */
internal class McpFir2IrScriptConfigurator(session: FirSession) : Fir2IrScriptConfiguratorExtension(session) {
    override fun IrScript.configure(script: FirScript, getIrScriptByFirSymbol: (FirScriptSymbol) -> IrScriptSymbol?) = Unit
}

internal class McpScriptRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::McpScriptConfigurator
        +::McpFir2IrScriptConfigurator
    }
}

/** What [McpScriptConfigurator.accepts] matches. It no longer picks the parser — [SnippetParser] parses every
 *  snippet itself — but the converter still offers each SCRIPT node to whichever configurator accepts its file. */
internal const val SCRIPT_EXT = ".mcpkts"

private const val SCRIPT_SCOPE = "org.js.lolifamily.minecraftmcp.repl.scope.ScriptScope"

/** Holds the snippet's value, when its body ends on one. Read back off the instantiated script object. */
internal val RESULT_PROPERTY: Name = Name.identifier("$\$mcpResult")

/** So snippets say bare `Patches` / `Probe`. */
private val DEFAULT_IMPORTS = listOf(
    "org.js.lolifamily.minecraftmcp.patch.Patches",
    "org.js.lolifamily.minecraftmcp.probe.Probe",
)
