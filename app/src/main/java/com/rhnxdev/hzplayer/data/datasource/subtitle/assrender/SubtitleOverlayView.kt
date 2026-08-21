package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay view that draws rendered subtitle bitmaps
 * on top of the video surface. Uses solid atomic buffer swaps to eliminate flicker.
 */
class SubtitleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    @Volatile
    private var displayBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun updateBitmap(source: Bitmap) {
        displayBitmap = source
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        if (displayBitmap == null) return
        displayBitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val bmp = displayBitmap ?: return
        if (bmp.isRecycled) return

        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()

        val dstLeft: Int
        val dstTop: Int
        val dstRight: Int
        val dstBottom: Int

        if (bmpAspect > viewAspect) {
            val scaledH = (width / bmpAspect).toInt()
            val offsetY = (height - scaledH) / 2
            dstLeft   = 0
            dstTop    = offsetY
            dstRight  = width
            dstBottom = offsetY + scaledH
        } else {
            val scaledW = (height * bmpAspect).toInt()
            val offsetX = (width - scaledW) / 2
            dstLeft   = offsetX
            dstTop    = 0
            dstRight  = offsetX + scaledW
            dstBottom = height
        }

        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(dstLeft, dstTop, dstRight, dstBottom)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }
}
