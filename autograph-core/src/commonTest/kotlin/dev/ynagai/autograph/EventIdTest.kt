package dev.ynagai.autograph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventIdTest {

    @Test
    fun uuidV7IdsAreVersion7() {
        val id = EventId.UuidV7.next()
        assertEquals(36, id.length)
        assertEquals('7', id[14], "version nibble must be 7 in $id")
    }

    @Test
    fun uuidV7IdsAreLexicographicallyMonotonic() {
        val ids = List(1_000) { EventId.UuidV7.next() }
        assertEquals(ids, ids.sorted(), "consecutive UUIDv7 ids must sort in generation order")
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun uuidV4IdsAreVersion4() {
        val id = EventId.UuidV4.next()
        assertEquals(36, id.length)
        assertEquals('4', id[14])
    }
}
