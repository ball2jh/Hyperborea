package com.nettarion.hyperborea.ui.program

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.HyperboreaService
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.profile.UserPreferences
import com.nettarion.hyperborea.core.program.*
import com.nettarion.hyperborea.program.ProgramCodec
import com.nettarion.hyperborea.program.ProgramStore
import com.nettarion.hyperborea.startHyperboreaService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProgramsViewModel @Inject constructor(
    private val store: ProgramStore,
    hardware: HardwareAdapter,
    orchestrator: Orchestrator,
    controller: com.nettarion.hyperborea.program.ProgramController,
    preferences: UserPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val saved = store.saved
    val device = hardware.deviceInfo
    val workoutState = orchestrator.state
    val programState = controller.state
    val imperial = preferences.useImperial
    val draft = MutableStateFlow(store.draft() ?: WorkoutProgram())
    val selectedId = MutableStateFlow(draft.value.blocks.first().id)
    val hasDraft = MutableStateFlow(store.draft() != null)
    val editing = MutableStateFlow(false)
    val savedTab = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    fun edit(program: WorkoutProgram) { draft.value = program; selectedId.value = program.blocks.first().id; editing.value = true; message.value = null }
    fun custom() { val program = WorkoutProgram(); update(program); edit(program) }
    fun continueDraft() = edit(store.draft() ?: WorkoutProgram())
    fun update(program: WorkoutProgram) { draft.value = program; store.saveDraft(program); hasDraft.value = true }
    fun updateBlock(index: Int, block: ProgramBlock) { update(draft.value.copy(blocks = draft.value.blocks.mapIndexed { i, b -> if (i == index) block else b })) }
    fun move(from: Int, to: Int) { update(draft.value.moveBlock(from, to)) }
    fun add(duplicate: Boolean) {
        if (draft.value.blocks.size >= 100) return
        val index = draft.value.blocks.indexOfFirst { it.id == selectedId.value }.coerceAtLeast(0)
        val block = if (duplicate) draft.value.blocks[index].copy(id = UUID.randomUUID().toString()) else ProgramBlock(name = "New block")
        val blocks = draft.value.blocks.toMutableList().apply { add(index + 1, block) }
        update(draft.value.copy(blocks = blocks)); selectedId.value = block.id
    }
    fun remove(index: Int) {
        if (draft.value.blocks.size == 1) return
        val blocks = draft.value.blocks.toMutableList().apply { removeAt(index) }
        update(draft.value.copy(blocks = blocks)); selectedId.value = blocks[index.coerceAtMost(blocks.lastIndex)].id
    }
    fun save() {
        val program = draft.value
        program.validationError()?.let { message.value = it; return }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.save(program) }; editing.value = false; savedTab.value = true; message.value = "Program saved" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Couldn't save program" }
        }
    }
    fun delete(program: WorkoutProgram) = viewModelScope.launch(Dispatchers.IO) {
        try { store.delete(program.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message.value = "Couldn't delete program" }
    }
    fun start(program: WorkoutProgram, onStarted: () -> Unit) {
        if (com.nettarion.hyperborea.program.programStartMode(workoutState.value, programState.value?.active == true) == null) {
            message.value = "The workout changed. Resume the manual workout, or stop the current program first."
            return
        }
        val info = device.value
        if (info == null) { message.value = "Waiting for treadmill identification"; return }
        program.validationError(info)?.let { message.value = it; return }
        store.saveDraft(program)
        context.startHyperboreaService(HyperboreaService.ACTION_START_PROGRAM, ProgramCodec.encode(program))
        onStarted()
    }
}
