package com.example.depthwallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Draws the three layers, back to front:
 *   1. background photo
 *   2. clock
 *   3. subject cutout (same pixels as the background, transparent elsewhere)
 *
 * Used by both the live wallpaper engine and the in-app preview, so they always match.
 * Not thread-safe: call from the main thread only (it reuses Paint objects).
 */
object DepthComposer {

    // Slight zoom so parallax shifting never reveals an empty edge.
    private const val ZOOM = 1.05f
    private const val IMAGE_SHIFT_DP = 8f     // photo + cutout move together
    private const val CLOCK_SHIFT_DP = -12f   // clock moves against the photo -> depth feel

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bounds = Rect()

    fun draw(
        canvas: Canvas,
        w: Int,
        h: Int,
        bg: Bitmap?,
        fg: Bitmap?,
        cfg: DepthConfig,
        nowMs: Long,
        is24h: Boolean,
        locale: Locale,
        tiltX: Float,   // -1..1
        tiltY: Float,   // -1..1
        density: Float,
    ) {
        canvas.drawColor(0xFF0E0E12.toInt())

        val imgDx = tiltX * IMAGE_SHIFT_DP * density
        val imgDy = tiltY * IMAGE_SHIFT_DP * density

        if (bg != null) drawLayer(canvas, bg, w, h, imgDx, imgDy)
        drawClock(
            canvas, w, h, cfg, nowMs, is24h, locale,
            dx = tiltX * CLOCK_SHIFT_DP * density,
            dy = tiltY * CLOCK_SHIFT_DP * density,
        )
        if (fg != null) drawLayer(canvas, fg, w, h, imgDx, imgDy)
    }

    private fun drawLayer(canvas: Canvas, bmp: Bitmap, w: Int, h: Int, dx: Float, dy: Float) {
        val scale = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height) * ZOOM
        val left = (w - bmp.width * scale) / 2f + dx
        val top = (h - bmp.height * scale) / 2f + dy
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
        canvas.restore()
    }

    private fun drawClock(
        canvas: Canvas,
        w: Int,
        h: Int,
        cfg: DepthConfig,
        nowMs: Long,
        is24h: Boolean,
        locale: Locale,
        dx: Float,
        dy: Float,
    ) {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val hText = if (is24h) hour.toString().padStart(2, '0') else (((hour + 11) % 12) + 1).toString()
        val mText = cal.get(Calendar.MINUTE).toString().padStart(2, '0')

        // Fit the digits block to the requested width.
        val sample = if (cfg.style == ClockStyle.STACKED) "00" else "00:00"
        clockPaint.color = cfg.colorArgb
        clockPaint.textSize = 100f
        clockPaint.textSize = 100f * (w * cfg.clockWidthFraction) / clockPaint.measureText(sample)
        clockPaint.getTextBounds("0", 0, 1, bounds)
        val dh = bounds.height().toFloat()          // digit height

        val cx = w / 2f + dx
        val cy = h * cfg.clockCenterY + dy

        val top: Float = when (cfg.style) {
            ClockStyle.STACKED -> {
                val gap = dh * 0.14f
                val t = cy - (dh * 2f + gap) / 2f
                canvas.drawText(hText, cx, t + dh, clockPaint)
                canvas.drawText(mText, cx, t + dh * 2f + gap, clockPaint)
                t
            }
            ClockStyle.INLINE -> {
                val t = cy - dh / 2f
                canvas.drawText("$hText:$mText", cx, t + dh, clockPaint)
                t
            }
        }

        if (cfg.showDate) {
            datePaint.color = cfg.colorArgb
            datePaint.alpha = (Color.alpha(cfg.colorArgb) * 0.9f).toInt()
            datePaint.textSize = w * 0.045f
            val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
            val date = SimpleDateFormat(pattern, locale).format(Date(nowMs))
            canvas.drawText(date, cx, top - datePaint.textSize * 0.9f, datePaint)
        }
    }
}
