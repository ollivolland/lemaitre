
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import datas.Session
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.flip
import org.opencv.core.Core.transpose
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min


class Velocity {
    companion object {
        fun extract(context: Context, uri: String) {

            if (!OpenCVLoader.initDebug())
                println("Unable to load OpenCV")
            else
                println("OpenCV loaded")

            //  TODO requires managment permission
            if(!File(uri).exists())
                throw Exception()


            val extractor = MediaExtractor()
            extractor.setDataSource(uri)


            val ii = IntArray(2)
            val videoTrackIndex: Int = ii[0]
            var format: MediaFormat? = null
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                format = extractor.getTrackFormat(i)
                mime = format.getString(MediaFormat.KEY_MIME)
//                    Log.d(TAG, "track " + i + " : key_mime = " + mime)
                if (mime == null)
                    continue
                if (mime.contains("video")) ii[0] = i
                else if (mime.contains("audio")) ii[1] = i
            }

            extractor.selectTrack(videoTrackIndex)

            val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
            println("try to create a codec mime=$mime codecName=$codecName")
            var codec: MediaCodec = if (codecName != null) MediaCodec.createByCodecName(codecName) else MediaCodec.createDecoderByType(mime!!) // may be throw IllegalArgumentException
//            val codec: MediaCodec = MediaCodec.createByCodecName("OMX.google.mpeg2.decoder") // may be throw IllegalArgumentException

            format!!.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)  //  -> image
            codec.configure(format, null, null, 0)
            Session.log("colorformats: " + codec.codecInfo.getCapabilitiesForType(mime).colorFormats.joinToString())

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val size = width*height
            val sizeYUV = size * 3 / 2
            val sizeUV = size / 4

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val arrayFrameCopy = ByteArray(sizeYUV)
            val summationArray = Array<Long>(sizeYUV) { 0 }
            var frameCount = 0
            var timeStart = SystemClock.elapsedRealtime()
            val times = mutableListOf<Long>()
            val END = 1


            fun onFinish() {
                Session.log("process took ${SystemClock.elapsedRealtime()-timeStart}")
                codec.stop()
                codec.release()

                // TODO rewrite into functions, test sync / async
                // TODO fix image topline issues

//                Session.log("processing end")
//                val arrayYUV = ByteArray(sizeYUV)
//                for (i in 0 until sizeYUV)
//                    arrayYUV[i] = (summationArray[i] / frameCount).toByte()
//                for (i in 0 until size)
//                    arrayYUV[i] = 127.toByte()
////                for (i in size until sizeYUV)
////                    arrayYUV[i] = 127.toByte()
//                MyYubToRgb.swapUV(arrayYUV, size)
//                MyYubToRgb.yuvtorgb(arrayYUV, bmp, context)
//                File(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png").createNewFile()
//                FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png")
//                    .use { out -> bmp.compress(Bitmap.CompressFormat.PNG,100, out) }
            }
            fun onInputBuffer(i:Int): Boolean {
                if (i <= 0) return true
                val inputBuffer = codec.getInputBuffer(i) ?: return false
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                val presentationTimeUs = extractor.sampleTime
                times.add(presentationTimeUs)
                val flags = extractor.sampleFlags

//                if(frameCount == END) {
//                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
////                    onFinish()    //  disable in snyc mode
//                    return false
//                }

                codec.queueInputBuffer(i, 0, sampleSize, presentationTimeUs, flags)
                extractor.advance()
                return true
            }
            fun onOutputBuffer(i:Int) {
                if (i <= 0 || frameCount >= END) return

                // process
                Session.log("processing $frameCount")

                frameCount++
                val img = codec.getOutputImage(i)!!
//                var i = 0
//                img.planes[0].buffer.rewind()
//                img.planes[0].buffer.get(arrayFrameCopy, 0, size)
//                img.planes[1].buffer.rewind()
//                img.planes[1].buffer.get(arrayFrameCopy, size, size / 2)
//                while (i < sizeYUV)
//                    summationArray[i] = summationArray[i] + arrayFrameCopy[i++].toLong()
//
//                if(frameCount == 1) {
//                    MyYubToRgb.swapUV(arrayFrameCopy, size)
//                    MyYubToRgb.yuvtorgb(arrayFrameCopy, bmp, context)
//                    File(Environment.getExternalStorageDirectory().absolutePath + "/Download/f$frameCount.png").createNewFile()
//                    FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/f$frameCount.png")
//                        .use { out -> bmp.compress(Bitmap.CompressFormat.PNG,100, out) }
//                }
//
                img.close()
                codec.releaseOutputBuffer(i, false)
            }

//            codec.setCallback(object : MediaCodec.Callback() {
//                override fun onInputBufferAvailable(p0: MediaCodec, i: Int) { onInputBuffer(i) }
//
//                override fun onOutputBufferAvailable(p0: MediaCodec, i: Int, p2: MediaCodec.BufferInfo) { onOutputBuffer(i) }
//
//                override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) = Unit
//
//                override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) { format = p1 }
//            })


//            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
//            codec.start()
//
//
//            val bufferInfo = MediaCodec.BufferInfo()
//            while (true) {
//                val inputBufferId = codec.dequeueInputBuffer(1_000_000)
//                if(!onInputBuffer(inputBufferId)) break
//
//                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1_000_000)
//                onOutputBuffer(outputBufferId)
//                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
//                    format = codec.outputFormat
//            }

//            onFinish()

