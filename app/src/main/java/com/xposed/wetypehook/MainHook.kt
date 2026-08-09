package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "miuiime"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val MIUI_PHRASE_PACKAGE = "com.miui.phrase"
private const val MIUI_INPUT_PROVIDER = "com.miui.provider.InputProvider"
private const val INPUT_METHOD_BOTTOM_MANAGER = "com.miui.inputmethod.InputMethodBottomManager"
private const val WETYPE_ABOUT_ACTIVITY = "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
private const val WETYPE_ABOUT_LOGO_TAG_KEY = 0x4D495549
private const val WETYPE_FONT_ASSET = "fonts/WE-Regular.ttf"
private const val MODULE_WETYPE_FONT_ASSET = "WE-Regular.ttf"
private const val TRANSPARENT_BOTTOM_VIEW_DARK_CONTENT = 0xFFF5F5F5.toInt()
private const val TRANSPARENT_BOTTOM_VIEW_LIGHT_CONTENT = 0xFF202020.toInt()
private const val TARGET_KIND_SYSTEM = "system"
private const val TARGET_KIND_PACKAGE = "package"
private const val TARGET_KIND_PHRASE = "phrase"
private const val STATE_TARGET_KIND = "target_kind"
private const val STATE_PACKAGE_NAME = "package_name"
private const val STATE_SOURCE_DIR = "source_dir"
private const val STATE_TARGETS = "targets"

