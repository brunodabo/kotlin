public trait Foo {
    override fun <lineMarker descr="Overrides function in 'Any'"></lineMarker>toString() = "str"
}

// Any.kt
//    public open fun <1>toString(): String