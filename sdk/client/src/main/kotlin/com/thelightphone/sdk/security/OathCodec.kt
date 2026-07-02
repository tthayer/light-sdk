package com.thelightphone.sdk.security

/**
 * Pure encode/decode for the YKOATH protocol, split out from I/O so it can be
 * unit-tested against captured byte sequences. See the YubiKey OATH protocol
 * spec: https://developers.yubico.com/OATH/YKOATH_Protocol.html
 */
object OathCodec {
    /** OATH applet AID. */
    val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x05, 0x27, 0x21, 0x01)

    const val CLA = 0x00
    const val INS_SELECT = 0xA4
    const val INS_LIST = 0xA1
    const val INS_CALCULATE = 0xA2
    const val INS_CALCULATE_ALL = 0xA4

    // Tags
    const val TAG_NAME = 0x71
    const val TAG_NAME_LIST = 0x72
    const val TAG_CHALLENGE = 0x74
    const val TAG_FULL_RESPONSE = 0x75
    const val TAG_TRUNCATED_RESPONSE = 0x76
    const val TAG_HOTP = 0x77
    const val TAG_VERSION = 0x79
    const val TAG_TOUCH = 0x7C

    const val DEFAULT_PERIOD = 30

    private const val TYPE_MASK = 0xF0
    private const val TYPE_HOTP = 0x10
    private const val TYPE_TOTP = 0x20
    private const val ALG_MASK = 0x0F

    /** Builds the SELECT-by-AID command for the OATH applet. */
    fun selectCommand(): Apdu = Apdu(CLA, INS_SELECT, p1 = 0x04, p2 = 0x00, data = AID)

    /** Builds the LIST command. */
    fun listCommand(): Apdu = Apdu(CLA, INS_LIST)

    /** 8-byte big-endian time counter: floor(epochSeconds / period). */
    fun challenge(epochSeconds: Long, period: Int): ByteArray {
        require(period > 0) { "period must be positive" }
        var counter = epochSeconds / period
        val out = ByteArray(8)
        for (i in 7 downTo 0) {
            out[i] = (counter and 0xff).toByte()
            counter = counter shr 8
        }
        return out
    }

    /** CALCULATE ALL, requesting truncated responses (P2 = 0x01). */
    fun calculateAllCommand(epochSeconds: Long): Apdu = Apdu(
        CLA, INS_CALCULATE_ALL, p1 = 0x00, p2 = 0x01,
        data = Tlv.encode(TAG_CHALLENGE, challenge(epochSeconds, DEFAULT_PERIOD)),
        le = 256,
    )

    /** CALCULATE for a single credential, truncated (P2 = 0x01). */
    fun calculateCommand(credentialId: String, epochSeconds: Long, period: Int): Apdu {
        val body = Tlv.encode(TAG_NAME, credentialId.toByteArray(Charsets.UTF_8)) +
            Tlv.encode(TAG_CHALLENGE, challenge(epochSeconds, period))
        return Apdu(CLA, INS_CALCULATE, p1 = 0x00, p2 = 0x01, data = body, le = 256)
    }

    /**
     * Splits an OATH applet name into its `period/issuer:account` parts.
     * Examples: "Example:alice@example.com", "60/ACME:bob", "GitHub".
     */
    fun parseName(id: String): Triple<Int, String?, String> {
        var rest = id
        var period = DEFAULT_PERIOD
        val slash = rest.indexOf('/')
        if (slash in 1..rest.length - 1) {
            val maybePeriod = rest.substring(0, slash).toIntOrNull()
            if (maybePeriod != null && maybePeriod > 0) {
                period = maybePeriod
                rest = rest.substring(slash + 1)
            }
        }
        val colon = rest.indexOf(':')
        return if (colon >= 0) {
            val issuer = rest.substring(0, colon)
            val account = rest.substring(colon + 1)
            Triple(period, issuer.ifEmpty { null }, account)
        } else {
            Triple(period, null, rest)
        }
    }

    private fun algorithmOf(nibble: Int): OathAlgorithm = when (nibble) {
        0x02 -> OathAlgorithm.SHA256
        0x03 -> OathAlgorithm.SHA512
        else -> OathAlgorithm.SHA1
    }

    private fun credentialOf(id: String, typeAlgoByte: Int?): OathCredential {
        val (period, issuer, name) = parseName(id)
        val type = if (typeAlgoByte != null && (typeAlgoByte and TYPE_MASK) == TYPE_HOTP) {
            OathType.HOTP
        } else {
            OathType.TOTP
        }
        val algo = if (typeAlgoByte != null) algorithmOf(typeAlgoByte and ALG_MASK) else OathAlgorithm.SHA1
        return OathCredential(id = id, issuer = issuer, name = name, type = type, algorithm = algo, period = period)
    }

    /** Parses a LIST response body into credentials (no codes). */
    fun parseList(body: ByteArray): List<OathCredential> =
        Tlv.parseList(body)
            .filter { it.tag == TAG_NAME_LIST && it.value.isNotEmpty() }
            .map { tlv ->
                val typeAlgo = tlv.value[0].toInt() and 0xff
                val id = String(tlv.value, 1, tlv.value.size - 1, Charsets.UTF_8)
                credentialOf(id, typeAlgo)
            }

    /**
     * Decodes a `0x76`/`0x75` response value into a zero-padded code string.
     * Truncated (`0x76`) values are already dynamically truncated by the key;
     * full (`0x75`) values are truncated here per RFC 4226.
     */
    fun formatResponse(tag: Int, value: ByteArray): String {
        require(value.isNotEmpty()) { "empty OATH response value" }
        val digits = value[0].toInt() and 0xff
        // Cap at 9: 10^10 overflows Int (POW10). OATH codes are 6-8 digits.
        require(digits in 1..9) { "unexpected digit count: $digits" }
        val binary: Int = when (tag) {
            TAG_TRUNCATED_RESPONSE -> {
                require(value.size >= 5) { "truncated response too short: ${value.size}" }
                ((value[1].toInt() and 0x7f) shl 24) or
                    ((value[2].toInt() and 0xff) shl 16) or
                    ((value[3].toInt() and 0xff) shl 8) or
                    (value[4].toInt() and 0xff)
            }
            TAG_FULL_RESPONSE -> {
                val hmac = value.copyOfRange(1, value.size)
                require(hmac.size >= 20) { "full response too short: ${hmac.size}" }
                val offset = hmac[hmac.size - 1].toInt() and 0x0f
                ((hmac[offset].toInt() and 0x7f) shl 24) or
                    ((hmac[offset + 1].toInt() and 0xff) shl 16) or
                    ((hmac[offset + 2].toInt() and 0xff) shl 8) or
                    (hmac[offset + 3].toInt() and 0xff)
            }
            else -> throw IllegalArgumentException("not a response tag: 0x${tag.toString(16)}")
        }
        val mod = POW10[digits]
        return (binary % mod).toString().padStart(digits, '0')
    }

    // Indices 0..9; 10^9 fits in Int (10^10 would overflow).
    private val POW10 = IntArray(10).apply {
        this[0] = 1
        for (i in 1..9) this[i] = this[i - 1] * 10
    }

    /**
     * Parses a CALCULATE ALL response into codes. [listMetadata] (keyed by
     * credential id, from a prior LIST) supplies type/algorithm/period; missing
     * entries are inferred from the name. [epochSeconds] sets the validity
     * window.
     */
    fun parseCalculateAll(
        body: ByteArray,
        listMetadata: Map<String, OathCredential>,
        epochSeconds: Long,
    ): List<OathCode> {
        val tlvs = Tlv.parseList(body)
        val codes = ArrayList<OathCode>()
        var i = 0
        while (i < tlvs.size) {
            val nameTlv = tlvs[i]
            if (nameTlv.tag != TAG_NAME) { i += 1; continue }
            val id = String(nameTlv.value, Charsets.UTF_8)
            val cred = listMetadata[id] ?: credentialOf(id, null)
            val valueTlv = tlvs.getOrNull(i + 1)
            i += 2
            if (valueTlv == null) break

            val period = cred.period.coerceAtLeast(1)
            val validFrom = (epochSeconds / period) * period
            val validUntil = validFrom + period
            when (valueTlv.tag) {
                TAG_TRUNCATED_RESPONSE, TAG_FULL_RESPONSE -> codes.add(
                    OathCode(cred, formatResponse(valueTlv.tag, valueTlv.value), validFrom, validUntil)
                )
                TAG_TOUCH -> codes.add(
                    OathCode(cred, null, validFrom, validUntil, requiresTouch = true)
                )
                TAG_HOTP -> codes.add(
                    OathCode(cred.copy(type = OathType.HOTP), null, validFrom, validUntil)
                )
                else -> codes.add(OathCode(cred, null, validFrom, validUntil))
            }
        }
        return codes
    }
}
