package com.maxrave.domain.mediaservice.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayNextInsertionTest {
    @Test
    fun insertsImmediatelyAfterTheCurrentTrack() {
        assertEquals(0, playNextInsertionIndex(currentIndex = -1, queueSize = 0))
        assertEquals(0, playNextInsertionIndex(currentIndex = 0, queueSize = 0))
        assertEquals(1, playNextInsertionIndex(currentIndex = 0, queueSize = 1))
        assertEquals(3, playNextInsertionIndex(currentIndex = 2, queueSize = 5))
        assertEquals(5, playNextInsertionIndex(currentIndex = 4, queueSize = 5))
    }

    @Test
    fun refusesIndicesOutsideTheQueue() {
        assertNull(playNextInsertionIndex(currentIndex = -2, queueSize = 5))
        assertNull(playNextInsertionIndex(currentIndex = 5, queueSize = 5))
    }
}
