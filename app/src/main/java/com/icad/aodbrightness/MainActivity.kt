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
        val switchPocketMode = findViewById<MaterialSwitch>(R.id.switchPocketMode)
        val sliderMin = findViewById<Slider>(R.id.sliderMin)
        val sliderMax = findViewById<Slider>(R.id.sliderMax)
        val sliderCurve = findViewById<Slider>(R.id.sliderCurve)
        val tvMinVal = findViewById<TextView>(R.id.tvMinVal)
        val tvMaxVal = findViewById<TextView>(R.id.tvMaxVal)
        val tvCurveVal = findViewById<TextView>(R.id.tvCurveVal)
        val cardMax = findViewById<MaterialCardView>(R.id.cardMax)
        val cardCurve = findViewById<MaterialCardView>(R.id.cardCurve)
        val cardLuxCutoff = findViewById<MaterialCardView>(R.id.cardLuxCutoff)
        val etMinLux = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMinLux)
        val etMaxLux = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMaxLux)
        val btnApplyLux = findViewById<MaterialButton>(R.id.btnApplyLux)
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

        val pocketMode = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_POCKET_MODE, 1) == 1
        } catch (t: Throwable) {
            prefs.getBoolean(BrightnessProvider.KEY_POCKET_MODE, true)
        }

        val minBrightness = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_MIN, 10)
        } catch (t: Throwable) {
            prefs.getInt(BrightnessProvider.KEY_MIN_BRIGHTNESS, 10)
        }

        val maxBrightness = try {
            android.provider.Settings.System.getInt(contentResolver, AodHookModule.SETTING_MAX, 160)
        } catch (t: Throwable) {
            prefs.getInt(BrightnessProvider.KEY_MAX_BRIGHTNESS, 160)
        }

        val curve = try {
            android.provider.Settings.System.getFloat(contentResolver, AodHookModule.SETTING_CURVE, 1.3f)
        } catch (t: Throwable) {
            prefs.getFloat(BrightnessProvider.KEY_CURVE, 1.3f)
        }

        val minLux = try {
            android.provider.Settings.System.getFloat(contentResolver, AodHookModule.SETTING_LUX_MIN, 0f)
        } catch (t: Throwable) {
            prefs.getFloat(BrightnessProvider.KEY_LUX_MIN, 0f)
        }

        val maxLux = try {
            android.provider.Settings.System.getFloat(contentResolver, AodHookModule.SETTING_LUX_MAX, 20000f)
        } catch (t: Throwable) {
            prefs.getFloat(BrightnessProvider.KEY_LUX_MAX, 20000f)
        }

        switchEnable.isChecked = enabled
        switchAdaptive.isChecked = adaptive
        switchPocketMode.isChecked = pocketMode
        sliderMin.value = minBrightness.coerceIn(1, 255).toFloat()
        sliderMax.value = maxBrightness.coerceIn(1, 255).toFloat()
        sliderCurve.value = curve.coerceIn(0.5f, 2.5f)
        etMinLux.setText(if (minLux % 1f == 0f) minLux.toInt().toString() else minLux.toString())
        etMaxLux.setText(if (maxLux % 1f == 0f) maxLux.toInt().toString() else maxLux.toString())

        tvMinVal.text = "$minBrightness / 255 (${(minBrightness * 100 / 255)}%)"
        tvMaxVal.text = "$maxBrightness / 255 (${(maxBrightness * 100 / 255)}%)"
        tvCurveVal.text = String.format(java.util.Locale.US, "%.1fx", curve)
        cardMax.visibility = if (adaptive) View.VISIBLE else View.GONE
        cardCurve.visibility = if (adaptive) View.VISIBLE else View.GONE
        cardLuxCutoff.visibility = if (adaptive) View.VISIBLE else View.GONE

        fun broadcastCurrentSettings() {
            val curMinLux = etMinLux.text?.toString()?.toFloatOrNull() ?: 0f
            val curMaxLux = etMaxLux.text?.toString()?.toFloatOrNull() ?: 20000f
            val intent = Intent(AodHookModule.ACTION_UPDATE_SETTINGS).apply {
                setPackage("com.android.systemui")
                putExtra(BrightnessProvider.KEY_ENABLED, switchEnable.isChecked)
                putExtra(BrightnessProvider.KEY_ADAPTIVE, switchAdaptive.isChecked)
                putExtra(BrightnessProvider.KEY_POCKET_MODE, switchPocketMode.isChecked)
                putExtra(BrightnessProvider.KEY_MIN_BRIGHTNESS, sliderMin.value.toInt())
                putExtra(BrightnessProvider.KEY_MAX_BRIGHTNESS, sliderMax.value.toInt())
                putExtra(BrightnessProvider.KEY_CURVE, sliderCurve.value)
                putExtra(BrightnessProvider.KEY_LUX_MIN, curMinLux)
                putExtra(BrightnessProvider.KEY_LUX_MAX, curMaxLux)
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
                    BrightnessProvider.KEY_POCKET_MODE -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_POCKET_MODE, if (value as Boolean) 1 else 0)
                    BrightnessProvider.KEY_MIN_BRIGHTNESS -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_MIN, value as Int)
                    BrightnessProvider.KEY_MAX_BRIGHTNESS -> android.provider.Settings.System.putInt(contentResolver, AodHookModule.SETTING_MAX, value as Int)
                    BrightnessProvider.KEY_CURVE -> android.provider.Settings.System.putFloat(contentResolver, AodHookModule.SETTING_CURVE, value as Float)
                    BrightnessProvider.KEY_LUX_MIN -> android.provider.Settings.System.putFloat(contentResolver, AodHookModule.SETTING_LUX_MIN, value as Float)
                    BrightnessProvider.KEY_LUX_MAX -> android.provider.Settings.System.putFloat(contentResolver, AodHookModule.SETTING_LUX_MAX, value as Float)
                }
            } catch (t: Throwable) { }

            try {
                contentResolver.update(BrightnessProvider.CONTENT_URI, cv, null, null)
            } catch (t: Throwable) { }
            broadcastCurrentSettings()
        }

        fun applyLuxCutoffs() {
            val minL = etMinLux.text?.toString()?.toFloatOrNull() ?: 0f
            val maxL = etMaxLux.text?.toString()?.toFloatOrNull() ?: 20000f
            val safeMin = kotlin.math.max(0f, minL)
            val safeMax = kotlin.math.max(safeMin + 1f, maxL)
            updateSetting(BrightnessProvider.KEY_LUX_MIN, safeMin)
            updateSetting(BrightnessProvider.KEY_LUX_MAX, safeMax)
            android.widget.Toast.makeText(this, "Cutoffs applied: ${safeMin.toInt()} - ${safeMax.toInt()} lux", android.widget.Toast.LENGTH_SHORT).show()
        }

        btnApplyLux.setOnClickListener {
            applyLuxCutoffs()
        }

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            updateSetting(BrightnessProvider.KEY_ENABLED, isChecked)
        }

        switchAdaptive.setOnCheckedChangeListener { _, isChecked ->
            val v = if (isChecked) View.VISIBLE else View.GONE
            cardMax.visibility = v
            cardCurve.visibility = v
            cardLuxCutoff.visibility = v
            updateSetting(BrightnessProvider.KEY_ADAPTIVE, isChecked)
        }

        switchPocketMode.setOnCheckedChangeListener { _, isChecked ->
            updateSetting(BrightnessProvider.KEY_POCKET_MODE, isChecked)
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

        etMaxLux.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyLuxCutoffs()
                true
            } else {
                false
            }
        }

        if (android.provider.Settings.System.getString(contentResolver, AodHookModule.SETTING_LUX_MIN) == null) {
            updateSetting(BrightnessProvider.KEY_LUX_MIN, minLux)
            updateSetting(BrightnessProvider.KEY_LUX_MAX, maxLux)
        } else {
            broadcastCurrentSettings()
        }
    }
}
