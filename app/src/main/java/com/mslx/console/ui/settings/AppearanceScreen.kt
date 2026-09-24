package com.mslx.console.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.ThemeMode
import com.mslx.console.ui.theme.GlassBackground
import com.mslx.console.ui.theme.GlassLevel
import com.mslx.console.ui.theme.GlassSurface
import com.mslx.console.ui.theme.PresetColors
import com.mslx.console.ui.theme.ThemeConfig
import com.mslx.console.ui.theme.currentGlassLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 外观二级页：主题颜色与动态取色 + 毛玻璃（档位显示 / 深浅背景图 / 面板透明度 /
 * 实时预览 / 一键恢复默认）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val level = remember { currentGlassLevel() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 透明度草稿：拖动时实时预览，停顿 200ms 再落盘（避免高频写 DataStore）
    var alphaDraft by remember(settings.glassAlpha) { mutableStateOf(settings.glassAlpha) }
    LaunchedEffect(alphaDraft) {
        if (alphaDraft != settings.glassAlpha) {
            delay(200)
            viewModel.setGlassAlpha(alphaDraft)
        }
    }

    // 系统相册选图（Photo Picker，无需存储权限）；深浅两套各一个 launcher
    val lightPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.importThemeBackground(dark = false, uri) { path ->
                scope.launch {
                    snackbarHostState.showSnackbar(if (path != null) "浅色背景图已更新" else "背景图导入失败")
                }
            }
        }
    }
    val darkPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.importThemeBackground(dark = true, uri) { path ->
                scope.launch {
                    snackbarHostState.showSnackbar(if (path != null) "深色背景图已更新" else "背景图导入失败")
                }
            }
        }
    }

    val previewConfig = ThemeConfig(
        mode = settings.themeMode,
        seedColor = settings.seedColor,
        glassAlpha = alphaDraft,
        lightBackground = settings.lightBackgroundPath,
        darkBackground = settings.darkBackgroundPath,
    )

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---- 主题颜色 ----
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeOption(
                        title = "动态取色 (Material You)",
                        subtitle = "跟随系统壁纸自动生成配色（Android 12+）",
                        selected = settings.themeMode == ThemeMode.DYNAMIC,
                        onClick = { viewModel.setTheme(ThemeMode.DYNAMIC, settings.seedColor) },
                    )
                    Spacer(Modifier.size(8.dp))
                    ThemeOption(
                        title = "预设颜色",
                        subtitle = "从下方挑选一个主题色",
                        selected = settings.themeMode == ThemeMode.SEED,
                        onClick = { viewModel.setTheme(ThemeMode.SEED, settings.seedColor) },
                    )
                    if (settings.themeMode == ThemeMode.SEED) {
                        Spacer(Modifier.size(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PresetColors.forEach { preset ->
                                ColorDot(
                                    color = Color(preset.argb),
                                    selected = settings.seedColor == preset.argb,
                                    onClick = { viewModel.setTheme(ThemeMode.SEED, preset.argb) },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 毛玻璃 ----
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "毛玻璃",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "当前档位",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = level.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (level == GlassLevel.NONE) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "当前系统版本过低，毛玻璃背景与自定义背景图不可用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Spacer(Modifier.size(12.dp))
                        GlassPreview(config = previewConfig)

                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "面板不透明度 ${(alphaDraft * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Slider(
                            value = alphaDraft,
                            onValueChange = { alphaDraft = it },
                            valueRange = 0.25f..1f,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )

                        BackgroundRow(
                            title = "浅色模式背景图",
                            configured = settings.lightBackgroundPath.isNotBlank(),
                            onPick = {
                                lightPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onClear = { viewModel.clearThemeBackground(dark = false) },
                        )
                        BackgroundRow(
                            title = "深色模式背景图",
                            configured = settings.darkBackgroundPath.isNotBlank(),
                            onPick = {
                                darkPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onClear = { viewModel.clearThemeBackground(dark = true) },
                        )

                        Spacer(Modifier.size(4.dp))
                        TextButton(onClick = viewModel::resetGlassAppearance) {
                            Text("恢复默认外观")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 毛玻璃实时预览：小窗口里铺背景 + 半透明面板示意。 */
@Composable
private fun GlassPreview(config: ThemeConfig) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
    ) {
        GlassBackground(config = config, modifier = Modifier.fillMaxSize())
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .align(Alignment.Center),
            shape = RoundedCornerShape(14.dp),
            alpha = config.glassAlpha,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "毛玻璃预览",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "面板会以当前透明度叠加在背景上",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单套背景图设置行：标题 + 状态 + 选择/清除。 */
@Composable
private fun BackgroundRow(
    title: String,
    configured: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (configured) "已设置" else "未设置（使用主题色渐变底）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPick) { Text(if (configured) "更换" else "选择") }
        if (configured) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
