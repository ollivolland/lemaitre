import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import com.ollivolland.lemaitre2.R

class ViewDevice(activity: Activity, viewGroup: ViewGroup) {
	val root: View
	val vTitle: TextView
	val vDesc: TextView
	val vSettings: ImageButton
	init {
		root = activity.layoutInflater.inflate(R.layout.view_device, viewGroup, false)
		vTitle = root.findViewById(R.id.device_tTitle)
		vDesc = root.findViewById(R.id.device_tDesc)
		vSettings = root.findViewById(R.id.device_bSettings)
		viewGroup.addView(root)
	}
}