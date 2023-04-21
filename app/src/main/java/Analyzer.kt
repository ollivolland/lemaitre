import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import mycamera2.MyCamera2
import mycamera2.MyReader
import java.io.File
import java.io.FileOutputStream
import java.lang.Integer.max
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.min


class Analyzer(private val context: Context, myCamera2: MyCamera2, private val myTimer: MyTimer, private val timeStart:Long) {
	var index = 0
	var isWant = false
	private val session = Globals.FORMAT_TIME_FILE.format(myTimer.time)!!
	private val day = Globals.FORMAT_DAY_FILE.format(myTimer.time)!!
	var c1=0;var c2=0
	var r=0;var g=0; var b=0
	var numThisBroken = 0
	var numBuffered = 0
//	var bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
	private val bitmaps = mutableListOf<Bitmap?>()
	private val buffers = mutableListOf<IntBuffer?>()
	private val isBroken = mutableListOf<Boolean>()
	private val timesMs = mutableListOf<Long>()
	private val numBroken = mutableListOf<Int>()
	private val needed = mutableListOf<Int>()
	private var isHasStreakStarted = false
	private var streakMaxBroken = 0
	val onStreakStartedListeners = mutableListOf<(Long)->Unit>()
	val onTriangulatedListeners = mutableListOf<(Long,Long)->Unit>()
	
	private val listenTo = ImageReader.OnImageAvailableListener {
		val image = it.acquireLatestImage() ?: return@OnImageAvailableListener

		try {
			if (isWant) {
//				yuvToRgbConverter.yuvToRgb(image, bitmap)
//				val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
				addBuffer(image)
				timesMs.add(image.timestamp / NANO_OVER_MILLI)

				if (index >= 2) {
					isBroken[index] = isBrokenAtLine(index, index - 1, HALF_HEIGHT)
					numBroken[index] = numThisBroken

					if (!isHasStreakStarted && isBroken[index] && needed.count { it > 0 } < MAX_IMAGES_CONCURRENT) {
						isHasStreakStarted = true
						streakMaxBroken = 0
						needed.add(index)
						println("STREAK STARTED")

						val timeFrameMs = myTimer.timeOfBoot + timesMs[index] - timeStart
						thread { for (x in onStreakStartedListeners) x(timeFrameMs) }
					} else if (isHasStreakStarted && !isBroken[index]) {
						isHasStreakStarted = false
						println("STREAK STOPPED")
						process(needed.last())
					}

					if (isHasStreakStarted) {
						if (numBroken[index] > streakMaxBroken) {
							streakMaxBroken = numBroken[index]
							needed[needed.lastIndex] = index
						}
					}

					//  dealloc not needed
					for (notNeeded in buffers.indices.filterNot { i ->
						if (i >= index - 2) return@filterNot true

						for (x in needed)
							if (i <= x && i >= x - 2) return@filterNot true

						return@filterNot false
					}) clearBuffer(notNeeded)
				}
				index++
//				bitmap.recycle()
			}
		} catch (e:Exception) { e.printStackTrace() }
		
		image.close()
	}
	val myReader: MyReader = myCamera2.addReader(MyReader.ReaderProfileBuilder(), listenTo)

//	fun decodeYUV420SP(yuv420sp: ByteArray):IntArray {	//	BROKEN
//		val frameSize = WIDTH * HEIGHT
//		val rgba = IntArray(frameSize * 4)
//		var j = 0; var yp = 0; var u = 0; var v = 0; var i = 0
//		var r: Int;var b: Int;var g: Int
//		var y: Int; var uvp: Int; var y1192: Int
//		while (j < HEIGHT) {
//			uvp = frameSize + (j shr 1) * WIDTH
//			while (i < WIDTH) {
//				y = (0xff and yuv420sp[yp].toInt()) - 16
//				if (y < 0) y = 0
//				if (i and 1 == 0) {
//					v = (0xff and yuv420sp[uvp++].toInt()) - 128
//					u = (0xff and yuv420sp[uvp++].toInt()) - 128
//				}
//				y1192 = 1192 * y
//				r = y1192 + 1634 * v
//				g = y1192 - 833 * v - 400 * u
//				b = y1192 + 2066 * u
//				if (r < 0) r = 0 else if (r > 262143) r = 262143
//				if (g < 0) g = 0 else if (g > 262143) g = 262143
//				if (b < 0) b = 0 else if (b > 262143) b = 262143
//
//				// rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) &
//				// 0xff00) | ((b >> 10) & 0xff);
//				// rgba, divide 2^10 ( >> 10)
//				rgba[yp] = (r shl 14 and (-0x1000000) or (g shl 6 and 0xff0000)
//						or (b shr 2 or 0xff00))
//				i++
//				yp++
//			}
//			j++
//		}
//		return  rgba
//	}

