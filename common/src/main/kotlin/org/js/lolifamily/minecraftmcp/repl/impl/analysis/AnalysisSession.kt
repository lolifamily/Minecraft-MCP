package org.js.lolifamily.minecraftmcp.repl.impl.analysis

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleStateModificationKind
import org.jetbrains.kotlin.analysis.api.platform.modification.publishModuleStateModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaDanglingFileModuleImpl
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.explicitModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.PomModelAspect
import org.jetbrains.kotlin.com.intellij.pom.PomTransaction
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.MaskingClassLoader
import org.js.lolifamily.minecraftmcp.repl.impl.McpScriptRegistrar
import org.js.lolifamily.minecraftmcp.repl.impl.ReplHost
import org.js.lolifamily.minecraftmcp.repl.impl.SCRIPT_EXT
import org.js.lolifamily.minecraftmcp.repl.impl.assembleCompileClasspath
import java.io.File
import java.nio.file.Path
import java.util.Objects

/**
 * The Analysis API over the *running* game: a standalone session whose one library module is the same widened
 * classpath [ReplHost] compiles snippets against. Ask it what a name means; [ReplHost] is what runs it.
 *
 * The two must see the identical classpath or an answer here can contradict what an eval does — hence the
 * widened list, not a freshly enumerated one: private / protected / `internal` members are callable in a
 * snippet only because the access-widen overlay opened them, and analysis has to be told the same story.
 *
 * Everything is lazy. Building the session indexes the whole classpath, so the first call is expensive and
 * every later one is free; drive it off-tick.
 */
object AnalysisSession {

    /** This class's own loader, exactly as [ReplHost] finds it — `repl.impl.*` is masking-owned. Null outside a
     *  masking launch (unit tests), where there is no parent kotlin to pin against either. */
    private val masking: MaskingClassLoader? by lazy {
        AnalysisSession::class.java.classLoader as? MaskingClassLoader
    }

    /** Handed out together: a [KtFile]'s context module must belong to the session that will analyze it, so
     *  neither half is meaningful without the other. */
    class Built(val session: StandaloneAnalysisAPISession, val context: KaSourceModule)

    /**
     * The session, built on first access and then kept — the game's classpath is fixed by the time one exists,
     * so there is nothing that could invalidate it. `lazy` retries rather than caching a failed build.
     *
     * @throws IllegalStateException if no eval has run yet — the classpath arrives from the game side.
     */
    val current: Built by lazy { build() }

    val project: Project get() = current.session.project

    /** What a snippet is analyzed against: an empty source module whose dependencies are the game's widened
     *  classpath and the running JDK. */
    val context: KaSourceModule get() = current.context

    /**
     * An in-memory snippet. Named [SCRIPT_EXT] for the same reason an eval's source is: any extension but `.kt`
     * is the whole of what puts the parser into script mode, which is what lets top-level statements sit beside
     * declarations.
     *
     * Its module is built here and pinned to the file rather than left to the platform, which would mint a fresh
     * one on *every* `analyze` — and each one takes a smart pointer on the file that nothing ever hands back.
     * An explicit module short-circuits that: the same instance is returned from then on, so a file costs one
     * module and one pointer no matter how many queries run against it.
     *
     * The file is non-physical, so the module is a *dangling* one, resolved against this session's [context].
     * `PREFER_SELF` because a snippet's own top-level declarations are part of what it means.
     */
    @OptIn(KaExperimentalApi::class)
    private fun newFile(code: String): KtFile {
        val file = KtPsiFactory(current.session.project).createFile("mcp_analysis$SCRIPT_EXT", code)
        file.explicitModule = EditorFileModule(file, current.context, KaDanglingFileResolutionMode.PREFER_SELF)
        return file
    }

    /** A fresh editing context over [code]. Hold one per caret, and [close][Editor.close] it when that caret
     *  is gone. */
    fun newEditor(code: String = ""): Editor = Editor(newFile(code))

    /** Where a query is being asked. [element] is what to hand `analyze`; filter candidates by [prefix], since
     *  the element's own name carries the placeholder rather than what was typed. */
    class Cursor(val file: KtFile, val element: KtElement, val prefix: String)

    /**
     * One editing context — a connection, a pane, whatever owns a caret.
     *
     * Its file is created once and rewritten per query, never replaced. A new file means a new dangling file
     * module, and every module `analyze` touches outlives [close] (see there). So a file per keystroke retains
     * a PSI tree per keystroke; one file per editor retains one, for as long as the editor lives — which is
     * why an editor is worth holding onto rather than making one per request.
     */
    class Editor internal constructor(private val file: KtFile) {

