package camera

import android.util.Log
import com.ollivolland.lemaitre2.BuildConfig

class MyLog(private var tag:String, private val priority:Int = Log.INFO) {
    
    constructor(any: Any,  priority:Int = Log.INFO) : this(any::class.simpleName!!, priority)
    
    init {
    	tag = tag.replace("${BuildConfig.APPLICATION_ID}.", "")
    }

    operator fun plusAssign(string: String) {
        Log.println(priority, tag, string)
    }

    operator fun plusAssign(e: Exception) {
        Log.e(tag, e.toString())
        Log.e(tag, e.stackTraceToString())
    }
}