package com.nettarion.hyperborea.core.profile

/**
 * Visual style of the floating system overlay shown while another app is in the foreground.
 *
 * [METRICS] is the read-mostly bar (resistance/power/cadence/incline + pause/stop) and only
 * appears mid-workout. [CONTROLS] is a treadmill-style remote (incline/speed −/+ plus
 * start/pause/stop) that also persists while idle so a workout can be started from another app.
 */
enum class OverlayStyle { METRICS, CONTROLS }
