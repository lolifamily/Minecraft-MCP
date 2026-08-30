package org.js.lolifamily.minecraftmcp.patch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the advice parameter ByteBuddy binds to the patch key, inlined as a constant via
 * {@code Advice.withCustomMapping().bind(...)} — so the woven value is byte-identical to the value the
 * handler was registered under.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PatchKey {
}
