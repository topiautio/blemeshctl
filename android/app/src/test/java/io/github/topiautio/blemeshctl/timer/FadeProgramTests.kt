package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FadeProgramTests {
    @Test
    fun matchesPythonGoldenVectors() {
        assertTrue(FadeGoldenVectors.all.size >= 16)
        for (vector in FadeGoldenVectors.all) {
            assertEquals(vector.name, vector.text, FadeProgram.text(vector.color, vector.white, vector.from, vector.to, vector.overSeconds))
            assertEquals(vector.name, vector.description, FadeProgram.description(vector.color, vector.white, vector.from, vector.to, vector.overSeconds))
        }
    }

    @Test
    fun everyGeneratedProgramCompilesWithTheScriptParser() {
        for (vector in FadeGoldenVectors.all) {
            val program = BleMeshScript.parse(vector.text, vector.name)
            val commands = program.count { it is LightCommand }
            assertEquals(vector.name, vector.text.lines().count { it.startsWith("color") || it.startsWith("white") || it == "off" }, commands)
            assertEquals(vector.name, commands, TimerRunner.countCommands(program))
            // A fade that ends with off must be recognised as such so the runner retries it.
            assertEquals(vector.name, vector.to == null, TimerRunner.finalOffLine(program) != null)
        }
    }

    @Test
    fun defaultFadeStepsAreBoundedByLevelsAndTwoSeconds() {
        val text = FadeProgram.text("ff8800", null, to = null, overSeconds = 20)
        assertEquals(10, text.lines().count { it.startsWith("color") })
        val long = FadeProgram.text("ff8800", null, to = null, overSeconds = 1200)
        assertEquals(100, long.lines().count { it == "wait 12s" })
        assertEquals("off", long.trimEnd().lines().last())
    }

    @Test
    fun explainsThatLightsCannotReportState() {
        val message = errorOf { FadeProgram.text(null, null, 100, null, 60) }
        assertTrue(message, message.contains("cannot report their current state"))
        assertEquals(
            "fade needs --color RGB or --white [KELVIN]: lights cannot report their current state (issue #21), " +
                "so blemeshctl cannot know which colour to dim",
            message,
        )
    }

    @Test
    fun rejectsInvalidRequests() {
        assertEquals("choose either --color or --white for a fade, not both", errorOf { FadeProgram.text("ff0000", 2700, 100, null, 60) })
        assertEquals("--from brightness must be from 1 through 100", errorOf { FadeProgram.text("ff0000", null, 0, null, 60) })
        assertEquals("--from brightness must be from 1 through 100", errorOf { FadeProgram.text("ff0000", null, 101, null, 60) })
        assertEquals("--to brightness must be from 1 through 100", errorOf { FadeProgram.text("ff0000", null, 100, 0, 60) })
        assertEquals("--over must be at least 1s", errorOf { FadeProgram.text("ff0000", null, 100, null, 0) })
        errorOf { FadeProgram.text("zzzzzz", null, 100, null, 60) }
    }

    private fun errorOf(block: () -> Unit): String {
        try {
            block()
        } catch (exception: TimerException) {
            return exception.message!!
        }
        fail("expected TimerException")
        return ""
    }
}