        /**
         * [code] with a caret at [offset], ready to analyze. A placeholder identifier is spliced in at the caret
         * first, because completion runs on source that does not parse: `foo.` has no selector, so the parser
         * yields no dotted expression and the receiver cannot be reached. IntelliJ splices one in for the same
         * reason, and reusing its name means anything that special-cases it still behaves.
         */
        fun cursor(code: String, offset: Int): Cursor {
            require(offset in 0..code.length) { "offset $offset outside 0..${code.length}" }
            rewrite(code.substring(0, offset) + PLACEHOLDER + code.substring(offset))
            val leaf = file.findElementAt(offset)
            val element = leaf?.let { PsiTreeUtil.getParentOfType(it, KtElement::class.java, false) } ?: file
            // Where the identifier begins is the lexer's answer, so cut the leaf's own text at the caret rather
            // than deciding here which characters an identifier may contain.
            val prefix = leaf?.run { text.substring(0, offset - textRange.startOffset) }.orEmpty()
            return Cursor(file, element, prefix)
        }

        /**
         * Swaps the file's whole tree for one parsed from [text]. The file object survives, so its module — and
         * with it the analysis cache key — stays the one this editor already had. The modification stamp moves,
         * which is what tells the session on that key to be rebuilt instead of reused stale.
         *
         * The parsed file is a throwaway. It is never analyzed, so it never becomes a module of its own.
         */
        private fun rewrite(text: String) {
            val parsed = KtPsiFactory(file.project).createFile(file.name, text)
            ApplicationManager.getApplication().runWriteAction {
                file.node.replaceAllChildrenToChildrenOf(parsed.node)
            }
        }

        /**
         * Hands this editor's module back. An editor is finished with after this.
         *
         * It does not free the file. `analyze` also registers every module in caches with no removal API — the
         * standalone `KotlinModuleDependentsProvider`'s soft-keyed map, and at least one more — so the PSI tree
         * stays reachable until the heap genuinely fills, at which point it is reclaimed in full. A soft cache,
         * not a leak — the bound on it is reusing editors.
         */
        fun close() = release(file)

        /**
         * Gives [file]'s module back to the analysis caches. Only that module goes — a module-level removal
         * evicts the module's own session and any dangling session contextual to it, and leaves library
         * sessions, and with them the classpath index, alone.
         *
         * Published on the analysis message bus, which reaches every cache keyed on the module. Handing the
         * event straight to the FIR session cache instead reaches only that one, and whatever else is holding
         * the module goes on holding it — and with it the file's whole PSI tree.
         */
        @OptIn(KaPlatformInterface::class)
        private fun release(file: KtFile) {
            val module = KotlinProjectStructureProvider.getModule(file.project, file, useSiteModule = null)
            ApplicationManager.getApplication().runWriteAction {
                module.publishModuleStateModificationEvent(KotlinModuleStateModificationKind.REMOVAL)
            }
        }
    }

    private fun build(): Built {
        val raw = ReplHost.lastClasspath
            ?: error("no classpath yet — the game side hands one over on the first eval, so run one first")
        // Same three arguments PlainEngine.warmUp passes, so widenClasspath's stamp cache hits what the
        // compiler already built instead of writing a second, differently-pinned overlay.
        val pinned = LanguageVersion.fromVersionString(masking?.parentApiVersion())
        val widened = assembleCompileClasspath(raw, AnalysisSession::class.java.classLoader, pinned)

        val disposable = Disposer.newDisposable("mcp-analysis")
        val t0 = System.nanoTime()
        lateinit var context: KaSourceModule
        val session = buildStandaloneAnalysisAPISession(disposable) {
            registerProjectService(PomModel::class.java, TransactionOnlyPomModel())
            registerScriptExtensions(project, disposable)
            buildKtModuleProvider {
                val jvm = JvmPlatforms.defaultJvmPlatform
                platform = jvm
                // A module of its own, not classpath: standalone has no configureJdkClasspathRoots(), and
                // without this nothing in java.* resolves. isJre only picks JDK 8's jre/lib layout; 9+ finds
                // the same jrt modules either way.
                val jdk = buildKtSdkModule {
                    platform = jvm
                    libraryName = "jdk"
                    addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
                }
                val (roots, opened) = splitRoots(widened, coreApplicationEnvironment.jarFileSystem)
                val runtime = buildKtLibraryModule {
                    platform = jvm
                    libraryName = "mcp-runtime"
                    addBinaryRoots(roots)
                    addBinaryVirtualFiles(opened)
                    contentScope = rootScope(project, roots, opened, coreApplicationEnvironment)
                }
                // Dependencies hang off a module that DEPENDS on them, which for a library is nothing — so the
                // context is an empty source module, the shape standalone itself builds for a script file.
                context = addModule(
                    buildKtSourceModule {
                        platform = jvm
                        moduleName = "mcp-analysis"
                        addRegularDependency(runtime)
                        addRegularDependency(jdk)
                        // Friend-all, matching PlainEngine's FRIEND_PATHS — analysis must allow the `internal`
                        // an eval can reach.
                        addFriendDependency(runtime)
                    },
                )
            }
        }
        Constants.LOG.info(
            "[mcp-analysis] session built over {} classpath roots in {} ms",
            widened.size, (System.nanoTime() - t0) / 1_000_000,
        )
        return Built(session, context)
    }

