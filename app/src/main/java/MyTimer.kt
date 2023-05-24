import android.os.SystemClock
import com.ollivolland.lemaitre2.GpsTime
import java.lang.Long.max

class MyTimer {
    val time;get() = timeOfBoot + SystemClock.elapsedRealtime()
    val timeOfBoot:Long = if(GpsTime.numObservations >= MIN_OBSERVATIONS) GpsTime.timeOfBoot
        else System.currentTimeMillis() - SystemClock.elapsedRealtime()
    
    fun lock(until:Long) {
        Thread.sleep(max(until - time, 0L))
    }
    
    companion object {
        const val MIN_OBSERVATIONS = 3
        
        fun getTime():Long {
            return if(GpsTime.numObservations < MIN_OBSERVATIONS) return System.currentTimeMillis()
            else GpsTime.timeOfBoot + SystemClock.elapsedRealtime()
        }
        
        fun isHasGpsTime(): Boolean = GpsTime.numObservations >= MIN_OBSERVATIONS
    }
}