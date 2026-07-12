package space.linuxct.teleforward.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriHarvestTest {

    // --- positive ------------------------------------------------------------------------------

    @Test
    fun findsHttpUrlEmbeddedInText() {
        val out = UriHarvest.harvest(listOf("Watch here: https://youtu.be/dQw4w9WgXcQ now!"))
        assertTrue(out.contains("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun trimsTrailingPunctuation() {
        val out = UriHarvest.harvest(listOf("see (https://example.com/a)."))
        assertTrue(out.contains("https://example.com/a"))
    }

    @Test
    fun findsSchemeUris() {
        val out = UriHarvest.harvest(
            listOf(
                "content://media/external/images/1",
                "android-app://com.example/https/host/path",
            ),
        )
        assertTrue(out.contains("content://media/external/images/1"))
        assertTrue(out.contains("android-app://com.example/https/host/path"))
    }

    @Test
    fun findsOpaqueAndVndAndIntentSchemes() {
        val out = UriHarvest.harvest(
            listOf(
                "reply tel:+15551234567 or mailto:a@b.com",
                "vnd.youtube:dQw4w9WgXcQ",
                "intent://x/y#Intent;scheme=https;end",
            ),
        )
        assertTrue(out.any { it.startsWith("tel:") })
        assertTrue(out.any { it.startsWith("mailto:") })
        assertTrue(out.any { it.startsWith("vnd.youtube:") })
        assertTrue(out.any { it.startsWith("intent://") })
    }

    @Test
    fun dedupesAcrossInputs() {
        val out = UriHarvest.harvest(listOf("x https://a.example/1", "y https://a.example/1"))
        assertEquals(1, out.count { it == "https://a.example/1" })
    }

    // --- negative ------------------------------------------------------------------------------

    @Test
    fun plainTextYieldsNothing() {
        val out = UriHarvest.harvest(listOf("Hello world, this is just a message with no links."))
        assertTrue(out.isEmpty())
    }

    @Test
    fun numbersAndColonsAreNotUris() {
        val out = UriHarvest.harvest(listOf("Meeting at 10:30, room 4:2, version 1.2.3"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun nullsAndBlanksAreIgnored() {
        val out = UriHarvest.harvest(listOf(null, "", "   "))
        assertTrue(out.isEmpty())
    }

    @Test
    fun keyValueTextIsNotAScheme() {
        val out = UriHarvest.harvest(listOf("note:remember this", "status:ok"))
        assertFalse(out.any { it.startsWith("note:") || it.startsWith("status:") })
    }
}
