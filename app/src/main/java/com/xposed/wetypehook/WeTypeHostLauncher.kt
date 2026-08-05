package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
private val activeHostDialogs = WeakHashMap<Activity, ComponentDialog>()
private val moduleResourcesCache = HashMap<String, Resources>()

object WeTypeHostLauncher {
    fun show(activity: Activity) {
        activeHostDialogs[activity]?.takeIf { it.isShowing }?.let { return }
        WeTypeSettings.bindModuleBridgePendingIntent(
            ModuleBridgeContract.settingsBridgePendingIntent(activity.intent)
        )

        val moduleContext = runCatching {
            activity.createPackageContext(
                MODULE_PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrElse {
            Log.e("Failed:Create module package context for WeType host dialog")
            Log.i(it)
            createEmbeddedModuleContext(activity)
                ?: return
        }

        val dialog = ComponentDialog(
            ModuleHostContext(activity, moduleContext),
            R.style.Theme_WeTypeHook_HostDialog
        ).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener {
                activeHostDialogs.remove(activity)
                WeTypeSettings.bindModuleBridgePendingIntent(null)
            }
        }
        activeHostDialogs[activity] = dialog
        val windowBackgroundColor = resolveWindowBackgroundColor(dialog.context)

        val composeView = ComposeView(dialog.context).apply {
            setBackgroundColor(windowBackgroundColor)
            setContent {
                WeTypeSettingsApp(
                    settingsContext = activity
                )
            }
        }
        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.show()
        dialog.window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            statusBarColor = windowBackgroundColor
            navigationBarColor = windowBackgroundColor
            setBackgroundDrawable(ColorDrawable(windowBackgroundColor))
        }
    }

    fun prepareForHotReload(): Boolean {
        val cleaned = runOnMainThreadBlocking {
            val dialogs = synchronized(activeHostDialogs) {
                activeHostDialogs.values.toList().also { activeHostDialogs.clear() }
            }
            dialogs.forEach { dialog ->
                dialog.setOnDismissListener(null)
                if (dialog.isShowing) dialog.dismiss()
            }
        }
        if (cleaned) {
            WeTypeSettings.bindModuleBridgePendingIntent(null)
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.clear()
            }
        }
        return cleaned
    }

    private fun createEmbeddedModuleContext(activity: Activity): Context? {
        val moduleApkPath = ModuleRuntime.resolveModuleApkPath()
        if (moduleApkPath == null) {
            Log.e("Failed:Resolve module apk path for embedded WeType host dialog")
            return null
        }
        val moduleResources = runCatching {
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.getOrPut(moduleApkPath) {
                    val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addAssetPath = AssetManager::class.java.getMethod(
                        "addAssetPath",
                        String::class.java
                    )
                    check(addAssetPath.invoke(assetManager, moduleApkPath) as Int != 0) {
                        "Failed to add embedded module asset path: $moduleApkPath"
                    }
                    Resources(
                        assetManager,
                        activity.resources.displayMetrics,
                        activity.resources.configuration
                    )
                }
            }
        }.getOrElse {
            Log.e("Failed:Create embedded module resources for WeType host dialog")
            Log.i(it)
            return null
        }
        return EmbeddedModuleContext(activity, moduleResources)
    }
}

private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return runCatching(block).onFailure {
            Log.e("Failed:Cleanup WeType host dialog for hot reload")
            Log.i(it)
        }.isSuccess
    }

    val completed = CountDownLatch(1)
    var failure: Throwable? = null
    if (!Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
    ) {
        return false
    }
    val finished = runCatching { completed.await(2, TimeUnit.SECONDS) }.getOrDefault(false)
    failure?.let {
        Log.e("Failed:Cleanup WeType host dialog for hot reload")
        Log.i(it)
    }
    return finished && failure == null
}

private fun resolveWindowBackgroundColor(context: Context): Int {
    val isDarkMode =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    return if (isDarkMode) Color.BLACK else Color.parseColor("#F7F7F7")
}

private class ModuleHostContext(
    baseContext: Context,
    private val moduleContext: Context
) : ContextThemeWrapper(baseContext, 0) {
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleContext.resources.newTheme().apply {
            setTo(moduleContext.theme)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleContext.assets
    }

    override fun getResources(): Resources {
        return moduleContext.resources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return moduleContext.packageName
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return moduleContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return moduleContext.classLoader
    }
}

private class EmbeddedModuleContext(
    baseContext: Context,
    private val moduleResources: Resources
) : ContextThemeWrapper(baseContext, 0) {
    private val hostBaseContext = baseContext
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleResources.newTheme().apply {
            setTo(baseContext.theme)
            applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleResources.assets
    }

    override fun getResources(): Resources {
        return moduleResources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return MODULE_PACKAGE_NAME
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return hostBaseContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return WeTypeHostLauncher::class.java.classLoader ?: hostBaseContext.classLoader
    }
}
