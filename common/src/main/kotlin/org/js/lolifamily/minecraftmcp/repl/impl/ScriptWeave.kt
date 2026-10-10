package org.js.lolifamily.minecraftmcp.repl.impl

import org.jetbrains.kotlin.scripting.compiler.plugin.impl.CompiledScriptClassLoader
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.exec.GuardLane
import org.js.lolifamily.minecraftmcp.repl.AccessBridge
import org.js.lolifamily.minecraftmcp.repl.NamespaceProbe
import org.js.lolifamily.minecraftmcp.repl.RemapBundle
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.io.File

/** Build the stock scripting classloader over our (instrumented, maybe-remapped) bytes, so it keeps the
 *  SAME class + resource semantics as the stock scripting loader — only the bytes differ.
 *  Crucially the stock loader serves each class's bytes AS A RESOURCE (getResourceAsStream), which
 *  kotlin-reflect reads to parse @Metadata; a findClass-only loader silently degrades reflection over
 *  script-defined types (`::class.members`, data-class `copy()`). The try/catch still falls back to a
 *  minimal loader that at least serves class bytes as resources if a LinkageError ever surfaces (e.g. the
 *  impl type moved in a future kotlin version — the link is resolved lazily at this call site, so
 *  NoClassDefFoundError is catchable here). */
