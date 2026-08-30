package org.js.lolifamily.minecraftmcp.patch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the advice parameter ByteBuddy binds to the patch's registry slot, inlined as a constant beside
 * {@link PatchKey} — so dispatch is an array index rather than a lookup on the key. Both are minted by the
 * same install, which is what keeps them from ever naming different patches.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PatchSlot {
}