    /**
     * The library module's content scope, built here rather than left to the module builder: its default routes
     * through `VirtualFileUtil.toNioPathOrNull`, and intellij-core ships a same-named facade without that method
     * — it survives only in the `@Metadata` proto, so the call finds nothing and throws `NoSuchMethodError` the
     * first time a module carries binary virtual files.
     *
     * Containment by walking a file's parents up to a root, which is where the Analysis API landed later anyway
     * — once `StandaloneLibraryScopeConstructionMode.ParentTraversal` reaches this version, this is one property.
     */
    private fun rootScope(
        project: Project,
        roots: List<Path>,
        opened: List<VirtualFile>,
        environment: CoreApplicationEnvironment,
    ): GlobalSearchScope {
        val all = (StandaloneProjectFactory.getVirtualFilesForLibraryRoots(roots, environment) + opened).toHashSet()
        return object : GlobalSearchScope(project) {
            override fun contains(file: VirtualFile): Boolean {
                var current: VirtualFile? = file
                while (current != null) {
                    if (current in all) return true
                    current = current.parent
                }
                return false
            }

            override fun isSearchInModuleContent(aModule: Module): Boolean = false

            override fun isSearchInLibraries(): Boolean = true
        }
    }

    /**
     * Archives opened here, everything else left as a plain root. A binary root is read back by dispatching on
     * `endsWith("jar")`, so an archive named otherwise — nested-jar extraction leaves `*.jar.tmp` and friends —
     * would resolve as one opaque file with none of its classes visible. An eval is unaffected: the compiler
     * indexes the same list its own way.
     *
     * Opening every archive rather than only the odd-named ones costs nothing — both halves are concatenated
     * into one list downstream. What does matter is identity: scope membership is a set lookup, and the jar FS
     * caches per path spelling, so a fresh FS or a system-dependent name yields a second, unfindable instance.
     */
    private fun splitRoots(widened: List<File>, jarFs: VirtualFileSystem): Pair<List<Path>, List<VirtualFile>> {
        val roots = ArrayList<Path>()
        val opened = ArrayList<VirtualFile>()
        for (f in widened) {
            val path = FileUtil.toSystemIndependentName(f.absolutePath)
            val vf = runCatching { jarFs.findFileByPath(path + JAR_SEPARATOR) }.getOrNull()
            if (vf != null) opened.add(vf) else roots.add(f.toPath())
        }
        return roots to opened
    }

    /**
     * Teach the session the same script shape the compiler uses, so a snippet's implicit `ScriptScope` receiver
     * and its bare `Patches` / `Probe` imports resolve here too. [McpScriptRegistrar] is reused as-is: the
     * relocated Analysis API and the embedded compiler share one `FirExtensionRegistrar` class.
     *
     * LLFirSessionFactory reads this extension point with no null guard, so the point has to exist before the
     * first session is configured — standalone does not register this one itself. Scoped to [owner], which is
     * the session's own disposable: the unscoped overload is deprecated, and rightly so.
     */
    private fun registerScriptExtensions(project: Project, owner: Disposable) {
        val area = project.extensionArea
        if (!area.hasExtensionPoint(FirExtensionRegistrarAdapter.name)) {
            CoreApplicationEnvironment.registerExtensionPoint(
                area, FirExtensionRegistrarAdapter.name, FirExtensionRegistrarAdapter::class.java,
            )
        }
        area.getExtensionPoint<FirExtensionRegistrarAdapter>(FirExtensionRegistrarAdapter.name)
            .registerExtension(McpScriptRegistrar(), owner)
    }
}

