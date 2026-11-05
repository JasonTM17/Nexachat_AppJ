package com.nexachat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression test enforcing 100% string key parity between
 * res/values/strings.xml (English) and res/values-vi/strings.xml (Vietnamese).
 */
class StringParityTest {

    @Test
    fun verifyEnglishAndVietnameseStringsHaveExactParity() {
        val rootDir = File(".").absoluteFile
        // Search upward if needed to locate res directory
        val resDir = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("android/app/src/main/res"),
        ).firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("Cannot find res directory from ${rootDir.path}")

        val enFile = File(resDir, "values/strings.xml")
        val viFile = File(resDir, "values-vi/strings.xml")

        assertTrue("English strings.xml should exist", enFile.exists())
        assertTrue("Vietnamese strings.xml should exist", viFile.exists())

        val enKeys = extractStringKeys(enFile)
        val viKeys = extractStringKeys(viFile)

        val missingInVi = enKeys - viKeys
        val extraInVi = viKeys - enKeys

        assertTrue("Keys present in EN but missing in VI: $missingInVi", missingInVi.isEmpty())
        assertTrue("Keys present in VI but missing in EN: $extraInVi", extraInVi.isEmpty())
        assertEquals("String count should match exactly", enKeys.size, viKeys.size)
    }

    private fun extractStringKeys(file: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val nodes = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name.isNotBlank()) {
                keys.add(name)
            }
        }
        return keys
    }
}
