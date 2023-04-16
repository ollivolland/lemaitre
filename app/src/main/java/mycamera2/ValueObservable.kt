package mycamera2

import android.util.Log
import kotlin.reflect.KProperty
import kotlin.reflect.typeOf

class ValueObservable<T: Any>(vararg observers: (T) -> Unit) {
	
	var value:T; set(value) = observedSet(value); get() = observedGet()
	private lateinit var actualValue:T
	private val observers = mutableListOf<(T) -> Unit>()
	var isInitialized:Boolean = false; private set
	
	init { this.observers.addAll(observers) }
	
	private fun observedSet(value: T) {
		Log.i("vo", "set ${value.javaClass.simpleName}")
		if(isInitialized) throw Exception()
		
		this.actualValue = value
		isInitialized = true
		observers.forEach { it(value) }
	}
	
	private fun observedGet():T {
		Log.i("vo", "get ${actualValue.javaClass.simpleName}")
		if(!isInitialized) throw Exception()
		
		return actualValue
	}
	
	operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = observedSet(value)
	
	operator fun getValue(thisRef: Any?, property: KProperty<*>): T = observedGet()
	
	operator fun plus(observe:(T) -> Unit) {
		if(isInitialized) observe(actualValue)    //  observe if already assigned
		
		observers.add(observe)
	}
	
	@Deprecated("use plus instead", ReplaceWith("plus"), DeprecationLevel.ERROR )
	fun addObserver(lambda:(T) -> Unit) { }
}