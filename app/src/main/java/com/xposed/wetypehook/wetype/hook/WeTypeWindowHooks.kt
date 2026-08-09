package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toDrawable
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.findMethodInHierarchy
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val WETYPE_BLUR_APPLY_MAX_RETRY = 6
private const val WETYPE_BACKGROUND_SETTLE_RETRY = 3
private const val WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX = 2
private const val WETYPE_HARDWARE_VIEW_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.hardware."
private const val WETYPE_CANDIDATE_VIEW_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val WETYPE_SETTINGS_KEYBOARD_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.keyboard.S10SettingsKeyboard"
private const val WETYPE_SETTINGS_RECYCLER_VIEW_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView"
private const val WETYPE_SETTING_VIEW_PACKAGE_PREFIX =
    "com.tencent.wetype.plugin.hld.view.settingkeyboard."

private val WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES = setOf(
    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S34ClipboardBombKeyboard"
)

private val WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES = setOf(
    "${WETYPE_SETTING_VIEW_PACKAGE_PREFIX}S10SettingKeyboardTypeView",
    "${WETYPE_SETTING_VIEW_PACKAGE_PREFIX}S10SettingCustomToolbarView"
)

private val WETYPE_HARDWARE_VIEW_ID_NAMES = arrayOf(
    "hardware_keyboard_candidate_container_view",
    "hardware_keyboard_pending_container_view",
    "hardware_keyboard_alternative_container_view",
    "hardware_keyboard_candidate_recyclerview",
    "hardware_keyboard_candidate_right_container"
)

internal object WeTypeWindowHooks {
    private data class WeTypeGlobalLayoutRegistration(
        val observer: WeakReference<ViewTreeObserver>,
        val listener: ViewTreeObserver.OnGlobalLayoutListener
    )

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
    private val overlayStateLock = Any()
    private val overlayRootsByContainer =
        WeakHashMap<ViewGroup, MutableMap<View, Boolean>>()
    private val overlayContainerByRoot = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val coveredUnderlayOriginalVisibilities =
        WeakHashMap<ViewGroup, MutableMap<View, Int>>()
    private val internalSettingUnderlayOriginalVisibilities =
        WeakHashMap<ViewGroup, MutableMap<View, Int>>()
    private val internalSettingContainers = WeakHashMap<ViewGroup, Boolean>()
    private val internalSettingContainerByOverlay = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val externalOverlayAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val internalSettingAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val externalOverlayLayoutRegistrations =
        WeakHashMap<ViewGroup, WeTypeGlobalLayoutRegistration>()
    private val internalSettingLayoutRegistrations =
        WeakHashMap<ViewGroup, WeTypeGlobalLayoutRegistration>()
    private val overlaySyncPosted = WeakHashMap<ViewGroup, Boolean>()
    private val internalSettingSyncPosted = WeakHashMap<ViewGroup, Boolean>()
    private var weTypeKeyboardBaseClasses: Set<Class<*>> = emptySet()
    private var weTypeCandidateViewClass: Class<*>? = null
    @Volatile
    private var overlayWindowVisible = false

    fun prepareForHotReload(): Boolean {
        val states = synchronized(weTypeWindowStates) {
            weTypeWindowStates.values.toList()
        }
        val cleaned = runOnMainThreadBlocking {
            overlayWindowVisible = false
            restoreAllCoveredUnderlays(clearTrackedRoots = true)
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
            synchronized(overlayStateLock) {
                overlayRootsByContainer.clear()
                overlayContainerByRoot.clear()
                coveredUnderlayOriginalVisibilities.clear()
                internalSettingUnderlayOriginalVisibilities.clear()
                internalSettingContainers.clear()
                internalSettingContainerByOverlay.clear()
                externalOverlayAttachListeners.clear()
                internalSettingAttachListeners.clear()
                externalOverlayLayoutRegistrations.clear()
                internalSettingLayoutRegistrations.clear()
                overlaySyncPosted.clear()
                internalSettingSyncPosted.clear()
                weTypeKeyboardBaseClasses = emptySet()
                weTypeCandidateViewClass = null
            }
        }
        return cleaned
    }

