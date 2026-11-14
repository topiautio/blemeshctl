package io.github.topiautio.blemeshctl.timer

/**
 * Looks up Finnish text by resource id.
 *
 * The timer logic stays free of Android classes: the app implements this with `Resources`,
 * tests with a fake that records which id and arguments were used.
 */
interface TextSource {
    fun get(id: Int, vararg args: Any): String

    fun plural(id: Int, quantity: Int, vararg args: Any): String
}
