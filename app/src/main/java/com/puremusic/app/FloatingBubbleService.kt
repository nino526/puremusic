package com.puremusic.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.TextView

/** User-controlled shortcut over other apps. No background microphone capture. */
class FloatingBubbleService : Service() {
    private lateinit var manager: WindowManager
    private var bubble: View? = null
    private val channel = "pure_music_overlay"
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(channel, "Floating recognition shortcut", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("screen", "identify"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PURE Music")
            .setContentText("Tap the floating button to identify a song")
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(42, notification)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "♫"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(154, 92, 244), Color.rgb(83, 56, 145))).apply { cornerRadius = 100f }
            elevation = 12f
        }
        val size = (62 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 20; y = 300
        }
        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = params.x; downY = params.y; touchX = event.rawX; touchY = event.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { params.x = downX + (event.rawX - touchX).toInt(); params.y = downY + (event.rawY - touchY).toInt(); manager.updateViewLayout(view, params); true }
                android.view.MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - touchX) < 20 && kotlin.math.abs(event.rawY - touchY) < 20) {
                        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "identify")
                        startActivity(intent)
                    }; true
                }
                else -> false
            }
        }
        manager.addView(view, params); bubble = view
    }
    override fun onDestroy() { bubble?.let { manager.removeView(it) }; super.onDestroy() }
}
