import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.R
import java.io.IOException

fun Spinner.config(headers: Array<String>, selectionIndex:Int, onSelect: (Int) -> Unit) {
    this.adapter = ArrayAdapter(this.context, R.layout.support_simple_spinner_dropdown_item, headers)
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelect(position)
        }
    }
    this.setSelection(selectionIndex)
}

fun TextView.setString(text:String) {
    this.text = text
}

fun Double.format(digits: Int) = "%.${digits}f".format(this).replace(',', '.')


fun createVideoURI(context: Context, displayName: String, f:(Uri)-> Unit): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
    }

    val resolver = context.contentResolver
    var uri: Uri? = null

    try {
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Failed to create new MediaStore record.")
        f(uri)
        return uri
    } catch (e: IOException) {
        uri?.let { orphanUri ->
            // Don't leave an orphan entry in the MediaStore
            resolver.delete(orphanUri, null, null)
        }
        throw e
    }
}