package com.nettarion.hyperborea.program

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.program.WorkoutProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What an import did, file by file, so the screen can say exactly which ones need fixing. */
data class ProgramImportResult(val imported: List<String>, val rejected: List<Rejected>) {
    data class Rejected(val fileName: String, val reason: String)
}

/**
 * File names for [programs], one each: the program name reduced to characters every filesystem
 * and shell handles, with a short id suffix only where two programs would otherwise collide.
 */
internal fun programFileNames(programs: List<WorkoutProgram>): Map<WorkoutProgram, String> {
    fun base(program: WorkoutProgram) =
        program.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(60).ifEmpty { "program" }
    val clashing = programs.groupBy { base(it).lowercase() }.filterValues { it.size > 1 }.keys
    return programs.associateWith { program ->
        val name = base(program)
        if (name.lowercase() in clashing) "$name-${program.id.take(8)}.json" else "$name.json"
    }
}

/** The id a file without one is imported under: stable per file name, so an edit replaces. */
internal fun programFileId(fileName: String): String =
    UUID.nameUUIDFromBytes("program-file:$fileName".toByteArray()).toString()

/**
 * Saved programs as plain JSON files, one per program, in the app's external files directory —
 * the one place both this app (on every supported Android version, with no storage permission)
 * and `adb push`/`adb pull` can reach. Lets a program be written or edited on a laptop.
 *
 * Both directions are explicit user actions: [export] writes the library out, [importAll] reads
 * every `.json` file back in, adding new programs and replacing ones with a matching id. Neither
 * deletes anything.
 */
@Singleton
class ProgramFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ProgramStore,
    private val logger: AppLogger,
) {
    /** Created on demand; null when the console has no external storage mounted. */
    fun directory(): File? = context.getExternalFilesDir(DIRECTORY)?.also { it.mkdirs() }

    /** Writes every saved program to [directory], returning how many files were written. */
    fun export(imperial: Boolean): Int {
        val dir = directory() ?: error("This console's shared storage isn't available.")
        val count = exportProgramFiles(dir, store.saved.value, imperial)
        logger.i(TAG, "Exported $count program file(s) to $dir")
        return count
    }

    /** Reads every `.json` file in [directory] into the library; unusable files are reported, not fatal. */
    fun importAll(): ProgramImportResult {
        val dir = directory() ?: error("This console's shared storage isn't available.")
        val (programs, rejected) = readProgramFiles(dir)
        rejected.forEach { logger.w(TAG, "Skipping program file ${it.fileName}: ${it.reason}") }
        store.saveAll(programs)
        logger.i(TAG, "Imported ${programs.size} program file(s) from $dir, rejected ${rejected.size}")
        return ProgramImportResult(programs.map { it.name }, rejected)
    }

    private companion object {
        const val TAG = "Programs"
        const val DIRECTORY = "programs"
    }
}

private const val MAX_FILE_BYTES = 100_000L

private fun programFilesIn(dir: File): List<File> =
    dir.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()

/** Writes one file per program into [dir], returning how many were written. */
internal fun exportProgramFiles(dir: File, programs: List<WorkoutProgram>, imperial: Boolean): Int {
    val names = programFileNames(programs)
    names.forEach { (program, name) -> File(dir, name).writeText(ProgramCodec.encodeFile(program, imperial)) }
    return names.size
}

/** Decodes every `.json` file in [dir]: the usable programs, and each unusable file with its reason. */
internal fun readProgramFiles(dir: File): Pair<List<WorkoutProgram>, List<ProgramImportResult.Rejected>> {
    val decoded = mutableListOf<Pair<File, WorkoutProgram>>()
    val rejected = mutableListOf<ProgramImportResult.Rejected>()
    for (file in programFilesIn(dir)) {
        try {
            require(file.length() <= MAX_FILE_BYTES) { "The file is too large to be a program." }
            decoded += file to ProgramCodec.decode(
                file.readText(),
                fallbackId = programFileId(file.name),
                fallbackName = file.name.dropLast(".json".length).take(60),
            )
        } catch (e: Exception) {
            rejected += ProgramImportResult.Rejected(file.name, e.message ?: "Couldn't read the file")
        }
    }
    // Files sharing an id — a copy that kept its "id", or a renamed program's earlier export —
    // would overwrite one another, and nothing here can tell which the user means. Import none
    // of them and say so, rather than guess or delete.
    val (unique, clashing) = decoded.groupBy { it.second.id }.values.partition { it.size == 1 }
    for (group in clashing) for ((file, _) in group) {
        val others = group.map { it.first.name }.filter { it != file.name }.joinToString(", ")
        rejected += ProgramImportResult.Rejected(file.name, "Same \"id\" as $others — delete the one you don't want, or remove its \"id\" line to import it as a new program.")
    }
    return unique.map { it.single().second } to rejected.sortedBy { it.fileName }
}
