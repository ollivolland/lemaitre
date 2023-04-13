import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.ollivolland.lemaitre2.R

class ConfigContainer(val parent: ViewGroup) {
    val root:View
    val layout:LinearLayout
    init {
        root = LayoutInflater.from(parent.context).inflate(R.layout.view_config, parent, false)
        layout = root.findViewById(R.id.config_lLayout)
        parent.addView(root)
    }
}