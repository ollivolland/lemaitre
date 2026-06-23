package com.ollivolland.lemaitre

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.github.chrisbanes.photoview.PhotoView
import datas.Session
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core.flip
import org.opencv.core.Core.transpose
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

class ActivityVelocity : Activity() {
    var width : Int = -1
    var height : Int = -1
    lateinit var image: PhotoView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_velocity)

        image = findViewById<PhotoView>(R.id.velocity_image)
        image.maximumScale = 10f

        handleVideoIntent(intent)
    }


    private fun handleVideoIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        OpenCVLoader.initDebug()

        val times = getTimes(uri)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        var f = 0
        var bmp2: Bitmap? = null
        val srcMat = Mat()
        val convMat = Mat()
        val accMat = Mat.zeros(Size(Point(height.toDouble(), width.toDouble())), CvType.CV_16UC4)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val timeStart = SystemClock.elapsedRealtime()
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


        transpose(mat, mat)
        flip(mat, mat,0)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)

        image.setImageBitmap(bmp)
        image.setOnPhotoTapListener { _, x, y ->
            val values = FloatArray(9)
            image.matrix.getValues(values)
            val relativeX: Float = (x - values[2]) / values[0] * width
            val relativeY: Float = (y - values[5]) / values[4] * height
            Imgproc.circle(mat, Point(relativeX.toDouble(), relativeY.toDouble()), 4, Scalar(255.0, 0.0, 0.0, 255.0), Imgproc.FILLED)
            Utils.matToBitmap(mat, bmp)
            image.setImageBitmap(bmp)
            println("drew circle to $relativeX, $relativeY")
        }
    }


    private fun getTimes(uri: Uri): Array<Long> {
        val extractor = MediaExtractor()
        extractor.setDataSource(contentResolver.openFileDescriptor(uri, "r")!!.fileDescriptor)

        val videoTrackIndex: Int = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.contains("video") ?: false }!!

        val format = extractor.getTrackFormat(videoTrackIndex)
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)

        extractor.selectTrack(videoTrackIndex)

        val times = mutableListOf<Long>()
        while (extractor.sampleTime >= 0) {
            val presentationTimeUs = extractor.sampleTime
            times.add(presentationTimeUs)
            extractor.advance()
        }


        return times.toTypedArray()
    }
}