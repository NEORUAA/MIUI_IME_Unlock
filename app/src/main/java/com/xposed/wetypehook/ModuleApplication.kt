package com.xposed.wetypehook

import android.app.Application
import android.util.Log
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        private const val TAG = "MIUIIME.Service"

        @Volatile
        var xposedService: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        WeTypeSettings.unbindRemotePreferences()
        runCatching {
            if (service.apiVersion < XposedService.API_102) {
                Log.w(TAG, "Framework service API ${service.apiVersion} does not support API 102")
                return@runCatching
            }

            if (service.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) {
                WeTypeSettings.bindRemotePreferences(
                    service.getRemotePreferences(WeTypeSettings.PREF_GROUP)
                )
                WeTypeSettings.synchronizeRemotePreferences(this)
            }

            val targets = service.runningTargets
            ModuleActivationTracker.recordRunningTargets(this, targets)
            targets
                .filter { target -> target.state == HookedTarget.State.STALE }
                .forEach { target -> requestHotReload(service, target) }
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize libxposed service", error)
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService !== service) return
        xposedService = null
        WeTypeSettings.unbindRemotePreferences()
    }

    private fun requestHotReload(service: XposedService, target: HookedTarget) {
        runCatching {
            service.hotReloadModule(target, null) { reloadedTarget, result ->
                when (result.status) {
                    HotReloadResult.Status.SUCCEEDED -> {
                        Log.i(TAG, "Hot reloaded ${reloadedTarget.processName}")
                        runCatching {
                            ModuleActivationTracker.recordRunningTargets(
                                this,
                                service.runningTargets
                            )
                        }
                    }

                    else -> Log.w(
                        TAG,
                        "Hot reload ${reloadedTarget.processName}: ${result.status} ${result.message.orEmpty()}"
                    )
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to request hot reload for ${target.processName}", error)
        }
    }
}
