package com.thelightphone.sdk.security

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApduTest {
    @Test
    fun `encode case 1 - no data no le`() {
        assertContentEquals(byteArrayOf(0x00, 0xA1.toByte(), 0, 0), Apdu(0x00, 0xA1).toBytes())
    }

    @Test
    fun `encode case 4 - data and le`() {
        val apdu = Apdu(0x00, 0xA4, p1 = 0x04, data = byteArrayOf(0xA0.toByte(), 0x01), le = 256)
        assertContentEquals(
            byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x02, 0xA0.toByte(), 0x01, 0x00),
            apdu.toBytes(),
        )
    }

    @Test
    fun `parse splits payload and status word`() {
        val r = ApduResponse.parse(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x90.toByte(), 0x00))
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), r.data)
        assertEquals(0x9000, r.sw)
        assertTrue(r.isSuccess)
    }

    /** A connection that replays a fixed script of responses, in order. */
    private class ScriptedConnection(private val responses: List<ByteArray>) : SmartCardConnection {
        val sent = mutableListOf<ByteArray>()
        private var i = 0
        override suspend fun transceive(commandApdu: ByteArray): ByteArray {
            sent.add(commandApdu)
            return responses[i++]
        }
        override fun close() {}
    }

    @Test
    fun `protocol assembles GET RESPONSE chain`() = runBlocking {
        val conn = ScriptedConnection(
            listOf(
                byteArrayOf(0x11, 0x22, 0x61, 0x02),       // more data: 2 bytes remain
                byteArrayOf(0x33, 0x44, 0x90.toByte(), 0x00), // final chunk + OK
            )
        )
        val response = SmartCardProtocol(conn).send(Apdu(0x00, 0xA1))
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), response.data)
        assertTrue(response.isSuccess)
        // second command must be GET RESPONSE (INS 0xC0)
        assertEquals(0xC0, conn.sent[1][1].toInt() and 0xff)
    }

    @Test
    fun `protocol retries on wrong-le`() = runBlocking {
        val conn = ScriptedConnection(
            listOf(
                byteArrayOf(0x6C, 0x05),                        // wrong Le, correct = 5
                byteArrayOf(1, 2, 3, 4, 5, 0x90.toByte(), 0x00),
            )
        )
        val response = SmartCardProtocol(conn).send(Apdu(0x00, 0xA1, le = 256))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), response.data)
        assertEquals(0x05, conn.sent[1].last().toInt() and 0xff) // resent with Le=5
    }
}
