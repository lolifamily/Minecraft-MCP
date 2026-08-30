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
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.builder.FirFileBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirScriptBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildImport
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildScriptReceiverParameter
import org.jetbrains.kotlin.fir.declarations.destructuringDeclarationContainerVariable
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.isDestructuringDeclarationContainerVariable
import org.jetbrains.kotlin.fir.expressions.FirLazyExpression
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirScriptSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirImplicitTypeRefImplWithoutSource
import org.jetbrains.kotlin.fir.types.impl.FirQualifierPartImpl
import org.jetbrains.kotlin.fir.types.impl.FirTypeArgumentListImpl
import org.jetbrains.kotlin.ir.declarations.IrScript
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.scripting.compiler.plugin.services.findExpressionForResultProperty

/**
 * Configures an `execute_code` snippet as a [FirScript]. `KotlinLightParser` picks script mode purely
 * on the source file's extension not being `.kt`, so naming the in-memory source [SCRIPT_EXT] is the
 * whole switch — and it is what lets a snippet mix top-level statements with `object` / `class`
 * declarations, neither of which survives being wrapped in a function.
 *
 * Imports are added at the FIR level, never by prepending text: a source rewrite would shift every
 * diagnostic's line number off what the user typed.
 */
internal class McpScriptConfigurator(session: FirSession) : FirScriptConfiguratorExtension(session) {

    override fun accepts(sourceFile: KtSourceFile?, scriptSource: KtSourceElement): Boolean = sourceFile?.name?.endsWith(SCRIPT_EXT) == true

    override fun FirScriptBuilder.configure(sourceFile: KtSourceFile?, context: Context<*>) {
        receivers.add(
            buildScriptReceiverParameter {
                // A user type ref: the TYPES phase resolves it exactly like a type the user wrote, and the
                // name is fully qualified so nothing about the snippet's own imports can shadow it.
                typeRef = buildUserTypeRef {
                    source = this@configure.source.fakeElement(KtFakeSourceElementKind.ScriptParameter)
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

        // Top-level snippet declarations default to public, and a public declaration may not name an `internal`
        // type. The compiler's REPL branch marks them Local for exactly this reason; light-tree has no REPL
        // branch (KT-77583), so a snippet arrives as a FirScript instead. Internal is enough here — a snippet
        // is its own module — and it stays public on the JVM, so nothing needs an accessor it did not need
        // before. Status resolve keeps a visibility that was set explicitly. Ahead of the early return below:
        // a snippet with no trailing expression has these declarations too.
        for (declaration in declarations) {
            if (declaration !is FirProperty && declaration !is FirNamedFunction) continue
            if (declaration is FirProperty && declaration.isFromDestructuring()) continue
            val member = declaration as FirMemberDeclaration
            val visibility = member.status.visibility
            if (visibility != Visibilities.Public && visibility != Visibilities.Unknown) continue
            member.replaceStatus(member.status.copy(visibility = Visibilities.Internal))
        }

        // `resultPropertyName` only LABELS a property; building it is ours. The snippet's trailing expression
        // arrives as the last anonymous initializer, so it is swapped for a property initialized by it —
        // which is what gives the eval a value to report and a type to report it under.
        val (lastBlock, lastExpression) = declarations.findExpressionForResultProperty() ?: return
        declarations.removeLast()
        @OptIn(UnresolvedExpressionTypeAccess::class)
        val resultTypeRef = lastExpression.takeUnless { it is FirLazyExpression }?.coneTypeOrNull?.toFirResolvedTypeRef()
            ?: FirImplicitTypeRefImplWithoutSource
        declarations.add(
            buildProperty {
                name = RESULT_PROPERTY
                symbol = FirRegularPropertySymbol(CallableId(context.packageFqName, name))
                source = lastBlock.source
                moduleData = session.moduleData
                origin = FirDeclarationOrigin.ScriptCustomization.ResultProperty
                initializer = lastExpression
                returnTypeRef = resultTypeRef
                getter = FirDefaultPropertyGetter(
                    source = lastBlock.source?.fakeElement(KtFakeSourceElementKind.DefaultAccessor),
                    moduleData = session.moduleData,
                    origin = FirDeclarationOrigin.ScriptCustomization.ResultProperty,
                    propertyTypeRef = resultTypeRef,
                    visibility = Visibilities.Public,
                    propertySymbol = symbol,
                    modality = Modality.FINAL,
                )
                status = FirDeclarationStatusImpl(Visibilities.Public, Modality.FINAL)
                isLocal = false
                isVar = false
            },
        )
        resultPropertyName = RESULT_PROPERTY
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

/** A destructuring is built as a container variable plus entries, accessors made to match, and it rejects
 *  visibility modifiers outright — so the whole shape is left as the builder made it. */
private fun FirProperty.isFromDestructuring(): Boolean =
    destructuringDeclarationContainerVariable != null || isDestructuringDeclarationContainerVariable == true

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

/** Any extension but `.kt` puts `KotlinLightParser` into script mode; this one is ours so
 *  [McpScriptConfigurator.accepts] can match. */
internal const val SCRIPT_EXT = ".mcpkts"

private const val SCRIPT_SCOPE = "org.js.lolifamily.minecraftmcp.repl.scope.ScriptScope"

/** Holds the snippet's last expression. Read back off the instantiated script object. */
internal val RESULT_PROPERTY: Name = Name.identifier("$\$mcpResult")

/** So snippets say bare `Patches` / `Probe`. */
private val DEFAULT_IMPORTS = listOf(
    "org.js.lolifamily.minecraftmcp.patch.Patches",
    "org.js.lolifamily.minecraftmcp.probe.Probe",
)