internal fun compiledScriptClassLoader(parent: ClassLoader?, files: Map<String, ByteArray>): ClassLoader {
    val loader: ClassLoader = try {
        CompiledScriptClassLoader(parent, files)
    } catch (t: Throwable) {
        Constants.LOG.warn(
            "[mcp-guard] stock CompiledScriptClassLoader unavailable ({}); minimal loader — " +
                "reflection over script-defined types may degrade",
            "$t",
        )
        object : ClassLoader(parent) {
            override fun findClass(name: String): Class<*> {
                val b = files[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
                return defineClass(name, b, 0, b.size)
            }
            override fun getResourceAsStream(name: String): java.io.InputStream? =
                files[name]?.let { java.io.ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
        }
    }
    // The guard's kill path names java.lang.Thread. Resolve it for this loader now, on a shallow stack, not on the
    // first kill — which may land at the stack-full edge. Once per loader covers every class it defines.
    Class.forName("java.lang.Thread", false, loader)
    return loader
}

// ============================================================================================
// Post-compile bytecode pipeline. The order is fixed:
//   1. Remap (non-mojmap only): mojmap refs → runtime namespace
//   2. Access widening: field/method accesses on external classes → invokedynamic + privateLookupIn.
//      Must follow remap — its indy owners are runtime names, resolved by Class.forName at bootstrap.
//   3. Timeout guard: inline `if(killId == evalId && on the lane thread) throw` at method entries, back-edges,
//      catch handlers.
//      Must follow widening — an indy needs a frame, and the guard must stay frameless to fire at the
//      stack-full edge, so the widening pass must never see the guard's GETSTATICs.
// Runs on every path incl. dev mojmap — which is why compiledScriptClassLoader must be a faithful loader.
// ============================================================================================

/** Post-compile bytecode pipeline. [classpath] is the compile classpath the snippet was built against, used
 *  as the remap reference. */
internal fun weaveClasses(
    input: Map<String, ByteArray>,
    guardLane: GuardLane?,
    evalId: Int,
    classpath: List<File>,
): Map<String, ByteArray> {
    val toNs = when (NamespaceProbe.current()) {
        // SPIGOT shares the slot: its obf names are written into the bundle's "intermediary" column (assembleSpigot).
        NamespaceProbe.Namespace.INTERMEDIARY, NamespaceProbe.Namespace.SPIGOT -> "intermediary"
        NamespaceProbe.Namespace.MIXED_SRG -> "srg"   // forge prod
        else -> null   // MOJMAP / UNKNOWN → no remap
    }
    // Null when provisioning failed on a runtime that needs it; CommonClass said so once at startup, so this
    // path stays silent rather than warning per eval.
    val bundle = RemapBundle.current()
    var files = input
    try {
        if (toNs != null && bundle != null) {
            // Remap reference classpath: mc-symbols is the only thing worth INDEXING.
            // readClassPath PARSES every class to build its inheritance index, so the cost is class COUNT, not
            // bytes: the whole compile cp holds an order of magnitude more of them, indexed and held open for a
            // hierarchy almost entirely irrelevant to the snippet's MC references. What DOES matter outside
            // mc-symbols — the snippet's own classes, and mod / loader types that subclass MC —
            // FlatRemapper.supersOf reads lazily instead. MC's non-MC supers (DFU etc.) aren't remapped anyway.
            val symbolFile = bundle.symbolsJar.toFile().absoluteFile
            val cp = classpath.filter { it.absoluteFile == symbolFile }
            // Never falls back to the full cp: an empty reference classpath remaps SILENTLY WRONG — with no
            // hierarchy an override stops resolving to its ancestor's mapping row — so this is a broken
            // invariant, not a case to degrade through. ReplBridge prepended exactly this jar.
            check(cp.isNotEmpty()) { "remap bundle symbol jar is not on the compile classpath: $symbolFile" }
            files = remapModule(files, bundle.mappings.toString(), cp, "named", toNs)
        }
        files = widenAccess(files)
    } catch (t: Throwable) {
        Constants.LOG.error("[mcp-remap] remap/widen failed; running as-is", t)
    }
    // Outside the try: an unguarded snippet must not reach a tick thread, so a failure here fails the eval.
    // Null guardLane => the off-tick ParallelLane: no watchdog, so no guard instrumentation. Remap and
    // widening already ran above (a parallel eval importing net.minecraft.* needs both all the same).
    if (guardLane != null) files = instrument(files, guardLane, evalId)
    return files
}

/** Instrument every real .class in the compiled script — top-level AND lambda/iterator synthetics (the
 *  iterator{} body lives in a synthetic $...invokeSuspend class, so instrumenting only the top-level class
 *  would miss the cross-tick loop). Metadata (.kotlin_module) rides along untouched. A failure on any one
 *  class fails the whole eval. */
private fun instrument(cof: Map<String, ByteArray>, guardLane: GuardLane, evalId: Int): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>(cof.size)
    for ((path, bytes) in cof) {
        out[path] = if (path.endsWith(".class")) instrumentClass(bytes, guardLane, evalId) else bytes
    }
    return out
}

/** ASM [ClassWriter] with COMPUTE_FRAMES that merges any two different reference types to Object — what kotlinc's
 *  own writer answers too (`ClassBuilderFactories.BinaryClassWriter`), so the recomputed frames are never less
 *  precise than the ones they replace. Sound only on kotlinc's invariant: a value is cast to its expected type
 *  before a merge, or before any use that needs more than Object (an interface is Object to the verifier). Both
 *  passes keep it — widening only ever erases a type toward Object, and the guard's branches join identical states —
 *  while javac output, which never casts an upcast, would not carry it.
 *
 *  Not ASM's default, which loads both names through this class's own loader: the module being woven is bytes no
 *  loader has seen, and for every other name the answer cannot matter. Shared by both bytecode passes below
 *  ([instrumentClass] and [widenAccessClass]). */
private fun frameWriter(cr: ClassReader): ClassWriter = object : ClassWriter(cr, COMPUTE_FRAMES) {
    override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
}

/** ASM core pass inlining the guard at every method entry EXCEPT `<init>`/`<clinit>`, plus every loop back-edge
 *  and real catch entry. The inlined branches (skip label) need a StackMapTable frame, so
 *  COMPUTE_FRAMES (can't accept(cr,0)+COMPUTE_MAXS like a stack-neutral call would).
 *
 *  The snippet body compiles INTO `<init>`, so it gets no entry guard — but back-edges and catch handlers are
 *  driven by the visitor, not by that exclusion, so the runaway-loop and swallowed-timeout cases stay covered.
 *  An entry guard there would have to sit before the super call, where `this` is still uninitialized. */
private fun instrumentClass(bytes: ByteArray, guardLane: GuardLane, evalId: Int): ByteArray {
    val cr = ClassReader(bytes)
    val cw = frameWriter(cr)
    @Suppress("ktlint:standard:wrapping")
    cr.accept(object : ClassVisitor(Opcodes.ASM9, cw) {
        override fun visitMethod(a: Int, n: String?, d: String?, s: String?, e: Array<String>?): MethodVisitor =
            GuardMethodVisitor(super.visitMethod(a, n, d, s, e), n.orEmpty(), guardLane, evalId)
    }, ClassReader.SKIP_FRAMES)
    return cw.toByteArray()
}

/** Inlines the guard at method entry (except `<init>`/`<clinit>`), loop back-edges and real catch
 *  entries — rationale in the class body below. */
private class GuardMethodVisitor(mv: MethodVisitor, mn: String, private val guardLane: GuardLane, private val evalId: Int) :
    MethodVisitor(Opcodes.ASM9, mv) {
    private val seen = HashSet<Label>()          // labels already visited: a later jump to one is a back-edge
    private val catchHandlers = HashSet<Label>() // entry labels of REAL catches (type != null) — see visitLabel

    // Inlined time guard at three points: method entry (visitCode), loop back-edge (before a jump/switch to an
    // already-seen label), and real catch-handler entry. The catch point makes a timeout uncatchable:
    // ScriptTimeoutError is an Error, so `catch(Exception)` already misses it — but `catch(Throwable)` would
    // swallow it and the script would "return normally" (isError=false), with the caller never learning it
    // timed out. Re-checking the kill id on catch entry re-throws past that catch (the handler sits outside its own
    // try region, so the same catch can't re-catch it). Only type != null handlers are guarded: a finally's
    // synthetic catch-all doesn't swallow (it runs cleanup then re-throws), and Kotlin's is self-referential,
    // so guarding it would make the re-throw catch itself and spin forever.
    private var guardEntry = mn != "<init>" && mn != "<clinit>"

    // Inline `if (TimeoutGuard.killId == evalId && TimeoutGuard.killThreads[lane] == Thread.currentThread()) throw
    // TimeoutGuard.timeout`. The id test pushes NO new frame — runs inside the current frame even at the stack-full
    // edge; the thread test behind it runs only mid-kill (TimeoutGuard says why its call is safe there). Stack peaks
    // at +2 on every path. Adds branches → needs COMPUTE_FRAMES (see instrumentClass). GETSTATIC (not GETFIELD): the
    // fields are @JvmField on a Kotlin `object`, which compiles to STATIC fields — a GETFIELD here would throw
    // IncompatibleClassChangeError at link time.
    private fun emitInline() {
        val g = "org/js/lolifamily/minecraftmcp/exec/TimeoutGuard"
        val skip = Label()
        super.visitFieldInsn(Opcodes.GETSTATIC, g, guardLane.killIdField, "I")   // serverKillId / renderKillId, per target lane
        super.visitLdcInsn(evalId)
        super.visitJumpInsn(Opcodes.IF_ICMPNE, skip)
        super.visitFieldInsn(Opcodes.GETSTATIC, g, "killThreads", "[Ljava/lang/Thread;")
        super.visitLdcInsn(guardLane.ordinal)
        super.visitInsn(Opcodes.AALOAD)
        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false)
        super.visitJumpInsn(Opcodes.IF_ACMPNE, skip)
        super.visitFieldInsn(Opcodes.GETSTATIC, g, "timeout", "Lorg/js/lolifamily/minecraftmcp/exec/ScriptTimeoutError;")
        super.visitInsn(Opcodes.ATHROW)
        super.visitLabel(skip)
    }

    override fun visitCode() {
        super.visitCode()
        if (guardEntry) { guardEntry = false; emitInline() }   // method-entry guard, before first insn
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        if (type != null) catchHandlers.add(handler)   // real catch; a finally's type==null catch-all is skipped
        super.visitTryCatchBlock(start, end, handler, type)
    }

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        seen.add(label)                        // a later jump/switch back to this label is a loop back-edge
        // Real catch entry (stack = [exception]): re-check the kill id so a swallowed timeout is re-thrown straight
        // past this catch. COMPUTE_FRAMES recomputes the handler frame, so inserting right after the label
        // (no deferral to the first real insn) is safe — emitInline is stack-neutral (ends [exception]).
        if (label in catchHandlers) { emitInline() }
    }

    override fun visitJumpInsn(op: Int, label: Label) {
        if (label in seen) { emitInline() }   // loop back-edge: BEFORE the jump
        super.visitJumpInsn(op, label)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        if (dflt in seen || labels.any { it in seen }) { emitInline() }
        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>) {
        if (dflt in seen || labels.any { it in seen }) { emitInline() }
        super.visitLookupSwitchInsn(dflt, keys, labels)
    }
}

