package com.thelightphone.sdk.security

import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [SmartCardConnection] over NFC ISO-DEP (ISO 14443-4). The [isoDep] must
 * already be connected, and reader mode must stay enabled for the whole
 * session — if the tag leaves the field, [transceive] throws and the read
 * fails. [close] closes the underlying tag connection.
 */
internal class NfcSmartCardConnection(private val isoDep: IsoDep) : SmartCardConnection {

    override suspend fun transceive(commandApdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        isoDep.transceive(commandApdu)
    }

    override fun close() {
        try {
            isoDep.close()
        } catch (_: Exception) {
        }
    }
}
