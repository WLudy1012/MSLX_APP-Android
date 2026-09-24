package com.mslx.console.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.data.model.PropOption
import com.mslx.console.data.model.PropSchema
import com.mslx.console.data.model.PropType
import com.mslx.console.data.model.SERVER_PROPERTIES_SCHEMA

/** 静态 schema 的派生索引：避免每次重组都重建 Set / 分组 Map。 */
private val KNOWN_PROPERTY_KEYS: Set<String> = SERVER_PROPERTIES_SCHEMA.map { it.key }.toSet()
private val PROPERTY_GROUPS: Map<String, List<PropSchema>> = SERVER_PROPERTIES_SCHEMA.groupBy { it.group }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPropertiesScreen(
    instanceId: Long,
    onBack: () -> Unit,
) {
    val viewModel: ServerPropertiesViewModel = viewModel(
        key = "server_props_$instanceId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ServerPropertiesViewModel(app, instanceId)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!state.loading) {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.saving,
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                            } else {
                                Text("保存", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                val values = state.values
                val unknownKeys = remember(values) { values.keys.filter { it !in KNOWN_PROPERTY_KEYS } }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = state.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.missing) {
                        item {
                            Text(
                                text = "未找到该文件，保存后会自动创建。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    PROPERTY_GROUPS.forEach { (group, props) ->
                        val visible = props.filter { values.containsKey(it.key) }
                        if (visible.isNotEmpty()) {
                            item(key = "group_$group") { SectionTitle(group) }
                            visible.forEach { prop ->
                                item(key = "known_${prop.key}") {
                                    PropCard(
                                        prop = prop,
                                        value = values[prop.key].orEmpty(),
                                        onValueChange = { v -> viewModel.setValue(prop.key, v) },
                                    )
                                }
                            }
                        }
                    }

                    if (unknownKeys.isNotEmpty()) {
                        item(key = "group_unknown") { SectionTitle("未收录配置项") }
                        unknownKeys.forEach { key ->
                            item(key = "unknown_$key") {
                                PropCard(
                                    prop = PropSchema(
                                        key = key,
                                        label = key,
                                        desc = "未收录的配置项",
                                        type = detectType(values[key].orEmpty()),
                                        group = "未收录配置项",
                                    ),
                                    value = values[key].orEmpty(),
                                    onValueChange = { v -> viewModel.setValue(key, v) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun detectType(v: String): PropType = when {
    v == "true" || v == "false" -> PropType.BOOLEAN
    v.isNotBlank() && v.toDoubleOrNull() != null -> PropType.NUMBER
    else -> PropType.STRING
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun PropCard(
    prop: PropSchema,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prop.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (prop.type == PropType.BOOLEAN) {
                    Switch(checked = value == "true", onCheckedChange = { onValueChange(it.toString()) })
                }
            }
            if (prop.desc.isNotBlank()) {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    text = prop.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = prop.key,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.padding(top = 8.dp))

            when (prop.type) {
                PropType.BOOLEAN -> Unit

                PropType.SELECT -> SelectDropdown(prop.options, value, onValueChange)

                PropType.NUMBER -> OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                PropType.STRING -> OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectDropdown(
    options: List<PropOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == selected }?.label ?: selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
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
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}
