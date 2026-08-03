package com.nettarion.hyperborea.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import com.nettarion.hyperborea.ui.util.UnitFormatter

/**
 * Stadium-shaped 400 m running track with a live position marker: the accent arc sweeps one
 * full circuit per 400 m of belt distance, with a lap counter and total distance in the infield.
 *
 * The sweep animates on [TrackMath.continuousLaps] (a monotonic laps-plus-fraction value) so the
 * lap boundary doesn't animate backwards; only the drawn fraction wraps.
 */
@Composable
fun RunningTrackWidget(
    distanceKm: Float?,
    useImperial: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyperboreaColors.current
    val laps = TrackMath.completedLaps(distanceKm)

    val animatedLaps by animateFloatAsState(
        targetValue = TrackMath.continuousLaps(distanceKm),
        animationSpec = tween(durationMillis = 900),
        label = "trackProgress",
    )
    val fraction = animatedLaps - animatedLaps.toInt()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            val stroke = 14.dp.toPx()
            // Fit the real track proportions (84.39 m straights, 36.50 m curve radius) into the
            // canvas, letterboxed and centered, so the stadium never stretches with the cell.
            val availableWidth = size.width - stroke
            val availableHeight = size.height - stroke
            val trackWidth: Float
            val trackHeight: Float
            if (availableWidth / availableHeight > TrackMath.TRACK_ASPECT_RATIO) {
                trackHeight = availableHeight
                trackWidth = availableHeight * TrackMath.TRACK_ASPECT_RATIO
            } else {
                trackWidth = availableWidth
                trackHeight = availableWidth / TrackMath.TRACK_ASPECT_RATIO
            }
            val left = (size.width - trackWidth) / 2f
            val top = (size.height - trackHeight) / 2f
            val trackRect = Rect(left, top, left + trackWidth, top + trackHeight)
            // Corner radius = half the height: with the fixed aspect ratio this yields exact
            // semicircular bends joining the two straights, like the real thing.
            val radius = trackRect.height / 2f
            val trackPath = Path().apply {
                addRoundRect(RoundRect(trackRect, CornerRadius(radius, radius)))
            }

            drawPath(
                path = trackPath,
                color = colors.divider,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            val measure = PathMeasure().apply { setPath(trackPath, forceClosed = true) }
            if (fraction > 0f) {
                val progressPath = Path()
                measure.getSegment(0f, measure.length * fraction, progressPath, startWithMoveTo = true)
                drawPath(
                    path = progressPath,
                    color = colors.electricBlue,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // Runner marker at the current position (drawn even at 0 so the start is visible).
            val position = measure.getPosition(measure.length * fraction)
            if (position.isSpecified) {
                drawCircle(color = colors.textHigh, radius = stroke * 0.75f, center = position)
                drawCircle(color = colors.electricBlue, radius = stroke * 0.45f, center = position)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Laps: $laps",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textHigh,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = distanceKm?.let { UnitFormatter.distanceDisplay(it, useImperial) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "400 m track",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textLow,
            )
        }
    }
}
