package com.thelightphone.sdk.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private const val TAG = "LightBluetoothSerial"

/**
 * Sandbox-safe access to a paired Bluetooth device over an RFCOMM (SPP-style)
 * serial link. Tools never touch [Context], [BluetoothManager], or
 * [BluetoothAdapter] directly — those are walled off by the Light SDK — so this
 * class brokers the radio and hands back only a protocol-agnostic
 * [LightSerialConnection] of opaque bytes.
 *
 * Obtain an instance from a screen's `lightContext.bluetoothSerial`.
 *
 * We do NOT scan or discover: the earbuds are paired once through the system
 * Bluetooth settings, then reached here via [pairedDevices] / [connect]. On
 * API 31+ (all Light devices, minSdk 33) reading bonded devices and opening a
 * socket require the dangerous runtime permission `BLUETOOTH_CONNECT`, which
 * this broker requests through the SDK's server-brokered grant flow (the same
 * path CAMERA uses).
 *
 * The emulator has no Bluetooth radio, so set [demoMode] = true to talk to an
 * in-memory fake device for UI development.
 */
class LightBluetoothSerial internal constructor(private val context: Context) {

    /**
     * When true, [pairedDevices] returns a single canned device and [connect]
     * returns a fake [LightSerialConnection] whose writes are no-ops and whose
     * [LightSerialConnection.incoming] replays [demoIncomingFrames] (if any).
     */
    var demoMode: Boolean = false

    /**
     * Frames the demo connection replays on [LightSerialConnection.incoming],
     * one emission each, ~200ms apart. Ignored unless [demoMode]. Empty by
     * default so the demo stream stays silent.
     */
    var demoIncomingFrames: List<ByteArray> = emptyList()

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    /** True when the device has a usable Bluetooth adapter. */
    val isSupported: Boolean
        get() = bluetoothAdapter != null

    /**
     * Lists devices already bonded (paired) through system settings. Requests
     * `BLUETOOTH_CONNECT` first if it is not yet granted. Throws
     * [LightBluetoothException] if Bluetooth is unavailable or the permission is
     * denied.
     */
    @SuppressLint("MissingPermission")
    suspend fun pairedDevices(): List<LightBluetoothDevice> {
        if (demoMode) {
            return listOf(LightBluetoothDevice(name = DEMO_DEVICE_NAME, address = DEMO_DEVICE_ADDRESS))
        }
        val adapter = bluetoothAdapter
            ?: throw LightBluetoothException("Bluetooth is not available on this device")
        ensureConnectPermission()
        return try {
            adapter.bondedDevices.orEmpty().map {
                LightBluetoothDevice(name = it.name, address = it.address)
            }
        } catch (e: SecurityException) {
            throw LightBluetoothException("Missing BLUETOOTH_CONNECT permission", e)
        }
    }

