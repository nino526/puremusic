package com.puremusic.app

import android.Manifest
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import java.net.URL

private val background = Color(0xFF101117)
private val panel = Color(0xFF232530)
private val purple = Color(0xFFB58CFF)
private val muted = Color(0xFFA7A9B6)

class MainActivity : ComponentActivity() {
    private var requestedScreen by mutableStateOf("songs")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedScreen = intent.getStringExtra("screen") ?: if (intent.action == Intent.ACTION_SEND) "identify" else "songs"
        setContent { PureApp() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        requestedScreen = intent.getStringExtra("screen") ?: if (intent.action == Intent.ACTION_SEND) "identify" else "songs"
    }
    private fun play(track: Track, queue: List<Track>) {
        PlaybackController.queue = queue
        PlaybackController.send(this, PlaybackService.PLAY, track)
    }
    private fun command(action: String) = PlaybackController.send(this, action)

    @Composable
    private fun PureApp() {
        val current = PlaybackController.current
        val playing = PlaybackController.playing
        val store = remember { PlaylistStore(this) }
        var screen by remember { mutableStateOf(requestedScreen) }
        var previousScreen by remember { mutableStateOf("songs") }
        var showQueue by remember { mutableStateOf(false) }
        var progress by remember { mutableLongStateOf(0L) }
        LaunchedEffect(current, playing) { while (true) { progress = PlaybackController.position; delay(500) } }
        fun showPlayer() { if (screen != "player") previousScreen = screen; screen = "player" }
        LaunchedEffect(requestedScreen) { screen = requestedScreen }
        DisposableEffect(screen) {
            if (screen == "visualizer") {
                if (Build.VERSION.SDK_INT >= 30) {
                    window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (4 or 1024 or 4096 or 2 or 512)
                }
            }
            onDispose {
                if (screen == "visualizer") {
                    if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    else {
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = 0
                    }
                }
            }
        }
        var songs by remember { mutableStateOf(emptyList<Track>()) }
        var query by remember { mutableStateOf("") }
        var selectedPlaylist by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var showNewPlaylist by remember { mutableStateOf(false) }
        var playlistName by remember { mutableStateOf("") }
        var addToPlaylist by remember { mutableStateOf<Track?>(null) }
        var token by remember { mutableStateOf(store.apiKey()) }
        var status by remember { mutableStateOf("Tap to recognize a song") }
        var isRecording by remember { mutableStateOf(false) }
        var link by remember { mutableStateOf(if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else "") }
        var audioUrl by remember { mutableStateOf("") }
        var downloadStatus by remember { mutableStateOf("") }
        var overlayEnabled by remember { mutableStateOf(false) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { songs = loadSongs(this) }
        val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                isRecording = true; status = "Listening for 12 seconds…"
                Recognition.captureAndIdentify(this, token) { response -> runOnUiThread { status = response; isRecording = false; if (response.contains(" — ")) { store.appendHistory(response); refresh++ } } }
            } else status = "Microphone permission is required."
        }
        LaunchedEffect(Unit) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED) songs = loadSongs(this@MainActivity)
            else permissions.launch(arrayOf(permission))
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = background, surface = panel, onBackground = Color.White, onSurface = Color.White)) {
            Column(Modifier.fillMaxSize().background(background)) {
                if (screen != "visualizer") Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 27.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PURE ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Music", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = purple)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { screen = "identify" }) { Icon(Icons.Default.Mic, "Identify", tint = purple) }
                    IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                }
                if (screen == "songs" || screen == "playlists") {
                    OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search songs or artists…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp), singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 18.dp, end = 18.dp)) {
                        listOf("songs" to "All Songs", "playlists" to "Playlists").forEach { (key, label) ->
                            TextButton(onClick = { screen = key; selectedPlaylist = null }, modifier = Modifier.weight(1f)) { Text(label, color = if (screen == key) purple else muted) }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        "visualizer" -> VisualizerScreen(onBack = { screen = "player" })
                        "player" -> if (current != null) FullPlayer(current, playing, progress, onBack = { screen = previousScreen }, onVisualizer = { screen = "visualizer" }, onQueue = { showQueue = true }, onControl = ::command, onSeek = { pos -> startService(Intent(this@MainActivity, PlaybackService::class.java).setAction(PlaybackService.SEEK).putExtra("position", pos)) })
                        "songs" -> {
                            val tracks = songs.filter { (it.title + " " + it.artist).contains(query, ignoreCase = true) }.filter { selectedPlaylist == null || store.songs(selectedPlaylist!!).contains(it.id) }
                            LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(selectedPlaylist ?: "My Library · ${songs.size} songs", color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        if (selectedPlaylist != null) TextButton(onClick = { selectedPlaylist = null }) { Text("Back") }
                                        IconButton(onClick = { songs = loadSongs(this@MainActivity) }) { Icon(Icons.Default.Refresh, "Refresh") }
                                    }
                                }
                                items(tracks, key = { it.id }) { track ->
                                    Row(Modifier.fillMaxWidth().clickable { play(track, tracks) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Cover()
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text(track.artist, color = muted, fontSize = 12.sp, maxLines = 1)
                                        }
                                        IconButton(onClick = { addToPlaylist = track }) { Icon(Icons.Default.PlaylistAdd, "Add to playlist") }
                                    }
                                }
                            }
                        }
                        "playlists" -> {
                            Column(Modifier.padding(20.dp)) {
                                Button(onClick = { showNewPlaylist = true }) { Icon(Icons.Default.Add, null); Text("New Playlist") }
                                key(refresh) {
                                    store.names().forEach { name ->
                                        Row(Modifier.fillMaxWidth().clickable { selectedPlaylist = name; screen = "songs" }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.QueueMusic, null, tint = purple)
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text("${store.songs(name).size} songs", fontSize = 12.sp, color = muted) }
                                            IconButton(onClick = { store.remove(name); refresh++ }) { Icon(Icons.Default.DeleteOutline, "Delete playlist") }
                                        }
                                    }
                                }
                            }
                        }
                        "identify" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Identify & Download", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(35.dp))
                            Box(Modifier.size(150.dp).background(Brush.radialGradient(listOf(purple.copy(alpha = 0.5f), panel)), RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = purple, modifier = Modifier.size(80.dp)) }
                            Spacer(Modifier.height(24.dp))
                            Text(status, color = muted)
                            Spacer(Modifier.height(15.dp))
                            Button(onClick = {
                                if (!isRecording) {
                                    if (token.isBlank()) status = FreeSongSearch.launch(this@MainActivity)
                                    else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) microphone.launch(Manifest.permission.RECORD_AUDIO)
                                    else { isRecording = true; status = "Listening for 12 seconds…"; Recognition.captureAndIdentify(this@MainActivity, token) { answer -> runOnUiThread { status = answer; isRecording = false; if (answer.contains(" — ")) { store.appendHistory(answer); refresh++ } } } }
                                }
                            }, enabled = !isRecording, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (isRecording) "Listening…" else "Listen & Identify") }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { status = FreeSongSearch.launch(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.MusicNote, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Free Song Search (Google)")
                            }
                            Text("Without an AudD token, Listen & Identify uses this free Google option too. Results stay in Google. Availability varies by device.", color = muted, fontSize = 12.sp)
                            Spacer(Modifier.height(22.dp))
                            Text("From TikTok", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                            Text("Paste a shared TikTok link to open the original video. The link alone does not grant audio download rights.", color = muted, fontSize = 12.sp)
                            OutlinedTextField(link, { link = it }, label = { Text("TikTok video link") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                            Button(onClick = { runCatching { val parsed = Uri.parse(link.trim()); if (parsed.scheme == "https" && (parsed.host == "tiktok.com" || parsed.host?.endsWith(".tiktok.com") == true)) startActivity(Intent(Intent.ACTION_VIEW, parsed)) else status = "Enter a valid TikTok HTTPS link." } }, modifier = Modifier.fillMaxWidth()) { Text("Open TikTok Video") }
                            Spacer(Modifier.height(20.dp))
                            Text("Recognition History", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                            key(refresh) { store.history().forEach { Text(it, modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) } }
                        }
                        "downloads" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text("Downloads", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Download from an authorized direct audio URL (HTTPS). TikTok video links cannot be used here.", fontSize = 13.sp, color = muted)
                            OutlinedTextField(audioUrl, { audioUrl = it }, label = { Text("Direct audio file URL") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                try {
                                    val url = URL(audioUrl.trim()); require(url.protocol == "https")
                                    require(url.path.lowercase().endsWith(".mp3") || url.path.lowercase().endsWith(".m4a") || url.path.lowercase().endsWith(".ogg"))
                                    val filename = "PURE_${System.currentTimeMillis()}.${url.path.substringAfterLast('.').lowercase()}"
                                    val request = DownloadManager.Request(Uri.parse(audioUrl.trim())).setTitle(filename).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "PURE Music/$filename")
                                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                                    downloadStatus = "Download queued. Refresh your library when finished."
                                } catch (_: Exception) { downloadStatus = "Enter a supported, authorized HTTPS audio file URL." }
                            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Text("Download Audio") }
                            Text(downloadStatus, color = muted, fontSize = 13.sp)
                        }
                        "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text("Settings", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(20.dp))
                            Text("Floating Recognition", fontWeight = FontWeight.Bold)
                            Text("Movable shortcut over other apps. Tap it to start identification in PURE Music.", fontSize = 12.sp, color = muted)
                            SwitchRow("Floating Button", overlayEnabled) { on ->
                                overlayEnabled = on
                                if (on) {
                                    if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                    else ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, FloatingBubbleService::class.java))
                                } else stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                            }
                            Button(onClick = { if (Settings.canDrawOverlays(this@MainActivity)) { overlayEnabled = true; ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, FloatingBubbleService::class.java)) } else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("Enable Floating Button") }
                            Spacer(Modifier.height(22.dp))
                            Text("Music Recognition", fontWeight = FontWeight.Bold)
                            Text("Free option: use Google Song Search in Identify. The built-in 12-second Listen & Identify still uses optional AudD and needs your own token. No background microphone recording.", fontSize = 12.sp, color = muted)
                            OutlinedTextField(token, { token = it }, label = { Text("AudD API token") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { store.setApiKey(token) }) { Text("Save API Token") }
                            Spacer(Modifier.height(14.dp))
                            Text("Automatic background listening is not enabled in this prototype. It requires a separate, privacy-conscious microphone foreground service.", color = muted, fontSize = 12.sp)
                        }
                    }
                }
                if (current != null && screen != "player" && screen != "visualizer") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                        .background(panel, RoundedCornerShape(16.dp)).padding(7.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { showPlayer() }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(39)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(current.artist, fontSize = 11.sp, color = muted, maxLines = 1)
                            }
                            Icon(Icons.Default.KeyboardArrowUp, "Open full player", tint = muted)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { PlaybackController.shuffle = !PlaybackController.shuffle }) {
                                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (PlaybackController.shuffle) purple else muted)
                            }
                            IconButton(onClick = { command(PlaybackService.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                            IconButton(onClick = { command(PlaybackService.TOGGLE) }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                            IconButton(onClick = { command(PlaybackService.NEXT) }) { Icon(Icons.Default.SkipNext, "Next") }
                            IconButton(onClick = { PlaybackController.repeat = !PlaybackController.repeat }) {
                                Icon(Icons.Default.Repeat, "Repeat", tint = if (PlaybackController.repeat) purple else muted)
                            }
                            IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Queue") }
                        }
                    }
                }
                if (screen != "player" && screen != "visualizer") NavigationBar(containerColor = background) {
                    listOf(Triple("songs", "Songs", Icons.Default.LibraryMusic), Triple("playlists", "Playlists", Icons.Default.QueueMusic), Triple("identify", "Identify", Icons.Default.GraphicEq), Triple("downloads", "Downloads", Icons.Default.Download)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = screen == key, onClick = { screen = key }, label = { Text(label, fontSize = 10.sp) }, icon = { Icon(icon, label) })
                    }
                }
            }
            if (showQueue) AlertDialog(onDismissRequest = { showQueue = false },
                title = { Text("Up next") },
                text = { LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(PlaybackController.queue, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().clickable {
                            play(track, PlaybackController.queue); showQueue = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = purple)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Close") } })
            if (showNewPlaylist) AlertDialog(onDismissRequest = { showNewPlaylist = false }, title = { Text("New Playlist") }, text = { OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Name") }) }, confirmButton = { TextButton(onClick = { store.create(playlistName); refresh++; playlistName = ""; showNewPlaylist = false }) { Text("Create") } }, dismissButton = { TextButton(onClick = { showNewPlaylist = false }) { Text("Cancel") } })
            addToPlaylist?.let { track -> AlertDialog(onDismissRequest = { addToPlaylist = null }, title = { Text("Add to Playlist") }, text = { Column { key(refresh) { store.names().forEach { name -> TextButton(onClick = { store.add(name, track.id); refresh++; addToPlaylist = null }) { Text(name) } } }; if (store.names().isEmpty()) Text("Create a playlist first.") } }, confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text("Close") } }) }
        }
    }
}