	fun yuvtorgb(image: Image, bitmap: Bitmap) {
		val yuvBytes: ByteBuffer = imageToByteBuffer(image)

		// Convert YUV to RGB
		val rs = RenderScript.create(context)
		val allocationRgb = Allocation.createFromBitmap(rs, bitmap)
		val allocationYuv = Allocation.createSized(rs, Element.U8(rs), yuvBytes.array().size)
		allocationYuv.copyFrom(yuvBytes.array())

		val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
		scriptYuvToRgb.setInput(allocationYuv)
		scriptYuvToRgb.forEach(allocationRgb)

		//	back
		allocationRgb.copyTo(bitmap)

		// Release
		allocationYuv.destroy()
		allocationRgb.destroy()
		rs.destroy()
	}

	private fun imageToByteBuffer(image: Image): ByteBuffer {
		val crop = image.cropRect
		val width = crop.width()
		val height = crop.height()
		val planes = image.planes
		val rowData = ByteArray(planes[0].rowStride)
		val bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8
		val output = ByteBuffer.allocateDirect(bufferSize)
		var channelOffset = 0
		var outputStride = 0
		for (planeIndex in 0..2) {
			when (planeIndex) {
				0 -> {
					channelOffset = 0
					outputStride = 1
				}
				1 -> {
					channelOffset = width * height + 1
					outputStride = 2
				}
				else -> {
					channelOffset = width * height
					outputStride = 2
				}
			}
			val buffer = planes[planeIndex].buffer
			val rowStride = planes[planeIndex].rowStride
			val pixelStride = planes[planeIndex].pixelStride
			val shift = if (planeIndex == 0) 0 else 1
			val widthShifted = width shr shift
			val heightShifted = height shr shift
			buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
			for (row in 0 until heightShifted) {
				val length: Int
				if (pixelStride == 1 && outputStride == 1) {
					length = widthShifted
					buffer[output.array(), channelOffset, length]
					channelOffset += length
				} else {
					length = (widthShifted - 1) * pixelStride + 1
					buffer[rowData, 0, length]
					for (col in 0 until widthShifted) {
						output.array()[channelOffset] = rowData[col * pixelStride]
						channelOffset += outputStride
					}
				}
				if (row < heightShifted - 1) {
					buffer.position(buffer.position() + rowStride - length)
				}
			}
		}
		return output
	}
	
	init {
		File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/${Globals.DIR_NAME}/$day/$session").mkdirs()
	}

