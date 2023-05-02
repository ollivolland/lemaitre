package mycamera2

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader

class MyReader(private val myCamera2: MyCamera2, readerProfileBuilder: ReaderProfileBuilder, listener:ImageReader.OnImageAvailableListener) {
	
	init {
		val surfaceObservable = myCamera2.addSurface()
		val readerProfile = readerProfileBuilder.build()
		
		val imageReader = ImageReader.newInstance(readerProfile.width, readerProfile.height, ImageFormat.YUV_420_888, 2)
		surfaceObservable.value = imageReader.surface
		imageReader.setOnImageAvailableListener(listener, null)
		
		myCamera2.onCloseListeners.add { imageReader.close() }
	}
	
	internal data class ReaderProfile(val width: Int, val height: Int)
	
	class ReaderProfileBuilder {
		var width = 1920
		var height = 1080
		
		internal fun build():ReaderProfile = ReaderProfile(width, height)
	}
}