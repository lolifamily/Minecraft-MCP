package org.js.lolifamily.minecraftmcp.compat

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.renderer.GameRenderer
import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.repl.Mappings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Cross-version seam for `Screenshot#takeScreenshot` and the main `RenderTarget`'s accessor. Both are
 * reflective, for different reasons — neither can be inlined later:
 *
 *  - **`takeScreenshot`: a compile-time break.** Synchronous through 1.21.4, asynchronous from 1.21.5. One
 *    source tree is compiled against every node, so neither shape exists on all of them. 1.21.6's
 *    `(RenderTarget, int, Consumer)` overload is ignored on purpose: that downscale is GPU-side and absent
 *    from the older nodes, so scaling stays the caller's job.
 *  - **The accessor: a runtime break.** `Minecraft#getMainRenderTarget` compiles on every node, but at 26.2
 *    it moved to `GameRenderer` — inside the 26.1.2 node's open-ended `[26.1.2,)` range.
 *
 * Matched by SHAPE, never by name, as [CommandsCompat]: name strings aren't reobf'd, so `"takeScreenshot"`
 * would stay mojmap and miss on Fabric intermediary / Forge SRG, while the `Class` references below are
 * remapped by the build. That also means no [Mappings] dependency, so this works before the runtime name
 * table loads — unlike [ClientCommandCompat].
 *
 * Each shape matches exactly one method: every `grab` overload takes a `File` or (26.2) a `Minecraft` first,
 * and a no-arg method returning `RenderTarget` is unique on both owners in every version 1.18.2 .. 26.2.
 */
object ScreenshotCompat {

    /** 1.18.2 .. 1.21.4. */
    private val syncTake: Method? by lazy {
        Screenshot::class.java.methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == RenderTarget::class.java
        }
    }

    /** 1.21.5 .. 26.2, unchanged the whole way. */
    private val asyncTake: Method? by lazy {
        Screenshot::class.java.methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == RenderTarget::class.java &&
                it.parameterTypes[1] == Consumer::class.java
        }
    }

    private val accessor: ((Minecraft) -> RenderTarget?)? by lazy { resolveAccessor() }

    /**
     * Take a screenshot of the main render target and hand the image to [consumer]. **Render thread only.**
     *
     * [consumer] runs inline through 1.21.4; from 1.21.5 the readback sits behind a GPU fence and vanilla's
     * `RenderSystem.executePendingTasks` delivers it a frame or two later — on the render thread either way.
     * It owns the [NativeImage]: copy what's needed and close it. It **must not throw** — on the async path
     * it runs inside a vanilla fenced task, so a throwable escapes into the game loop.
     *
     * @return `false` if this runtime has neither shape, or no main render target yet. `true` means the
     *         request was issued, not that [consumer] has run.
     * @throws Throwable whatever `takeScreenshot` threw: `IllegalStateException` for a target with no colour
     *         texture, `IllegalArgumentException` for a readback it can't shape.
     */
    fun request(mc: Minecraft, consumer: (NativeImage) -> Unit): Boolean {
        val target = accessor?.invoke(mc) ?: return false

        asyncTake?.let { m ->
            call(m, null, target, Consumer<NativeImage> { consumer(it) })
            return true
        }
        val m = syncTake ?: return false
        val image = call(m, null, target) as? NativeImage? ?: return false
        consumer(image)
        return true
    }

    /** Order is load-bearing: through 26.1.2 both owners can answer and `Minecraft`'s is the long-standing
     *  public one; on 26.2 it is gone and the lookup falls through to where the field now lives. */
    private fun resolveAccessor(): ((Minecraft) -> RenderTarget?)? {
        noArgRenderTarget(Minecraft::class.java)?.let { m ->
            return { mc -> call(m, mc) as RenderTarget? }
        }
        // Only the accessor moved — `gameRenderer` itself is a field on every version we target, so reading
        // it is an ordinary remapped reference, not another lookup.
        noArgRenderTarget(GameRenderer::class.java)?.let { m ->
            return { mc -> call(m, mc.gameRenderer) as RenderTarget? }
        }
        Constants.LOG.warn("[compat] no no-arg RenderTarget accessor on Minecraft or GameRenderer — screenshots unavailable")
        return null
    }

    private fun noArgRenderTarget(owner: Class<*>): Method? =
        owner.methods.firstOrNull { it.parameterCount == 0 && it.returnType == RenderTarget::class.java }

    /** [receiver] is null for the static `takeScreenshot`. Unwraps like [CommandsCompat], so the game's own
     *  failure surfaces instead of the reflection wrapper. */
    private fun call(m: Method, receiver: Any?, vararg args: Any?): Any? = try {
        m.invoke(receiver, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }
}
