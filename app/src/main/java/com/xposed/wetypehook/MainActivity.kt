package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeTypeSettingsApp(onRestartWeType = ::restartWeType)
        }
    }

    private fun restartWeType() {
        Thread {
            val hasRoot = runRootCommand("id")
            if (!hasRoot) {
                runOnUiThread {
                    Toast.makeText(this, R.string.settings_root_required, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            var restarted = false
            while (!restarted) {
                restarted = runRootCommand(
                    "am force-stop com.tencent.wetype && sleep 1 && monkey -p com.tencent.wetype 1"
                )
                if (!restarted) {
                    Thread.sleep(300)
                }
            }
            runOnUiThread {
                Toast.makeText(this, R.string.settings_restart_done, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun runRootCommand(command: String): Boolean {
        val process = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        }.getOrNull() ?: return false
        return runCatching {
            process.waitFor() == 0
        }.getOrDefault(false).also {
            process.destroy()
        }
    }
}

@Composable
private fun WeTypeSettingsApp(
    onRestartWeType: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        WeTypeSettingsScreen(onRestartWeType = onRestartWeType)
    }
}

@Composable
private fun SyncSystemBars(darkMode: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !darkMode
        insetsController.isAppearanceLightNavigationBars = !darkMode
    }
}

@Composable
private fun WeTypeSettingsScreen(
    onRestartWeType: () -> Unit
) {
    val context = LocalContext.current
    val snapshot = remember { WeTypeSettings.readSnapshot(context) }
    val systemDarkMode = isSystemInDarkTheme()

    var lightColor by rememberSaveable { mutableIntStateOf(snapshot.lightColor) }
    var darkColor by rememberSaveable { mutableIntStateOf(snapshot.darkColor) }
    var blurRadius by rememberSaveable { mutableIntStateOf(snapshot.blurRadius) }
    var cornerRadius by rememberSaveable { mutableIntStateOf(snapshot.cornerRadius) }
    var edgeHighlightEnabled by rememberSaveable { mutableStateOf(snapshot.edgeHighlightEnabled) }
    var edgeHighlightIntensity by rememberSaveable { mutableIntStateOf(snapshot.edgeHighlightIntensity) }
    var keyOpacity by rememberSaveable { mutableIntStateOf(snapshot.keyOpacity) }
    var currentModeIsDark by rememberSaveable { mutableStateOf(systemDarkMode) }
    var colorInput by rememberSaveable {
        mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    }
    var alphaValue by rememberSaveable {
        mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    fun saveSettings(showSavedToast: Boolean = true) {
        WeTypeSettings.save(
            context = context,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            keyOpacity = keyOpacity
        )
        if (showSavedToast) {
            Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        onRestartWeType()
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        keyOpacity = WeTypeSettings.DEFAULT_KEY_OPACITY
        syncEditorFromState()
        Toast.makeText(context, context.getString(R.string.settings_reset_toast), Toast.LENGTH_SHORT).show()
        saveSettings(showSavedToast = false)
    }

    val previewColor = currentColor()
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = ::restoreDefaults
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.settings_reset_title)
                        )
                    }
                },
                bottomContent = {
                    PreviewSection(
                        color = previewColor,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        keyOpacity = keyOpacity,
                        isDark = currentModeIsDark
                    )
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 颜色分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_color)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        // 模式切换 - 使用 TabRow
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_mode),
                                style = MiuixTheme.textStyles.main
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val tabs = listOf(
                                stringResource(R.string.settings_light_mode),
                                stringResource(R.string.settings_dark_mode)
                            )
                            TabRowWithContour(
                                tabs = tabs,
                                selectedTabIndex = if (currentModeIsDark) 1 else 0,
                                onTabSelected = { index ->
                                    val newDarkMode = index == 1
                                    if (newDarkMode != currentModeIsDark) {
                                        currentModeIsDark = newDarkMode
                                        syncEditorFromState()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 透明度滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_alpha_title),
                            value = alphaValue,
                            max = 255,
                            onValueChange = {
                                alphaValue = it
                                val rgb = currentColor() and 0xFFFFFF
                                updateColorFromArgb((alphaValue shl 24) or rgb)
                                colorInput = formatRgb(currentColor())
                            }
                        )

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_custom_color),
                                style = MiuixTheme.textStyles.main
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_color_helper),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextField(
                                value = colorInput,
                                onValueChange = { input ->
                                    val trimmed = input.trim()
                                    val hasPrefix = trimmed.startsWith("#")
                                    val body = trimmed.removePrefix("#")
                                    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                        return@TextField
                                    }

                                    colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                                    if (body.length == 6) {
                                        runCatching {
                                            val opaque = Color.parseColor("#$body")
                                            val argb = Color.argb(
                                                alphaValue.coerceIn(0, 255),
                                                Color.red(opaque),
                                                Color.green(opaque),
                                                Color.blue(opaque)
                                            )
                                            updateColorFromArgb(argb)
                                        }
                                    }
                                },
                                label = stringResource(R.string.settings_color_label),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 外观分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_appearance)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_edge_highlight_title),
                            description = stringResource(R.string.settings_edge_highlight_desc),
                            checked = edgeHighlightEnabled,
                            onCheckedChange = { edgeHighlightEnabled = it }
                        )

                        if (edgeHighlightEnabled) {
                            SliderPreferenceItem(
                                title = stringResource(R.string.settings_edge_highlight_intensity_title),
                                value = edgeHighlightIntensity,
                                max = 200,
                                onValueChange = { edgeHighlightIntensity = it }
                            )
                        }

                        HorizontalDivider()

                        // 模糊滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_blur_title),
                            value = blurRadius,
                            max = 100,
                            onValueChange = { blurRadius = it }
                        )

                        // 圆角滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_corner_title),
                            value = cornerRadius,
                            max = WeTypeSettings.MAX_CORNER_RADIUS,
                            onValueChange = { cornerRadius = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_key_opacity_title),
                            value = keyOpacity,
                            max = 255,
                            onValueChange = { keyOpacity = it }
                        )
                    }
                }
            }

            // 操作分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_actions)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.settings_save_title),
                            summary = stringResource(R.string.settings_save_desc),
                            onClick = { saveSettings() }
                        )

                        HorizontalDivider()

                        BasicComponent(
                            title = stringResource(R.string.settings_visit_github_title),
                            titleColor = BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary
                            ),
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/NEORUAA/MIUI_IME_Unlock")
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    keyOpacity: Int,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        SmallTitle(
            text = stringResource(R.string.settings_preview_title)
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = ComposeColor.Transparent
            )
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.natural_texture_004),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Column {
                    PreviewCard(
                        color = color,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        keyOpacity = keyOpacity,
                        isDark = isDark
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    keyOpacity: Int,
    isDark: Boolean
) {
    val context = LocalContext.current
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue * 2).dp)
    val previewShape = ContinuousRoundedRectangle(
        topStart = CornerSize(previewCorner),
        topEnd = CornerSize(previewCorner),
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp)
    )
    val previewKeyShape = ContinuousRoundedRectangle(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = color,
                        cornerRadius = previewCorner,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.natural_texture_004),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(ComposeColor(color))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(color).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(previewKeyBackgroundColor(isDark, keyOpacity))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = formatArgb(color),
                                    color = previewTextColor(color),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }

                            for (i in 'A'..'C') {
                                Box(
                                    modifier = Modifier
                                        .clip(previewKeyShape)
                                        .background(previewKeyBackgroundColor(isDark, keyOpacity))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = i.toString(),
                                        color = previewTextColor(color),
                                        style = MiuixTheme.textStyles.headline1,
                                        fontFamily = weTypeFontFamily
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius",
                            color = previewTextColor(color).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }
    }
}

