package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.util.WeakHashMap

private const val WETYPE_BLUR_APPLY_MAX_RETRY = 6
private const val WETYPE_BACKGROUND_SETTLE_RETRY = 3

internal object WeTypeWindowHooks {
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

    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()

    fun hookWindowBlur() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                onWindowStage(param.thisObject, "onStartInputView")
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWindowStage(param.thisObject, "onWindowShown")
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            Log.i("Success: Hook WeType window blur")
        }.onFailure {
            Log.i("Failed: Hook WeType window blur")
            Log.i(it)
        }
    }

    fun hookWindowCorner() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod("onCreate").hookAfter { param ->
                applyWindowCorner(param.thisObject)
            }
            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                applyWindowCorner(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    applyWindowCorner(param.thisObject)
                }
            }
            Log.i("Success: Hook WeType window corner")
        }.onFailure {
            Log.i("Failed: Hook WeType window corner")
            Log.i(it)
        }
    }

    private fun onWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWindowState(inputMethodService)
            when (stage) {
                "onStartInputView" -> state.blurEligible = false
                "onWindowShown", "updateFullscreenMode" -> state.blurEligible = true
            }

            if (!state.blurEligible) return@runCatching
            scheduleWindowBlur(inputMethodService)
        }.onFailure {
            Log.i("Failed: Handle WeType window stage")
            Log.i(it)
        }
    }

    private fun scheduleWindowBlur(inputMethodService: Any) {
        val state = getWindowState(inputMethodService)
        val token = ++state.blurApplyToken
        applyWindowBlurWhenReady(inputMethodService, token, 0)
    }

    private fun applyWindowBlurWhenReady(inputMethodService: Any, token: Int, attempt: Int) {
        runCatching {
            val state = getWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView
            val snapshot = collectWindowSnapshot(inputMethodService) ?: return

            if (snapshot.isLayoutReady()) {
                applyBackgroundCarrier(inputMethodService, window, decorView, context, state, snapshot)
                scheduleBackgroundSettle(inputMethodService, token, WETYPE_BACKGROUND_SETTLE_RETRY)
                return
            }

            if (attempt >= WETYPE_BLUR_APPLY_MAX_RETRY) return
            decorView.post { applyWindowBlurWhenReady(inputMethodService, token, attempt + 1) }
        }.onFailure {
            Log.i("Failed: Apply WeType window blur")
            Log.i(it)
        }
    }

    private fun scheduleBackgroundSettle(inputMethodService: Any, token: Int, remaining: Int) {
        if (remaining <= 0) return
        runCatching {
            val state = getWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView

            decorView.post {
                runCatching {
                    val latestState = getWindowState(inputMethodService)
                    if (latestState.blurApplyToken != token) return@runCatching

                    val latestSoftInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                    val latestWindow = latestSoftInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                    val latestDecorView = latestWindow.decorView
                    val snapshot = collectWindowSnapshot(inputMethodService) ?: return@runCatching
                    if (!snapshot.isLayoutReady()) {
                        scheduleBackgroundSettle(inputMethodService, token, remaining - 1)
                        return@runCatching
                    }
                    applyBackgroundCarrier(inputMethodService, latestWindow, latestDecorView, context, latestState, snapshot)
                    scheduleBackgroundSettle(inputMethodService, token, remaining - 1)
                }.onFailure {
                    Log.i("Failed: Settle WeType background carrier")
                    Log.i(it)
                }
            }
        }
    }

    private fun applyWindowCorner(inputMethodService: Any) {
        runCatching {
            val state = getWindowState(inputMethodService)
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView
            val cornerRadii = resolveCornerRadii(decorView, decorView.context, state)
            applyContinuousCornerOutline(decorView, cornerRadii)
        }
    }

    private fun getWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun resolveCornerRadii(targetView: View, context: Context, state: WeTypeWindowState): WeTypeCornerRadii {
        val topRadius = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCornerRadiusXposed(context).toFloat(),
            context.resources.displayMetrics
        )
        val insets = targetView.rootWindowInsets
        if (insets != null) {
            state.bottomLeftHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
            state.bottomRightHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat()
        }
        return WeTypeCornerRadii(
            topLeft = topRadius,
            topRight = topRadius,
            bottomRight = state.bottomRightHardwareCornerRadius ?: topRadius,
            bottomLeft = state.bottomLeftHardwareCornerRadius ?: topRadius
        )
    }

    private fun collectWindowSnapshot(inputMethodService: Any): WeTypeWindowSnapshot? {
        val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return null
        val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return null
        return WeTypeWindowSnapshot(
            decorView = window.decorView.toViewSnapshot(),
            candidatesFrame = readViewField(inputMethodService, "mCandidatesFrame")?.toViewSnapshot(),
            inputFrame = readViewField(inputMethodService, "mInputFrame")?.toViewSnapshot(),
            inputView = runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }.getOrNull()?.toViewSnapshot()
        )
    }

    private fun readViewField(inputMethodService: Any, fieldName: String): View? =
        runCatching { inputMethodService.getObjectAs<View>(fieldName) }.getOrNull()

    private fun View.toViewSnapshot(): WeTypeViewSnapshot {
        val location = IntArray(2)
        runCatching { getLocationInWindow(location) }
        return WeTypeViewSnapshot(
            locationY = location[1],
            top = top,
            height = height,
            measuredHeight = measuredHeight
        )
    }

    private fun applyBackgroundCarrier(
        inputMethodService: Any,
        window: Window,
        decorView: View,
        context: Context,
        state: WeTypeWindowState,
        snapshot: WeTypeWindowSnapshot
    ) {
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        val decorGroup = decorView as? ViewGroup ?: return
        val decorHeight = snapshot.decorView?.height ?: decorGroup.height
        val backgroundTop = snapshot.backgroundTop().coerceIn(0, decorHeight)
        val backgroundHeight = (decorHeight - backgroundTop).coerceAtLeast(0)
        val carrier = ensureBackgroundCarrier(context, decorGroup, state, inputMethodService)
        val layoutParams = (carrier.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, backgroundHeight)
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = backgroundHeight
        layoutParams.topMargin = backgroundTop
        carrier.layoutParams = layoutParams

        val cornerRadii = resolveCornerRadii(decorView, context, state)
        if (backgroundHeight < cornerRadii.maxRadius()) {
            carrier.visibility = View.GONE
            carrier.background = null
            return
        }

        carrier.visibility = View.VISIBLE
        applyContinuousCornerOutline(carrier, cornerRadii)
        carrier.background = createBackgroundDrawable(carrier, context, cornerRadii)
        setupHeightChangeListeners(inputMethodService, context, state)
    }

    private fun setupHeightChangeListeners(inputMethodService: Any, context: Context, state: WeTypeWindowState) {
        state.registeredViews.forEach { view ->
            state.heightChangeListener?.let { view.removeOnLayoutChangeListener(it) }
        }
        state.registeredViews.clear()

        val listener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldHeight = oldBottom - oldTop
            val newHeight = bottom - top
            if (oldHeight == newHeight) return@OnLayoutChangeListener

            runCatching {
                val ims = state.inputMethodService ?: return@runCatching
                val softInputWindow = ims.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                val decorView = window.decorView
                val snapshot = collectWindowSnapshot(ims) ?: return@runCatching
                if (snapshot.isLayoutReady()) {
                    applyBackgroundCarrier(ims, window, decorView, context, state, snapshot)
                }
            }.onFailure {
                Log.i("Failed: Reapply background on height change")
                Log.i(it)
            }
        }
        state.heightChangeListener = listener

        readViewField(inputMethodService, "mCandidatesFrame")?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
        readViewField(inputMethodService, "mInputFrame")?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
        runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }.getOrNull()?.also {
            it.addOnLayoutChangeListener(listener)
            state.registeredViews.add(it)
        }
    }

    private fun ensureBackgroundCarrier(
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
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        )
        state.backgroundCarrier = carrier
        state.inputMethodService = inputMethodService
        return carrier
    }

    private fun createBackgroundDrawable(targetView: View, context: Context, cornerRadii: WeTypeCornerRadii): Drawable {
        val color = WeTypeSettings.getCurrentBackgroundColorXposed(context)
        val blurRadius = WeTypeSettings.getBlurRadiusXposed(context)
        val edgeHighlightEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(context)
        val edgeHighlightIntensity = WeTypeSettings.getEdgeHighlightIntensityXposed(context)
        val tintDrawable = createTintDrawable(color, cornerRadii)
        val blurDrawable = createInternalBackgroundBlurDrawable(targetView, blurRadius, cornerRadii)
        val layers = buildList {
            blurDrawable?.also(::add)
            add(tintDrawable)
            if (edgeHighlightEnabled) {
                add(
                    WeTypeBloomStrokeDrawable(
                        context = context,
                        cornerRadii = cornerRadii,
                        surfaceColor = color,
                        intensityScale = edgeHighlightIntensity / 100f
                    )
                )
            }
        }
        return if (layers.size == 1) layers.first() else android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    private fun createInternalBackgroundBlurDrawable(targetView: View, blurRadius: Int, cornerRadii: WeTypeCornerRadii): Drawable? {
        val viewRootImpl = runCatching { targetView.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull() ?: return null
        val blurDrawable = runCatching { viewRootImpl.invokeMethodAs<Drawable>("createBackgroundBlurDrawable") }.getOrNull() ?: return null
        runCatching { blurDrawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(blurDrawable, blurRadius) }
        runCatching { blurDrawable.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(blurDrawable, Color.TRANSPARENT) }
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
            blurDrawable.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(blurDrawable, cornerRadii.maxRadius())
        }
        return blurDrawable
    }

    private fun createTintDrawable(color: Int, cornerRadii: WeTypeCornerRadii): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadii = cornerRadii.toArray()
        setColor(color)
    }

    private fun applyContinuousCornerOutline(view: View, cornerRadii: WeTypeCornerRadii) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                val width = target.width
                val height = target.height
                if (width <= 0 || height <= 0) return
                val path = createWeTypeContinuousRoundedPath(width.toFloat(), height.toFloat(), cornerRadii)
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
}
