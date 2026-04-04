package com.xposed.miuiime

import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

private const val TAG = "miuiime"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val WETYPE_FONT_ASSET = "fonts/WE-Regular.ttf"
private const val MODULE_WETYPE_FONT_ASSET = "WE-Regular.ttf"
private const val WETYPE_BLUR_APPLY_MAX_RETRY = 6
private const val WETYPE_BACKGROUND_SETTLE_RETRY = 3
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
)

private data class WeTypeWindowState(
    var blurApplyToken: Int = 0,
    var blurEligible: Boolean = false,
    var backgroundCarrier: View? = null,
    var inputMethodService: Any? = null,
    var heightChangeListener: View.OnLayoutChangeListener? = null,
    var registeredViews: MutableList<View> = mutableListOf(),
    var bottomLeftHardwareCornerRadius: Float? = null,
    var bottomRightHardwareCornerRadius: Float? = null
)

private data class WeTypeViewSnapshot(
    val locationX: Int,
    val locationY: Int,
    val top: Int,
    val height: Int,
    val measuredHeight: Int
)

private data class WeTypeWindowSnapshot(
    val decorView: WeTypeViewSnapshot?,
    val candidatesFrame: WeTypeViewSnapshot?,
    val inputFrame: WeTypeViewSnapshot?,
    val inputView: WeTypeViewSnapshot?
) {
    fun isLayoutReady(): Boolean {
        val decorReady = (decorView?.height ?: 0) > 0
        val contentReady = listOf(candidatesFrame, inputFrame, inputView)
            .any { snapshot -> snapshot != null && (snapshot.height > 0 || snapshot.measuredHeight > 0) }
        return decorReady && contentReady
    }

    fun backgroundTop(): Int {
        val contentTop = listOf(candidatesFrame, inputFrame, inputView)
            .filter { snapshot -> snapshot != null && (snapshot.height > 0 || snapshot.measuredHeight > 0) }
            .mapNotNull { snapshot ->
                val resolved = snapshot ?: return@mapNotNull null
                resolved.locationY.takeIf { it > 0 } ?: resolved.top.takeIf { it > 0 }
            }
            .minOrNull()
        return contentTop ?: 0
    }
}

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch"
    )
    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()
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
            hookWeTypeFont()
            hookWeTypeTransparentColors()
            hookWeTypeXmlDrawables()
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
        runCatching {
            Typeface::class.java.getDeclaredMethod(
                "createFromAsset",
                AssetManager::class.java,
                String::class.java
            ).hookBefore { param ->
                if (param.args[1] != WETYPE_FONT_ASSET) return@hookBefore
                param.result = Typeface.createFromAsset(getModuleAssetManager(), MODULE_WETYPE_FONT_ASSET)
            }
            Log.i("Success: Hook WeType font replacement")
        }.onFailure {
            Log.i("Failed: Hook WeType font replacement")
            Log.i(it)
        }
    }

    private fun hookWeTypeXmlDrawables() {
        runCatching {
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceWeTypeDrawable(resources, resId, null)?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param.args[0] as? Int ?: return@hookAfter
                val theme = param.args[1] as? Resources.Theme
                replaceWeTypeDrawable(resources, resId, theme)?.also { param.result = it }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceWeTypeDrawable(resources, resId, null)?.also { param.result = it }
                }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    val theme = param.args[2] as? Resources.Theme
                    replaceWeTypeDrawable(resources, resId, theme)?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    replaceWeTypeDrawable(typedArray.resources, resId, null)?.also { param.result = it }
                }
            Log.i("Success: Hook WeType xml drawables")
        }.onFailure {
            Log.i("Failed: Hook WeType xml drawables")
            Log.i(it)
        }
    }

    private fun hookWeTypeTransparentColors() {
        runCatching {
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceWeTypeColor(resources, colorResId, param.result as Int)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceWeTypeColor(resources, colorResId, param.result as Int)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceWeTypeColorStateList(
                        resources,
                        colorResId,
                        param.result as ColorStateList
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceWeTypeColorStateList(
                    resources,
                    colorResId,
                    param.result as ColorStateList
                )
            }
            TypedArray::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val index = param.args[0] as? Int ?: return@hookAfter
                val colorResId = typedArray.getResourceId(index, 0)
                if (colorResId == 0) return@hookAfter
                param.result = replaceWeTypeColor(typedArray.resources, colorResId, param.result as Int)
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    if (colorResId == 0) return@hookAfter
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    param.result = replaceWeTypeColorStateList(
                        typedArray.resources,
                        colorResId,
                        colorStateList
                    )
                }
            Log.i("Success: Hook WeType transparent colors")
        }.onFailure {
            Log.i("Failed: Hook WeType transparent colors")
            Log.i(it)
        }
    }

    private fun hookWeTypeWindowCorner() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod("onCreate").hookAfter { param ->
                applyWeTypeWindowCorner(param.thisObject)
            }
            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                applyWeTypeWindowCorner(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    applyWeTypeWindowCorner(param.thisObject)
                }
            }
            Log.i("Success: Hook WeType window corner")
        }.onFailure {
            Log.i("Failed: Hook WeType window corner")
            Log.i(it)
        }
    }

    private fun hookWeTypeWindowBlur() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                onWeTypeWindowStage(param.thisObject, "onStartInputView")
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWeTypeWindowStage(param.thisObject, "onWindowShown")
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWeTypeWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            Log.i("Success: Hook WeType window blur")
        }.onFailure {
            Log.i("Failed: Hook WeType window blur")
            Log.i(it)
        }
    }

    private fun onWeTypeWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            when (stage) {
                "onStartInputView" -> state.blurEligible = false
                "onWindowShown", "updateFullscreenMode" -> state.blurEligible = true
            }

            if (!state.blurEligible) return@runCatching
            scheduleWeTypeWindowBlur(inputMethodService)
        }.onFailure {
            Log.i("Failed: Handle WeType window stage")
            Log.i(it)
        }
    }

    private fun scheduleWeTypeWindowBlur(inputMethodService: Any) {
        val state = getWeTypeWindowState(inputMethodService)
        val token = ++state.blurApplyToken
        applyWeTypeWindowBlurWhenReady(inputMethodService, token, 0)
    }

    private fun applyWeTypeWindowBlurWhenReady(
        inputMethodService: Any,
        token: Int,
        attempt: Int
    ) {
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView ?: return
            val snapshot = collectWeTypeWindowSnapshot(inputMethodService) ?: return

            if (snapshot.isLayoutReady()) {
                // 避免在 SoftInputWindow 首次测量前改动窗口背景，先等视图具备稳定尺寸。
                applyWeTypeBackgroundCarrier(inputMethodService, window, decorView, context, state, snapshot)
                scheduleWeTypeBackgroundSettle(inputMethodService, token, WETYPE_BACKGROUND_SETTLE_RETRY)
                return
            }

            if (attempt >= WETYPE_BLUR_APPLY_MAX_RETRY) return

            decorView.post {
                applyWeTypeWindowBlurWhenReady(inputMethodService, token, attempt + 1)
            }
        }.onFailure {
            Log.i("Failed: Apply WeType window blur")
            Log.i(it)
        }
    }

    private fun scheduleWeTypeBackgroundSettle(
        inputMethodService: Any,
        token: Int,
        remaining: Int
    ) {
        if (remaining <= 0) return
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView ?: return

            decorView.post {
                runCatching {
                    val latestState = getWeTypeWindowState(inputMethodService)
                    if (latestState.blurApplyToken != token) return@runCatching

                    val latestSoftInputWindow =
                        inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                    val latestWindow =
                        latestSoftInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                    val latestDecorView = latestWindow.decorView ?: return@runCatching
                    val snapshot =
                        collectWeTypeWindowSnapshot(inputMethodService) ?: return@runCatching
                    if (!snapshot.isLayoutReady()) {
                        scheduleWeTypeBackgroundSettle(inputMethodService, token, remaining - 1)
                        return@runCatching
                    }
                    applyWeTypeBackgroundCarrier(
                        inputMethodService,
                        latestWindow,
                        latestDecorView,
                        context,
                        latestState,
                        snapshot
                    )
                    scheduleWeTypeBackgroundSettle(inputMethodService, token, remaining - 1)
                }.onFailure {
                    Log.i("Failed: Settle WeType background carrier")
                    Log.i(it)
                }
            }
        }
    }

    private fun applyWeTypeWindowCorner(inputMethodService: Any) {
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow")
                ?: return
            val decorView = window.decorView ?: return
            val cornerRadii = resolveWeTypeCornerRadii(decorView, decorView.context, state)
            applyContinuousCornerOutline(decorView, cornerRadii)
        }
    }

    private fun getWeTypeWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun resolveWeTypeCornerRadii(
        targetView: View,
        context: Context,
        state: WeTypeWindowState
    ): WeTypeCornerRadii {
        val topRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCornerRadiusXposed(context).toFloat(),
            context.resources.displayMetrics
        )
        val insets = targetView.rootWindowInsets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && insets != null) {
            state.bottomLeftHardwareCornerRadius =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
            state.bottomRightHardwareCornerRadius =
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat()
        }
        return WeTypeCornerRadii(
            topLeft = topRadius,
            topRight = topRadius,
            bottomRight = state.bottomRightHardwareCornerRadius ?: topRadius,
            bottomLeft = state.bottomLeftHardwareCornerRadius ?: topRadius
        )
    }

    private fun collectWeTypeWindowSnapshot(inputMethodService: Any): WeTypeWindowSnapshot? {
        val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return null
        val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return null
        return WeTypeWindowSnapshot(
            decorView = window.decorView?.toWeTypeViewSnapshot(),
            candidatesFrame = readWeTypeViewField(inputMethodService, "mCandidatesFrame")
                ?.toWeTypeViewSnapshot(),
            inputFrame = readWeTypeViewField(inputMethodService, "mInputFrame")
                ?.toWeTypeViewSnapshot(),
            inputView = runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }
                .getOrNull()
                ?.toWeTypeViewSnapshot()
        )
    }

    private fun readWeTypeViewField(inputMethodService: Any, fieldName: String): View? =
        runCatching { inputMethodService.getObjectAs<View>(fieldName) }.getOrNull()

    private fun View.toWeTypeViewSnapshot(): WeTypeViewSnapshot {
        val location = IntArray(2)
        runCatching { getLocationInWindow(location) }
        return WeTypeViewSnapshot(
            locationX = location[0],
            locationY = location[1],
            top = top,
            height = height,
            measuredHeight = measuredHeight
        )
    }

    private fun applyWeTypeBackgroundCarrier(
        inputMethodService: Any,
        window: Window,
        decorView: View,
        context: Context,
        state: WeTypeWindowState,
        snapshot: WeTypeWindowSnapshot
    ) {
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val decorGroup = decorView as? ViewGroup ?: return
        val decorHeight = snapshot.decorView?.height ?: decorGroup.height
        val backgroundTop = snapshot.backgroundTop().coerceIn(0, decorHeight)
        val backgroundHeight = (decorHeight - backgroundTop).coerceAtLeast(0)
        val carrier = ensureWeTypeBackgroundCarrier(context, decorGroup, state, inputMethodService)
        val layoutParams = (carrier.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                backgroundHeight
            )
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = backgroundHeight
        layoutParams.topMargin = backgroundTop
        carrier.layoutParams = layoutParams

        val cornerRadii = resolveWeTypeCornerRadii(decorView, context, state)

        // 当目标高度小于圆角半径时，不绘制背景
        if (backgroundHeight < cornerRadii.maxRadius()) {
            carrier.visibility = View.GONE
            carrier.background = null
            return
        }

        carrier.visibility = View.VISIBLE
        applyContinuousCornerOutline(carrier, cornerRadii)
        carrier.background = createWeTypeBackgroundDrawable(carrier, context, cornerRadii)

        // 每次应用背景时重新注册高度监听器到最新的输入法视图上
        // 横竖屏切换后视图会重建，需要重新注册
        setupHeightChangeListeners(inputMethodService, context, state)
    }

    private fun setupHeightChangeListeners(
        inputMethodService: Any,
        context: Context,
        state: WeTypeWindowState
    ) {
        // 清理旧 View 上的监听器
        state.registeredViews.forEach { view ->
            state.heightChangeListener?.let { view.removeOnLayoutChangeListener(it) }
        }
        state.registeredViews.clear()

        // 创建监听器：监听输入法内容视图的高度变化，重新绘制背景
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldHeight = oldBottom - oldTop
            val newHeight = bottom - top
            if (oldHeight == newHeight) return@OnLayoutChangeListener

            runCatching {
                val ims = state.inputMethodService ?: return@runCatching
                val softInputWindow = ims.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                val decorView = window.decorView ?: return@runCatching
                val snapshot = collectWeTypeWindowSnapshot(ims) ?: return@runCatching
                if (snapshot.isLayoutReady()) {
                    applyWeTypeBackgroundCarrier(ims, window, decorView, context, state, snapshot)
                }
            }.onFailure {
                Log.i("Failed: Reapply background on height change")
                Log.i(it)
            }
        }
        state.heightChangeListener = listener

        // 为输入法内容视图添加监听器（候选词区、输入区、输入视图），并保存引用
        readWeTypeViewField(inputMethodService, "mCandidatesFrame")?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
        readWeTypeViewField(inputMethodService, "mInputFrame")?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
        runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }.getOrNull()?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
    }

    private fun ensureWeTypeBackgroundCarrier(
        context: Context,
        decorGroup: ViewGroup,
        state: WeTypeWindowState,
        inputMethodService: Any
    ): View {
        val existing = state.backgroundCarrier?.takeIf { it.parent === decorGroup }
        if (existing != null) return existing

        val carrier = View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decorGroup.addView(
            carrier,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            )
        )
        state.backgroundCarrier = carrier
        state.inputMethodService = inputMethodService

        return carrier
    }

    private fun createWeTypeBackgroundDrawable(
        targetView: View,
        context: Context,
        cornerRadii: WeTypeCornerRadii
    ): Drawable {
        val color = WeTypeSettings.getCurrentBackgroundColorXposed(context)
        val blurRadius = WeTypeSettings.getBlurRadiusXposed(context)
        val edgeHighlightEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(context)
        val tintDrawable = createWeTypeTintDrawable(color, cornerRadii)
        val blurDrawable = createInternalBackgroundBlurDrawable(targetView, blurRadius, cornerRadii)
        val layers = buildList {
            blurDrawable?.also(::add)
            add(tintDrawable)
            if (edgeHighlightEnabled) {
                add(WeTypeBloomStrokeDrawable(context, cornerRadii, color))
            }
        }
        return if (layers.size == 1) layers.first() else LayerDrawable(layers.toTypedArray())
    }

    private fun createInternalBackgroundBlurDrawable(
        targetView: View,
        blurRadius: Int,
        cornerRadii: WeTypeCornerRadii
    ): Drawable? {
        val viewRootImpl = runCatching { targetView.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull()
            ?: return null
        val blurDrawable = runCatching {
            viewRootImpl.invokeMethodAs<Drawable>("createBackgroundBlurDrawable")
        }.getOrNull() ?: return null
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setBlurRadius",
                Int::class.javaPrimitiveType
            ).invoke(blurDrawable, blurRadius)
        }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setColor",
                Int::class.javaPrimitiveType
            ).invoke(blurDrawable, Color.TRANSPARENT)
        }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).invoke(
                blurDrawable,
                cornerRadii.topLeft,
                cornerRadii.topRight,
                cornerRadii.bottomRight,
                cornerRadii.bottomLeft
            )
        }.recoverCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType
            ).invoke(blurDrawable, cornerRadii.maxRadius())
        }
        return blurDrawable
    }

    private fun createWeTypeTintDrawable(
        color: Int,
        cornerRadii: WeTypeCornerRadii
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadii = cornerRadii.toArray()
        setColor(color)
    }

    private fun createContinuousRoundPath(
        width: Float,
        height: Float,
        cornerRadii: WeTypeCornerRadii
    ): android.graphics.Path = createWeTypeContinuousRoundedPath(width, height, cornerRadii)

    private fun applyContinuousCornerOutline(view: View, cornerRadii: WeTypeCornerRadii) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                val width = target.width
                val height = target.height
                if (width <= 0 || height <= 0) return
                val path = createContinuousRoundPath(width.toFloat(), height.toFloat(), cornerRadii)
                runCatching {
                    Outline::class.java.getMethod("setPath", android.graphics.Path::class.java)
                        .invoke(outline, path)
                }.onFailure {
                    outline.setRoundRect(0, 0, width, height, cornerRadii.maxRadius())
                }
            }
        }
        view.invalidateOutline()
    }

    private fun replaceWeTypeColor(resources: Resources, colorResId: Int, color: Int): Int {
        val colorName = runCatching { resources.getResourceEntryName(colorResId) }.getOrNull() ?: return color
        return WETYPE_COLOR_REPLACEMENTS[colorName] ?: color
    }

    private fun replaceWeTypeDrawable(
        resources: Resources,
        drawableResId: Int,
        theme: Resources.Theme?
    ): Drawable? {
        val drawableName = runCatching {
            resources.getResourceEntryName(drawableResId)
        }.getOrNull() ?: return null
        val replacementResId = WETYPE_DRAWABLE_REPLACEMENTS[drawableName] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceWeTypeColorStateList(
        resources: Resources,
        colorResId: Int,
        colorStateList: ColorStateList
    ): ColorStateList {
        val replacedColor = replaceWeTypeColor(resources, colorResId, colorStateList.defaultColor)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
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