	fun process(i: Int) {
		if(i >= buffers.size || i < 2 || buffers[i] == null || buffers[i-1] == null || buffers[i-2] == null) return

		println("needed ${needed.lastIndex}")
		val neededIndex = needed.lastIndex
		thread {
			try {
				println("BUFFERS got $i")
				val newBuffer = buffers[i]!!.array().copyOfRange(0, buffers[i]!!.capacity())

				//  bgr cause little indian
				var colorIndicator = 0xffff0000.toInt()
				val colorMid = 0xffffffff.toInt()
				var colorBroken = 0xff0000aa.toInt()

				val thisMaxBroken = numBroken[i]
				var yBroken = IntArray(WIDTH)
				var yIndex: Int
				var useI = i - 1

				val lambda: (Int) -> Boolean = { y ->
					yIndex = y * WIDTH

					for (x in 0 until WIDTH)
						if (isBroken(useI, useI - 1, yIndex + x)) {
							yBroken[y]++
							newBuffer[yIndex + x] = colorBroken
						}

					(yBroken[y] < thisMaxBroken * IMG_SKIP_MIN)
				}

				//  *****   IMG -1
				//  diff
				for (y in (HALF_HEIGHT - 1) downTo 0) if (lambda(y)) break
				for (y in (HALF_HEIGHT + 1) until HEIGHT) if (lambda(y)) break
				val lastYBroken = yBroken.clone()
				yBroken = IntArray(WIDTH)

				//  *****   IMG
				useI = i
				colorBroken = 0xff0000ff.toInt()
				for (y in (HALF_HEIGHT - 1) downTo 0) if (lambda(y)) break
				for (y in (HALF_HEIGHT + 1) until HEIGHT) if (lambda(y)) break

				//  mid
				yIndex = HALF_HEIGHT * WIDTH
				for (x in 0 until WIDTH) newBuffer[yIndex + x] = colorMid

				var yFirstBroken = 0
				val drawDiagram: (IntArray) -> Unit = { useyBroken ->
					//  diagram
					for (y in 1 until HEIGHT)
						for (x in max(min(useyBroken[y - 1], useyBroken[y]) - 3, 0) until max(useyBroken[y - 1], useyBroken[y]))
							newBuffer[y * WIDTH + WIDTH - x] = colorIndicator
					//  first broken
					val thisFullMostBroken = useyBroken.max()
					for (y in HEIGHT downTo 0)
						if (useyBroken[y] > thisFullMostBroken * FACTOR_MIN_BROKEN_FOR_BODY) {
							yFirstBroken = y
							break
						}
					yIndex = yFirstBroken * WIDTH
					for (x in 0 until WIDTH) newBuffer[yIndex + x] = colorIndicator
				}

				drawDiagram(lastYBroken)
				colorIndicator = 0xff00ff00.toInt()
				val yLastBroken = yFirstBroken
				drawDiagram(yBroken)

				//  time
				val timeFrameMS = myTimer.timeOfBoot + timesMs[i] - timeStart

				val delta = yFirstBroken - yLastBroken
				val deltaMS = timesMs[i] - timesMs[i - 1]
				val numDeltas = (HALF_HEIGHT - yFirstBroken) / delta.toDouble()
				val adjustmentMs = (numDeltas * deltaMS).toLong()
				var timeTriangleMs = timeFrameMS + adjustmentMs

				if (numDeltas.absoluteValue >= 5) timeTriangleMs = 0

				thread {
					for (x in onTriangulatedListeners) x(timeTriangleMs, timeFrameMS)
				}

				println(
					"delta = $delta\n" +
							"deltaMS = $deltaMS\n" +
							"numDeltas = $numDeltas\n" +
							"adjustmentMs = $adjustmentMs"
				)

				val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
				bmp.copyPixelsFromBuffer(IntBuffer.wrap(newBuffer))
				val matrix = Matrix()
				matrix.postRotate(90f)
				val rotatedBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, false)

				val file = File(
					"${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/${Globals.DIR_NAME}/$day/$session/" +
							"image_triangletime-${timeTriangleMs / 1000}-${String.format("%03d", timeTriangleMs % 1000)}-s" +
							"_frame-${i}_frametime-${timeFrameMS / 1000}-${String.format("%03d", timeFrameMS % 1000)}-s.jpg"
				)
				FileOutputStream(file).use { out ->
					rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)   //  jpg is faster
					rotatedBitmap.recycle()
					bmp.recycle()
					out.flush()
				}

				//	release buffer
				needed[neededIndex] = -3
			} catch (e:Exception) {
				e.printStackTrace()
			}
		}
	}
	
	fun stop() {
		isWant = false
	}
	
	private fun clearBuffer(i:Int) {
		if(i < 0 || buffers[i] == null) return

		println("cleared buffer[$i]")
		buffers[i] = null
		bitmaps[i]?.recycle()
		bitmaps[i] = null
		numBuffered--
	}
	
	private fun addBuffer(image: Image) {
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		yuvtorgb(image, bitmap)
		val buffer = IntBuffer.allocate(bitmap.byteCount)
		bitmap.copyPixelsToBuffer(buffer)

//		val array = decodeYUV420SP(imageToByteBuffer(image).array())
//		val buffer = IntBuffer.wrap(array)

		bitmaps.add(bitmap)
		buffers.add(buffer)
		numBuffered++
		println("allocated buffer[$index] with ${buffers.last()?.capacity()}, $numBuffered")
		
		isBroken.add(false)
		numBroken.add(0)
	}
	
	private fun isBrokenAtLine(bmp1: Int, bmp2: Int, y:Int):Boolean {
		numThisBroken = 0
		val yIndex = y * WIDTH
		
		for (x in 0 until WIDTH)
			if(isBroken(bmp1, bmp2, yIndex+x))
				numThisBroken++
		
		return numThisBroken >= MIN_LINE_BROKEN_FOR_REGISTER
	}
	
	private fun isBroken(bmp1: Int, bmp2: Int, index:Int):Boolean {
		c1 = buffers[bmp1]!![index]
		c2 = buffers[bmp2]!![index]
		r = ((c1 shr 16) and 0xff) - ((c2 shr 16) and 0xff)
		g = ((c1 shr 8) and 0xff) - ((c2 shr 8) and 0xff)
		b = (c1 and 0xff) - (c2 and 0xff)
		
		return (r * r + g * g + b * b) >= SENSITIVITY
	}
	
	companion object {
		const val WIDTH = 1920
		const val HEIGHT = 1080
		const val SENSITIVITY = 1097 //  195075 * p^2
		const val HALF_HEIGHT = HEIGHT/2
		const val MIN_LINE_BROKEN_FOR_REGISTER = (WIDTH * .15).toInt()
		const val IMG_SKIP_MIN = .1
		const val NANO_OVER_MILLI = 1E6.toLong()
		const val FACTOR_MIN_BROKEN_FOR_BODY = .7
		const val MAX_IMAGES_CONCURRENT = 1
	}
}