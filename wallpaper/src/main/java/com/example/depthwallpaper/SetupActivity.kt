package com.example.depthwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = DepthStore(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SetupScreen(store) }
            }
        }
    }
}

private val SWATCHES = listOf(0xF2FFFFFF, 0xF2FFE9C7, 0xF2CFE8FF, 0xF2111111).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(store: DepthStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dm = ctx.resources.displayMetrics

    var bg by remember { mutableStateOf<Bitmap?>(null) }
    var fg by remember { mutableStateOf<Bitmap?>(null) }
    var cfg by remember { mutableStateOf(store.loadConfig()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Show the previously saved wallpaper when reopening settings.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.loadLayers() }?.let { (b, f) -> bg = b; fg = f }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                status = "Preparing image…"
                val prepared = withContext(Dispatchers.IO) {
                    ImageUtils.prepare(ctx, uri, dm.widthPixels, dm.heightPixels)
                }
                status = "Checking segmentation model (first run may download it)…"
                if (!SubjectCutout.ensureModelReady(ctx)) {
                    status = "Segmentation model unavailable. Needs Google Play services and a connection."
                    return@launch
                }
                status = "Cutting out the subject…"
                val cut = SubjectCutout.cutout(prepared)
                bg = prepared
                fg = cut
                status = null
            } catch (t: Throwable) {
                status = "Failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    val is24h = DateFormat.is24HourFormat(ctx)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Depth clock wallpaper", style = MaterialTheme.typography.headlineSmall)

        Canvas(
            Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(dm.widthPixels.toFloat() / dm.heightPixels)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1B1B20))
        ) {
            drawIntoCanvas { c ->
                DepthComposer.draw(
                    canvas = c.nativeCanvas,
                    w = size.width.toInt(),
                    h = size.height.toInt(),
                    bg = bg,
                    fg = fg,
                    cfg = cfg,
                    nowMs = System.currentTimeMillis(),
                    is24h = is24h,
                    locale = Locale.getDefault(),
                    tiltX = 0f,
                    tiltY = 0f,
                    density = density,
                )
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !busy,
        ) { Text(if (bg == null) "Choose photo" else "Change photo") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockStyle.values().forEach { s ->
                FilterChip(
                    selected = cfg.style == s,
                    onClick = { cfg = cfg.copy(style = s) },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        LabeledSlider("Clock height", cfg.clockCenterY, 0.12f..0.60f) { cfg = cfg.copy(clockCenterY = it) }
        LabeledSlider("Clock size", cfg.clockWidthFraction, 0.30f..0.85f) { cfg = cfg.copy(clockWidthFraction = it) }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SWATCHES.forEach { argb ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (cfg.colorArgb == argb) 3.dp else 1.dp,
                            color = if (cfg.colorArgb == argb) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = CircleShape,
                        )
                        .clickable { cfg = cfg.copy(colorArgb = argb) }
                )
            }
        }

        LabeledSwitch("Show date", cfg.showDate) { cfg = cfg.copy(showDate = it) }
        LabeledSwitch("Tilt parallax", cfg.parallax) { cfg = cfg.copy(parallax = it) }

        Button(
            enabled = bg != null && fg != null && !busy,
            onClick = {
                val b = bg
                val f = fg
                if (b == null || f == null) return@Button
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) {
                        store.saveLayers(b, f)
                        store.saveConfig(cfg)
                    }
                    busy = false
                    applyWallpaper(ctx)
                }
            },
        ) { Text("Save & apply") }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** If our wallpaper is already active the engine just reloads; otherwise open the system chooser. */
private fun applyWallpaper(ctx: Context) {
    val info = WallpaperManager.getInstance(ctx).wallpaperInfo
    if (info != null &&
        info.packageName == ctx.packageName &&
        info.serviceName == DepthWallpaperService::class.java.name
    ) {
        Toast.makeText(ctx, "Wallpaper updated", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(ctx, DepthWallpaperService::class.java),
    )
    ctx.startActivity(intent)
}
