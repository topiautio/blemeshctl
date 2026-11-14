package io.github.topiautio.blemeshctl.timer

import android.content.Context

/** [TextSource] backed by the app's string resources. */
class ResourceTextSource(context: Context) : TextSource {
    private val resources = context.applicationContext.resources

    override fun get(id: Int, vararg args: Any): String = resources.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String =
        resources.getQuantityString(id, quantity, *args)
}