private fun previewKeyBackgroundColor(isDark: Boolean, keyOpacity: Int): ComposeColor {
    val baseColor = if (isDark) 0xFF707070.toInt() else 0xFFFCFCFE.toInt()
    return ComposeColor(
        Color.argb(
            keyOpacity.coerceIn(0, 255),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
    )
}

@Composable
private fun Modifier.weTypePreviewBloom(
    color: Int,
    cornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewContext = remember(context, isDark) {
        createPreviewContext(context, isDark)
    }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    return this.drawWithCache {
        val previewCornerRadii = WeTypeCornerRadii(
            topLeft = cornerRadiusPx,
            topRight = cornerRadiusPx,
            bottomRight = 0f,
            bottomLeft = 0f
        )
        val clipPath = createWeTypeContinuousRoundedPath(
            width = size.width,
            height = size.height,
            cornerRadii = previewCornerRadii
        )
        val bloomDrawable = if (edgeHighlightEnabled) {
            WeTypeBloomStrokeDrawable(
                context = previewContext,
                cornerRadii = previewCornerRadii,
                surfaceColor = color,
                intensityScale = edgeHighlightIntensity / 100f
            )
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            bloomDrawable ?: return@onDrawWithContent
            bloomDrawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val checkpoint = nativeCanvas.save()
                nativeCanvas.clipPath(clipPath)
                bloomDrawable.draw(nativeCanvas)
                nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }
}

private fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$value",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MiuixSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggleAction = {
        onCheckedChange(!checked)
    }

    BasicComponent(
        title = title,
        summary = description,
        onClick = toggleAction,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

private fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

private fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun formatArgb(color: Int): String = String.format("#%08X", color)

private fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}
