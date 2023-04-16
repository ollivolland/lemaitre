import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ImageReader
import android.os.Environment
import mycamera2.MyCamera2
import mycamera2.MyReader
import mycamera2.YuvToRgbConverter
import java.io.File
import java.io.FileOutputStream
import java.lang.Integer.max
import java.nio.IntBuffer
import kotlin.concurrent.thread
import kotlin.math.min


class Analyzer(context: Context, myCamera2: MyCamera2, val myTimer: MyTimer, val timeStart:Long) {
    private val yuvToRgbConverter = YuvToRgbConverter(context)
	var index = 0
	var isWant = false
	val session = Globals.FORMAT_TIME_FILE.format(myTimer.time)
	var c1=0;var c2=0
	var r=0;var g=0; var b=0
	var numThisBroken = 0
	var numBuffered = 0
	var bitmap: Bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
	private val buffers = mutableListOf<IntBuffer?>()
	private val isBroken = mutableListOf<Boolean>()
	private val timesMs = mutableListOf<Long>()
	private val numBroken = mutableListOf<Int>()
	private val needed = mutableListOf<Int>()
	var isHasStreakStarted = false
	var streakMaxBroken = 0
	
	private val listenTo = ImageReader.OnImageAvailableListener {
		val image = it.acquireLatestImage() ?: return@OnImageAvailableListener
		
		if(isWant) {
			yuvToRgbConverter.yuvToRgb(image, bitmap)
			addBuffer()
			timesMs.add(image.timestamp / NANO_OVER_MILLI)
			
			if(index >= 2) {
				isBroken[index] = isBrokenAtLine(index, index-1, HALF_HEIGHT)
						|| isBrokenAtLine(index, index-1, HALF_HEIGHT - MULTILINE_DIFF)
						|| isBrokenAtLine(index, index-1, HALF_HEIGHT + MULTILINE_DIFF)
				numBroken[index] = numThisBroken
				
				if(!isHasStreakStarted && isBroken[index]) {
					isHasStreakStarted = true
					streakMaxBroken = 0
					needed.add(index)
					println("STREAK STARTED")
				}
				else if(isHasStreakStarted && !isBroken[index]) {
					isHasStreakStarted = false
					println("STREAK STOPPED")
				}
				
				if(isHasStreakStarted) {
					if(numBroken[index] > streakMaxBroken) {
						streakMaxBroken = numBroken[index]
						needed[needed.lastIndex] = index
					}
				}
				
				//  dealloc not needed
				for (notNeeded in buffers.indices.filterNot { i ->
					if(i >= index - 2) return@filterNot true
					
					for (x in needed)
						if(i >= x - 2 && i <= x) return@filterNot true
					
					return@filterNot false
				}) clearBuffer(notNeeded)
			}
			index++
		}
		
		image.close()
	}
	val myReader: MyReader = myCamera2.addReader(MyReader.ReaderProfileBuilder(), listenTo)
	
	init {
		File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/${Globals.DIR_NAME}/$session").mkdirs()
	}
	
