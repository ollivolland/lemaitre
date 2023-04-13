import java.lang.Long.max

class MyTimer {
    val time;get() = System.currentTimeMillis()

    fun lock(until:Long) {
        Thread.sleep(max(until - time, 0L))
    }
}