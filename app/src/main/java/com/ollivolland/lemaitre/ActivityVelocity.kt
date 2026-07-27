package com.ollivolland.lemaitre

import Perspective
import Vec
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.chrisbanes.photoview.PhotoView
import com.github.chrisbanes.photoview.PhotoViewAttacher
import datas.Session
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

class ActivityVelocity : Activity() {
    var width : Int = -1
    var height : Int = -1
    lateinit var image: PhotoView
    lateinit var zoomer: PhotoView
    lateinit var zoomerLayout: RelativeLayout
    lateinit var text: TextView
    lateinit var textDesc: TextView
    lateinit var layout: RelativeLayout
    lateinit var toucher: View
    var p:Perspective = Perspective()
    var iDisplay = -1
    var iLast = 0
    var send:(()-> Unit)? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_velocity)

        image = findViewById(R.id.velocity_image)
        layout = findViewById(R.id.velocity_main)
        zoomer = findViewById(R.id.velocity_zoomerimage)
        zoomerLayout = findViewById(R.id.velocity_zoomer)
        text = findViewById(R.id.velocity_text)
        textDesc = findViewById(R.id.velocity_desc)
        toucher = findViewById(R.id.velocity_toucher)
        textInfo = text

        findViewById<ImageButton>(R.id.velocity_b1).setOnClickListener {
            iDisplay = (iDisplay-60).coerceIn(-2, iLast-1)
            send?.invoke()
        }
//        findViewById<ImageButton>(R.id.velocity_b2).setOnClickListener {
//            iDisplay = (iDisplay-1).coerceIn(-2, iLast-1)
//            send?.invoke()
//        }
        var b2Runnable: Thread? = null
        findViewById<ImageButton>(R.id.velocity_b2).setOnTouchListener { v,ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    b2Runnable?.interrupt()
                    b2Runnable = Thread {
                        try {
                            iDisplay = (iDisplay - 1).coerceIn(-2, iLast - 1)
                            send?.invoke()

                            Thread.sleep(500)

                            while (b2Runnable?.isInterrupted == false) {
                                iDisplay = (iDisplay - 1).coerceIn(-2, iLast - 1)
                                send?.invoke()
                            }
                        } catch (e: InterruptedException) {
                        }
                    }
                    b2Runnable.start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    b2Runnable?.interrupt()
//                    v.performClick(); // Necessary for accessibility compliance
                }
                else -> return@setOnTouchListener false
            }
            return@setOnTouchListener true
        }
        var f2Runnable: Thread? = null
        findViewById<ImageButton>(R.id.velocity_f2).setOnTouchListener { v,ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    f2Runnable?.interrupt()
                    f2Runnable = Thread {
                        try {
                            iDisplay = (iDisplay + 1).coerceIn(-2, iLast - 1)
                            send?.invoke()

                            Thread.sleep(500)

                            while (f2Runnable?.isInterrupted == false) {
                                iDisplay = (iDisplay + 1).coerceIn(-2, iLast - 1)
                                send?.invoke()
                            }
                        } catch (e: InterruptedException) {
                        }
                    }
                    f2Runnable.start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    f2Runnable?.interrupt()
//                    v.performClick(); // Necessary for accessibility compliance
                }
                else -> return@setOnTouchListener false
            }
            return@setOnTouchListener true
        }
        findViewById<ImageButton>(R.id.velocity_f1).setOnClickListener {
            iDisplay = (iDisplay+60).coerceIn(-2, iLast-1)
            send?.invoke()
        }
        findViewById<ImageButton>(R.id.velocity_lock).setOnClickListener {
            iDisplay = -2
            send?.invoke()
        }

        image.maximumScale = 100f
        zoomer.isZoomable = false
        zoomer.maximumScale = 20f

        handleVideoIntent(intent)
    }


    override fun onDestroy() {
        super.onDestroy()

        textInfo = null
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun handleVideoIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        OpenCVLoader.initDebug()

        val times = getTimes(uri)
        iLast = times.size

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        var f = 0
        var bmpFrame: Bitmap? = null
        val srcMat = Mat()
        val convMat = Mat()
        val accMat = Mat.zeros(Size(Point(height.toDouble(), width.toDouble())), CvType.CV_16UC4)
        val accMat2 = Mat.zeros(Size(Point(height.toDouble(), width.toDouble())), CvType.CV_16UC4)
        val timeStart = SystemClock.elapsedRealtime()
        Core.setNumThreads(8)
        val MAX = 100
        while (f < min(times.size, MAX)) {
            bmpFrame = retriever.getFrameAtIndex(f)
            Utils.bitmapToMat(bmpFrame, srcMat)

            srcMat.convertTo(convMat, CvType.CV_16UC4)
            Core.add(accMat, convMat, accMat)

            f++
            if(f % 256 == 0 || f == min(times.size, MAX)) {
                invalidateInfos("accumulated ${ ((f-1)%256)} frames")
                Core.multiply(accMat, Scalar.all(1.0 / 256.0), accMat)
                Core.add(accMat2, accMat, accMat2)
                accMat.setTo(Scalar.all(0.0))
                assert(f < 256 * 256)
            }
//            srcMat.release()
        }
        Session.log("process2 took ${SystemClock.elapsedRealtime()-timeStart}")

        Core.multiply(accMat2, Scalar.all(1.0 / (f / 256.0)), accMat2)

        val matAverage = Mat(width, height, CvType.CV_8UC4)
        accMat2.convertTo(matAverage, CvType.CV_8UC4)
        Core.rotate(matAverage, matAverage, Core.ROTATE_90_COUNTERCLOCKWISE)
        val kernelSize = Size(9.0, 9.0)
        val matAverageBlurred = Mat()
        Imgproc.GaussianBlur(matAverage, matAverageBlurred, kernelSize, 0.0)

        val bmpImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bmpZoomer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)


        Utils.matToBitmap(matAverage, bmpZoomer)
        zoomer.setImageBitmap(bmpZoomer)
