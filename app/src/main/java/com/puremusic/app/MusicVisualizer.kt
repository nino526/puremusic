package com.puremusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlin.math.*

// Original procedural visual effects inspired by classic media-player categories.
// No external visualizer assets, network calls or paid libraries are used.
private enum class VisualMode(val title: String) {
    PLASMA("Plasma"), ALCHEMY("Alchemy"), PARTICLES("Particles"), SPECTRUM("Spectrum"), WAVES("Waves")
}

private data class AudioFrame(val spectrum: List<Float>, val wave: List<Float>, val bass: Float, val beat: Float) {
    companion object { val EMPTY = AudioFrame(List(64) { 0f }, List(128) { 0f }, 0f, 0f) }
}

private fun readFrame(fft: ByteArray, waveform: ByteArray, previousBass: Float): AudioFrame {
    val bins = FloatArray(64)
    val usableBins = fft.size / 2 - 1
    // Log-like distribution gives bass enough visual space without ignoring treble.
    for (i in bins.indices) {
        val lower = ((usableBins * (2.0.pow(i / 64.0 * 1.0) - 1.0)) .toInt()).coerceIn(1, usableBins)
        val upper = ((usableBins * (2.0.pow((i + 1) / 64.0) - 1.0)).toInt()).coerceIn(lower, usableBins)
        var sum = 0f
        var samples = 0
        for (bin in lower..upper) {
            val real = fft[bin * 2].toInt().toFloat()
            val imag = fft[bin * 2 + 1].toInt().toFloat()
            sum += sqrt(real * real + imag * imag)
            samples++
        }
        val level = if (samples == 0) 0f else sum / samples
        bins[i] = (ln(1f + level) / ln(1f + 90f)).coerceIn(0f, 1f)
    }
    val low = bins.take(12).average().toFloat().coerceIn(0f, 1f)
    val onset = ((low - previousBass) * 3.5f).coerceIn(0f, 1f)
    val wave = List(128) { i ->
        (waveform[i * waveform.size / 128].toInt().and(0xff) - 128) / 128f
    }
    return AudioFrame(bins.toList(), wave, low, onset)
}

