package com.thelightphone.sdk.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A software YKOATH card used for the emulator demo (no USB/NFC hardware on an
 * emulator) and for unit tests. It speaks the same APDU protocol as a real key,
 * computing genuine RFC 6238 TOTP codes with [javax.crypto.Mac], so it exercises
 * the exact [OathCodec]/[OathSession] paths the hardware transports use.
 */
class InMemoryOathCard(private val credentials: List<Entry>) : SmartCardConnection {

    /** [secret] is the raw HMAC key bytes (already Base32-decoded). */
    data class Entry(
        val id: String,
        val secret: ByteArray,
        val type: OathType = OathType.TOTP,
        val algorithm: OathAlgorithm = OathAlgorithm.SHA1,
        val period: Int = OathCodec.DEFAULT_PERIOD,
        val digits: Int = 6,
    )

    override suspend fun transceive(commandApdu: ByteArray): ByteArray {
        val c = ParsedCommand.parse(commandApdu)
        return when {
            c.ins == OathCodec.INS_SELECT && c.p1 == 0x04 -> respond(Tlv.encode(OathCodec.TAG_VERSION, byteArrayOf(5, 4, 3)))
            c.ins == OathCodec.INS_LIST -> respond(buildList())
            c.ins == OathCodec.INS_CALCULATE_ALL -> respond(buildCalculateAll(c.data))
            c.ins == OathCodec.INS_CALCULATE -> respond(buildCalculate(c.data))
            else -> statusOnly(0x6D00) // INS not supported
        }
    }

    override fun close() {}

    private fun typeAlgoByte(e: Entry): Int {
        val type = if (e.type == OathType.HOTP) 0x10 else 0x20
        val algo = when (e.algorithm) {
            OathAlgorithm.SHA1 -> 0x01
            OathAlgorithm.SHA256 -> 0x02
            OathAlgorithm.SHA512 -> 0x03
        }
        return type or algo
    }

    private fun buildList(): ByteArray {
        val out = ArrayList<Byte>()
        for (e in credentials) {
            val nameBytes = e.id.toByteArray(Charsets.UTF_8)
            val value = ByteArray(nameBytes.size + 1)
            value[0] = typeAlgoByte(e).toByte()
            System.arraycopy(nameBytes, 0, value, 1, nameBytes.size)
            out.addAll(Tlv.encode(OathCodec.TAG_NAME_LIST, value).toList())
        }
        return out.toByteArray()
    }

    private fun challengeFromRequest(data: ByteArray): ByteArray =
        Tlv.parseList(data).first { it.tag == OathCodec.TAG_CHALLENGE }.value

    private fun buildCalculateAll(data: ByteArray): ByteArray {
        val challenge = challengeFromRequest(data)
        val out = ArrayList<Byte>()
        for (e in credentials) {
            out.addAll(Tlv.encode(OathCodec.TAG_NAME, e.id.toByteArray(Charsets.UTF_8)).toList())
            if (e.type == OathType.HOTP) {
                out.addAll(Tlv.encode(OathCodec.TAG_HOTP, ByteArray(0)).toList())
            } else {
                out.addAll(Tlv.encode(OathCodec.TAG_TRUNCATED_RESPONSE, truncated(e, challenge)).toList())
            }
        }
        return out.toByteArray()
    }

    private fun buildCalculate(data: ByteArray): ByteArray {
        val tlvs = Tlv.parseList(data)
        val id = String(tlvs.first { it.tag == OathCodec.TAG_NAME }.value, Charsets.UTF_8)
        val challenge = tlvs.first { it.tag == OathCodec.TAG_CHALLENGE }.value
        val entry = credentials.firstOrNull { it.id == id } ?: return statusBody(0x6A82)
        return Tlv.encode(OathCodec.TAG_TRUNCATED_RESPONSE, truncated(entry, challenge))
    }

    /** [digits, 4-byte dynamically-truncated int] — the 0x76 response body. */
    private fun truncated(e: Entry, challenge: ByteArray): ByteArray {
        val algo = when (e.algorithm) {
            OathAlgorithm.SHA1 -> "HmacSHA1"
            OathAlgorithm.SHA256 -> "HmacSHA256"
            OathAlgorithm.SHA512 -> "HmacSHA512"
        }
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(e.secret, algo))
        val h = mac.doFinal(challenge)
        val off = h[h.size - 1].toInt() and 0x0f
        return byteArrayOf(
            e.digits.toByte(),
            (h[off].toInt() and 0x7f).toByte(),
            h[off + 1],
            h[off + 2],
            h[off + 3],
        )
    }

    private fun respond(body: ByteArray): ByteArray = body + byteArrayOf(0x90.toByte(), 0x00)
    private fun statusOnly(sw: Int): ByteArray = statusBody(sw)
    private fun statusBody(sw: Int): ByteArray = byteArrayOf(((sw shr 8) and 0xff).toByte(), (sw and 0xff).toByte())

    private class ParsedCommand(val cla: Int, val ins: Int, val p1: Int, val p2: Int, val data: ByteArray) {
        companion object {
            fun parse(apdu: ByteArray): ParsedCommand {
                require(apdu.size >= 4) { "command too short" }
                val cla = apdu[0].toInt() and 0xff
                val ins = apdu[1].toInt() and 0xff
                val p1 = apdu[2].toInt() and 0xff
                val p2 = apdu[3].toInt() and 0xff
                var data = ByteArray(0)
                if (apdu.size > 5) {
                    val lc = apdu[4].toInt() and 0xff
                    if (lc > 0 && 5 + lc <= apdu.size) {
                        data = apdu.copyOfRange(5, 5 + lc)
                    }
                }
                return ParsedCommand(cla, ins, p1, p2, data)
            }
        }
    }
}
