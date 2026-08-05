package com.xposed.wetypehook

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.libxposed.service.HookedTarget

object ModuleActivationTracker {
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val PREF_NAME = "module_activation_status"
    private const val KEY_LAST_ACTIVATED_AT = "last_activated_at"
    private const val KEY_LAST_SOURCE_PACKAGE = "last_source_package"
    private const val KEY_LAST_SOURCE_PROCESS = "last_source_process"

    const val EXTRA_SOURCE_PACKAGE = "source_package"
    const val EXTRA_SOURCE_PROCESS = "source_process"

    private val trustedSourcePackages = setOf(
        "android",
        "com.tencent.wetype",
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.google.android.inputmethod.latin",
        "com.miui.phrase",
        "com.xiaomi.type"
    )

    data class ActivationStatus(
        val isActive: Boolean,
        val sourcePackage: String?,
        val sourceProcess: String?,
        val lastActivatedAt: Long
    )

    fun resolveStatusForUi(context: Context): ActivationStatus {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == MODULE_PACKAGE_NAME) {
            return readStatus(appContext)
        }
        return ActivationStatus(
            isActive = true,
            sourcePackage = appContext.packageName,
            sourceProcess = resolveProcessName(appContext),
            lastActivatedAt = System.currentTimeMillis()
        )
    }

    fun syncActivationFromUiContext(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == MODULE_PACKAGE_NAME) return
        notifyActivationFromHook(
            context = appContext,
            sourcePackage = appContext.packageName,
            sourceProcess = resolveProcessName(appContext)
        )
    }

    fun notifyActivationFromHook(
        context: Context,
        sourcePackage: String,
        sourceProcess: String?
    ) {
        val appContext = context.applicationContext ?: context
        if (sourcePackage !in trustedSourcePackages) return
        val intent = ModuleBridgeContract.explicitBridgeIntent()
            .putExtra(
                ModuleBridgeContract.EXTRA_MESSAGE_TYPE,
                ModuleBridgeContract.MESSAGE_RECORD_ACTIVATION
            )
            .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            .putExtra(EXTRA_SOURCE_PROCESS, sourceProcess)
        ModuleBridgeContract.sendWithIdentity(appContext, intent)
    }

    fun readStatus(context: Context): ActivationStatus {
        val prefs = preferences(context)
        val lastActivatedAt = prefs.getLong(KEY_LAST_ACTIVATED_AT, 0L)
        val bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return ActivationStatus(
            isActive = lastActivatedAt >= bootEpoch - 5_000L,
            sourcePackage = prefs.getString(KEY_LAST_SOURCE_PACKAGE, null),
            sourceProcess = prefs.getString(KEY_LAST_SOURCE_PROCESS, null),
            lastActivatedAt = lastActivatedAt
        )
    }

    fun registerStatusListener(
        context: Context,
        onStatusChanged: (ActivationStatus) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == KEY_LAST_ACTIVATED_AT ||
                key == KEY_LAST_SOURCE_PACKAGE ||
                key == KEY_LAST_SOURCE_PROCESS
            ) {
                onStatusChanged(readStatus(context))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterStatusListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    internal fun recordActivation(
        context: Context,
        sourcePackage: String,
        sourceProcess: String?
    ): Boolean {
        if (sourcePackage !in trustedSourcePackages) return false
        return preferences(context)
            .edit()
            .putLong(KEY_LAST_ACTIVATED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SOURCE_PACKAGE, sourcePackage)
            .putString(KEY_LAST_SOURCE_PROCESS, sourceProcess)
            .commit()
    }

    fun recordRunningTargets(context: Context, targets: List<HookedTarget>) {
        val target = targets.firstOrNull { target ->
            target.processName.substringBefore(':') == "com.tencent.wetype"
        } ?: return
        val sourcePackage = target.processName
            .substringBefore(':')
            .let { packageName ->
                if (packageName == "system_server" || packageName == "system") {
                    "android"
                } else {
                    packageName
                }
            }
        recordActivation(
            context = context,
            sourcePackage = sourcePackage,
            sourceProcess = target.processName
        )
    }

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun resolveProcessName(context: Context): String? = runCatching {
        context.applicationInfo.processName ?: context.packageName
    }.getOrNull()
}
