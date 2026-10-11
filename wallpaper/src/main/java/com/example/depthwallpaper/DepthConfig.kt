package com.example.depthwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

enum class ClockStyle { INLINE, STACKED }

data class DepthConfig(
    val style: ClockStyle = ClockStyle.STACKED,
    /** Vertical centre of the clock, as a fraction of screen height. */
    val clockCenterY: Float = 0.30f,
    /** Width of the digits block, as a fraction of screen width. */
    val clockWidthFraction: Float = 0.50f,
    val colorArgb: Int = 0xF2FFFFFF.toInt(),
    val showDate: Boolean = true,
    val parallax: Boolean = true,
)

/**
 * Persists the two wallpaper layers (background + subject cutout) and the config.
 *
 * Uses DEVICE-PROTECTED storage so the wallpaper still renders on the lock screen
 * after a reboot, before the user has unlocked the phone for the first time.
 */
class DepthStore(context: Context) {

    private val ctx: Context = context.applicationContext.createDeviceProtectedStorageContext()
    private val dir = File(ctx.filesDir, "depth").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("depth_cfg", Context.MODE_PRIVATE)
    private val bgFile = File(dir, "bg.jpg")
    private val fgFile = File(dir, "fg.png")

    /** Changes whenever layers or config are saved; the engine reloads when it differs. */
    fun stamp(): Long = prefs.getLong(KEY_STAMP, 0L)

    fun hasLayers(): Boolean = bgFile.exists() && fgFile.exists()

    fun saveLayers(bg: Bitmap, fg: Bitmap) {
        writeAtomic(bgFile) { bg.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        writeAtomic(fgFile) { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bumpStamp()
    }

    fun loadLayers(): Pair<Bitmap, Bitmap>? {
        if (!hasLayers()) return null
        val bg = BitmapFactory.decodeFile(bgFile.path) ?: return null
        val fg = BitmapFactory.decodeFile(fgFile.path) ?: return null
        return bg to fg
    }

    fun saveConfig(c: DepthConfig) {
        prefs.edit()
            .putString("style", c.style.name)
            .putFloat("centerY", c.clockCenterY)
            .putFloat("widthFrac", c.clockWidthFraction)
            .putInt("color", c.colorArgb)
            .putBoolean("showDate", c.showDate)
            .putBoolean("parallax", c.parallax)
            .apply()
        bumpStamp()
    }

    fun loadConfig(): DepthConfig {
        val d = DepthConfig()
        return DepthConfig(
            style = runCatching { ClockStyle.valueOf(prefs.getString("style", "") ?: "") }
                .getOrDefault(d.style),
            clockCenterY = prefs.getFloat("centerY", d.clockCenterY),
            clockWidthFraction = prefs.getFloat("widthFrac", d.clockWidthFraction),
            colorArgb = prefs.getInt("color", d.colorArgb),
            showDate = prefs.getBoolean("showDate", d.showDate),
            parallax = prefs.getBoolean("parallax", d.parallax),
        )
    }

    private fun bumpStamp() {
        prefs.edit().putLong(KEY_STAMP, maxOf(System.currentTimeMillis(), stamp() + 1)).apply()
    }

    private inline fun writeAtomic(target: File, write: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { write(it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val KEY_STAMP = "stamp"
    }
}
