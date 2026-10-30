package io.github.topiautio.blemeshctl.color

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorWheelMathTests {
    @Test
    fun edgeOfWheelIsFullySaturated() {
        assertEquals(WheelRgb(255, 0, 0), ColorWheelMath.hsvToRgb(ColorWheelMath.fromPoint(10f, 0f, 10f)))
        assertEquals(WheelRgb(0, 255, 0), ColorWheelMath.hsvToRgb(WheelHsv(120f, 1f)))
        assertEquals(WheelRgb(0, 0, 255), ColorWheelMath.hsvToRgb(WheelHsv(240f, 1f)))
    }

    @Test
    fun centreIsWhite() {
        assertEquals(WheelRgb(255, 255, 255), ColorWheelMath.hsvToRgb(ColorWheelMath.fromPoint(0f, 0f, 10f)))
    }

    @Test
    fun pointsBeyondTheEdgeClampToFullSaturation() {
        assertEquals(1f, ColorWheelMath.fromPoint(0f, 50f, 10f).saturation)
        assertEquals(90f, ColorWheelMath.fromPoint(0f, 50f, 10f).hueDegrees)
    }

    @Test
    fun rgbRoundTripsThroughTheWheel() {
        for (rgb in listOf(WheelRgb(255, 0, 0), WheelRgb(0, 255, 0), WheelRgb(255, 128, 128), WheelRgb(255, 0, 255))) {
            val hsv = ColorWheelMath.rgbToHsv(rgb.red, rgb.green, rgb.blue)
            assertEquals(rgb, ColorWheelMath.hsvToRgb(hsv))
        }
    }

    @Test
    fun markerPositionMatchesSelection() {
        val (x, y) = ColorWheelMath.pointFor(WheelHsv(90f, 0.5f), 100f)

        assertEquals(0f, x, 0.001f)
        assertEquals(50f, y, 0.001f)
    }
}
