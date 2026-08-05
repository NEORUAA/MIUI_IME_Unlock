package com.xposed.wetypehook.wetype.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.xposed.wetypehook.ModuleBridgeContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

object WeTypeSettings {
    const val PREF_GROUP = "wetype_settings"
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val WETYPE_PACKAGE_NAME = "com.tencent.wetype"
    private const val EXTRA_APPEARANCE_COLORS = "appearance_colors"
    private const val KEY_REMOTE_SYNC_PENDING = "remote_sync_pending"
    private const val KEY_HOST_SYNC_PENDING = "host_sync_pending"
    private const val KEY_HOST_SYNC_REVISION = "host_sync_revision"
    private const val KEY_LAST_IMPORTED_REVISION = "last_imported_revision"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_KEY_CORNER_RADIUS = "key_corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"
    private const val KEY_KEY_OPACITY_MIGRATED = "key_opacity_migrated"
    // Keep the original preference key so existing saved values still migrate cleanly.
    private const val KEY_CANDIDATE_BACKGROUND_ALPHA = "key_color_hook_alpha"
    private const val KEY_CANDIDATE_BACKGROUND_CORNER = "candidate_background_corner"
    private const val KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP =
        "candidate_background_left_margin_dp"
    private const val KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP = "candidate_pinyin_left_margin_dp"
    private const val KEY_APPEARANCE_COLOR_PREFIX = "appearance_color_"
    private const val KEY_DISABLE_HOT_UPDATE = "disable_hot_update"
    private const val KEY_TOOLBAR_ICON_BG_OPACITY = "toolbar_icon_bg_opacity"
    const val DEFAULT_LIGHT_COLOR = 0xBDD4D4D4.toInt()
    const val DEFAULT_DARK_COLOR = 0x40000000
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val MAX_CORNER_RADIUS = DEFAULT_CORNER_RADIUS * 2
    const val DEFAULT_KEY_CORNER_RADIUS = 10
    const val MAX_KEY_CORNER_RADIUS = 40
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 80
    const val DEFAULT_CANDIDATE_BACKGROUND_ALPHA = 150
    const val DEFAULT_CANDIDATE_BACKGROUND_CORNER = 60f
    const val MAX_CANDIDATE_BACKGROUND_CORNER = 60
    const val DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP = 6
    const val DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP = 16
    const val DEFAULT_TOOLBAR_ICON_BG_OPACITY = 150
    const val DEFAULT_DISABLE_HOT_UPDATE = true

    private val legacyKeyColorDefaults = mapOf(
        LIGHT_KEY_COLOR_GROUP_ID to 0xFFfcfcfe.toInt(),
        DARK_KEY_COLOR_GROUP_ID to 0xFF707070.toInt()
    )

    private val remotePrefsLock = Any()
    private val settingsSyncLock = Any()

    @Volatile
    private var cachedXposedSnapshot: Snapshot? = null

    @Volatile
    private var remotePreferences: SharedPreferences? = null

    @Volatile
    private var moduleBridgePendingIntent: PendingIntent? = null

