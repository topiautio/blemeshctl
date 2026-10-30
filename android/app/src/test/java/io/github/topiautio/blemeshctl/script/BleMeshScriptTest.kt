package io.github.topiautio.blemeshctl.script

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Unit tests run from the app module directory.
private val appAssets = File("src/main/assets")
private val cliExamples = File("../../examples")

class ScriptParserTests {
    @Test
    fun bundledAndCliRgbCycleAreValidEndlessLoops() {
        for (file in listOf(File(appAssets, "rgb-cycle.blemesh"), File(cliExamples, "rgb-cycle.blemesh"))) {
            val program = BleMeshScript.parse(file.readText(), file.name)

            assertEquals(2, program.size)
            assertArrayEquals(TelinkProtocol.onParameters(), (program[0] as LightCommand).parameters)
            val loop = program[1] as LoopStep
            assertNull(loop.count)
            assertEquals(
                listOf(
                    TelinkProtocol.rgbParameters(255, 0, 0, 100).toList(),
                    TelinkProtocol.rgbParameters(0, 255, 0, 100).toList(),
                    TelinkProtocol.rgbParameters(0, 0, 255, 100).toList(),
                ),
                loop.body.filterIsInstance<LightCommand>().map { it.parameters.toList() },
            )
            assertEquals(listOf(1000L, 1000L, 1000L), loop.body.filterIsInstance<WaitStep>().map { it.milliseconds })
        }
    }

    @Test
    fun bundledAndCliRainbowAreSaturatedAndContainNoWhite() {
        for (file in listOf(File(appAssets, "rainbow.blemesh"), File(cliExamples, "rainbow.blemesh"))) {
            val program = BleMeshScript.parse(file.readText(), file.name)

            assertEquals(2, program.size)
            val loop = program[1] as LoopStep
            assertEquals(1, loop.body.size)
            assertEquals(100L, (loop.body.single() as RainbowStep).milliseconds)
        }

        val colors = BleMeshScript.rainbowColors
        assertEquals(1530, colors.size)
        assertEquals(1530, colors.toSet().size)
        assertEquals(Triple(255, 0, 0), colors.first())
        assertEquals(Triple(255, 0, 1), colors.last())
        assertTrue(colors.all { (red, green, blue) -> maxOf(red, green, blue) == 255 && minOf(red, green, blue) == 0 })
        assertFalse(Triple(255, 255, 255) in colors)
    }

    @Test
    fun acceptsCommentsCrlfHashColoursAndFiniteRepeat() {
        val program = BleMeshScript.parse(
            "# A comment\r\non\r\nrepeat 2:\r\n  color #00ff00 65\r\n  wait 250ms",
            "scene.blemesh",
        )

        assertEquals(2, program.size)
        val repeat = program[1] as LoopStep
        assertEquals(2, repeat.count)
        assertArrayEquals(TelinkProtocol.rgbParameters(0, 255, 0, 65), (repeat.body[0] as LightCommand).parameters)
        assertEquals(WaitStep(5, 250), repeat.body[1])
    }

    @Test
    fun roundsFractionalMillisecondsUp() {
        val program = BleMeshScript.parse("on\nwait 0.0001s\nwait 1.5ms\nwait 2m", "durations.blemesh")

        assertEquals(listOf(1L, 2L, 120_000L), program.filterIsInstance<WaitStep>().map { it.milliseconds })
    }

    @Test
    fun rejectsInvalidScriptsWithASourceLine() {
        val cases = mapOf(
            "color ff0000\nunknown" to "unknown instruction",
            "color ff0000\n  wait 1s" to "unexpected indentation",
            "loop:\n  color ff0000" to "endless loop needs at least one wait",
            "repeat 0:\n  color ff0000" to "repeat count must be a positive integer",
            "color gg0000" to "only hexadecimal digits",
            "wait 0" to "wait must be a positive, finite duration",
            "rainbow 0" to "wait must be a positive, finite duration",
            "on\n\tcolor ff0000" to "tabs are not supported",
            "color ff0000 101" to "brightness must be from 1 through 100",
            "loop\n  on" to "loop blocks must be written as 'loop:'",
            "wait 1s" to "script contains no light commands",
            "# only a comment" to "script contains no instructions",
        )
        for ((text, expected) in cases) {
            val error = assertThrows(ScriptException::class.java) { BleMeshScript.parse(text, "bad.blemesh") }
            val message = error.message.orEmpty()
            assertTrue("'$message' should name the source", Regex("^bad\\.blemesh(:\\d+)?: ").containsMatchIn(message))
            assertTrue("'$message' should contain '$expected'", expected in message)
        }
    }
}

class ScriptExecutorTests {
    @Test
    fun rainbowVisitsEverySaturatedHue() = runTest {
        val program = BleMeshScript.parse("rainbow 100ms", "rainbow.blemesh")
        val sent = mutableListOf<LightCommand>()
        val waited = mutableListOf<WaitStep>()

        BleMeshScript.execute(program, sendCommand = { sent += it }, wait = { waited += it })

        assertEquals(1530, sent.size)
        assertEquals(
            BleMeshScript.rainbowColors.map { (red, green, blue) ->
                TelinkProtocol.rgbParameters(red, green, blue, 100).toList()
            },
            sent.map { it.parameters.toList() },
        )
        assertEquals("rainbow colour rgb #ff0000", sent.first().description)
        assertTrue(sent.first().report)
        assertTrue(sent.drop(1).none { it.report })
        assertEquals(List(1530) { WaitStep(1, 100, report = false) }, waited)
    }

    @Test
    fun finiteRepeatRunsInOrder() = runTest {
        val program = BleMeshScript.parse("repeat 2:\n  color ff0000\n  wait 0.25\n  color 0000ff", "repeat.blemesh")
        val sent = mutableListOf<List<Byte>>()
        val waited = mutableListOf<Long>()

        BleMeshScript.execute(
            program,
            sendCommand = { sent += it.parameters.toList() },
            wait = { waited += it.milliseconds },
        )

        val red = TelinkProtocol.rgbParameters(255, 0, 0, 100).toList()
        val blue = TelinkProtocol.rgbParameters(0, 0, 255, 100).toList()
        assertEquals(listOf(red, blue, red, blue), sent)
        assertEquals(listOf(250L, 250L), waited)
    }

    @Test
    fun doesNotRetryAfterASendFailure() = runTest {
        val program = BleMeshScript.parse("color ff0000\nwait 1s\ncolor 0000ff", "failure.blemesh")
        val sent = mutableListOf<List<Byte>>()

        val error = runCatching {
            BleMeshScript.execute(
                program,
                sendCommand = {
                    sent += it.parameters.toList()
                    throw IllegalStateException("connection dropped")
                },
                wait = { fail("unexpected wait: $it") },
            )
        }.exceptionOrNull()

        assertEquals("connection dropped", error?.message)
        assertEquals(listOf(TelinkProtocol.rgbParameters(255, 0, 0, 100).toList()), sent)
    }
}