    /**
     * Opens an RFCOMM socket to the paired device at [address] on the given
     * [serviceUuid] (SPP is usually `00001101-0000-1000-8000-00805F9B34FB`) and
     * returns a live [LightSerialConnection]. Requests `BLUETOOTH_CONNECT` first
     * if needed. The blocking connect runs on [Dispatchers.IO]. Throws
     * [LightBluetoothException] on any failure.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, serviceUuid: UUID): LightSerialConnection {
        if (demoMode) {
            return DemoSerialConnection(demoIncomingFrames).also { it.start() }
        }
        val adapter = bluetoothAdapter
            ?: throw LightBluetoothException("Bluetooth is not available on this device")
        ensureConnectPermission()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw LightBluetoothException("Invalid Bluetooth address: $address", e)
        }
        return withContext(Dispatchers.IO) {
            val socket: BluetoothSocket = try {
                device.createRfcommSocketToServiceRecord(serviceUuid)
            } catch (e: Exception) {
                throw LightBluetoothException("Could not create RFCOMM socket for $address", e)
            }
            try {
                // No cancelDiscovery(): we never start discovery (that would need
                // BLUETOOTH_SCAN); connecting straight to a bonded device is enough.
                socket.connect()
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw LightBluetoothException("Could not connect to $address", e)
            }
            Log.i(TAG, "RFCOMM connected to $address")
            RfcommConnection(socket).also { it.startReader() }
        }
    }

    private fun hasConnectPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Ensures `BLUETOOTH_CONNECT` is granted, requesting it via the SDK server's
     * permission-request activity (the same plumbing [com.thelightphone.sdk]'s
     * CAMERA flow uses). That activity resolves asynchronously, so we launch it
     * and then poll the OS grant state until the user responds or we time out.
     */
    private suspend fun ensureConnectPermission() {
        if (hasConnectPermission()) return
        val activity = context as? Activity
            ?: throw LightBluetoothException(
                "BLUETOOTH_CONNECT must be requested from a screen (Activity) context",
            )
        val component = callRemoteServiceMethod(
            LightServiceMethod.RequestPermissionComponent,
            Unit,
        ).getOrElse {
            throw LightBluetoothException(
                "Could not launch Bluetooth permission request: ${it.code} ${it.extra}",
            )
        }
        val componentName = ComponentName.unflattenFromString(component.componentName)
            ?: throw LightBluetoothException("Server returned an invalid permission component")
        // Must be startActivityForResult, NOT startActivity: LightSdkPermissionActivity
        // reads getCallingPackage() to identify the requesting tool and exits with
        // "Calling package was null" if it's absent. Plain startActivity leaves the
        // caller null. This mirrors PermissionRequestLauncher.launch() (the CAMERA path).
        activity.startActivityForResult(
            Intent()
                .setComponent(componentName)
                .putExtra(
                    LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            PERMISSION_REQUEST_CODE,
        )
        val deadline = System.currentTimeMillis() + PERMISSION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(PERMISSION_POLL_INTERVAL_MS)
            if (hasConnectPermission()) return
        }
        throw LightBluetoothException("BLUETOOTH_CONNECT permission was not granted")
    }

    /**
     * Real RFCOMM socket wrapper. Owns a reader coroutine that pumps inbound
     * bytes into [incoming]; the socket, streams and reader all live here so
     * tool code never sees them.
     */
    private class RfcommConnection(
        private val socket: BluetoothSocket,
    ) : LightSerialConnection {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

        @Volatile
        private var closed = false

        override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

        override val isConnected: Boolean
            get() = !closed && socket.isConnected

        fun startReader() {
            scope.launch {
                val input = try {
                    socket.inputStream
                } catch (e: IOException) {
                    Log.w(TAG, "Could not open input stream", e)
                    close()
                    return@launch
                }
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (isActive && !closed) {
                    val read = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        break
                    }
                    if (read < 0) break
                    if (read > 0) _incoming.emit(buffer.copyOf(read))
                }
                close()
            }
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw LightBluetoothException("Connection is closed")
            try {
                withContext(Dispatchers.IO) {
                    val out = socket.outputStream
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: IOException) {
                throw LightBluetoothException("Failed to write to Bluetooth socket", e)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            // Closing the socket unblocks the reader's blocking read().
            runCatching { socket.close() }
            scope.cancel()
        }
    }

    /** In-memory fake connection used when [demoMode] is set. */
    private class DemoSerialConnection(
        private val frames: List<ByteArray>,
    ) : LightSerialConnection {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

        @Volatile
        private var closed = false

        override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

        override val isConnected: Boolean
            get() = !closed

        fun start() {
            if (frames.isEmpty()) return
            scope.launch {
                for (frame in frames) {
                    if (closed) break
                    delay(DEMO_FRAME_INTERVAL_MS)
                    _incoming.emit(frame.copyOf())
                }
            }
        }

        override suspend fun write(bytes: ByteArray) {
            // No-op: nothing is on the other end in demo mode.
        }

        override fun close() {
            if (closed) return
            closed = true
            scope.cancel()
        }
    }

    private companion object {
        const val DEMO_DEVICE_NAME = "WF-1000XM5 (demo)"
        const val DEMO_DEVICE_ADDRESS = "00:11:22:33:44:55"
        const val PERMISSION_TIMEOUT_MS = 60_000L
        const val PERMISSION_POLL_INTERVAL_MS = 250L
        const val PERMISSION_REQUEST_CODE = 10101
        const val DEMO_FRAME_INTERVAL_MS = 200L
        const val READ_BUFFER_SIZE = 1024
    }
}
