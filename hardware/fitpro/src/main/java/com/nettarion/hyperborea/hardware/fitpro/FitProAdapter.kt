package com.nettarion.hyperborea.hardware.fitpro

import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceIdentity
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.FulfillResult
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import com.nettarion.hyperborea.hardware.fitpro.session.DeviceDatabase
import com.nettarion.hyperborea.hardware.fitpro.session.EquipmentProfiles
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransportFactory
import com.nettarion.hyperborea.hardware.fitpro.v1.V1Session
import com.nettarion.hyperborea.hardware.fitpro.v2.V2Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class FitProAdapter @Inject constructor(
    private val transportFactory: HidTransportFactory,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : HardwareAdapter {

    override val prerequisites = listOf(
        Prerequisite(
            id = "usb-device-accessible",
            description = "FitPro USB device must be accessible",
            isMet = { snapshot ->
                snapshot.usbDevices.any {
                    it.vendorId == FITPRO_VENDOR_ID && it.productId in FITPRO_PRODUCT_IDS
                }
            },
            fulfill = { controller ->
                if (controller.grantUsbPermission(HYPERBOREA_PACKAGE)) FulfillResult.Success
                else FulfillResult.Failed("USB permission not granted")
            },
        ),
    )

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isUsbHostAvailable

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    private val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity.asStateFlow()

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    private var session: FitProSession? = null
    private var dataForwardJob: Job? = null
    private var identityForwardJob: Job? = null
    private var stateMonitorJob: Job? = null

    override suspend fun connect() {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating

        try {
            val result = transportFactory.create(FITPRO_VENDOR_ID, FITPRO_PRODUCT_ID_V1)
            val transport = result.transport
            val productId = result.productId

            val equipmentProfile = EquipmentProfiles.fromProductId(productId)
            if (equipmentProfile == null) {
                logger.e(TAG, "Unknown product ID: $productId")
                _state.value = AdapterState.Error("Unknown FitPro product ID: $productId")
                return
            }

            val newSession = when (productId) {
                FITPRO_PRODUCT_ID_V1 -> {
                    logger.i(TAG, "FitPro V1 protocol (product ID $productId)")
                    V1Session(transport, logger, scope, equipmentProfile)
                }
                FITPRO_PRODUCT_ID_V2, FITPRO_PRODUCT_ID_V2_FTDI -> {
                    logger.i(TAG, "FitPro V2 protocol (product ID $productId)")
                    V2Session(transport, logger, scope)
                }
                else -> {
                    logger.e(TAG, "Unknown product ID: $productId")
                    _state.value = AdapterState.Error("Unknown FitPro product ID: $productId")
                    return
                }
            }

            newSession.start()
            session = newSession
            logger.i(TAG, "Connected via ${if (productId == FITPRO_PRODUCT_ID_V1) "V1" else "V2"}")

            // Set Active immediately if session already streaming (monitor coroutine may not have collected yet)
            if (newSession.sessionState.value is SessionState.Streaming) {
                _state.value = AdapterState.Active
            }

            // Capture identity available from handshake and derive DeviceInfo
            updateIdentity(newSession.deviceIdentity.value)

            // Forward exercise data from session
            dataForwardJob = scope.launch {
                newSession.exerciseData.collect { data ->
                    _exerciseData.value = data
                }
            }

            // Forward device identity changes and derive DeviceInfo
            identityForwardJob = scope.launch {
                newSession.deviceIdentity.collect { identity ->
                    updateIdentity(identity)
                }
            }

            // Monitor session state for disconnects/errors
            stateMonitorJob = scope.launch {
                newSession.sessionState.collect { sessionState ->
                    when (sessionState) {
                        is SessionState.Streaming -> _state.value = AdapterState.Active
                        is SessionState.Error -> {
                            _state.value = AdapterState.Error(sessionState.message, sessionState.cause)
                        }
                        is SessionState.Disconnected -> {
                            if (_state.value is AdapterState.Active) {
                                _state.value = AdapterState.Error("Device disconnected")
                            }
                        }
                        is SessionState.Connecting, is SessionState.Handshaking -> { /* Activating already set */ }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect", e)
            _state.value = AdapterState.Error("Connection failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        if (_state.value is AdapterState.Inactive) return
        logger.i(TAG, "Disconnecting")

        dataForwardJob?.cancel()
        dataForwardJob = null
        identityForwardJob?.cancel()
        identityForwardJob = null
        stateMonitorJob?.cancel()
        stateMonitorJob = null

        session?.stop()
        session = null

        _exerciseData.value = null
        _deviceIdentity.value = null
        _deviceInfo.value = null
        _state.value = AdapterState.Inactive
    }

    private fun updateIdentity(identity: DeviceIdentity?) {
        _deviceIdentity.value = identity
        _deviceInfo.value = when {
            identity == null -> null
            else -> {
                val model = identity.model
                val modelNumber = model?.toIntOrNull()
                if (modelNumber != null) DeviceDatabase.fromModel(modelNumber)
                else DeviceDatabase.fallback()
            }
        }
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        logger.d(TAG, "Sending command: $command")
        session?.writeFeature(command)
    }

    private companion object {
        const val TAG = "FitProAdapter"
        const val FITPRO_VENDOR_ID = 0x213C
        const val FITPRO_PRODUCT_ID_V1 = 2
        const val FITPRO_PRODUCT_ID_V2 = 3
        const val FITPRO_PRODUCT_ID_V2_FTDI = 4
        val FITPRO_PRODUCT_IDS = setOf(FITPRO_PRODUCT_ID_V1, FITPRO_PRODUCT_ID_V2, FITPRO_PRODUCT_ID_V2_FTDI)
        const val HYPERBOREA_PACKAGE = "com.nettarion.hyperborea"
    }
}
