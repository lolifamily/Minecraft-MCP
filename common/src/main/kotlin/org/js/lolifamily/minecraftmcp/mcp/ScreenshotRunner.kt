package org.js.lolifamily.minecraftmcp.mcp

import net.minecraft.client.Minecraft
import org.js.lolifamily.minecraftmcp.compat.ScreenshotCompat
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Captures the game's framebuffer for the `take_screenshot` tool.
 *
 * The render thread's whole contribution is one pixel-array copy; scaling and encoding run on the calling
 * (HTTP) thread, so a screenshot costs the game a texture readback and nothing else. The hop over is
 * vanilla's own `Minecraft#execute` rather than a lane — there is no tick affinity to respect here, just a
 * thread requirement.
 *
 * JPEG at a fixed size and quality, with nothing exposed to the caller: both are questions only whoever
 * consumes the image can answer, and that is a model with no idea what resolution it can actually see. The
 * cost of getting them wrong is ours, so the numbers are ours.
 *
 * Loads only when the client path is actually taken, so it never touches client classes on a dedicated
 * server. `Minecraft.getInstance()` is non-null from mod init on, but half-built until its constructor
 * returns — the `execute` hop is what makes that safe, not `Lanes.RENDER.isReady`, which is a physical-side
 * constant and would not catch a render-state read placed ahead of it.
 */
internal object ScreenshotRunner {

    const val MIME = "image/jpeg"

    /** High enough that Minecraft's UI text survives the chroma subsampling. */
    private const val QUALITY = 0.85f

    // A vision model charges for an image by AREA, not by edge, and rescales anything past these before it
    // looks at it — so pixels beyond them are bytes spent on detail that is thrown away unseen.
    private const val MAX_PIXELS = 3_750_656.0

    /** Pixels as [ScreenshotCompat] handed them over: ARGB, top-down, row-major — [BufferedImage.setRGB]'s
     *  own layout. Filled by the deprecated `NativeImage#makePixelArray`, used anyway because it is the only
     *  whole-image accessor 1.18.2 has; every replacement arrived later than the versions we target. */
    private class Raw(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Take a screenshot and encode it. Blocks until the image lands.
     *
     * No timeout, matching `execute_code` on the same lane: the render thread has no terminal boundary short
     * of JVM exit, so a wait that never ends means a game that has already stopped — and the HTTP pool's
     * threads are daemons. A timeout would only misfire on the honest slow cases, where frames are seconds
     * apart because the world is loading.
     *
     * @return the encoded image, or null if this runtime exposes no usable `takeScreenshot` or has no render
     *         target yet.
     */
    @Suppress("DEPRECATION") // makePixelArray — see [Raw]
    fun grab(): ByteArray? {
        val future = CompletableFuture<Raw?>()
        val mc = Minecraft.getInstance()
        mc.execute {
            try {
                val issued = ScreenshotCompat.request(mc) { image ->
                    // Nothing may escape this block: from 1.21.5 it runs inside a vanilla fenced task, where a
                    // throwable would land in the game loop instead of on the caller.
                    try {
                        future.complete(Raw(image.makePixelArray(), image.width, image.height))
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
                    } finally {
                        image.close()
                    }
                }
                if (!issued) future.complete(null)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }

        val raw = future.get() ?: return null
        return encode(raw)
    }

    private fun encode(raw: Raw): ByteArray {
        // TYPE_INT_RGB, not ARGB: ImageIO's JPEG writer mangles a 4-channel raster, so alpha goes here.
        val image = BufferedImage(raw.width, raw.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raw.width, raw.height, raw.pixels, 0, raw.width)
        return writeJpeg(downscale(image))
    }

    /**
     * Scaling on this thread, deliberately: the GPU-side downscale `takeScreenshot` grew at 1.21.6 would cost
     * a version seam and does not exist on the older nodes.
     */
    private fun downscale(src: BufferedImage): BufferedImage {
        val scale = sqrt(MAX_PIXELS / (src.width.toDouble() * src.height))
        if (scale >= 1.0) return src
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)

        val dst = BufferedImage(w, h, src.type)
        dst.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return dst
    }

    /** The long form, because `ImageIO.write` gives no way to set the quality. */
    private fun writeJpeg(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
