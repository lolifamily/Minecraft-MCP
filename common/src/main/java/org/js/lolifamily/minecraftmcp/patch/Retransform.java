package org.js.lolifamily.minecraftmcp.patch;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * The one call the batch unweave cannot make from Kotlin without paying for it: {@code retransformClasses} is
 * varargs, and Kotlin's spread reallocates the array in front of every such call. Java hands the same array
 * straight through, so the batch reaches the JVM as the caller built it.
 */
final class Retransform {

    private Retransform() {
    }

    /** {@link Instrumentation#retransformClasses}, by array rather than by spread. */
    static void of(Instrumentation inst, Class<?>[] classes) throws UnmodifiableClassException {
        inst.retransformClasses(classes);
    }
}
