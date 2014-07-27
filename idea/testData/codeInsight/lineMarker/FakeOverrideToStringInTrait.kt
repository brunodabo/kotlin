trait <lineMarker descr="*"></lineMarker>A {
    override fun <lineMarker descr="<html><body>Is implemented in <br>&nbsp;&nbsp;&nbsp;&nbsp;C<br>&nbsp;&nbsp;&nbsp;&nbsp;java.lang.Object</body></html>"><lineMarker descr="*"></lineMarker></lineMarker>toString() = "A"
}

abstract class <lineMarker descr="*"></lineMarker>B: A

class C: B() {
    override fun <lineMarker descr="*"></lineMarker>toString() = "B"
}
