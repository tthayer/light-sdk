package com.thelightphone.sdk.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OathCodecTest {
    @Test
    fun `challenge is big-endian time over period`() {
        // t=59, period=30 -> counter 1
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), OathCodec.challenge(59, 30))
        // t=90, period=30 -> counter 3
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 3), OathCodec.challenge(90, 30))
    }

    @Test
    fun `parseName handles period issuer and account`() {
        assertEquals(Triple(30, "Example", "alice@example.com"), OathCodec.parseName("Example:alice@example.com"))
        assertEquals(Triple(60, "ACME", "bob"), OathCodec.parseName("60/ACME:bob"))
        assertEquals(Triple(30, null, "GitHub"), OathCodec.parseName("GitHub"))
    }

    @Test
    fun `formatResponse truncated masks high bit and mods by digits`() {
        // digits=6, int = 0x7FFFFFFF -> 2147483647 % 1_000_000 = 483647
        val value = byteArrayOf(6, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals("483647", OathCodec.formatResponse(OathCodec.TAG_TRUNCATED_RESPONSE, value))
    }

    @Test
    fun `formatResponse zero-pads short codes`() {
        // int=42 -> "000042"
        val value = byteArrayOf(6, 0, 0, 0, 42)
        assertEquals("000042", OathCodec.formatResponse(OathCodec.TAG_TRUNCATED_RESPONSE, value))
    }

    @Test
    fun `parseList decodes type and algorithm`() {
        val totpSha1 = ByteArray(1 + 3).also { it[0] = 0x21; "abc".toByteArray().copyInto(it, 1) }  // TOTP|SHA1
        val hotpSha256 = ByteArray(1 + 3).also { it[0] = 0x12; "xyz".toByteArray().copyInto(it, 1) } // HOTP|SHA256
        val body = Tlv.encode(OathCodec.TAG_NAME_LIST, totpSha1) + Tlv.encode(OathCodec.TAG_NAME_LIST, hotpSha256)
        val creds = OathCodec.parseList(body)
        assertEquals(2, creds.size)
        assertEquals(OathType.TOTP, creds[0].type)
        assertEquals(OathAlgorithm.SHA1, creds[0].algorithm)
        assertEquals(OathType.HOTP, creds[1].type)
        assertEquals(OathAlgorithm.SHA256, creds[1].algorithm)
    }

    @Test
    fun `parseCalculateAll marks touch and hotp with null value`() {
        val body = Tlv.encode(OathCodec.TAG_NAME, "T:a".toByteArray()) +
            Tlv.encode(OathCodec.TAG_TOUCH, ByteArray(0)) +
            Tlv.encode(OathCodec.TAG_NAME, "H:b".toByteArray()) +
            Tlv.encode(OathCodec.TAG_HOTP, ByteArray(0))
        val codes = OathCodec.parseCalculateAll(body, emptyMap(), epochSeconds = 0)
        assertEquals(2, codes.size)
        assertNull(codes[0].value)
        assertEquals(true, codes[0].requiresTouch)
        assertNull(codes[1].value)
        assertEquals(OathType.HOTP, codes[1].credential.type)
    }
}
