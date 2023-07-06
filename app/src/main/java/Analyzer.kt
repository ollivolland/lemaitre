import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import mycamera2.MyCamera2
import mycamera2.MyReader
import mycamera2.MyYubToRgb
import java.io.File
import java.io.FileOutputStream
import java.lang.Integer.max
import java.nio.IntBuffer
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.min


class Analyzer(private val context: Context, myCamera2: MyCamera2, private val myTimer: MyTimer, private val timeStart:Long) {
	private var index = 0
	var isWant = false
	private val session = Globals.FORMAT_TIME_FILE.format(myTimer.time)!!
	private val day = Globals.FORMAT_DAY_FILE.format(myTimer.time)!!
	var c1=0;var c2=0
	var r=0;var g=0; var b=0
	var numThisBroken = 0
	var numBuffered = 0
	private val buffers = ArrayList<IntBuffer?>(61 * 30)
	private val isBroken = mutableListOf<Boolean>()
	private val timesMs = ArrayList<Long>(61 * 30)
	private val numBroken = mutableListOf<Int>()
	private val needed = mutableListOf<Int>()
	private var isHasStreakStarted = false
	private var streakMaxBroken = 0
	val onStreakStartedListeners = mutableListOf<(Long)->Unit>()
	val onTriangulatedListeners = mutableListOf<(triangleMs:Long,frameMs:Long,deltas:Double)->Unit>()
	
	private val listenTo = ImageReader.OnImageAvailableListener {
		val timeListened = myTimer.time   //  time without delay
		val image = it.acquireLatestImage() ?: return@OnImageAvailableListener

		try {
			if (isWant) {
				timesMs.add(timeListened)
				addBuffer(image)
//				timesMs.add(image.timestamp / NANO_OVER_MILLI + myTimer.timeToBoot)

				if (index >= 2) {
					isBroken[index] = isBrokenAtLine(index, index - 1)
					numBroken[index] = numThisBroken

					if (!isHasStreakStarted && isBroken[index] && needed.count { it > 0 } < MAX_IMAGES_CONCURRENT) {
						isHasStreakStarted = true
						streakMaxBroken = 0
						needed.add(index)
						println("STREAK STARTED")

						val timeFrameMs = timesMs[index - 1] - timeStart
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
						if (i >= index - 1) return@filterNot true	//	next will be added after loop

						for (x in needed)
							if (i <= x && i >= x - 2) return@filterNot true

						return@filterNot false
					}) clearBuffer(notNeeded)
				}
				index++
			}
		} catch (e:Exception) { e.printStackTrace() }
		
		image.close()
	}
	val myReader: MyReader = myCamera2.addReader(MyReader.ReaderProfileBuilder().also {
		it.width = WIDTH
		it.height = HEIGHT
	}, listenTo)

	init {
		File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/${Globals.DIR_NAME}/$day/$session").mkdirs()
	}

	private fun process(i: Int) {
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

				//  TODO    get direction from image
				var yFirstBroken = 0
				val drawDiagram: (IntArray) -> Unit = { useYBroken ->
					//  diagram
					for (y in 1 until HEIGHT)
						for (x in max(min(useYBroken[y - 1], useYBroken[y]) - 3, 0) until max(useYBroken[y - 1], useYBroken[y]))
							newBuffer[y * WIDTH + WIDTH - x] = colorIndicator
					//  first broken
					val thisFullMostBroken = useYBroken.max()
					for (y in HEIGHT downTo 0)
						if (useYBroken[y] > thisFullMostBroken * FACTOR_MIN_BROKEN_FOR_BODY) {
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
				val timeFrameMS = timesMs[i - 1] - timeStart

				val delta = yFirstBroken - yLastBroken
				val deltaMS = timesMs[i] - timesMs[i - 1]
				val numDeltas = (HALF_HEIGHT - yFirstBroken) / delta.toDouble()
				val adjustmentMs = (numDeltas * deltaMS).toLong()
				var timeTriangleMs = timeFrameMS + adjustmentMs

				if (numDeltas.absoluteValue >= 5) timeTriangleMs = 0

				thread {
					for (x in onTriangulatedListeners) x(timeTriangleMs, timeFrameMS, numDeltas)
				}

				println(
					"delta = $delta\n" +
					"deltaMS = $deltaMS\n" +
					"numDeltas = $numDeltas\n" +
					"adjustmentMs = $adjustmentMs"
				)
				
				//	release buffer
				needed[neededIndex] = -3

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
		numBuffered--
	}
	
	private fun addBuffer(image: Image) {
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		MyYubToRgb.yuvtorgb(image, bitmap, context)
		val buffer = IntBuffer.allocate(bitmap.byteCount)
		bitmap.copyPixelsToBuffer(buffer)
		bitmap.recycle()
		System.gc()

		buffers.add(buffer)
		numBuffered++
		println("allocated buffer[$index] with ${buffers.last()?.capacity()}, $numBuffered")
		
		isBroken.add(false)
		numBroken.add(0)
	}
	
	private fun isBrokenAtLine(bmp1: Int, bmp2: Int):Boolean {
		numThisBroken = 0
		val yIndex = HALF_HEIGHT * WIDTH
		
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
		const val WIDTH = 1280
		const val HEIGHT = 720
		const val SENSITIVITY = 1097 //  195075 * p^2
		const val HALF_HEIGHT = HEIGHT/2
		const val MIN_LINE_BROKEN_FOR_REGISTER = (WIDTH * .15).toInt()
		const val IMG_SKIP_MIN = .1
		const val NANO_OVER_MILLI = 1E6.toLong()
		const val FACTOR_MIN_BROKEN_FOR_BODY = .7
		const val MAX_IMAGES_CONCURRENT = 1
	}
}