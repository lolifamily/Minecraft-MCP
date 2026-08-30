package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.KtInMemoryTextSourceFile
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.cli.common.fir.reportToMessageCollector
import org.jetbrains.kotlin.cli.common.renderDiagnosticInternalName
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.PsiBasedProjectFileSearchScope
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.createLibraryListForJvm
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.convertToIrAndActualizeForJvm
import org.jetbrains.kotlin.cli.jvm.compiler.toVfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.cli.jvm.configureJdkHomeFromSystemProperty
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceModuleData
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendClassResolver
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendExtension
import org.jetbrains.kotlin.fir.backend.jvm.JvmFir2IrExtensions
import org.jetbrains.kotlin.fir.backend.utils.extractFirDeclarations
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirViaLightTree
import org.jetbrains.kotlin.fir.pipeline.runCheckers
import org.jetbrains.kotlin.fir.pipeline.runResolution
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.scripting.compiler.plugin.extensions.ScriptLoweringExtension
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.extractResultFields
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.scope.ScriptScope
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Compiles a snippet by driving the compiler directly, holding the expensive state ourselves.
 *
 * The only thing that makes this fast is WHERE the session boundary sits: the project environment, the
 * [FirJvmSessionFactory.Context] and the shared + library sessions (the classpath FIR index, seconds on a
 * modpack) are built once in [warmUp] and reused, while each eval builds only a source session — provider
 * wiring, not a classpath scan. Every CLI-level entry point (`K2JVMCompiler`, `prepareJvmSessions`,
 * `KotlinToJVMBytecodeCompiler`) rebuilds all of it per call, which is why none of them appear here.
 */
internal object PlainEngine {

    /** Stateless factories, so one instance serves every session — which is also what the CLI hands around. */
    private val registrars = listOf<FirExtensionRegistrar>(McpScriptRegistrar())

    /** Everything reused across evals. Built once; the game classpath does not change under us. The
     *  [KotlinCoreEnvironment] is deliberately absent: nothing reads it, and the Disposer it registered with
     *  keeps it (and its project, which [projectEnvironment] wraps) alive for the life of the process. */
    private class Warm(
        val configuration: CompilerConfiguration,
        val projectEnvironment: VfsBasedProjectEnvironment,
        val sessionContext: FirJvmSessionFactory.Context,
        val libraryList: DependencyListForCliModule,
        val collector: ScriptDiagnosticsMessageCollector,
        val classpath: List<File>,
    )

    @Volatile
    private var warm: Warm? = null

    /** The FIR caches under a CLI session are single-threaded, so one compile at a time. */
    private val compileLock = ReentrantLock()

    /**
     * A compiled snippet, or why it did not compile. [Ok.resultField] comes from the lowering's
     * `scriptResultFieldDataAttr`; [Ok.resultType] is what [resultTypeName] renders off the frontend cone. Both
     * are null when the snippet ended on a statement rather than a value.
     */
    sealed interface Compiled {
        class Ok(
            val classes: Map<String, ByteArray>,
            val mainClass: String,
            val resultField: String?,
            val resultType: String?,
            val resultValueClass: String?,
        ) : Compiled

        class Failed(val reports: List<ScriptDiagnostic>) : Compiled
    }

    /** Run a compiled snippet and hand back its result: the script body IS the class constructor, so
     *  constructing it runs it. */
    fun execute(ok: Compiled.Ok, scope: ScriptScope, parentLoader: ClassLoader): Any? {
        val cls = compiledScriptClassLoader(parentLoader, ok.classes).loadClass(ok.mainClass)
        // By signature, not declaredConstructors[0]: that array is in no specified order, so a second one — a
        // receiver added in McpScriptConfigurator, a synthetic default overload — would be picked at random.
        val instance = cls.getDeclaredConstructor(ScriptScope::class.java).apply { isAccessible = true }.newInstance(scope)
        val name = ok.resultField ?: return Unit
        val field = cls.getDeclaredField(name).apply { isAccessible = true }
        val raw = field.get(instance)
        // A value class sits UNBOXED in its backing field, so the line would otherwise disagree with its own
        // type. The compiler puts a static `box-impl` on every value class: going through it keeps the boxing
        // the compiler's own, and resolving the class on the snippet's loader keeps it the same kotlin the
        // snippet was compiled against — which kotlin-reflect could not promise, since ours is a different
        // copy whenever the regime splits the two.
        val valueClass = ok.resultValueClass ?: return raw
        return runCatching {
            Class.forName(valueClass, false, cls.classLoader)
                .getDeclaredMethod("box-impl", field.type).apply { isAccessible = true }
                .invoke(null, raw)
        }.getOrElse {
            Constants.LOG.warn("[mcp-plain] boxing {} failed; it is reported unboxed", valueClass, it)
            raw
        }
    }

