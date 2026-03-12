package com.nettarion.hyperborea.sensor.hrm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.RetryPolicy
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.DiscoveredSensor
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.core.adapter.SensorId
import com.nettarion.hyperborea.core.adapter.SensorReading
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.system.SystemSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HrmAdapter @Inject constructor(
    private val context: Context,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : SensorAdapter {

    override val id: SensorId = SensorId.HEART_RATE
    override val prerequisites: List<Prerequisite> = emptyList()

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private val _reading = MutableStateFlow<SensorReading?>(null)
    override val reading: StateFlow<SensorReading?> = _reading.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var connectedAddress: String? = null

    private val reconnectPolicy = RetryPolicy(maxAttempts = 5, initialDelayMs = 2000, maxDelayMs = 30000)

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isBluetoothLeEnabled

    override suspend fun startScan(): Flow<DiscoveredSensor> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner

        if (scanner == null) {
            close(IllegalStateException("BLE scanner not available"))
            return@callbackFlow
        }

        val scanCallback = HrmScanCallback(channel)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HrmGattCallback.HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        logger.i(TAG, "Starting HR sensor scan")
        scanner.startScan(listOf(filter), settings, scanCallback)

        awaitClose {
            logger.i(TAG, "Stopping HR sensor scan")
            scanner.stopScan(scanCallback)
        }
    }

    override suspend fun connect(address: String) {
        if (_state.value is AdapterState.Active && connectedAddress == address) return

        disconnectInternal()
        connectedAddress = address
        _state.value = AdapterState.Activating

        try {
            connectGatt(address)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.e(TAG, "Failed to connect to $address", e)
            _state.value = AdapterState.Error("Connection failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal()
        connectedAddress = null
        _state.value = AdapterState.Inactive
        logger.i(TAG, "Disconnected")
    }

    private fun connectGatt(address: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
            ?: throw IllegalStateException("BluetoothAdapter not available")

        val device = adapter.getRemoteDevice(address)
        val callback = HrmGattCallback(
            logger = logger,
            onConnectionStateChange = { connected ->
                if (connected) {
                    _state.value = AdapterState.Active
                    reconnectJob?.cancel()
                    reconnectJob = null
                    logger.i(TAG, "Connected to ${device.name ?: address}")
                } else {
                    _reading.value = null
                    if (connectedAddress != null && _state.value is AdapterState.Active) {
                        _state.value = AdapterState.Error("Disconnected")
                        startReconnect(address)
                    }
                }
            },
            onHeartRate = { hr ->
                _reading.value = hr
            },
        )

        gatt = device.connectGatt(context, false, callback)
    }

    private fun startReconnect(address: String) {
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            for (attempt in 1..reconnectPolicy.maxAttempts) {
                delay(reconnectPolicy.delayForAttempt(attempt))
                if (!isActive || connectedAddress != address) return@launch

                logger.i(TAG, "Reconnect attempt $attempt/${ reconnectPolicy.maxAttempts}")
                try {
                    disconnectGatt()
                    connectGatt(address)
                    // If connectGatt doesn't throw, we'll get the onConnectionStateChange callback
                    // Wait a bit for connection to establish
                    delay(3000)
                    if (_state.value is AdapterState.Active) {
                        logger.i(TAG, "Reconnected on attempt $attempt")
                        return@launch
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }
            }
            logger.w(TAG, "Reconnect failed after ${reconnectPolicy.maxAttempts} attempts")
            _state.value = AdapterState.Error("Reconnect failed")
        }
    }

    private fun disconnectInternal() {
        _reading.value = null
        disconnectGatt()
    }

    private fun disconnectGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private companion object {
        const val TAG = "HrmAdapter"
    }
}
