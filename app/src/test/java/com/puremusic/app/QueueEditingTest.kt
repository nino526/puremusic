package com.puremusic.app

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEditingTest {
    @Test fun playbackOrderCyclesThroughFourModes() {
        assertEquals(listOf("Normal", "Shuffle", "Repeat all", "Repeat one"), PlaybackOrder.entries.map { it.title })
    }
    @Test fun metadataFallbackForMissingArtist() {
        assertEquals("Unknown artist", cleanSongArtist("<unknown>"))
        assertEquals("Unknown artist", cleanSongArtist(""))
        assertEquals("ABBA", cleanSongArtist(" ABBA "))
    }
    @Test fun metadataFallbackForMissingTitle() {
        assertEquals("Song_01", cleanSongTitle("<unknown>", "Song_01.mp3"))
        assertEquals("Known title", cleanSongTitle(" Known title ", "ignored.mp3"))
        assertEquals("Unknown song", cleanSongTitle(null, null))
    }
    @Test fun appendAddsToEnd() {
        assertEquals(listOf(1L, 2L, 3L), QueueEditing.append(listOf(1L, 2L), 3L) { it })
    }
    @Test fun appendNeverDuplicates() {
        assertEquals(listOf(1L, 2L), QueueEditing.append(listOf(1L, 2L), 2L) { it })
    }
    @Test fun playNextInsertsAfterCurrent() {
        assertEquals(listOf(1L, 3L, 2L, 4L), QueueEditing.playAfter(listOf(1L, 2L, 4L), 1L, 3L) { it })
    }
    @Test fun playNextMovesExistingItemWithoutDuplicate() {
        assertEquals(listOf(1L, 4L, 2L, 3L), QueueEditing.playAfter(listOf(1L, 2L, 3L, 4L), 1L, 4L) { it })
    }
    @Test fun playNextWithoutCurrentStartsQueue() {
        assertEquals(listOf(9L, 1L, 2L), QueueEditing.playAfter(listOf(1L, 2L), null, 9L) { it })
    }
    @Test fun playNextWhenCurrentIsLast() {
        assertEquals(listOf(1L, 2L, 9L), QueueEditing.playAfter(listOf(1L, 2L), 2L, 9L) { it })
    }
}
