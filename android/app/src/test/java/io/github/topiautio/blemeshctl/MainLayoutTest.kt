package io.github.topiautio.blemeshctl

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MainLayoutTests {
    @Test
    fun scriptEditorAcceptsLineBreaks() {
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_main.xml"))
        val editTexts = layout.getElementsByTagName("EditText")
        val editors = (0 until editTexts.length)
            .map { editTexts.item(it) as Element }
            .filter { it.getAttribute("android:id") == "@+id/scriptEditor" }
        assertEquals(1, editors.size)
        // Without textMultiLine the editor shows a script on one line and Enter adds no line break.
        assertTrue(editors.single().getAttribute("android:inputType").split('|').contains("textMultiLine"))
    }
}
