package com.thelightphone.sdk.security

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import com.thelightphone.sdk.shared.LightResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "LightSecurityKey"

/**
 * Sandbox-safe access to a hardware security key's OATH (authenticator) applet
 * over USB or NFC. Tools never touch [Context], [UsbManager], or [NfcAdapter]
 * directly — those are walled off by the Light SDK — so this class brokers the
 * hardware and hands back only decoded [OathCode]s.
 *
 * Obtain an instance from a screen's `lightContext.securityKey`.
 *
 * The emulator has no USB host or NFC reader, so set [demoMode] = true to read
 * from an in-memory software card (real TOTP math) for UI development.
 */
class LightSecurityKey internal constructor(private val context: Context) {

    /** When true, [readCodes] returns codes from an in-memory card ([demoCredentials]). */
    var demoMode: Boolean = false

    /** Credentials the in-memory demo card exposes. Ignored unless [demoMode]. */
    var demoCredentials: List<InMemoryOathCard.Entry> = defaultDemoCredentials()

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** True if a compatible USB security key is currently plugged in. */
    fun hasUsbKey(): Boolean = findUsbKey() != null

    /** True if the device has NFC hardware available for tap-to-read. */
    fun hasNfc(): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /**
     * Reads codes from a plugged-in USB key (or the demo card). NFC is handled
     * separately by [startNfcReaderMode], which must own the RF field for the
     * whole time the screen is shown — so this does NOT touch NFC. Returns an
     * informative error when no USB/demo source is available.
     */
    suspend fun readCodes(
        epochSeconds: Long = System.currentTimeMillis() / 1000,
    ): LightResult<List<OathCode>> {
        if (demoMode) {
            return runSession(InMemoryOathCard(demoCredentials), epochSeconds)
        }

        val device = findUsbKey()
            ?: return LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "No key plugged in — hold your YubiKey to the back of the phone for NFC.",
            )
        val manager = usbManager ?: return LightResult.Error(LightResult.ErrorCode.Unknown, "USB unavailable")
        if (!manager.hasPermission(device) && !requestUsbPermission(manager, device)) {
            return LightResult.Error(LightResult.ErrorCode.NoPermission, "USB permission denied")
        }
        val connection = UsbCcidConnection.open(manager, device)
            ?: return LightResult.Error(LightResult.ErrorCode.Unknown, "could not open USB key")
        return runSession(connection, epochSeconds)
    }

    private suspend fun runSession(
        connection: SmartCardConnection,
        epochSeconds: Long,
    ): LightResult<List<OathCode>> = try {
        LightResult.Success(OathSession(connection).calculateAll(epochSeconds))
    } catch (e: OathException) {
        Log.w(TAG, "OATH read failed", e)
        LightResult.Error(LightResult.ErrorCode.Unknown, e.message)
    } catch (e: Exception) {
        Log.w(TAG, "security key read failed", e)
        LightResult.Error(LightResult.ErrorCode.Unknown, e.message ?: "read failed")
    } finally {
        connection.close()
    }

    private fun findUsbKey(): UsbDevice? =
        usbManager?.deviceList?.values?.firstOrNull { UsbCcidConnection.isSmartCard(it) }

    private suspend fun requestUsbPermission(manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
            }
            val pending = PendingIntent.getBroadcast(
                context, 0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            manager.requestPermission(device, pending)
        }

    private var nfcReaderActive = false

    /**
     * Enables NFC reader mode for as long as the screen is shown, and reads
     * OATH codes on every tap — delivering each result to [onResult] (called
     * off the main thread). Call [stopNfcReaderMode] from the screen's
     * hide/pause lifecycle.
     *
     * Reader mode must be enabled *before* the user taps, so the platform's
     * NDEF dispatcher never fires (which would launch the key's `my.yubico.com`
     * web link and background the tool). The whole APDU exchange runs inside
     * the callback while reader mode still owns the RF field.
     */
    fun startNfcReaderMode(onResult: (LightResult<List<OathCode>>) -> Unit) {
        val activity = context as? Activity ?: return
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return
        val callback = NfcAdapter.ReaderCallback { tag: Tag ->
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.w(TAG, "tapped tag is not ISO-DEP; hold a security key flat to the back")
                return@ReaderCallback
            }
            val read = runBlocking {
                try {
                    isoDep.connect()
                    isoDep.timeout = 7000
                    Log.i(TAG, "NFC ISO-DEP connected; running OATH session")
                    runSession(NfcSmartCardConnection(isoDep), System.currentTimeMillis() / 1000)
                } catch (e: Exception) {
                    Log.w(TAG, "NFC connect/read failed", e)
                    LightResult.Error(LightResult.ErrorCode.Unknown, e.message ?: "read failed")
                }
            }
            onResult(read)
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000) }
        Log.i(TAG, "starting persistent NFC reader mode")
        adapter.enableReaderMode(activity, callback, flags, extras)
        nfcReaderActive = true
    }

    /** Disables NFC reader mode. Safe to call when it was never started. */
    fun stopNfcReaderMode() {
        if (!nfcReaderActive) return
        val activity = context as? Activity ?: return
        NfcAdapter.getDefaultAdapter(context)?.disableReaderMode(activity)
        nfcReaderActive = false
        Log.i(TAG, "stopped NFC reader mode")
    }

    private companion object {
        // Distinct per-account secrets so demo codes differ (any bytes are a
        // valid HMAC key). These never touch real hardware.
        fun defaultDemoCredentials(): List<InMemoryOathCard.Entry> = listOf(
            InMemoryOathCard.Entry(id = "GitHub:octocat", secret = "light-demo-github-seed".toByteArray()),
            InMemoryOathCard.Entry(id = "Google:you@gmail.com", secret = "light-demo-google-seed".toByteArray()),
            InMemoryOathCard.Entry(id = "AWS:root", secret = "light-demo-aws-seed".toByteArray()),
        )
    }
}