//        val zoomerBaseMatrixInverse = Matrix()    //  doesnt work, stays identity
//        zoomerBaseMatrixInverse.reset()
//        zoomerimage.setSuppMatrix(zoomerBaseMatrixInverse)
//        zoomerimage.getDisplayMatrix(zoomerBaseMatrixInverse)
//        zoomerBaseMatrixInverse.invert(zoomerBaseMatrixInverse)
        zoomerLayout.visibility = View.GONE

        fun send1() {
            val matUse = Mat()

            if(iDisplay == -1)
                matAverage.copyTo(matUse)
            else if(iDisplay == -2) {
                bmpFrame = retriever.getFrameAtIndex(350)
                Utils.bitmapToMat(bmpFrame, matUse)
                Core.rotate(matUse, matUse, Core.ROTATE_90_COUNTERCLOCKWISE)
                Imgproc.GaussianBlur(matUse, matUse, kernelSize, 0.0)
                Core.absdiff(matAverage, matUse, matUse)
                Imgproc.cvtColor(matUse, matUse, Imgproc.COLOR_BGR2GRAY)
//                Imgproc.equalizeHist(matUse, matUse)

//                Imgproc.Sobel(matUse, matUse, CvType.CV_8UC1, 1, 1, 7)
//                matUse.convertTo(matUse, CvType.CV_8UC1)

//                Imgproc.cvtColor(matUse, matUse, Imgproc.COLOR_BGR2GRAY)
                Imgproc.threshold(matUse, matUse, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
//                Imgproc.Canny(matUse, matUse, 20.0, 150.0)

                // 5. Find the contours
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat() // Holds structural information about nested shapes

                Imgproc.findContours(
                    matUse,
                    contours,
                    hierarchy,
                    Imgproc.RETR_TREE, // Gets ONLY the outermost outlines
                    Imgproc.CHAIN_APPROX_SIMPLE // Compresses horizontal/vertical/diagonal segments
                )

                Imgproc.cvtColor(matUse, matUse, Imgproc.COLOR_GRAY2RGB)
                Imgproc.drawContours(
                    matUse,
                    contours,
                    -1, // -1 means draw ALL contours in the list
                    Scalar(0.0, 0.0, 255.0), // Outline color: Green (BGR format)
                    2 // Thickness of the outline
                )
            }
            else {
                bmpFrame = retriever.getFrameAtIndex(iDisplay)
                Utils.bitmapToMat(bmpFrame, matUse)
                Core.rotate(matUse, matUse, Core.ROTATE_90_COUNTERCLOCKWISE)
                runOnUiThread { textDesc.text = "f=$iDisplay at ${"%.3f".format(times[iDisplay] * 1E-6)}s" }
            }

            for (i in p.markerPositions.indices) {
                Imgproc.circle(matUse, Point(p.markerPositions[i][0].toDouble(), p.markerPositions[i][1].toDouble()), 4, Scalar(255.0, 0.0, 0.0, 255.0), Imgproc.FILLED)
                Imgproc.putText(matUse, "${p.markerDistances[i]}m", Point(p.markerPositions[i][0].toDouble(), p.markerPositions[i][1].toDouble() - 50.0), Imgproc.FONT_HERSHEY_PLAIN, 1.0, Scalar(255.0, 0.0, 0.0, 255.0), 2)
            }

            Utils.matToBitmap(matUse, bmpImage)
            runOnUiThread { image.setImageBitmap(bmpImage) }
            matUse.release()
        }
        this.send = { send1() }
        send1()

        val attacher = PhotoViewAttacher(image)
        attacher.maximumScale = 20f
        val imageDisplayMatrix = Matrix()
        fun inverse(v:Vec<Int>):Vec<Int> {
            return Vec.create(
                ((v[0] - attacher.displayRect.left) / attacher.displayRect.width() * width).toInt(),
                ((v[1] - attacher.displayRect.top) / attacher.displayRect.height() * height).toInt()
            )
        }
        fun map(v:Vec<Int>):Vec<Int> {
            return Vec.create(
                ((v[0].toDouble() / width) * attacher.displayRect.width() + attacher.displayRect.left).toInt(),
                ((v[1].toDouble() / height) * attacher.displayRect.height() + attacher.displayRect.top).toInt()
            )
        }

        attacher.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(p0: MotionEvent): Boolean {
                return false
            }

            override fun onDoubleTap(p0: MotionEvent): Boolean {
                val v = inverse(Vec.create(p0.x.toInt(), p0.y.toInt()))
                val selectedMarker = p.markerPositions.indices.firstOrNull { (p.markerPositions[it] - v).ls<Int>() < 100*100 } ?: -1
                if(selectedMarker >= 1) {
                    val taskEditText = EditText(layout.context)
                    val dialog = AlertDialog.Builder(layout.context)
                        .setTitle("Change Distance?")
                        .setMessage("Enter new Distance")
                        .setView(taskEditText)
                        .setPositiveButton("Change") { dialog, which ->
                            val task: String = taskEditText.text.toString()
                            val int = task.toIntOrNull()
                            if(int != null) {
                                p.markerDistances[selectedMarker] = int
                                p = Perspective(p.markerDistances)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .create().show()
                }
                invalidateInfos("onDoubleTap ${v} ${p.getDistance(v)} ${p.getPixelAtPositionF(p.getDistance(v))}")
                return false
            }

            override fun onDoubleTapEvent(p0: MotionEvent): Boolean = false
        })

        var selectedMarker = -1
        toucher.setOnTouchListener { view, ev ->
            var m = Matrix()
//            zoomerimage.getSuppMatrix(m)
//            m.getValues(values)
//            invalidateInfos("supp = " + values.joinToString { "%.2f".format(it) })
//            zoomerimage.getDisplayMatrix(m)
//            m.getValues(values)
//            invalidateInfos("disp = " + values.joinToString { "%.2f".format(it) })
//            zoomerBaseMatrixInverse.getValues(values)
//            invalidateInfos("inv = " + values.joinToString { "%.2f".format(it) })

            if(ev.action == MotionEvent.ACTION_DOWN) {
                val v = inverse(Vec.create(ev.x.toInt(), ev.y.toInt()))
                selectedMarker = p.markerPositions.indices.firstOrNull { (p.markerPositions[it] - v).ls<Int>() < 50*50 } ?: -1
                if(selectedMarker >= 0) {
                    invalidateInfos("down $v ${p.markerPositions[selectedMarker]} [$selectedMarker]")
                    attacher.getSuppMatrix(imageDisplayMatrix)
                    attacher.isZoomable = false
                    image.isZoomable = false
                    attacher.setDisplayMatrix(imageDisplayMatrix)

                    zoomerLayout.visibility = View.VISIBLE
                }
                else {
                    invalidateInfos("down ${"%.2f".format(p.getDistance(v))} m")// = ${"%.2f".formatV(p)}"
                }
            }

            if(ev.action == MotionEvent.ACTION_MOVE && selectedMarker >= 0) {
                val v = inverse(Vec.create(ev.x.toInt(), ev.y.toInt()))
                v.array[0] = v[0].coerceIn(0, width)
                v.array[1] = v[1].coerceIn(0, height)
                p.markerPositions[selectedMarker] = v
                send1()
                attacher.setDisplayMatrix(imageDisplayMatrix)

                m.reset()
                zoomer.setSuppMatrix(m)
                zoomer.getDisplayMatrix(m)
                m.invert(m)

                val zoom = 10f
                m.set(m)
                m.postTranslate(-v[0].toFloat(), -v[1].toFloat())
                m.postScale(zoom, zoom)
                m.postTranslate(zoomer.width/2f, zoomer.height/2f)
                zoomer.setSuppMatrix(m)
            }

            if(ev.action == MotionEvent.ACTION_UP) {
                if(selectedMarker >= 0) {
                    invalidateInfos("up")
                    image.isZoomable = true
                    attacher.isZoomable = true
                    attacher.setDisplayMatrix(imageDisplayMatrix)

                    zoomerLayout.visibility = View.GONE
                    p = Perspective(null, p.markerPositions)
                    invalidateInfos("z = ${"%.2f".format(p.z)}")
                    invalidateInfos("c = ${"%.2f".format(p.c)} => ${if(p.c < 1) "start" else "finish"} closer")
                }
                selectedMarker = -1
            }

            return@setOnTouchListener (selectedMarker >= 0)
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


    companion object {
        val infos = mutableListOf<String>()
        var textInfo: TextView? = null

        fun invalidateInfos(s: String) {
            infos.add(s)
            textInfo?.text = infos.reversed().take(20).joinToString("\n")
        }
    }
}