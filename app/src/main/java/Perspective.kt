import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Perspective(val _markerDistances: Array<Int>? = null, val _markerPositions: Array<Vec<Int>>? = null) {
    val z: Double
    val c: Double
    val mMarker: Double
    val mTrack: Double
    val originTrack:Vec<Double>
    val vDir:Vec<Double>
    val vUp:Vec<Double>
    val markerDistances: Array<Int>
    val markerPositions: Array<Vec<Int>>


    init {
        val file = File("${Globals.dirAppStorage.absolutePath}/velocityDefaults.json")
        val jo = if(file.exists()) JSONObject(file.readText()) else JSONObject()

        val markerDistanceFallBack = arrayOf(0, 30, 50)
        markerDistances = _markerDistances ?: arrayOf(-1, -1, -1)
        for (i in 0 until 3) {
            if (markerDistances[i] == -1)
                markerDistances[i] = jo.optInt("m$i", -1)
            if(markerDistances[i] == -1)
                markerDistances[i] = markerDistanceFallBack[i]
        }

        val markerPositionsFallBack = Array<Vec<Int>>(3) { Vec.create(1920 * (1 + it) / 4, 1080 / 2) }
        markerPositions = _markerPositions ?: arrayOf(Vec.create(-1, -1), Vec.create(-1, -1), Vec.create(-1, -1))
        for (i in 0 until 3) {
            if (markerPositions[i][0] == -1 && jo.has("v$i"))
                markerPositions[i] = Vec.fromJson<Int>(jo.getJSONArray("v$i"))!!
            if(markerPositions[i][0] == -1)
                markerPositions[i] = markerPositionsFallBack[i]
        }

        for (i in 0 until 3) {
            jo.put("m$i", markerDistances[i])
            jo.put("v$i", JSONArray(markerPositions[i].array))
        }
        file.writeText(jo.toString())


        val markerAngles = markerPositions.map { pixelToAngle(it) }.toTypedArray()
        //  0 = Finish, 2 = Start
        val dFinish = markerDistances[0] - markerDistances[1]
        val dStart = markerDistances[2] - markerDistances[1]
        val oFinish = sqrt((markerAngles[0] - markerAngles[1]).ls())
        val oStart = sqrt((markerAngles[2] - markerAngles[1]).ls())
        vDir = (markerAngles[2] - markerAngles[0]) / sqrt((markerAngles[2] - markerAngles[0]).ls())
        vUp = Vec.create(-vDir[1], vDir[0]) * (if(vDir[1] > 0) 1.0 else -1.0)

        //  scale fin to start, c < 1 => fin closer
        c = abs((sin(oFinish) * dStart) / (sin(oStart) * dFinish))
        //  depth-difference
        val dd = c * cos(oStart) - cos(oFinish)
        val h = sqrt((sin(oFinish) + c * sin(oStart)).pow(2) + dd.pow(2))
        val s = abs(dFinish - dStart) / h
        z = asin(dd / h)
        mMarker = s * cos(oFinish) + dFinish * sin(z)

        //  set track origins as marker origin
        mTrack = mMarker
        originTrack = markerAngles[1]

        //  adjust origin from marker to track
//        originTrack = toAngle(getPixelAtPositionF(markerDistance[1], atHeight:0, zIncrease:markerZAdjustment));
//        mTrack = mMarker + markerZAdjustment * Math.Cos(z);

//        Debug.WriteLine($"c={c:n3}:{(c>1?"start closer":"fin closer")} z={z * RAD_TO_DEG:n3}°");
//        for(int i = 0; i < 3; i++)
//        Debug.WriteLine($"distance to marker {i} = {(markerDistance[i] - markerDistance[1]) * Math.Sin(z) + mMarker:n2}m");
//        Debug.WriteLine($"tilt {Math.Atan(vDir.y / vDir.x) * RAD_TO_DEG:n3}°");
    }


    fun getDistanceFromAngle(o:Vec<Double>): Double
    {
        val oAdjusted = (o - originTrack) * vDir  //  originate from mid & tilt
//        ActivityVelocity.invalidateInfos("$o, ${o-originTrack}, $oAdjusted")

        // by rule of sines
        return markerDistances[1] + sin(oAdjusted[0]) * mTrack / sin(RIGHT_ANGLE + z - oAdjusted[0])
    }
    fun getDistance(px:Vec<Int>): Double = getDistanceFromAngle(pixelToAngle(px))


    fun getPixelAtPositionF(distance: Double, atHeight: Double = .8, zIncrease: Double = 0.0):Vec<Int>
    {
        //  TODO TEST!
        val d = distance - markerDistances[1]
        val v = Vec.create(
            d * cos(z) + zIncrease * sin(-z),
            atHeight,
            mTrack + d * sin(-z) + zIncrease * cos(z)
        )
        val vPixelAngle = vDir * atan(v[0] / v[2]) + vUp * atan(v[1] / v[2])  //  vDir & vUp are already tilted

        return angleToPixel(originTrack.to<Double>() + vPixelAngle)
    }
//    public Vec2I getPixelAtPosition(double distance, double atHeight = .8, bool isDebug = false, double zIncrease = 0, bool isDontThrow = false) => getPixelAtPositionF(distance, atHeight, isDebug, zIncrease, isDontThrow).toInt();
    fun getHeightAtDistance(d:Double, atHeight:Double = .8):Int = getPixelAtPositionF(d, atHeight)[1]


    fun createProof() {
        //  TODO
    }


    companion object {
//        var isEquidistantProjection = false
        var size = Vec.create(1920, 1080)
        var fow = Vec.create(68.5, 54.2) / 180.0 * Math.PI
        var fpx = Vec.create<Double>(2) { i-> (size[i] / 2) / tan(fow[i] / 2) }
        const val RIGHT_ANGLE = Math.PI / 2


        fun pixelToAngle(vec: Vec<Int>): Vec<Double> {
            val centered = vec - (size / 2)

//            if(isEquidistantProjection)
//                return distanceFromCenter / f

            return Vec.create(2) { i-> atan(centered[i] / fpx[i])}
        }


        fun angleToPixel(vec: Vec<Double>): Vec<Int> {
//            if(isEquidistantProjection)
//                return distanceFromCenter / f

            return Vec.create(2) { i-> (fpx[i] * tan(vec[i])).toInt() } + (size / 2)
        }
    }
}