// ============================================================================================
// Access widening: replace field/method accesses on external classes with invokedynamic calls that bootstrap
// via MethodHandles.privateLookupIn, so private/protected/package-private members are reachable from REPL
// scripts.
//
// Widening the MEMBER is only half of it: a call site whose descriptor NAMES an inaccessible class is rejected
// while the JVM resolves that descriptor, strictly before any bootstrap runs, so indy alone buys nothing there.
// Every inaccessible reference type in a call-site descriptor is therefore erased to Object here, with the real
// names passed through as bootstrap String arguments for AccessBridge to recover. Accessible types are left
// precise, so the hot path keeps its inlining.
//
// A value that crossed the bridge is therefore an Object to the verifier for good, so every consumer of one must
// be erased or bridged too: a type instruction moves into the bridge (visitTypeInsn), and so does every super call,
// and a constructor call where it has to (bridgeSpecial).
//
// What stays out, each explained where it is decided: creating or catching a non-public class (visitTypeInsn), and
// super(...) past what invokespecial allows (bridgeSpecial).
// ============================================================================================

/** A real class entry: a `.class` name whose bytes actually carry the JVM magic. */
private fun isClassFile(path: String, bytes: ByteArray): Boolean = path.endsWith(".class") &&
    bytes.size >= 4 &&
    (bytes[0].toInt() and 0xFF) == 0xCA &&
    (bytes[1].toInt() and 0xFF) == 0xFE

