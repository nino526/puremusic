package com.puremusic.app

/** Pure, deterministic queue transforms that can be unit-tested without Android. */
internal object QueueEditing {
    fun <T> append(queue: List<T>, item: T, id: (T) -> Long): List<T> =
        if (queue.any { id(it) == id(item) }) queue else queue + item

    /** Move the selected song immediately after the current song, without duplicates. */
    fun <T> playAfter(queue: List<T>, current: T?, item: T, id: (T) -> Long): List<T> {
        val remaining = queue.filterNot { id(it) == id(item) }
        val currentIndex = if (current == null) -1 else remaining.indexOfFirst { id(it) == id(current) }
        val insertAt = if (currentIndex < 0) 0 else currentIndex + 1
        return remaining.toMutableList().apply { add(insertAt, item) }
    }
}
