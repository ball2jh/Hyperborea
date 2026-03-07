package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.FtmsDataEncoder
import com.nettarion.hyperborea.core.RevolutionCounter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

@Suppress("DEPRECATION")
class FtmsBleServer(
    private val context: Context,
    private val logger: AppLogger,
    private val onClientChange: (Set<ClientInfo>) -> Unit,
    private val onCommand: (DeviceCommand) -> Unit,
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var callback: FtmsGattCallback? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val revCounter = RevolutionCounter()

    // Service addition synchronization
    private val serviceAddedChannel = Channel<Unit>(Channel.UNLIMITED)

    suspend fun start(deviceName: String) {
        bluetoothAdapter.name = deviceName

        val gattCallback = FtmsGattCallback(
            logger = logger,
            onClientConnected = { device ->
                connectedDevice = device
                onClientChange(setOf(ClientInfo(
                    id = device.address,
                    protocol = "BLE FTMS",
                    connectedAt = System.currentTimeMillis(),
                )))
            },
            onClientDisconnected = {
                connectedDevice = null
                revCounter.reset()
                onClientChange(emptySet())
            },
            onCommand = onCommand,
            onServiceAdded = { serviceAddedChannel.trySend(Unit) },
        )
        callback = gattCallback

        val server = bluetoothManager.openGattServer(context, gattCallback)
            ?: throw IllegalStateException("Failed to open GATT server")
        gattCallback.gattServer = server
        gattServer = server

        // Add FTMS service → wait → add CPS service → wait
        server.addService(FtmsServiceBuilder.buildFtmsService())
        withTimeout(SERVICE_ADD_TIMEOUT_MS) { serviceAddedChannel.receive() }

        server.addService(FtmsServiceBuilder.buildCpsService())
        withTimeout(SERVICE_ADD_TIMEOUT_MS) { serviceAddedChannel.receive() }

        // Start advertising
        startAdvertising()
        logger.i(TAG, "BLE FTMS server started, advertising as '$deviceName'")
    }

    fun stop() {
        stopAdvertising()

        gattServer?.close()
        gattServer = null
        callback = null
        connectedDevice = null
        revCounter.reset()
    }

    fun broadcastData(data: ExerciseData) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val cb = callback ?: return

        // Indoor Bike Data notification
        if (cb.isSubscribed(FtmsServiceBuilder.INDOOR_BIKE_DATA_UUID)) {
            val bikeData = FtmsDataEncoder.encodeIndoorBikeData(data)
            val char = server.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
                ?.getCharacteristic(FtmsServiceBuilder.INDOOR_BIKE_DATA_UUID)
            if (char != null) {
                char.value = bikeData
                if (!server.notifyCharacteristicChanged(device, char, false)) {
                    logger.w(TAG, "Failed to send Indoor Bike Data notification")
                }
            }
        }

        // CPS Measurement notification
        if (cb.isSubscribed(FtmsServiceBuilder.CPS_MEASUREMENT_UUID)) {
            val now = System.currentTimeMillis()
            revCounter.update(data, now)
            val cpsMeasurement = FtmsDataEncoder.encodeCpsMeasurement(
                data,
                revCounter.cumulativeWheelRevs,
                revCounter.lastWheelEventTime,
                revCounter.cumulativeCrankRevs,
                revCounter.lastCrankEventTime,
            )
            val char = server.getService(FtmsServiceBuilder.CPS_SERVICE_UUID)
                ?.getCharacteristic(FtmsServiceBuilder.CPS_MEASUREMENT_UUID)
            if (char != null) {
                char.value = cpsMeasurement
                if (!server.notifyCharacteristicChanged(device, char, false)) {
                    logger.w(TAG, "Failed to send CPS Measurement notification")
                }
            }
        }
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

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logger.i(TAG, "BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                logger.e(TAG, "BLE advertising failed, error=$errorCode")
            }
        }
        advertiseCallback = cb
        advertiser.startAdvertising(settings, data, cb)
    }

    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
    }

    private companion object {
        const val TAG = "FtmsBle"
        const val SERVICE_ADD_TIMEOUT_MS = 5000L
    }
}
