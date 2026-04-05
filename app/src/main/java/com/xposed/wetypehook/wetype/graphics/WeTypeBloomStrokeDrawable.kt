package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class BloomStrokeLightSource(
    val centerXFraction: Float,
    val centerYFraction: Float,
    val depth: Float,
    val color: Int
)

private data class BloomStrokeSpec(
    val strokeWidth: Float,
    val gradientAngle: Float,
    val baseColor: Int,
    val glowWidth: Float,
    val source1: BloomStrokeLightSource,
    val source2: BloomStrokeLightSource
)

private val glassStrokeSmallLight = floatArrayOf(
    0.8f, 180.0f,
    1.0f, 1.0f, 1.0f, 0.05f,
    2.6f,
    0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.6f,
    0.5f, 0.95f, -0.5f, 1.0f, 1.0f, 1.0f, 0.35f
)

private val glassStrokeSmallDark = floatArrayOf(
    0.8f, 180.0f,
    1.0f, 1.0f, 1.0f, 0.08f,
    2.3f,
    0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.6f,
    0.5f, 0.95f, -0.36f, 1.0f, 1.0f, 1.0f, 0.25f
)

internal class WeTypeBloomStrokeDrawable(
    private val context: Context,
    private val cornerRadii: WeTypeCornerRadii,
    private val surfaceColor: Int,
    private val intensityScale: Float = 1f
) : Drawable() {
    private val contentPath = Path()
    private val glowPath = Path()
    private val innerGlowPath = Path()
    private val highlightPath = Path()

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val source1GlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val source2GlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        if (!contentPath.isEmpty) {
            canvas.drawPath(contentPath, sheenPaint)
        }
        if (!glowPath.isEmpty) {
            canvas.drawPath(glowPath, outerGlowPaint)
            canvas.drawPath(glowPath, source1GlowPaint)
            canvas.drawPath(glowPath, source2GlowPaint)
        }
        if (!innerGlowPath.isEmpty) {
            canvas.drawPath(innerGlowPath, innerGlowPaint)
        }
        if (!highlightPath.isEmpty) {
            canvas.drawPath(highlightPath, highlightPaint)
            canvas.drawPath(highlightPath, hairlinePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        updateShaders(bounds)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(
            sheenPaint,
            outerGlowPaint,
            innerGlowPaint,
            source1GlowPaint,
            source2GlowPaint,
            highlightPaint,
            hairlinePaint
        ).forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateShaders(bounds)
    }

    private fun updateShaders(bounds: Rect) {
        contentPath.reset()
        glowPath.reset()
        innerGlowPath.reset()
        highlightPath.reset()
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val darkMode = isDarkMode()
        val spec = bloomStrokeSpec(darkMode)
        val bloomLayerAlphaScale = if (darkMode) 0.3f else 1f
        val alphaScale = surfaceAlphaScale(surfaceColor) *
            (drawableAlpha / 255f) *
            bloomLayerAlphaScale *
            intensityScale.coerceAtLeast(0f)
        val hairlineAlphaScale = if (darkMode) 0.7f else 1f
        val contentRect = RectF(bounds).apply { inset(0.5f, 0.5f) }

        val highlightWidth = dp(spec.strokeWidth + 0.8f)
        val innerGlowWidth = dp(spec.strokeWidth + spec.glowWidth * 1.55f + 2.2f)
        val outerGlowWidth = dp(spec.strokeWidth + spec.glowWidth * 1.9f + 2.4f)
        val sourceGlowWidth = dp(spec.strokeWidth + spec.glowWidth * 2.5f + 3.2f)
        val hairlineWidth = dp(0.9f)

        contentPath.set(createOffsetRoundedPath(contentRect, cornerRadii))

        val glowOutset = outerGlowWidth * 0.58f + 0.6f
        val glowRect = RectF(bounds).apply { inset(-glowOutset, -glowOutset) }
        if (glowRect.width() > 0f && glowRect.height() > 0f) {
            glowPath.set(
                createOffsetRoundedPath(
                    glowRect,
                    cornerRadii.outset(glowOutset)
                )
            )
        }

        val innerGlowInset = innerGlowWidth * 0.34f + 0.6f
        val innerGlowRect = RectF(bounds).apply { inset(innerGlowInset, innerGlowInset) }
        if (innerGlowRect.width() > 0f && innerGlowRect.height() > 0f) {
            innerGlowPath.set(
                createOffsetRoundedPath(
                    innerGlowRect,
                    cornerRadii.inset(innerGlowInset)
                )
            )
        }

        val highlightInset = highlightWidth / 2f + 0.5f
        val highlightRect = RectF(bounds).apply { inset(highlightInset, highlightInset) }
        if (highlightRect.width() > 0f && highlightRect.height() > 0f) {
            highlightPath.set(
                createOffsetRoundedPath(
                    highlightRect,
                    cornerRadii.inset(highlightInset)
                )
            )
        }

        val edgeGlowGradient = buildLinearGradient(
            contentRect,
            spec.gradientAngle,
            intArrayOf(
                scaleColorAlpha(spec.baseColor, 0.26f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.10f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.025f * alphaScale),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.24f, 0.58f, 1f)
        )
        val outerGlowGradient = buildLinearGradient(
            contentRect,
            spec.gradientAngle,
            intArrayOf(
                scaleColorAlpha(spec.baseColor, 0.62f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.28f * alphaScale),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 1f)
        )
        val sheenGradient = buildVerticalGradient(
            contentRect,
            intArrayOf(
                scaleColorAlpha(Color.WHITE, 0.20f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.16f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.06f * alphaScale),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.16f, 0.40f, 0.72f)
        )
        val highlightGradient = buildLinearGradient(
            contentRect,
            spec.gradientAngle,
            intArrayOf(
                scaleColorAlpha(Color.WHITE, 0.88f * alphaScale),
                scaleColorAlpha(spec.baseColor, 1.05f * alphaScale),
                scaleColorAlpha(spec.baseColor, 0.26f * alphaScale)
            ),
            floatArrayOf(0f, 0.24f, 1f)
        )

        sheenPaint.shader = sheenGradient

        outerGlowPaint.strokeWidth = outerGlowWidth
        outerGlowPaint.shader = outerGlowGradient

        innerGlowPaint.strokeWidth = innerGlowWidth
        innerGlowPaint.shader = edgeGlowGradient

        source1GlowPaint.strokeWidth = sourceGlowWidth
        source1GlowPaint.shader = buildSourceGradient(contentRect, spec.source1, alphaScale, true)

        source2GlowPaint.strokeWidth = sourceGlowWidth * 0.92f
        source2GlowPaint.shader = buildSourceGradient(contentRect, spec.source2, alphaScale, false)

        highlightPaint.strokeWidth = highlightWidth
        highlightPaint.shader = highlightGradient

        hairlinePaint.strokeWidth = hairlineWidth
        hairlinePaint.shader = buildLinearGradient(
            contentRect,
            spec.gradientAngle,
            intArrayOf(
                scaleColorAlpha(Color.WHITE, 0.90f * alphaScale * hairlineAlphaScale),
                scaleColorAlpha(Color.WHITE, 0.18f * alphaScale * hairlineAlphaScale),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.38f, 1f)
        )
    }

    private fun buildSourceGradient(
        rect: RectF,
        source: BloomStrokeLightSource,
        alphaScale: Float,
        primary: Boolean
    ): Shader {
        val centerX = rect.left + rect.width() * source.centerXFraction
        val centerY = rect.top + rect.height() * source.centerYFraction
        val radius = rect.width().coerceAtLeast(rect.height()) * if (primary) 0.76f else 0.64f
        return RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                scaleColorAlpha(source.color, (if (primary) 0.92f else 0.68f) * alphaScale),
                scaleColorAlpha(source.color, (if (primary) 0.48f else 0.30f) * alphaScale),
                scaleColorAlpha(source.color, 0.10f * alphaScale),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.22f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun buildVerticalGradient(
        rect: RectF,
        colors: IntArray,
        positions: FloatArray
    ): Shader = LinearGradient(
        rect.left,
        rect.top,
        rect.left,
        rect.bottom,
        colors,
        positions,
        Shader.TileMode.CLAMP
    )

    private fun buildLinearGradient(
        rect: RectF,
        angle: Float,
        colors: IntArray,
        positions: FloatArray
    ): Shader {
        val radians = Math.toRadians(angle.toDouble())
        val directionX = sin(radians).toFloat()
        val directionY = -cos(radians).toFloat()
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val halfExtent =
            (kotlin.math.abs(directionX) * rect.width() +
                kotlin.math.abs(directionY) * rect.height()) / 2f
        return LinearGradient(
            centerX - directionX * halfExtent,
            centerY - directionY * halfExtent,
            centerX + directionX * halfExtent,
            centerY + directionY * halfExtent,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
    }

    private fun createOffsetRoundedPath(rect: RectF, cornerRadii: WeTypeCornerRadii): Path =
        createWeTypeContinuousRoundedPath(rect.width(), rect.height(), cornerRadii).apply {
            offset(rect.left, rect.top)
        }

    private fun isDarkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private fun surfaceAlphaScale(color: Int): Float {
        val alpha = Color.alpha(color) / 255f
        return (1.10f - alpha * 0.20f).coerceIn(0.88f, 1.08f)
    }

    private fun bloomStrokeSpec(isDarkMode: Boolean): BloomStrokeSpec =
        (if (isDarkMode) glassStrokeSmallDark else glassStrokeSmallLight).toBloomStrokeSpec()

    private fun FloatArray.toBloomStrokeSpec(): BloomStrokeSpec = BloomStrokeSpec(
        strokeWidth = this[0],
        gradientAngle = this[1],
        baseColor = rgbaToColor(this[2], this[3], this[4], this[5]),
        glowWidth = this[6],
        source1 = BloomStrokeLightSource(
            centerXFraction = this[7],
            centerYFraction = this[8],
            depth = this[9],
            color = rgbaToColor(this[10], this[11], this[12], this[13])
        ),
        source2 = BloomStrokeLightSource(
            centerXFraction = this[14],
            centerYFraction = this[15],
            depth = this[16],
            color = rgbaToColor(this[17], this[18], this[19], this[20])
        )
    )

    private fun rgbaToColor(r: Float, g: Float, b: Float, a: Float): Int = Color.argb(
        (a * 255f).roundToInt().coerceIn(0, 255),
        (r * 255f).roundToInt().coerceIn(0, 255),
        (g * 255f).roundToInt().coerceIn(0, 255),
        (b * 255f).roundToInt().coerceIn(0, 255)
    )

    private fun scaleColorAlpha(color: Int, scale: Float): Int {
        val scaledAlpha = (Color.alpha(color) * scale).roundToInt().coerceIn(0, 255)
        return Color.argb(scaledAlpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
