package com.puremusic.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One playback state for the full-screen player, mini-player and Android media controls. */
object PlaybackController {
    var current by mutableStateOf<Track?>(null)
        internal set
    var playing by mutableStateOf(false)
        internal set
    var position by mutableStateOf(0L)
    /** The actual MediaPlayer session, updated whenever a track changes. */
    var audioSessionId by mutableStateOf(0)
        internal set
    var order by mutableStateOf(PlaybackOrder.NORMAL)
        private set
    private var orderLoaded = false
    fun loadOrder(context: Context) {
        if (orderLoaded) return
        val ordinal = context.getSharedPreferences("pure_playback", Context.MODE_PRIVATE)
            .getInt("playback_order", 0)
        order = PlaybackOrder.entries.getOrElse(ordinal) { PlaybackOrder.NORMAL }
        orderLoaded = true
    }
    fun cycleOrder(context: Context) {
        loadOrder(context)
        order = PlaybackOrder.entries[(order.ordinal + 1) % PlaybackOrder.entries.size]
        context.getSharedPreferences("pure_playback", Context.MODE_PRIVATE)
            .edit().putInt("playback_order", order.ordinal).apply()
    }
    var queue by mutableStateOf<List<Track>>(emptyList())
    fun send(context: Context, action: String, track: Track? = null) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        if (track != null) intent.putExtra("trackId", track.id)
        if (Build.VERSION.SDK_INT >= 26 && action == PlaybackService.PLAY) context.startForegroundService(intent)
        else context.startService(intent)
    }
}

class PlaybackService : Service() {
    companion object {
        const val PLAY = "com.puremusic.app.PLAY"
        const val TOGGLE = "com.puremusic.app.TOGGLE"
        const val NEXT = "com.puremusic.app.NEXT"
        const val PREVIOUS = "com.puremusic.app.PREVIOUS"
        const val SEEK = "com.puremusic.app.SEEK"
        const val STOP = "com.puremusic.app.STOP"
        private const val CHANNEL = "pure_music_playback"
        private const val NOTIFICATION_ID = 42
    }
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private var activeQueue: List<Track> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdate = object : Runnable {
        override fun run() {
            runCatching { PlaybackController.position = player?.currentPosition?.toLong() ?: 0L }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackController.loadOrder(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW)
        )
        handler.post(progressUpdate)
        session = MediaSession(this, "PURE Music").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = toggleIfPaused()
                override fun onPause() = pause()
                override fun onSkipToNext() = step(1)
                override fun onSkipToPrevious() = step(-1)
                override fun onSeekTo(pos: Long) = seek(pos)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                // A notification is shown immediately, before MediaPlayer.prepare() takes time.
                if (PlaybackController.queue.isNotEmpty()) activeQueue = PlaybackController.queue
                val trackId = intent.getLongExtra("trackId", -1L)
                val track = activeQueue.firstOrNull { it.id == trackId }
                    ?: loadSongs(this).firstOrNull { it.id == trackId }
                if (track != null) {
                    PlaybackController.current = track
                    session.isActive = true // Restore session after a previous STOP.
                    startForeground(NOTIFICATION_ID, notification())
                    playTrack(track)
                } else stopSelf()
            }
            TOGGLE -> if (player == null && PlaybackController.current != null) playTrack(PlaybackController.current!!) else toggle()
            NEXT -> step(1)
            PREVIOUS -> step(-1)
            SEEK -> seek(intent.getLongExtra("position", 0L))
            STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun playTrack(track: Track) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@PlaybackService, track.uri)
                setOnCompletionListener {
                    if (PlaybackController.order == PlaybackOrder.REPEAT_ONE) {
                        seekTo(0); start(); sync()
                    } else step(1, completion = true)
                }
                prepare()
                start()
            }
            PlaybackController.audioSessionId = player?.audioSessionId ?: 0
            PlaybackController.current = track
            PlaybackController.playing = true
            PlaybackController.position = 0L
            sync()
        } catch (_: Exception) {
            player?.release(); player = null
            PlaybackController.audioSessionId = 0
            PlaybackController.playing = false
            sync()
        }
    }
    private fun toggleIfPaused() { if (player?.isPlaying != true) toggle() }
    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        sync()
    }
    private fun pause() { player?.takeIf { it.isPlaying }?.pause(); sync() }
    private fun step(direction: Int, completion: Boolean = false) {
        val current = PlaybackController.current ?: return
        // Always consult the live queue after items have been added during playback.
        val queue = PlaybackController.queue.ifEmpty { activeQueue.ifEmpty { loadSongs(this) } }
        val index = queue.indexOfFirst { it.id == current.id }
        if (queue.isEmpty()) return
        val order = PlaybackController.order
        // Normal mode finishes at the end; Repeat all wraps; Repeat one restarts in the
        // completion callback; Shuffle chooses a different track where possible.
        if (completion && order == PlaybackOrder.NORMAL && index == queue.lastIndex) {
            pause()
            seek(0)
            return
        }
        val nextIndex = if (order == PlaybackOrder.SHUFFLE && queue.size > 1) {
            queue.indices.filter { it != index }.random()
        } else ((index + direction + queue.size) % queue.size)
        playTrack(queue[nextIndex])
    }
    private fun seek(position: Long) {
        player?.let { it.seekTo(position.coerceIn(0, it.duration.toLong()).toInt()) }
        sync()
    }
    private fun sync() {
        val isPlaying = player?.isPlaying == true
        PlaybackController.playing = isPlaying
        PlaybackController.position = player?.currentPosition?.toLong() ?: 0L
        val state = AndroidPlaybackState.Builder().setActions(
            AndroidPlaybackState.ACTION_PLAY or AndroidPlaybackState.ACTION_PAUSE or
                AndroidPlaybackState.ACTION_PLAY_PAUSE or AndroidPlaybackState.ACTION_SKIP_TO_NEXT or
                AndroidPlaybackState.ACTION_SKIP_TO_PREVIOUS or AndroidPlaybackState.ACTION_SEEK_TO or
                AndroidPlaybackState.ACTION_STOP
        ).setState(if (isPlaying) AndroidPlaybackState.STATE_PLAYING else AndroidPlaybackState.STATE_PAUSED,
            PlaybackController.position, if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime()).build()
        session.setPlaybackState(state)
        PlaybackController.current?.let { track ->
            session.setMetadata(android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.duration).build())
            startForeground(NOTIFICATION_ID, notification())
        }
    }
    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(): Notification {
        val track = PlaybackController.current
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("screen", "player")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val isPlaying = PlaybackController.playing
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "PURE Music")
            .setContentText(track?.artist ?: "Music player")
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", actionIntent(PREVIOUS)).build())
            .addAction(Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play", actionIntent(TOGGLE)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", actionIntent(NEXT)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
    private fun stopPlayback() {
        player?.release(); player = null
        PlaybackController.audioSessionId = 0
        PlaybackController.playing = false
        PlaybackController.position = 0L
        PlaybackController.current = null
        PlaybackController.queue = emptyList()
        activeQueue = emptyList()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacks(progressUpdate)
        player?.release()
        session.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
