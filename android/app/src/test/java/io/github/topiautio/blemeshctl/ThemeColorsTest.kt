package io.github.topiautio.blemeshctl

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

private val resources = File("src/main/res")

class ThemeColorsTests {
    private val day = colors("values")
    private val night = colors("values-night")
    private val black = colors("values", "colors_black.xml")

    @Test
    fun nightModeDefinesEveryColour() {
        // A colour missing from values-night silently stays light in dark mode.
        assertEquals(day.keys, night.keys)
    }

    @Test
    fun layoutsUseNamedColours() {
        val layouts = File(resources, "layout").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            val elements = parse(layout).getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val attributes = elements.item(index).attributes
                for (attribute in 0 until attributes.length) {
                    val node = attributes.item(attribute)
                    assertTrue(
                        "${layout.name}: ${node.nodeName}=\"${node.nodeValue}\" ignores dark mode",
                        !node.nodeValue.startsWith("#"),
                    )
                }
            }
        }
    }

    @Test
    fun textIsReadableInEveryTheme() {
        // Theme.BleMeshCtl.PureBlack keeps the night text colours on black backgrounds.
        val pureBlack = night + mapOf(
            "window_background" to black.getValue("black_background"),
            "status_background" to black.getValue("black_surface"),
            "editor_background" to black.getValue("black_surface"),
        )
        for ((mode, palette) in listOf("day" to day, "night" to night, "pure black" to pureBlack)) {
            for (text in listOf("text_primary", "text_secondary", "warning_text", "accent")) {
                for (background in listOf("window_background", "status_background", "editor_background")) {
                    val ratio = contrast(palette.getValue(text), palette.getValue(background))
                    assertTrue("$mode $text on $background is only $ratio:1", ratio >= 4.5)
                }
            }
        }
    }

    private fun colors(directory: String, file: String = "colors.xml"): Map<String, Int> {
        val elements = parse(File(resources, "$directory/$file")).getElementsByTagName("color")
        return (0 until elements.length).associate { index ->
            val element = elements.item(index) as Element
            element.getAttribute("name") to element.textContent.trim().removePrefix("#").toInt(16)
        }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    /** WCAG 2 contrast ratio between two opaque RGB colours. */
    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = (rgb shr shift and 0xff) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
