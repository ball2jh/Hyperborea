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
class ProgramSessionViewModel @Inject constructor(private val controller: ProgramController, private val store: ProgramStore, private val preferences: UserPreferences, @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context) : ViewModel() {
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
    val canResume by viewModel.canResume.collectAsStateWithLifecycle()
    val imperial by viewModel.imperial.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val run = state ?: return
    var expanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var name by remember(run.program.id) { mutableStateOf(run.program.name) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(programStatusText(run, imperial), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                run.countdown?.let { Text(it.toString(), style = MaterialTheme.typography.displayMedium, color = com.nettarion.hyperborea.ui.theme.LocalHyperboreaColors.current.accentWarm) }
                ProgramOverlayToggle(viewModel)
                if (run.status == ProgramStatus.MANUAL) Button(enabled = canResume, onClick = viewModel::resume) { Text("Resume program") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide graphs" else "Show graphs") }
                if (!run.active) OutlinedButton(onClick = { saving = true }) { Text("Save for next time") }
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
