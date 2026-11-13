package com.puremusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    // Volumetric neon torus: saturated blue-to-magenta spectral mesh,
                    // soft layered blooms and a dark center like the approved design.
                    val rad = min(w, h)
                    val neon = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(
                            Color(0xFFED68FF), Color(0xFFFC72ED),
                            Color(0xFF7752FF), Color(0xFF38E3FF),
                            Color(0xFF24AFFF), Color(0xFFCC53FF),
                            Color(0xFFED68FF)
                        ), center = center
                    )
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(
                                Color(0x00271B66),
                                Color(0x554B23A3),
                                Color(0x444F2DCA),
                                Color(0x225332A3),
                                Color.Transparent
                            ), center = center, radius = rad * .49f
                        ), radius = rad * .49f, center = center
                    )
                    // Silk-like glowing background waves, never just an outline.
                    for (layer in 0 until 8) {
                        val path = Path()
                        for (i in 0..140) {
                            val u = i / 140f
                            val x = u * w
                            val y = center.y + sin(u * 8.2f + t * .36f + layer * .32f) *
                                (rad * (.16f + layer * .012f)) +
                                cos(u * 17f - t * .23f + layer * .7f) * rad * .018f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        val shade = if (layer % 2 == 0) Color(0xFF4EDFFF) else Color(0xFFEC5EFF)
                        drawPath(path, shade.copy(alpha = .055f + layer * .012f),
                            style = Stroke(width = 2.4f + bass * 3f, cap = StrokeCap.Round))
                    }
                    // A set of closely spaced rings forms a translucent ribbon surface.
                    // The whole shape rotates gently; audio energy modulates its outline.
                    for (ring in 0 until 29) {
                        val path = Path()
                        val surface = ring / 28f
                        val baseR = rad * (.215f + surface * .115f) * (1f + bass * .12f)
                        for (i in 0..240) {
                            val a = i * (2f * PI.toFloat() / 240f)
                            val energy = frame.spectrum[(i * 64 / 240 + ring * 2) % 64]
                            val petal = sin(a * 5f + t * .48f) * rad * (.048f + surface * .006f)
                            val secondary = cos(a * 3f - t * .30f + ring * .09f) * rad * .019f
                            val rr = baseR + petal + secondary + energy * 24f * intensity
                            val angle = a + t * .045f + surface * .05f
                            val p = Offset(center.x + cos(angle) * rr, center.y + sin(angle) * rr)
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        path.close()
                        if (ring % 7 == 0) {
                            drawPath(path, brush = neon, alpha = .11f,
                                style = Stroke(width = 16f + bass * 9f, cap = StrokeCap.Round))
                            drawPath(path, brush = neon, alpha = .17f,
                                style = Stroke(width = 7f + bass * 4f, cap = StrokeCap.Round))
                        }
                        drawPath(path, brush = neon,
                            alpha = (.35f + .36f * (1f - abs(surface - .55f))).coerceIn(.3f, .78f),
                            style = Stroke(width = if (ring % 4 == 0) 2.1f else .95f,
                                cap = StrokeCap.Round))
                    }
                    // One prominent flowing outer edge with bloom and near-white highlights.
                    val crest = Path()
                    for (i in 0..280) {
                        val a = i * (2f * PI.toFloat() / 280f)
                        val energy = frame.spectrum[(i * 64 / 280) % 64]
                        val radius = rad * .296f +
                            sin(a * 5f + t * .48f) * rad * .055f +
                            cos(a * 3f - t * .3f) * rad * .018f +
                            energy * 25f * intensity
                        val angle = a + t * .045f
                        val pt = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                        if (i == 0) crest.moveTo(pt.x, pt.y) else crest.lineTo(pt.x, pt.y)
                    }
                    crest.close()
                    drawPath(crest, brush = neon, alpha = .16f,
                        style = Stroke(width = 29f + bass * 8f, cap = StrokeCap.Round))
                    drawPath(crest, brush = neon, alpha = .32f,
                        style = Stroke(width = 11f + bass * 5f, cap = StrokeCap.Round))
                    drawPath(crest, brush = neon, alpha = .98f,
                        style = Stroke(width = 2.8f + bass * 1.8f, cap = StrokeCap.Round))
                    // Bright spatial particles provide dimension around the ring.
                    for (i in 0 until 250) {
                        val a = i * 2.399963f + t * (.045f + i % 4 * .011f)
                        val depth = ((i * 37) % 107) / 107f
                        val radius = rad * (.22f + depth * .25f)
                        val energy = frame.spectrum[i % 64]
                        val point = Offset(center.x + cos(a) * radius,
                            center.y + sin(a) * radius)
                        val star = if (i % 2 == 0) Color(0xFF57E6FF) else Color(0xFFF279FF)
                        drawCircle(star.copy(alpha = (.19f + energy * .68f +
                            .15f * (sin(t + i * 1.4f) + 1f)).coerceIn(0f, .98f)),
                            radius = .85f + energy * 3f + if (i % 13 == 0) 1.4f else 0f,
                            center = point)
                    }
                }
                VisualMode.PARTICLES -> {
                    // Luminous swarms gathered into flowing streams, not sparse random dots.
                    val rad = min(w, h)
                    val sweep = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color(0xFFF36AFF), Color(0xFF59DEFF),
                            Color(0xFF735CFF), Color(0xFFE45CFF), Color(0xFFF36AFF)),
                        center = center)
                    drawCircle(brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color(0x663D1C93), Color(0x445C32C3), Color(0x2229A5D1), Color.Transparent),
                        center = center, radius = rad * .52f),
                        radius = rad * .52f, center = center)
                    for (stream in 0 until 7) {
                        val strand = Path()
                        val colorA = if (stream % 2 == 0) Color(0xFF42DFFF) else Color(0xFFF86CFF)
                        for (i in 0..170) {
                            val u = i / 170f
                            val a = stream * (2f * PI.toFloat() / 7f) +
                                u * 7f + t * (.26f + stream * .027f)
                            val energy = frame.spectrum[(i + stream * 13) % 64]
                            val radius = rad * (.05f + u * .38f) * (1f + bass * .15f) +
                                sin(u * 17f - t * .75f + stream) * rad * .011f
                            val point = Offset(center.x + cos(a) * radius,
                                center.y + sin(a) * radius * .88f)
                            if (i == 0) strand.moveTo(point.x, point.y) else strand.lineTo(point.x, point.y)
                            if (i % 2 == 0) drawCircle(colorA.copy(alpha = (.42f + energy * .43f).coerceAtMost(.98f)),
                                radius = 1.1f + (1f-u)*1.8f + energy * 3f,
                                center = point)
                        }
                        drawPath(strand, colorA.copy(alpha = .14f + bass * .2f),
                            style = Stroke(width = 12f, cap = StrokeCap.Round))
                        drawPath(strand, brush = sweep, alpha = .45f,
                            style = Stroke(width = 2.2f + bass * 2.5f, cap = StrokeCap.Round))
                    }
                    for (i in 0 until 410) {
                        val a = i * 2.399963f + t * (.04f + ((i * 3) % 7) * .012f)
                        val depth = ((i * 47) % 151) / 151f
                        val r = rad * (.1f + depth * .38f)
                        val wobble = sin(i * .71f + t * .8f) * rad * .014f
                        val pt = Offset(center.x + cos(a) * (r + wobble),
                            center.y + sin(a) * (r + wobble) * .84f)
                        val energy = frame.spectrum[i % 64]
                        val shade = if (i % 3 == 0) Color(0xFF51E4FF) else Color(0xFFF579FF)
                        drawCircle(shade.copy(alpha = (.2f + energy * .58f +
                            .14f * (1f + sin(t * 1.5f + i))).coerceIn(0f, 1f)),
                            radius = .8f + energy * 2.8f + if (i % 17 == 0) 1.6f else 0f,
                            center = pt)
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
    var moreOpen by remember { mutableStateOf(false) }
    val smallShape = RoundedCornerShape(11.dp)
    val labels = listOf("∿", "∞", "✦", "▥", "≋")
    Column(modifier.background(Color(0xFF050610))) {
        Box(Modifier.weight(1f)) {
            // Sound-reactive native Canvas inside a clipped, neon-edged album panel.
            key(modeIndex) { VisualizerScreen(onBack = {}, embedded = true) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            VisualMode.entries.take(5).forEachIndexed { index, effect ->
                val active = modeIndex == index
                val bg = if (active)
                    Brush.linearGradient(listOf(Color(0xFFAC48F3), Color(0xFF5B45DC)))
                else Brush.linearGradient(listOf(Color(0xFF191327), Color(0xFF101122)))
                Box(
                    Modifier.weight(1f).height(46.dp).clip(smallShape)
                        .background(bg)
                        .border(1.dp,
                            if (active) Color(0xFFEDB2FF) else Color(0xFF685181),
                            smallShape)
                        .clickable {
                            modeIndex = index
                            prefs.edit().putInt("mode", index).apply()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(labels[index], fontSize = 13.sp,
                            color = if (active) Color.White else Color(0xFFD0A8FF))
                        Text(effect.title, fontSize = 9.sp,
                            color = Color.White, fontWeight = if (active)
                                FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1)
                    }
                }
            }
            Box(Modifier.width(34.dp)) {
                val otherActive = modeIndex >= 5
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(smallShape)
                        .background(if (otherActive)
                            Brush.linearGradient(listOf(Color(0xFFAB48F3), Color(0xFF5C47DE)))
                            else Brush.linearGradient(listOf(Color(0xFF191327), Color(0xFF101122))))
                        .border(1.dp, if (otherActive) Color(0xFFEDB2FF)
                            else Color(0xFF685181), smallShape)
                        .clickable { moreOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("•••", color = Color.White, fontSize = 15.sp)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    VisualMode.entries.drop(5).forEach { effect ->
                        DropdownMenuItem(
                            text = { Text(effect.title) },
                            onClick = {
                                modeIndex = effect.ordinal
                                prefs.edit().putInt("mode", modeIndex).apply()
                                moreOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}
