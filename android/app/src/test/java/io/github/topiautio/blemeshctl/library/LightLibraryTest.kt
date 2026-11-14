package io.github.topiautio.blemeshctl.library

import io.github.topiautio.blemeshctl.script.ScriptException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Documentation-only addresses; not real devices.
private const val DESK = "02:00:00:00:00:01"
private const val LAMP = "02:00:00:00:00:02"
private const val SHELF = "02:00:00:00:00:03"

class LightNamesTests {
    @Test
    fun normalizesAddressesToUpperCase() {
        assertEquals("AA:BB:CC:DD:EE:0F", LightNames.normalizeAddress(" aa:bb:cc:dd:ee:0f "))
    }

    @Test
    fun rejectsMalformedAddresses() {
        for (text in listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA-BB-CC-DD-EE-FF", "GG:BB:CC:DD:EE:FF", "AABBCCDDEEFF")) {
            assertThrows(text, LibraryException::class.java) { LightNames.normalizeAddress(text) }
        }
    }

    @Test
    fun acceptsAndTrimsValidNames() {
        for (name in listOf("desk", "Desk 2", "living-room", "a_b", "9", "x".repeat(LightNames.MAX_NAME_LENGTH))) {
            assertEquals(name, LightNames.validateName(" $name ", "Light"))
        }
    }

    @Test
    fun rejectsInvalidNames() {
        val cases = listOf("", "   ", "-desk", "_desk", " desk:1", "desk!", "työpöytä", "x".repeat(LightNames.MAX_NAME_LENGTH + 1))
        for (name in cases) {
            val error = assertThrows(name, LibraryException::class.java) { LightNames.validateName(name, "Group") }
            assertTrue(error.message.orEmpty().startsWith("Group name"))
        }
    }
}

class LightLibraryTests {
    private fun library(storage: LibraryStorage = InMemoryLibraryStorage()) = LightLibrary(storage)

    @Test
    fun aliasesAreStoredByNormalizedAddressAndCanBeRemoved() {
        val storage = InMemoryLibraryStorage()
        val library = library(storage)

        library.setAlias(DESK.lowercase(), " Desk ")

        assertEquals("Desk", library.alias(DESK))
        assertEquals(mapOf("alias:$DESK" to "Desk"), storage.entries)
        assertEquals(listOf(DESK), library.resolveTarget("desk"))

        library.setAlias(DESK, "")
        assertNull(library.alias(DESK))
        assertEquals(DESK, library.displayName(DESK))
        assertEquals(emptyMap<String, String>(), storage.entries)
    }

    @Test
    fun renamingALightKeepsItsGroups() {
        val library = library()
        library.setAlias(DESK, "desk")
        library.saveGroup("office", listOf("desk"))

        library.setAlias(DESK, "work desk")

        assertEquals(listOf(DESK), library.resolveTarget("office"))
        assertEquals(listOf(DESK), library.resolveTarget("Work Desk"))
    }

    @Test
    fun twoLightsCannotShareAnAliasInAnyCase() {
        val library = library()
        library.setAlias(DESK, "desk")

        val error = assertThrows(LibraryException::class.java) { library.setAlias(LAMP, "DESK") }

        assertTrue(error.message.orEmpty().contains(DESK))
        assertNull(library.alias(LAMP))
    }

    @Test
    fun aliasAndGroupNamesCannotCollide() {
        val library = library()
        library.setAlias(DESK, "desk")
        library.saveGroup("Bedroom", listOf(DESK))

        assertThrows(LibraryException::class.java) { library.setAlias(LAMP, "bedroom") }
        assertThrows(LibraryException::class.java) { library.saveGroup("Desk", listOf(DESK)) }
        assertNull(library.alias(LAMP))
        assertEquals(listOf("Bedroom"), library.groups.map { it.name })
    }

    @Test
    fun groupNamesAreUniqueUnlessReplacingTheSameGroup() {
        val library = library()
        library.setAlias(DESK, "desk")
        library.setAlias(LAMP, "lamp")
        library.saveGroup("office", listOf("desk"))

        assertThrows(LibraryException::class.java) { library.saveGroup("OFFICE", listOf("lamp")) }

        library.saveGroup("Office", listOf("desk", "lamp"), replacing = "office")
        assertEquals(listOf(LightGroup("Office", listOf(DESK, LAMP))), library.groups)

        library.saveGroup("study", listOf("lamp"), replacing = "Office")
        assertEquals(listOf("study"), library.groups.map { it.name })
        assertNull(library.group("office"))
    }

