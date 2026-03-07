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
import com.nettarion.hyperborea.core.ClientInfo
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.FtmsDataEncoder
import com.nettarion.hyperborea.core.RevolutionCounter
import kotlinx.coroutines.channels.Channel
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
    @Volatile private var connectedDevice: BluetoothDevice? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val revCounter = RevolutionCounter()
    private var lastTrainingStatus: Byte = 0x01 // Idle

    // Service addition synchronization
    private val serviceAddedChannel = Channel<Unit>(Channel.UNLIMITED)

    @SuppressLint("MissingPermission") // Checked by requireBluetoothPermission() below
    suspend fun start(deviceName: String) {
        requireBluetoothPermission()

        bluetoothAdapter.name = deviceName

        val gattCallback = FtmsGattCallback(
            context = context,
            logger = logger,
            deviceInfo = deviceInfo,
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
        server.addService(FtmsServiceBuilder.buildFtmsService(deviceInfo.type))
        withTimeout(SERVICE_ADD_TIMEOUT_MS) { serviceAddedChannel.receive() }

        server.addService(FtmsServiceBuilder.buildCpsService())
        withTimeout(SERVICE_ADD_TIMEOUT_MS) { serviceAddedChannel.receive() }

        // Start advertising
        startAdvertising()
        logger.i(TAG, "BLE FTMS server started, advertising as '$deviceName'")
    }

    @SuppressLint("MissingPermission") // Permission verified during start()
    fun stop() {
        stopAdvertising()

        gattServer?.close()
        gattServer = null
        callback = null
        connectedDevice = null
        revCounter.reset()
        lastTrainingStatus = 0x01
    }

    @SuppressLint("MissingPermission") // Permission verified during start()
    fun broadcastData(data: ExerciseData) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val cb = callback ?: return

        // Data characteristic notification (device-type-specific)
        val dataUuid = FtmsServiceBuilder.dataCharacteristicUuid(deviceInfo.type)
        if (cb.isSubscribed(dataUuid)) {
            val encodedData = FtmsDataEncoder.encodeData(deviceInfo.type, data)
            val char = server.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
                ?.getCharacteristic(dataUuid)
            if (char != null) {
                char.value = encodedData
                if (!server.notifyCharacteristicChanged(device, char, false)) {
                    logger.w(TAG, "Failed to send data notification")
                }
            }
        }

        // Training Status notification (on mode change)
        val trainingStatus = FtmsDataEncoder.encodeTrainingStatus(data.workoutMode)
        if (trainingStatus[1] != lastTrainingStatus) {
            lastTrainingStatus = trainingStatus[1]
            if (cb.isSubscribed(FtmsServiceBuilder.TRAINING_STATUS_UUID)) {
                val tsChar = server.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
                    ?.getCharacteristic(FtmsServiceBuilder.TRAINING_STATUS_UUID)
                if (tsChar != null) {
                    tsChar.value = trainingStatus
                    server.notifyCharacteristicChanged(device, tsChar, false)
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

    @SuppressLint("MissingPermission") // Permission verified during start()
    fun broadcastStatus(opCode: Byte, parameter: ByteArray? = null) {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val cb = callback ?: return

        if (!cb.isSubscribed(FtmsServiceBuilder.FITNESS_MACHINE_STATUS_UUID)) return

        val value = if (parameter != null) {
            byteArrayOf(opCode) + parameter
        } else {
            byteArrayOf(opCode)
        }

        val char = server.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
            ?.getCharacteristic(FtmsServiceBuilder.FITNESS_MACHINE_STATUS_UUID)
        if (char != null) {
            char.value = value
            if (!server.notifyCharacteristicChanged(device, char, false)) {
                logger.w(TAG, "Failed to send Fitness Machine Status notification")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_CONNECT permission not granted")
        }
    }

    private companion object {
        const val TAG = "FtmsBle"
        const val SERVICE_ADD_TIMEOUT_MS = 5000L
    }
}
