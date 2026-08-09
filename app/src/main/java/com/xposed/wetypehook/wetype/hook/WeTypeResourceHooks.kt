package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.findMethodInHierarchy
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReturnConstant
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorMode
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object WeTypeResourceHooks {
    private const val WETYPE_RESOURCE_PACKAGE = "com.tencent.wetype"
    private const val WETYPE_DRAWABLE_R_CLASS = "com.tencent.wetype.plugin.hld.r"
    private const val WETYPE_ATTR_R_CLASS = "com.tencent.wetype.plugin.hld.o"
    private const val WETYPE_ID_R_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val KEY_DATA_CLASS =
        "com.tencent.wetype.plugin.hld.keyboard.selfdraw.bean.KeyData"
    // KeyData.bgCorner renders ~10dp smaller than its numeric value; offset the stored dp radius.
    private const val KEY_CORNER_BGCORNER_OFFSET = 10

    // Faint hairline alpha applied to the host key border color (mirrors the legacy flat look).
    private const val KEY_BORDER_ALPHA = 0x20

    // Background opacity fraction applied to the synthesized logo drawable. Dark logo variants use a
    // much lighter background so the white backing circle stays subtle against dark keyboards.
    private const val LOGO_LIGHT_BG_ALPHA_FRACTION = 0.9f
    private const val LOGO_DARK_BG_ALPHA_FRACTION = 0.2f
    private const val LOGO_RESOURCE_TAG_KEY = 0x4D49554C
    private const val CANDIDATE_SELF_VIEW_PACKAGE =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview."
    private const val CANDIDATE_SCROLL_VIEW_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.scrollview.SelfDrawScrollView"
    private const val CANDIDATE_VIEW_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
    private const val CANDIDATE_PINYIN_CONTAINER_ID_NAME = "strike_container_rl"
    private val typedArrayAttributeCache = Collections.synchronizedMap(
        WeakHashMap<TypedArray, IntArray>()
    )
    private val resourcePackageCache = Collections.synchronizedMap(
        WeakHashMap<Resources, MutableMap<Int, Boolean>>()
    )
    private data class CandidatePinyinListeners(
        val layout: View.OnLayoutChangeListener,
        val attach: View.OnAttachStateChangeListener
    )

    private data class ViewPadding(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int
    )

    private val candidatePinyinMarginListeners = Collections.synchronizedMap(
        WeakHashMap<View, CandidatePinyinListeners>()
    )
    private val candidatePinyinOriginalPaddings = Collections.synchronizedMap(
        WeakHashMap<View, ViewPadding>()
    )
    private data class LogoHostState(
        val resourceId: Int? = null,
        val drawable: Drawable? = null
    )

    private val replacedLogoStates = Collections.synchronizedMap(
        WeakHashMap<ImageView, LogoHostState>()
    )
    private val restoringLogoDrawable = ThreadLocal.withInitial { false }
    private val candidateItemRootBaseLeftPaddingPx = Collections.synchronizedMap(
        WeakHashMap<Any, Int>()
    )
    private val candidateItemRootAppliedLeftPaddingPx = Collections.synchronizedMap(
        WeakHashMap<Any, Int>()
    )
    private val candidateMarginInsetWriteDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private val appearanceColorParamsLock = Any()
    private val hsvScratchThreadLocal = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(3)
    }

    @Volatile
    private var cachedAppearanceColors: Map<String, Int>? = null

    @Volatile
    private var cachedAppearanceColorParams: Map<String, AppearanceColorParams> = emptyMap()

    private data class AppearanceColorParams(
        val mode: WeTypeAppearanceColorMode,
        val userColor: Int,
        val targetHue: Float = 0f,
        val deltaSaturation: Float = 0f,
        val deltaValue: Float = 0f
    )

    fun hookFont(
        fontAsset: String,
        moduleFontAsset: String,
        getModuleAssetManager: () -> AssetManager
    ) {
        runCatching {
            Typeface::class.java.getDeclaredMethod(
                "createFromAsset",
                AssetManager::class.java,
                String::class.java
            ).hookBefore { param ->
                if (param.args[1] != fontAsset) return@hookBefore
                param.result = Typeface.createFromAsset(getModuleAssetManager(), moduleFontAsset)
            }
            Log.i("Success: Hook WeType font replacement")
        }.onFailure {
            Log.i("Failed: Hook WeType font replacement")
            Log.i(it)
        }
    }


    private fun <T> resolveResourceIds(
        nameToValueMap: Map<String, T>,
        vararg classNames: String
    ): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        val unresolvedNames = nameToValueMap.keys.toMutableSet()
        for (className in classNames) {
            if (unresolvedNames.isEmpty()) break
            val clazz = loadClassOrNull(className) ?: continue
            val iterator = unresolvedNames.iterator()
            while (iterator.hasNext()) {
                val name = iterator.next()
                val field = runCatching { clazz.getField(name) }.getOrNull()
                if (field != null) {
                    val id = runCatching { field.getInt(null) }.getOrNull()
                    if (id != null) {
                        result[id] = nameToValueMap[name]!!
                        iterator.remove()
                    }
                }
            }
        }
        if (unresolvedNames.isNotEmpty()) {
            Log.i("Failed to resolve resource IDs for: $unresolvedNames")
        }
        return result
    }

    fun hookXmlDrawables(
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ) {
        runCatching {
            val resolvedReplacements = resolveResourceIds(drawableReplacements, "com.tencent.wetype.plugin.hld.r", "com.tencent.wetype.plugin.hld.s")
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param.args[0] as? Int ?: return@hookAfter
                if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                val theme = param.args[1] as? Resources.Theme
                replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
                    ?.also { param.result = it }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
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
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    val theme = param.args[2] as? Resources.Theme
                    replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    if (!shouldReplaceDrawable(typedArray.resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(typedArray.resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Log.i("Success: Hook WeType xml drawables")
        }.onFailure {
            Log.i("Failed: Hook WeType xml drawables")
            Log.i(it)
        }
    }

    fun hookAppearanceColors(staticColorReplacements: Map<String, Int>) {
        runCatching {
            val resolvedStatic = resolveResourceIds(staticColorReplacements, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")
            val dynamicMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.colorResourceNames.forEach { name -> dynamicMap[name] = group }
            }
            val resolvedDynamic = resolveResourceIds(dynamicMap, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")
            
            val themeAttrMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.themeAttributeNames.forEach { name -> themeAttrMap[name] = group }
            }
            val resolvedThemeAttrs = resolveResourceIds(themeAttrMap, "com.tencent.wetype.plugin.hld.o", "com.tencent.wetype.plugin.hld.s")

            hookThemeStyledAttributes(resolvedThemeAttrs)
            hookColorValueAccess(resolvedStatic, resolvedDynamic, resolvedThemeAttrs)
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    if (!shouldReplaceColor(resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                    param.result = replaceColor(colorResId, param.result as Int, resolvedStatic, resolvedDynamic)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                val resources = param.thisObject as? Resources ?: return@hookAfter
                if (!shouldReplaceColor(resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                param.result = replaceColor(colorResId, param.result as Int, resolvedStatic, resolvedDynamic)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    if (!shouldReplaceColor(resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                    param.result = replaceColorStateList(
                        colorResId,
                        param.result as ColorStateList,
                        resolvedStatic,
                        resolvedDynamic
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                val resources = param.thisObject as? Resources ?: return@hookAfter
                if (!shouldReplaceColor(resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                param.result = replaceColorStateList(
                    colorResId,
                    param.result as ColorStateList,
                    resolvedStatic,
                    resolvedDynamic
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
                if (colorResId != 0) {
                    if (!shouldReplaceColor(typedArray.resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                    param.result = replaceColor(
                        colorResId,
                        param.result as Int,
                        resolvedStatic,
                        resolvedDynamic
                    )
                    return@hookAfter
                }
                replaceThemeAttributeColor(
                    typedArray,
                    index,
                    param.result as Int,
                    resolvedThemeAttrs
                )?.also { param.result = it }
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    if (colorResId != 0) {
                        if (!shouldReplaceColor(typedArray.resources, colorResId, resolvedStatic, resolvedDynamic)) return@hookAfter
                        param.result = replaceColorStateList(
                            colorResId,
                            colorStateList,
                            resolvedStatic,
                            resolvedDynamic
                        )
                        return@hookAfter
                    }
                    replaceThemeAttributeColor(
                        typedArray,
                        index,
                        colorStateList.defaultColor,
                        resolvedThemeAttrs
                    )
                        ?.also { param.result = ColorStateList.valueOf(it) }
                }
            Log.i("Success: Hook WeType appearance colors")
        }.onFailure {
            Log.i("Failed: Hook WeType appearance colors")
            Log.i(it)
        }
    }

    private fun hookColorValueAccess(
        resolvedStatic: Map<Int, Int>,
        resolvedDynamic: Map<Int, WeTypeAppearanceColorGroup>,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ) {
        Resources::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java,
            Boolean::class.javaPrimitiveType
        ).hookAfter { param ->
            val resources = param.thisObject as? Resources ?: return@hookAfter
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            if (!shouldReplaceTypedValue(resources, outValue, resolvedStatic, resolvedDynamic)) return@hookAfter
            replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
        }
        runCatching {
            Resources::class.java.getMethod(
                "getValueForDensity",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val outValue = param.args[2] as? TypedValue ?: return@hookAfter
                if (!shouldReplaceTypedValue(resources, outValue, resolvedStatic, resolvedDynamic)) return@hookAfter
                replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
            }
        }
        TypedArray::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java
        ).hookAfter { param ->
            if (param.result != true) return@hookAfter
            val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            if (!shouldReplaceTypedValue(typedArray.resources, outValue, resolvedStatic, resolvedDynamic)) return@hookAfter
            replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
        }
        TypedArray::class.java.getMethod("peekValue", Int::class.javaPrimitiveType)
            .hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val outValue = param.result as? TypedValue ?: return@hookAfter
                if (!shouldReplaceTypedValue(typedArray.resources, outValue, resolvedStatic, resolvedDynamic)) return@hookAfter
                replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
            }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val outValue = param.args[1] as? TypedValue ?: return@hookAfter
                val attrResId = param.args[0] as Int
                if (
                    !isTargetWeTypeTypedValue(resources, outValue, resolvedStatic, resolvedDynamic) &&
                    !isTargetWeTypeThemeAttr(resources, attrResId, resolvedThemeAttrs)
                ) return@hookAfter
                if (replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)) {
                    return@hookAfter
                }
                replaceThemeAttributeTypedValue(attrResId, outValue, resolvedThemeAttrs)
            }
        }
    }

    /**
     * Recolors the self-draw keyboard keys by intercepting the host's theme-attribute color
     * resolution (`Resources.Theme.resolveAttribute`), the single funnel every self-draw key
     * fill/shadow/border color is resolved through — both the layout-JSON `@attr/?attr` button
     * colors (the bulk of keys) and the few programmatically-styled keys.
     *
     * Scoping is done purely by the stable R.attr field names of the key background attributes
     * (resolved via the positionally-stable R.attr class, never by version-specific method/entry
     * names). The "white" attributes cover normal/QWERTY keys and the "grey" attributes cover
     * special/function keys (backspace, "123", symbols, caps lock, ...). Accent keys (the green
     * "搜索"/enter button, `ime_color_btn_green_*`) and every non-key surface are intentionally left
     * untouched. The light/dark variants share the same attribute id and are disambiguated at
     * resolve time from the theme's night-mode configuration, matching the background color's
     * light/dark handling. Matching on the attribute id (rather than the resolved resource id) is
     * what makes this robust: the skin frequently resolves these attributes to inline colors whose
     * `TypedValue.resourceId` is 0.
     */
    fun hookSelfDrawKeyColors() {
        runCatching {
            val keyAttrRoles = resolveResourceIds(
                mapOf(
                    // Skin-layout button backgrounds (the bulk of keys: QWERTY/normal -> "white",
                    // special keys such as backspace/"123"/symbols -> "grey"). These are what the
                    // keyboard JSON references via @attr/?attr and are resolved through
                    // Resources.Theme.resolveAttribute.
                    "ime_color_btn_white_bg" to KeyAttrRole.Fill,
                    "ime_color_btn_grey_bg" to KeyAttrRole.Fill,
                    "ime_color_btn_white_shadow" to KeyAttrRole.Shadow,
                    "ime_color_btn_grey_shadow" to KeyAttrRole.Shadow,
                    // Dark-mode key shadow: the layout color arrays use this shared black-50%
                    // attribute as their dark slot for both white and grey keys. It is referenced
                    // exclusively by key shadowColor entries (verified: no code/other-resource use),
                    // so neutralizing the attribute resolution is safe and key-scoped.
                    "UN_BW_0_Alpha_0_5" to KeyAttrRole.Shadow,
                    "ime_color_btn_white_border" to KeyAttrRole.Border,
                    "ime_color_btn_grey_border" to KeyAttrRole.Border,
                    // Programmatically-styled keys (e.g. caps lock / enter / separator) that read the
                    // key colors directly instead of from the layout JSON.
                    "ime_key_white_color" to KeyAttrRole.Fill,
                    "ime_key_grey_color" to KeyAttrRole.Fill,
                    "ime_key_white_shadow_color" to KeyAttrRole.Shadow,
                    "ime_key_grey_shadow_color" to KeyAttrRole.Shadow,
                    "ime_key_white_border_color" to KeyAttrRole.Border,
                    "ime_key_grey_border_color" to KeyAttrRole.Border
                ),
                WETYPE_ATTR_R_CLASS,
                WETYPE_ID_R_CLASS
            )
            check(keyAttrRoles.values.any { it == KeyAttrRole.Fill }) {
                "Failed to resolve key background theme attributes"
            }

            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val attrResId = param.args[0] as? Int ?: return@hookAfter
                val role = keyAttrRoles[attrResId] ?: return@hookAfter
                val outValue = param.args[1] as? TypedValue ?: return@hookAfter
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val replacement = when (role) {
                    KeyAttrRole.Fill -> resolveKeyColor(theme)
                    KeyAttrRole.Shadow -> Color.TRANSPARENT
                    // Keep a faint hairline using the host's own border hue at low opacity.
                    KeyAttrRole.Border -> withForcedAlpha(outValue.data, KEY_BORDER_ALPHA)
                }
                applyColorToTypedValue(outValue, replacement)
            }

            Log.i("Success: Hook WeType self-draw key colors")
        }.onFailure {
            Log.i("Failed: Hook WeType self-draw key colors")
            Log.i(it)
        }
    }

    private enum class KeyAttrRole { Fill, Shadow, Border }

    /**
     * Overrides the per-key background corner radius. The host reads [KeyData.bgCorner] (a design-unit
     * value, default 16) for every key and scales it together with the key size, so returning the
     * user's configured value here applies a uniform, size-proportional corner to all keys. Hooked on
     * the readable, serialization-stable bean class/getter rather than any obfuscated identifier.
     */
    fun hookKeyboardKeyCorner() {
        runCatching {
            val keyDataClass = loadClassOrNull(KEY_DATA_CLASS)
                ?: error("Failed to load KeyData")
            keyDataClass.getMethod("getBgCorner").hookAfter { param ->
                // KeyData.getBgCorner() returns a nullable Float; force it to the configured value so
                // every key (and the host's null-default fallback) uses the same corner radius. The
                // stored value is the real radius in dp, while bgCorner renders ~10dp smaller than its
                // value, so we add the offset to make the rendered corner match the dp the user set.
                val cornerDp = WeTypeSettings.getKeyCornerRadiusXposed()
                param.result = (cornerDp + KEY_CORNER_BGCORNER_OFFSET).toFloat()
            }
            Log.i("Success: Hook WeType keyboard key corner")
        }.onFailure {
            Log.i("Failed: Hook WeType keyboard key corner")
            Log.i(it)
        }
    }

    private fun resolveKeyColor(theme: Resources.Theme): Int {        val isDarkMode = theme.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val groupId = if (isDarkMode) DARK_KEY_COLOR_GROUP_ID else LIGHT_KEY_COLOR_GROUP_ID
        return WeTypeSettings.getAppearanceColorXposed(groupId)
    }

    private fun applyColorToTypedValue(outValue: TypedValue, color: Int) {
        outValue.type = if (Color.alpha(color) == 0xFF) {
            TypedValue.TYPE_INT_COLOR_RGB8
        } else {
            TypedValue.TYPE_INT_COLOR_ARGB8
        }
        outValue.data = color
        outValue.assetCookie = 0
        outValue.resourceId = 0
        outValue.string = null
    }

    fun hookCandidateSpecialTextColor() {
        runCatching {
            resolveCandidateSpecialTextColorMethod().hookAfter { param ->
                param.result = WeTypeSettings.getAppearanceColorXposed("theme_color")
            }
            Log.i("Success: Hook candidate special text color")
        }.onFailure {
            Log.i("Failed: Hook candidate special text color")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundCorner() {
        runCatching {
            val candidateViewClass = resolveCandidateSelfViewBaseClass()
            val cornerSetter = resolveKotlinPropertySetter(
                owner = candidateViewClass,
                propertyName = "cornerSize"
            )
            if (cornerSetter != null) {
                cornerSetter.hookBefore { param ->
                    param.args[0] = WeTypeSettings.getCandidateBackgroundCornerXposed().roundToInt()
                }
            } else {
                // WeType 3.4 and earlier stripped Kotlin metadata. Keep the old field as a narrow
                // compatibility fallback, while locating the draw call by its stable signature.
                val legacyCornerField = candidateViewClass.getDeclaredField("g").also {
                    it.isAccessible = true
                }
                resolveCandidateBackgroundDrawMethod(candidateViewClass).hookBefore { param ->
                    legacyCornerField.setInt(
                        param.thisObject,
                        WeTypeSettings.getCandidateBackgroundCornerXposed().roundToInt()
                    )
                }
            }
            Log.i("Success: Hook candidate background corner")
        }.onFailure {
            Log.i("Failed: Hook candidate background corner")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundAlpha() {
        runCatching {
            val candidateViewClass = resolveCandidateSelfViewBaseClass()
            val colorMethods = candidateViewClass.declaredMethods.filter { method ->
                Modifier.isPrivate(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            check(colorMethods.isNotEmpty()) {
                "Failed to find candidate background color resolver"
            }
            colorMethods.forEach { method ->
                method.hookAfter { param ->
                    val color = param.result as? Int ?: return@hookAfter
                    if (Color.alpha(color) == 0) return@hookAfter
                    param.result = withForcedAlpha(
                        color,
                        WeTypeSettings.getCandidateBackgroundAlphaXposed()
                    )
                }
            }
            Log.i("Success: Hook candidate background alpha")
        }.onFailure {
            Log.i("Failed: Hook candidate background alpha")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundLeftMargin() {
        runCatching {
            val scrollViewClass = loadClassOrNull(CANDIDATE_SCROLL_VIEW_CLASS)
                ?: error("Failed to load candidate scroll view")
            val candidateItemRootClass = resolveCandidateItemRootClass(scrollViewClass)
            val setInsetMethod = candidateItemRootClass.findMethodInHierarchy {
                parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaObjectType,
                        Int::class.javaObjectType,
                        Int::class.javaObjectType,
                        Int::class.javaObjectType
                    )
                ) && returnType == Void.TYPE
            }
            val positionMethod = candidateItemRootClass.findMethod {
                parameterTypes.isEmpty() && returnType == Int::class.javaPrimitiveType
            }
            val contextMethod = candidateItemRootClass.findMethodInHierarchy {
                parameterTypes.isEmpty() && returnType == Context::class.java
            }
            val holderClass = candidateItemRootClass.declaredFields
                .map { it.type }
                .firstOrNull { it.enclosingClass == scrollViewClass }
                ?: error("Failed to resolve candidate holder class")
            val holderItemRootMethod = holderClass.findMethod {
                parameterTypes.isEmpty() && candidateItemRootClass.isAssignableFrom(returnType)
            }
            val bindMethod = scrollViewClass.declaredClasses
                .asSequence()
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { method ->
                    !Modifier.isAbstract(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(
                            arrayOf(holderClass, Int::class.javaPrimitiveType)
                        )
                }
                ?.also { it.isAccessible = true }
                ?: error("Failed to resolve candidate holder bind method")

            setInsetMethod.hookBefore { param ->
                if (candidateMarginInsetWriteDepth.get() != 0) return@hookBefore
                val itemRoot = param.thisObject ?: return@hookBefore
                if (!candidateItemRootClass.isInstance(itemRoot)) return@hookBefore
                val leftPadding = param.args[0] as? Int ?: return@hookBefore
                candidateItemRootBaseLeftPaddingPx[itemRoot] = leftPadding
                candidateItemRootAppliedLeftPaddingPx.remove(itemRoot)
            }
            setInsetMethod.hookAfter { param ->
                if (candidateMarginInsetWriteDepth.get() != 0) return@hookAfter
                val itemRoot = param.thisObject ?: return@hookAfter
                if (!candidateItemRootClass.isInstance(itemRoot)) return@hookAfter
                val position = runCatching { positionMethod.invoke(itemRoot) as Int }.getOrNull()
                    ?: return@hookAfter
                applyCandidateBackgroundLeftMargin(
                    itemRoot = itemRoot,
                    position = position,
                    setInsetMethod = setInsetMethod,
                    contextMethod = contextMethod
                )
            }
            bindMethod.hookAfter { param ->
                val holder = param.args[0] ?: return@hookAfter
                val position = param.args[1] as? Int ?: return@hookAfter
                val itemRoot = runCatching { holderItemRootMethod.invoke(holder) }.getOrNull()
                    ?: return@hookAfter
                applyCandidateBackgroundLeftMargin(
                    itemRoot = itemRoot,
                    position = position,
                    setInsetMethod = setInsetMethod,
                    contextMethod = contextMethod
                )
            }
            Log.i("Success: Hook candidate background left margin")
        }.onFailure {
            Log.i("Failed: Hook candidate background left margin")
            Log.i(it)
        }
    }

    fun hookCandidatePinyinLeftMargin() {
        runCatching {
            val candidateViewClass = loadClassOrNull(CANDIDATE_VIEW_CLASS)
                ?: error("Failed to load ImeCandidateView")
            val containerId = loadClassOrNull(WETYPE_ID_R_CLASS)
                ?.getField(CANDIDATE_PINYIN_CONTAINER_ID_NAME)
                ?.getInt(null)
                ?: error("Failed to resolve $CANDIDATE_PINYIN_CONTAINER_ID_NAME")
            candidateViewClass.getDeclaredMethod("onAttachedToWindow").hookAfter { param ->
                val candidateView = param.thisObject as? View ?: return@hookAfter
                val container = candidateView.findViewById<View>(containerId) ?: return@hookAfter
                applyCandidatePinyinLeftMargin(container)
                ensureCandidatePinyinMarginSync(container)
            }
            Log.i("Success: Hook candidate pinyin left margin")
        }.onFailure {
            Log.i("Failed: Hook candidate pinyin left margin")
            Log.i(it)
        }
    }

    fun hookKeyboardLogo() {
        runCatching {
            val sClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s") ?: return
            val logoIvId = sClass.getField("logo_iv").getInt(null)

            // Resolve the resource IDs of the "dark" logo variants up-front from the host's R
            // drawable class. The R field names (e.g. ime_logo_green_dark) are stable source
            // identifiers that survive across host versions, whereas the obfuscated resource
            // *entry* names returned by getResourceEntryName (e.g. "j3" in 3.3.0, "j1" in 3.4.0)
            // change between releases. Matching on those entry-name strings is what previously
            // broke the dark-logo detection on 3.4.0 and left the background fully opaque.
            val darkLogoResIds = resolveDarkLogoResIds()

            ImageView::class.java.getMethod(
                "setImageResource",
                Int::class.javaPrimitiveType
            ).hookBefore { param ->
                val imageView = param.thisObject as? ImageView ?: return@hookBefore
                if (imageView.id != logoIvId || restoringLogoDrawable.get() == true) return@hookBefore

                val resId = param.args[0] as? Int ?: return@hookBefore
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = LogoHostState(resourceId = resId)
                }
                imageView.setTag(LOGO_RESOURCE_TAG_KEY, resId)
                val alpha = if (isDarkLogoResource(resId, darkLogoResIds, imageView.resources)) {
                    LOGO_DARK_BG_ALPHA_FRACTION
                } else {
                    LOGO_LIGHT_BG_ALPHA_FRACTION
                }
                imageView.setImageDrawable(WeTypeIconDrawable(alpha))
                param.result = null
            }
            ImageView::class.java.getMethod(
                "setImageDrawable",
                Drawable::class.java
            ).hookBefore { param ->
                val imageView = param.thisObject as? ImageView ?: return@hookBefore
                if (imageView.id != logoIvId || restoringLogoDrawable.get() == true) return@hookBefore

                val drawableArg = param.args.getOrNull(0)
                if (drawableArg is WeTypeIconDrawable) return@hookBefore
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = LogoHostState(drawable = drawableArg as? Drawable)
                }
                imageView.setTag(LOGO_RESOURCE_TAG_KEY, null)
                var alpha = LOGO_LIGHT_BG_ALPHA_FRACTION
                val uiMode = imageView.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    alpha = LOGO_DARK_BG_ALPHA_FRACTION
                }
                param.args[0] = WeTypeIconDrawable(alpha)
            }
            Log.i("Success: Hook WeType keyboard logo")
        }.onFailure {
            Log.i("Failed: Hook WeType keyboard logo")
            Log.i(it)
        }
    }

    fun reconcileCurrentKeyboardLogos(rootViews: Collection<View>) {
        val logoIvId = runCatching {
            loadClassOrNull(WETYPE_ID_R_CLASS)?.getField("logo_iv")?.getInt(null)
        }.getOrNull() ?: return
        val candidatePinyinContainerId = runCatching {
            loadClassOrNull(WETYPE_ID_R_CLASS)
                ?.getField(CANDIDATE_PINYIN_CONTAINER_ID_NAME)
                ?.getInt(null)
        }.getOrNull()
        val darkLogoResIds = resolveDarkLogoResIds()
        rootViews.forEach { rootView ->
            forEachView(rootView) { view ->
                if (view.id == candidatePinyinContainerId) {
                    applyCandidatePinyinLeftMargin(view)
                    ensureCandidatePinyinMarginSync(view)
                }
                val imageView = view as? ImageView ?: return@forEachView
                if (imageView.id != logoIvId || imageView.drawable is WeTypeIconDrawable) {
                    return@forEachView
                }
                val resourceId = imageView.getTag(LOGO_RESOURCE_TAG_KEY) as? Int
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = if (resourceId != null) {
                        LogoHostState(resourceId = resourceId)
                    } else {
                        LogoHostState(drawable = imageView.drawable)
                    }
                }
                val isDark = resourceId?.let { resId ->
                    isDarkLogoResource(resId, darkLogoResIds, imageView.resources)
                } ?: (
                    imageView.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    )
                imageView.setImageDrawable(
                    WeTypeIconDrawable(
                        if (isDark) LOGO_DARK_BG_ALPHA_FRACTION
                        else LOGO_LIGHT_BG_ALPHA_FRACTION
                    )
                )
            }
        }
    }

    private inline fun forEachView(rootView: View, action: (View) -> Unit) {
        val pending = java.util.ArrayDeque<View>()
        pending.add(rootView)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            action(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    /**
     * Resolves the resource IDs of every "dark" keyboard-logo drawable from the host's R drawable
     * class by their stable source field names (e.g. ime_logo_green_dark, icon_logo_grey_dark).
     *
     * This is deliberately version-agnostic: it enumerates the R fields whose names identify a dark
     * logo variant rather than relying on the obfuscated resource entry names, which differ between
     * host releases (e.g. "j3" on 3.3.0 vs "j1" on 3.4.0). Returns an empty set if the class or
     * fields cannot be resolved, in which case the caller falls back to name/uiMode heuristics.
     */
    private fun resolveDarkLogoResIds(): Set<Int> {
        val rClass = loadClassOrNull(WETYPE_DRAWABLE_R_CLASS) ?: return emptySet()
        return rClass.declaredFields.asSequence()
            .filter { field ->
                val name = field.name.lowercase()
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    field.type == Int::class.javaPrimitiveType &&
                    name.contains("logo") &&
                    name.endsWith("_dark")
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.getInt(null)
                }.getOrNull()
            }
            .toSet()
    }

    private fun isDarkLogoResource(
        resId: Int,
        darkLogoResIds: Set<Int>,
        resources: Resources
    ): Boolean {
        if (resId in darkLogoResIds) return true
        // Fallback for host builds that keep readable resource entry names (the dark variants then
        // literally contain "dark", e.g. icon_logo_grey_dark).
        val resName = runCatching { resources.getResourceEntryName(resId) }.getOrNull().orEmpty()
        return resName.contains("dark", ignoreCase = true)
    }

    fun hookToolbarIconBackground() {
        runCatching {
            val sClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s") ?: return
            val containerId = sClass.getField("custom_toolbar_item_container_view").getInt(null)

            View::class.java.getMethod(
                "setBackground",
                Drawable::class.java
            ).hookBefore { param ->
                val view = param.thisObject as? View ?: return@hookBefore
                if (view.id != containerId) return@hookBefore
                val drawable = param.args[0] as? Drawable ?: return@hookBefore
                drawable.alpha = WeTypeSettings.getToolbarIconBgOpacityXposed()
            }
            View::class.java.getMethod(
                "setBackgroundDrawable",
                Drawable::class.java
            ).hookBefore { param ->
                val view = param.thisObject as? View ?: return@hookBefore
                if (view.id != containerId) return@hookBefore
                val drawable = param.args[0] as? Drawable ?: return@hookBefore
                drawable.alpha = WeTypeSettings.getToolbarIconBgOpacityXposed()
            }
            Log.i("Success: Hook WeType toolbar icon background")
        }.onFailure {
            Log.i("Failed: Hook WeType toolbar icon background")
            Log.i(it)
        }
    }

    private fun resolveCandidateItemRootClass(scrollViewClass: Class<*>): Class<*> =
        scrollViewClass.declaredClasses.firstOrNull { nestedClass ->
            nestedClass.superclass?.name?.startsWith(CANDIDATE_SELF_VIEW_PACKAGE) == true &&
                nestedClass.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.contentEquals(arrayOf(Context::class.java))
                } &&
                nestedClass.declaredFields.any { field ->
                    field.type.enclosingClass == scrollViewClass
                }
        } ?: error("Failed to resolve candidate item root class")

    private fun resolveCandidateSpecialTextColorMethod(): Method {
        val candidateViewClass = loadClassOrNull(CANDIDATE_VIEW_CLASS)
            ?: error("Failed to load ImeCandidateView")
        val candidateClass = candidateViewClass.declaredMethods
            .asSequence()
            .map { it.returnType }
            .distinct()
            .firstOrNull { returnType ->
                returnType.superclass?.name == "com.tencent.wxhld.info.Candidate" &&
                    resolveCandidateColorCompanionMethod(returnType) != null
            }
            ?: error("Failed to resolve CandidateWithExtra")
        return resolveCandidateColorCompanionMethod(candidateClass)
            ?: error("Failed to resolve CandidateWithExtra color method")
    }

    private fun resolveCandidateColorCompanionMethod(candidateClass: Class<*>): Method? {
        val staticHolderTypes = candidateClass.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .map { it.type }
            .toSet()
        return candidateClass.declaredClasses
            .asSequence()
            .filter { it in staticHolderTypes }
            .firstNotNullOfOrNull { nestedClass ->
                val colorMethods = nestedClass.declaredMethods.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Int::class.javaPrimitiveType &&
                        method.parameterTypes.contentEquals(
                            arrayOf(Int::class.javaPrimitiveType)
                        )
                }
                val noArgColorMethodCount = nestedClass.declaredMethods.count { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Int::class.javaPrimitiveType &&
                        method.parameterTypes.isEmpty()
                }
                colorMethods.singleOrNull()
                    ?.takeIf { noArgColorMethodCount >= 2 }
                    ?.also { it.isAccessible = true }
            }
    }

    private fun resolveCandidateSelfViewBaseClass(): Class<*> {
        val scrollViewClass = loadClassOrNull(CANDIDATE_SCROLL_VIEW_CLASS)
            ?: error("Failed to load candidate scroll view")
        val itemRootClass = resolveCandidateItemRootClass(scrollViewClass)
        return generateSequence(itemRootClass as Class<*>?) { it.superclass }
            .firstOrNull { candidateClass ->
                candidateClass.name.startsWith(CANDIDATE_SELF_VIEW_PACKAGE) &&
                    runCatching { resolveCandidateBackgroundDrawMethod(candidateClass) }.isSuccess
            }
            ?: error("Failed to resolve candidate self view base class")
    }

    private fun resolveCandidateBackgroundDrawMethod(owner: Class<*>): Method = owner.findMethod {
        Modifier.isPrivate(modifiers) &&
            returnType == Void.TYPE &&
            parameterTypes.size == 3 &&
            parameterTypes[0] == Context::class.java &&
            parameterTypes[1] == Canvas::class.java &&
            parameterTypes[2] == Rect::class.java
    }

    private fun resolveKotlinPropertySetter(
        owner: Class<*>,
        propertyName: String
    ): Method? {
        owner.declaredMethods.firstOrNull { method ->
            method.name == "set${propertyName.replaceFirstChar { it.uppercase() }}" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            method.isAccessible = true
            return method
        }

        val metadata = owner.declaredAnnotations.firstOrNull { annotation ->
            annotation.annotationClass.java.name == "kotlin.Metadata"
        } ?: return null
        val data2 = runCatching {
            @Suppress("UNCHECKED_CAST")
            metadata.annotationClass.java.getMethod("d2").invoke(metadata) as Array<String>
        }.getOrNull() ?: return null
        val propertyIndex = data2.indexOf(propertyName)
        if (propertyIndex < 0) return null

        return (propertyIndex - 1 downTo maxOf(0, propertyIndex - 4))
            .asSequence()
            .map { data2[it] }
            .flatMap { methodName ->
                owner.declaredMethods.asSequence().filter { method ->
                    method.name == methodName &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
            }
            .firstOrNull()
            ?.also { it.isAccessible = true }
    }

    private fun applyCandidateBackgroundLeftMargin(
        itemRoot: Any,
        position: Int,
        setInsetMethod: Method,
        contextMethod: Method
    ) {
        if (position < 0) return
        val baseLeftPaddingPx = candidateItemRootBaseLeftPaddingPx[itemRoot] ?: 0
        val context = runCatching { contextMethod.invoke(itemRoot) as? Context }.getOrNull()
            ?: return
        val targetLeftPaddingPx = baseLeftPaddingPx + if (position == 0) {
            resolveCandidateBackgroundLeftMarginPx(context)
        } else {
            0
        }
        if (candidateItemRootAppliedLeftPaddingPx[itemRoot] == targetLeftPaddingPx) return

        candidateMarginInsetWriteDepth.set((candidateMarginInsetWriteDepth.get() ?: 0) + 1)
        try {
            setInsetMethod.invoke(itemRoot, targetLeftPaddingPx, null, null, null)
            candidateItemRootAppliedLeftPaddingPx[itemRoot] = targetLeftPaddingPx
        } finally {
            candidateMarginInsetWriteDepth.set((candidateMarginInsetWriteDepth.get() ?: 1) - 1)
        }
    }

    private fun ensureCandidatePinyinMarginSync(view: View) {
        if (candidatePinyinMarginListeners.containsKey(view)) return
        val layoutListener = View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            applyCandidatePinyinLeftMargin(changedView)
        }
        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyCandidatePinyinLeftMargin(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                candidatePinyinMarginListeners.remove(v)?.let { listeners ->
                    v.removeOnLayoutChangeListener(listeners.layout)
                }
                v.removeOnAttachStateChangeListener(this)
            }
        }
        view.addOnLayoutChangeListener(layoutListener)
        view.addOnAttachStateChangeListener(attachStateListener)
        candidatePinyinMarginListeners[view] = CandidatePinyinListeners(
            layout = layoutListener,
            attach = attachStateListener
        )
    }

    /**
     * Releases view listeners, restores host logo drawables, and clears per-generation caches
     * before API 102 hot reload retires this module generation.
     */
    fun prepareForHotReload() {
        synchronized(candidatePinyinMarginListeners) {
            candidatePinyinMarginListeners.forEach { (view, listeners) ->
                runCatching { view.removeOnLayoutChangeListener(listeners.layout) }
                runCatching { view.removeOnAttachStateChangeListener(listeners.attach) }
            }
            candidatePinyinMarginListeners.clear()
        }
        synchronized(candidatePinyinOriginalPaddings) {
            candidatePinyinOriginalPaddings.forEach { (view, padding) ->
                runCatching {
                    view.setPaddingRelative(
                        padding.start,
                        padding.top,
                        padding.end,
                        padding.bottom
                    )
                }
            }
            candidatePinyinOriginalPaddings.clear()
        }

        val logoStates = synchronized(replacedLogoStates) {
            replacedLogoStates.entries.toList().also { replacedLogoStates.clear() }
        }
        restoringLogoDrawable.set(true)
        try {
            logoStates.forEach { (imageView, state) ->
                runCatching {
                    state.resourceId?.let(imageView::setImageResource)
                        ?: imageView.setImageDrawable(state.drawable)
                }
            }
        } finally {
            restoringLogoDrawable.remove()
        }

        synchronized(typedArrayAttributeCache) { typedArrayAttributeCache.clear() }
        synchronized(resourcePackageCache) { resourcePackageCache.clear() }
        synchronized(candidateItemRootBaseLeftPaddingPx) { candidateItemRootBaseLeftPaddingPx.clear() }
        synchronized(candidateItemRootAppliedLeftPaddingPx) {
            candidateItemRootAppliedLeftPaddingPx.clear()
        }
        synchronized(appearanceColorParamsLock) {
            cachedAppearanceColors = null
            cachedAppearanceColorParams = emptyMap()
        }
        candidateMarginInsetWriteDepth.remove()
        hsvScratchThreadLocal.remove()
    }

    private fun applyCandidatePinyinLeftMargin(view: View) {
        synchronized(candidatePinyinOriginalPaddings) {
            candidatePinyinOriginalPaddings.putIfAbsent(
                view,
                ViewPadding(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom)
            )
        }
        val startPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidatePinyinLeftMarginDpXposed().toFloat(),
            view.resources.displayMetrics
        ).roundToInt()
        if (view.paddingStart == startPadding) return
        view.setPaddingRelative(
            startPadding,
            view.paddingTop,
            view.paddingEnd,
            view.paddingBottom
        )
    }

    private fun resolveCandidateBackgroundLeftMarginPx(context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidateBackgroundLeftMarginDpXposed().toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }

    private fun replaceColor(
        colorResId: Int,
        color: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Int {
        staticColorReplacements[colorResId]?.let { return it }
        val group = dynamicColorReplacements[colorResId] ?: return color
        return resolvedGroupColor(group, color)
    }

    private fun replaceTypedValue(
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        val replacementColor = staticColorReplacements[colorResId]
            ?: dynamicColorReplacements[colorResId]?.let { group ->
                resolvedGroupColor(group, typedValue.data)
            }
            ?: return false

        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceDrawable(
        resources: Resources,
        drawableResId: Int,
        theme: Resources.Theme?,
        drawableReplacements: Map<Int, Int>,
        getModuleResources: (Resources) -> Resources
    ): Drawable? {
        val replacementResId = drawableReplacements[drawableResId] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceColorStateList(
        colorResId: Int,
        colorStateList: ColorStateList,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): ColorStateList {
        val replacedColor = replaceColor(colorResId, colorStateList.defaultColor, staticColorReplacements, dynamicColorReplacements)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
    }

    private fun shouldReplaceDrawable(
        resources: Resources,
        drawableResId: Int,
        drawableReplacements: Map<Int, Int>
    ): Boolean = drawableResId in drawableReplacements && isWeTypeResource(resources, drawableResId)

    private fun shouldReplaceColor(
        resources: Resources,
        colorResId: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = isTargetColorResource(
        colorResId,
        staticColorReplacements,
        dynamicColorReplacements
    ) && isWeTypeResource(resources, colorResId)

    private fun shouldReplaceTypedValue(
        resources: Resources,
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = isTargetWeTypeTypedValue(
        resources,
        typedValue,
        staticColorReplacements,
        dynamicColorReplacements
    )

    private fun isTargetWeTypeTypedValue(
        resources: Resources,
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        return isTargetColorResource(colorResId, staticColorReplacements, dynamicColorReplacements) &&
            isWeTypeResource(resources, colorResId)
    }

    private fun isTargetColorResource(
        colorResId: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = colorResId in staticColorReplacements || colorResId in dynamicColorReplacements

    private fun hookThemeStyledAttributes(
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ) {
        runCatching {
            Resources.Theme::class.java.getMethod("obtainStyledAttributes", IntArray::class.java)
                .hookAfter { param ->
                    val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                    val resources = theme.resources
                    val typedArray = param.result as? TypedArray ?: return@hookAfter
                    val attrs = param.args[0] as? IntArray ?: return@hookAfter
                    if (!containsTargetWeTypeThemeAttr(resources, attrs, resolvedThemeAttrs)) return@hookAfter
                    typedArrayAttributeCache[typedArray] = attrs.copyOf()
                }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                Int::class.javaPrimitiveType,
                IntArray::class.java
            ).hookAfter { param ->
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param.args[1] as? IntArray ?: return@hookAfter
                if (!containsTargetWeTypeThemeAttr(resources, attrs, resolvedThemeAttrs)) return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                android.util.AttributeSet::class.java,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param.args[1] as? IntArray ?: return@hookAfter
                if (!containsTargetWeTypeThemeAttr(resources, attrs, resolvedThemeAttrs)) return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
    }

    private fun replaceThemeAttributeTypedValue(
        attrResId: Int,
        typedValue: TypedValue,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val group = resolvedThemeAttrs[attrResId] ?: return false
        val replacementColor = resolvedGroupColor(group, typedValue.data)
        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceThemeAttributeColor(
        typedArray: TypedArray, 
        index: Int,
        color: Int,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Int? {
        val attrIds = typedArrayAttributeCache[typedArray] ?: return null
        val attrResId = attrIds.getOrNull(index) ?: return null
        val group = resolvedThemeAttrs[attrResId] ?: return null
        return resolvedGroupColor(group, color)
    }

    private fun resolvedGroupColor(group: WeTypeAppearanceColorGroup, sourceColor: Int): Int {
        val params = appearanceColorParams(group)
        return when (params.mode) {
            WeTypeAppearanceColorMode.Direct -> params.userColor
            WeTypeAppearanceColorMode.HueShift -> tintToAccent(
                sourceColor = sourceColor,
                params = params
            )
        }
    }

    private fun appearanceColorParams(group: WeTypeAppearanceColorGroup): AppearanceColorParams {
        val appearanceColors = WeTypeSettings.getAppearanceColorsXposed()
        val cachedColors = cachedAppearanceColors
        if (appearanceColors === cachedColors) {
            cachedAppearanceColorParams[group.id]?.let { return it }
        }

        synchronized(appearanceColorParamsLock) {
            if (appearanceColors !== cachedAppearanceColors) {
                cachedAppearanceColorParams = buildAppearanceColorParams(appearanceColors)
                cachedAppearanceColors = appearanceColors
            }
            cachedAppearanceColorParams[group.id]?.let { return it }

            val params = buildAppearanceColorParam(
                group = group,
                userColor = appearanceColors[group.id] ?: group.defaultColor
            )
            cachedAppearanceColorParams = cachedAppearanceColorParams + (group.id to params)
            return params
        }
    }

    private fun buildAppearanceColorParams(
        appearanceColors: Map<String, Int>
    ): Map<String, AppearanceColorParams> {
        return WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to buildAppearanceColorParam(
                group = group,
                userColor = appearanceColors[group.id] ?: group.defaultColor
            )
        }
    }

    private fun buildAppearanceColorParam(
        group: WeTypeAppearanceColorGroup,
        userColor: Int
    ): AppearanceColorParams {
        if (group.colorMode == WeTypeAppearanceColorMode.Direct) {
            return AppearanceColorParams(
                mode = group.colorMode,
                userColor = userColor
            )
        }

        val defaultHsv = FloatArray(3)
        val targetHsv = FloatArray(3)
        Color.colorToHSV(group.defaultColor, defaultHsv)
        Color.colorToHSV(userColor, targetHsv)
        return AppearanceColorParams(
            mode = group.colorMode,
            userColor = userColor,
            targetHue = targetHsv[0],
            deltaSaturation = targetHsv[1] - defaultHsv[1],
            deltaValue = targetHsv[2] - defaultHsv[2]
        )
    }

    private fun tintToAccent(sourceColor: Int, params: AppearanceColorParams): Int {
        val sourceHsv = obtainHsvScratch()
        Color.colorToHSV(sourceColor, sourceHsv)

        sourceHsv[0] = params.targetHue
        sourceHsv[1] = (sourceHsv[1] + params.deltaSaturation).coerceIn(0f, 1f)
        sourceHsv[2] = (sourceHsv[2] + params.deltaValue).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(sourceColor), sourceHsv)
    }

    private fun obtainHsvScratch(): FloatArray {
        return hsvScratchThreadLocal.get()!!
    }

    private fun containsTargetWeTypeThemeAttr(
        resources: Resources,
        attrs: IntArray,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        return attrs.any { attrResId ->
            isTargetWeTypeThemeAttr(resources, attrResId, resolvedThemeAttrs)
        }
    }

    private fun isTargetWeTypeThemeAttr(
        resources: Resources,
        attrResId: Int,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        return attrResId in resolvedThemeAttrs && isWeTypeResource(resources, attrResId)
    }

    private fun isWeTypeResource(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        val cache = synchronized(resourcePackageCache) {
            resourcePackageCache.getOrPut(resources) { mutableMapOf() }
        }
        synchronized(cache) {
            cache[resId]
        }?.let { return it }

        val isWeTypeResource = runCatching {
            resources.getResourcePackageName(resId) == WETYPE_RESOURCE_PACKAGE
        }.getOrDefault(false)
        synchronized(cache) {
            cache[resId] = isWeTypeResource
        }
        return isWeTypeResource
    }

    private fun withForcedAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
