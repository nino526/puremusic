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
import android.view.WindowInsetsController
import android.provider.Settings
import android.provider.MediaStore
import android.app.Activity
import android.app.RecoverableSecurityException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.view.WindowCompat
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
        // Erase the obsolete optional AudD credential on upgrade; recognition now opens Google.
        getSharedPreferences("pure_music", MODE_PRIVATE).edit().remove("audd_key").apply()
        PlaybackController.loadOrder(this)
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
                    window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (4 or 1024 or 4096 or 2 or 512)
                }
            }
            onDispose {
                if (screen == "visualizer") {
                    if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
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
        var trackMenu by remember { mutableStateOf<Track?>(null) }
        var deleteCandidate by remember { mutableStateOf<Track?>(null) }
        var pendingDelete by remember { mutableStateOf<Track?>(null) }
        var deleteError by remember { mutableStateOf<String?>(null) }
        fun finishDeletion(track: Track) {
            songs = loadSongs(this@MainActivity)
            store.removeSongEverywhere(track.id)
            refresh++
            PlaybackController.queue = PlaybackController.queue.filterNot { it.id == track.id }
            if (PlaybackController.current?.id == track.id) command(PlaybackService.STOP)
        }
        val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val track = pendingDelete
            pendingDelete = null
            if (result.resultCode == Activity.RESULT_OK && track != null) {
                try {
                    // Android 10 grants temporary ownership; retry deletion after consent.
                    // On Android 11+ MediaStore performs the deletion during confirmation.
                    if (Build.VERSION.SDK_INT == 29) contentResolver.delete(track.uri, null, null)
                    finishDeletion(track)
                } catch (e: Exception) {
                    deleteError = "Could not remove the song: ${e.localizedMessage ?: "permission denied"}"
                }
            }
        }
        fun deleteTrack(track: Track) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val request = MediaStore.createDeleteRequest(contentResolver, listOf(track.uri))
                    pendingDelete = track
                    deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
                } else {
                    val deleted = contentResolver.delete(track.uri, null, null)
                    if (deleted > 0) finishDeletion(track)
                    else deleteError = "Android did not allow deleting this audio file."
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    pendingDelete = track
                    deleteLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } else deleteError = "Android did not allow deleting this audio file."
            } catch (e: Exception) { deleteError = "Could not request deletion: ${e.localizedMessage ?: "permission denied"}" }
        }
        var status by remember { mutableStateOf("Recognize a song using the Google app") }
        var link by remember { mutableStateOf(if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else "") }
        var audioUrl by remember { mutableStateOf("") }
        var downloadStatus by remember { mutableStateOf("") }
        var overlayEnabled by remember { mutableStateOf(false) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { songs = loadSongs(this) }
        LaunchedEffect(Unit) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED) songs = loadSongs(this@MainActivity)
            else permissions.launch(arrayOf(permission))
        }
        val appearancePrefs = remember { getSharedPreferences("pure_appearance", MODE_PRIVATE) }
        var themeChoice by remember { mutableStateOf(appearancePrefs.getString("theme", "system") ?: "system") }
        val systemDark = isSystemInDarkTheme()
        val useDark = when (themeChoice) { "light" -> false; "dark" -> true; else -> systemDark }
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !useDark && screen != "visualizer"
            controller.isAppearanceLightNavigationBars = !useDark && screen != "visualizer"
        }
        val colors = if (useDark) darkColorScheme(
            primary = purple, onPrimary = Color(0xFF1C102B), background = background,
            onBackground = Color.White, surface = panel, onSurface = Color.White,
            surfaceVariant = Color(0xFF323441), onSurfaceVariant = Color(0xFFE3DFEA),
            secondary = purple, onSecondary = Color.Black, outline = Color(0xFF858391))
        else lightColorScheme(
            primary = Color(0xFF7946B9), onPrimary = Color.White, background = Color(0xFFF8F6FC),
            onBackground = Color(0xFF211D29), surface = Color.White, onSurface = Color(0xFF211D29),
            surfaceVariant = Color(0xFFEDE7F5), onSurfaceVariant = Color(0xFF494451),
            secondary = Color(0xFF7946B9), onSecondary = Color.White, outline = Color(0xFF797382))
        val appBg = colors.background
        val appPanel = colors.surfaceVariant
        val appMuted = colors.onSurfaceVariant
        val appAccent = colors.primary
        MaterialTheme(colorScheme = colors) {
            Column(Modifier.fillMaxSize().background(appBg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (screen != "visualizer") Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 27.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PURE ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Music", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = appAccent)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { screen = "identify" }) { Icon(Icons.Default.Mic, "Identify", tint = appAccent) }
                    IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                }
                if (screen == "songs" || screen == "playlists") {
                    OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search songs or artists…", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp), singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 18.dp, end = 18.dp)) {
                        listOf("songs" to "All Songs", "playlists" to "Playlists").forEach { (key, label) ->
                            TextButton(onClick = { screen = key; selectedPlaylist = null }, modifier = Modifier.weight(1f)) { Text(label, color = if (screen == key) appAccent else appMuted) }
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
                                        Text(selectedPlaylist ?: "My Library · ${songs.size} songs", color = appMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
                                            Text(track.artist, color = appMuted, fontSize = 12.sp, maxLines = 1)
                                        }
                                        IconButton(onClick = { trackMenu = track }) { Icon(Icons.Default.MoreVert, "Song options") }
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
                                            Icon(Icons.Default.QueueMusic, null, tint = appAccent)
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text("${store.songs(name).size} songs", fontSize = 12.sp, color = appMuted) }
                                            IconButton(onClick = { store.remove(name); refresh++ }) { Icon(Icons.Default.DeleteOutline, "Delete playlist") }
                                        }
                                    }
                                }
                            }
                        }
                        "identify" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Identify & Download", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(35.dp))
                            Box(Modifier.size(150.dp).background(Brush.radialGradient(listOf(purple.copy(alpha = 0.5f), panel)), RoundedCornerShape(100.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = appAccent, modifier = Modifier.size(80.dp)) }
                            Spacer(Modifier.height(24.dp))
                            Text(status, color = appMuted)
                            Spacer(Modifier.height(15.dp))
                            Button(onClick = { status = FreeSongSearch.launch(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Mic, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Search a song with Google")
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("Song recognition opens Google. No API token is needed; Google shows the results in its own app.", color = appMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(22.dp))
                            Text("From TikTok", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                            Text("Paste a shared TikTok link to open the original video. The link alone does not grant audio download rights.", color = appMuted, fontSize = 12.sp)
                            OutlinedTextField(link, { link = it }, label = { Text("TikTok video link") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                            Button(onClick = { runCatching { val parsed = Uri.parse(link.trim()); if (parsed.scheme == "https" && (parsed.host == "tiktok.com" || parsed.host?.endsWith(".tiktok.com") == true)) startActivity(Intent(Intent.ACTION_VIEW, parsed)) else status = "Enter a valid TikTok HTTPS link." } }, modifier = Modifier.fillMaxWidth()) { Text("Open TikTok Video") }
                            Spacer(Modifier.height(20.dp))
                            Text("Recognition History", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                            key(refresh) { store.history().forEach { Text(it, modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) } }
                        }
                        "downloads" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text("Downloads", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Download from an authorized direct audio URL (HTTPS). TikTok video links cannot be used here.", fontSize = 13.sp, color = appMuted)
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
                            Text(downloadStatus, color = appMuted, fontSize = 13.sp)
                        }
                        "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text("Settings", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Appearance", fontWeight = FontWeight.Bold)
                            listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (choice, label) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    themeChoice = choice
                                    appearancePrefs.edit().putString("theme", choice).apply()
                                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = themeChoice == choice, onClick = {
                                        themeChoice = choice
                                        appearancePrefs.edit().putString("theme", choice).apply()
                                    })
                                    Text(label)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Visualizer", fontWeight = FontWeight.Bold)
                            Text("Five effects. Select and save the effect inside Now Playing or in full-screen mode.", fontSize = 12.sp, color = appMuted)
                            Spacer(Modifier.height(20.dp))
                            Text("Floating Recognition", fontWeight = FontWeight.Bold)
                            Text("Movable shortcut over other apps. Tap it to start identification in PURE Music.", fontSize = 12.sp, color = appMuted)
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
                            Text("Use Search a song with Google in Identify. PURE Music does not collect API tokens or send microphone recordings to AudD.", fontSize = 12.sp, color = appMuted)
                            Spacer(Modifier.height(14.dp))
                            Text("Audio visualizations require Android microphone permission to read the local player audio session. You can grant it directly in Now Playing.", color = appMuted, fontSize = 12.sp)
                        }
                    }
                }
                if (current != null && screen != "player" && screen != "visualizer") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                        .background(appPanel, RoundedCornerShape(16.dp)).padding(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { showPlayer() }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Cover(39)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(current.artist, fontSize = 11.sp, color = appMuted, maxLines = 1)
                                }
                                Icon(Icons.Default.KeyboardArrowUp, "Open full player", tint = appMuted)
                            }
                            IconButton(onClick = { command(PlaybackService.STOP); screen = "songs" }) {
                                Icon(Icons.Default.Close, "Close mini player and stop music", tint = appMuted)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            PlaybackModeButton()
                            IconButton(onClick = { command(PlaybackService.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                            IconButton(onClick = { command(PlaybackService.TOGGLE) }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
                            IconButton(onClick = { command(PlaybackService.NEXT) }) { Icon(Icons.Default.SkipNext, "Next") }
                            IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Queue") }
                        }
                    }
                }
                if (screen != "player" && screen != "visualizer") NavigationBar(containerColor = appBg, tonalElevation = 0.dp) {
                    listOf(Triple("songs", "Songs", Icons.Default.LibraryMusic), Triple("playlists", "Playlists", Icons.Default.QueueMusic), Triple("identify", "Identify", Icons.Default.GraphicEq), Triple("downloads", "Downloads", Icons.Default.Download)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = screen == key, onClick = { screen = key }, label = { Text(label, fontSize = 10.sp) }, icon = { Icon(icon, label) })
                    }
                }
            }
            trackMenu?.let { track ->
                AlertDialog(onDismissRequest = { trackMenu = null },
                    title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    text = { Column {
                        TextButton(onClick = {
                            val queue = PlaybackController.queue.ifEmpty { listOfNotNull(PlaybackController.current) }
                            PlaybackController.queue = QueueEditing.playAfter(queue, PlaybackController.current, track) { it.id }
                            trackMenu = null
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QueueMusic, null); Spacer(Modifier.width(10.dp)); Text("Play next", modifier = Modifier.weight(1f)) }
                        TextButton(onClick = {
                            val queue = PlaybackController.queue.ifEmpty { listOfNotNull(PlaybackController.current) }
                            PlaybackController.queue = QueueEditing.append(queue, track) { it.id }
                            trackMenu = null
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlaylistPlay, null); Spacer(Modifier.width(10.dp)); Text("Add to queue", modifier = Modifier.weight(1f)) }
                        TextButton(onClick = { trackMenu = null; addToPlaylist = track }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlaylistAdd, null); Spacer(Modifier.width(10.dp)); Text("Add to playlist", modifier = Modifier.weight(1f)) }
                        TextButton(onClick = {
                            trackMenu = null
                            val share = Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, track.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(share, "Share song"))
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(10.dp)); Text("Share", modifier = Modifier.weight(1f)) }
                        TextButton(onClick = { trackMenu = null; deleteCandidate = track }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(10.dp)); Text("Delete from phone", modifier = Modifier.weight(1f)) }
                    } }, confirmButton = { TextButton(onClick = { trackMenu = null }) { Text("Close") } })
            }
            deleteCandidate?.let { track -> AlertDialog(onDismissRequest = { deleteCandidate = null },
                title = { Text("Delete song from phone?") },
                text = { Text("${track.title} will be removed from device storage, not only from PURE Music. Android may ask for confirmation. This cannot be undone.") },
                confirmButton = { TextButton(onClick = { deleteCandidate = null; deleteTrack(track) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }) }
            deleteError?.let { message -> AlertDialog(onDismissRequest = { deleteError = null }, title = { Text("Deletion failed") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { deleteError = null }) { Text("OK") } }) }
            if (showQueue) AlertDialog(onDismissRequest = { showQueue = false },
                title = { Text("Up next") },
                text = { LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(PlaybackController.queue, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().clickable {
                            play(track, PlaybackController.queue); showQueue = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = appAccent)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = appMuted, fontSize = 12.sp)
                            }
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Close") } })
            if (showNewPlaylist) AlertDialog(onDismissRequest = { showNewPlaylist = false }, title = { Text("New Playlist") }, text = { OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Name") }) }, confirmButton = { TextButton(onClick = { store.create(playlistName); refresh++; playlistName = ""; showNewPlaylist = false }) { Text("Create") } }, dismissButton = { TextButton(onClick = { showNewPlaylist = false }) { Text("Cancel") } })
            addToPlaylist?.let { track -> AlertDialog(onDismissRequest = { addToPlaylist = null }, title = { Text("Add to Playlist") }, text = { Column { key(refresh) { store.names().forEach { name -> TextButton(onClick = { store.add(name, track.id); refresh++; addToPlaylist = null }) { Text(name) } } }; if (store.names().isEmpty()) Text("Create a playlist first.") } }, confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text("Close") } }) }
        }
    }
}