/** Jar-url separator: archive path before it, entry path after, so a trailing one asks for the archive's root.
 *  Inlined rather than read off `URLUtil` — two divergent copies of that class are on the classpath and only
 *  one declares the constant, so which node compiles decides whether the reference resolves. */
private const val JAR_SEPARATOR = "!/"

/** Spliced in at the caret so unfinished source parses. Any identifier real code would never write does the
 *  job; this is IntelliJ's own spelling, so whatever special-cases it still recognizes ours. Copied rather
 *  than referenced: `CompletionUtilCore` lives in the IDE's completion infrastructure, which neither the
 *  embedded compiler nor the Analysis API brings along. */
private const val PLACEHOLDER = "IntellijIdeaRulezzz"

/**
 * A dangling file module that holds its file outright.
 *
 * The stock [KaDanglingFileModuleImpl] takes a smart pointer on the file instead, which the pointer manager
 * registers and keeps; the module offers no way to hand it back, and its own reference is `private`. So the
 * file's PSI tree outlives anything that releases the module, which is what [AnalysisSession.Editor.close]
 * would otherwise be unable to reclaim. Here the file is an ordinary field, and goes when the module does.
 *
 * Writing one is the documented alternative — the stock implementation says as much. The optimization given up
 * is one shortcut in session building: for its own class the factory merges the context session's symbol
 * providers straight in, and for anything else it aggregates and compares both dependency sets first. Same
 * result, a little more work per session. Everything below mirrors the stock behavior, which is to say it
 * defers to the context module throughout.
 */
@OptIn(KaPlatformInterface::class)
private class EditorFileModule(
    private val snippet: KtFile,
    override val contextModule: KaModule,
    override val resolutionMode: KaDanglingFileResolutionMode,
) : KaModuleBase(),
    KaDanglingFileModule {

    /** Throwing once invalid is the interface's contract, not a choice. */
    override val files: List<KtFile>
        get() = if (snippet.isValid) listOf(snippet) else error("Dangling file module is invalid")

    override val isCodeFragment: Boolean get() = snippet is KtCodeFragment
    override val isValid: Boolean get() = snippet.isValid

    override val project: Project get() = contextModule.project
    override val targetPlatform: TargetPlatform get() = contextModule.targetPlatform

    /** Through the view provider, not `PsiFile.virtualFile`: the latter is null for an in-memory file, and the
     *  scope still has to accept one. */
    override val baseContentScope: GlobalSearchScope
        get() = GlobalSearchScope.filesScope(project, listOf(snippet.viewProvider.virtualFile))

    override val directRegularDependencies: List<KaModule> get() = contextModule.directRegularDependencies
    override val directDependsOnDependencies: List<KaModule> get() = contextModule.directDependsOnDependencies
    override val transitiveDependsOnDependencies: List<KaModule> get() = contextModule.transitiveDependsOnDependencies

    override val directFriendDependencies: List<KaModule>
        get() = listOf(contextModule) + contextModule.directFriendDependencies

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditorFileModule) return false
        return snippet == other.snippet && contextModule == other.contextModule && resolutionMode == other.resolutionMode
    }

    override fun hashCode(): Int = Objects.hash(snippet, contextModule, resolutionMode)

    override fun toString(): String = snippet.name
}

/**
 * The one service a core environment is missing before PSI can be written. Every mutation routes through
 * `ChangeUtil.prepareAndRunChangeAction` → `PomManager.getModel(project).runTransaction(...)`, and a core project
 * has none: the real `PomModelImpl` lives in `platform/core-impl`, which the embedded compiler does not carry,
 * and nothing in the compiler registers one because the compiler only ever reads PSI.
 *
 * Running the transaction and stopping there is the point, not a shortcut. What the real implementation adds is
 * PSI change event dispatch and a reparse, and neither is wanted: the reparse is a known cost under repeated
 * edits, and analysis learns that a file changed by polling its modification stamp — which the tree surgery
 * bumps by itself — rather than by listening.
 */
private class TransactionOnlyPomModel :
    UserDataHolderBase(),
    PomModel {
    private val tree = TreeAspect()

    @Suppress("UNCHECKED_CAST")
    override fun <T : PomModelAspect> getModelAspect(aspect: Class<T>): T? = if (aspect == TreeAspect::class.java) tree as T else null

    override fun runTransaction(transaction: PomTransaction) = transaction.run()
}