    private val remotePrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedXposedSnapshot = null
    }

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val keyCornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val candidateBackgroundAlpha: Int,
        val candidateBackgroundCorner: Float,
        val candidateBackgroundLeftMarginDp: Int,
        val candidatePinyinLeftMarginDp: Int,
        val appearanceColors: Map<String, Int>,
        val toolbarIconBgOpacity: Int,
        val disableHotUpdate: Boolean
    )

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun getKeyCornerRadius(context: Context): Int = readSnapshot(context).keyCornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getCandidateBackgroundAlpha(context: Context): Int =
        readSnapshot(context).candidateBackgroundAlpha

    fun getCandidateBackgroundCorner(context: Context): Float =
        readSnapshot(context).candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidateBackgroundLeftMarginDp

    fun getCandidatePinyinLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidatePinyinLeftMarginDp

    fun getAppearanceColors(context: Context): Map<String, Int> = readSnapshot(context).appearanceColors

    fun isDisableHotUpdate(context: Context): Boolean = readSnapshot(context).disableHotUpdate

    fun bindRemotePreferences(preferences: SharedPreferences) {
        synchronized(remotePrefsLock) {
            if (remotePreferences === preferences) return
            remotePreferences?.let { previous ->
                runCatching {
                    previous.unregisterOnSharedPreferenceChangeListener(remotePrefChangeListener)
                }
            }
            remotePreferences = preferences
            runCatching {
                preferences.registerOnSharedPreferenceChangeListener(remotePrefChangeListener)
            }
        }
        cachedXposedSnapshot = null
    }

    fun unbindRemotePreferences() {
        synchronized(remotePrefsLock) {
            remotePreferences?.let { preferences ->
                runCatching {
                    preferences.unregisterOnSharedPreferenceChangeListener(remotePrefChangeListener)
                }
            }
            remotePreferences = null
        }
        cachedXposedSnapshot = null
    }

    fun prepareForHotReload() = unbindRemotePreferences()

    fun bindModuleBridgePendingIntent(pendingIntent: PendingIntent?) {
        moduleBridgePendingIntent = pendingIntent
    }

    fun ensureHostSnapshot(context: Context) {
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        val localSnapshot = localPreferences.toSnapshotOrNull()
        if (appContext.packageName == MODULE_PACKAGE_NAME) {
            synchronizeRemotePreferences(appContext)
            return
        }
        val remoteSnapshot = remotePreferences?.toSnapshotOrNull()
        val hostSyncPending = localPreferences.getBoolean(KEY_HOST_SYNC_PENDING, false)

        when {
            hostSyncPending && localSnapshot != null -> {
                cachedXposedSnapshot = localSnapshot
                val revision = localPreferences.getLong(KEY_HOST_SYNC_REVISION, 0L)
                sendSnapshotToModule(appContext, localSnapshot, revision) { accepted ->
                    acknowledgeHostSnapshot(appContext, revision, accepted)
                }
            }

            remoteSnapshot != null -> {
                writeSnapshot(localPreferences, remoteSnapshot)
                cachedXposedSnapshot = remoteSnapshot
            }

            localSnapshot != null -> {
                cachedXposedSnapshot = localSnapshot
                val revision = nextHostRevision(localPreferences)
                val staged = localPreferences.edit()
                    .putBoolean(KEY_HOST_SYNC_PENDING, true)
                    .putLong(KEY_HOST_SYNC_REVISION, revision)
                    .commit()
                if (staged) {
                    sendSnapshotToModule(appContext, localSnapshot, revision) { accepted ->
                        acknowledgeHostSnapshot(appContext, revision, accepted)
                    }
                }
            }
        }
    }

    fun synchronizeRemotePreferences(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != MODULE_PACKAGE_NAME) return
        synchronized(settingsSyncLock) {
            synchronizeRemotePreferencesLocked(appContext)
        }
    }

    private fun synchronizeRemotePreferencesLocked(context: Context) {
        val remote = remotePreferences ?: return
        val localPreferences = appPreferences(context)
        val localSnapshot = localPreferences.toSnapshotOrNull()
        val remoteSnapshot = remote.toSnapshotOrNull()
        val pending = localPreferences.getBoolean(KEY_REMOTE_SYNC_PENDING, false)
        when {
            pending && localSnapshot != null -> {
                val synced = runCatching { writeSnapshot(remote, localSnapshot) }
                    .getOrDefault(false)
                if (synced) {
                    localPreferences.edit().putBoolean(KEY_REMOTE_SYNC_PENDING, false).commit()
                }
                if (synced) cachedXposedSnapshot = localSnapshot
            }

            remoteSnapshot == null && localSnapshot != null -> {
                val synced = runCatching { writeSnapshot(remote, localSnapshot) }
                    .getOrDefault(false)
                if (synced) {
                    localPreferences.edit().putBoolean(KEY_REMOTE_SYNC_PENDING, false).commit()
                }
                if (synced) cachedXposedSnapshot = localSnapshot
            }

            remoteSnapshot != null -> {
                val mirrored = writeSnapshot(localPreferences, remoteSnapshot) { editor ->
                    editor.putBoolean(KEY_REMOTE_SYNC_PENDING, false)
                }
                if (mirrored) cachedXposedSnapshot = remoteSnapshot
            }
        }
    }

    fun importBridgedSettings(context: Context, settings: Bundle): Boolean {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != MODULE_PACKAGE_NAME) {
            return false
        }
        return synchronized(settingsSyncLock) {
            val snapshot = settings.toSnapshot()
            val revision = settings.getLong(ModuleBridgeContract.EXTRA_REVISION, 0L)
            val localPreferences = appPreferences(appContext)
            val lastImportedRevision = localPreferences.getLong(KEY_LAST_IMPORTED_REVISION, 0L)
            if (revision > 0L && revision < lastImportedRevision) {
                return@synchronized true
            }
            val staged = writeSnapshot(localPreferences, snapshot) { editor ->
                editor
                    .putBoolean(KEY_REMOTE_SYNC_PENDING, true)
                    .putLong(KEY_LAST_IMPORTED_REVISION, revision)
            }
            if (!staged) return@synchronized false
            cachedXposedSnapshot = snapshot
            synchronizeRemotePreferencesLocked(appContext)
            true
        }
    }

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE,
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val sanitizedAppearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to (appearanceColors[group.id] ?: group.defaultColor)
        }
        return saveDirect(
            context = context,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = sanitizedAppearanceColors,
            disableHotUpdate = disableHotUpdate,
            onPersisted = onPersisted
        )
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

    fun getKeyCornerRadiusXposed(): Int = readSnapshotXposed().keyCornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getCandidateBackgroundAlphaXposed(): Int =
        readSnapshotXposed().candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(): Float =
        readSnapshotXposed().candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(): Int =
        readSnapshotXposed().toolbarIconBgOpacity

    fun getCandidatePinyinLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readSnapshotXposed().disableHotUpdate

    fun getAppearanceColorXposed(groupId: String): Int =
        readSnapshotXposed().appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun getAppearanceColorsXposed(): Map<String, Int> = readSnapshotXposed().appearanceColors

    fun readSnapshot(context: Context): Snapshot {
        return remotePreferences?.toSnapshotOrNull()
            ?: appPreferences(context).toSnapshotOrNull()
            ?: defaultSnapshot()
    }

    private fun readSnapshotXposed(): Snapshot {
        cachedXposedSnapshot?.let { return it }

        synchronized(remotePrefsLock) {
            cachedXposedSnapshot?.let { return it }
            val resolvedSnapshot = remotePreferences?.toSnapshotOrNull()
                ?: defaultSnapshot()
            cachedXposedSnapshot = resolvedSnapshot
            return resolvedSnapshot
        }
    }

    private fun appPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
    }

    private fun saveDirect(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean,
        onPersisted: (Boolean) -> Unit
    ): Boolean {
        val snapshot = Snapshot(
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius.coerceIn(0, 100),
            cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = keyCornerRadius.coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, 200),
            candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
            candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(
                0f,
                MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()
            ),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.coerceIn(0, 64),
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.coerceIn(0, 64),
            toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceColors[group.id] ?: group.defaultColor)
            },
            disableHotUpdate = disableHotUpdate
        )
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        return if (appContext.packageName == MODULE_PACKAGE_NAME) {
            val persisted = synchronized(settingsSyncLock) {
                val synced = remotePreferences?.let { preferences ->
                    runCatching { writeSnapshot(preferences, snapshot) }.getOrDefault(false)
                } ?: false
                writeSnapshot(localPreferences, snapshot) { editor ->
                    editor.putBoolean(KEY_REMOTE_SYNC_PENDING, !synced)
                }.also { saved ->
                    if (saved) cachedXposedSnapshot = snapshot
                }
            }
            onPersisted(persisted)
            persisted
        } else {
            val revision = nextHostRevision(localPreferences)
            val staged = writeSnapshot(localPreferences, snapshot) { editor ->
                editor
                    .putBoolean(KEY_HOST_SYNC_PENDING, true)
                    .putLong(KEY_HOST_SYNC_REVISION, revision)
            }
            if (!staged) {
                onPersisted(false)
                return false
            }
            cachedXposedSnapshot = snapshot
            sendSnapshotToModule(appContext, snapshot, revision) { accepted ->
                acknowledgeHostSnapshot(appContext, revision, accepted)
                onPersisted(accepted)
            }
        }
    }

    private fun writeSnapshot(
        preferences: SharedPreferences,
        snapshot: Snapshot,
        configureEditor: (SharedPreferences.Editor) -> Unit = {}
    ): Boolean {
        val editor = preferences.edit()
            .putInt(KEY_LIGHT_COLOR, snapshot.lightColor)
            .putInt(KEY_DARK_COLOR, snapshot.darkColor)
            .putInt(KEY_BLUR_RADIUS, snapshot.blurRadius)
            .putInt(KEY_CORNER_RADIUS, snapshot.cornerRadius)
            .putInt(KEY_KEY_CORNER_RADIUS, snapshot.keyCornerRadius)
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
            .putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, snapshot.candidateBackgroundAlpha)
            .putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, snapshot.candidateBackgroundCorner)
            .putInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                snapshot.candidateBackgroundLeftMarginDp
            )
            .putInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                snapshot.candidatePinyinLeftMarginDp
            )
            .putInt(KEY_TOOLBAR_ICON_BG_OPACITY, snapshot.toolbarIconBgOpacity)
            .putBoolean(KEY_DISABLE_HOT_UPDATE, snapshot.disableHotUpdate)
            .putBoolean(KEY_KEY_OPACITY_MIGRATED, true)
            .remove(KEY_KEY_OPACITY)
        WeTypeAppearanceColorGroups.groups.forEach { group ->
            editor.putInt(
                "$KEY_APPEARANCE_COLOR_PREFIX${group.id}",
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            )
        }
        WeTypeAppearanceColorGroups.obsoleteGroupIds.forEach { groupId ->
            editor.remove("$KEY_APPEARANCE_COLOR_PREFIX$groupId")
        }
        configureEditor(editor)
        return editor.commit()
    }

    private fun sendSnapshotToModule(
        context: Context,
        snapshot: Snapshot,
        revision: Long,
        onAccepted: (Boolean) -> Unit
    ): Boolean {
        if (context.packageName != WETYPE_PACKAGE_NAME) {
            onAccepted(false)
            return false
        }
        val appContext = context.applicationContext ?: context
        val acknowledgementAction = "${ModuleBridgeContract.ACTION_ACK_PREFIX}.${UUID.randomUUID()}"
        val acknowledgementToken = UUID.randomUUID().toString()
        val intent = ModuleBridgeContract.explicitBridgeIntent()
            .putExtra(ModuleBridgeContract.EXTRA_MESSAGE_TYPE, ModuleBridgeContract.MESSAGE_SAVE_SETTINGS)
            .putExtra(ModuleBridgeContract.EXTRA_SETTINGS, snapshot.toBundle())
            .putExtra(ModuleBridgeContract.EXTRA_REVISION, revision)
            .putExtra(ModuleBridgeContract.EXTRA_ACK_ACTION, acknowledgementAction)
            .putExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN, acknowledgementToken)
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var registered = false
        lateinit var acknowledgementReceiver: BroadcastReceiver

        fun finish(accepted: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            mainHandler.removeCallbacksAndMessages(null)
            if (registered) runCatching { appContext.unregisterReceiver(acknowledgementReceiver) }
            onAccepted(accepted)
        }

        acknowledgementReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, acknowledgement: Intent) {
                if (acknowledgement.action != acknowledgementAction ||
                    acknowledgement.getStringExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN) !=
                    acknowledgementToken ||
                    acknowledgement.getLongExtra(
                        ModuleBridgeContract.EXTRA_REVISION,
                        Long.MIN_VALUE
                    ) != revision
                ) {
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    receiverContext.packageManager.getPackagesForUid(sentFromUid)
                        ?.contains(MODULE_PACKAGE_NAME) != true
                ) {
                    finish(false)
                    return
                }
                finish(
                    acknowledgement.getIntExtra(ModuleBridgeContract.EXTRA_RESULT, 0) ==
                        ModuleBridgeContract.RESULT_ACCEPTED
                )
            }
        }
        val didRegister = runCatching {
            val filter = IntentFilter(acknowledgementAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    acknowledgementReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(acknowledgementReceiver, filter)
            }
            true
        }.getOrDefault(false)
        registered = didRegister
        val sentViaPendingIntent = moduleBridgePendingIntent?.let { pendingIntent ->
            runCatching {
                pendingIntent.send(appContext, 0, intent)
            }.onFailure {
                moduleBridgePendingIntent = null
            }.isSuccess
        } == true
        if (!didRegister ||
            (!sentViaPendingIntent && !ModuleBridgeContract.sendWithIdentity(appContext, intent))
        ) {
            finish(false)
            return false
        }
        mainHandler.postDelayed({ finish(false) }, ModuleBridgeContract.ACK_TIMEOUT_MILLIS)
        return true
    }

    private fun acknowledgeHostSnapshot(context: Context, revision: Long, accepted: Boolean) {
        if (!accepted) return
        val preferences = appPreferences(context)
        if (preferences.getLong(KEY_HOST_SYNC_REVISION, Long.MIN_VALUE) != revision) return
        preferences.edit().putBoolean(KEY_HOST_SYNC_PENDING, false).commit()
    }

    private fun nextHostRevision(preferences: SharedPreferences): Long {
        val previous = preferences.getLong(KEY_HOST_SYNC_REVISION, 0L)
        return maxOf(System.currentTimeMillis(), previous + 1L)
    }

    private fun Snapshot.toBundle(): Bundle = Bundle().apply {
        putInt(KEY_LIGHT_COLOR, lightColor)
        putInt(KEY_DARK_COLOR, darkColor)
        putInt(KEY_BLUR_RADIUS, blurRadius)
        putInt(KEY_CORNER_RADIUS, cornerRadius)
        putInt(KEY_KEY_CORNER_RADIUS, keyCornerRadius)
        putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
        putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity)
        putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, candidateBackgroundAlpha)
        putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, candidateBackgroundCorner)
        putInt(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP, candidateBackgroundLeftMarginDp)
        putInt(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP, candidatePinyinLeftMarginDp)
        putInt(KEY_TOOLBAR_ICON_BG_OPACITY, toolbarIconBgOpacity)
        putBoolean(KEY_DISABLE_HOT_UPDATE, disableHotUpdate)
        putBundle(
            EXTRA_APPEARANCE_COLORS,
            Bundle().apply {
                appearanceColors.forEach { (groupId, color) -> putInt(groupId, color) }
            }
        )
    }

    private fun Bundle.toSnapshot(): Snapshot {
        val defaults = defaultSnapshot()
        val appearanceBundle = getBundle(EXTRA_APPEARANCE_COLORS)
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, defaults.lightColor),
            darkColor = getInt(KEY_DARK_COLOR, defaults.darkColor),
            blurRadius = getInt(KEY_BLUR_RADIUS, defaults.blurRadius).coerceIn(0, 100),
            cornerRadius = getInt(KEY_CORNER_RADIUS, defaults.cornerRadius)
                .coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, defaults.keyCornerRadius)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                defaults.edgeHighlightEnabled
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                defaults.edgeHighlightIntensity
            ).coerceIn(0, 200),
            candidateBackgroundAlpha = getInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                defaults.candidateBackgroundAlpha
            ).coerceIn(0, 255),
            candidateBackgroundCorner = getFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                defaults.candidateBackgroundCorner
            ).coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
            candidateBackgroundLeftMarginDp = getInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                defaults.candidateBackgroundLeftMarginDp
            ).coerceIn(0, 64),
            candidatePinyinLeftMarginDp = getInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                defaults.candidatePinyinLeftMarginDp
            ).coerceIn(0, 64),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceBundle?.getInt(group.id, group.defaultColor)
                    ?: group.defaultColor)
            },
            toolbarIconBgOpacity = getInt(
                KEY_TOOLBAR_ICON_BG_OPACITY,
                defaults.toolbarIconBgOpacity
            ).coerceIn(0, 255),
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, defaults.disableHotUpdate)
        )
    }

    private fun SharedPreferences.toSnapshot(): Snapshot {
        val shouldMigrateLegacyKeyOpacity = contains(KEY_KEY_OPACITY) &&
            !getBoolean(KEY_KEY_OPACITY_MIGRATED, false)
        val legacyKeyOpacity = if (shouldMigrateLegacyKeyOpacity) {
            getInt(KEY_KEY_OPACITY, 255).coerceIn(0, 255)
        } else {
            null
        }
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, DEFAULT_KEY_CORNER_RADIUS)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            candidateBackgroundAlpha = getInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                DEFAULT_CANDIDATE_BACKGROUND_ALPHA
            ),
            candidateBackgroundCorner = getFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                DEFAULT_CANDIDATE_BACKGROUND_CORNER
            ).coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
            candidateBackgroundLeftMarginDp = getInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            candidatePinyinLeftMarginDp = getInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            toolbarIconBgOpacity = getInt(KEY_TOOLBAR_ICON_BG_OPACITY, DEFAULT_TOOLBAR_ICON_BG_OPACITY).coerceIn(0, 255),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                val key = "$KEY_APPEARANCE_COLOR_PREFIX${group.id}"
                val fallbackColor = if (legacyKeyOpacity != null) {
                    legacyKeyColorDefaults[group.id] ?: group.defaultColor
                } else {
                    group.defaultColor
                }
                val color = getInt(key, fallbackColor)
                group.id to migrateLegacyKeyOpacity(group, color, legacyKeyOpacity)
            },
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, DEFAULT_DISABLE_HOT_UPDATE)
        )
    }

    private fun migrateLegacyKeyOpacity(
        group: WeTypeAppearanceColorGroup,
        color: Int,
        legacyKeyOpacity: Int?
    ): Int {
        if (!group.isKeyColorGroup || legacyKeyOpacity == null || Color.alpha(color) != 0xFF) {
            return color
        }
        return (legacyKeyOpacity shl 24) or (color and 0x00FFFFFF)
    }

    private fun SharedPreferences.toSnapshotOrNull(): Snapshot? {
        if (!containsAnyPersistedSetting()) return null
        return toSnapshot()
    }

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        keyCornerRadius = DEFAULT_KEY_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        candidateBackgroundAlpha = DEFAULT_CANDIDATE_BACKGROUND_ALPHA,
        candidateBackgroundCorner = DEFAULT_CANDIDATE_BACKGROUND_CORNER,
        candidateBackgroundLeftMarginDp = DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
        candidatePinyinLeftMarginDp = DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
        toolbarIconBgOpacity = DEFAULT_TOOLBAR_ICON_BG_OPACITY,
        appearanceColors = WeTypeAppearanceColorGroups.defaultColors(),
        disableHotUpdate = DEFAULT_DISABLE_HOT_UPDATE
    )

    private fun SharedPreferences.containsAnyPersistedSetting(): Boolean {
        if (contains(KEY_LIGHT_COLOR) ||
            contains(KEY_DARK_COLOR) ||
            contains(KEY_BLUR_RADIUS) ||
            contains(KEY_CORNER_RADIUS) ||
            contains(KEY_KEY_CORNER_RADIUS) ||
            contains(KEY_EDGE_HIGHLIGHT_ENABLED) ||
            contains(KEY_EDGE_HIGHLIGHT_INTENSITY) ||
            contains(KEY_KEY_OPACITY) ||
            contains(KEY_CANDIDATE_BACKGROUND_ALPHA) ||
            contains(KEY_CANDIDATE_BACKGROUND_CORNER) ||
            contains(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP) ||
            contains(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP) ||
            contains(KEY_TOOLBAR_ICON_BG_OPACITY) ||
            contains(KEY_DISABLE_HOT_UPDATE)
        ) {
            return true
        }
        return WeTypeAppearanceColorGroups.groups.any { group ->
            contains("$KEY_APPEARANCE_COLOR_PREFIX${group.id}")
        }
    }

}
