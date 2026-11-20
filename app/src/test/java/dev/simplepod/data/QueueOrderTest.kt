package dev.simplepod.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOrderTest {
    private val ids = listOf(1L, 2L, 3L, 4L)

    @Test
    fun movesUpAndDown() {
        assertEquals(listOf(1L, 3L, 2L, 4L), moveItem(ids, 3, -1))
        assertEquals(listOf(2L, 1L, 3L, 4L), moveItem(ids, 1, 1))
    }

    @Test
    fun clampsAtEnds() {
        assertEquals(ids, moveItem(ids, 1, -1))
        assertEquals(ids, moveItem(ids, 4, 1))
        assertEquals(listOf(2L, 3L, 4L, 1L), moveItem(ids, 1, 10))
    }

    @Test
    fun ignoresUnknownIds() {
        assertEquals(ids, moveItem(ids, 99, 1))
    }
}
