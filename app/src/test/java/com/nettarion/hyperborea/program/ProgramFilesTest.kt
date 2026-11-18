package com.nettarion.hyperborea.program

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.program.WorkoutProgram
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgramFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `a renamed program's earlier export is never imported over the newer one`() {
        val program = WorkoutProgram(id = "p1", name = "A")
        exportProgramFiles(folder.root, listOf(program), imperial = true)
        exportProgramFiles(folder.root, listOf(program.copy(name = "Z")), imperial = true)
        // Export deletes nothing; A.json would have won an alphabetical tie-break.
        assertThat(folder.root.list()!!.sorted()).containsExactly("A.json", "Z.json")
        val (programs, rejected) = readProgramFiles(folder.root)
        assertThat(programs).isEmpty()
        assertThat(rejected.map { it.fileName }).containsExactly("A.json", "Z.json").inOrder()
        assertThat(rejected.first().reason).contains("Z.json")
    }

    @Test fun `files sharing an id are all reported while the rest still import`() {
        val json = ProgramCodec.encodeFile(WorkoutProgram(id = "p1", name = "Ramps"), imperial = false)
        File(folder.root, "ramps.json").writeText(json)
        File(folder.root, "ramps-harder.json").writeText(json.replace("Ramps", "Ramps harder"))
        File(folder.root, "hills.json").writeText("""{ "blocks": [ { "seconds": 60, "speed": 5 } ] }""")
        // Exporting the library must leave the user's own copy alone.
        exportProgramFiles(folder.root, listOf(WorkoutProgram(id = "p1", name = "Intervals")), imperial = false)
        assertThat(folder.root.list()!!.sorted()).containsExactly("Intervals.json", "hills.json", "ramps-harder.json", "ramps.json")
        File(folder.root, "Intervals.json").delete()
        val (programs, rejected) = readProgramFiles(folder.root)
        assertThat(programs.map { it.name }).containsExactly("hills")
        assertThat(rejected.map { it.fileName }).containsExactly("ramps-harder.json", "ramps.json")
        assertThat(rejected.first().reason).contains("ramps.json")
    }
}
