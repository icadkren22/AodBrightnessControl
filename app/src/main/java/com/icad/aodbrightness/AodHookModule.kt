package com.icad.aodbrightness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Optional
import kotlin.math.abs

class AodHookModule : XposedModule() {

    companion object {
        private const val TAG = "AodBrightnessHook"
        const val ACTION_UPDATE_SETTINGS = "com.icad.aodbrightness.UPDATE_SETTINGS"

        const val SETTING_ENABLED = "aod_brightness_enabled"
        const val SETTING_ADAPTIVE = "aod_brightness_adaptive"
        const val SETTING_MIN = "aod_brightness_min"
        const val SETTING_MAX = "aod_brightness_max"
        const val SETTING_CURVE = "aod_brightness_curve"

        @Volatile var isEnabled: Boolean = true
        @Volatile var isAdaptive: Boolean = false
        @Volatile var minBrightnessInt: Int = 30 // 1..255
        @Volatile var maxBrightnessInt: Int = 100 // 1..255
        @Volatile var curveGamma: Float = 1.3f // 0.5..2.5
        @Volatile var currentAmbientLux: Float = 0f

        @Volatile private var activeInstance: WeakReference<Any>? = null
        @Volatile private var cachedSetDozeMethod: Method? = null
        @Volatile private var observerRegistered: Boolean = false

        fun targetBrightness(): Float {
            val lo = minBrightnessInt / 255.0f
            val hi = maxBrightnessInt / 255.0f
            return if (isAdaptive) {
                val normalizedLux = (currentAmbientLux / 10000.0f).coerceIn(0.0f, 1.0f)
                val factor = Math.pow(normalizedLux.toDouble(), curveGamma.toDouble()).toFloat()
                (lo + (hi - lo) * factor).coerceIn(0.001f, 1.0f)
            } else {
                lo.coerceIn(0.001f, 1.0f)
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun injectNativeSensor(obj: Any) {
            try {
                val optField = obj.javaClass.getDeclaredField("mLightSensorOptional").apply { isAccessible = true }
                val current = optField.get(obj) as? Array<*>
                val hasSensor = current != null && current.isNotEmpty() && (current[0] as? Optional<*>)?.isPresent == true
                if (!hasSensor) {
                    val smField = obj.javaClass.getDeclaredField("mSensorManager").apply { isAccessible = true }
                    val sm = smField.get(obj) as? SensorManager
                    val sensor = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
                    if (sensor != null) {
                        val array = java.lang.reflect.Array.newInstance(Optional::class.java, 5) as Array<Optional<Sensor>>
                        for (i in array.indices) {
                            array[i] = Optional.of(sensor)
                        }
                        optField.set(obj, array)
                        Log.i(TAG, "Native Bridge: Injected sensor '${sensor.name}' into SystemUI mLightSensorOptional")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native Bridge: Failed injecting sensor into mLightSensorOptional: ${t.message}", t)
            }
        }

        fun pushToDozeService(obj: Any) {
            val t = targetBrightness()
            try {
                val srvField = obj.javaClass.getDeclaredField("mDozeService").apply { isAccessible = true }
                val srv = srvField.get(obj) ?: return

                var method = cachedSetDozeMethod
                if (method == null) {
                    method = (srv.javaClass.methods + srv.javaClass.declaredMethods)
                        .firstOrNull { it.name == "setDozeScreenBrightness" && it.parameterTypes.size == 1 }
                        ?.also { it.isAccessible = true }
                    cachedSetDozeMethod = method
                }
                method?.invoke(srv, t)
            } catch (t: Throwable) {
                Log.w(TAG, "pushToDozeService failed: ${t.message}")
            }
        }

        fun applyBrightness(obj: Any? = null) {
            val instance = obj ?: activeInstance?.get() ?: return
            if (!isEnabled) return
            val target = targetBrightness()

            try {
                val defField = instance.javaClass.getDeclaredField("mDefaultDozeBrightness").apply { isAccessible = true }
                defField.setFloat(instance, target)
            } catch (t: Throwable) { }

            try {
                val brtField = instance.javaClass.getDeclaredField("mSensorToBrightness").apply { isAccessible = true }
                var arr = brtField.get(instance) as? FloatArray
                if (arr == null || arr.isEmpty()) {
                    arr = FloatArray(1)
                    brtField.set(instance, arr)
                }
                arr[0] = target
            } catch (t: Throwable) { }

            for (field in listOf("mSensorToScrimOpacity", "mSensorToWallpaperScrimOpacity")) {
                runCatching {
                    val arr = instance.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(instance) as? IntArray
                    arr?.fill(0)
                }
            }

            try {
                val method = instance.javaClass.getDeclaredMethod("updateBrightnessAndReady", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                method.invoke(instance, true)
                Log.d(TAG, "Native Bridge: Invoked native updateBrightnessAndReady(true) -> $target")
            } catch (t: Throwable) {
                pushToDozeService(instance)
            }
        }

        fun loadSettings(context: Context) {
            try {
                val cr = context.contentResolver
                isEnabled = Settings.System.getInt(cr, SETTING_ENABLED, 1) == 1
                isAdaptive = Settings.System.getInt(cr, SETTING_ADAPTIVE, 0) == 1
                minBrightnessInt = Settings.System.getInt(cr, SETTING_MIN, 30)
                maxBrightnessInt = Settings.System.getInt(cr, SETTING_MAX, 100)
                curveGamma = Settings.System.getFloat(cr, SETTING_CURVE, 1.3f)
                Log.i(TAG, "Loaded settings: enabled=$isEnabled, adaptive=$isAdaptive, min=$minBrightnessInt, max=$maxBrightnessInt, curve=$curveGamma")
            } catch (t: Throwable) {
                Log.w(TAG, "loadSettings failed: ${t.message}")
            }
        }

        fun setupObserver(context: Context) {
            if (observerRegistered) return
            observerRegistered = true

            val handler = Handler(Looper.getMainLooper())
            val reloadRunnable = Runnable {
                loadSettings(context)
                applyBrightness()
            }

            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(reloadRunnable)
                    handler.postDelayed(reloadRunnable, 300)
                }
            }

            val cr = context.contentResolver
            for (key in listOf(SETTING_ENABLED, SETTING_ADAPTIVE, SETTING_MIN, SETTING_MAX, SETTING_CURVE)) {
                try {
                    cr.registerContentObserver(Settings.System.getUriFor(key), false, observer)
                } catch (t: Throwable) { }
            }

            val filter = IntentFilter(ACTION_UPDATE_SETTINGS)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent == null) return
                    isEnabled = intent.getBooleanExtra(BrightnessProvider.KEY_ENABLED, isEnabled)
                    isAdaptive = intent.getBooleanExtra(BrightnessProvider.KEY_ADAPTIVE, isAdaptive)
                    minBrightnessInt = intent.getIntExtra(BrightnessProvider.KEY_MIN_BRIGHTNESS, minBrightnessInt)
                    maxBrightnessInt = intent.getIntExtra(BrightnessProvider.KEY_MAX_BRIGHTNESS, maxBrightnessInt)
                    curveGamma = intent.getFloatExtra(BrightnessProvider.KEY_CURVE, curveGamma)
                    Log.d(TAG, "Broadcast received: min=$minBrightnessInt, max=$maxBrightnessInt, curve=$curveGamma, adaptive=$isAdaptive")
                    applyBrightness()
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                Log.i(TAG, "Successfully registered ContentObserver and BroadcastReceiver in SystemUI")
            } catch (t: Throwable) {
                Log.e(TAG, "Error registering receiver: ${t.message}", t)
            }
        }

        fun appContext(): Context? = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName == "com.android.systemui") {
            hookSystemUI(param.defaultClassLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName == "com.android.systemui") {
            hookSystemUI(param.classLoader)
        }
    }

    private var systemUiHooked = false

    private fun hookSystemUI(classLoader: ClassLoader) {
        if (systemUiHooked) return
        try {
            val clazz = classLoader.loadClass("com.android.systemui.doze.DozeScreenBrightness")
            Log.i(TAG, "Found DozeScreenBrightness class in SystemUI")

            appContext()?.let {
                loadSettings(it)
                setupObserver(it)
            }

            // 1. Hook transitionTo: Inject native sensor and let SystemUI manage lifecycle
            clazz.declaredMethods.firstOrNull { it.name == "transitionTo" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    appContext()?.let { setupObserver(it) }

                    // Inject the hardware sensor into SystemUI's mLightSensorOptional array
                    injectNativeSensor(obj)

                    val newState = chain.args[1]?.toString() ?: ""
                    Log.d(TAG, "transitionTo state: $newState")

                    // Apply pre-transition brightness configuration
                    val target = targetBrightness()
                    try {
                        val defField = obj.javaClass.getDeclaredField("mDefaultDozeBrightness").apply { isAccessible = true }
                        defField.setFloat(obj, target)
                    } catch (t: Throwable) { }

                    try {
                        val brtField = obj.javaClass.getDeclaredField("mSensorToBrightness").apply { isAccessible = true }
                        var arr = brtField.get(obj) as? FloatArray
                        if (arr == null || arr.isEmpty()) {
                            arr = FloatArray(1)
                            brtField.set(obj, arr)
                        }
                        arr[0] = target
                    } catch (t: Throwable) { }

                    // Proceed with native transitionTo — SystemUI will natively call setLightSensorEnabled(true/false)
                    chain.proceed()
                }
                Log.i(TAG, "Hooked transitionTo")
            }

            // 2. Hook onSensorChanged: SystemUI's native listener receives hardware events!
            clazz.declaredMethods.firstOrNull { it.name == "onSensorChanged" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    val event = chain.args[0] as? SensorEvent
                    if (event != null && event.values.isNotEmpty()) {
                        val lux = event.values[0]
                        if (abs(lux - currentAmbientLux) >= 1f) {
                            currentAmbientLux = lux
                            Log.d(TAG, "Native Bridge Sensor Event: lux=$lux")
                        }

                        val target = targetBrightness()
                        try {
                            val brtField = obj.javaClass.getDeclaredField("mSensorToBrightness").apply { isAccessible = true }
                            var arr = brtField.get(obj) as? FloatArray
                            if (arr == null || arr.isEmpty()) {
                                arr = FloatArray(1)
                                brtField.set(obj, arr)
                            }
                            arr[0] = target
                        } catch (t: Throwable) { }

                        // Point native computeBrightness to index 0
                        event.values[0] = 0.0f
                    }

                    // Native DozeScreenBrightness.onSensorChanged -> updateBrightnessAndReady() runs!
                    chain.proceed()
                }
                Log.i(TAG, "Hooked native onSensorChanged")
            }

            // 3. Hook resetBrightnessToDefault
            clazz.declaredMethods.firstOrNull { it.name == "resetBrightnessToDefault" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    val target = targetBrightness()
                    try {
                        val defField = obj.javaClass.getDeclaredField("mDefaultDozeBrightness").apply { isAccessible = true }
                        defField.setFloat(obj, target)
                    } catch (t: Throwable) { }
                    chain.proceed()
                }
                Log.i(TAG, "Hooked resetBrightnessToDefault")
            }

            // 4. Hook clampToDimBrightnessForScreenOff
            clazz.declaredMethods.firstOrNull { it.name == "clampToDimBrightnessForScreenOff" }?.let { m ->
                hook(m).intercept { chain ->
                    if (isEnabled) targetBrightness() else chain.proceed()
                }
                Log.i(TAG, "Hooked clampToDimBrightnessForScreenOff")
            }

            systemUiHooked = true
            Log.i(TAG, "Successfully installed Native Bridge SystemUI hooks!")
        } catch (t: Throwable) {
            Log.e(TAG, "Error hooking SystemUI: ${t.message}", t)
        }
    }
}
