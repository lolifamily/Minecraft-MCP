package org.js.lolifamily.minecraftmcp.repl.impl

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import kotlin.metadata.ClassName
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.localDelegatedProperties
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature

// ============================================================================================
// `@kotlin.Metadata` plumbing shared by BOTH remap directions — the compile-classpath overlay
// (runtime -> mojmap, ClasspathWiden) and the compiled-snippet weave (mojmap -> runtime, ScriptRemap).
//
// A Kotlin class carries its types TWICE: in the bytecode, and in the `@Metadata` proto that kotlin-reflect
// reads instead of the bytecode. A rename that touches only the first leaves the two disagreeing — reflection
// then resolves classifiers that do not exist on this runtime, and degrades to `???` or throws deep inside
// kotlin-reflect with the original name nowhere in the message.
//
// Only what is genuinely direction-free lives here: the annotation capture, the proto walk, and the re-emit
// rule. Each side keeps its own ASM plumbing and its own read/dispatch/write driver, because those are NOT
// the same code — the overlay also opens visibilities and realigns moduleName, and the weave does neither.
// ============================================================================================

/** Captures a `@kotlin.Metadata` annotation during an ASM read, and re-emits it — verbatim, or as a rewritten
 *  replacement. Stores ONLY the fields the original actually carried, which is what makes a verbatim re-emit
 *  add nothing: a lambda class (k=3) carries no d1, and an empty one reads back as a deserializable proto,
 *  NPEing readFunctionDataFrom deep inside the inliner. Primitive arrays (`mv`/`bv`) arrive as one value;
 *  string arrays (`d1`/`d2`) arrive element-by-element via a nested visitArray visitor. */
internal class MetaCapture : AnnotationVisitor(Opcodes.ASM9) {
    /** Field name (`@Metadata`'s JvmNames: k/mv/bv/d1/d2/xs/pn/xi) -> value; string arrays held as List. */
    private val fields = LinkedHashMap<String, Any?>()

    val kind: Int get() = fields["k"] as? Int ?: 1

    override fun visit(name: String?, value: Any?) { if (name != null) fields[name] = value }

    override fun visitArray(name: String?): AnnotationVisitor? {
        if (name == null) return null
        val list = ArrayList<String>().also { fields[name] = it }
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(n: String?, v: Any?) { list.add(v as String) }
        }
    }

    /** Which fields the original annotation actually carried — the set a rewrite must reproduce one-for-one. */
    fun fieldNames(): Set<String> = fields.keys

    /** What a rewrite cannot reproduce, read off the original — see [fieldOr]. */
    fun original(name: String): Any? = fields[name]

    /** The captured annotation as a real [Metadata], for kotlin-metadata to read. */
    fun toMetadata(): Metadata = kotlin.metadata.jvm.Metadata(
        kind = fields["k"] as Int?,
        metadataVersion = fields["mv"] as IntArray?,
        data1 = strings("d1"), data2 = strings("d2"),
        extraString = fields["xs"] as String?,
        packageName = fields["pn"] as String?,
        extraInt = fields["xi"] as Int?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun strings(n: String): Array<String>? = (fields[n] as ArrayList<String>?)?.toTypedArray()

    /** Emit [replacement] (a rewrite) or the captured fields verbatim into [av]. */
    fun emit(av: AnnotationVisitor, replacement: Map<String, Any?>?) {
        for ((n, v) in replacement ?: fields) {
            if (v is List<*>) av.visitArray(n).apply { v.forEach { visit(null, it) }; visitEnd() } else av.visit(n, v)
        }
        av.visitEnd()
    }
}

/** `@Metadata`'s accessors keyed by JvmName (k/mv/bv/d1/d2/xs/pn/xi). Read off the annotation type so the
 *  field list — and every field's declared default — is never maintained by hand here. */
private val METADATA_FIELDS: Map<String, java.lang.reflect.Method> =
    Metadata::class.java.declaredMethods.filter { it.parameterCount == 0 }.associateBy { it.name }

/**
 * [name]'s value on this written [Metadata], or [fallback] where the write left the declared default — which
 * means it does not model that field.  Arrays come back as List, ready for an ASM `visitArray`.
 *
 * The emit rule both sides share: a rewrite RE-ENCODES, it does not restructure, so emit exactly the fields
 * the original carried. One added reads as "there is data here" (see [MetaCapture]); one dropped is worse — a
 * missing `k` makes the whole class read as Java, taking every Kotlin member with it. Where `write()` left a
 * field at its default it does not model that field at all (it passes neither xs nor pn for k=1/2), so the
 * original's value stands.
 */
