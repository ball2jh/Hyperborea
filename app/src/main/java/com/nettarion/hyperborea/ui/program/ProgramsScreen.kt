package com.nettarion.hyperborea.ui.program

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.program.*
import com.nettarion.hyperborea.program.ProgramStartMode
import com.nettarion.hyperborea.program.programStartMode
import com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors

internal fun programTime(seconds: Int): String = "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

@Composable
fun ProgramsScreen(onBack: () -> Unit, onStarted: () -> Unit, viewModel: ProgramsViewModel = hiltViewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val selected by viewModel.selectedId.collectAsStateWithLifecycle()
    val hasDraft by viewModel.hasDraft.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val savedTab by viewModel.savedTab.collectAsStateWithLifecycle()
    val imperial by viewModel.imperial.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val workout by viewModel.workoutState.collectAsStateWithLifecycle()
    val run by viewModel.programState.collectAsStateWithLifecycle()
    val startMode = programStartMode(workout, run?.active == true)
    var warmupProgram by remember { mutableStateOf<WorkoutProgram?>(null) }
    fun requestStart(program: WorkoutProgram) {
        if (startMode == ProgramStartMode.AFTER_WARMUP) warmupProgram = program
        else viewModel.start(program, onStarted)
    }
    val colors = LocalHyperboreaColors.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = colors.textHigh) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (editing) viewModel.editing.value = false else onBack() }) { Text("← Back") }
            Text(if (editing) "Edit program" else "Programs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (!editing && hasDraft) TextButton(onClick = viewModel::continueDraft) { Text("Continue draft") }
            if (!editing) Button(onClick = viewModel::custom) { Text("＋ Create custom") }
        }
        message?.let { Text(it, color = colors.accentWarm) }
        val startBlockedReason = when (workout) {
            OrchestratorState.Paused -> "A workout is paused. Resume your manual walk to start a program, or stop and save or discard the current workout."
            is OrchestratorState.Running -> if (startMode == ProgramStartMode.AFTER_WARMUP) null else "A program is already running, or the equipment connection needs attention. Return to the dashboard before starting another program."
            is OrchestratorState.AwaitingConsoleStart -> "A workout is already armed. Return to the dashboard and cancel it before choosing a program."
            is OrchestratorState.Preparing -> "Getting the treadmill ready. Program starts will be available when preparation finishes."
            OrchestratorState.Stopping -> "Ending the current workout. Program starts will be available when it finishes."
            else -> if (device == null) "The treadmill has not been identified yet. Return to the dashboard to check the equipment connection." else null
        }
        if (startMode == ProgramStartMode.AFTER_WARMUP) Text("Warming up? Choose a program to continue this workout after a 3–2–1 count-in.", color = colors.accentWarm)
        startBlockedReason?.let { reason ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onBack) { Text("Return to workout") }
                }
            }
        }
        if (!editing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !savedTab, onClick = { viewModel.savedTab.value = false }, label = { Text("Ready-made") })
                FilterChip(selected = savedTab, onClick = { viewModel.savedTab.value = true }, label = { Text("My programs (${saved.size})") })
                if (savedTab) {
                    OutlinedButton(onClick = { viewModel.importFiles() }) { Text("Import files") }
                    OutlinedButton(enabled = saved.isNotEmpty(), onClick = { viewModel.exportFiles() }) { Text("Export files") }
                }
                Spacer(Modifier.weight(1f))
                ProgramOverlayToggle()
            }
            if (savedTab) viewModel.filesPath?.let { path ->
                Text("Program files are JSON, one per program, in $path — copy them with adb push / adb pull, then Import.", color = colors.textMedium, style = MaterialTheme.typography.bodySmall)
            }
            if (savedTab && saved.isEmpty()) Text("Save a custom program to run it again later.", color = colors.textMedium)
            LazyVerticalGrid(GridCells.Adaptive(480.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(if (savedTab) saved else ProgramTemplates.all, key = { it.id }) { program ->
                    Card {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(program.name, style = MaterialTheme.typography.headlineSmall)
                            Text("${programTime(program.totalSeconds)}${if (program.loop) " per loop" else ""} · ${program.blocks.size} blocks${if (program.loop) " · Loops until stopped" else ""}", color = colors.textMedium)
                            ProgramGraph(program, true, imperial, Modifier.fillMaxWidth().height(130.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { viewModel.edit(program) }) { Text("Edit") }
                                Button(enabled = startMode != null && device != null, onClick = { requestStart(program) }) { Text("Start program") }
                                if (savedTab) TextButton(onClick = { viewModel.delete(program) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        } else {
            val index = draft.blocks.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            val block = draft.blocks[index]
            var minutesText by remember(block.id) { mutableStateOf((block.durationSeconds / 60).toString()) }
            var secondsText by remember(block.id) { mutableStateOf((block.durationSeconds % 60).toString()) }
            val enteredDuration = parseProgramDuration(minutesText, secondsText)
            fun changeDuration(minutes: String, seconds: String) {
                if (minutes.length > 2 || seconds.length > 2 || !minutes.all(Char::isDigit) || !seconds.all(Char::isDigit)) return
                minutesText = minutes
                secondsText = seconds
                parseProgramDuration(minutes, seconds)?.let { viewModel.updateBlock(index, block.copy(durationSeconds = it)) }
            }
            OutlinedTextField(value = draft.name, onValueChange = { viewModel.update(draft.copy(name = it.take(60))) }, label = { Text("Program name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProgramGraph(draft, true, imperial, Modifier.fillMaxWidth().weight(1f), selectedId = selected, onChange = { i, value -> viewModel.selectedId.value = draft.blocks[i].id; viewModel.updateBlock(i, draft.blocks[i].copy(speedKph = value.coerceIn(.5f, maxOf(.5f, device?.maxSpeed ?: 30f)))) })
                    ProgramGraph(draft, false, imperial, Modifier.fillMaxWidth().weight(1f), selectedId = selected, onChange = { i, value -> viewModel.selectedId.value = draft.blocks[i].id; viewModel.updateBlock(i, draft.blocks[i].copy(inclinePercent = value.coerceIn(device?.minIncline ?: -10f, device?.maxIncline ?: 40f))) })
                    BlockStrip(draft, selected, onSelect = { viewModel.selectedId.value = it }, onMove = viewModel::move)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { viewModel.add(false) }) { Text("＋ Add block") }
                        OutlinedButton(onClick = { viewModel.add(true) }) { Text("Repeat block") }
                        Text("Drag ⠿ to reorder", color = colors.textMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    WholeProgramShift(
                        draft, imperial,
                        // The connected treadmill's limits, else the widest a program may hold.
                        speedRange = WorkoutProgram.MIN_SPEED_KPH..maxOf(WorkoutProgram.MIN_SPEED_KPH, device?.maxSpeed?.takeIf { it > 0f } ?: WorkoutProgram.MAX_SPEED_KPH),
                        inclineRange = device?.takeIf { it.maxIncline > it.minIncline }?.let { it.minIncline..it.maxIncline } ?: WorkoutProgram.MIN_INCLINE_PERCENT..WorkoutProgram.MAX_INCLINE_PERCENT,
                        onShift = viewModel::update,
                    )
                }
                Column(Modifier.width(340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().toggleable(value = draft.loop, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { viewModel.update(draft.copy(loop = it)) }), verticalAlignment = Alignment.CenterVertically) {
                        Text("Loop program", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = draft.loop, onCheckedChange = null)
                    }
                    if (draft.loop) Text("Repeat all blocks until you press Stop.", color = colors.textMedium)
                    if (!draft.loop) {
                        Row(Modifier.fillMaxWidth().toggleable(value = draft.easeToStop, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { viewModel.update(draft.copy(easeToStop = it)) }), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ease to a stop", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = draft.easeToStop, onCheckedChange = null)
                        }
                        val windDown = draft.windDownBlock()
                        if (draft.easeToStop) Text(
                            if (windDown != null) "After the last block the belt slows to ${"%.1f".format(windDown.speedKph * if (imperial) .621371f else 1f)} ${if (imperial) "mph" else "km/h"} over ${programTime(windDown.durationSeconds)}, then stops."
                            else "The last block is already at walking pace, so the belt stops when it ends.",
                            color = colors.textMedium,
                        )
                    }
                    Text("Block ${index + 1}", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = block.name, onValueChange = { viewModel.updateBlock(index, block.copy(name = it.take(40))) }, label = { Text("Block name") }, singleLine = true)
                    Text("Duration", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { changeDuration(it, secondsText) },
                            label = { Text("Minutes") },
                            placeholder = { Text("0") },
                            singleLine = true,
                            isError = enteredDuration == null,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = secondsText,
                            onValueChange = { changeDuration(minutesText, it) },
                            label = { Text("Seconds") },
                            placeholder = { Text("00") },
                            singleLine = true,
                            isError = enteredDuration == null,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (enteredDuration == null) Text("Enter 0:10–60:00, with seconds from 0 to 59.", color = MaterialTheme.colorScheme.error)
                    Text("Speed: ${"%.1f".format(block.speedKph * if (imperial) .621371f else 1f)} ${if (imperial) "mph" else "km/h"}")
                    Slider(value = block.speedKph, onValueChange = { viewModel.updateBlock(index, block.copy(speedKph = kotlin.math.round(it * 10) / 10)) }, valueRange = .5f..maxOf(device?.maxSpeed ?: 20f, block.speedKph))
                    val speedStep = if (imperial) .1f / .621371f else .1f
                    val speedUnit = if (imperial) "mph" else "km/h"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { viewModel.updateBlock(index, block.copy(speedKph = (block.speedKph - speedStep).coerceAtLeast(.5f))) }) { Text("− 0.1 $speedUnit", maxLines = 1) }
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { viewModel.updateBlock(index, block.copy(speedKph = (block.speedKph + speedStep).coerceAtMost(30f))) }) { Text("＋ 0.1 $speedUnit", maxLines = 1) }
                    }
                    Text("Incline: ${"%.1f".format(block.inclinePercent)}%")
                    Slider(value = block.inclinePercent, onValueChange = { viewModel.updateBlock(index, block.copy(inclinePercent = kotlin.math.round(it * 2) / 2)) }, valueRange = minOf(device?.minIncline ?: -3f, block.inclinePercent)..maxOf(device?.maxIncline ?: 15f, block.inclinePercent))
                    Row(Modifier.toggleable(value = block.ramp && index > 0, enabled = index > 0, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { viewModel.updateBlock(index, block.copy(ramp = it)) }), verticalAlignment = Alignment.CenterVertically) { Switch(checked = block.ramp && index > 0, enabled = index > 0, onCheckedChange = null); Text(if (index == 0) "First block holds starting targets" else "Ramp across this block") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = index > 0, onClick = { viewModel.move(index, index - 1) }) { Text("← Earlier") }
                        OutlinedButton(enabled = index < draft.blocks.lastIndex, onClick = { viewModel.move(index, index + 1) }) { Text("Later →") }
                    }
                    TextButton(enabled = draft.blocks.size > 1, onClick = { viewModel.remove(index) }) { Text("Remove block") }
                }
            }
            draft.validationError(device)?.let { Text(it, color = colors.accentWarm) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                val easeSeconds = draft.windDownBlock()?.durationSeconds
                Text("${programTime(draft.totalSeconds)} ${if (draft.loop) "per loop · repeats until stopped" else "total${easeSeconds?.let { " + ${programTime(it)} easing to a stop" } ?: ""} · draft kept on this console"}", color = colors.textMedium, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = enteredDuration != null, onClick = viewModel::save, modifier = Modifier.padding(end = 12.dp)) { Text("Save program") }
                Button(enabled = startMode != null && device != null && enteredDuration != null && draft.validationError(device) == null, onClick = { requestStart(draft) }) { Text("Start session") }
            }
        }
    }
    }
    warmupProgram?.let { program ->
        AlertDialog(
            onDismissRequest = { warmupProgram = null },
            title = { Text("Start ${program.name} after your warm-up?") },
            text = { Text("Your warm-up stays in this workout recording. Keep walking at your current settings during the 3–2–1 count-in, then the program takes over speed and incline.") },
            confirmButton = { TextButton(enabled = startMode == ProgramStartMode.AFTER_WARMUP, onClick = { warmupProgram = null; viewModel.start(program, onStarted) }) { Text("Start program") } },
            dismissButton = { TextButton(onClick = { warmupProgram = null }) { Text("Keep walking") } },
        )
    }
}

/**
 * Moves every block at once — the whole workout a notch faster/slower or steeper/flatter. A step
 * is disabled when it would push any block past the treadmill's limits, so the profile keeps its
 * shape and stepping back always restores it.
 */
@Composable
private fun WholeProgramShift(
    draft: WorkoutProgram,
    imperial: Boolean,
    speedRange: ClosedFloatingPointRange<Float>,
    inclineRange: ClosedFloatingPointRange<Float>,
    onShift: (WorkoutProgram) -> Unit,
) {
    val colors = LocalHyperboreaColors.current
    // Steps are in the rider's display unit; blocks are stored in km/h.
    val toKph = if (imperial) 1f / .621371f else 1f
    @Composable
    fun StepButtons(what: String, steps: List<Float>, shifted: (Float) -> WorkoutProgram?) {
        for (step in steps) {
            val result = shifted(step)
            val label = "${if (step > 0) "＋" else "−"} ${"%.1f".format(kotlin.math.abs(step))}"
            OutlinedButton(
                enabled = result != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.semantics { contentDescription = "All blocks: $what $label" },
                onClick = { result?.let(onShift) },
            ) { Text(label, maxLines = 1) }
        }
    }
    // Scrolls sideways rather than wrapping on the narrower consoles.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("All blocks", style = MaterialTheme.typography.titleSmall)
        Text("Speed", color = colors.textMedium, modifier = Modifier.padding(start = 8.dp))
        StepButtons("speed", listOf(-.5f, -.1f, .1f, .5f)) { draft.shiftedBy(speedKph = it * toKph, speedRange = speedRange, inclineRange = inclineRange) }
        Text(if (imperial) "mph" else "km/h", color = colors.textMedium)
        Text("Incline", color = colors.textMedium, modifier = Modifier.padding(start = 16.dp))
        StepButtons("incline", listOf(-.5f, .5f)) { draft.shiftedBy(inclinePercent = it, speedRange = speedRange, inclineRange = inclineRange) }
        Text("%", color = colors.textMedium)
    }
}