	fun postProcessing() {
		thread {
			for (i in buffers.indices)
				if(i < buffers.size - 3 && buffers[i] != null && i > 2 && buffers[i-1] != null && buffers[i-2] != null) {
					println("BUFFERS got $i")
					val newBuffer = buffers[i]!!.array().copyOfRange(0, buffers[i]!!.capacity())
					
					//  bgr cause little indian
					var colorIndicator = 0xffff0000.toInt()
					val colorMid = 0xffffffff.toInt()
					var colorBroken = 0xff0000aa.toInt()
					
					val thisMaxBroken = numBroken[i]
					var yBroken = IntArray(WIDTH)
					var yIndex: Int
					var useI = i-1
					
					val lambda:(Int)->Boolean = { y ->
						yIndex = y*WIDTH
						
						for (x in 0 until WIDTH)
							if(isBroken(useI, useI-1, yIndex+x)) {
								yBroken[y]++
								newBuffer[yIndex+x] = colorBroken
							}
						
						(yBroken[y] < thisMaxBroken * IMG_SKIP_MIN)
					}
					
					//  *****   IMG -1
					//  diff
					for (y in (HALF_HEIGHT-1) downTo 0) if(lambda(y)) break
					for (y in (HALF_HEIGHT+1) until  HEIGHT) if(lambda(y)) break
					val lastYBroken = yBroken.clone()
					yBroken = IntArray(WIDTH)
					
					//  *****   IMG
					useI = i
					colorBroken = 0xff0000ff.toInt()
					for (y in (HALF_HEIGHT-1) downTo 0) if(lambda(y)) break
					for (y in (HALF_HEIGHT+1) until  HEIGHT) if(lambda(y)) break
					
					//  mid
					yIndex = HALF_HEIGHT*WIDTH
					for (x in 0 until WIDTH) newBuffer[yIndex+x] = colorMid
					yIndex = (HALF_HEIGHT-MULTILINE_DIFF)*WIDTH
					for (x in 0 until WIDTH) newBuffer[yIndex+x] = colorMid
					yIndex = (HALF_HEIGHT+MULTILINE_DIFF)*WIDTH
					for (x in 0 until WIDTH) newBuffer[yIndex+x] = colorMid
					
					var yFirstBroken = 0
					val drawDiagram:(IntArray)->Unit = { useyBroken ->
						//  diagram
						for (y in 1 until HEIGHT)
							for (x in max(min(useyBroken[y-1], useyBroken[y])-3, 0) until max(useyBroken[y-1], useyBroken[y]))
								newBuffer[y*WIDTH+WIDTH-x] = colorIndicator
						//  first broken
						val thisFullMostBroken = useyBroken.max()
						for (y in HEIGHT downTo 0)
							if (useyBroken[y] > thisFullMostBroken*FACTOR_MIN_BROKEN_FOR_BODY) {
								yFirstBroken = y
								break
							}
						yIndex = yFirstBroken*WIDTH
						for (x in 0 until WIDTH) newBuffer[yIndex+x] = colorIndicator
					}
					
					drawDiagram(lastYBroken)
					colorIndicator = 0xff00ff00.toInt()
					val yLastBroken = yFirstBroken
					drawDiagram(yBroken)
					
					//  time
					val frametimeAdj = myTimer.timeOfBoot + timesMs[i] - timeStart
					
					val delta = yFirstBroken - yLastBroken
					val deltaMS = timesMs[i] - timesMs[i-1]
					val numDeltas = (HALF_HEIGHT - yFirstBroken) / delta.toDouble()
					val adjustmentMs = (numDeltas * deltaMS).toLong()
					val midtimeAdj = frametimeAdj + adjustmentMs
					
					println("delta = $delta\n" +
						"deltaMS = $deltaMS\n" +
						"numDeltas = $numDeltas\n" +
						"adjustmentMs = $adjustmentMs")
					
					bitmap.copyPixelsFromBuffer(IntBuffer.wrap(newBuffer))
					val file = File("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/${Globals.DIR_NAME}/$session/" +
							"image_triangletime-${midtimeAdj/1000}-${String.format("%03d", midtimeAdj%1000)}-s" +
							"_frame-${i}_frametime-${frametimeAdj/1000}-${String.format("%03d", frametimeAdj%1000)}-s.jpg")
					FileOutputStream(file).use { out ->
						val matrix = Matrix()
						matrix.postRotate(90f)
						val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
						rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)   //  jpg is faster
						rotatedBitmap.recycle()
						out.flush()
					}
				}
			
			bitmap.recycle()
			for (i in buffers.indices)
				buffers[i] = null
		}
	}
	
	private fun clearBuffer(i:Int) {
		if(i < 0 || buffers[i] == null) return
		
		buffers[i] = null
		numBuffered--
	}
	
	private fun addBuffer() {
		val buffer = IntBuffer.allocate(bitmap.byteCount)
		bitmap.copyPixelsToBuffer(buffer)
		buffers.add(buffer)
		numBuffered++
		println("allocated buffer[$index] with ${buffer.capacity()}, $numBuffered")
		
		isBroken.add(false)
		numBroken.add(0)
		
		if(numBuffered > 15) {
			println("BUFFER OVERRUN")
			isWant = false
		}
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
		const val MIN_LINE_BROKEN_FOR_REGISTER = (WIDTH * .1).toInt()
		const val IMG_SKIP_MIN = .1
		const val NANO_OVER_MILLI = 1E6.toLong()
		const val FACTOR_MIN_BROKEN_FOR_BODY = .7
		const val MULTILINE_DIFF = 100
	}
}