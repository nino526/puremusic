package com.puremusic.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Track(val id: Long, val title: String, val artist: String, val duration: Long, val uri: Uri)

fun loadSongs(context: Context): List<Track> {
    val out = mutableListOf<Track>()
    val collection = if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME)
    try {
        context.contentResolver.query(collection, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val key = cursor.getLong(id)
                out.add(Track(key, cleanSongTitle(cursor.getString(title), cursor.getString(displayName)), cleanSongArtist(cursor.getString(artist)), cursor.getLong(duration), ContentUris.withAppendedId(collection, key)))
            }
        }
    } catch (_: SecurityException) { }
    return out
}

class PlaylistStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pure_music", Context.MODE_PRIVATE)
    fun names(): List<String> = data().keys().asSequence().toList().sorted()
    fun songs(name: String): List<Long> {
        val array = data().optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.optLong(it) }
    }
    fun create(name: String) {
        if (name.isBlank()) return
        val json = data(); if (!json.has(name.trim())) json.put(name.trim(), JSONArray()); save(json)
    }
    fun add(name: String, id: Long) {
        val json = data(); val arr = json.optJSONArray(name) ?: JSONArray()
        if ((0 until arr.length()).none { arr.optLong(it) == id }) arr.put(id)
        json.put(name, arr); save(json)
    }
    fun remove(name: String) { val json = data(); json.remove(name); save(json) }
    /** Purge deleted tracks from saved playlists, keeping their other tracks intact. */
    fun removeSongEverywhere(id: Long) {
        val json = data()
        val names = json.keys().asSequence().toList()
        for (name in names) {
            val existing = json.optJSONArray(name) ?: continue
            val kept = JSONArray()
            for (i in 0 until existing.length()) {
                val trackId = existing.optLong(i)
                if (trackId != id) kept.put(trackId)
            }
            json.put(name, kept)
        }
        save(json)
    }
    fun history(): List<String> {
        val arr = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }
    fun appendHistory(item: String) {
        val arr = JSONArray(); arr.put(item)
        history().filter { it != item }.take(99).forEach { arr.put(it) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
    private fun data() = try { JSONObject(prefs.getString("playlists", "{}")) } catch (_: Exception) { JSONObject() }
    private fun save(json: JSONObject) { prefs.edit().putString("playlists", json.toString()).apply() }
}
