package com.puremusic.app
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
class IdentifyTileService : TileService() {
  override fun onStartListening() {
    super.onStartListening()
    qsTile?.let { it.label = "Identify song"; it.icon = Icon.createWithResource(this,R.drawable.nino_tile_icon); it.updateTile() }
  }
  override fun onClick() {
    super.onClick()
    val intent=Intent(this,MainActivity::class.java).apply {
      flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra("screen","identify"); putExtra("autoIdentify",true)
    }
    if(Build.VERSION.SDK_INT>=34) {
      val pending=PendingIntent.getActivity(this,1001,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      startActivityAndCollapse(pending)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}