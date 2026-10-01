package com.wludy.rolithax.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.data.model.LocalJava

/**
 * Java 环境选择器(严格对齐网页版 GeneralSettings 的 javaType 逻辑)。
 * 通过回调把结果写回 settings.java 与 settings.dockerImage。
 */
@Composable
fun JavaEnvironmentSelector(
    java: String,
    dockerImage: String?,
    args: String?,
    onlineVersions: List<String>,
    localJavas: List<LocalJava>,
    onJavaChanged: (String) -> Unit,
    onDockerImageChanged: (String) -> Unit,
) {
    // 独立状态：用户选择的类型不会被 java 反推回 custom
    var javaType by remember { mutableStateOf(parseJavaType(java, dockerImage, args, localJavas)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SelectorField(
            label = "启动方式",
            options = JavaType.entries.map { it.value to it.label },
            selectedValue = javaType.value,
            onSelect = { value ->
                val type = JavaType.entries.first { it.value == value }
                javaType = type
                onJavaChanged(defaultJavaFor(type))
                if (type == JavaType.DOCKER_JAVA || type == JavaType.MCDR_DOCKER_JAVA) {
                    onDockerImageChanged(dockerImageForVersion(dockerJavaVersion(dockerImage)))
                }
            },
        )

        when (javaType) {
            JavaType.ONLINE -> {
                Spacer(Modifier.padding(top = 8.dp))
                if (onlineVersions.isEmpty()) {
                    // 在线版本列表获取失败时，允许手动输入版本号兜底
                    OutlinedTextField(
                        value = java.removePrefix("MSLX://Java/"),
                        onValueChange = { v ->
                            onJavaChanged(if (v.isBlank()) "" else "MSLX://Java/$v")
                        },
                        label = { Text("Java 版本号（在线列表获取失败，可手动输入）") },
                        placeholder = { Text("例如 17 或 21") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SelectorField(
                        label = "在线 Java 版本",
                        options = onlineVersions.map { v -> v to "Java $v (在线)" },
                        selectedValue = java.removePrefix("MSLX://Java/"),
                        onSelect = { v -> onJavaChanged("MSLX://Java/$v") },
                    )
                }
            }

            JavaType.LOCAL -> {
                Spacer(Modifier.padding(top = 8.dp))
                SelectorField(
                    label = "本地 Java",
                    options = localJavas.map { it.path to "Java ${it.version} (${it.path})" },
                    selectedValue = java,
                    onSelect = { path -> onJavaChanged(path) },
                )
            }

            JavaType.CUSTOM -> {
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = java,
                    onValueChange = onJavaChanged,
                    label = { Text("Java 可执行文件完整路径") },
                    placeholder = { Text("例如 /usr/bin/java 或 C:\\...\\java.exe") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            JavaType.DOCKER_JAVA, JavaType.MCDR_DOCKER_JAVA -> {
                Spacer(Modifier.padding(top = 8.dp))
                SelectorField(
                    label = "容器内 Java 运行时版本",
                    options = DOCKER_JAVA_VERSIONS.map { it to "MSLX Docker 镜像 [Java $it]" },
                    selectedValue = dockerJavaVersion(dockerImage),
                    onSelect = { v -> onDockerImageChanged(dockerImageForVersion(v)) },
                )
            }

            JavaType.DOCKER_CUSTOM, JavaType.MCDR_DOCKER_CUSTOM -> {
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = dockerImage.orEmpty(),
                    onValueChange = onDockerImageChanged,
                    label = { Text("Docker 镜像") },
                    placeholder = { Text("例如 itzg/minecraft-server:latest") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> Unit // env / none / mcdr：无需子控件
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selectedValue }?.second ?: selectedValue,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, labelText) ->
                DropdownMenuItem(
                    text = { Text(labelText) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
