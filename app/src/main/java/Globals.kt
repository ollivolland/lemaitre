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
        val dirAppStorage = File("${Environment.getExternalStorageDirectory().absolutePath}/Android/media/com.ollivolland.lemaitre")
        val dirStarts = File("${dirAppStorage.absolutePath}/starts")
        val dirLogs = File("${dirAppStorage.absolutePath}/logs")
        val dirDownload = File("${Environment.getExternalStorageDirectory().absolutePath}/Download")
        val dirDCIM = File("${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_DCIM}")

        val deviceName: String = Settings.Global.getString(MyApp.appContext.contentResolver, Settings.Global.DEVICE_NAME)
        val deviceFingerprint: String = Settings.Secure.getString(MyApp.appContext.contentResolver, Settings.Secure.ANDROID_ID)



        val RANDOM = Random(System.currentTimeMillis())
        val FORMAT_TIME = SimpleDateFormat("HH:mm:ss")
        val FORMAT_TIME_FILE = SimpleDateFormat("HH-mm-ss")
        val FORMAT_LOGCAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        val FORMAT_DAY_FILE = SimpleDateFormat("yyyy-MM-dd")
        val formatToSeconds = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH)
    }
}