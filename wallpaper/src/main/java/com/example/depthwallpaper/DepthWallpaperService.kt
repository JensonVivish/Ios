package com.example.depthwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import java.util.Locale

class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    private inner class DepthEngine : Engine(), SensorEventListener {

        private val store = DepthStore(applicationContext)
        private val density = resources.displayMetrics.density

        private var cfg = DepthConfig()
        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var loadedStamp = -1L

        private var w = 0
        private var h = 0
        private var visible = false

        // Parallax state
        private val sensorManager: SensorManager? = getSystemService(SensorManager::class.java)
        private val accel: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var sensorOn = false
        private var baselineReady = false
        private var baseX = 0f
        private var baseY = 0f
        private var tiltX = 0f
        private var tiltY = 0f
        private var lastDrawMs = 0L

        private var receiverOn = false
        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                reloadIfNeeded()
                registerTimeReceiver()
                updateSensor()
                drawFrame()
            } else {
                unregisterTimeReceiver()
                unregisterSensor()
            }
        }

        override fun onDestroy() {
            unregisterTimeReceiver()
            unregisterSensor()
            bg = null
            fg = null
            super.onDestroy()
        }

        // ---- data ---------------------------------------------------------------------

        private fun reloadIfNeeded() {
            val stamp = store.stamp()
            if (stamp == loadedStamp) return
            cfg = store.loadConfig()
            val layers = store.loadLayers()
            bg = layers?.first
            fg = layers?.second
            loadedStamp = stamp
        }

        // ---- drawing ------------------------------------------------------------------

        private fun drawFrame() {
            if (!visible || w == 0 || h == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                DepthComposer.draw(
                    canvas = canvas,
                    w = w,
                    h = h,
                    bg = bg,
                    fg = fg,
                    cfg = cfg,
                    nowMs = System.currentTimeMillis(),
                    is24h = DateFormat.is24HourFormat(applicationContext),
                    locale = Locale.getDefault(),
                    tiltX = if (cfg.parallax) tiltX else 0f,
                    tiltY = if (cfg.parallax) tiltY else 0f,
                    density = density,
                )
            } catch (_: Exception) {
                // Surface can disappear between visibility change and lock; ignore.
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        // ---- clock tick ---------------------------------------------------------------

        private fun registerTimeReceiver() {
            if (receiverOn) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)          // every minute (dynamic only)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            this@DepthWallpaperService.registerReceiver(timeReceiver, filter)
            receiverOn = true
        }

        private fun unregisterTimeReceiver() {
            if (!receiverOn) return
            try { this@DepthWallpaperService.unregisterReceiver(timeReceiver) } catch (_: Exception) {}
            receiverOn = false
        }

        // ---- parallax -----------------------------------------------------------------

        private fun updateSensor() {
            if (visible && cfg.parallax && accel != null) registerSensor() else unregisterSensor()
        }

        private fun registerSensor() {
            if (sensorOn) return
            baselineReady = false
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
            sensorOn = true
        }

        private fun unregisterSensor() {
            if (!sensorOn) return
            sensorManager?.unregisterListener(this)
            sensorOn = false
            tiltX = 0f
            tiltY = 0f
        }

        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]
            val y = e.values[1]
            if (!baselineReady) {
                baseX = x; baseY = y; baselineReady = true
            }
            // Slow baseline follows how you're holding the phone; we react to changes around it.
            baseX += (x - baseX) * BASELINE_ALPHA
            baseY += (y - baseY) * BASELINE_ALPHA

            val targetX = ((x - baseX) / TILT_RANGE).coerceIn(-1f, 1f)
            val targetY = ((y - baseY) / TILT_RANGE).coerceIn(-1f, 1f)
            // Flip a sign here if the motion feels backwards to you.
            tiltX += (targetX - tiltX) * SMOOTHING
            tiltY += (targetY - tiltY) * SMOOTHING

            val now = SystemClock.uptimeMillis()
            if (now - lastDrawMs >= 33L) {       // ~30 fps cap
                lastDrawMs = now
                drawFrame()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private companion object {
        const val TILT_RANGE = 3.5f        // m/s^2 of gravity shift ≈ 20° tilt = full travel
        const val BASELINE_ALPHA = 0.01f
        const val SMOOTHING = 0.15f
    }
}
