package camera

import kotlin.reflect.KProperty

class ValueObservable<T: Any>(vararg observers: (T) -> Unit) {
	
	private lateinit var value:T
	private val observers = mutableListOf<(T) -> Unit>()
	var isInitialized:Boolean = false; private set
	
	init { this.observers.addAll(observers) }
	
	operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
		if(isInitialized) throw Exception()
		
		this.value = value
		isInitialized = true
		observers.forEach { it(value) }
	}
	
	operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
		if(!isInitialized) throw Exception()
		
		return value
	}
	
	operator fun plus(observe:(T) -> Unit) {
		if(isInitialized) observe(value)    //  observe if already assigned
		
		observers.add(observe)
	}
	
	@Deprecated("use plus instead", ReplaceWith("plus"), DeprecationLevel.ERROR )
	fun addObserver(lambda:(T) -> Unit) { }
}