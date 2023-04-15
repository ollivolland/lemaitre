import java.text.SimpleDateFormat
import kotlin.random.Random

class Globals {
    companion object {
        val RANDOM = Random(System.currentTimeMillis())
        val FORMAT_TIME = SimpleDateFormat("HH:mm:ss")
    }
}