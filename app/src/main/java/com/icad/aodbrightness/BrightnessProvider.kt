package com.icad.aodbrightness

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

class BrightnessProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.icad.aodbrightness.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")

        const val PREF_NAME = "aod_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_ADAPTIVE = "adaptive"
        const val KEY_POCKET_MODE = "pocket_mode"
        const val KEY_MIN_BRIGHTNESS = "min_brightness"
        const val KEY_MAX_BRIGHTNESS = "max_brightness"
        const val KEY_CURVE = "curve"
        const val KEY_LUX_MIN = "lux_min"
        const val KEY_LUX_MAX = "lux_max"
        const val KEY_DISABLE_AOD_BLUR = "disable_aod_blur"

        const val METHOD_GET_SETTINGS = "get_settings"
    }

    private fun getPrefs(): SharedPreferences {
        return context!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_GET_SETTINGS) {
            val prefs = getPrefs()
            return Bundle().apply {
                putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, true))
                putBoolean(KEY_ADAPTIVE, prefs.getBoolean(KEY_ADAPTIVE, true))
                putBoolean(KEY_POCKET_MODE, prefs.getBoolean(KEY_POCKET_MODE, true))
                putInt(KEY_MIN_BRIGHTNESS, prefs.getInt(KEY_MIN_BRIGHTNESS, 10))
                putInt(KEY_MAX_BRIGHTNESS, prefs.getInt(KEY_MAX_BRIGHTNESS, 160))
                putFloat(KEY_CURVE, prefs.getFloat(KEY_CURVE, 1.3f))
                putFloat(KEY_LUX_MIN, prefs.getFloat(KEY_LUX_MIN, 0f))
                putFloat(KEY_LUX_MAX, prefs.getFloat(KEY_LUX_MAX, 20000f))
                putBoolean(KEY_DISABLE_AOD_BLUR, prefs.getBoolean(KEY_DISABLE_AOD_BLUR, true))
            }
        }
        return super.call(method, arg, extras)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val prefs = getPrefs()
        val cursor = MatrixCursor(arrayOf("key", "value"))
        cursor.addRow(arrayOf(KEY_ENABLED, if (prefs.getBoolean(KEY_ENABLED, true)) 1 else 0))
        cursor.addRow(arrayOf(KEY_ADAPTIVE, if (prefs.getBoolean(KEY_ADAPTIVE, true)) 1 else 0))
        cursor.addRow(arrayOf(KEY_POCKET_MODE, if (prefs.getBoolean(KEY_POCKET_MODE, true)) 1 else 0))
        cursor.addRow(arrayOf(KEY_MIN_BRIGHTNESS, prefs.getInt(KEY_MIN_BRIGHTNESS, 10)))
        cursor.addRow(arrayOf(KEY_MAX_BRIGHTNESS, prefs.getInt(KEY_MAX_BRIGHTNESS, 160)))
        cursor.addRow(arrayOf(KEY_CURVE, prefs.getFloat(KEY_CURVE, 1.3f)))
        cursor.addRow(arrayOf(KEY_LUX_MIN, prefs.getFloat(KEY_LUX_MIN, 0f)))
        cursor.addRow(arrayOf(KEY_LUX_MAX, prefs.getFloat(KEY_LUX_MAX, 20000f)))
        cursor.addRow(arrayOf(KEY_DISABLE_AOD_BLUR, if (prefs.getBoolean(KEY_DISABLE_AOD_BLUR, true)) 1 else 0))
        return cursor
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (values != null) {
            val editor = getPrefs().edit()
            if (values.containsKey(KEY_ENABLED)) {
                editor.putBoolean(KEY_ENABLED, values.getAsBoolean(KEY_ENABLED))
            }
            if (values.containsKey(KEY_ADAPTIVE)) {
                editor.putBoolean(KEY_ADAPTIVE, values.getAsBoolean(KEY_ADAPTIVE))
            }
            if (values.containsKey(KEY_POCKET_MODE)) {
                editor.putBoolean(KEY_POCKET_MODE, values.getAsBoolean(KEY_POCKET_MODE))
            }
            if (values.containsKey(KEY_MIN_BRIGHTNESS)) {
                editor.putInt(KEY_MIN_BRIGHTNESS, values.getAsInteger(KEY_MIN_BRIGHTNESS))
            }
            if (values.containsKey(KEY_MAX_BRIGHTNESS)) {
                editor.putInt(KEY_MAX_BRIGHTNESS, values.getAsInteger(KEY_MAX_BRIGHTNESS))
            }
            if (values.containsKey(KEY_CURVE)) {
                editor.putFloat(KEY_CURVE, values.getAsFloat(KEY_CURVE))
            }
            if (values.containsKey(KEY_LUX_MIN)) {
                editor.putFloat(KEY_LUX_MIN, values.getAsFloat(KEY_LUX_MIN))
            }
            if (values.containsKey(KEY_LUX_MAX)) {
                editor.putFloat(KEY_LUX_MAX, values.getAsFloat(KEY_LUX_MAX))
            }
            if (values.containsKey(KEY_DISABLE_AOD_BLUR)) {
                editor.putBoolean(KEY_DISABLE_AOD_BLUR, values.getAsBoolean(KEY_DISABLE_AOD_BLUR))
            }
            editor.apply()
            context?.contentResolver?.notifyChange(uri, null)
            return 1
        }
        return 0
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.$AUTHORITY.settings"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
