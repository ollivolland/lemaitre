package mycamera2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer


class MyYubToRgb {
    companion object {
        fun yuvtorgb(image: Image, bitmap: Bitmap, context:Context) {
            yuvtorgb(imageToByteBuffer(image), bitmap, context)
        }


        private var rs: RenderScript? = null
        private var scriptYuvToRgb: ScriptIntrinsicYuvToRGB? = null
        fun yuvtorgb(yuvBytes: ByteArray, bitmap: Bitmap, context:Context) {
            // Convert YUV to RGB
            if(rs == null)
                rs = RenderScript.create(context)
            val allocationRgb = Allocation.createFromBitmap(rs, bitmap)
            val allocationYuv = Allocation.createSized(rs, Element.U8(rs), yuvBytes.size)
            allocationYuv.copyFrom(yuvBytes)

            if(scriptYuvToRgb == null)
                scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
            scriptYuvToRgb!!.setInput(allocationYuv)
            scriptYuvToRgb!!.forEach(allocationRgb)

            //	back
            allocationRgb.copyTo(bitmap)

            // Release
            allocationYuv.destroy()
            allocationRgb.destroy()
//            rs.destroy()
        }


//        fun YUVToRGB(image: Image): Bitmap? {
//            assert(image.format == ImageFormat.YUV_420_888)
//
//            // NV21 is a plane of 8 bit Y values followed by interleaved  Cb Cr
//            val ib = ByteBuffer.allocate(image.height * image.width * 2)
//
//            val y = image.planes[0].buffer
//            val cr = image.planes[1].buffer
//            val cb = image.planes[2].buffer
//            ib.put(y)
//            ib.put(cb)
//            ib.put(cr)
//
//            return YUVToRGB(ib.array(), image.width, image.height)
//        }

        fun YUVToRGB(byteArray: ByteArray, bmp: Bitmap) {

            val yuvImage = YuvImage(byteArray, ImageFormat.NV21, bmp.width, bmp.height, null)

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0,bmp.width, bmp.height), 100, out)
            val buff = ByteBuffer.wrap(out.toByteArray())
            buff.rewind()
            bmp.copyPixelsFromBuffer(buff)
        }


        fun imageToByteBuffer(image: Image): ByteArray {
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()
            val planes = image.planes
            val rowData = ByteArray(planes[0].rowStride)
            val bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8
            val output = ByteArray(bufferSize)
            var channelOffset: Int
            var outputStride: Int
            for (planeIndex in 0..2) {
                //NV12 / NV21
                channelOffset = when(planeIndex) {
                    0 -> 0
                    1 -> width * height + 1
                    else -> width * height
                }
                outputStride = if(planeIndex == 0) 1 else 2
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
                        buffer[output, channelOffset, length]
                        channelOffset += length
                    } else {
                        length = (widthShifted - 1) * pixelStride + 1
                        buffer[rowData, 0, length]
                        for (col in 0 until widthShifted) {
                            output[channelOffset] = rowData[col * pixelStride]
                            channelOffset += outputStride
                        }
                    }
                    if (row < heightShifted - 1) buffer.position(buffer.position() + rowStride - length)
                }
            }
            return output
        }


        fun swapUV(byteArr: ByteArray, size: Int) {
            var swap: Byte
            var i = size
            val sizeYUV = size * 3 / 2
            while (i < sizeYUV) {
                swap = byteArr[i]
                byteArr[i] = byteArr[i + 1]
                byteArr[i + 1] = swap
                i+=2
            }
        }
    }
}