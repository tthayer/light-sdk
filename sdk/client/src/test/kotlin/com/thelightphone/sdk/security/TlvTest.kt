package com.thelightphone.sdk.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TlvTest {
    @Test
    fun `encode short-form length`() {
        val encoded = Tlv.encode(0x71, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(0x71, 0x03, 1, 2, 3), encoded)
    }

    @Test
    fun `encode long-form length above 127`() {
        val value = ByteArray(200) { 0x41 }
        val encoded = Tlv.encode(0x72, value)
        assertEquals(0x72, encoded[0].toInt() and 0xff)
        assertEquals(0x81, encoded[1].toInt() and 0xff)
        assertEquals(200, encoded[2].toInt() and 0xff)
        assertEquals(203, encoded.size)
    }

    @Test
    fun `round-trip several elements`() {
        val bytes = Tlv.encode(0x71, "abc".toByteArray()) + Tlv.encode(0x76, byteArrayOf(6, 1, 2, 3, 4))
        val parsed = Tlv.parseList(bytes)
        assertEquals(2, parsed.size)
        assertEquals(0x71, parsed[0].tag)
        assertContentEquals("abc".toByteArray(), parsed[0].value)
        assertEquals(0x76, parsed[1].tag)
    }

    @Test
    fun `parse rejects truncated element`() {
        assertFailsWith<IllegalArgumentException> {
            Tlv.parseList(byteArrayOf(0x71, 0x05, 1, 2)) // claims 5, only 2 present
        }
    }
}
