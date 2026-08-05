package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val WETYPE_BLUR_APPLY_MAX_RETRY = 6
private const val WETYPE_BACKGROUND_SETTLE_RETRY = 3
private const val WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX = 2
private const val WETYPE_HARDWARE_VIEW_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.hardware."

private val WETYPE_HARDWARE_VIEW_ID_NAMES = arrayOf(
    "hardware_keyboard_candidate_container_view",
    "hardware_keyboard_pending_container_view",
    "hardware_keyboard_alternative_container_view",
    "hardware_keyboard_candidate_recyclerview",
    "hardware_keyboard_candidate_right_container"
)

internal object WeTypeWindowHooks {
    private data class WeTypeWindowState(
        var blurApplyToken: Int = 0,
        var blurEligible: Boolean = false,
        var windowVisible: Boolean = false,
        var backgroundCarrier: View? = null,
        var inputMethodService: Any? = null,
        var heightChangeListener: View.OnLayoutChangeListener? = null,
        var registeredViews: MutableList<View> = mutableListOf(),
        var computedVisibleImeHeightPx: Int? = null,
        var bottomLeftHardwareCornerRadius: Float? = null,
        var bottomRightHardwareCornerRadius: Float? = null,
        var hardwareViewIds: IntArray? = null,
        var originalWindowStateCaptured: Boolean = false,
        var originalWindowBackground: Drawable? = null,
        var originalWindowBlurRadius: Int? = null,
        val outlineSnapshots: MutableMap<View, Pair<Boolean, ViewOutlineProvider?>> = WeakHashMap()
    )

