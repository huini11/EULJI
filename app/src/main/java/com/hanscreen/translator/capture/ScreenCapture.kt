package com.hanscreen.translator.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/**
 * 屏幕镜像抓帧。
 *
 * 关键点：MediaProjection 只在画面变化时产出新帧。若用户停在静态页面，
 * 直接 acquireLatestImage 会拿不到数据。因此常驻监听最新帧并缓存成 Bitmap，
 * 没有新帧时复用上一帧。
 *
 * 使用约定：capture() 返回的 Bitmap 由本类持有，调用方不得 recycle。
 * 服务层用 busy 标志串行化调用，避免用到被回收的实例。
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var pending: Image? = null
    private var cached: Bitmap? = null

    fun start() {
        if (reader != null) return

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            synchronized(lock) {
                val old = pending
                pending = img
                runCatching { old?.close() }
            }
        }, handler)

        display = projection.createVirtualDisplay(
            "hanscreen-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler
        )
    }

    /** 有新帧就转成 Bitmap 缓存，否则返回上一帧 */
    fun capture(): Bitmap? {
        synchronized(lock) {
            val img = pending
            if (img != null) {
                pending = null
                val bmp = runCatching { toBitmap(img) }.getOrNull()
                runCatching { img.close() }
                if (bmp != null) {
                    cached?.let { runCatching { it.recycle() } }
                    cached = bmp
                }
            }
            return cached
        }
    }

    fun hasFrame(): Boolean = synchronized(lock) { cached != null }

    private fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val raw = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            raw
        } else {
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            raw.recycle()
            cropped
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { pending?.close() }
            pending = null
            runCatching { cached?.recycle() }
            cached = null
        }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
    }
}
