package com.xposed.wetypehook

import android.content.IntentFilter
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.getStaticObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.putStaticObject
import com.github.kyuubiran.ezxhelper.utils.sameAs
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.wetype.hook.WeTypeResourceHooks
import com.xposed.wetypehook.wetype.hook.WeTypeWindowHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "miuiime"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val WETYPE_FONT_ASSET = "fonts/WE-Regular.ttf"
private const val MODULE_WETYPE_FONT_ASSET = "WE-Regular.ttf"
private val WETYPE_COLOR_REPLACEMENTS = mapOf(
    "g8" to Color.TRANSPARENT,
    "gb" to Color.TRANSPARENT,
    "k5" to Color.TRANSPARENT,
    "k9" to Color.TRANSPARENT,
    "ng" to Color.TRANSPARENT,
    "pq" to Color.TRANSPARENT
)
private val WETYPE_DRAWABLE_REPLACEMENTS = mapOf(
    "ic" to R.drawable.wetype_ic,
    "gi" to R.drawable.wetype_gi,
    "ib" to R.drawable.wetype_ib,
    "gj" to R.drawable.wetype_gj,
    // Key backgrounds
    "i0" to R.drawable.wetype_key_grey,
    "i1" to R.drawable.wetype_key_grey,
    "i2" to R.drawable.wetype_key_grey_normal,
    "i3" to R.drawable.wetype_key_grey_normal,
    "i4" to R.drawable.wetype_key_grey_pressed,
    "i5" to R.drawable.wetype_key_grey_pressed,
    "i6" to R.drawable.wetype_key_plain,
    "i7" to R.drawable.wetype_key_plain,
    "i8" to R.drawable.wetype_key_plain_normal,
    "i9" to R.drawable.wetype_key_plain_normal,
    "i_" to R.drawable.wetype_key_plain_pressed,
    "ia" to R.drawable.wetype_key_plain_pressed,
    "hu" to R.drawable.wetype_key_green,
    "hv" to R.drawable.wetype_key_green,
    "hw" to R.drawable.wetype_key_green_normal,
    "hx" to R.drawable.wetype_key_green_normal,
    "hy" to R.drawable.wetype_key_green_pressed,
    "hz" to R.drawable.wetype_key_green_pressed,
    "hk" to R.drawable.wetype_key_plain,
    "hl" to R.drawable.wetype_key_plain,
    "hm" to R.drawable.wetype_key_plain_pressed,
    "hn" to R.drawable.wetype_key_plain_pressed,
    "hs" to R.drawable.wetype_key_delete,
    "ht" to R.drawable.wetype_key_delete_dark,
)