internal fun Metadata.fieldOr(name: String, fallback: Any?): Any? {
    fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is Array<*> && b is Array<*> -> a.contentEquals(b)
        else -> a == b
    }
    val m = METADATA_FIELDS[name] ?: return fallback
    val v = m.invoke(this)
    if (sameValue(v, m.defaultValue)) return fallback
    return if (v is Array<*>) v.toList() else v
}

/**
 * Rewrites a `@Metadata` proto so it agrees with the bytecode [r] just rewrote. One walk serves both
 * directions: the direction is [r]'s business and none of this walk's.
 *
 * No direction flag, because member names go through [Remapper.mapMethodName] / [Remapper.mapFieldName] and
 * ASM's base answers those with the name it was given — a class-only remapper opts out by not overriding them.
 * The snippet weave does override them, and must: it renames an override in bytecode (`getDescriptionId` ->
 * `method_7876`), and a proto still spelling the mojmap name is the same disagreement one level down.
 */
// Suppressed, not @OptIn: `contextParameters`' marker is kotlin.ExperimentalContextParameters, a 2.2 STDLIB
// class, and this module pins apiVersion to 2.0 so its classes load on the GAME's kotlin (multiloader-common).
// Naming the marker would put that reference in the class file and raise the mod's FLK floor for everyone;
// suppressing opts in without naming it — the emitted constant pool holds no kotlin.* newer than 1.x.
@Suppress("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE")
internal class KmRemap(private val r: Remapper) {

    /** Rename [c]'s own name, its supertypes, constructors and every member. */
    fun rename(c: KmClass) {
        // Read BEFORE c.name moves: member lookups key on the owner in the SOURCE namespace, and renaming the
        // class first would hand every member below a name the tables have never heard of.
        val owner = c.name.replace('.', '$')
        mapClassName(c.name)?.let { c.name = it }
        c.supertypes.forEach { renameType(it) }
        c.inlineClassUnderlyingType?.let { renameType(it) }
        renameTypeParams(c.typeParameters)
        c.constructors.forEach { ctor ->
            renameParams(ctor.valueParameters)
            // Name left alone on purpose: a constructor is `<init>` in both namespaces, and no table has a row
            // for it — asking would cost a lookup to be told what we already know.
            ctor.signature = ctor.signature?.let { it.copy(descriptor = r.mapMethodDesc(it.descriptor)) }
        }
        c.sealedSubclasses.replaceAll { mapClassName(it) ?: it }
        renameMembers(owner, c.functions, c.properties, c.typeAliases, c.localDelegatedProperties)
    }

    /** Rename every member of a file facade or multi-file part. [owner] is the facade class the members live
     *  on — a [KmPackage] does not carry it, and member lookups need it. */
    fun rename(p: KmPackage, owner: String) = renameMembers(owner, p.functions, p.properties, p.typeAliases, p.localDelegatedProperties)

    /** The type-bearing members of a class or package body — the shape openMembers walks for visibility, plus
     *  [locals], the delegates declared inside a function body (`val x by lazy {}`), which carry a type of their
     *  own and reach the proto through no other list. The JVM signatures go too: they are what the backend and
     *  kotlin-reflect link against, so leaving them in the old namespace while the Kotlin types moved would split
     *  the two views of one member.
     *
     *  `contextReceiverTypes` is deliberately absent: the reader stopped populating it, folding that legacy
     *  feature into `contextParameters`, which is walked. */
    private fun renameMembers(
        owner: String,
        fns: List<KmFunction>,
        props: List<KmProperty>,
        tas: List<KmTypeAlias>,
        locals: List<KmProperty>,
    ) {
        fns.forEach { f ->
            renameType(f.returnType)
            f.receiverParameterType?.let { renameType(it) }
            renameParams(f.valueParameters)
            renameParams(f.contextParameters)
            renameTypeParams(f.typeParameters)
            f.signature = f.signature?.let { mapMethod(owner, it) }
        }
        props.forEach { renameProperty(owner, it) }
        locals.forEach { renameProperty(owner, it) }
        tas.forEach { a ->
            renameType(a.underlyingType)
            renameType(a.expandedType)
            renameTypeParams(a.typeParameters)
            a.annotations.replaceAll(::renameAnnotation)
        }
    }

