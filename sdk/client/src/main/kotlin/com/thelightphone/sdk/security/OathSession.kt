package com.thelightphone.sdk.security

/** Thrown when the OATH applet returns a non-success status word or is absent. */
class OathException(message: String, val statusWord: Int? = null) : Exception(message)

/**
 * A live YKOATH session over a [SmartCardConnection]. Construct it with an open
 * connection; [select] runs automatically on first use. Not thread-safe — drive
 * it from a single coroutine.
 *
 * Password-protected applets are not supported yet: [select] fails fast if the
 * key requires a password, rather than silently returning nothing.
 */
class OathSession(connection: SmartCardConnection) {
    private val protocol = SmartCardProtocol(connection)
    private var selected = false

    private suspend fun ensureSelected() {
        if (selected) return
        val response = protocol.send(OathCodec.selectCommand())
        if (!response.isSuccess) {
            throw OathException("OATH applet SELECT failed", response.sw)
        }
        val tlvs = Tlv.parseList(response.data)
        if (tlvs.any { it.tag == OathCodec.TAG_CHALLENGE }) {
            throw OathException("security key OATH is password-protected (not supported)")
        }
        selected = true
    }

    /** Lists credentials stored on the key (no codes computed). */
    suspend fun listCredentials(): List<OathCredential> {
        ensureSelected()
        val response = protocol.send(OathCodec.listCommand())
        if (!response.isSuccess) throw OathException("OATH LIST failed", response.sw)
        return OathCodec.parseList(response.data)
    }

    /**
     * Computes codes for every credential at [epochSeconds]. Standard 30-second
     * TOTP credentials come straight from CALCULATE ALL; credentials with a
     * non-default period are recomputed individually so their code matches the
     * period the credential was actually programmed with. Touch-required and
     * HOTP credentials are returned with a null value and the appropriate flag.
     */
    suspend fun calculateAll(epochSeconds: Long): List<OathCode> {
        ensureSelected()
        val metadata = listCredentials().associateBy { it.id }
        val response = protocol.send(OathCodec.calculateAllCommand(epochSeconds))
        if (!response.isSuccess) throw OathException("OATH CALCULATE ALL failed", response.sw)
        val codes = OathCodec.parseCalculateAll(response.data, metadata, epochSeconds)

        return codes.map { code ->
            if (code.hasValue && code.credential.period != OathCodec.DEFAULT_PERIOD &&
                code.credential.type == OathType.TOTP
            ) {
                recalculate(code.credential, epochSeconds) ?: code
            } else {
                code
            }
        }
    }

    /** Recomputes one credential with its own period; null on failure/touch. */
    private suspend fun recalculate(credential: OathCredential, epochSeconds: Long): OathCode? {
        val response = protocol.send(
            OathCodec.calculateCommand(credential.id, epochSeconds, credential.period)
        )
        if (!response.isSuccess) return null
        val tlv = Tlv.parseList(response.data).firstOrNull() ?: return null
        val period = credential.period.coerceAtLeast(1)
        val validFrom = (epochSeconds / period) * period
        return when (tlv.tag) {
            OathCodec.TAG_TRUNCATED_RESPONSE, OathCodec.TAG_FULL_RESPONSE ->
                OathCode(credential, OathCodec.formatResponse(tlv.tag, tlv.value), validFrom, validFrom + period)
            OathCodec.TAG_TOUCH ->
                OathCode(credential, null, validFrom, validFrom + period, requiresTouch = true)
            else -> null
        }
    }
}