    fun hookTransparentOverlayUnderlay() {
        val resolvedExternalOverlays = WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES.mapNotNull { className ->
            runCatching {
                val overlayClass = loadClassOrNull(className)
                    ?: error("Failed to resolve $className")
                val showMethod = overlayClass.declaredMethods.single { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1] == Bundle::class.java
                }.apply { isAccessible = true }
                overlayClass to showMethod
            }.onFailure { error ->
                Log.i("Failed: Hook transparent overlay for $className")
                Log.i(error)
            }.getOrNull()
        }

        weTypeKeyboardBaseClasses = resolvedExternalOverlays.mapNotNull { (overlayClass, _) ->
            runCatching {
                findKeyboardBaseClass(overlayClass)
            }.onFailure { error ->
                Log.i("Failed: Resolve WeType keyboard base for ${overlayClass.name}")
                Log.i(error)
            }.getOrNull()
        }.toSet()
        weTypeCandidateViewClass = loadClassOrNull(WETYPE_CANDIDATE_VIEW_CLASS_NAME).also { candidateClass ->
            if (candidateClass == null) {
                Log.i("Failed: Resolve WeType candidate view for transparent overlays")
            }
        }

        resolvedExternalOverlays.forEach { (overlayClass, showMethod) ->
            runCatching {
                showMethod.hookAfter { param ->
                    reconcileShownOverlayRoot(param.thisObject as? View)
                }
                Log.i("Success: Hook transparent overlay for ${overlayClass.name}")
            }.onFailure { error ->
                Log.i("Failed: Hook transparent overlay for ${overlayClass.name}")
                Log.i(error)
            }
        }

        hookInternalSettingOverlays()

        val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
        runCatching {
            inputMethodService?.getMethod("onWindowShown")?.hookAfter { param ->
                overlayWindowVisible = true
                reconcileCurrentOverlayUnderlays(param.thisObject)
            }
        }.onFailure(Log::i)
        runCatching {
            inputMethodService?.getMethod("onWindowHidden")?.hookAfter {
                overlayWindowVisible = false
                restoreAllCoveredUnderlays(clearTrackedRoots = true)
            }
        }.onFailure(Log::i)
        runCatching {
            inputMethodService?.getMethod("onDestroy")?.hookAfter {
                overlayWindowVisible = false
                restoreAllCoveredUnderlays(clearTrackedRoots = true)
            }
        }.onFailure(Log::i)

