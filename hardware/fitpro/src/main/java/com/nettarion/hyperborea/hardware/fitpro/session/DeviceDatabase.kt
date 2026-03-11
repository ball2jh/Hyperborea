package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric

object DeviceDatabase {

    private data class DeviceRecord(
        val name: String,
        val type: DeviceType,
        val supportedMetrics: Set<Metric>,
        val maxResistance: Int,
        val minResistance: Int,
        val minIncline: Float,
        val maxIncline: Float,
        val maxPower: Int,
        val minPower: Int,
        val powerStep: Int,
        val resistanceStep: Float,
        val inclineStep: Float,
        val speedStep: Float,
        val maxSpeed: Float,
    )

    private val STANDARD_BIKE_METRICS = setOf(
        Metric.POWER, Metric.CADENCE, Metric.SPEED,
        Metric.RESISTANCE, Metric.INCLINE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    private val STANDARD_TREADMILL_METRICS = setOf(
        Metric.POWER, Metric.SPEED, Metric.INCLINE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    private val STANDARD_ROWER_METRICS = setOf(
        Metric.POWER, Metric.CADENCE, Metric.SPEED,
        Metric.RESISTANCE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    private val STANDARD_ELLIPTICAL_METRICS = setOf(
        Metric.POWER, Metric.CADENCE, Metric.SPEED,
        Metric.RESISTANCE, Metric.INCLINE,
        Metric.DISTANCE, Metric.CALORIES,
    )

    // Known model numbers from V1 handshake SystemInfoResponse.
    // Only model 2117 (S22i) is hardware-verified. All other model numbers are
    // inferred from the ICON model number pattern (e.g. NTEX02117 → 2117).
    // Specs are from marketing materials.
    private val knownModels: Map<Int, DeviceRecord> = mapOf(
        // ── NordicTrack Bikes ──
        2117 to DeviceRecord(
            name = "NordicTrack S22i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        2121 to DeviceRecord(
            name = "NordicTrack S22i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        2422 to DeviceRecord(
            name = "NordicTrack S22i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        2722 to DeviceRecord(
            name = "NordicTrack S27i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        5119 to DeviceRecord(
            name = "NordicTrack S15i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        3121 to DeviceRecord(
            name = "NordicTrack S10i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        5121 to DeviceRecord(
            name = "NordicTrack S15i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        5117 to DeviceRecord(
            name = "NordicTrack S10i",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        12921 to DeviceRecord(
            name = "NordicTrack VU 29",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        14921 to DeviceRecord(
            name = "NordicTrack R35",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),

        // ── NordicTrack Treadmills ──
        39225 to DeviceRecord(
            name = "NordicTrack Commercial X32i",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -6f, maxIncline = 40f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        29221 to DeviceRecord(
            name = "NordicTrack X22i",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -6f, maxIncline = 40f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        19221 to DeviceRecord(
            name = "NordicTrack Commercial 2950",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        19125 to DeviceRecord(
            name = "NordicTrack Commercial 2450",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        14119 to DeviceRecord(
            name = "NordicTrack Commercial 1750",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        17125 to DeviceRecord(
            name = "NordicTrack Commercial 1750",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 22f,
        ),
        14124 to DeviceRecord(
            name = "NordicTrack Commercial 1250",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        14125 to DeviceRecord(
            name = "NordicTrack Commercial 1250",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        13125 to DeviceRecord(
            name = "NordicTrack Commercial LE",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        15423 to DeviceRecord(
            name = "NordicTrack EXP 10i",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 16.1f,
        ),

        // ── NordicTrack Rowers ──
        19425 to DeviceRecord(
            name = "NordicTrack RW900",
            type = DeviceType.ROWER,
            supportedMetrics = STANDARD_ROWER_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0f, maxSpeed = 0f,
        ),
        15125 to DeviceRecord(
            name = "NordicTrack RW700",
            type = DeviceType.ROWER,
            supportedMetrics = STANDARD_ROWER_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0f, maxSpeed = 0f,
        ),
        10124 to DeviceRecord(
            name = "NordicTrack RW600",
            type = DeviceType.ROWER,
            supportedMetrics = STANDARD_ROWER_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0f, maxSpeed = 0f,
        ),

        // ── NordicTrack Ellipticals ──
        71620 to DeviceRecord(
            name = "NordicTrack FS14i",
            type = DeviceType.ELLIPTICAL,
            supportedMetrics = STANDARD_ELLIPTICAL_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = -10f, maxIncline = 10f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        71423 to DeviceRecord(
            name = "NordicTrack AirGlide 14i",
            type = DeviceType.ELLIPTICAL,
            supportedMetrics = STANDARD_ELLIPTICAL_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = -5f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        71320 to DeviceRecord(
            name = "NordicTrack FS10i",
            type = DeviceType.ELLIPTICAL,
            supportedMetrics = STANDARD_ELLIPTICAL_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 10f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),

        // ── ProForm Bikes (no incline) ──
        92220 to DeviceRecord(
            name = "ProForm Studio Bike Pro 22",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        16723 to DeviceRecord(
            name = "ProForm Studio Bike Pro 14",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        16718 to DeviceRecord(
            name = "ProForm Studio Bike Pro",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        68919 to DeviceRecord(
            name = "ProForm Studio Bike Limited",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        16725 to DeviceRecord(
            name = "ProForm Carbon Pro 10 Studio Bike",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        79920 to DeviceRecord(
            name = "ProForm Studio Bike Pro TC",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 22, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),

        // ── ProForm Treadmills ──
        15820 to DeviceRecord(
            name = "ProForm Pro 9000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        17116 to DeviceRecord(
            name = "ProForm Pro 9000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        16925 to DeviceRecord(
            name = "ProForm Carbon Pro 9000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        12820 to DeviceRecord(
            name = "ProForm Pro 2000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        10925 to DeviceRecord(
            name = "ProForm Carbon Pro 2000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        14823 to DeviceRecord(
            name = "ProForm Trainer 14.0",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        12823 to DeviceRecord(
            name = "ProForm Carbon T14",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 16.1f,
        ),
        99920 to DeviceRecord(
            name = "ProForm Carbon T10",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 16.1f,
        ),
        99721 to DeviceRecord(
            name = "ProForm Trainer 12.0",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        10724 to DeviceRecord(
            name = "ProForm Trainer 1000",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 12f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),

        // ── ProForm Rower ──
        98120 to DeviceRecord(
            name = "ProForm Pro R10",
            type = DeviceType.ROWER,
            supportedMetrics = STANDARD_ROWER_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0f, maxSpeed = 0f,
        ),

        // ── ProForm Elliptical ──
        1420 to DeviceRecord(
            name = "ProForm Pro HIIT H14",
            type = DeviceType.ELLIPTICAL,
            supportedMetrics = STANDARD_ELLIPTICAL_METRICS,
            maxResistance = 26, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),

        // ── FreeMotion Bikes ──
        82820 to DeviceRecord(
            name = "FreeMotion Coachbike b22.7",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        84821 to DeviceRecord(
            name = "FreeMotion Coachbike",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = -10f, maxIncline = 20f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        82420 to DeviceRecord(
            name = "FreeMotion u22.9 Upright",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
        82520 to DeviceRecord(
            name = "FreeMotion r22.9 Recumbent",
            type = DeviceType.BIKE,
            supportedMetrics = STANDARD_BIKE_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),

        // ── FreeMotion Treadmills ──
        70920 to DeviceRecord(
            name = "FreeMotion t22.9 Reflex",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 24.1f,
        ),
        70718 to DeviceRecord(
            name = "FreeMotion t10.9b Reflex",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = 0f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 19.3f,
        ),
        74819 to DeviceRecord(
            name = "FreeMotion i22.9 Incline Trainer",
            type = DeviceType.TREADMILL,
            supportedMetrics = STANDARD_TREADMILL_METRICS,
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 30f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0.5f, speedStep = 0.5f, maxSpeed = 24.1f,
        ),

        // ── FreeMotion Elliptical ──
        84420 to DeviceRecord(
            name = "FreeMotion e22.9",
            type = DeviceType.ELLIPTICAL,
            supportedMetrics = STANDARD_ELLIPTICAL_METRICS,
            maxResistance = 24, minResistance = 1,
            minIncline = 0f, maxIncline = 0f,
            maxPower = 2000, minPower = 0, powerStep = 1, resistanceStep = 1.0f, inclineStep = 0f, speedStep = 0.5f, maxSpeed = 60f,
        ),
    )

    fun fromModel(modelNumber: Int): DeviceInfo {
        val record = knownModels[modelNumber] ?: FALLBACK
        return record.toDeviceInfo()
    }

    fun fallback(): DeviceInfo = FALLBACK.toDeviceInfo()

    private val FALLBACK = DeviceRecord(
        name = "FitPro Device",
        type = DeviceType.BIKE,
        supportedMetrics = STANDARD_BIKE_METRICS,
        maxResistance = 24,
        minResistance = 1,
        minIncline = -10f,
        maxIncline = 20f,
        maxPower = 2000,
        minPower = 0,
        powerStep = 1,
        resistanceStep = 1.0f,
        inclineStep = 0.5f,
        speedStep = 0.5f,
        maxSpeed = 60f,
    )

    private val productIdToModel: Map<Int, Int> = mapOf(
        2 to 2117, 3 to 2117, 4 to 2117,
    )

    fun fromProductId(productId: Int): DeviceInfo? {
        val modelNumber = productIdToModel[productId] ?: return null
        return fromModel(modelNumber)
    }

    private fun DeviceRecord.toDeviceInfo() = DeviceInfo(
        name = name,
        type = type,
        supportedMetrics = supportedMetrics,
        maxResistance = maxResistance,
        minResistance = minResistance,
        minIncline = minIncline,
        maxIncline = maxIncline,
        maxPower = maxPower,
        minPower = minPower,
        powerStep = powerStep,
        resistanceStep = resistanceStep,
        inclineStep = inclineStep,
        speedStep = speedStep,
        maxSpeed = maxSpeed,
    )
}
