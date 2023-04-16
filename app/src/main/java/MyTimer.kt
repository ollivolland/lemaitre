import android.os.SystemClock
import java.lang.Long.max

class MyTimer {
    val timeOfBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val time;get() = timeOfBoot + SystemClock.elapsedRealtime()

    fun lock(until:Long) {
        Thread.sleep(max(until - time, 0L))
    }
}