package io.github.topiautio.blemeshctl.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour-wheel position at full value: hue in degrees and saturation from 0 to 1. */
data class WheelHsv(val hueDegrees: Float, val saturation: Float)

data class WheelRgb(val red: Int, val green: Int, val blue: Int)

/** Geometry and colour conversion for a hue/saturation wheel. */
object ColorWheelMath {
    /** Convert a point relative to the wheel centre to a hue and saturation. */
    fun fromPoint(deltaX: Float, deltaY: Float, radius: Float): WheelHsv {
        require(radius > 0f) { "radius must be positive" }
        var hue = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
        if (hue < 0f) hue += 360f
        val saturation = (hypot(deltaX.toDouble(), deltaY.toDouble()) / radius).toFloat().coerceIn(0f, 1f)
        return WheelHsv(hue, saturation)
    }

    /** Return the marker offset from the wheel centre for [hsv]. */
    fun pointFor(hsv: WheelHsv, radius: Float): Pair<Float, Float> {
        require(radius >= 0f) { "radius must not be negative" }
        val angle = Math.toRadians(normalizeHue(hsv.hueDegrees).toDouble())
        val distance = hsv.saturation.coerceIn(0f, 1f) * radius
        return Pair((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat())
    }

    fun hsvToRgb(hsv: WheelHsv): WheelRgb {
        val hue = normalizeHue(hsv.hueDegrees)
        val saturation = hsv.saturation.coerceIn(0f, 1f)
        val section = hue / 60f
        val secondary = (1f - abs(section % 2f - 1f)) * saturation
        val (red, green, blue) = when (section.toInt()) {
            0 -> Triple(saturation, secondary, 0f)
            1 -> Triple(secondary, saturation, 0f)
            2 -> Triple(0f, saturation, secondary)
            3 -> Triple(0f, secondary, saturation)
            4 -> Triple(secondary, 0f, saturation)
            else -> Triple(saturation, 0f, secondary)
        }
        val match = 1f - saturation
        return WheelRgb(
            ((red + match) * 255f).roundToInt().coerceIn(0, 255),
            ((green + match) * 255f).roundToInt().coerceIn(0, 255),
            ((blue + match) * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    fun rgbToHsv(red: Int, green: Int, blue: Int): WheelHsv {
        val normalizedRed = red.coerceIn(0, 255) / 255f
        val normalizedGreen = green.coerceIn(0, 255) / 255f
        val normalizedBlue = blue.coerceIn(0, 255) / 255f
        val maximum = max(normalizedRed, max(normalizedGreen, normalizedBlue))
        val minimum = min(normalizedRed, min(normalizedGreen, normalizedBlue))
        val delta = maximum - minimum
        val hue = when {
            delta == 0f -> 0f
            maximum == normalizedRed -> ((normalizedGreen - normalizedBlue) / delta % 6f) * 60f
            maximum == normalizedGreen -> ((normalizedBlue - normalizedRed) / delta + 2f) * 60f
            else -> ((normalizedRed - normalizedGreen) / delta + 4f) * 60f
        }
        val saturation = if (maximum == 0f) 0f else delta / maximum
        return WheelHsv(normalizeHue(hue), saturation)
    }

    private fun normalizeHue(hueDegrees: Float): Float = (hueDegrees % 360f + 360f) % 360f
}
