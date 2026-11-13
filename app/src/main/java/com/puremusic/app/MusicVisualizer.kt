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
import kotlinx.coroutines.CancellationException
import kotlin.math.*

// Original procedural visual effects inspired by classic media-player categories.
// No external visualizer assets, network calls or paid libraries are used.
private enum class VisualMode(val title: String) {
    PLASMA("Plasma"), ALCHEMY("Alchemy"), PARTICLES("Particles"), SPECTRUM("Spectrum"), WAVES("Waves"),
    CIRCLE("Circle"), AURA("Aura"), GALAXY("Galaxy"), NEBULA("Nebula Pulse")
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
    // The animation clock must not depend on audiofx capture: some devices return
    // silence or temporarily fail to provide an FFT even while playback continues.
    LaunchedEffect(visible, isPlaying, fps) {
        if (!visible || !isPlaying) return@LaunchedEffect
        while (true) {
            tick++
            delay(1000L / fps)
        }
    }
    LaunchedEffect(hasPermission, sessionId, visible, isPlaying, fps) {
        frame = AudioFrame.EMPTY
        captureError = null
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
            var failures = 0
            while (true) {
                val okFft = effect.getFft(fft) == Visualizer.SUCCESS
                val okWave = effect.getWaveForm(wave) == Visualizer.SUCCESS
                if (okFft && okWave) {
                    val next = readFrame(fft, wave, previousBass)
                    previousBass = next.bass * .65f + previousBass * .35f
                    frame = next
                    failures = 0
                    captureError = null
                } else {
                    failures++
                    if (failures >= fps * 2) {
                        captureError = "Audio capture is unavailable. Animation is in ambient mode until sound capture works."
                        break
                    }
                }
                delay(1000L / fps)
            }
        } catch (cancel: CancellationException) {
            throw cancel
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
                    // Neon fluid layers: slow movement at rest, bass-responsive distortion.
                    drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0x557D39FF), Color(0x2239CFFF), Color.Transparent),
                        center = center, radius = min(w, h) * .47f),
                        radius = min(w, h) * .47f, center = center)
                    for (layer in 0 until 17) {
                        val path = Path()
                        val baseR = min(w, h) * (.11f + layer * .019f) * pulse
                        for (i in 0..180) {
                            val a = i * (2f * PI.toFloat() / 180f)
                            val energy = frame.spectrum[(i * 64 / 180 + layer * 3) % 64]
                            val undulation = sin(a * (3f + layer % 5) + t * (if (layer % 2 == 0) 1.1f else -.8f))
                            val r = baseR + undulation * (min(w, h) * .024f + energy * 65f * intensity)
                            val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        val shade = if (layer % 3 == 0) Color(0xFF4DDEFF) else if (layer % 2 == 0) Color(0xFFE66BFF) else color
                        drawPath(path, shade.copy(alpha = .12f + (layer % 5) * .09f),
                            style = Stroke(width = 1.5f + bass * 3f, cap = StrokeCap.Round))
                    }
                }
                VisualMode.ALCHEMY -> {
                    // Bright central energy and interleaved cyan/magenta rotating filaments.
                    val rad = min(w, h)
                    drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0xAAEF8DFF), Color(0x663F7EFF), Color(0x226D22B3), Color.Transparent),
                        center = center, radius = rad * (.26f + bass * .08f)),
                        center = center, radius = rad * (.26f + bass * .08f))
                    for (ring in 0 until 10) {
                        val path = Path()
                        for (i in 0..220) {
                            val a = i * (2f * PI.toFloat() / 220f)
                            val energy = frame.spectrum[(i * 64 / 220 + ring * 3) % 64]
                            val wobble = sin(a * (3f + ring % 5) + t * (.75f + ring * .13f)) *
                                (rad * (.021f + ring * .002f) + energy * 58f * intensity)
                            val r = rad * (.13f + ring * .023f) * pulse + wobble
                            val rotation = a + t * .08f * (if (ring % 2 == 0) 1f else -1f)
                            val p = Offset(center.x + cos(rotation) * r, center.y + sin(rotation) * r)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            if (i % 9 == 0) {
                                val spark = if (ring % 2 == 0) Color(0xFF64E8FF) else Color(0xFFFF83FC)
                                drawCircle(spark.copy(alpha = .35f + energy * .55f),
                                    radius = 1.5f + energy * 3.8f + ring * .12f, center = p)
                            }
                        }
                        val hue = if (ring % 2 == 0) Color(0xFF4DDFFF) else Color(0xFFFF6BFA)
                        drawPath(path, hue.copy(alpha = (.4f + ring * .045f).coerceAtMost(.8f)),
                            style = Stroke(width = 1.6f + bass * 3f, cap = StrokeCap.Round))
                    }
                }
                VisualMode.PARTICLES -> {
                    // Spectral particle streams with flowing vortex trails and a soft nebula.
                    val rad = min(w, h)
                    drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0x665730B8), Color(0x222453D8), Color.Transparent),
                        center = center, radius = rad * .46f),
                        center = center, radius = rad * .46f)
                    for (stream in 0 until 5) {
                        val trail = Path()
                        val cyan = stream % 2 == 0
                        val hue = if (cyan) Color(0xFF56E8FF) else Color(0xFFFF65EE)
                        for (i in 0..145) {
                            val u = i / 145f
                            val a = stream * (2f * PI.toFloat() / 5f) +
                                u * 6.5f + t * (.28f + stream * .025f)
                            val energy = frame.spectrum[(i + stream * 11) % 64]
                            val r = rad * (.055f + u * .36f) * (1f + bass * .2f)
                            val shimmer = sin(u * 18f - t * 1.4f + stream) * (rad * .016f + energy * 9f)
                            val p = Offset(center.x + cos(a) * (r + shimmer),
                                center.y + sin(a) * (r + shimmer) * .82f)
                            if (i == 0) trail.moveTo(p.x, p.y) else trail.lineTo(p.x, p.y)
                            if (i % 2 == 0) {
                                drawCircle(hue.copy(alpha = (.22f + .48f * (1f - u) + energy * .3f).coerceAtMost(1f)),
                                    radius = 1.3f + (1f-u)*2.1f + energy*3.6f, center = p)
                            }
                        }
                        drawPath(trail, hue.copy(alpha = .29f + bass * .24f),
                            style = Stroke(width = 1.5f + bass * 2.5f, cap = StrokeCap.Round))
                    }
                    // Extra stars provide depth and subtle bass-dependent sparkle.
                    for (i in 0 until 230) {
                        val seed = i * 2.399963f
                        val depth = ((i * 37) % 101) / 101f
                        val angle = seed + t * (.05f + depth * .09f)
                        val distance = rad * (.08f + depth * .39f)
                        val energy = frame.spectrum[i % 64]
                        val pt = Offset(center.x + cos(angle) * distance,
                            center.y + sin(angle) * distance * .83f)
                        val alpha = (.16f + energy * .6f + .16f * (sin(t + i * .9f) + 1f)).coerceIn(0f, .95f)
                        drawCircle((if (i % 3 == 0) Color(0xFF60E6FF) else Color(0xFFE882FF))
                            .copy(alpha = alpha), radius = .65f + energy * 2.2f, center = pt)
                    }
                }
                VisualMode.WAVES -> {
                    // Oscillating luminous ribbons, animated even on quiet passages.
                    drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0x552A60DD), Color(0x3347198F), Color.Transparent),
                        center = center, radius = min(w, h) * .55f),
                        center = center, radius = min(w, h) * .55f)
                    for (layer in 0 until 11) {
                        val path = Path()
                        for (i in 0..170) {
                            val u = i / 170f
                            val sample = frame.wave[(i * frame.wave.size / 171).coerceAtMost(frame.wave.lastIndex)]
                            val energy = frame.spectrum[i * 64 / 171]
                            val x = u * w
                            val y = h * (.25f + layer * .045f) +
                                sin(u * 12f + t * (.8f + layer * .05f) + layer * .37f) *
                                (h * .05f + energy * h * .065f) +
                                sample * h * .07f * intensity
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        val hue = if (layer % 2 == 0) Color(0xFF5DDAFF) else Color(0xFFF171FF)
                        drawPath(path, hue.copy(alpha = .21f + layer * .052f),
                            style = Stroke(width = 1.5f + bass * 2.8f, cap = StrokeCap.Round))
                    }
                }

                VisualMode.CIRCLE -> {
                    // Oscillating magenta-and-cyan audio ring, with bright beat particles.
                    for (layer in 0 until 5) {
                        val path = Path()
                        for (i in 0..180) {
                            val a = i * (2f * PI.toFloat() / 180f)
                            val energy = frame.spectrum[(i * 64 / 180) % 64]
                            val radius = min(w, h) * (.25f + layer * .012f) * pulse +
                                sin(a * (5f + layer) + t * (1.2f + layer * .1f)) * (13f + energy * 60f * intensity)
                            val p = Offset(center.x + cos(a) * radius, center.y + sin(a) * radius)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            if (layer == 0 && i % 6 == 0)
                                drawCircle(Color(0xFF6FE8FF).copy(alpha = .22f + energy * .7f),
                                    radius = 1.6f + energy * 3.3f, center = p)
                        }
                        drawPath(path, (if (layer % 2 == 0) Color(0xFF64DAFF) else Color(0xFFE86BFF))
                            .copy(alpha = .43f - layer * .055f), style = Stroke(width = 2.1f + bass * 3f))
                    }
                }
                VisualMode.AURA -> {
                    // Wide translucent wave ribbons, gently moving even between beats.
                    for (layer in 0 until 11) {
                        val path = Path()
                        for (i in 0..150) {
                            val u = i / 150f
                            val energy = frame.spectrum[(i * 64 / 150) % 64]
                            val x = u * w
                            val y = h * (.29f + layer * .045f) +
                                sin(u * 11f + t * (.7f + layer * .05f) + layer * .44f) *
                                (h * .083f + energy * h * .09f) * intensity +
                                cos(u * 21f - t * .27f + layer) * h * .022f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, (if (layer % 3 == 0) Color(0xFF5BE8FF) else Color(0xFFB25CFF))
                            .copy(alpha = .12f + layer * .04f), style = Stroke(width = 2f + bass * 2.5f))
                    }
                }
                VisualMode.GALAXY -> {
                    // Five intertwined spirals of stars orbiting the track's bass.
                    drawCircle(Color(0xFFAF55FF).copy(alpha = .1f + bass * .12f),
                        radius = min(w, h) * .13f * pulse, center = center)
                    for (i in 0 until 640) {
                        val arm = i % 5
                        val travel = ((i / 5) % 128) / 128f
                        val a = arm * (2f * PI.toFloat() / 5f) + travel * 10f + t * .22f
                        val wobble = sin(i * .5f + t * .7f) * (.01f + travel * .08f)
                        val r = min(w, h) * (.028f + travel * .45f) * (1f + bass * .3f)
                        val p = Offset(center.x + cos(a + wobble) * r, center.y + sin(a + wobble) * r * .73f)
                        val energy = frame.spectrum[i % 64]
                        drawCircle((if (arm % 2 == 0) Color(0xFFE66CFF) else Color(0xFF60C8FF))
                            .copy(alpha = (.18f + energy * .7f).coerceAtMost(.95f)),
                            radius = 1f + energy * 2.8f + (1f - travel), center = p)
                    }
                }
                VisualMode.NEBULA -> {
                    // Plasma cloud and rotating filaments around a glowing core.
                    val core = min(w, h) * (.21f + bass * .09f)
                    drawCircle(Color(0xFFB349FF).copy(alpha = .13f + bass * .09f),
                        radius = core * 1.45f, center = center)
                    drawCircle(Color(0xFF5EDFFF).copy(alpha = .10f + bass * .11f),
                        radius = core * .96f, center = center)
                    for (layer in 0 until 13) {
                        val path = Path()
                        for (i in 0..170) {
                            val a = i * (2f * PI.toFloat() / 170f)
                            val energy = frame.spectrum[(i * 64 / 170 + layer * 7) % 64]
                            val rr = core * (.71f + layer * .03f) +
                                sin(a * (3 + layer % 5) + t * (.8f + layer * .08f)) *
                                (11f + energy * 55f * intensity)
                            val p = Offset(center.x + cos(a) * rr, center.y + sin(a) * rr)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(path, (if (layer % 2 == 0) Color(0xFF64DCFF) else Color(0xFFE569FF))
                            .copy(alpha = .11f + layer * .035f), style = Stroke(width = 2.2f + bass * 2f))
                    }
                    for (i in 0 until 190) {
                        val a = i * 2.399963f + t * (.24f + i % 4 * .035f)
                        val r = core * (.5f + (i % 73) / 87f)
                        val energy = frame.spectrum[i % 64]
                        drawCircle(Color(0xFFD295FF).copy(alpha = .22f + energy * .55f),
                            radius = 1f + energy * 3.6f,
                            center = Offset(center.x + cos(a) * r, center.y + sin(a) * r))
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
        if (embedded && !hasPermission) {
            Column(Modifier.align(Alignment.Center).padding(18.dp)
                .background(Color(0xD9111220), RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Enable music-reactive visualization", color = Color.White)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Allow audio visualization")
                }
                Text("Android requires audio permission to analyze the local song.",
                    color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        } else if (embedded && !isPlaying) {
            Text("Paused — press Play to animate", color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0xBB111220), RoundedCornerShape(8.dp)).padding(8.dp),
                style = MaterialTheme.typography.labelSmall)
        } else if (embedded && captureError != null) {
            Text(captureError!!, color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xBB111220)).padding(6.dp),
                style = MaterialTheme.typography.labelSmall)
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
