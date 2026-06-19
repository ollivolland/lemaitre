import android.os.Environment
import android.provider.Settings
import com.ollivolland.lemaitre.MyApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

class Globals {

    companion object {
        const val DIR_NAME = "lemaitre"
        val dirExternal = File("${Environment.getExternalStorageDirectory().absolutePath}/Android/data/com.ollivolland.lemaitre")
        val dirStarts = File("${Environment.getExternalStorageDirectory().absolutePath}/Android/data/com.ollivolland.lemaitre/starts")
        val dirDownload = File("${Environment.getExternalStorageDirectory().absolutePath}/Download")

        val deviceName = Settings.Global.getString(MyApp.appContext.contentResolver, Settings.Global.DEVICE_NAME)
        val deviceFingerprint = Settings.Secure.getString(MyApp.appContext.contentResolver, Settings.Secure.ANDROID_ID)



        val RANDOM = Random(System.currentTimeMillis())
        val FORMAT_TIME = SimpleDateFormat("HH:mm:ss")
        val FORMAT_TIME_FILE = SimpleDateFormat("HH-mm-ss")
        val FORMAT_DAY_FILE = SimpleDateFormat("yyyy-MM-dd")
        val formatToSeconds = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH)
    }
}