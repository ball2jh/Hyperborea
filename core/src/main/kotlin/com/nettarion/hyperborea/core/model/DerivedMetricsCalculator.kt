package com.nettarion.hyperborea.core.model

fun computeDerivedMetrics(
    summary: RideSummary,
    samples: List<WorkoutSample>,
    profile: Profile?,
): DerivedMetrics {
    val hasPower = samples.any { it.power != null }
    val hasHr = samples.any { it.heartRate != null }

    val workKj = if (hasPower) {
        samples.sumOf { it.power ?: 0 } / 1000f
    } else null

    val variabilityIndex = run {
        val np = summary.normalizedPower ?: return@run null
        val avg = summary.avgPower ?: return@run null
        if (avg == 0) return@run null
        np.toFloat() / avg
    }

    val efficiencyFactor = run {
        val np = summary.normalizedPower ?: return@run null
        val avgHr = summary.avgHeartRate ?: return@run null
        if (avgHr == 0) return@run null
        np.toFloat() / avgHr
    }

    val weightKg = profile?.weightKg
    val avgPowerPerKg = if (summary.avgPower != null && weightKg != null && weightKg > 0f) {
        summary.avgPower.toFloat() / weightKg
    } else null

    val maxPowerPerKg = if (summary.maxPower != null && weightKg != null && weightKg > 0f) {
        summary.maxPower.toFloat() / weightKg
    } else null

    val caloriesPerHour = if (summary.durationSeconds > 0) {
        summary.calories * 3600f / summary.durationSeconds
    } else null

    val powerZones = buildPowerZones(samples, profile?.ftpWatts)
    val hrZones = buildHrZones(samples, profile?.maxHeartRate)

    return DerivedMetrics(
        workKj = workKj,
        variabilityIndex = variabilityIndex,
        efficiencyFactor = efficiencyFactor,
        avgPowerPerKg = avgPowerPerKg,
        maxPowerPerKg = maxPowerPerKg,
        caloriesPerHour = caloriesPerHour,
        powerZones = powerZones,
        hrZones = hrZones,
    )
}

private fun buildPowerZones(samples: List<WorkoutSample>, ftpWatts: Int?): ZoneDistribution? {
    if (ftpWatts == null || ftpWatts <= 0) return null
    val powerSamples = samples.filter { it.power != null }
    if (powerSamples.isEmpty()) return null

    val thresholds = listOf(
        "Z1 Recovery" to 0.0f,
        "Z2 Endurance" to 0.55f,
        "Z3 Tempo" to 0.76f,
        "Z4 Threshold" to 0.91f,
        "Z5 VO2max" to 1.06f,
        "Z6 Anaerobic" to 1.21f,
        "Z7 Neuromuscular" to 1.51f,
    )

    val buckets = IntArray(7)
    for (sample in powerSamples) {
        val pct = (sample.power ?: 0).toFloat() / ftpWatts
        val zone = when {
            pct >= 1.51f -> 6
            pct >= 1.21f -> 5
            pct >= 1.06f -> 4
            pct >= 0.91f -> 3
            pct >= 0.76f -> 2
            pct >= 0.55f -> 1
            else -> 0
        }
        buckets[zone]++
    }

    val totalSeconds = powerSamples.size
    val zones = thresholds.mapIndexed { i, (name, _) ->
        ZoneBucket(
            name = name,
            seconds = buckets[i],
            percentage = if (totalSeconds > 0) buckets[i] * 100f / totalSeconds else 0f,
        )
    }

    return ZoneDistribution(
        referenceValue = ftpWatts,
        referenceLabel = "FTP",
        zones = zones,
    )
}

private fun buildHrZones(samples: List<WorkoutSample>, maxHeartRate: Int?): ZoneDistribution? {
    if (maxHeartRate == null || maxHeartRate <= 0) return null
    val hrSamples = samples.filter { it.heartRate != null }
    if (hrSamples.isEmpty()) return null

    val thresholds = listOf(
        "Z1 Recovery" to 0.0f,
        "Z2 Easy" to 0.60f,
        "Z3 Moderate" to 0.70f,
        "Z4 Hard" to 0.80f,
        "Z5 Maximum" to 0.90f,
    )

    val buckets = IntArray(5)
    for (sample in hrSamples) {
        val pct = (sample.heartRate ?: 0).toFloat() / maxHeartRate
        val zone = when {
            pct >= 0.90f -> 4
            pct >= 0.80f -> 3
            pct >= 0.70f -> 2
            pct >= 0.60f -> 1
            else -> 0
        }
        buckets[zone]++
    }

    val totalSeconds = hrSamples.size
    val zones = thresholds.mapIndexed { i, (name, _) ->
        ZoneBucket(
            name = name,
            seconds = buckets[i],
            percentage = if (totalSeconds > 0) buckets[i] * 100f / totalSeconds else 0f,
        )
    }

    return ZoneDistribution(
        referenceValue = maxHeartRate,
        referenceLabel = "Max HR",
        zones = zones,
    )
}
