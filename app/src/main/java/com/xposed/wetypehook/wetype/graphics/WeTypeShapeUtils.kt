package com.xposed.wetypehook.wetype.graphics

import android.graphics.Path
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline as ComposeOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.continuities.G2Continuity
import com.kyant.capsule.continuities.G2ContinuityProfile

internal data class WeTypeCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) {
    fun inset(amount: Float): WeTypeCornerRadii = WeTypeCornerRadii(
        topLeft = (topLeft - amount).coerceAtLeast(0f),
        topRight = (topRight - amount).coerceAtLeast(0f),
        bottomRight = (bottomRight - amount).coerceAtLeast(0f),
        bottomLeft = (bottomLeft - amount).coerceAtLeast(0f)
    )

    fun outset(amount: Float): WeTypeCornerRadii = WeTypeCornerRadii(
        topLeft = topLeft + amount,
        topRight = topRight + amount,
        bottomRight = bottomRight + amount,
        bottomLeft = bottomLeft + amount
    )

    fun maxRadius(): Float = maxOf(topLeft, topRight, bottomRight, bottomLeft)

    fun toArray(): FloatArray = floatArrayOf(
        topLeft, topLeft,
        topRight, topRight,
        bottomRight, bottomRight,
        bottomLeft, bottomLeft
    )

    companion object {
        fun uniform(radius: Float): WeTypeCornerRadii = WeTypeCornerRadii(
            topLeft = radius,
            topRight = radius,
            bottomRight = radius,
            bottomLeft = radius
        )
    }
}

private val weTypeSmoothContinuity = G2Continuity(
    profile = G2ContinuityProfile(
        extendedFraction = 0.66,
        arcFraction = 0.38,
        bezierCurvatureScale = 1.10,
        arcCurvatureScale = 1.10
    ),
    capsuleProfile = G2ContinuityProfile.Capsule
)

internal fun createWeTypeContinuousRoundedPath(
    width: Float,
    height: Float,
    cornerRadii: WeTypeCornerRadii
): Path {
    val outline = ContinuousRoundedRectangle(
        topStart = CornerSize(cornerRadii.topLeft),
        topEnd = CornerSize(cornerRadii.topRight),
        bottomEnd = CornerSize(cornerRadii.bottomRight),
        bottomStart = CornerSize(cornerRadii.bottomLeft),
        continuity = weTypeSmoothContinuity
    ).createOutline(
        size = Size(width, height),
        layoutDirection = LayoutDirection.Ltr,
        density = Density(1f)
    )
    return when (outline) {
        is ComposeOutline.Generic -> outline.path.asAndroidPath()
        is ComposeOutline.Rounded -> Path().apply {
            addRoundRect(
                0f,
                0f,
                width,
                height,
                cornerRadii.toArray(),
                Path.Direction.CW
            )
        }
        is ComposeOutline.Rectangle -> Path().apply {
            addRect(0f, 0f, width, height, Path.Direction.CW)
        }
    }
}

internal fun createWeTypeContinuousRoundedPath(
    width: Float,
    height: Float,
    radius: Float
): Path = createWeTypeContinuousRoundedPath(width, height, WeTypeCornerRadii.uniform(radius))
