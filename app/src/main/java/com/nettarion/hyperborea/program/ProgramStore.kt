package com.nettarion.hyperborea.program

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.program.ProgramBlock
import com.nettarion.hyperborea.core.program.WorkoutProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit, versioned local format; independent of the ride-history database. */
object ProgramCodec {
    fun encode(program: WorkoutProgram): String = JSONObject().apply {
        put("version", 1); put("id", program.id); put("name", program.name)
        put("loop", program.loop)
        put("blocks", JSONArray().apply { program.blocks.forEach { b -> put(JSONObject().apply {
            put("id", b.id); put("name", b.name); put("seconds", b.durationSeconds)
            put("speed", b.speedKph); put("incline", b.inclinePercent); put("ramp", b.ramp)
        }) } })
    }.toString()
    fun decode(json: String): WorkoutProgram {
        require(json.length <= 100_000)
        val o = JSONObject(json)
        require(o.getInt("version") == 1)
        val blocks = o.getJSONArray("blocks")
        require(blocks.length() in 1..100)
        return WorkoutProgram(o.getString("id"), o.getString("name"), (0 until blocks.length()).map { i ->
            val b = blocks.getJSONObject(i)
            ProgramBlock(b.getString("id"), b.getString("name"), b.getInt("seconds"), b.getDouble("speed").toFloat(), b.getDouble("incline").toFloat(), b.getBoolean("ramp"))
        }, loop = o.optBoolean("loop", false)).also { require(it.validationError() == null) }
    }
}

@Singleton
class ProgramStore @Inject constructor(@ApplicationContext context: Context, private val logger: AppLogger) {
    private val prefs = context.getSharedPreferences("workout_programs", Context.MODE_PRIVATE)
    private val _saved = MutableStateFlow(readSaved())
    val saved = _saved.asStateFlow()
    fun draft(): WorkoutProgram? = prefs.getString("draft", null)?.let { json -> runCatching { ProgramCodec.decode(json) }.getOrNull() }
    fun saveDraft(program: WorkoutProgram) { if (program.validationError() == null) prefs.edit().putString("draft", ProgramCodec.encode(program)).apply() }
    @Synchronized fun save(program: WorkoutProgram) {
        require(program.validationError() == null)
        val saved = if (program.id.startsWith("builtin-")) program.copy(id = UUID.randomUUID().toString()) else program
        val list = _saved.value.filterNot { it.id == saved.id } + saved
        require(list.size <= 100) { "The program library is full. Delete a program first." }
        val json = JSONArray().apply { list.forEach { put(ProgramCodec.encode(it)) } }.toString()
        // Save is user initiated: commit off the UI thread, report write errors instead of claiming success.
        check(prefs.edit().putString("saved", json).commit()) { "Couldn't save the program to this console." }
        _saved.value = list
    }
    @Synchronized fun delete(id: String) {
        val list = _saved.value.filterNot { it.id == id }
        check(prefs.edit().putString("saved", JSONArray().apply { list.forEach { put(ProgramCodec.encode(it)) } }.toString()).commit())
        _saved.value = list
    }
    private fun readSaved(): List<WorkoutProgram> = try {
        val array = JSONArray(prefs.getString("saved", "[]"))
        (0 until array.length()).mapNotNull { i -> runCatching { ProgramCodec.decode(array.getString(i)) }.onFailure { logger.w(TAG, "Skipping invalid saved program: ${it.message}") }.getOrNull() }
    } catch (e: Exception) { logger.e(TAG, "Couldn't read program library", e); emptyList() }
    private companion object { const val TAG = "Programs" }
}
