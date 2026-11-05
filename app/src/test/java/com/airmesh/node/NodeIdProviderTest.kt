package com.airmesh.node

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeIdProviderTest {

    private class MemoryStore(var value: Int? = null) : NodeIdStore {
        override fun read(): Int? = value
        override fun write(id: Int) {
            value = id
        }
    }

    @Test
    fun generatedIdIsNonZeroAndStable() {
        val store = MemoryStore()
        val provider = NodeIdProvider(store, Random(1234))

        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertTrue("id must fit in 16 bits and be non-zero", first in 1..0xFFFF)
        assertEquals("the id must not change between calls", first, second)
        assertEquals("the generated id must be persisted", first, store.value)
    }

    @Test
    fun persistedIdIsReusedByANewProvider() {
        val store = MemoryStore(value = 4242)

        val id = NodeIdProvider(store, Random(99)).getOrCreate()

        assertEquals(4242, id)
        assertEquals("a valid stored id must not be rewritten", 4242, store.value)
    }

    @Test
    fun invalidStoredIdIsReplaced() {
        val store = MemoryStore(value = 0)

        val id = NodeIdProvider(store, Random(7)).getOrCreate()

        assertNotEquals(0, id)
        assertTrue(id in 1..0xFFFF)
        assertEquals("the replacement must be persisted", id, store.value)
    }
}