        if (resolvedExternalOverlays.isEmpty()) {
            Log.i("Failed: Hook WeType transparent overlay underlay visibility")
        } else {
            Log.i("Success: Hook WeType transparent overlay underlay visibility")
        }
    }

    fun reconcileCurrentOverlayUnderlays(rootViews: List<View>) {
        if (rootViews.isEmpty()) return
        overlayWindowVisible = true
        rootViews.forEach { root ->
            fun visit(view: View) {
                when (view.javaClass.name) {
                    in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES ->
                        reconcileShownOverlayRoot(view)
                    in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES ->
                        reconcileInternalSettingOverlay(view)
                }
                if (view is ViewGroup) {
                    repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
                }
            }
            visit(root)
        }
        scheduleKnownOverlayContainers()
        scheduleKnownInternalSettingContainers()
    }

    private fun reconcileCurrentOverlayUnderlays(inputMethodService: Any) {
        val decorView = resolveInputMethodDecorView(inputMethodService) ?: return
        reconcileCurrentOverlayUnderlays(listOf(decorView))
        HookEnvironment.postTracked(decorView, 48L) {
            reconcileCurrentOverlayUnderlays(listOf(decorView))
        }
        HookEnvironment.postTracked(decorView, 144L) {
            reconcileCurrentOverlayUnderlays(listOf(decorView))
        }
    }

    private fun reconcileShownOverlayRoot(view: View?) {
        if (!overlayWindowVisible) return
        val overlayRoot = view?.takeIf {
            it.javaClass.name in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES
        } ?: return
        ensureExternalOverlayAttachListener(overlayRoot)
        fun registerWhenAttached(retriesRemaining: Int) {
            val container = findOverlayKeyboardContainer(overlayRoot)
            if (container != null) {
                registerOverlayRoot(container, overlayRoot)
                scheduleOverlayUnderlaySync(container)
            } else if (retriesRemaining > 0) {
                HookEnvironment.postTracked(overlayRoot, 32L) {
                    registerWhenAttached(retriesRemaining - 1)
                }
            }
        }
        registerWhenAttached(retriesRemaining = 5)
    }

    private fun registerOverlayRoot(container: ViewGroup, overlayRoot: View) {
        synchronized(overlayStateLock) {
            overlayRootsByContainer
                .getOrPut(container) { WeakHashMap() }[overlayRoot] = true
            overlayContainerByRoot[overlayRoot] = WeakReference(container)
        }
        ensureExternalOverlayLayoutRegistration(container)
    }

    private fun reconcileDetachedOverlayRoot(view: View?) {
        val overlayRoot = view?.takeIf {
            it.javaClass.name in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES
        } ?: return
        val container = synchronized(overlayStateLock) {
            overlayContainerByRoot[overlayRoot]?.get()
        } ?: return
        scheduleOverlayUnderlaySync(container)
    }

    private fun ensureExternalOverlayAttachListener(overlayRoot: View) {
        val listener = synchronized(overlayStateLock) {
            if (externalOverlayAttachListeners.containsKey(overlayRoot)) {
                null
            } else {
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        reconcileShownOverlayRoot(view)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        reconcileDetachedOverlayRoot(view)
                    }
                }.also { externalOverlayAttachListeners[overlayRoot] = it }
            }
        } ?: return
        overlayRoot.addOnAttachStateChangeListener(listener)
    }

    private fun scheduleOverlayUnderlaySync(container: ViewGroup) {
        ensureExternalOverlayLayoutRegistration(container)
        val shouldPost = synchronized(overlayStateLock) {
            if (overlaySyncPosted[container] == true) {
                false
            } else {
                overlaySyncPosted[container] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = HookEnvironment.postTracked(container) {
            synchronized(overlayStateLock) { overlaySyncPosted.remove(container) }
            syncOverlayUnderlay(container)
            HookEnvironment.postTracked(container, 48L) { syncOverlayUnderlay(container) }
            HookEnvironment.postTracked(container, 144L) { syncOverlayUnderlay(container) }
        }
        if (!posted) {
            synchronized(overlayStateLock) { overlaySyncPosted.remove(container) }
            syncOverlayUnderlay(container)
        }
    }

    private fun syncOverlayUnderlay(container: ViewGroup) {
        if (!overlayWindowVisible) {
            restoreCoveredUnderlays(container)
            removeExternalOverlayLayoutRegistration(container)
            return
        }
        val visibleOverlayRoots = synchronized(overlayStateLock) {
            overlayRootsByContainer[container]
                ?.keys
                ?.filter { root ->
                    root.isAttachedToWindow &&
                        root.isShown &&
                        isDescendantOf(root, container)
                }
                .orEmpty()
                .toSet()
        }
        if (visibleOverlayRoots.isNotEmpty()) {
            hideCoveredUnderlays(container, visibleOverlayRoots)
        } else {
            restoreCoveredUnderlays(container)
            removeExternalOverlayLayoutRegistration(container)
        }
    }

    private fun ensureExternalOverlayLayoutRegistration(container: ViewGroup) {
        // Host reset/hide methods are intentionally not resolved: framework visibility and
        // reparenting changes are observed only while a transparent overlay is active.
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        var previousRegistration: WeTypeGlobalLayoutRegistration? = null
        val registration = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations[container]?.let { current ->
                if (current.observer.get() === observer) return@synchronized null
                previousRegistration = current
            }
            val containerReference = WeakReference(container)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                containerReference.get()?.let(::syncOverlayUnderlay)
            }
            WeTypeGlobalLayoutRegistration(WeakReference(observer), listener).also {
                externalOverlayLayoutRegistrations[container] = it
            }
        } ?: return
        previousRegistration?.let { previous ->
            previous.observer.get()?.takeIf { observer -> observer.isAlive }
                ?.removeOnGlobalLayoutListener(previous.listener)
        }
        observer.addOnGlobalLayoutListener(registration.listener)
    }

    private fun removeExternalOverlayLayoutRegistration(container: ViewGroup) {
        val registration = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations.remove(container)
        } ?: return
        registration.observer.get()?.takeIf { observer -> observer.isAlive }
            ?.removeOnGlobalLayoutListener(registration.listener)
    }

    private fun removeAllExternalOverlayLayoutRegistrations() {
        val containers = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations.keys.toList()
        }
        containers.forEach(::removeExternalOverlayLayoutRegistration)
    }

    private fun hideCoveredUnderlays(
        container: ViewGroup,
        visibleOverlayRoots: Set<View>
    ) {
        val coveredUnderlays = findCoveredUnderlays(container)
        val rootsToRestore = mutableListOf<Pair<View, Int>>()
        val rootsToHide = mutableListOf<View>()
        synchronized(overlayStateLock) {
            val originals = coveredUnderlayOriginalVisibilities
                .getOrPut(container) { WeakHashMap() }
            visibleOverlayRoots.forEach { overlayRoot ->
                originals.remove(overlayRoot)?.let { visibility ->
                    rootsToRestore += overlayRoot to visibility
                }
            }
            coveredUnderlays.forEach { underlay ->
                if (
                    underlay !in visibleOverlayRoots &&
                    underlay.visibility == View.VISIBLE &&
                    underlay.isShown
                ) {
                    if (!originals.containsKey(underlay)) {
                        originals[underlay] = underlay.visibility
                    }
                    rootsToHide += underlay
                }
            }
        }
        rootsToRestore.forEach { (view, visibility) ->
            if (view.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(view, visibility)
            }
        }
        rootsToHide.forEach { underlay ->
            setOverlayManagedVisibility(underlay, View.INVISIBLE)
        }
    }

    private fun restoreCoveredUnderlays(container: ViewGroup) {
        val originals = synchronized(overlayStateLock) {
            coveredUnderlayOriginalVisibilities.remove(container)
                ?.entries
                ?.toList()
                .orEmpty()
        }
        originals.forEach { (view, visibility) ->
            if (view.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(view, visibility)
            }
        }
    }

    private fun restoreAllCoveredUnderlays(clearTrackedRoots: Boolean) {
        restoreAllInternalSettingUnderlays(clearTrackedOverlays = clearTrackedRoots)
        removeAllExternalOverlayLayoutRegistrations()
        val containers = synchronized(overlayStateLock) {
            coveredUnderlayOriginalVisibilities.keys.toList()
        }
        containers.forEach(::restoreCoveredUnderlays)
        if (clearTrackedRoots) {
            removeAllExternalOverlayAttachListeners()
            synchronized(overlayStateLock) {
                overlayRootsByContainer.clear()
                overlayContainerByRoot.clear()
                overlaySyncPosted.clear()
            }
        }
    }

    private fun scheduleKnownOverlayContainers() {
        val containers = synchronized(overlayStateLock) {
            overlayRootsByContainer.keys.toList()
        }
        containers.forEach(::scheduleOverlayUnderlaySync)
    }

    private fun scheduleKnownInternalSettingContainers() {
        val containers = synchronized(overlayStateLock) {
            internalSettingContainers.keys.toList()
        }
        containers.forEach(::scheduleInternalSettingUnderlaySync)
    }

    private fun removeAllExternalOverlayAttachListeners() {
        val listeners = synchronized(overlayStateLock) {
            externalOverlayAttachListeners.entries.map { (view, listener) -> view to listener }
                .also { externalOverlayAttachListeners.clear() }
        }
        listeners.forEach { (view, listener) ->
            view.removeOnAttachStateChangeListener(listener)
        }
    }

    private fun setOverlayManagedVisibility(view: View, visibility: Int) {
        view.visibility = visibility
    }

    private fun findCoveredUnderlays(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            if (isWeTypeKeyboardRoot(view) || isWeTypeCandidateView(view)) {
                result += view
                return
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
            }
        }
        visit(root)
        return result
    }

    private fun hookInternalSettingOverlays() {
        val hookedRenderMethods = mutableSetOf<java.lang.reflect.Method>()
        WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES.forEach { className ->
            runCatching {
                val overlayClass = loadClassOrNull(className)
                    ?: error("Failed to resolve $className")
                val renderMethod = overlayClass.findMethodInHierarchy {
                    returnType == Void.TYPE &&
                        parameterTypes.contentEquals(
                            arrayOf(
                                View::class.java,
                                Bundle::class.java,
                                Boolean::class.javaPrimitiveType
                            )
                        )
                }
                if (hookedRenderMethods.add(renderMethod)) {
                    renderMethod.hookBefore { param ->
                        registerInternalSettingOverlay(param.thisObject as? View)
                    }
                    renderMethod.hookAfter { param ->
                        (param.thisObject as? View)?.let { overlay ->
                            reconcileInternalSettingOverlay(overlay)
                            scheduleInternalSettingOverlayReconcile(overlay)
                        }
                    }
                }
                Log.i("Success: Hook internal setting overlay for $className")
            }.onFailure { error ->
                Log.i("Failed: Hook internal setting overlay for $className")
                Log.i(error)
            }
        }
    }

    private fun registerInternalSettingOverlay(target: View?) {
        if (!overlayWindowVisible) return
        val overlay = target?.takeIf { view ->
            view.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES &&
                hasAncestorClass(view, WETYPE_SETTINGS_KEYBOARD_CLASS_NAME)
        } ?: return
        val container = overlay.parent as? ViewGroup ?: return
        synchronized(overlayStateLock) {
            internalSettingContainerByOverlay[overlay] = WeakReference(container)
            internalSettingContainers[container] = true
        }
        ensureInternalSettingAttachListener(overlay)
        ensureInternalSettingLayoutRegistration(container)
        hideInternalSettingUnderlay(container)
    }

    private fun scheduleInternalSettingOverlayReconcile(overlay: View) {
        val reconcile = {
            reconcileInternalSettingOverlay(overlay)
            Unit
        }
        if (!HookEnvironment.postTracked(overlay, 48L, reconcile)) reconcile()
        HookEnvironment.postTracked(overlay, 144L, reconcile)
    }

    private fun hideInternalSettingUnderlay(container: ViewGroup) {
        val underlays = buildList {
            repeat(container.childCount) { index ->
                val child = container.getChildAt(index)
                if (
                    child is LinearLayout &&
                    child.visibility == View.VISIBLE &&
                    containsDescendantClass(child, WETYPE_SETTINGS_RECYCLER_VIEW_CLASS_NAME)
                ) {
                    add(child)
                }
            }
        }
        val rootsToHide = synchronized(overlayStateLock) {
            val originals = internalSettingUnderlayOriginalVisibilities
                .getOrPut(container) { WeakHashMap() }
            underlays.filter { underlay ->
                if (!originals.containsKey(underlay)) {
                    originals[underlay] = underlay.visibility
                }
                true
            }
        }
        rootsToHide.forEach { underlay ->
            setOverlayManagedVisibility(underlay, View.INVISIBLE)
        }
    }

    private fun reconcileInternalSettingOverlay(view: View?) {
        if (!overlayWindowVisible) return
        val overlay = view?.takeIf { candidate ->
            candidate.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES
        } ?: return
        val container = resolveInternalSettingContainer(overlay) ?: return
        synchronized(overlayStateLock) {
            internalSettingContainers[container] = true
        }
        ensureInternalSettingAttachListener(overlay)
        ensureInternalSettingLayoutRegistration(container)
        syncInternalSettingUnderlay(container)
    }

    private fun scheduleInternalSettingUnderlaySync(container: ViewGroup) {
        ensureInternalSettingLayoutRegistration(container)
        val shouldPost = synchronized(overlayStateLock) {
            if (internalSettingSyncPosted[container] == true) {
                false
            } else {
                internalSettingSyncPosted[container] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = HookEnvironment.postTracked(container) {
            synchronized(overlayStateLock) { internalSettingSyncPosted.remove(container) }
            syncInternalSettingUnderlay(container)
            HookEnvironment.postTracked(container, 48L) { syncInternalSettingUnderlay(container) }
            HookEnvironment.postTracked(container, 144L) { syncInternalSettingUnderlay(container) }
        }
        if (!posted) {
            synchronized(overlayStateLock) { internalSettingSyncPosted.remove(container) }
            syncInternalSettingUnderlay(container)
        }
    }

    private fun syncInternalSettingUnderlay(container: ViewGroup) {
        if (!overlayWindowVisible) {
            restoreInternalSettingUnderlay(container)
            removeInternalSettingLayoutRegistration(container)
            return
        }
        val hasVisibleOverlay = buildList {
            repeat(container.childCount) { index -> add(container.getChildAt(index)) }
        }.any { child ->
            child.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES &&
                child.isAttachedToWindow &&
                child.isShown
        }
        if (hasVisibleOverlay) {
            hideInternalSettingUnderlay(container)
        } else {
            restoreInternalSettingUnderlay(container)
            removeInternalSettingLayoutRegistration(container)
        }
    }

    private fun ensureInternalSettingAttachListener(overlay: View) {
        val listener = synchronized(overlayStateLock) {
            if (internalSettingAttachListeners.containsKey(overlay)) {
                null
            } else {
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        reconcileInternalSettingOverlay(view)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        reconcileInternalSettingOverlay(view)
                    }
                }.also { internalSettingAttachListeners[overlay] = it }
            }
        } ?: return
        overlay.addOnAttachStateChangeListener(listener)
    }

    private fun ensureInternalSettingLayoutRegistration(container: ViewGroup) {
        // Keep this instance-scoped so host method obfuscation cannot affect close detection.
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        var previousRegistration: WeTypeGlobalLayoutRegistration? = null
        val registration = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations[container]?.let { current ->
                if (current.observer.get() === observer) return@synchronized null
                previousRegistration = current
            }
            val containerReference = WeakReference(container)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                containerReference.get()?.let(::syncInternalSettingUnderlay)
            }
            WeTypeGlobalLayoutRegistration(WeakReference(observer), listener).also {
                internalSettingLayoutRegistrations[container] = it
            }
        } ?: return
        previousRegistration?.let { previous ->
            previous.observer.get()?.takeIf { observer -> observer.isAlive }
                ?.removeOnGlobalLayoutListener(previous.listener)
        }
        observer.addOnGlobalLayoutListener(registration.listener)
    }

    private fun removeInternalSettingLayoutRegistration(container: ViewGroup) {
        val registration = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations.remove(container)
        } ?: return
        registration.observer.get()?.takeIf { observer -> observer.isAlive }
            ?.removeOnGlobalLayoutListener(registration.listener)
    }

    private fun removeAllInternalSettingLayoutRegistrations() {
        val containers = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations.keys.toList()
        }
        containers.forEach(::removeInternalSettingLayoutRegistration)
    }

    private fun restoreInternalSettingUnderlay(container: ViewGroup) {
        val originals = synchronized(overlayStateLock) {
            internalSettingUnderlayOriginalVisibilities.remove(container)
                ?.entries
                ?.toList()
                .orEmpty()
        }
        originals.forEach { (underlay, visibility) ->
            if (underlay.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(underlay, visibility)
            }
        }
    }

    private fun restoreAllInternalSettingUnderlays(clearTrackedOverlays: Boolean) {
        removeAllInternalSettingLayoutRegistrations()
        val containers = synchronized(overlayStateLock) {
            internalSettingUnderlayOriginalVisibilities.keys.toList()
        }
        containers.forEach(::restoreInternalSettingUnderlay)
        if (clearTrackedOverlays) {
            val listeners = synchronized(overlayStateLock) {
                internalSettingAttachListeners.entries.map { (view, listener) -> view to listener }
                    .also { internalSettingAttachListeners.clear() }
            }
            listeners.forEach { (view, listener) ->
                view.removeOnAttachStateChangeListener(listener)
            }
            synchronized(overlayStateLock) {
                internalSettingContainers.clear()
                internalSettingContainerByOverlay.clear()
                internalSettingSyncPosted.clear()
            }
        }
    }

    private fun resolveInternalSettingContainer(overlay: View): ViewGroup? {
        val attachedContainer = (overlay.parent as? ViewGroup)?.takeIf {
            hasAncestorClass(overlay, WETYPE_SETTINGS_KEYBOARD_CLASS_NAME)
        }
        if (attachedContainer != null) {
            synchronized(overlayStateLock) {
                internalSettingContainerByOverlay[overlay] = WeakReference(attachedContainer)
            }
            return attachedContainer
        }
        return synchronized(overlayStateLock) {
            internalSettingContainerByOverlay[overlay]?.get()
        }
    }

    private fun containsDescendantClass(root: View, className: String): Boolean {
        if (root.javaClass.name == className) return true
        if (root !is ViewGroup) return false
        repeat(root.childCount) { index ->
            if (containsDescendantClass(root.getChildAt(index), className)) return true
        }
        return false
    }

    private fun hasAncestorClass(view: View, className: String): Boolean {
        var current = view.parent as? View
        while (current != null) {
            if (current.javaClass.name == className) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isWeTypeKeyboardRoot(view: View): Boolean =
        weTypeKeyboardBaseClasses.any { keyboardBase -> keyboardBase.isInstance(view) }

    private fun isWeTypeCandidateView(view: View): Boolean =
        weTypeCandidateViewClass?.isInstance(view) == true

    private fun findKeyboardBaseClass(overlayClass: Class<*>): Class<*> {
        var candidate = overlayClass
        while (true) {
            val superclass = candidate.superclass ?: break
            val superclassHasKeyboardType = superclass.methods.any { method ->
                method.name == "getKeyboardType" && method.parameterCount == 0
            }
            if (!View::class.java.isAssignableFrom(superclass) || !superclassHasKeyboardType) break
            candidate = superclass
        }
        check(
            View::class.java.isAssignableFrom(candidate) &&
                candidate != View::class.java &&
                candidate != ViewGroup::class.java &&
                candidate.methods.any { method ->
                    method.name == "getKeyboardType" && method.parameterCount == 0
                }
        ) { "Invalid WeType keyboard base: ${candidate.name}" }
        return candidate
    }

    private fun findOverlayKeyboardContainer(view: View): ViewGroup? {
        val overlayHost = view.parent as? ViewGroup ?: return null
        return overlayHost.parent as? ViewGroup
    }

    private fun isDescendantOf(view: View, ancestor: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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
        val decorView = resolveInputMethodDecorView(inputMethodService) ?: return
        WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        HookEnvironment.postTracked(decorView) {
            WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        }
        HookEnvironment.postTracked(decorView, 100L) {
            WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
        }
    }

    private fun resolveInputMethodDecorView(inputMethodService: Any): View? = runCatching {
        val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow")
        softInputWindow?.invokeMethodAs<Window>("getWindow")?.decorView
    }.getOrNull()

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