@Composable
fun VisualizerScreen(onBack: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pure_visualizer", 0) }
    var mode by remember { mutableStateOf(VisualMode.entries.getOrElse(prefs.getInt("mode", 1)) { VisualMode.ALCHEMY }) }
    val palettes = remember { listOf(Color(0xFFAE88FF), Color(0xFF45D7FF), Color(0xFFFF648C), Color(0xFF5CF2B2), Color(0xFFFFC86C)) }
    var paletteIndex by remember { mutableIntStateOf(prefs.getInt("color", 0).coerceIn(0, palettes.lastIndex)) }
    var fps by remember { mutableIntStateOf(prefs.getInt("fps", 30).takeIf { it in listOf(15, 30, 60) } ?: 30) }
    var intensity by remember { mutableFloatStateOf(prefs.getFloat("intensity", 1f).coerceIn(.5f, 2f)) }
    var showControls by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf(AudioFrame.EMPTY) }
    var tick by remember { mutableLongStateOf(0L) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) visible = true
            if (event == Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val sessionId = PlaybackController.audioSessionId
    val isPlaying = PlaybackController.playing
    LaunchedEffect(hasPermission, sessionId, visible, isPlaying, fps) {
        frame = AudioFrame.EMPTY
        if (!hasPermission || sessionId <= 0 || !visible || !isPlaying) return@LaunchedEffect
        var effect: Visualizer? = null
        try {
            effect = Visualizer(sessionId)
            val range = Visualizer.getCaptureSizeRange()
            val size = 1024.coerceIn(range[0], range[1])
            effect.captureSize = size
            effect.enabled = true
            val fft = ByteArray(size)
            val wave = ByteArray(size)
            var previousBass = 0f
            captureError = null
            while (true) {
                val okFft = effect.getFft(fft) == Visualizer.SUCCESS
                val okWave = effect.getWaveForm(wave) == Visualizer.SUCCESS
                if (okFft && okWave) {
                    val next = readFrame(fft, wave, previousBass)
                    previousBass = next.bass * .65f + previousBass * .35f
                    frame = next
                    tick++
                }
                delay(1000L / fps)
            }
        } catch (e: Exception) {
            captureError = "Audio visualization unavailable on this device: ${e.localizedMessage ?: "audio effect failed"}"
        } finally {
            effect?.let { runCatching { it.enabled = false }; it.release() }
        }
    }
    val color = palettes[paletteIndex]
    Box(Modifier.fillMaxSize().background(Color(0xFF05060E))) {
        Canvas(Modifier.fillMaxSize().clickable { showControls = !showControls }) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val t = tick.toFloat() * (30f / fps) * .034f
            val bass = frame.bass * intensity
            val pulse = (1f + bass * .45f + frame.beat * .4f)
            when (mode) {
                VisualMode.PLASMA -> {
                    // Smooth concentric, rotating wave ribbons with bass-driven radius.
                    for (layer in 0 until 15) {
                        val path = Path()
                        val baseR = min(w, h) * (.1f + layer * .022f) * pulse
                        val points = 180
                        for (i in 0..points) {
                            val a = i * (2f * PI.toFloat() / points)
                            val energy = frame.spectrum[(i * 64 / points + layer * 3) % 64]
                            val r = baseR + sin(a * (3f + layer % 5) + t * (if (layer % 2 == 0) 1 else -1)) * (10f + energy * 55f * intensity)
                            val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(path, color.copy(alpha = (.07f + (layer % 5) * .045f).coerceAtMost(.35f)), style = Stroke(width = 1.6f + bass * 3f))
                    }
                    drawCircle(color.copy(alpha = (.07f + bass * .12f).coerceAtMost(.3f)), radius = min(w, h) * .19f * pulse, center = center)
                }
                VisualMode.ALCHEMY -> {
                    // Rotating particle flowers and interlaced orbital filaments.
                    for (ring in 0 until 7) {
                        val path = Path()
                        val points = 250
                        for (i in 0..points) {
                            val a = i * (2f * PI.toFloat() / points)
                            val harmonic = (ring + 2).toFloat()
                            val energy = frame.spectrum[(i * 64 / points) % 64]
                            val r = min(w, h) * (.12f + ring * .045f) + sin(a * harmonic + t * (ring % 3 + 1)) * (22f + 95f * energy * intensity)
                            val angle = a + t * .09f * (if (ring % 2 == 0) 1f else -1f)
                            val p = Offset(center.x + cos(angle) * r, center.y + sin(angle) * r)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            if (i % 12 == 0) drawCircle(color.copy(alpha = .2f + energy * .6f), radius = 1.2f + energy * 4f, center = p)
                        }
                        drawPath(path, color.copy(alpha = .3f + ring * .055f), style = Stroke(width = 1.3f + bass * 2.2f))
                    }
                }
                VisualMode.PARTICLES -> {
                    for (i in 0 until 500) {
                        val a = i * 2.399963f + t * (.12f + (i % 7) * .014f)
                        val band = frame.spectrum[i % 64]
                        val r = min(w, h) * (.06f + (i % 115) / 140f) * (1f + band * .45f * intensity)
                        val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
                        drawCircle(color.copy(alpha = (.2f + band * .8f).coerceAtMost(1f)), radius = 1.2f + band * 5f, center = p)
                    }
                }
                VisualMode.WAVES -> {
                    for (layer in 0 until 9) {
                        val path = Path()
                        frame.wave.forEachIndexed { i, sample ->
                            val x = i * w / (frame.wave.size - 1)
                            val y = h * (.25f + layer * .065f) + sample * h * (.05f + layer * .012f) * intensity + sin(t + i * .025f + layer) * bass * 24f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color.copy(alpha = .18f + layer * .075f), style = Stroke(width = 1.5f + bass * 3f))
                    }
                }
                VisualMode.SPECTRUM -> {
                    val count = 48
                    val gap = w / count
                    val baseline = h * .64f
                    for (i in 0 until count) {
                        val value = frame.spectrum[(i * 64 / count)].coerceIn(0f, 1f)
                        val bar = (8f + value.pow(1.4f) * h * .47f * intensity).coerceAtMost(h * .55f)
                        drawLine(color.copy(alpha = .45f + .5f * value), Offset((i + .5f) * gap, baseline), Offset((i + .5f) * gap, baseline - bar), strokeWidth = gap * .65f, cap = StrokeCap.Round)
                        drawLine(color.copy(alpha = .14f), Offset((i + .5f) * gap, baseline + 12f), Offset((i + .5f) * gap, baseline + bar * .25f), strokeWidth = gap * .6f)
                    }
                    val path = Path()
                    frame.wave.forEachIndexed { i, sample ->
                        val x = i * w / (frame.wave.size - 1)
                        val y = h * .81f + sample * h * .12f * intensity
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color.copy(alpha = .95f), style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
            }
        }
        if (showControls && !embedded) {
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0xD9111220)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                    Text("MUSIC VISUALIZER", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Settings, "Visualizer settings", tint = Color.White) }
                    IconButton(onClick = { showControls = false }) { Icon(Icons.Default.Close, "Hide controls", tint = Color.White) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VisualMode.entries.forEach { candidate ->
                        FilterChip(selected = mode == candidate, onClick = { mode = candidate; prefs.edit().putInt("mode", candidate.ordinal).apply() }, label = { Text(candidate.title) })
                    }
                }
                if (!hasPermission) {
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow audio visualization", color = Color.White) }
                } else if (sessionId == 0) {
                    Text("Play a local song to see its sound", color = Color.LightGray)
                } else if (!isPlaying) {
                    Text("Paused — visualization sleeps to save battery", color = Color.LightGray)
                }
                if (captureError != null) Text(captureError!!, color = Color(0xFFFFADB6))
                Text("Tap the animation to hide or show controls", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showSettings && !embedded) {
            AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Visualizer settings") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Color")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        palettes.forEachIndexed { i, swatch ->
                            Box(Modifier.size(if (paletteIndex == i) 39.dp else 32.dp)
                                .background(swatch, RoundedCornerShape(50)).clickable { paletteIndex = i; prefs.edit().putInt("color", i).apply() })
                        }
                    }
                    Text("Effect strength: ${"%.1f".format(intensity)}×")
                    Slider(value = intensity, onValueChange = { intensity = it }, valueRange = .5f..2f, onValueChangeFinished = { prefs.edit().putFloat("intensity", intensity).apply() })
                    Text("Frame rate (lower saves battery)")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(15, 30, 60).forEach { rate -> FilterChip(selected = fps == rate, onClick = { fps = rate; prefs.edit().putInt("fps", rate).apply() }, label = { Text("$rate FPS") }) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } })
        }
    }
}

@Composable
fun EmbeddedVisualizer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pure_visualizer", 0) }
    var modeIndex by remember { mutableIntStateOf(prefs.getInt("mode", 1).coerceIn(0, VisualMode.entries.lastIndex)) }
    Column(modifier.background(Color(0xFF05060E))) {
        Box(Modifier.weight(1f)) {
            // Same audio-reactive rendering and selected mode as the full-screen visualizer.
            key(modeIndex) { VisualizerScreen(onBack = {}, embedded = true) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VisualMode.entries.forEachIndexed { index, effect ->
                FilterChip(selected = modeIndex == index, onClick = {
                    modeIndex = index
                    prefs.edit().putInt("mode", index).apply()
                }, label = { Text(effect.title, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
}
