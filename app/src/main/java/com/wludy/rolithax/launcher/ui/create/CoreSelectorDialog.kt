package com.wludy.rolithax.launcher.ui.create

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreSelectorDialog(
    categories: List<CoreCategory>,
    selectedCategoryKey: String,
    selectedCoreName: String,
    versions: List<String>,
    versionDescription: String,
    builds: List<String>,
    buildsVisible: Boolean,
    loadingVersions: Boolean,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectCoreName: (String) -> Unit,
    onSelectVersion: (String) -> Unit,
    onSelectBuild: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val active = categories.firstOrNull { it.key == selectedCategoryKey }
    val filteredCores = (active?.cores.orEmpty()).filter { search.isBlank() || it.contains(search, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择服务端核心") },
        text = {
            Column(Modifier.fillMaxWidth().height(480.dp)) {
                // 分类
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = cat.key == selectedCategoryKey,
                            onClick = { onSelectCategory(cat.key) },
                            label = { Text(cat.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索核心名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (loading) {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        // 核心名称
                        Text("选择核心", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            filteredCores.forEach { coreName ->
                                FilterChip(
                                    selected = coreName == selectedCoreName,
                                    onClick = { onSelectCoreName(coreName) },
                                    label = { Text(coreName) },
                                )
                            }
                        }

                        if (selectedCoreName.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text("$selectedCoreName 支持版本", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            if (versionDescription.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(versionDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            when {
                                loadingVersions -> CircularProgressIndicator()
                                versions.isNotEmpty() -> {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        versions.forEach { version ->
                                            FilterChip(selected = false, onClick = { onSelectVersion(version) }, label = { Text(version) })
                                        }
                                    }
                                }
                                else -> Text("未找到版本信息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (buildsVisible) {
                            Spacer(Modifier.height(16.dp))
                            Text("选择构建版本", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                builds.forEach { build ->
                                    FilterChip(selected = false, onClick = { onSelectBuild(build) }, label = { Text(build) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
