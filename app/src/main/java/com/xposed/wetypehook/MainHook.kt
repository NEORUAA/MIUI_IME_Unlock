package com.xposed.wetypehook
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.xposed.wetypehook.wetype.hook.WeTypeResourceHooks
import com.xposed.wetypehook.wetype.hook.WeTypeUpdateHooks
import com.xposed.wetypehook.wetype.hook.WeTypeWindowHooks
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.getStaticObject
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReplace
import com.xposed.wetypehook.xposed.hookReturnConstant
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.invokeStaticMethodAuto
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.xposed.putStaticObject
import com.xposed.wetypehook.xposed.sameAs
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "miuiime"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val INPUT_METHOD_BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager"
private const val WETYPE_ABOUT_ACTIVITY = "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
private const val WETYPE_ABOUT_LOGO_TAG_KEY = 0x4D495549
private const val WETYPE_FONT_ASSET = "fonts/WE-Regular.ttf"
private const val MODULE_WETYPE_FONT_ASSET = "WE-Regular.ttf"
private const val TRANSPARENT_BOTTOM_VIEW_DARK_CONTENT = 0xFFF5F5F5.toInt()
private const val TRANSPARENT_BOTTOM_VIEW_LIGHT_CONTENT = 0xFF202020.toInt()

private val WETYPE_COLOR_REPLACEMENTS = mapOf(
    "ime_skin_candidate_end_color" to Color.TRANSPARENT,
    "ime_skin_candidate_start_color" to Color.TRANSPARENT,
    "ime_skin_dark_candidate_end_color" to Color.TRANSPARENT,
    "ime_skin_dark_candidate_start_color" to Color.TRANSPARENT,
    "ime_skin_dark_keyboard_end_color" to Color.TRANSPARENT,
    "ime_skin_keyboard_end_color" to Color.TRANSPARENT
)
private val WETYPE_DRAWABLE_REPLACEMENTS = mapOf(
    "ime_emoji_keyboard_gradient_bg_color" to R.drawable.wetype_full_gradient_bg,
    "ime_keyboard_full_gradient_bg_color" to R.drawable.wetype_full_gradient_bg,
    "ime_emoji_keyboard_gradient_bg_color_dark" to R.drawable.wetype_full_gradient_bg_dark,
    "ime_keyboard_full_gradient_bg_color_dark" to R.drawable.wetype_full_gradient_bg_dark
)

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private val miuiImeList = setOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type"
    )
    private val installedHookTokens = ConcurrentHashMap.newKeySet<String>()
    private var navBarColor: Int? = null
    private var bottomViewSourceColor: Int? = null
    private lateinit var modulePath: String
    private var moduleAssetManager: AssetManager? = null
    private var moduleResources: Resources? = null
    private var assetManagerAddAssetPathMethod: Method? = null
    private var viewListenerInfoField: Field? = null
    private var onClickListenerField: Field? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        ModuleRuntime.updateModuleApkPath(startupParam.modulePath)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookEnvironment.init(lpparam.classLoader, TAG)

        if (lpparam.packageName != WETYPE_PACKAGE && PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") {
            return
            }
        if (lpparam.packageName == "android") {
            if (isMiuiImeSupport) {
                startPermissionHook()
            }
        } else {
            startHook(lpparam, isMiuiImeSupport)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam, isMiuiImeSupport: Boolean) {
        val packageName = lpparam.packageName
        val isWeType = packageName == WETYPE_PACKAGE

        if (isWeType) {
            installWeTypeHooks(packageName)
        }

        if (!isMiuiImeSupport) return

        Log.i("miuiime is supported")

        val isNonCustomize = packageName !in miuiImeList
        if (isNonCustomize) {
            installBaseImeHooks(isWeType)
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        hookInputMethodModuleManager(isNonCustomize)

        Log.i("Hook MIUI IME Done!")
    }

    private fun installWeTypeHooks(sourcePackage: String) {
        hookActivationHeartbeat(sourcePackage)
        WeTypeSettings.configureStorage(sourcePackage)
        WeTypeSettings.initXposed()
        hookWeTypeFont()
        hookWeTypeTransparentColors()
        hookWeTypeXmlDrawables()
        hookWeTypeSelfDrawKeyColors()
        hookWeTypeCandidateSpecialTextColor()
        hookWeTypeCandidateBackgroundAlpha()
        hookWeTypeCandidateBackgroundLeftMargin()
        hookWeTypeCandidateBackgroundCorner()
        hookWeTypeCandidatePinyinLeftMargin()
        hookWeTypeSettingKeyboardOpaqueBackground()
        hookWeTypeWindowBlur()
        hookWeTypeWindowCorner()
        hookWeTypeDisableHotUpdate()
        hookWeTypeIntentEntry()
        hookWeTypeAboutLogoEntry()
        WeTypeResourceHooks.hookKeyboardLogo()
        WeTypeResourceHooks.hookToolbarIconBackground()
    }

    private fun installBaseImeHooks(forceTransparentBottomView: Boolean) {
        val injectorClass = loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
            ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")
        injectorClass?.let { clazz ->
            hookSIsImeSupport(clazz)
            hookIsXiaoAiEnable(clazz)
            setPhraseBgColor(clazz, forceTransparentBottomView)
        } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
    }

    private fun hookInputMethodModuleManager(isNonCustomize: Boolean) {
        runCatching {
            findMethod("android.inputmethodservice.InputMethodModuleManager") {
                name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
            }.hookBefore { param ->
                runCatching {
                    val targetClassLoader = param.args[0] as? ClassLoader ?: return@runCatching
                    val dexPath = param.args[1] as? String ?: return@runCatching
                    if (targetClassLoader !is BaseDexClassLoader) return@runCatching

                    if (!isBottomManagerLoaded(targetClassLoader)) {
                        targetClassLoader.invokeMethodAs<Any?>("addDexPath", dexPath)
                    }
                    installBottomManagerHooks(targetClassLoader, isNonCustomize)
                    param.result = null
                }.onFailure {
                    Log.e("Failed:Handle InputMethodModuleManager.loadDex")
                    Log.i(it)
                }
            }
        }.onFailure {
            Log.e("Failed:Hook InputMethodModuleManager.loadDex")
            Log.i(it)
        }
    }

    private fun isBottomManagerLoaded(classLoader: ClassLoader): Boolean =
        runCatching {
            Class.forName(INPUT_METHOD_BOTTOM_MANAGER, true, classLoader)
        }.isSuccess

    private fun installBottomManagerHooks(classLoader: ClassLoader, isNonCustomize: Boolean) {
        hookDeleteNotSupportIme(
            "$INPUT_METHOD_BOTTOM_MANAGER\$MiuiSwitchInputMethodListener",
            classLoader
        )
        val bottomManagerClass = loadClassOrNull(INPUT_METHOD_BOTTOM_MANAGER, classLoader) ?: run {
            Log.e("Failed:Class not found: $INPUT_METHOD_BOTTOM_MANAGER")
            return
        }

        if (isNonCustomize) {
            hookSIsImeSupport(bottomManagerClass)
            hookIsXiaoAiEnable(bottomManagerClass)
        }
        hookSupportImeList(bottomManagerClass)
    }

    private fun hookSupportImeList(clazz: Class<*>) {
        val token = classHookToken("getSupportIme", clazz)
        if (!installedHookTokens.add(token)) return

        runCatching {
            clazz.getDeclaredMethod("getSupportIme").apply {
                isAccessible = true
            }.hookReplace { _ ->
                val bottomViewHelper = clazz.getStaticObject("sBottomViewHelper") ?: return@hookReplace null
                bottomViewHelper.getObjectAs<InputMethodManager>("mImm")?.enabledInputMethodList
            }
        }.onFailure {
            installedHookTokens.remove(token)
            Log.e("Failed:Hook method getSupportIme")
            Log.i(it)
        }
    }

    private fun hookActivationHeartbeat(sourcePackage: String) {
        findMethod("android.app.Application") {
            name == "attach" && parameterTypes.sameAs(Context::class.java)
        }.hookAfter { param ->
            val context = param.args[0] as? Context ?: return@hookAfter
            WeTypeSettings.ensureHostSnapshot(context)
            notifyActivationHeartbeat(context, sourcePackage)
        }

        findMethod("android.inputmethodservice.InputMethodService") {
            name == "onStartInputView" && parameterTypes.size == 2
        }.hookAfter { param ->
            val service = param.thisObject as? InputMethodService ?: return@hookAfter
            if (service.packageName != sourcePackage) return@hookAfter
            WeTypeSettings.ensureHostSnapshot(service)
            notifyActivationHeartbeat(service, sourcePackage)
        }
    }

    private fun notifyActivationHeartbeat(context: Context, sourcePackage: String) {
        ModuleActivationTracker.notifyActivationFromHook(
            context = context,
            sourcePackage = sourcePackage,
            sourceProcess = runCatching {
                context.applicationInfo.processName ?: context.packageName
            }.getOrNull()
        )
    }

    private fun hookWeTypeFont() {
        WeTypeResourceHooks.hookFont(
            fontAsset = WETYPE_FONT_ASSET,
            moduleFontAsset = MODULE_WETYPE_FONT_ASSET,
            getModuleAssetManager = ::getModuleAssetManager
        )
    }

    private fun hookWeTypeXmlDrawables() {
        WeTypeResourceHooks.hookXmlDrawables(
            drawableReplacements = WETYPE_DRAWABLE_REPLACEMENTS,
            getModuleResources = ::getModuleResources
        )
    }

    private fun hookWeTypeTransparentColors() {
        WeTypeResourceHooks.hookAppearanceColors(WETYPE_COLOR_REPLACEMENTS)
    }

    private fun hookWeTypeSelfDrawKeyColors() {
        WeTypeResourceHooks.hookSelfDrawKeyColors()
    }

    private fun hookWeTypeCandidateSpecialTextColor() {
        WeTypeResourceHooks.hookCandidateSpecialTextColor()
    }

    private fun hookWeTypeCandidateBackgroundAlpha() {
        WeTypeResourceHooks.hookCandidateBackgroundAlpha()
    }

    private fun hookWeTypeCandidateBackgroundLeftMargin() {
        WeTypeResourceHooks.hookCandidateBackgroundLeftMargin()
    }

    private fun hookWeTypeCandidateBackgroundCorner() {
        WeTypeResourceHooks.hookCandidateBackgroundCorner()
    }

    private fun hookWeTypeCandidatePinyinLeftMargin() {
        WeTypeResourceHooks.hookCandidatePinyinLeftMargin()
    }

    private fun hookWeTypeSettingKeyboardOpaqueBackground() {
        WeTypeResourceHooks.hookSettingKeyboardOpaqueBackground()
    }

    private fun hookWeTypeWindowCorner() {
        WeTypeWindowHooks.hookWindowCorner()
    }

    private fun hookWeTypeDisableHotUpdate() {
        WeTypeUpdateHooks.hookDisableHotUpdate()
    }

    private fun hookWeTypeWindowBlur() {
        WeTypeWindowHooks.hookWindowBlur()
    }

    private fun hookWeTypeIntentEntry() {
        runCatching {
            findMethod("android.app.Activity") {
                name == "onResume" && parameterTypes.isEmpty()
            }.hookAfter { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                val intent = activity.intent ?: return@hookAfter
                if (!intent.getBooleanExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, false)) return@hookAfter
                intent.removeExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS)
                activity.window?.decorView?.post {
                    WeTypeHostLauncher.show(activity)
                }
            }
        }.onFailure {
            Log.e("Failed:Hook WeType intent entry")
            Log.i(it)
        }
    }

    private fun hookWeTypeAboutLogoEntry() {
        runCatching {
            findMethod(WETYPE_ABOUT_ACTIVITY) {
                name == "onResume" && parameterTypes.isEmpty()
            }.hookAfter { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfter
                activity.window?.decorView?.post {
                    hookWeTypeAboutLogoClick(activity)
                }
            }
        }.onFailure {
            Log.e("Failed:Hook WeType about logo entry")
            Log.i(it)
        }
    }

    private fun hookWeTypeAboutLogoClick(activity: Activity) {
        runCatching {
            val decorView = activity.window?.decorView ?: return
            val logoView = findFirstViewByClassName(decorView, "com.tencent.wetype.plugin.hld.view.ImeRadiusImageView") ?: return
            if (logoView.getTag(WETYPE_ABOUT_LOGO_TAG_KEY) == true) return

            val originalClickListener = resolveOnClickListener(logoView)
            logoView.isClickable = true
            logoView.setTag(WETYPE_ABOUT_LOGO_TAG_KEY, true)
            logoView.setOnClickListener { view ->
                runCatching {
                    originalClickListener?.onClick(view)
                }.onFailure {
                    Log.e("Failed:Invoke original WeType about logo listener")
                    Log.i(it)
                }
                WeTypeHostLauncher.show(activity)
            }
        }.onFailure {
            Log.e("Failed:Attach WeType about logo click hook")
            Log.i(it)
        }
    }

    private fun findFirstViewByClassName(view: View?, className: String): View? {
        if (view == null) return null
        if (view.javaClass.name == className) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstViewByClassName(view.getChildAt(i), className)
                if (found != null) return found
            }
        }
        return null
    }

    private fun resolveOnClickListener(view: View): View.OnClickListener? {
        return runCatching {
            val listenerInfoField = viewListenerInfoField ?: View::class.java.getDeclaredField("mListenerInfo").apply {
                isAccessible = true
            }.also { viewListenerInfoField = it }
            val listenerInfo = listenerInfoField.get(view) ?: return null
            val clickListenerField = onClickListenerField ?: listenerInfo.javaClass.getDeclaredField("mOnClickListener").apply {
                isAccessible = true
            }.also { onClickListenerField = it }
            clickListenerField.get(listenerInfo) as? View.OnClickListener
        }.getOrNull()
    }

    private fun getModuleAssetManager(): AssetManager {
        moduleAssetManager?.let { return it }
        val resolvedModulePath = ModuleRuntime.resolveModuleApkPath()
            ?: modulePath.takeIf { ::modulePath.isInitialized }
            ?: error("Module apk path is unavailable")
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = assetManagerAddAssetPathMethod ?: AssetManager::class.java.getMethod(
            "addAssetPath",
            String::class.java
        ).also { assetManagerAddAssetPathMethod = it }
        check(addAssetPath.invoke(assetManager, resolvedModulePath) as Int != 0) {
            "Failed to add module asset path: $resolvedModulePath"
        }
        moduleAssetManager = assetManager
        return assetManager
    }

    private fun getModuleResources(baseResources: Resources): Resources {
        moduleResources?.let { return it }
        return Resources(
            getModuleAssetManager(),
            baseResources.displayMetrics,
            baseResources.configuration
        ).also { moduleResources = it }
    }

    private fun hookSIsImeSupport(clazz: Class<*>) {
        runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        val token = classHookToken("isXiaoAiEnable", clazz)
        if (!installedHookTokens.add(token)) return

        runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            installedHookTokens.remove(token)
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    private fun setPhraseBgColor(clazz: Class<*>, forceTransparent: Boolean) {
        val token = classHookToken("phraseBgColor:${forceTransparent}", clazz)
        if (!installedHookTokens.add(token)) return

        runCatching {
            val setNavigationBarColorMethod = findMethod("com.android.internal.policy.PhoneWindow") {
                name == "setNavigationBarColor" && parameterTypes.sameAs(Int::class.java)
            }
            setNavigationBarColorMethod.hookBefore { param ->
                if (forceTransparent) {
                    bottomViewSourceColor = param.args[0] as? Int
                    param.args[0] = Color.TRANSPARENT
                }
            }
            setNavigationBarColorMethod.hookAfter { param ->
                if (forceTransparent) {
                    navBarColor = Color.TRANSPARENT
                    customizeBottomViewColor(clazz, true)
                    return@hookAfter
                }
                if (param.args[0] == 0) return@hookAfter

                navBarColor = param.args[0] as Int
                customizeBottomViewColor(clazz, false)
            }

            clazz.findMethod { name == "customizeBottomViewColor" }.hookBefore { param ->
                if (!forceTransparent) return@hookBefore
                if (param.args.size > 1 && param.args[1] is Int) {
                    param.args[1] = Color.TRANSPARENT
                }
            }

            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz, forceTransparent)
            }
        }.onFailure {
            installedHookTokens.remove(token)
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    private fun customizeBottomViewColor(clazz: Class<*>, forceTransparent: Boolean) {
        if (forceTransparent) {
            val contentColor = resolveTransparentBottomViewContentColor()
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true,
                Color.TRANSPARENT,
                contentColor,
                withAlpha(contentColor, 0x66)
            )
            return
        }

        navBarColor?.let { colorValue ->
            val invertedColor = -0x1 - colorValue
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true,
                colorValue,
                invertedColor or -0x1000000,
                invertedColor or 0x66000000
            )
        }
    }

    private fun resolveTransparentBottomViewContentColor(): Int {
        val isDarkMode =
            Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) TRANSPARENT_BOTTOM_VIEW_DARK_CONTENT else TRANSPARENT_BOTTOM_VIEW_LIGHT_CONTENT
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        val token = "$className@${System.identityHashCode(classLoader)}:deleteNotSupportIme"
        if (!installedHookTokens.add(token)) return

        runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            installedHookTokens.remove(token)
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.inputmethod.InputMethodManagerServiceImpl") {
                name == "isCallingBetweenCustomIME"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val context = param.args[0] as? Context ?: return@hookAfter
                val uid = param.args[1] as? Int ?: return@hookAfter
                val currentInputMethodPackageName = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/') ?: return@hookAfter
                val packagesForUid = context.packageManager.getPackagesForUid(uid) ?: return@hookAfter
                if (packagesForUid.contains(currentInputMethodPackageName)) {
                    param.result = true
                }
            }
        }.onFailure {
            Log.i("Failed: Hook method isCallingBetweenCustomIME")
            Log.i(it)
        }
    }

    private fun classHookToken(hookName: String, clazz: Class<*>): String =
        "${clazz.name}@${System.identityHashCode(clazz.classLoader)}:$hookName"
}
