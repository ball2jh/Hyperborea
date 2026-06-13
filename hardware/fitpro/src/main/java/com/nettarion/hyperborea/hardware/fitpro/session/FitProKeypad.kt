package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.model.ConsoleKey

/**
 * The FitPro console keypad code space — identical on V1 (the KEY_OBJECT field) and V2 (the
 * KEY_COOKED feature); the membrane-keypad firmware predates the protocol split. Both sessions
 * edge-detect the *currently-pressed* code themselves (0 = no key) and translate fresh presses
 * here. What a press *does* differs by protocol: V1 MCUs act on their own keys, V2 forwards them
 * for the host to act on — see [com.nettarion.hyperborea.core.model.ConsoleKey].
 */
object FitProKeypad {

    fun consoleKeyFromCode(code: Int): ConsoleKey? = when (code) {
        KEY_START -> ConsoleKey.START
        KEY_STOP -> ConsoleKey.STOP
        KEY_SPEED_UP -> ConsoleKey.SPEED_UP
        KEY_SPEED_DOWN -> ConsoleKey.SPEED_DOWN
        KEY_INCLINE_UP -> ConsoleKey.INCLINE_UP
        KEY_INCLINE_DOWN -> ConsoleKey.INCLINE_DOWN
        // GEAR_UP/DOWN map to resistance — on bike consoles the +/- buttons are the resistance/gear
        // selector and there's no separate "gear" the app tracks.
        KEY_RESISTANCE_UP, KEY_GEAR_UP -> ConsoleKey.RESISTANCE_UP
        KEY_RESISTANCE_DOWN, KEY_GEAR_DOWN -> ConsoleKey.RESISTANCE_DOWN
        else -> null // fan / volume / etc. — not mapped (yet)
    }

    private const val KEY_STOP = 1
    private const val KEY_START = 2
    private const val KEY_SPEED_UP = 3
    private const val KEY_SPEED_DOWN = 4
    private const val KEY_INCLINE_UP = 5
    private const val KEY_INCLINE_DOWN = 6
    private const val KEY_RESISTANCE_UP = 7
    private const val KEY_RESISTANCE_DOWN = 8
    private const val KEY_GEAR_UP = 9
    private const val KEY_GEAR_DOWN = 10
}