    @Test
    fun groupMembersMustBeKnownLights() {
        val library = library()
        library.setAlias(DESK, "desk")

        val unknownAddress = assertThrows(LibraryException::class.java) {
            library.saveGroup("office", listOf("desk", LAMP))
        }
        assertTrue(unknownAddress.message.orEmpty().contains("not a known light"))
        assertThrows(LibraryException::class.java) { library.saveGroup("office", listOf("nobody")) }
        assertThrows(LibraryException::class.java) { library.saveGroup("office", emptyList()) }
        assertThrows(LibraryException::class.java) { library.saveGroup("office", listOf("desk", DESK.lowercase())) }
        assertTrue(library.groups.isEmpty())

        // A light seen by a scan counts as known, and stays known as a member.
        library.saveGroup("office", listOf("desk", LAMP.lowercase()), discoveredAddresses = setOf(LAMP))
        assertEquals(listOf(DESK, LAMP), library.resolveTarget("office"))
        library.saveGroup("hall", listOf(LAMP))
        assertEquals(setOf(DESK, LAMP), library.knownAddresses)
    }

    @Test
    fun groupsCannotContainGroups() {
        val library = library()
        library.setAlias(DESK, "desk")
        library.saveGroup("office", listOf("desk"))

        val error = assertThrows(LibraryException::class.java) { library.saveGroup("house", listOf("office")) }

        assertTrue(error.message.orEmpty().contains("cannot contain another group"))
    }

    @Test
    fun resolvesAddressesAliasesAndGroups() {
        val library = library()
        library.setAlias(DESK, "desk")
        library.setAlias(LAMP, "lamp")
        library.saveGroup("bedroom", listOf("lamp", "desk"))

        assertEquals(listOf(SHELF), library.resolveTarget(SHELF.lowercase()))
        assertEquals(listOf(LAMP), library.resolveTarget(" LAMP "))
        assertEquals(listOf(LAMP, DESK), library.resolveTarget("Bedroom"))
        assertThrows(LibraryException::class.java) { library.resolveTarget("kitchen") }
    }

    @Test
    fun scenesAreValidatedBeforeTheyAreSaved() {
        val storage = InMemoryLibraryStorage()
        val library = library(storage)

        assertThrows(ScriptException::class.java) { library.saveScene("movie", "color ff0000 101") }
        assertThrows(ScriptException::class.java) { library.saveScene("movie", "loop:\n  on") }
        assertThrows(LibraryException::class.java) { library.saveScene("movie:night", "on") }
        assertThrows(LibraryException::class.java) {
            library.saveScene("huge", "on\n" + "#".repeat(LightLibrary.MAX_SCENE_CHARACTERS))
        }
        assertTrue(storage.entries.isEmpty())

        library.saveScene("movie", "on\ncolor 2200ff 30\n")
        assertEquals(mapOf("scene:movie" to "on\ncolor 2200ff 30\n"), storage.entries)
    }

    @Test
    fun scenesCanBeRenamedAndDeleted() {
        val library = library()
        library.saveScene("movie", "off")
        library.saveScene("reading", "on")

        assertThrows(LibraryException::class.java) { library.renameScene("movie", "READING") }
        library.renameScene("movie", "Movie night")
        library.renameScene("Movie night", "movie NIGHT")

        assertEquals(listOf("movie NIGHT", "reading"), library.scenes.map { it.name })
        assertEquals("off", library.scene("Movie Night")?.script)

        library.deleteScene("MOVIE night")
        assertEquals(listOf("reading"), library.scenes.map { it.name })
        assertThrows(LibraryException::class.java) { library.deleteScene("movie night") }
    }

    @Test
    fun sceneNamesMayMatchALightOrGroupName() {
        val library = library()
        library.setAlias(DESK, "desk")

        library.saveScene("desk", "on")

        assertEquals(listOf(DESK), library.resolveTarget("desk"))
    }

    @Test
    fun reloadsEverythingFromStorage() {
        val storage = InMemoryLibraryStorage()
        library(storage).apply {
            setAlias(DESK, "desk")
            saveGroup("office", listOf("desk", LAMP), discoveredAddresses = setOf(LAMP))
            saveScene("movie", "on\nwhite 2700K 20\n")
        }

        val reloaded = library(storage)

        assertEquals(listOf(NamedLight(DESK, "desk")), reloaded.lights)
        assertEquals(listOf(LightGroup("office", listOf(DESK, LAMP))), reloaded.groups)
        assertEquals(listOf(Scene("movie", "on\nwhite 2700K 20\n")), reloaded.scenes)
    }

    @Test
    fun skipsMalformedOrConflictingStoredEntries() {
        val storage = InMemoryLibraryStorage(
            mapOf(
                "alias:$DESK" to "desk",
                "alias:not-an-address" to "lamp",
                "alias:$LAMP" to "DESK",
                "group:desk" to DESK,
                "group:office" to "$DESK,bad",
                "group:hall" to DESK,
                "scene:bad" to "unknown",
                "scene:good" to "on",
                "unrelated" to "value",
            ),
        )

        val library = library(storage)

        assertEquals(listOf(NamedLight(DESK, "desk")), library.lights)
        assertEquals(listOf(LightGroup("hall", listOf(DESK))), library.groups)
        assertEquals(listOf("good"), library.scenes.map { it.name })
    }
}
