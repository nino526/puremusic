package com.puremusic.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Records an explicit 12-second microphone sample and sends it to AudD, with a user-supplied API token. */
object Recognition {
    fun captureAndIdentify(context: Context, token: String, result: (String) -> Unit) {
        if (token.isBlank()) { result("Set your AudD API token in Settings first."); return }
        val file = File(context.cacheDir, "recognition_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare(); recorder.start()
        } catch (e: Exception) { runCatching { recorder.release() }; result("Could not record audio: ${e.message}"); return }
        Thread {
            try {
                Thread.sleep(12000)
                recorder.stop(); recorder.release()
                val boundary = "PureMusicBoundary${System.currentTimeMillis()}"
                val connection = URL("https://api.audd.io/").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 20000; connection.readTimeout = 30000
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.outputStream.use { out ->
                    fun part(name: String, value: String) {
                        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                    }
                    part("api_token", token)
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                    file.inputStream().use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val raw = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                val response = JSONObject(raw)
                val song = response.optJSONObject("result")
                val description = if (song != null) "${song.optString("title", "Unknown")} — ${song.optString("artist", "Unknown")}" else "No song identified. Try a clearer sample."
                result(description)
                connection.disconnect()
            } catch (e: Exception) { runCatching { recorder.release() }; result("Recognition failed: ${e.message}") }
            finally { file.delete() }
        }.start()
    }
}
