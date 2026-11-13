package com.puremusic.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * No-token alternative: delegates song recognition to the user's Google app.
 * This is a separate app flow; Android exposes no public song-result API here.
 * The private MUSIC_SEARCH action may change in future Google app releases.
 */
object FreeSongSearch {
    fun launch(context: Context): String {
        val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Google Song Search opened. Results stay in the Google app."
        } catch (_: ActivityNotFoundException) {
            try {
                val fallback = context.packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
                if (fallback != null) {
                    context.startActivity(fallback)
                    "In Google, tap the microphone, then Search a song."
                } else "Install or enable the Google app to use free song search."
            } catch (_: Exception) {
                "Could not open the Google app."
            }
        } catch (_: SecurityException) {
            "Google Song Search is not available on this device."
        }
    }
}
