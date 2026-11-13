package com.puremusic.app

internal fun cleanSongTitle(title: String?, filename: String?): String {
    val normalized = title?.trim().orEmpty()
    if (normalized.isNotEmpty() && !normalized.equals("<unknown>", ignoreCase = true)) return normalized
    return filename?.trim()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Unknown song"
}

internal fun cleanSongArtist(artist: String?): String =
    artist?.trim()?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
        ?: "Unknown artist"
