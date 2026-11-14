package io.github.topiautio.blemeshctl.library

import java.util.Locale

/** Raised when a light alias, group, or scene cannot be saved or resolved. */
class LibraryException(message: String) : IllegalArgumentException(message)

/** Validation shared by light aliases, group names, scene names, and Bluetooth addresses. */
object LightNames {
    const val MAX_NAME_LENGTH = 32

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_ -]*")
    private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

    /** Return [text] as an upper-case `AA:BB:CC:DD:EE:FF` address, or fail. */
    fun normalizeAddress(text: String): String {
        val normalized = text.trim().uppercase(Locale.ROOT)
        if (!ADDRESS.matches(normalized)) {
            throw LibraryException("'${text.trim()}' ei ole Bluetooth-osoite, esimerkiksi AA:BB:CC:DD:EE:FF")
        }
        return normalized
    }

    fun isAddress(text: String): Boolean = ADDRESS.matches(text.trim().uppercase(Locale.ROOT))

    /**
     * Return the trimmed name, or fail with a message that starts with [kind] (a Finnish genitive such as `Ryhmän`).
     *
     * Names start with a letter or digit and may then contain letters, digits,
     * spaces, underscores, and hyphens. A colon is never allowed, so a name can
     * never be mistaken for a Bluetooth address.
     */
    fun validateName(text: String, kind: String): String {
        val name = text.trim()
        if (name.isEmpty()) throw LibraryException("$kind nimi ei saa olla tyhjä")
        if (name.length > MAX_NAME_LENGTH) {
            throw LibraryException("$kind nimi saa olla enintään $MAX_NAME_LENGTH merkkiä")
        }
        if (!NAME.matches(name)) {
            throw LibraryException(
                "$kind nimen '$name' pitää alkaa kirjaimella tai numerolla, ja siinä saa olla vain " +
                    "kirjaimia A–Z, numeroita, välilyöntejä sekä merkit '_' ja '-'",
            )
        }
        return name
    }

    /** Names are compared case-insensitively, so `Desk` and `desk` are the same name. */
    fun sameName(first: String, second: String): Boolean = key(first) == key(second)

    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
}
