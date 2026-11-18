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

/**
 * Explicit, versioned local format; independent of the ride-history database.
 *
 * The same JSON is what a program *file* holds (see [ProgramFiles]), so [decode] is forgiving
 * about everything a person writing one by hand would rather leave out: `version`, the program and
 * block ids, block names, `incline` and `ramp` are optional, and `speedUnit` may be `"mph"`.
 * Whatever it accepts still has to pass [WorkoutProgram.validationError].
 */
object ProgramCodec {
    private const val MPH_PER_KPH = 0.621371

    fun encode(program: WorkoutProgram): String = JSONObject().apply {
        put("version", 1); put("id", program.id); put("name", program.name)
        put("loop", program.loop)
        put("easeToStop", program.easeToStop)
        put("blocks", JSONArray().apply { program.blocks.forEach { b -> put(JSONObject().apply {
            put("id", b.id); put("name", b.name); put("seconds", b.durationSeconds)
            put("speed", b.speedKph); put("incline", b.inclinePercent); put("ramp", b.ramp)
        }) } })
    }.toString()

    /**
     * The hand-editable form written to a program file: indented, speeds in the rider's own unit
     * (declared in `speedUnit`), and no block ids — copying a block in a text editor must not
     * produce two blocks with the same identity.
     */
    fun encodeFile(program: WorkoutProgram, imperial: Boolean): String = JSONObject().apply {
        fun rounded(value: Double) = Math.round(value * 1000.0) / 1000.0
        put("version", 1); put("id", program.id); put("name", program.name)
        put("speedUnit", if (imperial) "mph" else "kph")
        put("loop", program.loop)
        put("easeToStop", program.easeToStop)
        put("blocks", JSONArray().apply { program.blocks.forEach { b -> put(JSONObject().apply {
            put("name", b.name); put("seconds", b.durationSeconds)
            put("speed", rounded(b.speedKph.toDouble() * if (imperial) MPH_PER_KPH else 1.0))
            put("incline", rounded(b.inclinePercent.toDouble())); put("ramp", b.ramp)
        }) } })
    }.toString(2)

    /**
     * @param fallbackId used when the JSON carries no `id` — a file's stable identity, so
     *   re-importing an edited file replaces its program instead of adding a copy.
     * @param fallbackName used when the JSON carries no `name`.
     * @throws IllegalArgumentException with a readable reason when the program can't be used.
     */
    fun decode(json: String, fallbackId: String? = null, fallbackName: String? = null): WorkoutProgram {
        require(json.length <= 100_000) { "The file is too large to be a program." }
        val o = try { JSONObject(json) } catch (e: org.json.JSONException) { throw IllegalArgumentException("Not valid JSON: ${e.message}") }
        try {
            require(o.optInt("version", 1) == 1) { "Unsupported program version ${o.opt("version")}." }
            val toKph = when (val unit = o.optString("speedUnit", "kph").lowercase()) {
                "kph", "km/h", "kmh" -> 1.0
                "mph" -> 1.0 / MPH_PER_KPH
                else -> throw IllegalArgumentException("Unknown speedUnit \"$unit\" — use \"kph\" or \"mph\".")
            }
            val blocks = o.getJSONArray("blocks")
            require(blocks.length() in 1..100) { "A program needs between 1 and 100 blocks." }
            return WorkoutProgram(
                id = o.optString("id").ifBlank { fallbackId ?: UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { fallbackName ?: "" },
                blocks = (0 until blocks.length()).map { i ->
                    val b = blocks.getJSONObject(i)
                    ProgramBlock(
                        id = b.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = b.optString("name").ifBlank { "Block ${i + 1}" },
                        durationSeconds = b.getInt("seconds"),
                        speedKph = (b.getDouble("speed") * toKph).toFloat(),
                        inclinePercent = b.optDouble("incline", 0.0).toFloat(),
                        ramp = b.optBoolean("ramp", false),
                    )
                },
                loop = o.optBoolean("loop", false),
                easeToStop = o.optBoolean("easeToStop", true),
            ).also { program -> program.validationError()?.let { throw IllegalArgumentException(it) } }
        } catch (e: org.json.JSONException) {
            // Missing or mistyped fields: org.json's message names the offending key.
            throw IllegalArgumentException(e.message ?: "Malformed program")
        }
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
    /** Adds or replaces (by id) several programs with a single write — a file import. */
    @Synchronized fun saveAll(programs: List<WorkoutProgram>) {
        if (programs.isEmpty()) return
        require(programs.all { it.validationError() == null })
        val incoming = programs.map { if (it.id.startsWith("builtin-")) it.copy(id = UUID.randomUUID().toString()) else it }.associateBy { it.id }
        val list = _saved.value.filterNot { it.id in incoming } + incoming.values
        require(list.size <= 100) { "The program library is full. Delete a program first." }
        val json = JSONArray().apply { list.forEach { put(ProgramCodec.encode(it)) } }.toString()
        check(prefs.edit().putString("saved", json).commit()) { "Couldn't save the programs to this console." }
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
