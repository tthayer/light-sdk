package com.thelightphone.sdk.security

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A [SmartCardConnection] over a USB CCID (chip-card interface) device such as a
 * plugged-in YubiKey. Wraps APDUs in CCID PC_to_RDR_XfrBlock messages and
 * unwraps RDR_to_PC_DataBlock responses. See the USB CCID spec, section 6.
 */
class UsbCcidConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : SmartCardConnection {

    private var sequence = 0
    private val timeoutMs = 5000

    override suspend fun transceive(commandApdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        xfrBlock(commandApdu)
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    /** PC_to_RDR_IccPowerOn — returns the ATR (discarded; we just need the card powered). */
    private fun powerOn() {
        writeMessage(MSG_POWER_ON, extra = byteArrayOf(0x00, 0x00, 0x00), body = ByteArray(0))
        readDataBlock()
    }

    private fun xfrBlock(apdu: ByteArray): ByteArray {
        writeMessage(MSG_XFR_BLOCK, extra = byteArrayOf(0x00, 0x00, 0x00), body = apdu)
        var block = readDataBlock()
        // bmCommandStatus == "time extension requested": card is still working.
        while ((block.status.toInt() and 0xC0) == 0x80) {
            block = readDataBlock()
        }
        return block.data
    }

    private fun writeMessage(type: Int, extra: ByteArray, body: ByteArray) {
        require(extra.size == 3)
        val msg = ByteArray(10 + body.size)
        msg[0] = type.toByte()
        msg[1] = (body.size and 0xff).toByte()
        msg[2] = ((body.size shr 8) and 0xff).toByte()
        msg[3] = ((body.size shr 16) and 0xff).toByte()
        msg[4] = ((body.size shr 24) and 0xff).toByte()
        msg[5] = 0x00 // bSlot
        msg[6] = (sequence++ and 0xff).toByte() // bSeq
        msg[7] = extra[0]
        msg[8] = extra[1]
        msg[9] = extra[2]
        body.copyInto(msg, 10)

        var offset = 0
        while (offset < msg.size) {
            val chunk = minOf(bulkOut.maxPacketSize, msg.size - offset)
            val sent = connection.bulkTransfer(bulkOut, msg.copyOfRange(offset, offset + chunk), chunk, timeoutMs)
            // A 0 return (stall / zero-length packet) makes no progress; treat it
            // as failure rather than spinning forever.
            if (sent <= 0) throw OathException("USB bulk write stalled at offset $offset")
            offset += sent
        }
    }

    private class DataBlock(val status: Byte, val data: ByteArray)

    /** Reads one RDR_to_PC_DataBlock, reassembling across multiple bulk-in URBs. */
    private fun readDataBlock(): DataBlock {
        val acc = ByteArrayOutputStream()
        val buffer = ByteArray(bulkIn.maxPacketSize.coerceAtLeast(64))
        var declaredLength = -1
        while (true) {
            val read = connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
            // <= 0 makes no progress (error / stall / ZLP); fail instead of spinning.
            if (read <= 0) throw OathException("USB bulk read failed")
            acc.write(buffer, 0, read)
            val current = acc.toByteArray()
            if (declaredLength < 0 && current.size >= 10) {
                declaredLength = (current[1].toInt() and 0xff) or
                    ((current[2].toInt() and 0xff) shl 8) or
                    ((current[3].toInt() and 0xff) shl 16) or
                    ((current[4].toInt() and 0xff) shl 24)
                // Guard against a malfunctioning device claiming an absurd length.
                if (declaredLength < 0 || declaredLength > MAX_RESPONSE_BYTES) {
                    throw OathException("USB CCID response length out of range: $declaredLength")
                }
            }
            if (declaredLength >= 0 && current.size >= 10 + declaredLength) {
                val status = current[7]
                return DataBlock(status, current.copyOfRange(10, 10 + declaredLength))
            }
        }
    }

    companion object {
        private const val MSG_POWER_ON = 0x62
        private const val MSG_XFR_BLOCK = 0x6F

        /** Upper bound on a single CCID response payload; rejects absurd claimed lengths. */
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        /** True if [device] exposes a CCID (smart-card) interface. */
        fun isSmartCard(device: UsbDevice): Boolean = (0 until device.interfaceCount)
            .any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CSCID }

        /**
         * Opens and powers on the CCID interface of [device]. Caller must already
         * hold USB permission. Returns null if the interface can't be claimed.
         */
        suspend fun open(usbManager: UsbManager, device: UsbDevice): UsbCcidConnection? =
            withContext(Dispatchers.IO) {
                val ccid = (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }
                    ?: return@withContext null

                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null
                for (i in 0 until ccid.endpointCount) {
                    val ep = ccid.getEndpoint(i)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
                }
                if (bulkIn == null || bulkOut == null) return@withContext null

                val connection = usbManager.openDevice(device) ?: return@withContext null
                if (!connection.claimInterface(ccid, true)) {
                    connection.close()
                    return@withContext null
                }
                val conn = UsbCcidConnection(connection, ccid, bulkIn, bulkOut)
                try {
                    conn.powerOn()
                } catch (e: Exception) {
                    conn.close()
                    return@withContext null
                }
                conn
            }
    }
}
