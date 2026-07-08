package com.ethran.notable.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Cover/ink preview only (no text). Real TextViews handle titles.
 * Always fills a fixed [widthPx]×[heightPx] frame via center-crop so every tile is the same size.
 */
object WidgetCardRenderer {

    /** Matches `GridCells.Adaptive(140.dp)` in HomeView (used for column count). */
    const val BASE_CARD_WIDTH_DP = 140f

    private const val PREVIEW_HEIGHT_RATIO = 4f / 3f
    private const val BORDER_COLOR = 0xFF666666.toInt()
    private const val PLACEHOLDER_BG = 0x59CCCCCC

    fun previewHeightPx(widthPx: Int): Int =
        (widthPx * PREVIEW_HEIGHT_RATIO).toInt().coerceAtLeast(1)

    fun renderPreview(
        context: Context,
        capture: WidgetCapture?,
        widthPx: Int,
        density: Float,
        heightPx: Int = previewHeightPx(widthPx)
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val border = (1f * density).coerceAtLeast(1f)
        val dest = Rect(
            border.toInt(),
            border.toInt(),
            width - border.toInt(),
            height - border.toInt()
        )
        val frame = RectF(border / 2f, border / 2f, width - border / 2f, height - border / 2f)

        if (capture == null) {
            drawPlaceholder(context, canvas, frame)
            drawBorder(canvas, frame, border)
            return output
        }

        val source = capture.thumbnailPath?.let {
            loadSourceBitmap(it, maxSide = maxOf(width, height))
        }
        if (source != null) {
            // Same fill for covers and ink so every tile is identical framing.
            drawCenterCrop(canvas, source, dest)
            source.recycle()
        } else {
            drawPlaceholder(context, canvas, frame)
        }

        drawBorder(canvas, frame, border)

        if (capture.isPinned) {
            val pin = (10 * density).toInt().coerceAtLeast(6)
            val margin = (6 * density).toInt().coerceAtLeast(3)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            canvas.drawRect(
                (width - margin - pin).toFloat(),
                margin.toFloat(),
                (width - margin).toFloat(),
                (margin + pin).toFloat(),
                fill
            )
        }

        return output
    }

    private fun drawBorder(canvas: Canvas, frame: RectF, border: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = BORDER_COLOR
            strokeWidth = border
        }
        canvas.drawRect(frame, stroke)
    }

    private fun loadSourceBitmap(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = maxOf(1, largest / maxSide.coerceAtLeast(1))
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun drawCenterCrop(canvas: Canvas, source: Bitmap, dest: Rect) {
        val scale = maxOf(
            dest.width().toFloat() / source.width,
            dest.height().toFloat() / source.height
        )
        val sw = (dest.width() / scale).toInt().coerceAtMost(source.width).coerceAtLeast(1)
        val sh = (dest.height() / scale).toInt().coerceAtMost(source.height).coerceAtLeast(1)
        val sx = (source.width - sw) / 2
        val sy = (source.height - sh) / 2
        canvas.drawBitmap(source, Rect(sx, sy, sx + sw, sy + sh), dest, null)
    }

    private fun drawPlaceholder(context: Context, canvas: Canvas, frame: RectF) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = PLACEHOLDER_BG
        }
        canvas.drawRect(frame, fill)
        val iconSize = (frame.width() * 0.28f).toInt().coerceIn(24, 64)
        val drawable = ContextCompat.getDrawable(context, com.ethran.notable.R.drawable.pencil)
            ?: return
        val icon = drawable.toBitmap(iconSize, iconSize)
        canvas.drawBitmap(
            icon,
            frame.centerX() - iconSize / 2f,
            frame.centerY() - iconSize / 2f,
            null
        )
        icon.recycle()
    }
}
