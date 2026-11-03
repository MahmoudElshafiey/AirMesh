package com.airmesh.protocol

/**
 * CRC-16/CCITT-FALSE (also known as CRC-16/IBM-3740).
 *
 * Parameters:
 *  - width: 16 bits
 *  - polynomial: 0x1021
 *  - initial value: 0xFFFF
 *  - input/output reflected: no
 *  - final XOR: 0x0000
 *
 * Known test vector: CRC("123456789") == 0x29B1.
 */
object Crc16 {

    const val POLYNOMIAL = 0x1021
    const val INITIAL_VALUE = 0xFFFF

    /** CRC over the whole array. */
    fun compute(data: ByteArray): Int = compute(data, 0, data.size)

    /** CRC over `data[offset until offset + length]`. */
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range [$offset, ${offset + length}) is outside data size ${data.size}"
        }

        var crc = INITIAL_VALUE
        for (i in offset until offset + length) {
            // XOR the byte into the high half of the register, then shift 8 times.
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor POLYNOMIAL
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
