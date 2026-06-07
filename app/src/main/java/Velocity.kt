
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.os.SystemClock
import datas.Session
import mycamera2.MyYubToRgb
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer


class Velocity {
    companion object {
        fun extract(context: Context, uri: String) {

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
            var codec: MediaCodec? = null
//            Log.d(TAG, "try to create a codec mime=" + mime + " codecName=" + codecName)
            if (codecName != null) codec = MediaCodec.createByCodecName(codecName)
            else if (mime != null) codec = MediaCodec.createDecoderByType(mime) // may be throw IllegalArgumentException

            format!!.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)  //  -> image
            codec!!.configure(format, null, null, 0)
            Session.log("colorformats: " + codec.codecInfo.getCapabilitiesForType(mime).colorFormats.joinToString())

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val size = width*height
            val sizeYUV = size * 3 / 2

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val arrayFrameCopy = ByteArray(sizeYUV)
            val summationArray = Array<Long>(sizeYUV) { 0 }
            var frameCount = 0
            var frameBuff: ByteBuffer
            val timeStart = SystemClock.elapsedRealtime()
            val times = mutableListOf<Long>()
            val END = 20


            fun onFinish() {
                val timeEnd = SystemClock.elapsedRealtime()
                Session.log("process took ${timeEnd-timeStart}")
                codec.stop()
                codec.release()

                // TODO rewrite into functions, test sync / async
                // TODO fix image topline issues

                Session.log("processing end")
                val arrayYUV = ByteArray(sizeYUV)
                for (i in 0 until sizeYUV)
                    arrayYUV[i] = (summationArray[i] / frameCount).toByte()
                for (i in 0 until size)
                    arrayYUV[i] = 127.toByte()
                MyYubToRgb.swapUV(arrayYUV, size)
                MyYubToRgb.yuvtorgb(arrayYUV, bmp, context)
                File(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png").createNewFile()
                FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png")
                    .use { out -> bmp.compress(Bitmap.CompressFormat.PNG,100, out) }
            }
            fun onInputBuffer(i:Int): Boolean {
                if (i <= 0) return true
                val inputBuffer = codec.getInputBuffer(i) ?: return false
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                val presentationTimeUs = extractor.sampleTime
                times.add(presentationTimeUs)
                val flags = extractor.sampleFlags

                if(frameCount == END) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
//                    onFinish()    //  disable in snyc mode
                    return false
                }

                codec.queueInputBuffer(i, 0, sampleSize, presentationTimeUs, flags)
                extractor.advance()
                return true
            }
            fun onOutputBuffer(i:Int) {
                if (i <= 0 || frameCount >= END) return

                // process
                Session.log("processing $frameCount")

                frameCount++
                frameBuff = codec.getOutputBuffer(i)!!
                frameBuff.rewind()
                synchronized(arrayFrameCopy) {
                    frameBuff.get(arrayFrameCopy)
                    synchronized(summationArray) {
                        for (i in 0 until sizeYUV)
                            summationArray[i] = summationArray[i] + arrayFrameCopy[i].toLong()
                    }
                }

//                MyYubToRgb.swapUV(arrayFrameCopy, size)
//                MyYubToRgb.yuvtorgb(arrayFrameCopy, bmp, context)
//                File(Environment.getExternalStorageDirectory().absolutePath + "/Download/f$frameCount.png").createNewFile()
//                FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/f$frameCount.png")
//                    .use { out -> bmp.compress(Bitmap.CompressFormat.PNG,100, out) }

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


            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.start()


            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val inputBufferId = codec.dequeueInputBuffer(1_000_000)
                if(!onInputBuffer(inputBufferId)) break

                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1_000_000)
                onOutputBuffer(outputBufferId)
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                    format = codec.outputFormat
            }

            onFinish()
        }
    }
}