    @Synchronized
    @OptIn(K1Deprecation::class, ExperimentalCompilerApi::class)
    fun warmUp(cpFiles: List<File>, parentApiVersion: String? = null) {
        warm?.let { return }
        // Parsed once, here: the overlay writes its rebuilt kotlin_module at this level too, and the frontend
        // rejects a module file newer than what it was pinned to.
        val pinned = LanguageVersion.fromVersionString(parentApiVersion)
        val widened = assembleCompileClasspath(cpFiles, PlainEngine::class.java.classLoader, pinned)
        val collector = ScriptDiagnosticsMessageCollector(null)

        // create(), not the bare constructor: it is what registers the extension storage the IR extension
        // below goes into, and the diagnostic factories storage createSourceSession refuses to run without.
        val configuration = CompilerConfiguration.create(messageCollector = collector).apply {
            put(CommonConfigurationKeys.MODULE_NAME, "mcp")
            // We drive the K2 frontend directly, but the JVM backend only learns that from here, and several
            // of its lowerings are gated on it — including the one that reparents a library's top-level
            // callables from their package fragment onto their JVM facade class, without which codegen fails.
            put(CommonConfigurationKeys.USE_FIR, true)
            // Before the environment is built, so the roots are indexed once as it is constructed and land in
            // the content roots createLibraryListForJvm reads. `updateClasspath` is the other way in, but it
            // subtracts what is already registered — it is for jars that appear mid-session, which ours never do.
            addJvmClasspathRoots(widened)
            // Without these the JDK itself is off the compile classpath and even `java.lang.Object` — every
            // MC type's supertype — fails to resolve. Home first: the roots are read out of it.
            configureJdkHomeFromSystemProperty()
            configureJdkClasspathRoots()
            put(JVMConfigurationKeys.USE_FAST_JAR_FILE_SYSTEM, true)
            // Snippets inline mod code (McpScope.yield) and higher bytecode cannot inline into lower, so the
            // running JVM — the ceiling of every loadable class — is the floor for the target.
            put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.fromString(Runtime.version().feature().toString()) ?: JvmTarget.entries.last())
            // Friend-all: `internal` across every classpath jar. Inert on its own — createLibraryListForJvm
            // below is what turns it into friend module data.
            put(JVMConfigurationKeys.FRIEND_PATHS, widened.map { it.absolutePath })
            // Non-null only in SPLIT, where the snippet compiles on our compiler but runs on the game's older
            // stdlib — pin it to that API level so it can't reference APIs the game lacks.
            pinned?.let { v ->
                languageVersionSettings = LanguageVersionSettingsImpl(v, ApiVersion.createByLanguageVersion(v))
                Constants.LOG.info("[mcp-plain] SPLIT: snippets pinned to language/api version {}", v.versionString)
            }
        }

        // Turns each IrScript into a class with the body in its constructor. Pure IR-in/IR-out — the one
        // piece of the scripting plugin still worth having, and it reads no scripting configuration.
        val storage = configuration.extensionsStorage ?: error("no extensionsStorage; scripts would never lower to classes")
        with(storage) { IrGenerationExtension.registerExtension(ScriptLoweringExtension()) }

        val disposable = Disposer.newDisposable("mcp-plain-engine")
        val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val projectEnvironment = environment.toVfsBasedProjectEnvironment()
        val librariesScope = PsiBasedProjectFileSearchScope(ProjectScope.getLibrariesScope(environment.project))
        val libraryList = createLibraryListForJvm("mcp", configuration, widened.map { it.absolutePath })

