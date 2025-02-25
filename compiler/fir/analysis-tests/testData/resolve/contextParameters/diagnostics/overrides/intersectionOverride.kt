// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: +ContextParameters
class A

interface First {
    context(a: A)
    fun foo()

    context(a: A)
    val b: String
}

interface Second {
    context(a: Any)
    fun foo()

    context(a: Any)
    val b: String
}

interface IntersectionInterface: First, Second

class IntersectionClass : First, Second {
    context(a: A)
    override fun foo() { }

    context(a: Any)
    override fun foo() { }

    context(a: A)
    override val b: String
        get() = ""

    context(a: Any)
    override val b: String
        get() = ""
}

fun usage(a: IntersectionInterface) {
    with<A, Unit>(A()) {
        a.<!OVERLOAD_RESOLUTION_AMBIGUITY!>foo<!>()
        a.<!OVERLOAD_RESOLUTION_AMBIGUITY!>b<!>
        IntersectionClass().<!OVERLOAD_RESOLUTION_AMBIGUITY!>foo<!>()
        IntersectionClass().<!OVERLOAD_RESOLUTION_AMBIGUITY!>b<!>
    }
}