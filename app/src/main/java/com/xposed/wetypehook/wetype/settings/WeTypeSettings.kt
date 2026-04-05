package com.xposed.wetypehook.wetype.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import de.robv.android.xposed.XSharedPreferences

object WeTypeSettings {
    private const val MODULE_PACKAGE_NAME = "com.xposed.miuiime"
    private const val PREF_NAME = "wetype_settings"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"

    const val DEFAULT_LIGHT_COLOR = 0xA0D1D3D8.toInt()
    const val DEFAULT_DARK_COLOR = 0x90202020.toInt()
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val MAX_CORNER_RADIUS = DEFAULT_CORNER_RADIUS * 2
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 50
    const val DEFAULT_KEY_OPACITY = 200

    private val xposedPrefsLock = Any()

    @Volatile
    private var xposedPrefs: XSharedPreferences? = null

    private val xposedPrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runCatching { xposedPrefs?.reload() }
    }

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val keyOpacity: Int
    )

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getKeyOpacity(context: Context): Int = readSnapshot(context).keyOpacity

    fun initXposed() {
        getXposedPrefs()?.reload()
    }

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        keyOpacity: Int
    ) {
        appPreferences(context)
            .edit()
            .putInt(KEY_LIGHT_COLOR, lightColor)
            .putInt(KEY_DARK_COLOR, darkColor)
            .putInt(KEY_BLUR_RADIUS, blurRadius.coerceIn(0, 100))
            .putInt(KEY_CORNER_RADIUS, cornerRadius.coerceIn(0, MAX_CORNER_RADIUS))
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity.coerceIn(0, 200))
            .putInt(KEY_KEY_OPACITY, keyOpacity.coerceIn(0, 255))
            .commit()
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int {
        val snapshot = readSnapshotXposed()
        val isDarkMode =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) snapshot.darkColor else snapshot.lightColor
    }

    fun getBlurRadiusXposed(context: Context): Int = readSnapshotXposed().blurRadius

    fun getCornerRadiusXposed(context: Context): Int = readSnapshotXposed().cornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getKeyOpacityXposed(context: Context): Int = readSnapshotXposed().keyOpacity

    fun readSnapshot(context: Context): Snapshot {
        val prefs = appPreferences(context)
        return prefs.toSnapshot()
    }

    private fun readSnapshotXposed(): Snapshot {
        val prefs = getXposedPrefs() ?: return defaultSnapshot()
        return prefs.toSnapshot().copy(
            cornerRadius = prefs.getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS)
        )
    }

    @Suppress("DEPRECATION")
    private fun appPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return try {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    @Suppress("DEPRECATION")
    private fun getXposedPrefs(): XSharedPreferences? {
        xposedPrefs?.let { return it }
        synchronized(xposedPrefsLock) {
            xposedPrefs?.let { return it }
            return runCatching {
                XSharedPreferences(MODULE_PACKAGE_NAME, PREF_NAME).also { prefs ->
                    prefs.reload()
                    prefs.registerOnSharedPreferenceChangeListener(xposedPrefChangeListener)
                    xposedPrefs = prefs
                }
            }.getOrNull()
        }
    }

    private fun SharedPreferences.toSnapshot(): Snapshot {
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            keyOpacity = getInt(KEY_KEY_OPACITY, DEFAULT_KEY_OPACITY)
        )
    }

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        keyOpacity = DEFAULT_KEY_OPACITY
    )
}
