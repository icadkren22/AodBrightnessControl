package com.icad.aodbrightness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AodHookModule : XposedModule() {

    companion object {
        private const val TAG = "AodBrightnessHook"
        const val ACTION_UPDATE_SETTINGS = "com.icad.aodbrightness.UPDATE_SETTINGS"

        const val SETTING_ENABLED = "aod_brightness_enabled"
        const val SETTING_ADAPTIVE = "aod_brightness_adaptive"
        const val SETTING_POCKET_MODE = "aod_brightness_pocket_mode"
        const val SETTING_MIN = "aod_brightness_min"
        const val SETTING_MAX = "aod_brightness_max"
        const val SETTING_CURVE = "aod_brightness_curve"
        const val SETTING_LUX_MIN = "aod_brightness_lux_min"
        const val SETTING_LUX_MAX = "aod_brightness_lux_max"

        @Volatile var isEnabled: Boolean = true
        @Volatile var isAdaptive: Boolean = false
        @Volatile var isPocketMode: Boolean = true
        @Volatile var minBrightnessInt: Int = 10 // 1..255
        @Volatile var maxBrightnessInt: Int = 160 // 1..255
        @Volatile var curveGamma: Float = 1.3f // 0.5..2.5
        @Volatile var minLuxCutoff: Float = 0f
        @Volatile var maxLuxCutoff: Float = 20000f
        @Volatile var currentAmbientLux: Float = 0f
        @Volatile var isNear: Boolean = false
        @Volatile var inDoze: Boolean = false

        const val KEY_BLUR_MODE = "blur_mode"
        @Volatile var isAcrylicBlur: Boolean = false

        fun checkAcrylicBlur() {
            try {
                val prop = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "persist.sys.phh.sf.background_blur", "disabled") as? String
                isAcrylicBlur = (prop == "acrylic")
                Log.d(TAG, "checkAcrylicBlur: prop=$prop, isAcrylicBlur=$isAcrylicBlur")
            } catch (t: Throwable) {
                Log.w(TAG, "checkAcrylicBlur failed: ${t.message}")
            }
        }

        object FrostedGlassEffect {
            private var noisePaint: Paint? = null
            private var tintPaint: Paint? = null

            fun getTintPaint(): Paint {
                tintPaint?.let { return it }
                return Paint().apply {
                    color = Color.parseColor("#99121418") // Translucent dark charcoal/slate
                    isAntiAlias = true
                }.also { tintPaint = it }
            }

            fun getNoisePaint(): Paint {
                noisePaint?.let { return it }
                val size = 64
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val rnd = java.util.Random(1337)
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        // Subtle frosted grain
                        val v = 15 + rnd.nextInt(35)
                        bmp.setPixel(x, y, Color.argb(v, 255, 255, 255))
                    }
                }
                val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }.also { noisePaint = it }
            }
        }

        @Volatile private var activeInstance: WeakReference<Any>? = null
        @Volatile private var cachedSetDozeMethod: Method? = null
        @Volatile private var observerRegistered: Boolean = false
        @Volatile private var lightSensorRegistered: Boolean = false
        @Volatile private var proximitySensorRegistered: Boolean = false
        @Volatile private var sensorManager: SensorManager? = null
        @Volatile private var lightSensor: Sensor? = null
        @Volatile private var proximitySensor: Sensor? = null

        private val lightSensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.isEmpty()) return
                val lux = event.values[0]
                // Only update if change is significant (> 1 lux or > 5% difference)
                if (abs(lux - currentAmbientLux) >= 1f) {
                    currentAmbientLux = lux
                    Log.d(TAG, "Light sensor event: lux=$lux")
                    if (isEnabled && isAdaptive && !isNear) {
                        applyBrightness()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        private val proximitySensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.isEmpty()) return
                val distance = event.values[0]
                val maxRange = proximitySensor?.maximumRange ?: 5f
                val near = if (maxRange > 1.0f) distance < 1.0f else distance < maxRange
                Log.d(TAG, "Proximity sensor event: distance=$distance (maxRange=$maxRange), near=$near")
                if (near != isNear) {
                    isNear = near
                    Log.i(TAG, "Pocket mode state changed: isNear=$isNear")
                    if (isEnabled && isPocketMode) {
                        applyBrightness()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        fun targetBrightness(): Float {
            if (isPocketMode && isNear) {
                return 0.0f
            }
            val lo = minBrightnessInt / 255.0f
            val hi = maxBrightnessInt / 255.0f
            return if (isAdaptive) {
                // Smooth power curve between minLuxCutoff and maxLuxCutoff:
                val span = max(1.0f, maxLuxCutoff - minLuxCutoff)
                val normalizedLux = ((currentAmbientLux - minLuxCutoff) / span).coerceIn(0.0f, 1.0f)
                val factor = Math.pow(normalizedLux.toDouble(), curveGamma.toDouble()).toFloat()
                (lo + (hi - lo) * factor).coerceIn(0.001f, 1.0f)
            } else {
                lo.coerceIn(0.001f, 1.0f)
            }
        }

        fun patchInstance(obj: Any) {
            val t = targetBrightness()
            runCatching {
                val defField = obj.javaClass.getDeclaredField("mDefaultDozeBrightness").apply { isAccessible = true }
                defField.setFloat(obj, t)
            }
            runCatching {
                val arr = obj.javaClass.getDeclaredField("mSensorToBrightness").apply { isAccessible = true }.get(obj) as? FloatArray
                arr?.fill(t)
            }
            for (field in listOf("mSensorToScrimOpacity", "mSensorToWallpaperScrimOpacity")) {
                runCatching {
                    val arr = obj.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(obj) as? IntArray
                    arr?.fill(0)
                }
            }
        }

        @Volatile private var cachedSetDozeStateMethod: Method? = null

        fun getDozeService(obj: Any): Any? = runCatching {
            obj.javaClass.getDeclaredField("mDozeService").apply { isAccessible = true }.get(obj)
        }.getOrNull()

        fun pushToDozeService(obj: Any) {
            val t = targetBrightness()
            try {
                val srv = getDozeService(obj) ?: return

                // ── screen state ──────────────────────────────────────────────────────
                // When pocketed we want Display.STATE_OFF (1) so OLED pixels truly go dark.
                // When un-pocketed we restore Display.STATE_DOZE_SUSPEND (4) which is the
                // normal always-on state.
                // Display states: OFF=1, ON=2, DOZE=3, DOZE_SUSPEND=4, ON_SUSPEND=5
                var stateMethod = cachedSetDozeStateMethod
                if (stateMethod == null) {
                    stateMethod = (srv.javaClass.methods + srv.javaClass.declaredMethods)
                        .firstOrNull { it.name == "setDozeScreenState" && it.parameterTypes.size == 1 }
                        ?.also { it.isAccessible = true }
                    cachedSetDozeStateMethod = stateMethod
                }
                if (stateMethod != null) {
                    val displayState = if (isPocketMode && isNear) 1 else 4 // OFF=1, DOZE_SUSPEND=4
                    Log.d(TAG, "setDozeScreenState($displayState) near=$isNear")
                    stateMethod.invoke(srv, displayState)
                }

                // ── brightness ────────────────────────────────────────────────────────
                // Only push brightness when the screen is actually visible
                if (!isNear || !isPocketMode) {
                    var method = cachedSetDozeMethod
                    if (method == null) {
                        method = (srv.javaClass.methods + srv.javaClass.declaredMethods)
                            .firstOrNull { it.name == "setDozeScreenBrightness" && it.parameterTypes.size == 1 }
                            ?.also { it.isAccessible = true }
                        cachedSetDozeMethod = method
                    }
                    if (method != null) {
                        if (method.parameterTypes[0] == Int::class.javaPrimitiveType || method.parameterTypes[0] == Integer::class.java) {
                            method.invoke(srv, (t * 255f).toInt().coerceIn(1, 255))
                        } else {
                            method.invoke(srv, t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pushToDozeService failed: ${t.message}")
            }
        }

        fun applyBrightness(obj: Any? = null) {
            val instance = obj ?: activeInstance?.get() ?: return
            if (!isEnabled) return
            patchInstance(instance)
            pushToDozeService(instance)
            Log.d(TAG, "applyBrightness: target=${targetBrightness()}, min=$minBrightnessInt, max=$maxBrightnessInt, lux=$currentAmbientLux, near=$isNear")
        }

        fun updateSensorRegistration() {
            val sm = sensorManager ?: return

            // Light sensor: only while in doze, enabled, and adaptive
            val shouldLightBeRegistered = inDoze && isEnabled && isAdaptive
            if (shouldLightBeRegistered) {
                if (!lightSensorRegistered) {
                    lightSensor?.let {
                        lightSensorRegistered = sm.registerListener(lightSensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                        Log.i(TAG, "Registered light sensor listener: success=$lightSensorRegistered")
                    }
                }
            } else {
                if (lightSensorRegistered) {
                    sm.unregisterListener(lightSensorListener)
                    lightSensorRegistered = false
                    Log.i(TAG, "Unregistered light sensor listener")
                }
            }

            // Proximity sensor: only while in doze, enabled, and pocket mode
            val shouldProxBeRegistered = inDoze && isEnabled && isPocketMode
            if (shouldProxBeRegistered) {
                if (!proximitySensorRegistered) {
                    proximitySensor?.let {
                        proximitySensorRegistered = sm.registerListener(proximitySensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                        Log.i(TAG, "Registered proximity sensor listener: success=$proximitySensorRegistered")
                    }
                }
            } else {
                if (proximitySensorRegistered) {
                    sm.unregisterListener(proximitySensorListener)
                    proximitySensorRegistered = false
                    isNear = false
                    Log.i(TAG, "Unregistered proximity sensor listener")
                }
            }
        }

        fun loadSettings(context: Context) {
            try {
                val cr = context.contentResolver
                isEnabled = Settings.System.getInt(cr, SETTING_ENABLED, 1) == 1
                isAdaptive = Settings.System.getInt(cr, SETTING_ADAPTIVE, 0) == 1
                isPocketMode = Settings.System.getInt(cr, SETTING_POCKET_MODE, 1) == 1
                minBrightnessInt = Settings.System.getInt(cr, SETTING_MIN, 10)
                maxBrightnessInt = Settings.System.getInt(cr, SETTING_MAX, 160)
                curveGamma = Settings.System.getFloat(cr, SETTING_CURVE, 1.3f)
                minLuxCutoff = Settings.System.getFloat(cr, SETTING_LUX_MIN, 0f)
                maxLuxCutoff = Settings.System.getFloat(cr, SETTING_LUX_MAX, 20000f)
                checkAcrylicBlur()
                Log.i(TAG, "Loaded settings: enabled=$isEnabled, adaptive=$isAdaptive, pocket=$isPocketMode, min=$minBrightnessInt, max=$maxBrightnessInt, curve=$curveGamma, luxMin=$minLuxCutoff, luxMax=$maxLuxCutoff, acrylic=$isAcrylicBlur")
                updateSensorRegistration()
            } catch (t: Throwable) {
                Log.w(TAG, "loadSettings failed: ${t.message}")
            }
        }

        fun setupObserver(context: Context) {
            if (observerRegistered) return
            observerRegistered = true

            // Initialize SensorManager
            try {
                val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                sensorManager = sm
                lightSensor = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
                proximitySensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                Log.i(TAG, "Sensors init: light=${lightSensor?.name}, prox=${proximitySensor?.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed initializing SensorManager: ${t.message}")
            }

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
            for (key in listOf(SETTING_ENABLED, SETTING_ADAPTIVE, SETTING_POCKET_MODE, SETTING_MIN, SETTING_MAX, SETTING_CURVE, SETTING_LUX_MIN, SETTING_LUX_MAX)) {
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
                    isPocketMode = intent.getBooleanExtra(BrightnessProvider.KEY_POCKET_MODE, isPocketMode)
                    minBrightnessInt = intent.getIntExtra(BrightnessProvider.KEY_MIN_BRIGHTNESS, minBrightnessInt)
                    maxBrightnessInt = intent.getIntExtra(BrightnessProvider.KEY_MAX_BRIGHTNESS, maxBrightnessInt)
                    curveGamma = intent.getFloatExtra(BrightnessProvider.KEY_CURVE, curveGamma)
                    minLuxCutoff = intent.getFloatExtra(BrightnessProvider.KEY_LUX_MIN, minLuxCutoff)
                    maxLuxCutoff = intent.getFloatExtra(BrightnessProvider.KEY_LUX_MAX, maxLuxCutoff)
                    if (intent.hasExtra(KEY_BLUR_MODE)) {
                        val mode = intent.getStringExtra(KEY_BLUR_MODE)
                        isAcrylicBlur = (mode == "acrylic")
                    } else {
                        checkAcrylicBlur()
                    }
                    Log.d(TAG, "Broadcast received: min=$minBrightnessInt, max=$maxBrightnessInt, curve=$curveGamma, adaptive=$isAdaptive, pocket=$isPocketMode, luxCutoff=[$minLuxCutoff..$maxLuxCutoff], acrylic=$isAcrylicBlur")
                    updateSensorRegistration()
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

            updateSensorRegistration()
        }

        fun appContext(): Context? = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        Log.d(TAG, "onPackageLoaded: pkg=${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        Log.i(TAG, "onPackageReady: pkg=${param.packageName}")
        if (param.packageName == "com.android.systemui") {
            hookSystemUI(param.classLoader)
        } else if (param.packageName == "me.phh.treble.app") {
            hookTrebleApp(param.classLoader)
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

            // 1. transitionTo - manage active instance and sensor lifecycle
            clazz.declaredMethods.firstOrNull { it.name == "transitionTo" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    appContext()?.let { setupObserver(it) }

                    val newState = chain.args[1]?.toString() ?: ""
                    Log.d(TAG, "transitionTo state: $newState")

                    if (newState == "FINISH") {
                        // Turned screen back on / left doze
                        inDoze = false
                        isNear = false
                        updateSensorRegistration()
                        Log.d(TAG, "Unregistered sensors on FINISH")
                    } else if (newState.contains("DOZE")) {
                        inDoze = true
                        updateSensorRegistration()
                    }

                    patchInstance(obj)
                    chain.proceed()
                }
                Log.i(TAG, "Hooked transitionTo")
            }

            // 2. resetBrightnessToDefault - set target brightness directly
            clazz.declaredMethods.firstOrNull { it.name == "resetBrightnessToDefault" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    if (!inDoze) {
                        inDoze = true
                        updateSensorRegistration()
                    }
                    if (isEnabled) {
                        patchInstance(obj)
                        pushToDozeService(obj)
                        Log.d(TAG, "resetBrightnessToDefault applied target: ${targetBrightness()}")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                Log.i(TAG, "Hooked resetBrightnessToDefault")
            }

            // 3. clampToDimBrightnessForScreenOff - override screen-off clamp with our target
            clazz.declaredMethods.firstOrNull { it.name == "clampToDimBrightnessForScreenOff" }?.let { m ->
                hook(m).intercept { chain ->
                    if (isEnabled) targetBrightness() else chain.proceed()
                }
                Log.i(TAG, "Hooked clampToDimBrightnessForScreenOff")
            }

            // 4. onSensorChanged (original method backup hook, if SystemUI ever fires it)
            clazz.declaredMethods.firstOrNull { it.name == "onSensorChanged" }?.let { m ->
                hook(m).intercept { chain ->
                    val obj = chain.thisObject
                    activeInstance = WeakReference(obj)
                    val event = chain.args[0] as? SensorEvent
                    if (event != null && event.values.isNotEmpty()) {
                        currentAmbientLux = event.values[0]
                    }
                    if (isEnabled && isAdaptive) {
                        patchInstance(obj)
                        pushToDozeService(obj)
                    }
                    chain.proceed()
                }
                Log.i(TAG, "Hooked onSensorChanged")
            }

            // 5. ScrimDrawable.draw - inject Frosted Glass / Acrylic effect
            try {
                val scrimClass = classLoader.loadClass("com.android.systemui.scrim.ScrimDrawable")
                scrimClass.declaredMethods.firstOrNull { it.name == "draw" && it.parameterTypes.size == 1 }?.let { m ->
                    hook(m).intercept { chain ->
                        chain.proceed()
                        if (isAcrylicBlur) {
                            val drawable = chain.thisObject as? Drawable ?: return@intercept null
                            val canvas = chain.args[0] as? Canvas ?: return@intercept null
                            val alpha = drawable.alpha
                            if (alpha > 5) {
                                val bounds = drawable.bounds
                                val tint = FrostedGlassEffect.getTintPaint()
                                tint.alpha = (alpha * 0.45f).toInt().coerceIn(0, 255)
                                canvas.drawRect(bounds, tint)

                                val noise = FrostedGlassEffect.getNoisePaint()
                                noise.alpha = (alpha * 0.75f).toInt().coerceIn(0, 255)
                                canvas.drawRect(bounds, noise)
                            }
                        }
                        null
                    }
                    Log.i(TAG, "Hooked ScrimDrawable.draw for Frosted Glass Acrylic")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not hook ScrimDrawable: ${t.message}")
            }

            systemUiHooked = true
            Log.i(TAG, "Successfully installed all SystemUI hooks!")
        } catch (t: Throwable) {
            Log.e(TAG, "Error hooking SystemUI: ${t.message}", t)
        }
    }

    private var trebleAppHooked = false

    private fun hookTrebleApp(classLoader: ClassLoader) {
        if (trebleAppHooked) return
        try {
            val fragClass = classLoader.loadClass("me.phh.treble.app.MiscSettingsFragment")
            fragClass.declaredMethods.firstOrNull { it.name == "onCreatePreferences" }?.let { m ->
                hook(m).intercept { chain ->
                    chain.proceed()
                    val frag = chain.thisObject
                    try {
                        val findPref = frag.javaClass.getMethod("findPreference", CharSequence::class.java)
                        val pref = findPref.invoke(frag, "key_display_sf_blur_algorithm") ?: return@intercept null

                        val getEntries = pref.javaClass.getMethod("getEntries")
                        val setEntries = pref.javaClass.getMethod("setEntries", Array<CharSequence>::class.java)
                        val getValues = pref.javaClass.getMethod("getEntryValues")
                        val setValues = pref.javaClass.getMethod("setEntryValues", Array<CharSequence>::class.java)

                        val entries = (getEntries.invoke(pref) as? Array<*>)?.map { it.toString() as CharSequence }?.toMutableList() ?: mutableListOf()
                        val values = (getValues.invoke(pref) as? Array<*>)?.map { it.toString() as CharSequence }?.toMutableList() ?: mutableListOf()

                        if (!values.any { it.toString() == "acrylic" }) {
                            entries.add("Frosted Glass / Acrylic (Ultra-Light)")
                            values.add("acrylic")
                            setEntries.invoke(pref, entries.toTypedArray())
                            setValues.invoke(pref, values.toTypedArray())
                            Log.i(TAG, "Injected 'Frosted Glass / Acrylic' into TrebleApp!")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error injecting into TrebleApp: ${t.message}", t)
                    }
                    null
                }
                trebleAppHooked = true
                Log.i(TAG, "Hooked MiscSettingsFragment in TrebleApp")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hook TrebleApp: ${t.message}")
        }
    }
}
