package com.example.depthwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Produces a transparent PNG-style bitmap containing only the photo's main subject.
 * The result has the same pixel size as the input, so it lines up 1:1 with the background.
 */
object SubjectCutout {

    private fun client() = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )

    /** Makes sure the on-device model is installed (downloads it via Play services if needed). */
    suspend fun ensureModelReady(context: Context): Boolean {
        val segmenter = client()
        try {
            val installer = ModuleInstall.getClient(context)
            if (installer.areModulesAvailable(segmenter).await().areModulesAvailable()) return true

            val done = CompletableDeferred<Boolean>()
            val listener = InstallStatusListener { update ->
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> done.complete(true)
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> done.complete(false)
                    else -> Unit
                }
            }
            val request = ModuleInstallRequest.newBuilder()
                .addApi(segmenter)
                .setListener(listener)
                .build()
            val response = installer.installModules(request).await()
            if (response.areModulesAlreadyAvailable()) return true
            return withTimeoutOrNull(120_000L) { done.await() } ?: false
        } finally {
            segmenter.close()
        }
    }

    suspend fun cutout(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val segmenter = client()
        try {
            val result = segmenter.process(InputImage.fromBitmap(source, 0)).await()
            val mask = result.foregroundConfidenceMask
                ?: error("Segmentation returned no mask")

            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            mask.rewind()
            for (i in pixels.indices) {
                // Soften the edge: confidence 0.35..0.65 ramps smoothly to opaque.
                val a = smoothstep(0.35f, 0.65f, mask.get(i))
                pixels[i] = ((a * 255f).toInt() shl 24) or (pixels[i] and 0x00FFFFFF)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            segmenter.close()
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

internal object ImageUtils {

    /**
     * Decodes [uri] (EXIF rotation handled by ImageDecoder), centre-crops it to the
     * screen's aspect ratio and downsizes to at most screen resolution.
     * Segmenting the cropped image keeps the mask perfectly aligned with what is drawn.
     */
    fun prepare(context: Context, uri: Uri, screenW: Int, screenH: Int): Bitmap {
        val maxEdge = 3000
        val src = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.contentResolver, uri)
        ) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = min(1f, maxEdge.toFloat() / max(info.size.width, info.size.height))
            if (s < 1f) {
                decoder.setTargetSize(
                    (info.size.width * s).roundToInt(),
                    (info.size.height * s).roundToInt()
                )
            }
        }

        val targetAspect = screenW.toFloat() / screenH
        val srcAspect = src.width.toFloat() / src.height
        val cw: Int
        val ch: Int
        if (srcAspect > targetAspect) {
            ch = src.height
            cw = (ch * targetAspect).roundToInt()
        } else {
            cw = src.width
            ch = (cw / targetAspect).roundToInt()
        }
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cw, ch)

        val scale = min(1f, screenW.toFloat() / cw)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(cropped, (cw * scale).roundToInt(), (ch * scale).roundToInt(), true)
        } else cropped

        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, false)
    }
}
