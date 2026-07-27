import org.json.JSONArray

class Vec<T: Any>(val array: Array<T>) {
//    constructor(vararg ts:T) : this(Array(ts.size) { ts[it] })


    fun toJson(): JSONArray {
        return JSONArray(array)
    }


    inline fun <reified G : Any> to(): Vec<G> {
        return Vec<G>(array.map { it -> it as G }.toTypedArray())
    }


    operator fun get(b: Int):T {
        return this.array[b]
    }


    override fun toString(): String {
        return "(" + array.joinToString(", ") + ")"
    }


    inline operator fun <reified T : Number> plus(b:Vec<T>):Vec<T> {
        if(this.array.size != b.array.size || array[0]::class != T::class)
            throw Exception()

        return Vec(Array(this.array.size) { i-> when (T::class) {
            Int::class -> (this.array[i] as Int + b.array[i] as Int) as T
            Long::class -> (this.array[i] as Long + b.array[i] as Long) as T
            Float::class -> (this.array[i] as Float + b.array[i] as Float) as T
            Double::class -> (this.array[i] as Double + b.array[i] as Double) as T
            else -> throw IllegalArgumentException("Unsupported type")
        } })
    }


    inline operator fun <reified T : Number> minus(b:Vec<T>):Vec<T> {
        if(this.array.size != b.array.size || array[0]::class != T::class)
            throw Exception()

        return Vec(Array(this.array.size) { i-> when (T::class) {
            Int::class -> (this.array[i] as Int - b.array[i] as Int) as T
            Long::class -> (this.array[i] as Long - b.array[i] as Long) as T
            Float::class -> (this.array[i] as Float - b.array[i] as Float) as T
            Double::class -> (this.array[i] as Double - b.array[i] as Double) as T
            else -> throw IllegalArgumentException("Unsupported type")
        } })
    }


    inline operator fun <reified T : Number> div(b:T):Vec<T> {
        if(array[0]::class != T::class)
            throw Exception()

        return Vec(Array(this.array.size) { i-> when (T::class) {
            Int::class -> (this.array[i] as Int / b as Int) as T
            Long::class -> (this.array[i] as Long / b as Long) as T
            Float::class -> (this.array[i] as Float / b as Float) as T
            Double::class -> (this.array[i] as Double / b as Double) as T
            else -> throw IllegalArgumentException("Unsupported type")
        } })
    }


    inline operator fun <reified T : Number> times(b:T):Vec<T> {
        if(array[0]::class != T::class)
            throw Exception()

        return Vec(Array(this.array.size) { i-> when (T::class) {
            Int::class -> (this.array[i] as Int * b as Int) as T
            Long::class -> (this.array[i] as Long * b as Long) as T
            Float::class -> (this.array[i] as Float * b as Float) as T
            Double::class -> (this.array[i] as Double * b as Double) as T
            else -> throw IllegalArgumentException("Unsupported type")
        } })
    }


    inline operator fun <reified T : Number> times(b:Vec<T>):Vec<T> {
        if(array[0]::class != T::class)
            throw Exception()

        return Vec(Array(this.array.size) { i-> when (T::class) {
            Int::class -> (this.array[i] as Int * b[i] as Int) as T
            Long::class -> (this.array[i] as Long * b[i] as Long) as T
            Float::class -> (this.array[i] as Float * b[i] as Float) as T
            Double::class -> (this.array[i] as Double * b[i] as Double) as T
            else -> throw IllegalArgumentException("Unsupported type")
        } })
    }


    inline fun <reified T : Number> ls():T {
//        if(array[0]::class != T::class)
//            throw Exception()

        return when (T::class) {
            Int::class -> array.sumOf { it as Int * it as Int } as T
            Long::class -> array.sumOf { it as Long * it as Long } as T
            Float::class -> array.sumOf { (it as Float * it as Float).toDouble() }.toFloat() as T
            Double::class -> array.sumOf { it as Double * it as Double } as T
            else -> throw IllegalArgumentException("Unsupported type")
        }
    }


    companion object {
        inline fun <reified T> create(vararg ts:T): Vec<T> where T:Any {
            return Vec(ts.map { it }.toTypedArray())
        }
        inline fun <reified T> create(size: Int, noinline init:(Int)->T): Vec<T> where T:Any{
            return Vec(Array(size, init))
        }


        inline fun <reified T> fromJson(array: JSONArray?): Vec<T>? where T:Any {
            if(array == null)
                return null

            return when (T::class) {
                Int::class -> Vec.create(array.length()) { array.getInt(it) } as Vec<T>
                Long::class -> Vec.create(array.length()) { array.getLong(it) } as Vec<T>
                Float::class -> Vec.create(array.length()) { array.optDouble(it).toFloat() } as Vec<T>
                Double::class -> Vec.create(array.length()) { array.optDouble(it) } as Vec<T>
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }
    }
}


fun <T : Any> String.formatV(v:Vec<T>): String {
    return "(" + v.array.joinToString(", ") { this.format(it) } + ")"
}