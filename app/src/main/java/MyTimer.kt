import android.os.SystemClock
import com.ollivolland.lemaitre2.GpsTime
import java.lang.Long.max

class MyTimer {
    val time;get() = timeOfBoot + SystemClock.elapsedRealtime()
    val timeOfBoot:Long = if(GpsTime.numObservations >= 10) GpsTime.timeOfBoot
        else System.currentTimeMillis() - SystemClock.elapsedRealtime()
    
    fun lock(until:Long) {
        Thread.sleep(max(until - time, 0L))
    }
}