@Composable
private fun PlaybackModeButton(showLabel: Boolean = false) {
    val context = LocalContext.current
    val order = PlaybackController.order
    val icon = when (order) {
        PlaybackOrder.NORMAL -> Icons.Default.PlaylistPlay
        PlaybackOrder.SHUFFLE -> Icons.Default.Shuffle
        PlaybackOrder.REPEAT_ALL -> Icons.Default.Repeat
        PlaybackOrder.REPEAT_ONE -> Icons.Default.RepeatOne
    }
    val tint = if (order == PlaybackOrder.NORMAL) MaterialTheme.colorScheme.onSurfaceVariant
               else MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { PlaybackController.cycleOrder(context) }) {
            Icon(icon, "Playback mode: ${order.title}. Tap to switch", tint = tint)
        }
        if (showLabel) Text(order.title, color = tint, fontSize = 10.sp)
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
            Text("NOW PLAYING", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 330.dp).aspectRatio(1f)
            .background(Brush.linearGradient(listOf(Color(0xFF845BAD), Color(0xFF26395D))), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center) {
            EmbeddedVisualizer(modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        Text(track.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(5.dp))
        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Slider(value = ratio, onValueChange = { dragging = it }, onValueChangeFinished = {
            onSeek(((dragging ?: ratio) * duration).toLong()); dragging = null
        }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime((ratio * duration).toLong()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(formatPlaybackTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            PlaybackModeButton(showLabel = true)
            IconButton(onClick = { onControl(PlaybackService.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(38.dp)) }
            FilledIconButton(onClick = { onControl(PlaybackService.TOGGLE) }, modifier = Modifier.size(70.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { onControl(PlaybackService.NEXT) }) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(38.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, "Queue"); Spacer(Modifier.width(8.dp)); Text("Up next") }
            TextButton(onClick = onVisualizer) { Icon(Icons.Default.GraphicEq, "Visualizer"); Spacer(Modifier.width(8.dp)); Text("Visualizer") }
        }
        Spacer(Modifier.height(8.dp))
    }
}
private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
