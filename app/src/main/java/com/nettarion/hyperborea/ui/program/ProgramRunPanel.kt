package com.nettarion.hyperborea.ui.program

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.program.ProgramStatus
import com.nettarion.hyperborea.program.ProgramController
import com.nettarion.hyperborea.program.ProgramStore
import com.nettarion.hyperborea.program.programStatusText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ProgramSessionViewModel @Inject constructor(private val controller: ProgramController, private val store: ProgramStore, private val preferences: UserPreferences, hardware: com.nettarion.hyperborea.core.adapter.HardwareAdapter, @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context) : ViewModel() {
    val data = hardware.exerciseData
    val state = controller.state
    val canResume = controller.canResumeProgram
    val imperial = preferences.useImperial
    val message = MutableStateFlow<String?>(null)
    val overlayEnabled = preferences.overlayEnabled
    val overlayGranted = MutableStateFlow(hasOverlayAccess())
    val overlayIssue = MutableStateFlow<String?>(null)
    private var awaitingOverlayGrant = false
    private fun hasOverlayAccess() = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)
    fun refreshOverlayAccess() {
        overlayGranted.value = hasOverlayAccess()
        if (awaitingOverlayGrant && overlayGranted.value) preferences.setOverlayEnabled(true)
        awaitingOverlayGrant = false
    }
    fun setOverlay(enabled: Boolean) {
        overlayIssue.value = null
        if (!enabled || hasOverlayAccess()) { preferences.setOverlayEnabled(enabled); return }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                awaitingOverlayGrant = true
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: android.content.ActivityNotFoundException) { overlayIssue.value = "Overlay access isn't available on this console"; awaitingOverlayGrant = false }
        }
    }
    fun resume() { viewModelScope.launch { controller.resumeProgram() } }
    fun save(name: String) {
        val program = state.value?.program?.copy(name = name) ?: return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.save(program) }; message.value = "Program saved to My programs" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Couldn't save program" }
        }
    }
}

@Composable
fun ProgramRunPanel(viewModel: ProgramSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val canResume by viewModel.canResume.collectAsStateWithLifecycle()
    val imperial by viewModel.imperial.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val run = state ?: return
    var expanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var name by remember(run.program.id) { mutableStateOf(run.program.name) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.active) {
                val presentation = com.nettarion.hyperborea.program.programOverlayPresentation(run, data, imperial)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(run.block.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("Block ${run.blockIndex + 1} of ${run.program.blocks.size}" + if (run.program.loop) " · Loop ${run.completedLoops + 1}" else "", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            run.program.blocks.forEachIndexed { index, block ->
                                val start = run.program.blocks.take(index).sumOf { it.durationSeconds } * 1000L
                                val progress = ((run.elapsedMillis - start).toFloat() / (block.durationSeconds * 1000L)).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(block.durationSeconds.toFloat()).height(5.dp),
                                    color = if (index == run.blockIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    trackColor = MaterialTheme.colorScheme.surface)
                            }
                        }
                    }
                    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProgramTargetRow("Speed", presentation.currentSpeed, presentation.targetSpeed, presentation.speedUnit, run.manualSpeed)
                        ProgramTargetRow("Incline", presentation.currentIncline, presentation.targetIncline, "%", run.manualIncline)
                    }
                    Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val blockRemaining = ((run.program.blocks.take(run.blockIndex + 1).sumOf { it.durationSeconds } * 1000L - run.elapsedMillis + 999) / 1000).toInt().coerceAtLeast(0)
                        Text(if (run.status == ProgramStatus.RUNNING && presentation.value == "Hold") blockRemaining.toString() else presentation.value, style = MaterialTheme.typography.headlineLarge,
                            color = if (presentation.warning) com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors.current.accentWarm else MaterialTheme.colorScheme.onSurface)
                        val nextBlock = run.program.blocks.getOrNull(run.blockIndex + 1) ?: run.program.blocks.first().takeIf { run.program.loop }
                        Text(if (run.status == ProgramStatus.RUNNING && nextBlock != null && !(run.block.ramp && run.blockIndex > 0 && !(run.manualSpeed && run.manualIncline) && run.countdown == null)) "seconds to ${nextBlock.name}" else presentation.caption,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgramOverlayToggle(viewModel)
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide graphs" else "Show graphs") }
                        }
                        if (run.status == ProgramStatus.MANUAL) {
                            Text(run.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 360.dp))
                            Button(enabled = canResume, onClick = viewModel::resume) { Text("Resume program") }
                        } else Text(when {
                            run.manualSpeed && run.manualIncline -> "Speed & incline manual until next block"
                            run.manualSpeed -> "Speed manual until next block"
                            run.manualIncline -> "Incline manual until next block"
                            run.status == ProgramStatus.PAUSED -> "Program paused"
                            else -> "Automatic targets"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(programStatusText(run, imperial), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { saving = true }) { Text("Save for next time") }
            }
            message?.let { Text(it) }
            if (expanded) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProgramGraph(run.program, true, imperial, Modifier.weight(1f).height(140.dp), elapsedMillis = run.elapsedMillis)
                ProgramGraph(run.program, false, imperial, Modifier.weight(1f).height(140.dp), elapsedMillis = run.elapsedMillis)
            }
        }
    }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("Save reusable program") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Program name") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { viewModel.save(name); saving = false }) { Text("Save program") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } })
}


@Composable
internal fun ProgramOverlayToggle(viewModel: ProgramSessionViewModel = hiltViewModel()) {
    val enabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val granted by viewModel.overlayGranted.collectAsStateWithLifecycle()
    val issue by viewModel.overlayIssue.collectAsStateWithLifecycle()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        viewModel.refreshOverlayAccess()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshOverlayAccess()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column {
        Row(Modifier.toggleable(value = enabled && granted, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = viewModel::setOverlay), verticalAlignment = Alignment.CenterVertically) {
            Text("Background widget", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled && granted, onCheckedChange = null,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (enabled && !granted) Text("Allow display over other apps to show the widget.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ProgramTargetRow(label: String, current: String, target: String, unit: String, manual: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(62.dp), style = MaterialTheme.typography.bodyMedium)
        Text("$current $unit", style = MaterialTheme.typography.titleMedium)
        Text("→ $target $unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (manual) Text("Manual", style = MaterialTheme.typography.labelSmall, color = com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors.current.accentWarm)
    }
}
