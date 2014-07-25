// KT-4479 Compilation to JavaScript with traits and delegation
// http://youtrack.jetbrains.com/issue/KT-4479

trait Foo {
    fun bar()
}

class Boo(val foo: Foo) : Foo by foo

object baz: Foo {
    override fun bar() = println("delegated")
}

fun main(args : Array<String>) {
    Boo(baz).bar()
}