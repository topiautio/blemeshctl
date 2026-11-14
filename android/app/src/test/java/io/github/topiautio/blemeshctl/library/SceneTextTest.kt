package io.github.topiautio.blemeshctl.library

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneTextTests {
    /** Parse generated text with the real parser and return each command's opcode and payload. */
    private fun commands(text: String): List<Pair<Int, List<Byte>>> =
        BleMeshScript.parse(text, "generated.blemesh").map { step ->
            val command = step as LightCommand
            command.opcode to command.parameters.toList()
        }

    private val on = TelinkProtocol.COMMAND_ON_OFF to TelinkProtocol.onParameters().toList()

    @Test
    fun onAndOffScenesAreSingleCommands() {
        assertEquals("on\n", SceneText.fromControls(SceneControls.On))
        assertEquals("off\n", SceneText.fromControls(SceneControls.Off))
        assertEquals(listOf(on), commands(SceneText.fromControls(SceneControls.On)))
        assertEquals(
            listOf(TelinkProtocol.COMMAND_ON_OFF to TelinkProtocol.offParameters().toList()),
            commands(SceneText.fromControls(SceneControls.Off)),
        )
    }

    @Test
    fun colourScenesRoundTripThroughTheParser() {
        for (controls in listOf(
            SceneControls.Colour(0x22, 0x00, 0xff, 30),
            SceneControls.Colour(0, 0, 0, 1),
            SceneControls.Colour(255, 255, 255, 100),
        )) {
            val text = SceneText.fromControls(controls)
            assertEquals(
                text,
                listOf(
                    on,
                    TelinkProtocol.COMMAND_RGB to
                        TelinkProtocol.rgbParameters(controls.red, controls.green, controls.blue, controls.brightness).toList(),
                ),
                commands(text),
            )
        }
        assertEquals("on\ncolor 2200ff 30\n", SceneText.fromControls(SceneControls.Colour(0x22, 0x00, 0xff, 30)))
    }

    @Test
    fun whiteScenesRoundTripThroughTheParser() {
        for (controls in listOf(
            SceneControls.White(TelinkProtocol.WHITE_NEUTRAL_KELVIN, 75),
            SceneControls.White(TelinkProtocol.WHITE_WARMEST_KELVIN, 1),
            SceneControls.White(TelinkProtocol.WHITE_COOLEST_KELVIN, 100),
        )) {
            val text = SceneText.fromControls(controls)
            assertEquals(
                text,
                listOf(
                    on,
                    TelinkProtocol.COMMAND_WHITE to TelinkProtocol.whiteParameters(controls.kelvin, controls.brightness).toList(),
                ),
                commands(text),
            )
        }
        assertEquals("on\nwhite 4600K 75\n", SceneText.fromControls(SceneControls.White(4600, 75)))
    }

    @Test
    fun generatedScenesCanBeSaved() {
        val library = LightLibrary(InMemoryLibraryStorage())

        library.saveScene("evening", SceneText.fromControls(SceneControls.White(2700, 40)))

        assertEquals(2, library.program(library.scene("evening")!!).size)
    }

    @Test
    fun rejectsControlsOutsideTheProtocolRange() {
        val cases = listOf(
            SceneControls.Colour(256, 0, 0, 50),
            SceneControls.Colour(0, -1, 0, 50),
            SceneControls.Colour(0, 0, 0, 0),
            SceneControls.White(2699, 50),
            SceneControls.White(4600, 101),
        )
        for (controls in cases) {
            assertThrows(controls.toString(), LibraryException::class.java) { SceneText.fromControls(controls) }
        }
    }
}
