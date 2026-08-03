package com.nettarion.hyperborea.overlay

import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.orchestration.OrchestratorState

/**
 * Contract shared by the overlay bar variants ([OverlayBarView], [OverlayControlBarView]) so
 * [OverlayManager] can drive whichever style the user selected without knowing the concrete view.
 */
internal interface OverlayContentView {
    fun updateExerciseData(data: ExerciseData?)
    fun updateState(state: OrchestratorState)
}

/**
 * Colors shared by the overlay views — kept as raw ints (not Compose colors) because the overlay
 * is classic-View based; values mirror ui/theme/Color.kt.
 */
internal object OverlayPalette {
    const val SURFACE = 0xFF0F1115.toInt()
    const val TEXT_HIGH = 0xFFF0F2F5.toInt()
    const val TEXT_MEDIUM = 0xFF7A8290.toInt()
    const val TEXT_LOW = 0xFF3D4350.toInt()
    const val DIVIDER = 0xFF1A1D24.toInt()
    const val ELECTRIC_BLUE = 0xFF3B82F6.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val STATUS_ACTIVE = 0xFF22C55E.toInt()
    const val STATUS_ERROR = 0xFFEF4444.toInt()
}
