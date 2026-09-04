package com.icad.aodbrightness

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val switchEnable = findViewById<MaterialSwitch>(R.id.switchEnable)
        val switchAdaptive = findViewById<MaterialSwitch>(R.id.switchAdaptive)
        val sliderMin = findViewById<Slider>(R.id.sliderMin)
        val sliderMax = findViewById<Slider>(R.id.sliderMax)
        val sliderCurve = findViewById<Slider>(R.id.sliderCurve)
        val tvMinVal = findViewById<TextView>(R.id.tvMinVal)
        val tvMaxVal = findViewById<TextView>(R.id.tvMaxVal)
        val tvCurveVal = findViewById<TextView>(R.id.tvCurveVal)
        val cardMax = findViewById<MaterialCardView>(R.id.cardMax)
        val cardCurve = findViewById<MaterialCardView>(R.id.cardCurve)
        val btnTestScreenOff = findViewById<MaterialButton>(R.id.btnTestScreenOff)

        // Ensure Android system AOD/Doze is enabled
        try {
            android.provider.Settings.Secure.putInt(contentResolver, "doze_enabled", 1)
            android.provider.Settings.Secure.putInt(contentResolver, "doze_always_on", 1)
        } catch (t: Throwable) { }

        val prefs = getSharedPreferences(BrightnessProvider.PREF_NAME, Context.MODE_PRIVATE)

        val enabled = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_ENABLED, 1) == 1
        } catch (t: Throwable) {
            prefs.getBoolean(BrightnessProvider.KEY_ENABLED, true)
        }

        val adaptive = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_ADAPTIVE, 0) == 1
        } catch (t: Throwable) {
            prefs.getBoolean(BrightnessProvider.KEY_ADAPTIVE, false)
        }

        val minBrightness = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_MIN, 30)
        } catch (t: Throwable) {
            prefs.getInt(BrightnessProvider.KEY_MIN_BRIGHTNESS, 30)
        }

        val maxBrightness = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_MAX, 100)
        } catch (t: Throwable) {
            prefs.getInt(BrightnessProvider.KEY_MAX_BRIGHTNESS, 100)
        }

        val curve = try {
            android.provider.Settings.System.getFloat(contentResolver, AodHookModule.SETTING_CURVE, 1.3f)
        } catch (t: Throwable) {
            prefs.getFloat(BrightnessProvider.KEY_CURVE, 1.3f)
        }

        switchEnable.isChecked = enabled
        switchAdaptive.isChecked = adaptive
        sliderMin.value = minBrightness.coerceIn(1, 255).toFloat()
        sliderMax.value = maxBrightness.coerceIn(1, 255).toFloat()
        sliderCurve.value = curve.coerceIn(0.5f, 2.5f)

        tvMinVal.text = "$minBrightness / 255 (${(minBrightness * 100 / 255)}%)"
        tvMaxVal.text = "$maxBrightness / 255 (${(maxBrightness * 100 / 255)}%)"
        tvCurveVal.text = String.format(java.util.Locale.US, "%.1fx", curve)
        cardMax.visibility = if (adaptive) View.VISIBLE else View.GONE
        cardCurve.visibility = if (adaptive) View.VISIBLE else View.GONE

        fun broadcastCurrentSettings() {
            val intent = Intent(AodHookModule.ACTION_UPDATE_SETTINGS).apply {
                setPackage("com.android.systemui")
                putExtra(BrightnessProvider.KEY_ENABLED, switchEnable.isChecked)
                putExtra(BrightnessProvider.KEY_ADAPTIVE, switchAdaptive.isChecked)
                putExtra(BrightnessProvider.KEY_MIN_BRIGHTNESS, sliderMin.value.toInt())
                putExtra(BrightnessProvider.KEY_MAX_BRIGHTNESS, sliderMax.value.toInt())
                putExtra(BrightnessProvider.KEY_CURVE, sliderCurve.value)
            }
            sendBroadcast(intent)
        }

        fun updateSetting(key: String, value: Any) {
            val editor = prefs.edit()
            val cv = ContentValues()
            when (value) {
                is Boolean -> {
                    editor.putBoolean(key, value)
                    cv.put(key, value)
                }
                is Int -> {
                    editor.putInt(key, value)
                    cv.put(key, value)
                }
                is Float -> {
                    editor.putFloat(key, value)
                    cv.put(key, value)
                }
            }
            editor.apply()

            try {
                when (key) {
                    BrightnessProvider.KEY_ENABLED -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_ENABLED, if (value as Boolean) 1 else 0)
                    BrightnessProvider.KEY_ADAPTIVE -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_ADAPTIVE, if (value as Boolean) 1 else 0)
                    BrightnessProvider.KEY_MIN_BRIGHTNESS -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_MIN, value as Int)
                    BrightnessProvider.KEY_MAX_BRIGHTNESS -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_MAX, value as Int)
                    BrightnessProvider.KEY_CURVE -> android.provider.Settings.System.putFloat(contentResolver, AodHookModule.SETTING_CURVE, value as Float)
                }
            } catch (t: Throwable) { }

            try {
                contentResolver.update(BrightnessProvider.CONTENT_URI, cv, null, null)
            } catch (t: Throwable) { }
            broadcastCurrentSettings()
        }

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            updateSetting(BrightnessProvider.KEY_ENABLED, isChecked)
        }

        switchAdaptive.setOnCheckedChangeListener { _, isChecked ->
            val v = if (isChecked) View.VISIBLE else View.GONE
            cardMax.visibility = v
            cardCurve.visibility = v
            updateSetting(BrightnessProvider.KEY_ADAPTIVE, isChecked)
        }

        sliderMin.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            tvMinVal.text = "$v / 255 (${(v * 100 / 255)}%)"
            updateSetting(BrightnessProvider.KEY_MIN_BRIGHTNESS, v)
        }

        sliderMax.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            tvMaxVal.text = "$v / 255 (${(v * 100 / 255)}%)"
            updateSetting(BrightnessProvider.KEY_MAX_BRIGHTNESS, v)
        }

        sliderCurve.addOnChangeListener { _, value, _ ->
            tvCurveVal.text = String.format(java.util.Locale.US, "%.1fx", value)
            updateSetting(BrightnessProvider.KEY_CURVE, value)
        }

        btnTestScreenOff.setOnClickListener {
            btnTestScreenOff.postDelayed({
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 26"))
                } catch (t: Throwable) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "26"))
                    } catch (t2: Throwable) { }
                }
            }, 500)
        }

        broadcastCurrentSettings()
    }
}
