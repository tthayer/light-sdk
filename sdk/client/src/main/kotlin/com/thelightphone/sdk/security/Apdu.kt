package com.thelightphone.sdk.security

import java.io.ByteArrayOutputStream

/**
 * A short-form ISO 7816-4 command APDU. YKOATH only ever needs short APDUs
 * (largest payload is a SELECT AID at 7 bytes), and long responses are pulled
 * with GET RESPONSE by [SmartCardProtocol], so extended length is unnecessary.
 */
class Apdu(
    val cla: Int,
    val ins: Int,
    val p1: Int = 0,
    val p2: Int = 0,
    val data: ByteArray = ByteArray(0),
    /** Expected max response bytes; null = no Le byte, 256 = Le 0x00. */
    val le: Int? = null,
) {
    fun toBytes(): ByteArray {
        require(data.size <= 255) { "short APDU data limited to 255 bytes, got ${data.size}" }
        val out = ByteArrayOutputStream()
        out.write(cla and 0xff)
        out.write(ins and 0xff)
        out.write(p1 and 0xff)
        out.write(p2 and 0xff)
        if (data.isNotEmpty()) {
            out.write(data.size)
            out.write(data)
        }
        if (le != null) {
            require(le in 1..256) { "Le must be 1..256, got $le" }
            out.write(if (le == 256) 0x00 else le)
        }
        return out.toByteArray()
    }
}

/** Parsed response APDU: payload plus the two status bytes. */
class ApduResponse(val data: ByteArray, val sw: Int) {
    val sw1: Int get() = (sw shr 8) and 0xff
    val sw2: Int get() = sw and 0xff
    val isSuccess: Boolean get() = sw == SW_OK

    companion object {
        const val SW_OK = 0x9000
        const val SW_MORE_DATA_PREFIX = 0x61 // sw1; sw2 = bytes remaining
        const val SW_WRONG_LE_PREFIX = 0x6c  // sw1; sw2 = correct Le
        const val SW_FILE_NOT_FOUND = 0x6a82
        const val SW_AUTH_REQUIRED = 0x6982
        const val SW_WRONG_DATA = 0x6a80

        /** Splits raw card bytes (payload || SW1 || SW2) into an [ApduResponse]. */
        fun parse(raw: ByteArray): ApduResponse {
            require(raw.size >= 2) { "response too short: ${raw.size} bytes" }
            val sw = ((raw[raw.size - 2].toInt() and 0xff) shl 8) or (raw[raw.size - 1].toInt() and 0xff)
            return ApduResponse(raw.copyOfRange(0, raw.size - 2), sw)
        }
    }
}

/**
 * A live connection to a smart card (a security key's contact/contactless
 * interface). [transceive] sends one APDU and returns the raw response
 * including the trailing status word.
 */
interface SmartCardConnection {
    suspend fun transceive(commandApdu: ByteArray): ByteArray
    fun close()
}

/**
 * Wraps a [SmartCardConnection] with ISO 7816 response handling: GET RESPONSE
 * chaining on 61xx, and Le correction on 6Cxx. Callers get a single assembled
 * [ApduResponse] per [send].
 */
class SmartCardProtocol(private val connection: SmartCardConnection) {
    /** INS for GET RESPONSE; CLA is inherited from the pending command. */
    private val insGetResponse = 0xC0

    suspend fun send(apdu: Apdu): ApduResponse {
        var response = ApduResponse.parse(connection.transceive(apdu.toBytes()))

        // 6Cxx: resend the same command with the corrected Le.
        if (response.sw1 == ApduResponse.SW_WRONG_LE_PREFIX) {
            val corrected = Apdu(apdu.cla, apdu.ins, apdu.p1, apdu.p2, apdu.data, le = response.sw2.let { if (it == 0) 256 else it })
            response = ApduResponse.parse(connection.transceive(corrected.toBytes()))
        }

        // 61xx: keep issuing GET RESPONSE until the card stops chaining.
        if (response.sw1 == ApduResponse.SW_MORE_DATA_PREFIX) {
            val acc = ByteArrayOutputStream()
            acc.write(response.data)
            var remaining = response.sw2
            while (true) {
                val le = if (remaining == 0) 256 else remaining
                val get = Apdu(apdu.cla, insGetResponse, 0, 0, le = le)
                val next = ApduResponse.parse(connection.transceive(get.toBytes()))
                acc.write(next.data)
                if (next.sw1 == ApduResponse.SW_MORE_DATA_PREFIX) {
                    remaining = next.sw2
                } else {
                    return ApduResponse(acc.toByteArray(), next.sw)
                }
            }
        }

        return response
    }
}
