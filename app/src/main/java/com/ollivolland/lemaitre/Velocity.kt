
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import datas.Session
import mycamera2.MyYubToRgb
import java.io.File
import java.io.FileOutputStream
import java.nio.IntBuffer


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
            for (i in 0..extractor.trackCount-1) {
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

            codec!!.configure(format, null, null, 0)
            format!!.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            val width = format!!.getInteger(MediaFormat.KEY_WIDTH)
            val height = format!!.getInteger(MediaFormat.KEY_HEIGHT)
            val size = width*height

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val buffer = IntBuffer.allocate(bmp.byteCount)
            val longarr = Array<Long>(size*3) { 0 }
            val intarr = IntArray(size)
            var frameCount = 0


            codec.setCallback(object : MediaCodec.Callback() {
                private var isInputDone = false

                override fun onInputBufferAvailable(p0: MediaCodec, index: Int) {
                    if (isInputDone) return

                    val inputBuffer = codec.getInputBuffer(index) ?: return
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    val presentationTimeUs = extractor.sampleTime
                    val flags = extractor.sampleFlags
//
                    Session.log("queueInputBuffer $index")
                    codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, flags)
                    extractor.advance()
                }

                override fun onOutputBufferAvailable(
                    p0: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
//                    if (isOutputDone) return
//                    isOutputDone = true
//                    isInputDone = true

                    // 4. Convert the very first decoded buffer to a Bitmap
                    val image: Image? = codec.getOutputImage(index)
                    Session.log("onOutputBufferAvailable ${info.presentationTimeUs}")
                    MyYubToRgb.yuvtorgb(image!!, bmp, context)
                    buffer.position(0)
                    bmp.copyPixelsToBuffer(buffer)

                    for (u in 0..2){
                        val offset = (2-u)*size
                        val shift = u*8
                        for (i in 0..size-1)
                            longarr[offset + i] = longarr[offset + i] + ((buffer[i] shr shift) and 0xff)
                    }

                    frameCount++


                    if(frameCount == 100) {
                        isInputDone = true

                        for (i in 0..size - 1)
                            intarr[i] = (0xff000000 or ((longarr[2 * size + i] / frameCount) shl 16) or ((longarr[1 * size + i] / frameCount) shl 8) or ((longarr[i] / frameCount) shl 0)).toInt()

                        MyYubToRgb.yuvtorgb(image!!, bmp, context)
                        buffer.position(0)
                        bmp.copyPixelsToBuffer(buffer)
                        bmp.setPixels(intarr, 0, width, 0, 0, width, height)
                        File(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png").createNewFile()
                        FileOutputStream(Environment.getExternalStorageDirectory().absolutePath + "/Download/bmp.png").use { out ->
                            bmp.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                out
                            ) // bmp is your Bitmap instance
                        }
                        Session.log("WROTE")
                    }

                    image!!.close()

                    // 5. Clean up resources immediately
                    codec.releaseOutputBuffer(index, false)
//                    cleanup(codec, extractor)
                }

                override fun onError(
                    p0: MediaCodec,
                    p1: MediaCodec.CodecException
                ) {
                }

                override fun onOutputFormatChanged(
                    p0: MediaCodec,
                    p1: MediaFormat
                ) {
                }

            })

            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.start()
        }
    }
}