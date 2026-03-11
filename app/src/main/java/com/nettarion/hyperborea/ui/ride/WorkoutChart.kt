package com.nettarion.hyperborea.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.nettarion.hyperborea.core.model.WorkoutSample
import com.nettarion.hyperborea.ui.theme.TextHigh
import com.nettarion.hyperborea.ui.theme.TextLow
import com.nettarion.hyperborea.ui.theme.TextMedium
import kotlin.math.ceil

@Composable
fun WorkoutChart(
    samples: List<WorkoutSample>,
    valueSelector: (WorkoutSample) -> Number?,
    label: String,
    unit: String,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val chartData = remember(samples, valueSelector) { buildChartData(samples, valueSelector) }

    if (chartData == null) return

    Canvas(modifier = modifier) {
        val leftPadding = 48f
        val bottomPadding = 20f
        val topPadding = 28f
        val rightPadding = 8f

        val chartLeft = leftPadding
        val chartTop = topPadding
        val chartRight = size.width - rightPadding
        val chartBottom = size.height - bottomPadding
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

        val gridLines = niceGridLines(chartData.min, chartData.max)
        val yMin = gridLines.first().toFloat()
        val yMax = gridLines.last().toFloat()
        val yRange = if (yMax > yMin) yMax - yMin else 1f

        // Grid lines + Y labels
        val gridStyle = TextStyle(color = TextLow, fontSize = 9.sp)
        for (value in gridLines) {
            val y = chartBottom - ((value - yMin) / yRange) * chartHeight
            drawLine(
                color = TextLow.copy(alpha = 0.3f),
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1f,
            )
            val text = textMeasurer.measure(value.toString(), gridStyle)
            drawText(text, topLeft = Offset(chartLeft - text.size.width - 4f, y - text.size.height / 2f))
        }

        // X-axis time labels
        drawTimeLabels(textMeasurer, chartData.maxTime, chartLeft, chartRight, chartBottom, gridStyle)

        // Area fill + line
        val points = chartData.points
        if (points.size >= 2) {
            val linePath = Path()
            val fillPath = Path()
            var started = false

            for (point in points) {
                val x = chartLeft + (point.time / chartData.maxTime.toFloat()) * chartWidth
                val y = chartBottom - ((point.value - yMin) / yRange) * chartHeight

                if (!started) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, chartBottom)
                    fillPath.lineTo(x, y)
                    started = true
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // Close fill path
            val lastX = chartLeft + (points.last().time / chartData.maxTime.toFloat()) * chartWidth
            fillPath.lineTo(lastX, chartBottom)
            fillPath.close()

            drawPath(fillPath, color = lineColor.copy(alpha = 0.15f), style = Fill)
            drawPath(linePath, color = lineColor, style = Stroke(width = 1.5f))
        }

        // Label + stats overlay
        val labelText = "$label ($unit)"
        val labelResult = textMeasurer.measure(labelText, TextStyle(color = TextMedium, fontSize = 10.sp))
        drawText(labelResult, topLeft = Offset(chartLeft + 4f, 2f))

        val statsText = "avg ${chartData.avg}  max ${chartData.max}"
        val statsResult = textMeasurer.measure(statsText, TextStyle(color = TextLow, fontSize = 9.sp))
        drawText(statsResult, topLeft = Offset(chartRight - statsResult.size.width - 4f, 2f))
    }
}

private fun DrawScope.drawTimeLabels(
    textMeasurer: TextMeasurer,
    maxTime: Long,
    chartLeft: Float,
    chartRight: Float,
    chartBottom: Float,
    style: TextStyle,
) {
    if (maxTime <= 0) return
    val chartWidth = chartRight - chartLeft

    // Choose interval: 5m, 10m, 15m, 30m, 60m based on duration
    val intervalSec = when {
        maxTime <= 600 -> 120L    // 2 min intervals for <=10 min
        maxTime <= 1800 -> 300L   // 5 min
        maxTime <= 3600 -> 600L   // 10 min
        maxTime <= 7200 -> 900L   // 15 min
        else -> 1800L             // 30 min
    }

    var t = 0L
    while (t <= maxTime) {
        val x = chartLeft + (t.toFloat() / maxTime) * chartWidth
        val m = t / 60
        val label = if (m < 60) "${m}:00" else "${m / 60}:${"%02d".format(m % 60)}:00"
        val result = textMeasurer.measure(label, style)
        if (x + result.size.width <= chartRight + 20f) {
            drawText(result, topLeft = Offset(x - result.size.width / 2f, chartBottom + 2f))
        }
        t += intervalSec
    }
}

private data class ChartPoint(val time: Long, val value: Float)

private data class ChartData(
    val points: List<ChartPoint>,
    val min: Int,
    val max: Int,
    val avg: Int,
    val maxTime: Long,
)

private fun buildChartData(
    samples: List<WorkoutSample>,
    valueSelector: (WorkoutSample) -> Number?,
): ChartData? {
    val points = samples.mapNotNull { sample ->
        val v = valueSelector(sample)?.toFloat() ?: return@mapNotNull null
        ChartPoint(sample.timestampSeconds, v)
    }
    if (points.isEmpty()) return null

    val values = points.map { it.value }
    val min = values.min().toInt()
    val max = values.max().toInt()
    val avg = values.average().toInt()
    val maxTime = points.maxOf { it.time }

    return ChartData(points, min, max, avg, maxTime)
}

private fun niceGridLines(min: Int, max: Int): List<Int> {
    val range = max - min
    if (range <= 0) return listOf(min, min + 1)

    // Choose a step that gives 3-5 grid lines
    val rawStep = range / 4.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(rawStep))).toInt().coerceAtLeast(1)
    val step = when {
        rawStep <= magnitude -> magnitude
        rawStep <= magnitude * 2 -> magnitude * 2
        rawStep <= magnitude * 5 -> magnitude * 5
        else -> magnitude * 10
    }

    val start = (min / step) * step
    val end = (ceil(max.toDouble() / step) * step).toInt()

    val lines = mutableListOf<Int>()
    var v = start
    while (v <= end) {
        lines.add(v)
        v += step
    }
    return lines
}