private val WETYPE_COLOR_REPLACEMENTS = mapOf(
    "ime_skin_candidate_end_color" to Color.TRANSPARENT,
    "ime_skin_candidate_start_color" to Color.TRANSPARENT,
    "ime_skin_dark_candidate_end_color" to Color.TRANSPARENT,
    "ime_skin_dark_candidate_start_color" to Color.TRANSPARENT,
    "ime_skin_dark_keyboard_end_color" to Color.TRANSPARENT,
    "ime_skin_keyboard_end_color" to Color.TRANSPARENT
)
class MainHook : XposedModule() {
    private val miuiImeList = setOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type"
    )
    private val installedHookTokens = ConcurrentHashMap.newKeySet<String>()
    private val monitoredImeInputFrames = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val imeInputFrameLayoutListeners = WeakHashMap<ViewGroup, View.OnLayoutChangeListener>()
    private val originalImeContentBottomPaddings = WeakHashMap<View, Int>()
    private val originalFullscreenAreaHeights = WeakHashMap<ViewGroup, IntArray>()
    private val adjustedImeContentViews = WeakHashMap<ViewGroup, WeakReference<View>>()
    private val miuiBottomFrameViews = WeakHashMap<
        ViewGroup,
        Triple<WeakReference<ViewGroup>, WeakReference<View>, WeakReference<View>>
    >()
    private var navBarColor: Int? = null
    private var bottomViewSourceColor: Int? = null
    private var modulePath: String? = null
    private var moduleAssetManager: AssetManager? = null
    private var assetManagerAddAssetPathMethod: Method? = null
    private var viewListenerInfoField: Field? = null
    private var onClickListenerField: Field? = null
    private val originalAboutLogoStates = WeakHashMap<View, AboutLogoState>()

    private val activeTargets = LinkedHashMap<String, ActiveTarget>()

    private data class ActiveTarget(
        val kind: String,
        val packageName: String? = null,
        val sourceDir: String? = null
    )

    private data class AboutLogoState(
        val clickListener: View.OnClickListener?,
        val isClickable: Boolean
    )

    private val supportedPackages = setOf(
        MIUI_PHRASE_PACKAGE,
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.google.android.inputmethod.latin",
        WETYPE_PACKAGE,
        "com.xiaomi.type"
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookEnvironment.attach(this, null, TAG)
        modulePath = moduleApplicationInfo.sourceDir
        ModuleRuntime.updateModuleApkPath(modulePath)
        Log.i(
            "Loaded in ${param.processName}: $frameworkName $frameworkVersion " +
                "($frameworkVersionCode), API $apiVersion, properties=0x${frameworkProperties.toString(16)}"
        )

    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (frameworkProperties and XposedInterface.PROP_CAP_SYSTEM == 0L) {
            Log.i("Skip system_server: framework does not advertise system process support")
            return
        }
        HookEnvironment.updateClassLoader(param.classLoader)
        recordActiveTarget(ActiveTarget(TARGET_KIND_SYSTEM))
        val isMiuiImeSupport = PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] == "1"
        if (isMiuiImeSupport) {
            HookEnvironment.withHookScope("system.permission") {
                startPermissionHook()
            }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName !in supportedPackages) {
            if (param.isFirstPackage) {
                Log.i("Detach from out-of-scope first package $packageName")
                detach()
            }
            return
        }

        HookEnvironment.updateClassLoader(param.classLoader)
        val isMiuiImeSupport = PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] == "1"
        if (packageName == MIUI_PHRASE_PACKAGE) {
            recordActiveTarget(ActiveTarget(
                kind = TARGET_KIND_PHRASE,
                packageName = packageName,
                sourceDir = param.applicationInfo.sourceDir
            ))
            if (isMiuiImeSupport) {
                HookEnvironment.withHookScope("phrase.validation") {
                    startPackageValidationHook(param.applicationInfo.sourceDir, param.classLoader)
                }
            }
        } else {
            recordActiveTarget(ActiveTarget(TARGET_KIND_PACKAGE, packageName))
            startHook(packageName, param.classLoader, isMiuiImeSupport)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(activeTargetsBundle())
        HookEnvironment.prepareForHotReload()

        val hostClean = WeTypeHostLauncher.prepareForHotReload()
        val windowClean = WeTypeWindowHooks.prepareForHotReload()
        val resourcesClean = runOnMainThreadBlocking {
            WeTypeResourceHooks.prepareForHotReload()
        }
        val mainClean = cleanupExternalState()
        WeTypeSettings.prepareForHotReload()
        if (!hostClean || !windowClean || !resourcesClean || !mainClean) {
            Log.e("Reject hot reload because target-process callbacks could not be fully cleaned")
            return false
        }
        Log.i("Old generation is ready for hot reload")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        HookEnvironment.attach(this, null, TAG)
        modulePath = moduleApplicationInfo.sourceDir
        ModuleRuntime.updateModuleApkPath(modulePath)
        val targets = (param.savedInstanceState as? Bundle).toActiveTargets()
        synchronized(activeTargets) {
            activeTargets.clear()
            targets.forEach(::recordActiveTargetLocked)
        }
        if (targets.isEmpty()) {
            Log.i("Hot reload has no active target state; remove old hooks")
            HookEnvironment.finishHotReload(param.oldHookHandles)
            return
        }

        val isMiuiImeSupport = PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] == "1"
        val bottomManagersToReconcile = LinkedHashSet<Class<*>>()
        val packageNamesToReconcile = LinkedHashSet<String>()
        targets.forEach { target ->
            val classLoader = resolveHotReloadClassLoader(target, param.oldHookHandles)
            HookEnvironment.updateClassLoader(classLoader)
            when (target.kind) {
                TARGET_KIND_SYSTEM -> if (isMiuiImeSupport) {
                    HookEnvironment.withHookScope("system.permission") { startPermissionHook() }
                }

                TARGET_KIND_PHRASE -> if (isMiuiImeSupport && target.sourceDir != null) {
                    HookEnvironment.withHookScope("phrase.validation") {
                        startPackageValidationHook(target.sourceDir, classLoader)
                    }
                }

                TARGET_KIND_PACKAGE -> target.packageName?.let { packageName ->
                    startHook(packageName, classLoader, isMiuiImeSupport)
                    bottomManagersToReconcile += reinstallDynamicBottomManagerHooks(
                        param.oldHookHandles,
                        packageName
                    )
                    packageNamesToReconcile += packageName
                }
            }
        }
        HookEnvironment.finishHotReload(param.oldHookHandles)
        runOnMainThreadBlocking {
            bottomManagersToReconcile.forEach(::reconcileCurrentImeFrame)
        }
        if (WETYPE_PACKAGE in packageNamesToReconcile) reconcileCurrentWeTypeUiAfterHotReload()
        Log.i("Hot reload completed for ${targets.joinToString { it.packageName ?: it.kind }}")
    }

    private fun startHook(
        packageName: String,
        classLoader: ClassLoader,
        isMiuiImeSupport: Boolean
    ) {
        val isWeType = packageName == WETYPE_PACKAGE

        if (isWeType) {
            installWeTypeHooks(packageName)
        }

        if (!isMiuiImeSupport) return

        Log.i("miuiime is supported")

        val isNonCustomize = packageName !in miuiImeList
        if (isNonCustomize) {
            HookEnvironment.withHookScope("miui.base") {
                installBaseImeHooks(isWeType)
            }
        }

        HookEnvironment.withHookScope("miui.delete-unsupported") {
            hookDeleteNotSupportIme(
                "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
                classLoader
            )
        }

        HookEnvironment.withHookScope("miui.dynamic-loader") {
            hookInputMethodModuleManager(isNonCustomize)
        }

        Log.i("Hook MIUI IME Done!")
    }

    private fun installWeTypeHooks(sourcePackage: String) {
        if (frameworkProperties and XposedInterface.PROP_CAP_REMOTE != 0L) {
            runCatching {
                WeTypeSettings.bindRemotePreferences(
                    getRemotePreferences(WeTypeSettings.PREF_GROUP)
                )
            }.onFailure { error ->
                Log.e("Remote preferences are unavailable; WeType hooks use defaults")
                Log.i(error)
            }
        } else {
            Log.i("Remote preferences are unavailable; WeType hooks use defaults")
        }

        HookEnvironment.withHookScope("wetype.activation") { hookActivationHeartbeat(sourcePackage) }
        HookEnvironment.withHookScope("wetype.font") { hookWeTypeFont() }
        HookEnvironment.withHookScope("wetype.colors") { hookWeTypeTransparentColors() }
        HookEnvironment.withHookScope("wetype.overlay-underlay") { WeTypeWindowHooks.hookTransparentOverlayUnderlay() }
        HookEnvironment.withHookScope("wetype.self-draw-colors") { hookWeTypeSelfDrawKeyColors() }
        HookEnvironment.withHookScope("wetype.key-corner") { hookWeTypeKeyboardKeyCorner() }
        HookEnvironment.withHookScope("wetype.candidate-text") { hookWeTypeCandidateSpecialTextColor() }
        HookEnvironment.withHookScope("wetype.candidate-alpha") { hookWeTypeCandidateBackgroundAlpha() }
        HookEnvironment.withHookScope("wetype.candidate-margin") { hookWeTypeCandidateBackgroundLeftMargin() }
        HookEnvironment.withHookScope("wetype.candidate-corner") { hookWeTypeCandidateBackgroundCorner() }
        HookEnvironment.withHookScope("wetype.pinyin-margin") { hookWeTypeCandidatePinyinLeftMargin() }
        HookEnvironment.withHookScope("wetype.settings-background") { hookWeTypeSettingKeyboardOpaqueBackground() }
        HookEnvironment.withHookScope("wetype.window-blur") { hookWeTypeWindowBlur() }
        HookEnvironment.withHookScope("wetype.window-corner") { hookWeTypeWindowCorner() }
        HookEnvironment.withHookScope("wetype.disable-update") { hookWeTypeDisableHotUpdate() }
        HookEnvironment.withHookScope("wetype.intent-entry") { hookWeTypeIntentEntry() }
        HookEnvironment.withHookScope("wetype.about-entry") { hookWeTypeAboutLogoEntry() }
        HookEnvironment.withHookScope("wetype.keyboard-logo") { WeTypeResourceHooks.hookKeyboardLogo() }
        HookEnvironment.withHookScope("wetype.toolbar-icon") { WeTypeResourceHooks.hookToolbarIconBackground() }
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
                    HookEnvironment.withHookScope("miui.dynamic-bottom") {
                        installBottomManagerHooks(targetClassLoader, isNonCustomize)
                    }
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
            hookMiuiBottomInsetCompatibility(bottomManagerClass)
        }
        hookSupportImeList(bottomManagerClass)
    }

    private fun hookMiuiBottomInsetCompatibility(clazz: Class<*>) {
        val token = classHookToken("miuiBottomInsetCompatibility", clazz)
        if (!installedHookTokens.add(token)) return

        val hookInstalled = runCatching {
            clazz.findMethod {
                name == "addMiuiBottomView" &&
                    Modifier.isStatic(modifiers) &&
                    parameterTypes.size >= 6 &&
                    Context::class.java.isAssignableFrom(parameterTypes[0]) &&
                    LayoutInflater::class.java.isAssignableFrom(parameterTypes[1]) &&
                    ViewGroup::class.java.isAssignableFrom(parameterTypes[2]) &&
                    ViewGroup::class.java.isAssignableFrom(parameterTypes[3]) &&
                    View::class.java.isAssignableFrom(parameterTypes[4]) &&
                    View::class.java.isAssignableFrom(parameterTypes[5])
            }.hookAfter { param ->
                val fullscreenArea = param.args.getOrNull(2) as? ViewGroup ?: return@hookAfter
                val inputFrame = param.args.getOrNull(3) as? ViewGroup ?: return@hookAfter
                val rootView = param.args.getOrNull(4) as? View ?: return@hookAfter
                val bottomArea = param.args.getOrNull(5) as? View ?: return@hookAfter
                registerMiuiBottomFrame(fullscreenArea, inputFrame, rootView, bottomArea)
            }
            true
        }.onFailure {
            installedHookTokens.remove(token)
            Log.i("Failed:Hook MIUI bottom inset compatibility")
            Log.i(it)
        }.getOrDefault(false)
        if (!hookInstalled) return

        clazz.declaredMethods
            .filter { it.name == "onWindowShown" || it.name == "changeViewForMiuiBottom" }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.hookAfter {
                        reconcileCurrentImeFrame(clazz)
                    }
                }.onFailure {
                    Log.i("Failed:Hook MIUI bottom inset lifecycle method ${method.name}")
                    Log.i(it)
                }
            }
    }

    private fun registerMiuiBottomFrame(
        fullscreenArea: ViewGroup,
        inputFrame: ViewGroup,
        rootView: View,
        bottomArea: View
    ) {
        miuiBottomFrameViews[inputFrame] = Triple(
            WeakReference(fullscreenArea),
            WeakReference(rootView),
            WeakReference(bottomArea)
        )
        if (monitoredImeInputFrames.add(inputFrame)) {
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                reconcileMiuiBottomFrame(inputFrame)
            }
            imeInputFrameLayoutListeners[inputFrame] = listener
            inputFrame.addOnLayoutChangeListener(listener)
        }
        HookEnvironment.postTracked(inputFrame) { reconcileMiuiBottomFrame(inputFrame) }
    }

    private fun reconcileCurrentImeFrame(clazz: Class<*>) {
        val helper = runCatching { clazz.getStaticObject("sBottomViewHelper") }.getOrNull()
        val currentInputFrame = helper?.getObjectAs<ViewGroup>("mInputFrame")
        if (currentInputFrame != null && !miuiBottomFrameViews.containsKey(currentInputFrame)) {
            val fullscreenArea = helper.getObjectAs<ViewGroup>("mFullscreenArea")
            val rootView = helper.getObjectAs<View>("mRootView")
            val bottomArea = helper.getObjectAs<View>("mMiuiBottomArea")
            if (fullscreenArea != null && rootView != null && bottomArea != null) {
                registerMiuiBottomFrame(fullscreenArea, currentInputFrame, rootView, bottomArea)
            } else {
                Log.i("Failed:Rebind current MIUI bottom frame after hot reload")
            }
        }
        val inputFrames = currentInputFrame?.let(::listOf)
            ?: miuiBottomFrameViews.keys.toList()
        inputFrames.forEach { inputFrame ->
            HookEnvironment.postTracked(inputFrame) { reconcileMiuiBottomFrame(inputFrame) }
            HookEnvironment.postTracked(inputFrame, 100L) { reconcileMiuiBottomFrame(inputFrame) }
        }
    }

    private fun reconcileMiuiBottomFrame(inputFrame: ViewGroup) {
        val frameViews = miuiBottomFrameViews[inputFrame] ?: return
        val fullscreenArea = frameViews.first.get() ?: return
        val rootView = frameViews.second.get() ?: return
        val bottomArea = frameViews.third.get() ?: return
        val contentView = (0 until inputFrame.childCount)
            .firstNotNullOfOrNull { index ->
                inputFrame.getChildAt(index).takeIf { it.visibility == View.VISIBLE }
            }
        val navigationInset = rootView.rootWindowInsets
            ?.getInsets(WindowInsets.Type.navigationBars())
            ?.bottom

        if (navigationInset == null || navigationInset <= 0 ||
            !isBottomAreaActive(rootView, inputFrame, bottomArea, navigationInset)
        ) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }
        if (contentView == null) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }
        val adjustedContentReference = adjustedImeContentViews[inputFrame]
        val adjustedContentView = adjustedContentReference?.get()
        if (adjustedContentReference != null && adjustedContentView !== contentView) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }

        val originalPadding = originalImeContentBottomPaddings[contentView]
        val isCurrentContentAdjusted = adjustedImeContentViews[inputFrame]?.get() === contentView
        val isAlreadyAdjusted = isCurrentContentAdjusted &&
            originalPadding == navigationInset &&
            contentView.paddingBottom == 0
        val fillsInputFrame = inputFrame.paddingBottom == 0 &&
            contentView.top == inputFrame.paddingTop &&
            contentView.bottom == inputFrame.height
        if (!fillsInputFrame ||
            contentView.paddingBottom != navigationInset && !isAlreadyAdjusted
        ) {
            if (isCurrentContentAdjusted) restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            return
        }

        originalImeContentBottomPaddings.putIfAbsent(contentView, contentView.paddingBottom)
        if (!isAlreadyAdjusted) {
            contentView.setPadding(
                contentView.paddingLeft,
                contentView.paddingTop,
                contentView.paddingRight,
                contentView.paddingBottom - navigationInset
            )
        }
        adjustedImeContentViews[inputFrame] = WeakReference(contentView)
        if (!expandFullscreenArea(fullscreenArea, navigationInset)) {
            restoreMiuiBottomFrame(inputFrame, fullscreenArea)
        }
    }

    private fun expandFullscreenArea(fullscreenArea: ViewGroup, navigationInset: Int): Boolean {
        val params = fullscreenArea.layoutParams ?: return false
        val previous = originalFullscreenAreaHeights[fullscreenArea]
        val currentHeight = params.height
        val baseHeight = when {
            previous == null -> currentHeight
            currentHeight == previous[2] && navigationInset == previous[1] -> return true
            currentHeight == previous[2] || currentHeight == previous[0] -> previous[0]
            else -> currentHeight
        }
        val targetHeight = if (baseHeight >= 0) {
            baseHeight + navigationInset
        } else {
            fullscreenArea.measuredHeight + navigationInset
        }
        originalFullscreenAreaHeights[fullscreenArea] = intArrayOf(
            baseHeight,
            navigationInset,
            targetHeight
        )
        if (currentHeight != targetHeight) {
            params.height = targetHeight
            fullscreenArea.layoutParams = params
        }
        return true
    }

    private fun restoreMiuiBottomFrame(inputFrame: ViewGroup, fullscreenArea: ViewGroup) {
        adjustedImeContentViews.remove(inputFrame)?.get()?.let { view ->
            val paddingBottom = originalImeContentBottomPaddings.remove(view)
            if (paddingBottom != null && view.paddingBottom == 0) {
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    paddingBottom
                )
            }
        }
        val height = originalFullscreenAreaHeights.remove(fullscreenArea) ?: return
        val params = fullscreenArea.layoutParams ?: return
        if (params.height == height[2]) {
            params.height = height[0]
            fullscreenArea.layoutParams = params
        }
    }

    private fun isBottomAreaActive(
        rootView: View,
        inputFrame: View,
        bottomArea: View,
        navigationInset: Int
    ): Boolean {
        if (!bottomArea.isShown || bottomArea.height < navigationInset) return false
        val rootLocation = IntArray(2)
        val inputLocation = IntArray(2)
        val bottomLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)
        inputFrame.getLocationOnScreen(inputLocation)
        bottomArea.getLocationOnScreen(bottomLocation)
        return bottomLocation[1] + bottomArea.height == rootLocation[1] + rootView.height &&
            inputLocation[1] + inputFrame.height == bottomLocation[1]
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

    private fun hookWeTypeTransparentColors() {
        WeTypeResourceHooks.hookAppearanceColors(WETYPE_COLOR_REPLACEMENTS)
    }

    private fun hookWeTypeSelfDrawKeyColors() {
        WeTypeResourceHooks.hookSelfDrawKeyColors()
    }

    private fun hookWeTypeKeyboardKeyCorner() {
        WeTypeResourceHooks.hookKeyboardKeyCorner()
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
                activity.window?.decorView?.let { decorView ->
                    HookEnvironment.postTracked(decorView) {
                        WeTypeHostLauncher.show(activity)
                    }
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
                activity.window?.decorView?.let { decorView ->
                    HookEnvironment.postTracked(decorView) {
                        hookWeTypeAboutLogoClick(activity)
                    }
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
            originalAboutLogoStates[logoView] = AboutLogoState(
                clickListener = originalClickListener,
                isClickable = logoView.isClickable
            )
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
            ?: modulePath
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

    /**
     * Hook InputProvider 的输入法白名单，修复当前输入法无法获取剪贴板的问题
     */
    private fun startPackageValidationHook(sourceDir: String, classLoader: ClassLoader) {
        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                val validationMethod = bridge.findMethod {
                    matcher {
                        declaredClass = MIUI_INPUT_PROVIDER
                        returnType = "boolean"
                        usingStrings(
                            "InputProvider",
                            "Invalid caller UID: ",
                            "No package name for UID: ",
                            "Package validation failed: ",
                            "Unexpected error during package validation"
                        )
                    }
                }.singleOrNull()?.getMethodInstance(classLoader) ?: run {
                    Log.e("Failed:Locate package validation method in $MIUI_INPUT_PROVIDER")
                    return@use
                }
                validationMethod.hookBefore { param ->
                    runCatching {
                        val provider = param.thisObject ?: return@runCatching
                        val context = provider.invokeMethodAs<Context>("getContext")
                            ?: return@runCatching
                        val currentInputMethodPackageName = Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.DEFAULT_INPUT_METHOD
                        )?.substringBefore('/') ?: return@runCatching
                        val packagesForUid = context.packageManager
                            .getPackagesForUid(Binder.getCallingUid()) ?: return@runCatching
                        if (packagesForUid.contains(currentInputMethodPackageName)) {
                            param.result = true
                        }
                    }.onFailure {
                        Log.i("Failed:Validate calling package for clipboard access")
                        Log.i(it)
                    }
                }
                Log.i("Success:Hook package validation")
            }
        }.onFailure {
            Log.e("Failed:Hook package validation")
            Log.i(it)
        }
    }

    private fun classHookToken(hookName: String, clazz: Class<*>): String =
        "${clazz.name}@${System.identityHashCode(clazz.classLoader)}:$hookName"

    private fun ActiveTarget.toBundle(): Bundle = Bundle().apply {
        putString(STATE_TARGET_KIND, kind)
        packageName?.let { putString(STATE_PACKAGE_NAME, it) }
        sourceDir?.let { putString(STATE_SOURCE_DIR, it) }
    }

    private fun activeTargetsBundle(): Bundle = Bundle().apply {
        val targets = synchronized(activeTargets) {
            ArrayList(activeTargets.values.map { target -> target.toBundle() })
        }
        putParcelableArrayList(STATE_TARGETS, targets)
    }

    private fun Bundle?.toActiveTargets(): List<ActiveTarget> {
        if (this == null) return emptyList()
        return getParcelableArrayList<Bundle>(STATE_TARGETS)
            ?.mapNotNull { targetBundle -> targetBundle.toActiveTarget() }
            .orEmpty()
    }

    private fun recordActiveTarget(target: ActiveTarget) {
        synchronized(activeTargets) {
            recordActiveTargetLocked(target)
        }
    }

    private fun recordActiveTargetLocked(target: ActiveTarget) {
        activeTargets["${target.kind}:${target.packageName.orEmpty()}"] = target
    }

    private fun Bundle.toActiveTarget(): ActiveTarget? {
        val kind = getString(STATE_TARGET_KIND) ?: return null
        if (kind !in setOf(TARGET_KIND_SYSTEM, TARGET_KIND_PACKAGE, TARGET_KIND_PHRASE)) return null
        return ActiveTarget(
            kind = kind,
            packageName = getString(STATE_PACKAGE_NAME),
            sourceDir = getString(STATE_SOURCE_DIR)
        )
    }

    private fun resolveHotReloadClassLoader(
        target: ActiveTarget,
        oldHookHandles: List<XposedInterface.HookHandle>
    ): ClassLoader {
        if (target.kind == TARGET_KIND_PACKAGE || target.kind == TARGET_KIND_PHRASE) {
            currentApplicationClassLoader()?.let { return it }
        }
        val preferredPrefixes = when (target.kind) {
            TARGET_KIND_SYSTEM -> listOf("com.android.server.")
            TARGET_KIND_PHRASE -> listOf(MIUI_INPUT_PROVIDER)
            TARGET_KIND_PACKAGE -> listOfNotNull(
                target.packageName,
                INPUT_METHOD_BOTTOM_MANAGER
            )
            else -> emptyList()
        }
        oldHookHandles.firstNotNullOfOrNull { handle ->
            val executable = runCatching { handle.executable }.getOrNull() ?: return@firstNotNullOfOrNull null
            executable.declaringClass.classLoader?.takeIf {
                preferredPrefixes.any(executable.declaringClass.name::startsWith)
            }
        }?.let { return it }

        Thread.currentThread().contextClassLoader
            ?.takeUnless { it === MainHook::class.java.classLoader }
            ?.let { return it }
        oldHookHandles.firstNotNullOfOrNull { handle ->
            runCatching { handle.executable.declaringClass.classLoader }.getOrNull()
        }?.let { return it }
        return ClassLoader.getSystemClassLoader()
    }

    private fun currentApplicationClassLoader(): ClassLoader? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null)
            ?.javaClass
            ?.classLoader
    }.getOrNull()

    private fun reinstallDynamicBottomManagerHooks(
        oldHookHandles: List<XposedInterface.HookHandle>,
        packageName: String
    ): List<Class<*>> {
        val isNonCustomize = packageName !in miuiImeList
        val bottomManagerClasses = oldHookHandles.mapNotNull { handle ->
            val declaringClass = runCatching { handle.executable.declaringClass }.getOrNull()
                ?: return@mapNotNull null
            if (!declaringClass.name.startsWith(INPUT_METHOD_BOTTOM_MANAGER)) return@mapNotNull null
            declaringClass
        }.distinctBy { clazz -> System.identityHashCode(clazz.classLoader) }
        bottomManagerClasses.forEach { bottomManagerClass ->
            val classLoader = bottomManagerClass.classLoader ?: return@forEach
            HookEnvironment.withHookScope("miui.dynamic-bottom") {
                installBottomManagerHooks(classLoader, isNonCustomize)
            }
        }
        return if (isNonCustomize) bottomManagerClasses else emptyList()
    }

    /**
     * API 102 hot reload does not replay lifecycle callbacks for already resumed activities.
     * Reattach only the external view state that the old generation explicitly removed.
     */
    private fun reconcileCurrentWeTypeUiAfterHotReload() {
        val reconciled = runOnMainThreadBlocking {
            currentProcessActivities().forEach { activity ->
                if (activity.javaClass.name == WETYPE_ABOUT_ACTIVITY) {
                    hookWeTypeAboutLogoClick(activity)
                }
                val intent = activity.intent ?: return@forEach
                if (!intent.getBooleanExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, false)) {
                    return@forEach
                }
                intent.removeExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS)
                WeTypeHostLauncher.show(activity)
            }
            currentProcessInputMethodServices().forEach(
                WeTypeWindowHooks::reconcileCurrentInputMethodService
            )
            WeTypeResourceHooks.reconcileCurrentKeyboardLogos(currentProcessWindowViews())
        }
        if (!reconciled) {
            Log.e("Failed:Reconcile current WeType UI after hot reload")
        }
    }

    private fun currentProcessWindowViews(): List<View> = runCatching {
        val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
        val windowManagerGlobal = windowManagerGlobalClass
            .getDeclaredMethod("getInstance")
            .apply { isAccessible = true }
            .invoke(null) ?: return@runCatching emptyList()
        val views = windowManagerGlobalClass
            .getDeclaredField("mViews")
            .apply { isAccessible = true }
            .get(windowManagerGlobal) as? Collection<*> ?: return@runCatching emptyList()
        views.filterIsInstance<View>()
    }.onFailure { error ->
        Log.e("Failed:Inspect current window views after hot reload")
        Log.i(error)
    }.getOrDefault(emptyList())

    private fun currentProcessInputMethodServices(): List<InputMethodService> = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return@runCatching emptyList()
        val services = activityThreadClass
            .getDeclaredField("mServices")
            .apply { isAccessible = true }
            .get(currentActivityThread) as? Map<*, *> ?: return@runCatching emptyList()
        services.values.filterIsInstance<InputMethodService>()
    }.onFailure { error ->
        Log.e("Failed:Inspect current input method services after hot reload")
        Log.i(error)
    }.getOrDefault(emptyList())

    private fun currentProcessActivities(): List<Activity> = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThread = activityThreadClass
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: return@runCatching emptyList()
        val activities = activityThreadClass
            .getDeclaredField("mActivities")
            .apply { isAccessible = true }
            .get(currentActivityThread) as? Map<*, *> ?: return@runCatching emptyList()
        activities.values.mapNotNull { record ->
            record ?: return@mapNotNull null
            runCatching {
                record.javaClass.getDeclaredField("activity")
                    .apply { isAccessible = true }
                    .get(record) as? Activity
            }.getOrNull()
        }.filterNot(Activity::isFinishing)
    }.onFailure { error ->
        Log.e("Failed:Inspect current activities after hot reload")
        Log.i(error)
    }.getOrDefault(emptyList())

    private fun cleanupExternalState(): Boolean = runOnMainThreadBlocking {
        imeInputFrameLayoutListeners.forEach { (view, listener) ->
            view.removeOnLayoutChangeListener(listener)
        }
        miuiBottomFrameViews.forEach { (inputFrame, frameViews) ->
            frameViews.first.get()?.let { fullscreenArea ->
                restoreMiuiBottomFrame(inputFrame, fullscreenArea)
            }
        }
        originalAboutLogoStates.forEach { (view, state) ->
            view.setOnClickListener(state.clickListener)
            view.isClickable = state.isClickable
            view.setTag(WETYPE_ABOUT_LOGO_TAG_KEY, null)
        }

        imeInputFrameLayoutListeners.clear()
        monitoredImeInputFrames.clear()
        originalImeContentBottomPaddings.clear()
        originalFullscreenAreaHeights.clear()
        adjustedImeContentViews.clear()
        miuiBottomFrameViews.clear()
        originalAboutLogoStates.clear()
        installedHookTokens.clear()
        navBarColor = null
        bottomViewSourceColor = null
        runCatching { moduleAssetManager?.close() }
        moduleAssetManager = null
        assetManagerAddAssetPathMethod = null
        viewListenerInfoField = null
        onClickListenerField = null
    }

    private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).onFailure {
                Log.e("Failed:Cleanup main hook state for hot reload")
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
            Log.e("Failed:Cleanup main hook state for hot reload")
            Log.i(it)
        }
        return finished && failure == null
    }
}
