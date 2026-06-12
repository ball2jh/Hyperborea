package com.nettarion.hyperborea.hardware.fitpro.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UsbHidTransport(
    private val context: Context,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val deviceName: String,
    private val logger: AppLogger,
) : HidTransport {

    /** Link usable — cleared by [close] and by a USB detach of our device. */
    @Volatile
    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    /** Resources released — distinct from [_isOpen] so a detach can't turn [close] into a leak. */
    @Volatile
    private var closed = false

    /**
     * Serializes all transfers: the FitPro MCU is a strict request/response peer, and a blocking
     * IN transfer left in flight while an OUT transfer runs is exactly the overlap it never sees
     * from its own console software. Poll reads hold this only [POLL_TIMEOUT_MS] at a time, so a
     * write waits at most one poll window.
     */
    private val transferMutex = Mutex()

    private var detachReceiver: BroadcastReceiver? = null

    override suspend fun open() {
        if (_isOpen) return
        check(!closed) { "Transport already closed — create a new one" }
        claimInterfaceWithRetry()
        registerDetachReceiver()
        _isOpen = true
        logger.d(TAG, "Transport opened")
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        _isOpen = false
        unregisterDetachReceiver()
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
            logger.d(TAG, "Transport closed")
        } catch (e: Exception) {
            logger.w(TAG, "USB close error: ${e.message}")
        }
    }

    /** The MCU intermittently refuses interface claims; keep asking like its own software does. */
    private suspend fun claimInterfaceWithRetry() {
        repeat(CLAIM_ATTEMPTS) { attempt ->
            if (connection.claimInterface(usbInterface, true)) {
                if (attempt > 0) logger.i(TAG, "Claimed USB interface after ${attempt + 1} attempts")
                return
            }
            logger.w(TAG, "claimInterface failed (attempt ${attempt + 1}/$CLAIM_ATTEMPTS)")
            delay(CLAIM_RETRY_DELAY_MS)
        }
        throw IllegalStateException("Failed to claim USB interface after $CLAIM_ATTEMPTS attempts")
    }

    override suspend fun write(data: ByteArray) {
        if (!_isOpen) throw IllegalStateException("Transport not open")
        require(data.size <= MAX_PACKET_SIZE) { "Packet too large: ${data.size} > $MAX_PACKET_SIZE" }
        // Pad to 64 bytes — the MCU expects full-size USB packets
        val padded = if (data.size < MAX_PACKET_SIZE) data.copyOf(MAX_PACKET_SIZE) else data

        // The MCU routinely refuses OUT transfers for short stretches (it services the endpoint
        // on its own schedule); a refused write is retried, not fatal. Only a sustained refusal
        // means the link is actually dead.
        withContext(Dispatchers.IO) {
            var attempts = 0
            while (true) {
                if (!_isOpen) throw IllegalStateException("Transport closed during write")
                val transferred = transferMutex.withLock {
                    connection.bulkTransfer(outEndpoint, padded, padded.size, WRITE_TIMEOUT_MS)
                }
                if (transferred >= 0) {
                    if (attempts > 0) logger.i(TAG, "USB write succeeded after ${attempts + 1} attempts")
                    return@withContext
                }
                attempts++
                if (attempts >= WRITE_MAX_ATTEMPTS) {
                    throw IllegalStateException("USB write failed after $attempts attempts")
                }
                if (attempts == 1 || attempts % 10 == 0) {
                    logger.w(TAG, "USB write refused, retrying (attempt $attempts/$WRITE_MAX_ATTEMPTS)")
                }
                if (attempts == 1) {
                    // An instantly-refused write usually means the OUT endpoint is halted. Try the
                    // standard clear-halt request once per write call: if it succeeds and the retry
                    // works, the halt was stale; if it succeeds but writes keep stalling, the MCU is
                    // actively rejecting; if it fails too, the device is mute even at control level.
                    val cleared = transferMutex.withLock { clearHalt(outEndpoint) }
                    logger.w(TAG, if (cleared) "OUT endpoint halt cleared" else "OUT endpoint clear-halt refused — device mute at control level")
                }
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
    }

    /** Standard CLEAR_FEATURE(ENDPOINT_HALT) control request to [endpoint]. True on success. */
    private fun clearHalt(endpoint: UsbEndpoint): Boolean =
        connection.controlTransfer(
            0x02, // host-to-device | standard | endpoint recipient
            0x01, // CLEAR_FEATURE
            0x00, // ENDPOINT_HALT
            endpoint.address,
            null, 0, CONTROL_TIMEOUT_MS,
        ) >= 0

    override suspend fun readPacket(): ByteArray? {
        if (!_isOpen) return null
        return withContext(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val transferred = transferMutex.withLock {
                connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            }
            if (transferred > 0) buffer.copyOf(transferred) else null
        }
    }

    override suspend fun clearBuffer() {
        if (!_isOpen) return
        val clearCmd = ByteArray(MAX_PACKET_SIZE).also { it[0] = 0xFF.toByte() }
        val readBuf = ByteArray(MAX_PACKET_SIZE)
        var consecutiveFf = 0
        var attempts = 0

        withContext(Dispatchers.IO) {
            while (consecutiveFf < 2 && attempts < MAX_CLEAR_ATTEMPTS) {
                transferMutex.withLock {
                    connection.bulkTransfer(outEndpoint, clearCmd, clearCmd.size, 500)
                }
                delay(50)
                val n = transferMutex.withLock {
                    connection.bulkTransfer(inEndpoint, readBuf, readBuf.size, 500)
                }
                if (n > 0 && readBuf[0] == 0xFF.toByte()) {
                    consecutiveFf++
                } else {
                    consecutiveFf = 0
                }
                attempts++
            }
        }

        logger.i(TAG, "Buffer cleared after $attempts attempts (ff=$consecutiveFf)")
    }

    override fun incoming(): Flow<ByteArray> = flow {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        var failedPolls = 0
        while (currentCoroutineContext().isActive && _isOpen) {
            val pollStart = SystemClock.elapsedRealtime()
            val transferred = transferMutex.withLock {
                connection.bulkTransfer(inEndpoint, buffer, buffer.size, POLL_TIMEOUT_MS)
            }
            if (transferred > 0) {
                failedPolls = 0
                emit(buffer.copyOf(transferred))
            } else {
                // -1 is both "no data within the poll window" (normal between events) and
                // "endpoint/device error" — the API doesn't distinguish. Surface long silences,
                // and pace the loop when the failure came back instantly (a dead device returns
                // immediately; a quiet one blocks for the full poll window).
                failedPolls++
                if (failedPolls % SILENT_POLL_LOG_EVERY == 0) {
                    logger.w(TAG, "No USB data for $failedPolls consecutive polls")
                }
                if (SystemClock.elapsedRealtime() - pollStart < POLL_TIMEOUT_MS / 2) {
                    delay(POLL_TIMEOUT_MS.toLong())
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The MCU drops off the bus and re-enumerates in normal operation (its watchdog cycles USB
     * when unhappy). Seeing those events in the log is essential for field diagnosis, and a
     * detach of our device must end the session instead of leaving loops spinning against a
     * dead connection.
     */
    // InlinedApi: RECEIVER_NOT_EXPORTED (API 33) is only referenced inside the SDK_INT >= 33
    //   branch; inlining the int constant on older builds is harmless and never reached there.
    // UnspecifiedRegisterReceiverFlag: the flag is only required on API 33+, which the guard
    //   already routes to the 3-arg overload; lint can't see that the 2-arg call is API <33 only.
    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    private fun registerDetachReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        logger.w(TAG, "USB device detached: ${device.deviceName} (vid=0x${device.vendorId.toString(16)})")
                        if (device.deviceName == deviceName) {
                            // Flag only — actual cleanup happens via the session's close() path.
                            _isOpen = false
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        logger.i(TAG, "USB device attached: ${device.deviceName} (vid=0x${device.vendorId.toString(16)})")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts still reach NOT_EXPORTED receivers; the flag is mandatory on 34+.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        detachReceiver = receiver
    }

    private fun unregisterDetachReceiver() {
        detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        detachReceiver = null
    }

    private companion object {
        const val TAG = "UsbHidTransport"
        const val MAX_PACKET_SIZE = 64

        // V1 request/response reads: generous, the MCU answers immediately when alive.
        const val READ_TIMEOUT_MS = 1000

        // Event polling (V2): short windows so queued writes interleave promptly.
        const val POLL_TIMEOUT_MS = 50
        const val SILENT_POLL_LOG_EVERY = 200 // ≈10 s of silence per warning

        // Writes: short per-attempt timeout, generous retry budget (refusals are normal).
        const val WRITE_TIMEOUT_MS = 50
        const val WRITE_MAX_ATTEMPTS = 50
        const val WRITE_RETRY_DELAY_MS = 20L

        const val CLAIM_ATTEMPTS = 20
        const val CLAIM_RETRY_DELAY_MS = 500L

        const val CONTROL_TIMEOUT_MS = 200

        const val MAX_CLEAR_ATTEMPTS = 10
    }
}
