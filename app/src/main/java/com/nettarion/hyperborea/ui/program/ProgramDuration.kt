package com.nettarion.hyperborea.ui.program

/** Blank fields represent zero while editing; incomplete/out-of-range totals cannot be saved. */
internal fun parseProgramDuration(minutes: String, seconds: String): Int? {
    val min = minutes.ifBlank { "0" }.toIntOrNull() ?: return null
    val sec = seconds.ifBlank { "0" }.toIntOrNull() ?: return null
    if (min !in 0..60 || sec !in 0..59) return null
    return (min * 60 + sec).takeIf { it in 10..3600 }
}