@Composable
private fun BlockStrip(program: WorkoutProgram, selected: String, onSelect: (String) -> Unit, onMove: (Int, Int) -> Unit) {
    val latestMove by rememberUpdatedState(onMove)
    val latestProgram by rememberUpdatedState(program)
    val stride = with(LocalDensity.current) { 188.dp.toPx() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(program.blocks, key = { _, b -> b.id }) { _, block ->
            var drag by remember { mutableFloatStateOf(0f) }
            Surface(color = if (selected == block.id) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(180.dp).graphicsLayer { translationX = drag }, onClick = { onSelect(block.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).semantics { contentDescription = "Drag ${block.name} to reorder" }.pointerInput(block.id) {
                        detectDragGestures(onDragStart = { onSelect(block.id) }, onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, delta ->
                            change.consume(); drag += delta.x
                            val from = latestProgram.blocks.indexOfFirst { it.id == block.id }
                            val to = from + if (drag > 0) 1 else -1
                            if (kotlin.math.abs(drag) >= stride / 2 && to in latestProgram.blocks.indices) { latestMove(from, to); drag -= if (drag > 0) stride else -stride }
                        }
                    }, contentAlignment = Alignment.Center) { Text("⠿", style = MaterialTheme.typography.headlineSmall) }
                    Column(Modifier.padding(vertical = 12.dp)) { Text(block.name, maxLines = 1); Text(programTime(block.durationSeconds), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
