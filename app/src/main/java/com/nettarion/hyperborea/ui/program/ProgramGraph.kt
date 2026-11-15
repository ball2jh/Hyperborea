package com.nettarion.hyperborea.ui.program

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.nettarion.hyperborea.core.program.WorkoutProgram
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors
import kotlin.math.round

@Composable
fun ProgramGraph(
    program: WorkoutProgram,
    speed: Boolean,
    imperial: Boolean,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    elapsedMillis: Long? = null,
    onChange: ((Int, Float) -> Unit)? = null,
) {
    val colors = LocalHyperboreaColors.current
    val scale = if (speed && imperial) 0.621371f else 1f
    val unit = if (speed) { if (imperial) "mph" else "km/h" } else "%"
    val max = if (speed) maxOf(20f, program.blocks.maxOf { it.speedKph }) else maxOf(15f, program.blocks.maxOf { it.inclinePercent })
    val min = if (speed) 0f else minOf(-3f, program.blocks.minOf { it.inclinePercent })
    val latestBounds by rememberUpdatedState(max to min)
    val line = if (speed) colors.accentWarm else colors.electricBlue
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val latestProgram by rememberUpdatedState(program)
    val latestChange by rememberUpdatedState(onChange)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val left = with(density) { 42.dp.toPx() }
    val top = with(density) { 14.dp.toPx() }
    val bottom = with(density) { 28.dp.toPx() }
    var draggingIndex by remember { mutableIntStateOf(0) }
    Column(modifier) {
        Text(if (speed) "Speed · $unit" else "Incline · %", color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().weight(1f).onSizeChanged { canvasSize = it }
            .semantics { contentDescription = "${if (speed) "Speed" else "Incline"} targets over ${program.totalSeconds / 60f} minutes" }
            .then(if (onChange == null) Modifier else Modifier.pointerInput(speed) {
                detectDragGestures(onDragStart = { offset ->
                    val p = latestProgram
                    val seconds = ((offset.x - left) / (canvasSize.width - left - 12.dp.toPx())).coerceIn(0f, 0.999999f) * p.totalSeconds
                    val (upper, lower) = latestBounds
                    val plotHeight = (canvasSize.height - top - bottom).coerceAtLeast(1f)
                    val value = upper - (offset.y - top) / plotHeight * (upper - lower)
                    draggingIndex = programGraphBlockAt(
                        p, seconds, value, speed,
                        (canvasSize.width - left - 12.dp.toPx()) / p.totalSeconds,
                        plotHeight / (upper - lower), 24.dp.toPx(),
                    )
                }, onDrag = { change, _ ->
                    change.consume()
                    val (upper, lower) = latestBounds
                    val value = upper - ((change.position.y - top) / (canvasSize.height - top - bottom)) * (upper - lower)
                    latestChange?.invoke(draggingIndex, if (speed) (round(value * 10) / 10).coerceIn(0.5f, 30f) else (round(value * 2) / 2).coerceIn(-10f, 40f))
                })
            })
        ) {
            val right = size.width - 12.dp.toPx()
            val base = size.height - bottom
            fun x(seconds: Float) = left + seconds / program.totalSeconds * (right - left)
            fun y(value: Float) = top + (max - value) / (max - min) * (base - top)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.textMedium.toArgb(); textSize = 12.dp.toPx() }
            for (i in 0..3) {
                val value = min + (max - min) * i / 3
                drawLine(colors.divider, Offset(left, y(value)), Offset(right, y(value)))
                drawContext.canvas.nativeCanvas.drawText("%.0f".format(value * scale), 2.dp.toPx(), y(value) + 4.dp.toPx(), paint)
            }
            for (i in 0..4) {
                val seconds = program.totalSeconds * i / 4f
                paint.textAlign = when (i) { 4 -> Paint.Align.RIGHT; 0 -> Paint.Align.LEFT; else -> Paint.Align.CENTER }
                drawContext.canvas.nativeCanvas.drawText("%.1f".format(seconds / 60) + if (i == 4) " min" else "", x(seconds), size.height - 4.dp.toPx(), paint)
            }
            var start = 0f
            val path = Path()
            program.blocks.forEachIndexed { index, block ->
                val end = start + block.durationSeconds
                fun value(i: Int) = program.blocks[i].let { if (speed) it.speedKph else it.inclinePercent }
                val from = if (block.ramp && index > 0) value(index - 1) else value(index)
                if (selectedId == block.id) drawRect(line.copy(alpha = .10f), Offset(x(start), top), Size(x(end) - x(start), base - top))
                if (index == 0) path.moveTo(x(start), y(from)) else path.lineTo(x(start), y(from))
                path.lineTo(x(end), y(value(index)))
                if (onChange != null) drawCircle(line, if (selectedId == block.id) 7.dp.toPx() else 4.dp.toPx(), Offset(x(if (block.ramp && index > 0) end else (start + end) / 2), y(value(index))))
                start = end
            }
            drawPath(path, line, style = Stroke(3.dp.toPx()))
            elapsedMillis?.let { drawLine(colors.textHigh, Offset(x(it / 1000f), top), Offset(x(it / 1000f), base), 2.dp.toPx()) }
        }
    }
}
