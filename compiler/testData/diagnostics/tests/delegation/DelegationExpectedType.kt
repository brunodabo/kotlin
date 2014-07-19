trait A<T> {
    fun foo(): Int
}

class AImpl<T>: A<T> {
    override fun foo() = 42
}

fun <T> bar(): A<T> = AImpl()

class B : A<Int> by bar()

class C : A<Int> by AImpl()