class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch"
    )
    private var navBarColor: Int? = null
    private var bottomViewSourceColor: Int? = null
    private lateinit var modulePath: String
    private var moduleAssetManager: AssetManager? = null
    private var moduleResources: Resources? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        EzXHelperInit.setLogTag(TAG)
        Log.i("miuiime is supported")

        if (lpparam.packageName == "android") {
            startPermissionHook()
        } else {
            startHook(lpparam)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val isWeType = lpparam.packageName == WETYPE_PACKAGE
        if (isWeType) {
            WeTypeSettings.initXposed()
            hookWeTypeFont()
            hookWeTypeTransparentColors()
            hookWeTypeXmlDrawables()
            hookWeTypeSelfDrawKeyColors()
            hookWeTypeWindowBlur()
            hookWeTypeWindowCorner()
        }

        // 检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            val sInputMethodServiceInjector =
                loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
                    ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")

            sInputMethodServiceInjector?.also {
                hookSIsImeSupport(it)
                hookIsXiaoAiEnable(it)
                setPhraseBgColor(it, isWeType)
            } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        // 获取常用语的ClassLoader
        findMethod("android.inputmethodservice.InputMethodModuleManager") {
            name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
        }.hookAfter { param ->
            hookDeleteNotSupportIme(
                "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                param.args[0] as ClassLoader
            )
            loadClassOrNull(
                "com.miui.inputmethod.InputMethodBottomManager",
                param.args[0] as ClassLoader
            )?.also {
                if (isNonCustomize) {
                    hookSIsImeSupport(it)
                    hookIsXiaoAiEnable(it)
                }

                // 针对A11的修复切换输入法列表
                it.getDeclaredMethod("getSupportIme").hookReplace { _ ->
                    it.getStaticObject("sBottomViewHelper")
                        .getObjectAs<InputMethodManager>("mImm").enabledInputMethodList
                }
            } ?: Log.e("Failed:Class not found: com.miui.inputmethod.InputMethodBottomManager")
        }

        Log.i("Hook MIUI IME Done!")
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
        WeTypeResourceHooks.hookTransparentColors(WETYPE_COLOR_REPLACEMENTS)
    }

    private fun hookWeTypeSelfDrawKeyColors() {
        WeTypeResourceHooks.hookSelfDrawKeyColors()
    }

    private fun hookWeTypeWindowCorner() {
        WeTypeWindowHooks.hookWindowCorner()
    }

    private fun hookWeTypeWindowBlur() {
        WeTypeWindowHooks.hookWindowBlur()
    }

    private fun getModuleAssetManager(): AssetManager {
        moduleAssetManager?.let { return it }
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        check(addAssetPath.invoke(assetManager, modulePath) as Int != 0) {
            "Failed to add module asset path: $modulePath"
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

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    /**
     * 在适当的时机修改抬高区域背景颜色
     *
     * @param clazz 声明或继承字段的类
     */
    private fun setPhraseBgColor(clazz: Class<*>, forceTransparent: Boolean) {
        kotlin.runCatching {
            // 导航栏颜色被设置后, 将颜色存储起来并传递给常用语
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

            // 当常用语被创建后, 将背景颜色设置为存储的导航栏颜色
            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz, forceTransparent)
            }
        }.onFailure {
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    /**
     * 将导航栏颜色赋值给输入法优化的底图
     *
     * @param clazz 声明或继承字段的类
     */
    private fun customizeBottomViewColor(clazz: Class<*>, forceTransparent: Boolean) {
        if (forceTransparent) {
            val sourceColor = bottomViewSourceColor ?: navBarColor ?: Color.BLACK
            val contentColor = -0x1 - sourceColor
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true,
                Color.TRANSPARENT,
                contentColor or -0x1000000,
                contentColor or 0x66000000
            )
            return
        }

        navBarColor?.let {
            val color = -0x1 - it
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true, navBarColor, color or -0x1000000, color or 0x66000000
            )
        }
    }

    /**
     * 针对A10的修复切换输入法列表
     *
     * @param className 声明或继承方法的类的名称
     */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        kotlin.runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    /**
     * Hook 获取应用列表权限，为所有输入法强制提供获取输入法列表的权限。
     * 用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，导致切换输入法功能不能显示其他输入法的问题。
     * 理论等效于在输入法的AndroidManifest.xml中添加:
     * ```xml
     * <manifest>
     *     <queries>
     *         <intent>
     *             <action android:name="android.view.InputMethod" />
     *         </intent>
     *     </queries>
     * </manifest>
     * ```
     * 当前实现可能影响开机速度，如需此修复需手动设置系统框架作用域。
     */
    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.pm.AppsFilterUtils") {
                name == "canQueryViaComponents"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val querying = param.args[0]
                val potentialTarget = param.args[1]
                if (!isIme(querying)) return@hookAfter
                if (!isIme(potentialTarget)) return@hookAfter
                param.result = true
            }
        }.onFailure {
            Log.i("Failed: Hook method canQueryViaComponents")
            Log.i(it)
        }
    }

    private fun isIme(androidPackage: Any): Boolean {
        val services = androidPackage.invokeMethodAs<List<Any>>("getServices")
        services?.forEach { service ->
            if (!service.invokeMethodAs<Boolean>("isExported")!!) return@forEach
            val intents = service.invokeMethodAs<List<Any>>("getIntents")
            intents?.forEach { intent ->
                val intentFilter = intent.invokeMethodAs<IntentFilter>("getIntentFilter")
                if (intentFilter?.matchAction("android.view.InputMethod") == true) {
                    return true
                }
            }
        }
        return false
    }
}
