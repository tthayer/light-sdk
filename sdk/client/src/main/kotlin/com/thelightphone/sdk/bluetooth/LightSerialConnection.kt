package com.thelightphone.sdk.bluetooth

import kotlinx.coroutines.flow.Flow

/**
 * A paired Bluetooth device visible to the tool, as brokered by
 * [LightBluetoothSerial]. [address] is an opaque handle understood by the
 * broker — app code passes it back to [LightBluetoothSerial.connect] and never
 * needs to interpret it.
 */
data class LightBluetoothDevice(
    val name: String?,
    val address: String,
)

/**
 * A live RFCOMM (SPP-style) byte stream to a paired Bluetooth device. This is
 * the ONLY surface the tool sees — the underlying [android.bluetooth] socket,
 * streams and reader thread are owned by the SDK broker so tool code stays
 * inside the Light sandbox.
 *
 * The transport is protocol-agnostic: it moves opaque bytes. Framing, the Sony
 * ACK/sequence handshake and payload parsing all live in tool code on top of
 * this interface.
 */
interface LightSerialConnection {
    /**
     * Write raw bytes to the device. Suspends until the bytes are handed to the
     * socket's output stream. Throws [LightBluetoothException] if the link is
     * down.
     */
    suspend fun write(bytes: ByteArray)

    /**
     * Hot stream of inbound byte chunks in arrival order. Chunk boundaries are
     * NOT message boundaries — consumers must reassemble frames themselves.
     * Completes when the connection closes; emits nothing in demo mode unless
     * the broker injects canned data.
     */
    val incoming: Flow<ByteArray>

    /** Whether the socket is currently connected. */
    val isConnected: Boolean

    /** Close the socket and stop the reader. Idempotent. */
    fun close()
}

/** Raised for any Bluetooth transport failure surfaced to tool code. */
class LightBluetoothException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
