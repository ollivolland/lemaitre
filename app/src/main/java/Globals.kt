import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import kotlin.random.Random

class Globals {

    companion object {
        lateinit var get:Globals
        const val DIR_NAME = "lemaitre"
        val dirExternal = File("${Environment.getExternalStorageDirectory().absolutePath}/Android/data/com.ollivolland.lemaitre")

        fun init(context: Context) {
            get = Globals()
        }
        
        val RANDOM = Random(System.currentTimeMillis())
        val FORMAT_TIME = SimpleDateFormat("HH:mm:ss")
        val FORMAT_TIME_FILE = SimpleDateFormat("HH-mm-ss")
        val FORMAT_DAY_FILE = SimpleDateFormat("yyyy-MM-dd")
    }
}