        val sessionContext = FirJvmSessionFactory.Context(configuration, projectEnvironment, librariesScope)
        val shared = FirJvmSessionFactory.createSharedLibrarySession(
            mainModuleName = Name.special("<mcp>"),
            extensionRegistrars = registrars,
            languageVersionSettings = configuration.languageVersionSettings,
            context = sessionContext,
        )
        FirJvmSessionFactory.createLibrarySession(
            shared,
            moduleDataProvider = libraryList.moduleDataProvider,
            extensionRegistrars = registrars,
            languageVersionSettings = configuration.languageVersionSettings,
            context = sessionContext,
        )

        warm = Warm(configuration, projectEnvironment, sessionContext, libraryList, collector, widened)
        Constants.LOG.info("[mcp-plain] warm: {} classpath entries, all friends", widened.size)
    }

    /** Compile [code] to woven class bytes. Off-tick, and [warmUp] must already have run — warming here would
     *  silently drop its SPLIT api pinning, since the first call is the one that sticks. [name] becomes the
     *  generated class's name, so a fixed one would give every snippet the same class. */
    fun compile(code: String, name: String, killIdField: String, evalId: Int): Compiled = compileLock.withLock {
        val w = warm ?: error("plain engine not warmed")
        w.collector.clear()   // diagnostics from an earlier snippet must not leak into this one
        val renderInternalNames = w.configuration.renderDiagnosticInternalName

        val moduleData = FirSourceModuleData(
            Name.special("<$name>"),
            w.libraryList.regularDependencies,
            emptyList(),
            w.libraryList.friendDependencies,
            JvmPlatforms.defaultJvmPlatform,
        )
        val session: FirSession = FirJvmSessionFactory.createSourceSession(
            moduleData,
            javaSourcesScope = AbstractProjectFileSearchScope.EMPTY,
            createIncrementalCompilationSymbolProviders = { null },
            extensionRegistrars = registrars,
            configuration = w.configuration,
            context = w.sessionContext,
            needRegisterJavaElementFinder = true,
            isForLeafHmppModule = false,
            init = {},
        )

        val reporter = DiagnosticsCollectorImpl()
        // The extension is what puts the parser into script mode; the stem is what the script class is named
        // after. The path must be non-null or `CompilerMessageLocationWithRange.create` drops the whole
        // location, and every diagnostic arrives without a line to point at.
        val fileName = "$name$SCRIPT_EXT"
        val source = KtInMemoryTextSourceFile(fileName, fileName, code)
        val firFiles = session.buildFirViaLightTree(listOf(source), reporter, null)
        val (scopeSession, fir) = session.runResolution(firFiles)
        session.runCheckers(scopeSession, fir, reporter, MppCheckerKind.Common)
        session.runCheckers(scopeSession, fir, reporter, MppCheckerKind.Platform)
        if (reporter.hasErrors) {
            reporter.reportToMessageCollector(w.collector, renderInternalNames)
            return Compiled.Failed(w.collector.diagnostics)
        }

        val frontend = AllModulesFrontendOutput(listOf(SingleModuleFrontendOutput(session, scopeSession, fir)))
        val emitted = emitBytecode(w, TargetId(name, "java-production"), frontend, reporter, yieldTypes(fir, session))
        reporter.reportToMessageCollector(w.collector, renderInternalNames)
        if (reporter.hasErrors) return Compiled.Failed(w.collector.diagnostics)

        val raw = LinkedHashMap<String, ByteArray>()
        for (f in emitted.state.factory.asList()) raw[f.relativePath] = f.asByteArray()
        // The lowering tags the script class with its own name and its result field, so nothing here guesses.
        val result = extractResultFields(emitted.irModule).values.firstOrNull()
        val mainClass = result?.scriptClassName?.asString() ?: scriptClassName(emitted.irModule)
        val woven = weaveClasses(raw, killIdField, evalId, w.classpath)
        val resultCone = result?.let { resultConeType(fir) }
        val resultType = result?.let { resultTypeName(resultCone, it.fieldTypeName) }
        return Compiled.Ok(woven, mainClass, result?.fieldName?.asString(), resultType, valueClassName(resultCone, session, mainClass))
    }

    private class Emitted(val irModule: IrModuleFragment, val state: GenerationState)

    /** Resolved FIR through fir2ir and the JVM backend. [yieldTypes] rides along as one more IR extension —
     *  the conversion is where an IrCall is still a call, so it is the only window to fill its arguments. */
    private fun emitBytecode(
        w: Warm,
        targetId: TargetId,
        frontend: AllModulesFrontendOutput,
        reporter: BaseDiagnosticsCollector,
        yieldTypes: Map<Pair<Int, Int>, String>,
    ): Emitted {
        // The conversion fills this in and the backend reads it back, so both halves must hold the SAME
        // instance — a second one arrives at codegen empty.
        val extensions = JvmFir2IrExtensions(w.configuration)
        val fir2Ir = frontend.convertToIrAndActualizeForJvm(
            extensions, w.configuration, reporter,
            w.configuration.getCompilerExtensions(IrGenerationExtension) + YieldTypeExtension(yieldTypes),
        )
        // The resolver answers "what class is this ASM type" for the backend — value-class boxing and the
        // compiling-against-JDK8 check both go through it. The default one answers from module descriptors,
        // which under K2 hold nothing, so it must be the FIR-backed one.
        val state = GenerationState(
            w.projectEnvironment.project,
            fir2Ir.irModuleFragment.descriptor,
            w.configuration,
            ClassBuilderFactories.BINARIES,
            targetId = targetId,
            moduleName = targetId.name,
            jvmBackendClassResolver = FirJvmBackendClassResolver(fir2Ir.components),
            diagnosticReporter = reporter,
        )
        val backendInput = JvmIrCodegenFactory.BackendInput(
            fir2Ir.irModuleFragment,
            fir2Ir.pluginContext.irBuiltIns,
            fir2Ir.symbolTable,
            fir2Ir.components.irProviders,
            extensions,
            FirJvmBackendExtension(
                fir2Ir.components,
                fir2Ir.irActualizedResult?.actualizedExpectDeclarations?.extractFirDeclarations(),
            ),
            fir2Ir.pluginContext,
        )
        // Neither call hands the bytes back: codegen writes them into state.factory, which is what [compile]
        // reads. Lowering must run first — it is where the script class is shaped into JVM IR.
        val factory = JvmIrCodegenFactory(w.configuration)
        factory.invokeCodegen(factory.invokeLowerings(state, backendInput))
        return Emitted(fir2Ir.irModuleFragment, state)
    }

    /** The result property's resolved type. Two things are read off it that the rendered name cannot carry:
     *  flexibility, which the renderer flattens to the upper bound, and value-class-ness. */
    @OptIn(DirectDeclarationsAccess::class)
    private fun resultConeType(fir: List<FirFile>): ConeKotlinType? = fir.asSequence()
        .flatMap { it.declarations }.filterIsInstance<FirScript>()
        .flatMap { it.declarations }.filterIsInstance<FirProperty>()
        .firstOrNull { it.name == RESULT_PROPERTY }
        ?.returnTypeRef?.coneTypeOrNull

    /** JVM binary name of the result's type when it is a value class, else null — the one case where the
     *  field alone cannot report the value it holds. A root package means the SNIPPET declared it, and the script
     *  lowering nests those inside [scriptClass]: a ClassId carries the relative path, never the container. */
    private fun valueClassName(cone: ConeKotlinType?, session: FirSession, scriptClass: String): String? {
        val id = cone?.toRegularClassSymbol(session)?.takeIf { it.isInlineOrValue }?.classId ?: return null
        val nested = id.relativeClassName.asString().replace('.', '$')
        return if (id.packageFqName.isRoot) "$scriptClass$$nested" else "${id.packageFqName.asString()}.$nested"
    }

    /**
     * The result type as the frontend saw it: IR flattens a platform type to its upper bound at EVERY level, so
     * patching the rendered string reaches the outermost `?` and nothing inside it. The cone still knows.
     *
     * [fallback] is the lowered field's IR type, for a cone that should always be there — null would drop the
     * whole `=> type = value` line, not just its type.
     */
    private fun resultTypeName(cone: ConeKotlinType?, fallback: String): String = cone?.renderReadableWithFqNames() ?: fallback

    /** Only reached when the snippet had no result value, so the lowering left no tag to read the name off.
     *  A script's own declarations become members of its class, so the file holds exactly the one. */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun scriptClassName(module: IrModuleFragment): String =
        module.files.flatMap { it.declarations }.filterIsInstance<IrClass>().singleOrNull()?.kotlinFqName?.asString()
            ?: error("no script class in the lowered module — ScriptLoweringExtension did not run")
}