            while (extractor.sampleTime >= 0) {
                val presentationTimeUs = extractor.sampleTime
                times.add(presentationTimeUs)
                extractor.advance()
            }

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.fromFile(File(uri)))
            var f = 0
            var bmp2: Bitmap? = null
            val srcMat = Mat()
            val longMat = Mat()
            val convMat = Mat()
            val accMat = Mat.zeros(Size(Point(height.toDouble(), width.toDouble())), CvType.CV_16UC4)
            val shortArray = ShortArray(size*4)
            accMat.put(0,0, shortArray)
            val longArray = Array<Long>(size*3) { 0L }
            val byteArray = ByteArray(size*4)
//            var pix: Mat.Atable<Byte>?
            var pix: Mat.Tuple4<Byte>?
            var ind = 0

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            timeStart = SystemClock.elapsedRealtime()
            while (f < min(times.size,50)) {
                bmp2 = retriever.getFrameAtIndex(f)
                Utils.bitmapToMat(bmp2, srcMat)

                srcMat.convertTo(convMat, CvType.CV_16UC4)
                org.opencv.core.Core.add(accMat, convMat, accMat)

                f++
                assert(f < 256)
            }
            Session.log("process2 took ${SystemClock.elapsedRealtime()-timeStart}")

            org.opencv.core.Core.multiply(accMat, Scalar.all(1.0 / f), accMat)

            val mat = Mat(width, height, CvType.CV_8UC4)
            accMat.convertTo(mat, CvType.CV_8UC4)
//            mat.put(0, 0, byteArray)

//            transpose(mat, mat)
//            flip(mat, mat,1)

            transpose(mat, mat)
            flip(mat, mat,0)

            Utils.matToBitmap(mat, bmp)
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/acc.png").createNewFile()
            FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/acc.png")
                .use { out -> bmp!!.compress(Bitmap.CompressFormat.PNG,100, out) }


            val blurSize = 9.0
            Imgproc.GaussianBlur(mat,mat, Size(blurSize, blurSize),0.0)


            var copy = Mat()
            Imgproc.Canny(mat, copy, 20.0, 50.0)
            Utils.matToBitmap(copy, bmp)
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/canny.png").createNewFile()
            FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/canny.png")
                .use { out -> bmp!!.compress(Bitmap.CompressFormat.PNG,100, out) }

            var dx = Mat()
            var dy = Mat()
            val ksize = 3
            Imgproc.Sobel(mat, dx, CvType.CV_64F, 1, 0, ksize)
//            Imgproc.Sobel(dx, dx, CvType.CV_64F, 1, 0, ksize)
            Imgproc.Sobel(mat, dy, CvType.CV_64F, 0, 1, ksize)





//            Core.addWeighted(dx, 0.5, dy, 0.5, 0.0, copy)
            Core.convertScaleAbs(dx, dx)
            Core.convertScaleAbs(dy, dy)


            val ch = Mat()
            Core.extractChannel(dy, ch, 0)
