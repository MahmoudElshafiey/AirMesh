package com.airmesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Crc16Test {

    @Test
    fun ccittFalseKnownVector() {
        // The standard check value for CRC-16/CCITT-FALSE.
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Crc16.compute(check))
    }

    @Test
    fun emptyInputReturnsInitialValue() {
        assertEquals(Crc16.INITIAL_VALUE, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun offsetAndLengthMatchSlice() {
        val data = "xx123456789yy".toByteArray(Charsets.US_ASCII)
        val slice = data.copyOfRange(2, 11)
        assertEquals(Crc16.compute(slice), Crc16.compute(data, 2, 9))
    }

    @Test
    fun rejectsOutOfBoundsRange() {
        assertThrows(IllegalArgumentException::class.java) {
            Crc16.compute(ByteArray(4), 2, 3)
        }
    }
}
