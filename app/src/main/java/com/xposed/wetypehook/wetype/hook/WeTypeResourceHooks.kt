package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

internal object WeTypeResourceHooks {
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

    fun hookXmlDrawables(
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ) {
        runCatching {
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceDrawable(resources, resId, null, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param.args[0] as? Int ?: return@hookAfter
                val theme = param.args[1] as? Resources.Theme
                replaceDrawable(resources, resId, theme, drawableReplacements, getModuleResources)
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
                    replaceDrawable(resources, resId, null, drawableReplacements, getModuleResources)
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
                    val theme = param.args[2] as? Resources.Theme
                    replaceDrawable(resources, resId, theme, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    replaceDrawable(typedArray.resources, resId, null, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Log.i("Success: Hook WeType xml drawables")
        }.onFailure {
            Log.i("Failed: Hook WeType xml drawables")
            Log.i(it)
        }
    }

    fun hookTransparentColors(colorReplacements: Map<String, Int>) {
        runCatching {
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColor(resources, colorResId, param.result as Int, colorReplacements)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColor(resources, colorResId, param.result as Int, colorReplacements)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColorStateList(
                        resources,
                        colorResId,
                        param.result as ColorStateList,
                        colorReplacements
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColorStateList(
                    resources,
                    colorResId,
                    param.result as ColorStateList,
                    colorReplacements
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
                param.result = replaceColor(typedArray.resources, colorResId, param.result as Int, colorReplacements)
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    if (colorResId == 0) return@hookAfter
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    param.result = replaceColorStateList(
                        typedArray.resources,
                        colorResId,
                        colorStateList,
                        colorReplacements
                    )
                }
            Log.i("Success: Hook WeType transparent colors")
        }.onFailure {
            Log.i("Failed: Hook WeType transparent colors")
            Log.i(it)
        }
    }

    fun hookSelfDrawKeyColors() {
        runCatching {
            val imeButtonClass = loadClassOrNull("com.tencent.wetype.plugin.hld.keyboard.selfdraw.j")
                ?: error("Failed to load ImeButton")

            listOf(
                "i" to { context: Context -> WeTypeSettings.getKeyOpacityXposed(context) },
                "k" to { _: Context -> 0x20 },
                "X" to { context: Context -> (WeTypeSettings.getKeyOpacityXposed(context) + 20).coerceAtMost(255) }
            ).forEach { (methodName, alphaProvider) ->
                imeButtonClass.getMethod(methodName).hookAfter { param ->
                    val color = param.result as? Int ?: return@hookAfter
                    if (color == 0) return@hookAfter
                    val button = param.thisObject ?: return@hookAfter
                    val view = runCatching { button.getObjectAs<android.view.View>("a") }.getOrNull()
                    val context = view?.context ?: return@hookAfter
                    param.result = withForcedAlpha(color, alphaProvider(context))
                }
            }

            imeButtonClass.getMethod("Y").hookReturnConstant(Color.TRANSPARENT)

            Log.i("Success: Hook WeType self-draw key colors")
        }.onFailure {
            Log.i("Failed: Hook WeType self-draw key colors")
            Log.i(it)
        }
    }

    private fun replaceColor(
        resources: Resources,
        colorResId: Int,
        color: Int,
        colorReplacements: Map<String, Int>
    ): Int {
        val colorName = runCatching { resources.getResourceEntryName(colorResId) }.getOrNull() ?: return color
        return colorReplacements[colorName] ?: color
    }

    private fun replaceDrawable(
        resources: Resources,
        drawableResId: Int,
        theme: Resources.Theme?,
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ): Drawable? {
        val drawableName = runCatching { resources.getResourceEntryName(drawableResId) }.getOrNull() ?: return null
        val replacementResId = drawableReplacements[drawableName] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceColorStateList(
        resources: Resources,
        colorResId: Int,
        colorStateList: ColorStateList,
        colorReplacements: Map<String, Int>
    ): ColorStateList {
        val replacedColor = replaceColor(resources, colorResId, colorStateList.defaultColor, colorReplacements)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
    }

    private fun withForcedAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
