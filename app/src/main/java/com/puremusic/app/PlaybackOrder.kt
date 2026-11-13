package com.puremusic.app

/** One playback mode button cycles through all supported playback orders. */
enum class PlaybackOrder(val title: String) {
    NORMAL("Normal"), SHUFFLE("Shuffle"), REPEAT_ALL("Repeat all"), REPEAT_ONE("Repeat one")
}