@Composable private fun Cover(size: Int = 50) { Box(Modifier.size(size.dp).background(Brush.linearGradient(listOf(Color(0xFF8D63B3), Color(0xFF293B62))), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.White) } }
@Composable private fun SwitchRow(label: String, checked: Boolean, update: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked, update) } }

@Composable
private fun FullPlayer(track: Track, playing: Boolean, position: Long, onBack: () -> Unit, onVisualizer: () -> Unit, onQueue: () -> Unit,
                       onControl: (String) -> Unit, onSeek: (Long) -> Unit) {
    val duration = track.duration.coerceAtLeast(1L)
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    val ratio = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Back to library") }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", fontSize = 12.sp, color = muted, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f)
            .background(Brush.linearGradient(listOf(Color(0xFF845BAD), Color(0xFF26395D))), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(116.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text(track.title, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Text(track.artist, color = muted, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        Slider(value = ratio, onValueChange = { dragging = it }, onValueChangeFinished = {
            onSeek(((dragging ?: ratio) * duration).toLong()); dragging = null
        }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime((ratio * duration).toLong()), color = muted, fontSize = 12.sp)
            Text(formatPlaybackTime(duration), color = muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            val shuffle = PlaybackController.shuffle
            IconButton(onClick = { PlaybackController.shuffle = !shuffle }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) purple else muted)
            }
            IconButton(onClick = { onControl(PlaybackService.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(38.dp)) }
            FilledIconButton(onClick = { onControl(PlaybackService.TOGGLE) }, modifier = Modifier.size(70.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { onControl(PlaybackService.NEXT) }) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(38.dp)) }
            val repeat = PlaybackController.repeat
            IconButton(onClick = { PlaybackController.repeat = !repeat }) {
                Icon(Icons.Default.Repeat, "Repeat current song", tint = if (repeat) purple else muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, "Queue"); Spacer(Modifier.width(8.dp)); Text("Up next") }
            TextButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, "Visualizer"); Spacer(Modifier.width(8.dp)); Text("Visualizer") }
        }
        Spacer(Modifier.height(22.dp))
    }
}
private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
