package com.nettarion.hyperborea.broadcast.ftms

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ClientInfo
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.ftms.BroadcastProfile
import com.nettarion.hyperborea.core.ftms.FtmsNotificationPump
import com.nettarion.hyperborea.core.ftms.GattService
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Suppress("DEPRECATION")
class FtmsBleServer(
    private val context: Context,
    private val deviceInfo: DeviceInfo,
    private val logger: AppLogger,
    private val onClientChange: (Set<ClientInfo>) -> Unit,
    private val onCommand: (DeviceCommand) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var callback: FtmsGattCallback? = null
    private var scope: CoroutineScope? = null

    // A GATT server is inherently multi-central: track every connected device by address so a
    // second central (or a stale link) can't clobber the live client's state.
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val connectedClients = ConcurrentHashMap<String, ClientInfo>()

    @Volatile private var isStopped = false
    private var advertiseCallback: AdvertiseCallback? = null
    @Volatile private var latestData: ExerciseData = ExerciseData.ZERO
    private var tickerJob: Job? = null
    // The adapter name we replaced and the one we set — so stop() can put the device's global
    // Bluetooth identity back instead of leaving the console permanently renamed.
    private var originalAdapterName: String? = null
    private var advertisedName: String? = null

    /**
     * The shared notification pump handles encode order, Training-Status dedup, and the CPS
     * revolution counters; this sink fans each payload out to every subscribed central.
     */
    private val pump = FtmsNotificationPump(deviceInfo.type, object : FtmsNotificationPump.Sink {
        override fun isSubscribed(characteristic: FtmsNotificationPump.Characteristic): Boolean {
            val cb = callback ?: return false
            val charUuid = uuidFor(characteristic)
            return connectedDevices.keys.any { cb.isSubscribed(it, charUuid) }
        }

        @SuppressLint("MissingPermission") // Permission verified during start()
        override suspend fun send(characteristic: FtmsNotificationPump.Characteristic, payload: ByteArray) {
            if (isStopped) return
            val server = gattServer ?: return
            val cb = callback ?: return
            val charUuid = uuidFor(characteristic)
            val char = server.getService(serviceUuidFor(characteristic))?.getCharacteristic(charUuid) ?: return
            char.value = payload
            for ((address, device) in connectedDevices) {
                if (!cb.isSubscribed(address, charUuid)) continue
                try {
                    if (!server.notifyCharacteristicChanged(device, char, false)) {
                        logger.w(TAG, "Failed to send $characteristic notification to $address")
                    }
                } catch (e: RemoteException) {
                    logger.d(TAG, "GATT server closed during broadcast: ${e.message}")
                    return
                }
            }
        }
    })

    // Service addition synchronization
    private val serviceAddedChannel = Channel<Unit>(Channel.UNLIMITED)

    @SuppressLint("MissingPermission") // Checked by requireBluetoothPermission() below
    suspend fun start(deviceName: String, scope: CoroutineScope) {
        requireBluetoothPermission()
        isStopped = false
        this.scope = scope

        // Drain any stale events from previous start attempts
        while (serviceAddedChannel.tryReceive().isSuccess) {}

        originalAdapterName = bluetoothAdapter.name
        advertisedName = deviceName
        bluetoothAdapter.name = deviceName

        val gattCallback = FtmsGattCallback(
            context = context,
            logger = logger,
            deviceInfo = deviceInfo,
            onClientConnected = { device ->
                connectedDevices[device.address] = device
                connectedClients[device.address] = ClientInfo(
                    id = device.address,
                    protocol = "BLE FTMS",
                    connectedAt = System.currentTimeMillis(),
                )
                onClientChange(connectedClients.values.toSet())
            },
            onClientDisconnected = { device ->
                connectedDevices.remove(device.address)
                connectedClients.remove(device.address)
                if (connectedDevices.isEmpty()) {
                    scope.launch { pump.resetCounters() }
                }
                onClientChange(connectedClients.values.toSet())
            },
            onCommand = onCommand,
            onServiceAdded = { serviceAddedChannel.trySend(Unit) },
            onSubscriptionEnabled = {
                // Runs on a BLE binder thread — hand off to the scope; the pump's mutex
                // serializes it against the ticker.
                scope.launch {
                    pump.onSubscriptionChanged()
                    pump.emit(latestData)
                }
            },
        )
        callback = gattCallback

        val server = bluetoothManager.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("Failed to open GATT server")
        gattCallback.gattServer = server
        gattServer = server

        // Add each service this device type advertises, one at a time — the GATT stack only accepts
        // the next addService() after the previous one has been acknowledged.
        for (service in BroadcastProfile.servicesFor(deviceInfo.type)) {
            val gattService = when (service) {
                GattService.FTMS -> FtmsServiceBuilder.buildFtmsService(deviceInfo.type)
                GattService.CYCLING_POWER -> FtmsServiceBuilder.buildCpsService()
                GattService.RUNNING_SPEED_CADENCE -> FtmsServiceBuilder.buildRscService()
            }
            server.addService(gattService)
            withTimeout(SERVICE_ADD_TIMEOUT_MS) { serviceAddedChannel.receive() }
            logger.d(TAG, "$service service added")
        }

        // Start advertising
        startAdvertising()

        // Re-send the latest sample at a fixed cadence while a client is connected — real FTMS
        // hardware notifies continuously, and clients (e.g. Zwift) wedge if the stream goes silent
        // while they are subscribed. Seeded with ExerciseData.ZERO so the stream is alive even before
        // a workout starts; a real data change still pushes instantly via broadcastData().
        tickerJob = scope.launch {
            while (isActive && !isStopped) {
                if (connectedDevices.isNotEmpty()) pump.emit(latestData)
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }

        logger.i(TAG, "BLE FTMS server started, advertising as '$deviceName'")
    }

    @SuppressLint("MissingPermission") // Permission verified during start()
    fun stop() {
        logger.i(TAG, "BLE FTMS server stopping")
        isStopped = true
        tickerJob?.cancel()
        tickerJob = null
        stopAdvertising()

        gattServer?.close()
        gattServer = null
        callback = null
        scope = null
        connectedDevices.clear()
        connectedClients.clear()
        latestData = ExerciseData.ZERO
        restoreAdapterName()
        // No pump reset needed: FtmsAdapter builds a fresh server (and pump) per start.
    }

    suspend fun broadcastData(data: ExerciseData) {
        latestData = data
        if (!isStopped && connectedDevices.isNotEmpty()) pump.emit(data)
    }

    /**
     * Puts the device-global Bluetooth name back the way we found it — but only if it still holds
     * the name we set (if something else renamed the adapter since, leave it alone).
     */
    @SuppressLint("MissingPermission") // Permission verified during start()
    private fun restoreAdapterName() {
        val original = originalAdapterName ?: return
        originalAdapterName = null
        val advertised = advertisedName
        advertisedName = null
        try {
            if (original != advertised && bluetoothAdapter.name == advertised) {
                bluetoothAdapter.name = original
            }
        } catch (e: Exception) {
            logger.w(TAG, "Couldn't restore Bluetooth adapter name: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission") // Permission verified during start()
    fun broadcastStatus(opCode: Byte, parameter: ByteArray? = null) {
        val server = gattServer ?: return
        val cb = callback ?: return

        val value = if (parameter != null) {
            byteArrayOf(opCode) + parameter
        } else {
            byteArrayOf(opCode)
        }

        val char = server.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
            ?.getCharacteristic(FtmsServiceBuilder.FITNESS_MACHINE_STATUS_UUID) ?: return
        char.value = value
        for ((address, device) in connectedDevices) {
            if (!cb.isSubscribed(address, FtmsServiceBuilder.FITNESS_MACHINE_STATUS_UUID)) continue
            if (!server.notifyCharacteristicChanged(device, char, false)) {
                logger.w(TAG, "Failed to send Fitness Machine Status notification to $address")
            }
        }
    }

    private fun uuidFor(characteristic: FtmsNotificationPump.Characteristic): UUID = when (characteristic) {
        FtmsNotificationPump.Characteristic.DATA -> FtmsServiceBuilder.dataCharacteristicUuid(deviceInfo.type)
        FtmsNotificationPump.Characteristic.TRAINING_STATUS -> FtmsServiceBuilder.TRAINING_STATUS_UUID
        FtmsNotificationPump.Characteristic.CPS_MEASUREMENT -> FtmsServiceBuilder.CPS_MEASUREMENT_UUID
        FtmsNotificationPump.Characteristic.RSC_MEASUREMENT -> FtmsServiceBuilder.RSC_MEASUREMENT_UUID
    }

    private fun serviceUuidFor(characteristic: FtmsNotificationPump.Characteristic): UUID = when (characteristic) {
        FtmsNotificationPump.Characteristic.DATA,
        FtmsNotificationPump.Characteristic.TRAINING_STATUS,
        -> FtmsServiceBuilder.FTMS_SERVICE_UUID
        FtmsNotificationPump.Characteristic.CPS_MEASUREMENT -> FtmsServiceBuilder.CPS_SERVICE_UUID
        FtmsNotificationPump.Characteristic.RSC_MEASUREMENT -> FtmsServiceBuilder.RSC_SERVICE_UUID
    }

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            logger.e(TAG, "BLE advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(FtmsServiceBuilder.FTMS_SERVICE_UUID))
            .build()

        // Scan response: FTMS Service Data AD Type (Section 3.1)
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(
                ParcelUuid(FtmsServiceBuilder.FTMS_SERVICE_UUID),
                FtmsServiceBuilder.serviceDataAdValue(deviceInfo.type),
            )
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logger.i(TAG, "BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                logger.e(TAG, "BLE advertising failed, error=$errorCode")
                onError?.invoke("BLE advertising failed (error $errorCode)")
            }
        }
        advertiseCallback = cb
        advertiser.startAdvertising(settings, data, scanResponse, cb)
    }

    @SuppressLint("MissingPermission") // Permission verified during start()
    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
    }

    private fun requireBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // start() opens a GATT server (BLUETOOTH_CONNECT) and advertises (BLUETOOTH_ADVERTISE).
        val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            throw SecurityException("Bluetooth permissions not granted: $missing")
        }
    }

    private companion object {
        const val TAG = "FtmsBle"
        const val SERVICE_ADD_TIMEOUT_MS = 5000L
        const val NOTIFICATION_INTERVAL_MS = 250L
    }
}