    private fun renameProperty(owner: String, p: KmProperty) {
        renameType(p.returnType)
        p.receiverParameterType?.let { renameType(it) }
        p.setterParameter?.let { renameParams(listOf(it)) }
        renameParams(p.contextParameters)
        renameTypeParams(p.typeParameters)
        p.getterSignature = p.getterSignature?.let { mapMethod(owner, it) }
        p.setterSignature = p.setterSignature?.let { mapMethod(owner, it) }
        p.fieldSignature = p.fieldSignature?.let {
            it.copy(name = r.mapFieldName(owner, it.name, it.descriptor), descriptor = r.mapDesc(it.descriptor))
        }
    }

    /** Both halves of a method signature, each read off the ORIGINAL: the name is looked up BY the old
     *  descriptor, so mapping the descriptor first would ask the tables about a signature that never existed. */
    private fun mapMethod(owner: String, s: kotlin.metadata.jvm.JvmMethodSignature) =
        s.copy(name = r.mapMethodName(owner, s.name, s.descriptor), descriptor = r.mapMethodDesc(s.descriptor))

    /** The [ClassName] form of [Remapper.map]: metadata separates nested classes with '.' where bytecode uses
     *  '$', and a leading '.' marks a local class, which is never a mapped name. Null when unmapped — the
     *  identity a [Remapper] answers with instead is not a usable "no" for a caller that must leave the field
     *  untouched. */
    private fun mapClassName(name: ClassName): ClassName? {
        if (name.startsWith(".")) return null
        val src = name.replace('.', '$')
        val dst = r.map(src)
        return if (dst == src) null else dst.replace('$', '.')
    }

    /** Rewrite every classifier reachable from [t]. Types nest through arguments, abbreviation, outer type and
     *  the flexible upper bound, so touching the classifier alone would miss most of a generic signature. */
    private fun renameType(t: KmType) {
        when (val c = t.classifier) {
            is KmClassifier.Class -> mapClassName(c.name)?.let { t.classifier = KmClassifier.Class(it) }
            is KmClassifier.TypeAlias -> mapClassName(c.name)?.let { t.classifier = KmClassifier.TypeAlias(it) }
            is KmClassifier.TypeParameter -> Unit
        }
        t.arguments.forEach { a -> a.type?.let { renameType(it) } }
        t.abbreviatedType?.let { renameType(it) }
        t.outerType?.let { renameType(it) }
        t.flexibleTypeUpperBound?.let { renameType(it.type) }
        t.annotations.replaceAll(::renameAnnotation)
    }

    private fun renameParams(ps: List<KmValueParameter>) = ps.forEach { p ->
        renameType(p.type)
        p.varargElementType?.let { renameType(it) }
    }

    private fun renameTypeParams(ps: List<KmTypeParameter>) = ps.forEach { p ->
        p.upperBounds.forEach { renameType(it) }
        p.annotations.replaceAll(::renameAnnotation)
    }

    /** A type-use annotation has nowhere to live in the class file, so the proto is its only carrier — and its
     *  ARGUMENTS name classes just as its own type does. */
    private fun renameAnnotation(a: KmAnnotation) =
        KmAnnotation(mapClassName(a.className) ?: a.className, a.arguments.mapValues { renameArgument(it.value) })

    /** `else` takes the literals, and whatever argument kind a later metadata version adds: passing one through
     *  costs a stale name inside an annotation, throwing on it would cost the whole class. */
    private fun renameArgument(v: KmAnnotationArgument): KmAnnotationArgument = when (v) {
        is KmAnnotationArgument.KClassValue ->
            mapClassName(v.className)?.let { KmAnnotationArgument.KClassValue(it) } ?: v
        is KmAnnotationArgument.ArrayKClassValue ->
            mapClassName(v.className)?.let { KmAnnotationArgument.ArrayKClassValue(it, v.arrayDimensionCount) } ?: v
        is KmAnnotationArgument.EnumValue ->
            mapClassName(v.enumClassName)?.let { KmAnnotationArgument.EnumValue(it, v.enumEntryName) } ?: v
        is KmAnnotationArgument.AnnotationValue -> KmAnnotationArgument.AnnotationValue(renameAnnotation(v.annotation))
        is KmAnnotationArgument.ArrayValue -> KmAnnotationArgument.ArrayValue(v.elements.map { renameArgument(it) })
        else -> v
    }
}
