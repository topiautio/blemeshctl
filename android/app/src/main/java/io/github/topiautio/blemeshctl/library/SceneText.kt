package io.github.topiautio.blemeshctl.library

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException

/** The light state that the app's normal controls can express. */
sealed interface SceneControls {
    data object On : SceneControls

    data object Off : SceneControls

    data class Colour(val red: Int, val green: Int, val blue: Int, val brightness: Int) : SceneControls

    data class White(val kelvin: Int, val brightness: Int) : SceneControls
}

/** Turns the current controls into scene text in the ordinary `.blemesh` syntax. */
object SceneText {
    /**
     * Return a small script that recreates [controls].
     *
     * Colour and white scenes switch the light on first, so recalling them also
     * works on a light that was off. Values are checked with the same rules as
     * the protocol encoder, so the text always parses.
     */
    fun fromControls(controls: SceneControls): String {
        try {
            return when (controls) {
                SceneControls.On -> "on\n"
                SceneControls.Off -> "off\n"
                is SceneControls.Colour -> {
                    TelinkProtocol.rgbParameters(controls.red, controls.green, controls.blue, controls.brightness)
                    "on\ncolor ${hex(controls.red)}${hex(controls.green)}${hex(controls.blue)} ${controls.brightness}\n"
                }
                is SceneControls.White -> {
                    TelinkProtocol.whiteParameters(controls.kelvin, controls.brightness)
                    "on\nwhite ${controls.kelvin}K ${controls.brightness}\n"
                }
            }
        } catch (exception: TelinkProtocolException) {
            throw LibraryException(exception.message ?: "invalid light controls")
        }
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')
}
