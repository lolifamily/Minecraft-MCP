package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.lang.PsiBuilderFactory
import org.jetbrains.kotlin.com.intellij.lang.impl.PsiBuilderImpl
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.com.intellij.psi.TokenType
import org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.parsing.KotlinExpressionParsing
import org.jetbrains.kotlin.parsing.KotlinLightParser
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.parsing.KotlinParsing
import org.jetbrains.kotlin.parsing.PRECEDING_ALL_BINDER
import org.jetbrains.kotlin.parsing.SemanticWhitespaceAwarePsiBuilder
import org.jetbrains.kotlin.parsing.SemanticWhitespaceAwarePsiBuilderImpl
import org.jetbrains.kotlin.parsing.TRAILING_ALL_BINDER
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Parses a snippet as what `execute_code` promises it is: imports, then a FUNCTION BODY.
 *
 * `KotlinParsing.parseScript` parses the same text under class-body rules — a property may carry a getter or a
 * setter, statements may share a line — and the analysis then treats every top-level declaration as a member, so
 * no smart cast crosses from one statement to the next and a top-level `var` never smart-casts at all. The one
 * entry that parses statements under function-body rules, `parseBlockCodeFragment`, belongs to the debugger and
 * rejects imports written into the text: its code fragments take them out of band. So this is `parseScript` with
 * its single switch flipped, and one marker added:
 *
 * ```
 * KT_FILE
 *  ├─ IMPORT_LIST …
 *  └─ SCRIPT
 *     └─ BLOCK
 *        └─ SCRIPT_INITIALIZER      ← ONE, around the whole body
 *           ├─ PROPERTY             ← parsed as a local: no accessors, same-line statements need a `;`
 *           └─ …
 * ```
 *
 * That one marker is what lets the stock light-tree converter do all the rest: it turns the initializer into a
 * block the way it turns any function body, and every "where am I" check it makes reads the statements as local.
 *
 * Two members are out of reach: `createForTopLevelNonLazy` is package-private — the only way to a non-lazy parser,
 * the lazy one collapsing function bodies into nodes light tree never expands — and `parsePreamble` is `private`,
 * which no compiler flag or package placement can open. Both are resolved once, by the constructor, and
 * [PlainEngine] holds the instance for the life of the process. The constructor rather than a static initializer:
 * a lookup that fails there throws out of warm-up with its real cause every time it is retried, where a failed
 * class initializer is reported on JDK 17 as a bare NoClassDefFoundError from its second use on. The compiler is
 * our own pinned copy, so the two can only go missing when this build moves Kotlin — and then warm-up fails on
 * its first line, naming the member.
 *
 * `invoke`, not `invokeExact`: Kotlin gives a polymorphic call its return type only from an `as` applied directly
 * to it, and `parsePreamble` returns `void`, which no Kotlin expression spells. Once per eval, so the adaptation
 * `invoke` does costs nothing that matters.
 */
internal class SnippetParser {
    private val newParsing: MethodHandle
    private val parsePreamble: MethodHandle

    init {
        val lookup = MethodHandles.privateLookupIn(KotlinParsing::class.java, MethodHandles.lookup())
        newParsing = lookup.findStatic(
            KotlinParsing::class.java,
            "createForTopLevelNonLazy",
            MethodType.methodType(KotlinParsing::class.java, SemanticWhitespaceAwarePsiBuilder::class.java),
        )
        parsePreamble = lookup.findVirtual(KotlinParsing::class.java, "parsePreamble", MethodType.methodType(Void.TYPE))
    }

    /** Parse [code] into a light tree, reporting syntax errors to [errors] as `KotlinLightParser` would. */
    fun parse(code: CharSequence, errors: KotlinLightParser.LightTreeParsingErrorListener): FlyweightCapableTreeStructure<LighterASTNode> {
        val psi = PsiBuilderFactory.getInstance().createBuilder(KotlinParserDefinition(), KotlinLexer(), code)
        val builder = SemanticWhitespaceAwarePsiBuilderImpl(psi)
        val parsing = newParsing.invoke(builder) as KotlinParsing

        val file = builder.mark()
        parsePreamble.invoke(parsing)
        val script = builder.mark()
        val block = builder.mark()
        val body = builder.mark()
        // parseScript passes true here. It is the whole difference between a script's top level and a function body.
        KotlinExpressionParsing(builder, parsing, false).parseStatements(false)
        body.done(KtNodeTypes.SCRIPT_INITIALIZER)
        // parseScript's own tail: whatever the statements stopped at — a stray `}` — is an error, not the end.
        while (!builder.eof()) {
            val stray = builder.mark()
            builder.advanceLexer()
            stray.error("Unexpected symbol")
        }
        block.done(KtNodeTypes.BLOCK)
        block.setCustomEdgeTokenBinders(PRECEDING_ALL_BINDER, TRAILING_ALL_BINDER)
        script.done(KtNodeTypes.SCRIPT)
        script.setCustomEdgeTokenBinders(PRECEDING_ALL_BINDER, TRAILING_ALL_BINDER)
        file.done(KtNodeTypes.KT_FILE)

        val tree = psi.lightTree
        reportErrors(tree, tree.root, errors)
        return tree
    }
}

/** `KotlinLightParser.reportErrors`, which is private: a syntax error sits in the tree as an error node until
 *  something walks the tree and reports it. */
private fun reportErrors(
    tree: FlyweightCapableTreeStructure<LighterASTNode>,
    node: LighterASTNode,
    errors: KotlinLightParser.LightTreeParsingErrorListener,
) {
    val children = Ref<Array<LighterASTNode?>>()
    val count = tree.getChildren(node, children)
    for (i in 0 until count) {
        val child = children.get()[i] ?: continue
        if (child.tokenType == TokenType.ERROR_ELEMENT) {
            errors.onError(child.startOffset, child.endOffset, PsiBuilderImpl.getErrorMessage(child))
        }
        reportErrors(tree, child, errors)
    }
}