//            val sm = Mat()
//            Imgproc.resize(ch, sm, Size(50.0, 50.0))
//            Imgproc.resize(sm, sm, ch.size())
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch.png", ch)
            val lsd = Imgproc.createLineSegmentDetector()
            val lines = Mat()
            data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
                val m: Double = if(x0 == x1) Double.POSITIVE_INFINITY else ((y1 - y0) / (x1 - x0)).toDouble()
                val intercept: Double = y0 - m * x0
                val lengthSquared: Long = ((x1-x0)*(x1-x0) + (y1-y0)*(y1-y0)).toLong()
            }
            val combined = mutableListOf<MutableList<Line>>()
            lsd.detect(ch, lines)
            for (i in 0 until lines.rows()) {
                val lineArray = FloatArray(4)
                lines.get(i, 0, lineArray)
                var line:Line? = Line(lineArray[0], lineArray[1], lineArray[2], lineArray[3])
                combined.add(mutableListOf(line!!))
            }
            var isHasChanged = true
            val tolerance = 1E-3
            while (isHasChanged) {
                isHasChanged = false
                outer@ for (line in combined.flatten())
                    for (to in combined.sortedByDescending { it.sumOf { it.lengthSquared } })
                        if(abs(atan(to.sumOf { it.m } / to.size) - atan(line.m)) < PI*tolerance && abs(to.sumOf { it.intercept } / to.size - line.intercept) < height*tolerance) {
                            val from = combined.first { it.contains(line) }
                            if(from != to) {
                                from.remove(line)
                                to.add(line)
                                if(from.isEmpty())
                                    combined.remove(from)
                                isHasChanged = true
                                @outer break
                            }
                            break
                        }
            }

            var i = 0
            val longestLength = combined.maxOf { it.sumOf { it.lengthSquared } }
            combined.removeAll { it.sumOf { it.lengthSquared } < longestLength * 1E-2 }
            val linesCombined = Mat(combined.size, 4, CvType.CV_32F)
            linesCombined.setTo(Scalar.all(0.0))
            combined.forEach {
                val minx = it.minOf { it.x0 }
                val maxx = it.maxOf { it.x1 }
                val intercept = it.sumOf { it.intercept.toLong() } / it.size
                val m = (it.sumOf { it.m } / it.size).toFloat()
                linesCombined.put(i++, 0, floatArrayOf(minx, intercept + minx * m, maxx, intercept + maxx * m))
            }
            val longest = combined.maxBy { it.sumOf { it.lengthSquared } }
            lsd.drawSegments(ch, linesCombined)
//            lsd.drawSegments(ch, lines)
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch.png", ch)


            val center = Point((mat.width() / 2).toDouble(), (mat.height() / 2).toDouble())
            val rotationMatrix = Imgproc.getRotationMatrix2D(center, atan(longest.sumOf { it.m } / longest.size) / PI * 180, 1.0)
            Imgproc.warpAffine(ch, ch, rotationMatrix, mat.size())
            val p = longest.sumOf { it.intercept } / longest.size + ch.width() / 2 * longest.sumOf { it.m } / longest.size
            Imgproc.line(ch, Point(0.0,p),Point(ch.width().toDouble(),p), Scalar(0.0, 255.0, 0.0, 255.0))
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch2.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/ch2.png", ch)


            Imgproc.cvtColor(dx, copy, Imgproc.COLOR_BGR2GRAY)
            val ys = mutableListOf<Scalar>()
            for (y in 0 until height) {
                ys.add(Core.sumElems(dy.row(y)))
            }
            Session.log("${ys.indices.maxBy { ys[it].`val`[0] }}")


            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/sobeldx.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/sobeldx.png", copy)
            Imgproc.cvtColor(dy, copy, Imgproc.COLOR_BGR2GRAY)
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/sobeldy.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/sobeldy.png", copy)


            Imgproc.warpAffine(mat, mat, rotationMatrix, mat.size())
            Imgproc.line(mat, Point(0.0,p),Point(ch.width().toDouble(),p), Scalar(0.0, 0.0, 255.0, 255.0))
            File(Environment.getExternalStorageDirectory().absolutePath + "/Download/acc-rot.png").createNewFile()
            Imgcodecs.imwrite(Environment.getExternalStorageDirectory().absolutePath + "/Download/acc-rot.png", mat)

            rotationMatrix.release()
            retriever.release()
        }
    }
}