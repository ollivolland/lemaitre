import java.text.SimpleDateFormat
import kotlin.random.Random

class Globals {
    companion object {
        const val DIR_NAME = "lemaitre"
        
        val RANDOM = Random(System.currentTimeMillis())
        val FORMAT_TIME = SimpleDateFormat("HH:mm:ss")
        val FORMAT_TIME_FILE = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
    }
}