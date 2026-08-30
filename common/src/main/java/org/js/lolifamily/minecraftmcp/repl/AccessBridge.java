package org.js.lolifamily.minecraftmcp.repl;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Bootstrap methods for {@code invokedynamic} call sites emitted by the access-widening bytecode
 * transform in {@code ScriptWeave}. Each bootstrap resolves a private/protected/package-private member
 * via {@link MethodHandles#privateLookupIn} and returns a {@link ConstantCallSite}, which the JIT
 * can inline.
 *
 * <p>This class lives on the <b>game loader</b> (not {@code impl/}), so script classes loaded by
 * the masking loader can resolve it via parent delegation — same cross-loader pattern as
 * {@link ReplBridge} and {@code PatchEnterCallback}.
 *
 * <p>The call-site {@code MethodType} is not the real signature. Widening a member's access is only
 * half the job: a call site whose descriptor names an inaccessible class is rejected while the JVM
 * resolves that descriptor, before any bootstrap here runs (e.g. a descriptor naming package-private
 * {@code kotlin.collections.EmptyMap} is rejected). So the transform erases every inaccessible
 * reference type in the call-site descriptor to {@link Object} and passes the real names through as
 * string bootstrap arguments instead.
 *
 * <p>Two consequences for every bootstrap below:
 * <ul>
 *   <li>{@code type} cannot be trusted for the member's real type — the field bootstraps read it
 *       reflectively via {@link #findField}, the method bootstraps take it from the {@code desc} argument.</li>
 *   <li>Every returned handle is {@link MethodHandle#asType}'d to {@code type}, which inserts the casts
 *       between the erased {@code Object} view and the real types.</li>
 * </ul>
 */
// Every public method here is a JVM bootstrap method, linked from generated invokedynamic
// bytecode rather than from Java source, and its fixed (Lookup, String invokedName, MethodType,
// ...) signature forces the unused invokedName parameter — hence the class-wide suppression.
@SuppressWarnings("unused")
public final class AccessBridge {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private AccessBridge() {}

    private static Class<?> resolve(String owner, MethodHandles.Lookup caller) throws ClassNotFoundException {
        return Class.forName(owner.replace('/', '.'), false, caller.lookupClass().getClassLoader());
    }

    /**
     * {@link MethodHandles#privateLookupIn} on {@code cls}, or {@code caller} where its module refuses to open —
     * java.base being the one that does.
     *
     * <p>{@link #findMethodOwner} and {@link #findField} resolve onto the DECLARING class, which for an inherited
     * member is not the call-site owner {@code ScriptWeave}'s opaque-package filter saw: a snippet subclassing a
     * JDK type reaches its own {@code protected} members through here. Falling back to {@code caller}, the call
     * site's own access, is what keeps the bridge from granting LESS than the bytecode it replaced;
     * {@link #LOOKUP} is related to neither class and refuses.
     */
    private static MethodHandles.Lookup lookupIn(Class<?> cls, MethodHandles.Lookup caller) {
        try {
            return MethodHandles.privateLookupIn(cls, LOOKUP);
        } catch (IllegalAccessException e) {
            return caller;
        }
    }

    /**
     * The field {@code ref} resolves {@code field} to. The transform emits the reference-site owner, but a
     * private field can be declared on a superclass, where {@code privateLookupIn(subclass)} cannot reach it.
     * Interfaces are searched last, transitively, for constants.
     *
     * <p>{@code wantStatic} is the CALL SITE's kind — GETFIELD wants an instance field, GETSTATIC a static one
     * — and a mismatch keeps walking. Load-bearing on an obf runtime, where proguard reuses one name across a
     * hierarchy: {@code BlockPos.CODEC} and the {@code Vec3i.x} it inherits are both {@code a}.
     */
    private static Field findField(Class<?> ref, String field, boolean wantStatic) throws NoSuchFieldException {
        for (Class<?> c = ref; c != null; c = c.getSuperclass()) {
            Field f = declaredField(c, field, wantStatic);
            if (f != null) return f;
        }
        for (Class<?> i : interfaceClosure(ref)) {
            Field f = declaredField(i, field, wantStatic);
            if (f != null) return f;
        }
        throw new NoSuchFieldException(ref + "." + field);
    }

    /** {@code c}'s own {@code field} of the wanted kind, or null. */
    private static Field declaredField(Class<?> c, String field, boolean wantStatic) {
        try {
            Field f = c.getDeclaredField(field);
            return Modifier.isStatic(f.getModifiers()) == wantStatic ? f : null;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    /**
     * The class in {@code ref}'s hierarchy that declares {@code method} with {@code mt}'s parameter types —
     * the method analogue of {@link #findField}. Falls back to {@code ref} when nothing matches.
     */
    private static Class<?> findMethodOwner(Class<?> ref, String method, MethodType mt) {
        Class<?>[] params = mt.parameterArray();
        for (Class<?> c = ref; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(method, params);
                return c;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Class<?> i : interfaceClosure(ref)) {
            try {
                i.getDeclaredMethod(method, params);
                return i;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return ref;
    }

    /** Every interface reachable from {@code ref}, breadth-first. */
    private static Iterable<Class<?>> interfaceClosure(Class<?> ref) {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> c = ref; c != null; c = c.getSuperclass()) {
            Collections.addAll(queue, c.getInterfaces());
        }
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        while (!queue.isEmpty()) {
            Class<?> i = queue.poll();
            if (!seen.add(i)) continue;
            out.add(i);
            Collections.addAll(queue, i.getInterfaces());
        }
        return out;
    }

    // ---- field bootstraps -------------------------------------------------------------------

    /** Bootstrap a {@code GETFIELD} call site. */
    public static CallSite fieldGet(MethodHandles.Lookup caller, String invokedName,
                                    MethodType type, String owner, String field) throws Throwable {
        java.lang.reflect.Field f = findField(resolve(owner, caller), field, false);
        Class<?> cls = f.getDeclaringClass();
        MethodHandle mh = lookupIn(cls, caller).findGetter(cls, field, f.getType());
        return new ConstantCallSite(mh.asType(type));
    }

    /** Bootstrap a {@code PUTFIELD} call site. */
    public static CallSite fieldSet(MethodHandles.Lookup caller, String invokedName,
                                    MethodType type, String owner, String field) throws Throwable {
        java.lang.reflect.Field f = findField(resolve(owner, caller), field, false);
        Class<?> cls = f.getDeclaringClass();
        MethodHandle mh = lookupIn(cls, caller).findSetter(cls, field, f.getType());
        return new ConstantCallSite(mh.asType(type));
    }

    /** Bootstrap a {@code GETSTATIC} call site. */
    public static CallSite staticFieldGet(MethodHandles.Lookup caller, String invokedName,
                                          MethodType type, String owner, String field) throws Throwable {
        java.lang.reflect.Field f = findField(resolve(owner, caller), field, true);
        Class<?> cls = f.getDeclaringClass();
        MethodHandle mh = lookupIn(cls, caller).findStaticGetter(cls, field, f.getType());
        return new ConstantCallSite(mh.asType(type));
    }

    /** Bootstrap a {@code PUTSTATIC} call site. */
    public static CallSite staticFieldSet(MethodHandles.Lookup caller, String invokedName,
                                          MethodType type, String owner, String field) throws Throwable {
        java.lang.reflect.Field f = findField(resolve(owner, caller), field, true);
        Class<?> cls = f.getDeclaringClass();
        MethodHandle mh = lookupIn(cls, caller).findStaticSetter(cls, field, f.getType());
        return new ConstantCallSite(mh.asType(type));
    }

    // ---- method bootstraps ------------------------------------------------------------------

    /** Bootstrap an {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} call site; the real signature is in
     *  {@code desc}, the declaring class resolved via {@link #findMethodOwner}. */
    public static CallSite virtualCall(MethodHandles.Lookup caller, String invokedName,
                                       MethodType type, String owner, String method,
                                       String desc) throws Throwable {
        MethodType mt = MethodType.fromMethodDescriptorString(desc, caller.lookupClass().getClassLoader());
        Class<?> cls = findMethodOwner(resolve(owner, caller), method, mt);
        MethodHandle mh;
        try {
            mh = lookupIn(cls, caller).findVirtual(cls, method, mt);
        } catch (NoSuchMethodException e) {
            mh = backingField(cls, method, mt, true, caller, e);
        }
        return new ConstantCallSite(mh.asType(type));
    }

    /**
     * A Kotlin {@code private val} has a backing field and no accessor, yet the metadata flip makes the
     * frontend emit {@code getXxx()} against it — resolve such a call to the field instead. An {@code object}
     * or top-level property backs onto a STATIC field, so {@code hasReceiver} says whether the call site
     * passes one that the handle has to swallow.
     *
     * <p>Matched field-to-accessor, never the reverse: the mapping is not injective, {@code setEmpty} being
     * the setter of both {@code empty} and {@code isEmpty}.
     */
    private static MethodHandle backingField(Class<?> cls, String method, MethodType mt, boolean hasReceiver,
                                             MethodHandles.Lookup caller, NoSuchMethodException cause) throws Throwable {
        boolean get = mt.parameterCount() == 0 && mt.returnType() != void.class;
        boolean set = mt.parameterCount() == 1 && mt.returnType() == void.class;
        if (!get && !set) throw cause;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName();
                if (!method.equals(get ? getterName(n) : setterName(n))) continue;
                MethodHandles.Lookup lk = lookupIn(c, caller);
                if (!Modifier.isStatic(f.getModifiers())) {
                    if (!hasReceiver) throw cause;   // a static call site has no instance to read from
                    return get ? lk.findGetter(c, n, f.getType()) : lk.findSetter(c, n, f.getType());
                }
                MethodHandle mh = get ? lk.findStaticGetter(c, n, f.getType())
                        : lk.findStaticSetter(c, n, f.getType());
                return hasReceiver ? MethodHandles.dropArguments(mh, 0, c) : mh;
            }
        }
        throw cause;   // not a property accessor either — report the call that was actually made
    }

    /** {@code JvmAbi.getterName}: an {@code is}-prefixed property is already its own getter. */
    private static String getterName(String prop) {
        return isPrefixed(prop) ? prop : "get" + capitalize(prop);
    }

    /** {@code JvmAbi.setterName}: the {@code is} is dropped rather than kept — {@code isEmpty} sets via {@code setEmpty}. */
    private static String setterName(String prop) {
        return "set" + (isPrefixed(prop) ? prop.substring(2) : capitalize(prop));
    }

    /** {@code JvmAbi.startsWithIsPrefix}: {@code is} followed by anything that is not a lower-case letter. */
    private static boolean isPrefixed(String name) {
        if (!name.startsWith("is") || name.length() == 2) return false;
        char c = name.charAt(2);
        return c < 'a' || c > 'z';
    }

    /** Kotlin capitalizes ASCII only, so {@code URL} and {@code _options} keep their head. */
    private static String capitalize(String s) {
        char c = s.isEmpty() ? ' ' : s.charAt(0);
        return c >= 'a' && c <= 'z' ? Character.toUpperCase(c) + s.substring(1) : s;
    }

    /** Bootstrap an {@code INVOKESTATIC} call site; the real signature is in {@code desc}, the declaring
     *  class resolved via {@link #findMethodOwner}. */
    public static CallSite staticCall(MethodHandles.Lookup caller, String invokedName,
                                      MethodType type, String owner, String method,
                                      String desc) throws Throwable {
        MethodType mt = MethodType.fromMethodDescriptorString(desc, caller.lookupClass().getClassLoader());
        Class<?> cls = findMethodOwner(resolve(owner, caller), method, mt);
        MethodHandle mh;
        try {
            mh = lookupIn(cls, caller).findStatic(cls, method, mt);
        } catch (NoSuchMethodException e) {
            mh = backingField(cls, method, mt, false, caller, e);   // a top-level `private val` is a static field
        }
        return new ConstantCallSite(mh.asType(type));
    }
}
