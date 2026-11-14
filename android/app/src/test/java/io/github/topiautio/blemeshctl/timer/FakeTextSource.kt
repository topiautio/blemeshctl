package io.github.topiautio.blemeshctl.timer

/** Text source that renders `id:arg,arg`, so tests can assert which string and arguments were used. */
internal class FakeTextSource : TextSource {
    override fun get(id: Int, vararg args: Any): String = render(id, args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String = "plural " + render(id, args) + " q$quantity"

    fun expect(id: Int, vararg args: Any): String = render(id, arrayOf(*args))

    private fun render(id: Int, args: Array<out Any>) = "$id:" + args.joinToString(",")
}