/**
 * Erasure is a MODULE-level decision, so the ownership test spans every class this compilation produced — not
 * just the one being visited. One eval yields several classes (the snippet plus a synthetic per lambda /
 * `iterator {}` body), and a member erased in its declaring class must read the same from every sibling that
 * references it. Scoped per-class the two disagree, and the mismatch is a link-time
 * `NoSuchMethodError`/`NoSuchFieldError` naming the snippet's own class.
 *
 * The test is on the DECLARING class, climbed through the module's own superclass chain — not on the access
 * site's owner: a snippet class extending an MC class names its inherited members through itself, and those are
 * somebody else's declarations, neither erased here nor reachable without the bridge. Interfaces are not
 * climbed: no instance fields there, and an owned default method still resolves through the bridge.
 *
 * All-or-nothing for the same reason: a half-widened module IS that disagreement, so a single failure throws
 * and [weaveClasses]'s catch leaves the whole module unwidened.
 */
private fun widenAccess(cof: Map<String, ByteArray>): Map<String, ByteArray> {
    // The map's keys ARE the ownership test; its values carry the chain to climb.
    val supers = HashMap<String, String?>()
    val declared = HashSet<String>()
    for ((path, bytes) in cof) {
        if (!isClassFile(path, bytes)) continue
        val cr = ClassReader(bytes)
        supers[cr.className] = cr.superName
        cr.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? {
                    declared.add("${cr.className} $n $d"); return null
                }
                override fun visitMethod(a: Int, n: String, d: String, s: String?, x: Array<out String>?): MethodVisitor? {
                    declared.add("${cr.className} $n $d"); return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    val declaredHere = { owner: String, name: String, desc: String ->
        var c: String? = owner
        var found = false
        while (c != null && c in supers) {
            if ("$c $name $desc" in declared) { found = true; break }
            c = supers[c]
        }
        found
    }
    val out = LinkedHashMap<String, ByteArray>(cof.size)
    for ((path, bytes) in cof) {
        out[path] = if (!isClassFile(path, bytes)) {
            bytes
        } else {
            try {
                widenAccessClass(bytes, declaredHere)
            } catch (t: Throwable) {
                throw IllegalStateException("access widen failed for $path", t)
            }
        }
    }
    return out
}

private fun widenAccessClass(bytes: ByteArray, declaredHere: (String, String, String) -> Boolean): ByteArray {
    val cr = ClassReader(bytes)
    val cw = frameWriter(cr)
    @Suppress("ktlint:standard:wrapping")
    cr.accept(object : ClassVisitor(Opcodes.ASM9, cw) {
        // Erasure has to CASCADE into the snippet's OWN declarations. Once a value's type is erased to
        // Object on the stack, the field it gets stored into — and any signature it flows through — must
        // be Object too, or the verifier rejects the class outright ("Type 'java/lang/Object' is not
        // assignable to ..."). The generic signature is dropped whenever the descriptor changes: it is
        // metadata only, and a stale one would contradict the erased descriptor.
        override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor {
            val e = AccessWideningVisitor.erase(desc)
            return super.visitField(access, name, e, if (e == desc) sig else null, value)
        }

        override fun visitMethod(access: Int, name: String, desc: String, sig: String?, exc: Array<String>?): MethodVisitor {
            val e = AccessWideningVisitor.eraseMethodDesc(desc)
            val mv = super.visitMethod(access, name, e, if (e == desc) sig else null, exc)
            return AccessWideningVisitor(mv, declaredHere, if (name == "<init>") cr.superName else null)
        }
    }, ClassReader.SKIP_FRAMES)
    return cw.toByteArray()
}

/** [superInit] is the direct superclass when the visited method is a constructor, null otherwise — see
 *  [bridgeSpecial]. */
internal class AccessWideningVisitor(
    mv: MethodVisitor,
    private val declaredHere: (String, String, String) -> Boolean,
    private val superInit: String?,
) : MethodVisitor(Opcodes.ASM9, mv) {
    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        // Ours first, so the invariant is read off the declaring class rather than inferred from a name shape:
        // our own declarations were erased, so the access must agree with them — and we reach them directly, no
        // bridge needed. Every other declaration keeps its real descriptor; we cannot rewrite THEIRS to match.
        val ours = declaredHere(owner, name, descriptor)
        if (ours || !shouldWiden(owner)) {
            super.visitFieldInsn(opcode, owner, name, if (ours) erase(descriptor) else descriptor)
            return
        }
        // Both the OWNER and the FIELD TYPE go into the call-site descriptor, and the JVM access-checks
        // every type in it while resolving the call site — before the bootstrap can run. Erase the ones
        // a snippet cannot name; AccessBridge recovers the real types reflectively.
        val recv = erase("L$owner;")
        val ft = erase(descriptor)
        when (opcode) {
            Opcodes.GETFIELD -> {
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "fieldGet", BSM2, false)
                super.visitInvokeDynamicInsn("get", "($recv)$ft", bsm, owner, name)
            }
            Opcodes.PUTFIELD -> {
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "fieldSet", BSM2, false)
                super.visitInvokeDynamicInsn("set", "($recv$ft)V", bsm, owner, name)
            }
            Opcodes.GETSTATIC -> {
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "staticFieldGet", BSM2, false)
                super.visitInvokeDynamicInsn("get", "()$ft", bsm, owner, name)
            }
            Opcodes.PUTSTATIC -> {
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "staticFieldSet", BSM2, false)
                super.visitInvokeDynamicInsn("set", "($ft)V", bsm, owner, name)
            }
            else -> super.visitFieldInsn(opcode, owner, name, descriptor)
        }
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        // Same rule as visitFieldInsn; what a bridge can carry at all is [bridgeable].
        val ours = declaredHere(owner, name, descriptor)
        if (opcode == Opcodes.INVOKESPECIAL && !ours && bridgeSpecial(owner, name, descriptor, isInterface)) return
        if (ours || !bridgeable(opcode, owner, name)) {
            super.visitMethodInsn(
                opcode, owner, name,
                if (ours) eraseMethodDesc(descriptor) else descriptor, isInterface,
            )
            return
        }
        when (opcode) {
            // The UNERASED `descriptor` still rides along as a bootstrap String argument: resolving it
            // inside AccessBridge only LOADS classes, and loading is not access-checked.
            Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                val indyDesc = "(" + erase("L$owner;") + eraseMethodDesc(descriptor).substring(1)
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "virtualCall", BSM3, false)
                super.visitInvokeDynamicInsn("call", indyDesc, bsm, owner, name, descriptor)
            }
            Opcodes.INVOKESTATIC -> {
                val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "staticCall", BSM3, false)
                super.visitInvokeDynamicInsn("call", eraseMethodDesc(descriptor), bsm, owner, name, descriptor)
            }
            else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }

    /**
     * Reroutes a class's invokespecial through the bridge. A super call always goes, as every other call to a
     * foreign member does: [AccessBridge.superCall] opens the method, and its erased descriptor takes an argument
     * that crossed the bridge as Object. A constructor goes only where the call as written would fail — its
     * descriptor names a type the bridge erased, so verification would fail, and the whole class with it; or the
     * constructor itself is not public ([closedInit]) — since rerouting one costs a discarded allocation. True if
     * [owner]'s call was rerouted.
     *
     * A rerouted constructor keeps its NEW and DUP, so the class still initializes where it did:
     * [AccessBridge.construct] builds the instance, and the two uninitialized copies under it are dropped. Only a
     * NEW's `<init>` can go that way. A `super(...)`/`this(...)` initializes `this`, which nothing but invokespecial
     * can do, so it stays as written and fails past what invokespecial allows: a non-public constructor, an erased
     * argument. The verifier's own rule tells the two apart — such a call exists only in a constructor and names the
     * class itself (ours, never here) or its direct superclass, [superInit] — except for a NEW of the direct
     * superclass inside a constructor, which stays as written too, deliberately: telling it apart would take state
     * carried across instructions, for a shape a snippet almost never writes.
     *
     * Only a class's call is rerouted. An interface's super call, `super<I>.m()`, stays as written: a default method
     * is public, and none in the game names a type a snippet cannot, so there is nothing for the bridge to do.
     */
    private fun bridgeSpecial(owner: String, name: String, descriptor: String, isInterface: Boolean): Boolean {
        if (isInterface || !shouldWiden(owner)) return false
        if (name != "<init>") {
            val indyDesc = "(" + erase("L$owner;") + eraseMethodDesc(descriptor).substring(1)
            val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "superCall", BSM3, false)
            super.visitInvokeDynamicInsn("call", indyDesc, bsm, owner, name, descriptor)
            return true
        }
        if (owner == superInit) return false
        if (eraseMethodDesc(descriptor) == descriptor && !closedInit(owner, descriptor)) return false
        // The constructor's descriptor with the instance as its result: `(...)V` → `(...)Lowner;`.
        val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "construct", BSM2, false)
        super.visitInvokeDynamicInsn("new", eraseMethodDesc(descriptor.dropLast(1) + "L$owner;"), bsm, owner, descriptor)
        super.visitInsn(Opcodes.DUP_X2)   // [new, new, obj] → [obj, new, new, obj]
        super.visitInsn(Opcodes.POP)
        super.visitInsn(Opcodes.POP)
        super.visitInsn(Opcodes.POP)
        return true
    }

    /**
     * A type instruction naming a class a snippet cannot is itself access-checked. Each of the three with a fixed
     * stack shape therefore moves into the bridge, which loads the class by name, where loading is not
     * access-checked, and keeps the instruction's own semantics:
     *  - CHECKCAST: the same check, still an Object, or an array of Object, to the verifier. It cannot stay as
     *    written: kotlinc emits one on its own wherever a value flows into a slot of that type. Most are upcasts, but
     *    an `as`, or a value read out of a generic container, is a real check.
     *  - INSTANCEOF: `is` and `as?`.
     *  - ANEWARRAY: a real array of that class, typed as an array of Object.
     * A class literal, `::class`, moves into the bridge too: see [visitLdcInsn]. And `when (x) { is ... }` is one of
     * these INSTANCEOFs, PlainEngine compiling it as a chain of type checks.
     *
     * NEW stays as written, deliberately: its uninitialized object lives across the whole argument list, so carrying
     * it would mean lifting each NEW out of its arguments — far more rewriting than a snippet that creates a
     * non-public class is worth. So does a `catch` of one, deliberately too: it is an exception-table entry, not an
     * instruction, and carrying it would mean rewriting the handler. Both fail with IllegalAccessError.
     */
    override fun visitTypeInsn(opcode: Int, type: String) {
        val desc = if (type.startsWith("[")) type else "L$type;"
        val erased = erase(desc)
        when {
            erased == desc -> super.visitTypeInsn(opcode, type)
            opcode == Opcodes.CHECKCAST -> typeIndy("checkCast", "(Ljava/lang/Object;)$erased", type)
            opcode == Opcodes.INSTANCEOF -> typeIndy("instanceOf", "(Ljava/lang/Object;)Z", type)
            opcode == Opcodes.ANEWARRAY -> typeIndy("newArray", "(I)[$erased", type)   // its operand is the element type
            else -> super.visitTypeInsn(opcode, type)   // NEW, deliberately: see above
        }
    }

    /** A class literal, `::class`, of a class a snippet cannot name, as a dynamic constant [AccessBridge.classConstant]
     *  resolves by name, where loading is not access-checked: still a constant, now one the snippet can load. */
    override fun visitLdcInsn(value: Any?) = super.visitLdcInsn(if (value is Type && closedClass(value)) classConstant(value) else value)

    /** A type instruction on [type] — an internal name, or an array descriptor — as an indy through [bootstrap]. */
    private fun typeIndy(bootstrap: String, desc: String, type: String) =
        super.visitInvokeDynamicInsn(bootstrap, desc, Handle(Opcodes.H_INVOKESTATIC, BRIDGE, bootstrap, BSM1, false), type)

    /**
     * Lambdas (`LambdaMetafactory` call sites the Kotlin compiler emits). Two things here name a type we
     * erased and must move with it: the captured-argument descriptor, and the impl-method handle — which
     * points at the snippet's OWN synthetic method, whose signature [widenAccessClass]'s `visitMethod` already
     * erased. `instantiatedMethodType` describes that handle, so it moves too ([INSTANTIATED_TYPE]).
     *
     * `samMethodType` does NOT, and neither do altMetafactory's bridge types: they are the INTERFACE's
     * contract. LMF builds the proxy's method descriptor from `samMethodType`, so an erased one declares a
     * signature the interface never had — it links clean and throws `AbstractMethodError` at the first call,
     * from inside whoever invoked the callback. Left alone, a SAM the snippet cannot implement is refused at
     * the lambda itself, naming the type.
     *
     * Only handles owned by the snippet are rewritten, for the same reason as everywhere else: we can
     * erase our own declarations, not somebody else's.
     */
    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: Handle, vararg bsmArgs: Any?) {
        // Array(...) stays in argument position rather than going through a local: an array constructor in a
        // vararg slot is the shape the compiler copy-elides, so the spread costs nothing.
        super.visitInvokeDynamicInsn(
            name, eraseMethodDesc(descriptor), bsm,
            *Array(bsmArgs.size) { i ->
                when (val a = bsmArgs[i]) {
                    is Handle ->
                        if (!declaredHere(a.owner, a.name, a.desc)) {
                            a
                        } else {
                            Handle(a.tag, a.owner, a.name, eraseHandleDesc(a.tag, a.desc), a.isInterface)
                        }
                    // The owner + index pin this to a MethodType, so `a.sort` adds nothing. Every other
                    // bootstrap's arguments are somebody else's too, and pass through for the same reason.
                    is Type -> if (bsm.owner == LMF && i == INSTANTIATED_TYPE) Type.getMethodType(eraseMethodDesc(a.descriptor)) else a
                    else -> a
                }
            },
        )
    }

    companion object {
        private const val BRIDGE = "org/js/lolifamily/minecraftmcp/repl/AccessBridge"
        private const val BSM1 = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;)Ljava/lang/invoke/CallSite;"
        private const val BSM2 = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;"
        private const val BSM3 = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;"
        private const val CONDY = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;" +
            "Ljava/lang/String;)Ljava/lang/Class;"

        /** Whether [type], an `ldc` constant, is a class a snippet cannot name; a method type never is. */
        private fun closedClass(type: Type): Boolean = type.sort != Type.METHOD && erase(type.descriptor) != type.descriptor

        /** [type] as a dynamic constant resolved through [AccessBridge.classConstant]. */
        private fun classConstant(type: Type): ConstantDynamic {
            val bsm = Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "classConstant", CONDY, false)
            return ConstantDynamic("class", "Ljava/lang/Class;", bsm, type.internalName)
        }

        private const val LMF = "java/lang/invoke/LambdaMetafactory"

        /** Where `metafactory` and `altMetafactory` both put `instantiatedMethodType` — the one bootstrap
         *  argument describing the impl handle rather than the interface. See [visitInvokeDynamicInsn]. */
        private const val INSTANTIATED_TYPE = 2

        /** The JVM's two initializer methods: never a call [bridgeable] takes. */
        private val NON_INDY_METHODS = setOf("<init>", "<clinit>")

        private val OPAQUE = arrayOf(
            // JDK
            "java/", "javax/", "sun/", "jdk/",
            // the compiler/IDE stack we RUN on (not kotlin stdlib — that stays accessible)
            "org/jetbrains/",
            // infrastructure libraries (high class count, never accessed from REPL)
            "it/unimi/", "org/apache/", "io/netty/", "com/ibm/", "org/lwjgl/",
            "org/spongepowered/", "com/sun/", "oshi/", "com/llamalad7/",
        )

        // Everything under an [OPAQUE] prefix is machinery the REPL never reaches into; everything else is
        // fair game, default package included — a snippet's own classes are excluded by `owned`, exactly.
        // Takes an internal CLASS name, never a package path: the OPAQUE prefixes end in '/'. An array owner
        // arrives as a descriptor ([I) instead — it breaks that contract, and has nothing to widen anyway.
        fun shouldWiden(owner: String): Boolean = !owner.startsWith("[") && OPAQUE.none { owner.startsWith(it) }

        /** Whether a call site always goes through an [AccessBridge] indy: a widenable owner, and neither an
         *  initializer nor an INVOKESPECIAL — those are [bridgeSpecial]'s. */
        fun bridgeable(opcode: Int, owner: String, name: String): Boolean =
            shouldWiden(owner) && name !in NON_INDY_METHODS && opcode != Opcodes.INVOKESPECIAL

        /** Cache for [inaccessible] — keyed by internal name, one class-file read per class. */
        private val publicity = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /**
         * True if [internalName] is NOT a public class — meaning a snippet cannot even NAME it, let
         * alone touch its members: a snippet always sits in a different runtime package (different
         * loader AND different package name), so package-private is out of reach by definition.
         *
         * Read straight from the class file's access flags: cheap, cached, and it loads nothing.
         * Anything unreadable counts as accessible, so this can only ever ADD erasure where it is
         * provably needed — an unknown class keeps the old, precise-typed call site.
         */
        fun inaccessible(internalName: String): Boolean = publicity.getOrPut(internalName) {
            try {
                Constants.MC_LOADER.getResourceAsStream("$internalName.class")?.use {
                    (ClassReader(it.readBytes()).access and Opcodes.ACC_PUBLIC) == 0
                } ?: false
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * True if [owner]'s constructor [desc] is not public. Read like [inaccessible], with the same default —
         * anything unreadable counts as public — but not cached: unlike a type, which every descriptor names again,
         * it is asked only about the constructors a snippet calls, and those are few and seldom repeated.
         */
        fun closedInit(owner: String, desc: String): Boolean = try {
            Constants.MC_LOADER.getResourceAsStream("$owner.class")?.use { s ->
                val node = ClassNode().also { ClassReader(s.readBytes()).accept(it, DECLARATIONS_ONLY) }
                node.methods.any { it.name == "<init>" && it.desc == desc && (it.access and Opcodes.ACC_PUBLIC) == 0 }
            } ?: false
        } catch (_: Throwable) {
            false
        }

        /**
         * Erase one type descriptor to `Object` when a snippet cannot name its class. An array erases its
         * ELEMENT type and keeps its dimensions: the real array is assignable to `[Ljava/lang/Object;` by
         * covariance and it is still an array, so `arraylength`/`aaload` keep verifying — collapsing to a
         * bare `Object` would not. Primitives and accessible types pass through untouched, so the common
         * path keeps its precise types — and with them the JIT's ability to inline the call site.
         */
        fun erase(desc: String): String {
            var i = 0
            while (i < desc.length && desc[i] == '[') i++
            if (i >= desc.length || desc[i] != 'L') return desc      // primitive, or a bare array of one
            val internal = desc.substring(i + 1, desc.length - 1)
            return if (shouldWiden(internal) && inaccessible(internal)) desc.substring(0, i) + "Ljava/lang/Object;" else desc
        }

        /** [erase] applied to every parameter and to the return type of METHOD descriptor. */
        fun eraseMethodDesc(desc: String): String {
            val sb = StringBuilder("(")
            for (a in Type.getArgumentTypes(desc)) sb.append(erase(a.descriptor))
            sb.append(')')
            val ret = Type.getReturnType(desc)
            sb.append(if (ret.sort == Type.VOID) "V" else erase(ret.descriptor))
            return sb.toString()
        }

        /** A field handle's desc is a FIELD descriptor, which [eraseMethodDesc] would read off the end of. Unreachable today. */
        fun eraseHandleDesc(tag: Int, desc: String): String = if (tag <= Opcodes.H_PUTSTATIC) erase(desc) else eraseMethodDesc(desc)
    }
}