    private data class WeTypeViewSnapshot(
        val locationY: Int,
        val top: Int,
        val height: Int,
        val measuredHeight: Int,
        val visibility: Int,
        val isShown: Boolean
    ) {
        fun hasVisibleHeight(): Boolean = visibility == View.VISIBLE && isShown && height > 0
    }

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
                .filter { snapshot -> snapshot?.hasVisibleHeight() == true }
                .mapNotNull { snapshot ->
                    val resolved = snapshot ?: return@mapNotNull null
                    resolved.locationY.takeIf { it > 0 } ?: resolved.top.takeIf { it > 0 }
                }
                .minOrNull()
            return contentTop ?: 0
        }
    }

    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()

    fun prepareForHotReload(): Boolean {
        val states = synchronized(weTypeWindowStates) {
            weTypeWindowStates.values.toList()
        }
        val cleaned = runOnMainThreadBlocking {
            states.forEach { state ->
                state.blurApplyToken++
                state.windowVisible = false
                state.blurEligible = false
                state.registeredViews.forEach { view ->
                    state.heightChangeListener?.let { view.removeOnLayoutChangeListener(it) }
                }
                state.registeredViews.clear()
                state.heightChangeListener = null
                state.outlineSnapshots.forEach { (view, snapshot) ->
                    view.clipToOutline = snapshot.first
                    view.outlineProvider = snapshot.second
                    view.invalidateOutline()
                }
                state.outlineSnapshots.clear()
                restoreWindowState(state)
                removeBackgroundCarrier(state)
                state.inputMethodService = null
            }
        }
        if (cleaned) {
            synchronized(weTypeWindowStates) {
                weTypeWindowStates.clear()
            }
        }
        return cleaned
    }

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
                reconcileCurrentResourceViews(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWindowStage(param.thisObject, "onWindowShown")
                    reconcileCurrentResourceViews(param.thisObject)
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            inputMethodService.getMethod(
                "onComputeInsets",
                InputMethodService.Insets::class.java
            ).hookAfter { param ->
                onComputeInsets(param.thisObject, param.args.getOrNull(0) as? InputMethodService.Insets)
            }
            runCatching {
                inputMethodService.getMethod("onWindowHidden").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("hideWindow").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("onDestroy").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = true)
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

    fun reconcileCurrentInputMethodService(
        inputMethodService: InputMethodService,
        attempt: Int = 0
    ) {
        if (!inputMethodService.isInputViewShown) {
            if (attempt >= WETYPE_BACKGROUND_SETTLE_RETRY) return
            val decorView = runCatching {
                val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow")
                softInputWindow?.invokeMethodAs<Window>("getWindow")?.decorView
            }.getOrNull() ?: return
            HookEnvironment.postTracked(decorView, 100L) {
                reconcileCurrentInputMethodService(inputMethodService, attempt + 1)
            }
            return
        }
        val state = getWindowState(inputMethodService)
        state.windowVisible = true
        state.blurEligible = true
        state.computedVisibleImeHeightPx = null
        applyWindowCorner(inputMethodService)
        scheduleWindowBlur(inputMethodService)
        reconcileCurrentResourceViews(inputMethodService)
    }

    private fun reconcileCurrentResourceViews(inputMethodService: Any) {
        val decorView = runCatching {
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow")
            softInputWindow?.invokeMethodAs<Window>("getWindow")?.decorView
        }.getOrNull() ?: return
        WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        HookEnvironment.postTracked(decorView) {
            WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        }
        HookEnvironment.postTracked(decorView, 100L) {
            WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        }
    }

    private fun onComputeInsets(inputMethodService: Any, insets: InputMethodService.Insets?) {
        runCatching {
            val state = getWindowState(inputMethodService)
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
            val rootHeight = window.decorView.rootView?.height?.takeIf { it > 0 }
                ?: window.decorView.height.takeIf { it > 0 }
                ?: return@runCatching
            val visibleTopInsets = insets?.visibleTopInsets ?: return@runCatching
            val visibleImeHeight = (rootHeight - visibleTopInsets).coerceAtLeast(0)
            val previousVisibleImeHeight = state.computedVisibleImeHeightPx
            state.computedVisibleImeHeightPx = visibleImeHeight

            if (!state.windowVisible) return@runCatching
            if (visibleImeHeight <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX) {
                val wasExpanded = previousVisibleImeHeight
                    ?.let { it > WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX } != false
                if (wasExpanded) state.blurApplyToken++
                hideBackgroundCarrier(state)
                return@runCatching
            }
            if (visibleImeHeight == previousVisibleImeHeight) return@runCatching
            if (state.blurEligible) scheduleWindowBlur(inputMethodService)
        }.onFailure {
            Log.i("Failed: Track WeType visible IME height")
            Log.i(it)
        }
    }

    private fun onWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWindowState(inputMethodService)
            when (stage) {
                "onStartInputView" -> {
                    state.computedVisibleImeHeightPx = null
                    state.blurEligible = state.windowVisible
                }
                "onWindowShown" -> {
                    state.windowVisible = true
                    state.blurEligible = true
                    state.computedVisibleImeHeightPx = null
                }
                "updateFullscreenMode" -> {
                    if (!state.windowVisible) return@runCatching
                    state.blurEligible = true
                }
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
        if (!state.windowVisible) return
        val token = ++state.blurApplyToken
        applyWindowBlurWhenReady(inputMethodService, token, 0)
    }

    private fun applyWindowBlurWhenReady(inputMethodService: Any, token: Int, attempt: Int) {
        runCatching {
            val state = getWindowState(inputMethodService)
            if (state.blurApplyToken != token) return
            if (!state.windowVisible || !state.blurEligible) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView
            val snapshot = collectWindowSnapshot(inputMethodService) ?: return

            if (shouldHideBackground(inputMethodService, decorView, state)) {
                hideBackgroundCarrier(state)
                return
            }

            if (snapshot.isLayoutReady()) {
                applyBackgroundCarrier(inputMethodService, window, decorView, context, state, snapshot)
                scheduleBackgroundSettle(inputMethodService, token, WETYPE_BACKGROUND_SETTLE_RETRY)
                return
            }

            if (attempt >= WETYPE_BLUR_APPLY_MAX_RETRY) return
            HookEnvironment.postTracked(decorView) {
                applyWindowBlurWhenReady(inputMethodService, token, attempt + 1)
            }
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
            if (!state.windowVisible || !state.blurEligible) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView

            HookEnvironment.postTracked(decorView) {
                runCatching {
                    val latestState = getWindowState(inputMethodService)
                    if (latestState.blurApplyToken != token) return@runCatching
                    if (!latestState.windowVisible || !latestState.blurEligible) return@runCatching

                    val latestSoftInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                    val latestWindow = latestSoftInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                    val latestDecorView = latestWindow.decorView
                    val snapshot = collectWindowSnapshot(inputMethodService) ?: return@runCatching
                    if (shouldHideBackground(inputMethodService, latestDecorView, latestState)) {
                        hideBackgroundCarrier(latestState)
                        scheduleBackgroundSettle(inputMethodService, token, remaining - 1)
                        return@runCatching
                    }
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
            applyContinuousCornerOutline(decorView, cornerRadii, state)
        }
    }

    private fun getWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun onWindowInactive(inputMethodService: Any, removeCarrier: Boolean) {
        runCatching {
            val state = getWindowState(inputMethodService)
            state.windowVisible = false
            state.blurEligible = false
            state.computedVisibleImeHeightPx = null
            state.blurApplyToken++
            hideBackgroundCarrier(state)
            if (removeCarrier) {
                removeBackgroundCarrier(state)
                synchronized(weTypeWindowStates) {
                    weTypeWindowStates.remove(inputMethodService)
                }
            }
        }.onFailure {
            Log.i("Failed: Cleanup WeType window background")
            Log.i(it)
        }
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
            measuredHeight = measuredHeight,
            visibility = visibility,
            isShown = isShown
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
        if (!state.originalWindowStateCaptured) {
            state.originalWindowBackground = decorView.background
            state.originalWindowBlurRadius = runCatching {
                Window::class.java.getMethod("getBackgroundBlurRadius").invoke(window) as? Int
            }.getOrNull()
            state.originalWindowStateCaptured = true
        }
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        if (shouldHideBackground(inputMethodService, decorView, state)) {
            hideBackgroundCarrier(state)
            return
        }

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
            hideBackgroundCarrier(state)
            return
        }

        carrier.visibility = View.VISIBLE
        applyContinuousCornerOutline(carrier, cornerRadii, state)
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
                if (!state.windowVisible || !state.blurEligible) return@runCatching
                val ims = state.inputMethodService ?: return@runCatching
                val softInputWindow = ims.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                val decorView = window.decorView
                val snapshot = collectWindowSnapshot(ims) ?: return@runCatching
                if (shouldHideBackground(ims, decorView, state)) {
                    hideBackgroundCarrier(state)
                    return@runCatching
                }
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

    private fun shouldHideBackground(
        inputMethodService: Any,
        decorView: View,
        state: WeTypeWindowState
    ): Boolean {
        val collapsedByInsets = state.computedVisibleImeHeightPx
            ?.let { it <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX } == true
        if (collapsedByInsets) return true

        val inputView = runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }.getOrNull()
        if (inputView != null && containsWeTypeHardwareView(inputView, state)) return true
        return containsWeTypeHardwareView(decorView, state)
    }

    private fun containsWeTypeHardwareView(view: View, state: WeTypeWindowState): Boolean {
        val className = view.javaClass.name
        if (className.startsWith(WETYPE_HARDWARE_VIEW_CLASS_PREFIX)) return true

        val hardwareViewIds = state.hardwareViewIds ?: resolveHardwareViewIds(view.context)
            .also { state.hardwareViewIds = it }
        if (view.id != View.NO_ID && hardwareViewIds.contains(view.id)) return true

        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsWeTypeHardwareView(group.getChildAt(index), state)) return true
        }
        return false
    }

    private fun resolveHardwareViewIds(context: Context): IntArray =
        WETYPE_HARDWARE_VIEW_ID_NAMES.mapNotNull { name ->
            context.resources.getIdentifier(name, "id", context.packageName)
                .takeIf { it != 0 }
        }.toIntArray()

    private fun hideBackgroundCarrier(state: WeTypeWindowState) {
        state.registeredViews.forEach { view ->
            state.heightChangeListener?.let { view.removeOnLayoutChangeListener(it) }
        }
        state.registeredViews.clear()

        val carrier = state.backgroundCarrier ?: return
        carrier.visibility = View.GONE
        carrier.background = null
        (carrier.layoutParams as? FrameLayout.LayoutParams)?.let { layoutParams ->
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.height = 0
            layoutParams.topMargin = 0
            carrier.layoutParams = layoutParams
        }
    }

    private fun removeBackgroundCarrier(state: WeTypeWindowState) {
        val carrier = state.backgroundCarrier ?: return
        (carrier.parent as? ViewGroup)?.removeView(carrier)
        state.backgroundCarrier = null
        state.inputMethodService = null
    }

    private fun restoreWindowState(state: WeTypeWindowState) {
        if (!state.originalWindowStateCaptured) return
        val inputMethodService = state.inputMethodService ?: return
        runCatching {
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
            window.setBackgroundBlurRadius(state.originalWindowBlurRadius ?: 0)
            window.setBackgroundDrawable(state.originalWindowBackground)
        }
        state.originalWindowStateCaptured = false
        state.originalWindowBackground = null
        state.originalWindowBlurRadius = null
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

    private fun applyContinuousCornerOutline(
        view: View,
        cornerRadii: WeTypeCornerRadii,
        state: WeTypeWindowState
    ) {
        state.outlineSnapshots.putIfAbsent(view, view.clipToOutline to view.outlineProvider)
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

    private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).onFailure {
                Log.i("Failed: Cleanup WeType window state for hot reload")
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
            Log.i("Failed: Cleanup WeType window state for hot reload")
            Log.i(it)
        }
        return finished && failure == null
    }
}
