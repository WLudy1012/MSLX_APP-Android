package com.wludy.rolithax.launcher.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.wludy.rolithax.launcher.data.ManagedServer
import com.wludy.rolithax.launcher.data.resources.ResourceIssue
import com.wludy.rolithax.launcher.data.resources.ResourceProject
import com.wludy.rolithax.launcher.data.resources.ResourceVersion
import com.wludy.rolithax.launcher.ui.MslxCard
import com.wludy.rolithax.launcher.ui.MslxSectionHeader
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

private data class ResourceCategory(val id: String, val label: String)

private val resourceCategories = listOf(
    ResourceCategory("mod", "模组"),
    ResourceCategory("plugin", "插件"),
    ResourceCategory("modpack", "整合包"),
    ResourceCategory("datapack", "数据包"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceCenterScreen(
    onOpenCreate: () -> Unit,
    viewModel: ResourceCenterViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val projects = viewModel.projects.collectAsLazyPagingItems()
    val snackbar = androidx.compose.material3.SnackbarHostState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        bottomBar = {
            when {
                state.installing -> InstallProgressBar(state.installProgress, state.installMessage, viewModel::cancelInstall)
                state.cart.isNotEmpty() -> CartBar(state.cart.size, viewModel::reviewCart)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 920.dp)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("资源中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "为目标实例挑选资源，统一检查依赖后再安装",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TargetSelector(
                    servers = state.servers,
                    selectedKey = state.selectedServerKey,
                    loading = state.loadingTargets,
                    gameVersion = state.gameVersion,
                    loader = state.loader,
                    onClick = viewModel::showTargetPicker,
                )

                if (state.cores.isNotEmpty()) MslApiBanner(state.cores.size, onOpenCreate)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setSearch,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("搜索模组、插件或整合包") },
                        shape = MaterialTheme.shapes.large,
                    )
                    FilterChip(
                        selected = state.serverOnly,
                        onClick = { viewModel.setServerOnly(!state.serverOnly) },
                        label = { Text("适配服务端") },
                        leadingIcon = if (state.serverOnly) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    resourceCategories.forEach { category ->
                        FilterChip(
                            selected = state.category == category.id,
                            onClick = { viewModel.setCategory(category.id) },
                            label = { Text(category.label) },
                        )
                    }
                }

                MslxSectionHeader(
                    title = when (state.category) {
                        "plugin" -> "适合此服务端的插件"
                        "modpack" -> "可以直接部署的整合包"
                        "datapack" -> "为世界添加数据包"
                        else -> "适合此服务端的模组"
                    },
                    subtitle = listOfNotNull(
                        state.gameVersion.takeIf(String::isNotBlank)?.let { "Minecraft $it" },
                        state.loader.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase)?.let { "适配 $it" },
                        "Modrinth v3",
                    ).joinToString(" · "),
                )

                when (val refresh = projects.loadState.refresh) {
                    is LoadState.Loading -> if (projects.itemCount == 0) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else ProjectList(projects, viewModel, Modifier.weight(1f))
                    is LoadState.Error -> if (projects.itemCount == 0) {
                        EmptyMessage("资源暂时无法加载", refresh.error.message ?: "检查网络后重试", "重试", projects::retry)
                    } else ProjectList(projects, viewModel, Modifier.weight(1f))
                    else -> if (projects.itemCount == 0) {
                        EmptyMessage("没有匹配的资源", "试试其他关键词、版本或资源类型。")
                    } else ProjectList(projects, viewModel, Modifier.weight(1f))
                }

                when (projects.loadState.append) {
                    is LoadState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is LoadState.Error -> TextButton(onClick = projects::retry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("加载更多失败，点此重试")
                    }
                    else -> Unit
                }
            }
        }
    }

    if (state.showTargetPicker) {
        ModalBottomSheet(onDismissRequest = viewModel::hideTargetPicker) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("选择安装目标", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("云端资源由 Daemon 直接下载，不会经过手机中转。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (state.loadingTargets) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else if (state.servers.isEmpty()) {
                    EmptyMessage("还没有可用实例", "先创建本地实例或连接 MSLX Daemon。", "创建服务端", onOpenCreate)
                } else {
                    LazyColumn {
                        items(state.servers, key = ManagedServer::key) { server ->
                            TargetRow(server, selected = server.key == state.selectedServerKey) { viewModel.selectServer(server) }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    state.selectedProject?.let { project ->
        ModalBottomSheet(onDismissRequest = viewModel::closeVersions) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("选择游戏版本与加载器匹配的文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (state.loadingVersions) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else if (state.versions.isEmpty()) {
                    EmptyMessage("没有可安装的版本", "当前实例的游戏版本或加载器可能不受支持。")
                } else {
                    LazyColumn {
                        items(state.versions, key = ResourceVersion::id) { version ->
                            VersionRow(version, state.gameVersion, state.loader) { viewModel.addVersion(version) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (state.showCart) {
        ModalBottomSheet(onDismissRequest = viewModel::closeCart) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("安装清单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    state.selectedServerKey?.let { key -> state.servers.firstOrNull { it.key == key }?.name }.orEmpty().ifBlank { "请先选择目标实例" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (state.cart.isEmpty()) {
                    EmptyMessage("清单还是空的", "选好资源版本后，它们会在这里一起校验。")
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(state.cart, key = { it.project.id }) { entry ->
                            CartRow(entry.project, entry.version.number) { viewModel.removeFromCart(entry.providerId, entry.project.id) }
                        }
                    }
                    val sharedMods = state.cart.filter { it.project.type == "mod" && it.project.clientCompatible }
                    if (sharedMods.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "客户端需同步 ${sharedMods.size} 个模组",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    TextButton(onClick = {
                                        val text = sharedMods.joinToString("\n") { "${it.project.name} ${it.version.number}".trim() }
                                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                            ClipData.newPlainText("客户端同步模组", text),
                                        )
                                        scope.launch { snackbar.showSnackbar("客户端模组清单已复制") }
                                    }) { Text("复制清单") }
                                }
                                Text(
                                    sharedMods.joinToString("、") { it.project.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    state.issues.forEach { issue -> IssueRow(issue) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = viewModel::closeCart, modifier = Modifier.weight(1f).height(50.dp)) {
                            Text("继续挑选")
                        }
                        Button(
                            onClick = viewModel::installCart,
                            enabled = state.cartValidated && !state.validatingCart && !state.installing && state.issues.none { it.severity == ResourceIssue.Severity.BLOCKING },
                            modifier = Modifier.weight(1f).height(50.dp),
                        ) {
                            if (state.validatingCart) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("安装到目标")
                        }
                    }
                    TextButton(
                        onClick = viewModel::reviewCart,
                        enabled = !state.validatingCart,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(if (state.issues.isEmpty()) "检查依赖与冲突" else "重新检查") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProjectList(projects: LazyPagingItems<ResourceProject>, viewModel: ResourceCenterViewModel, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(projects.itemCount, key = projects.itemKey { it.id }) { index ->
            projects[index]?.let { ResourceRow(it) { viewModel.openVersions(it) } }
        }
    }
}

@Composable
private fun ResourceRow(project: ResourceProject, onClick: () -> Unit) {
    MslxCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = project.iconUrl,
                contentDescription = "${project.name} 图标",
                modifier = Modifier.size(54.dp).clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(project.author.takeIf(String::isNotBlank), "${compactCount(project.downloads)} 次下载").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(project.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TargetSelector(
    servers: List<ManagedServer>,
    selectedKey: String?,
    loading: Boolean,
    gameVersion: String,
    loader: String,
    onClick: () -> Unit,
) {
    val selected = servers.firstOrNull { it.key == selectedKey }
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
            Text(
                when {
                    loading -> "正在读取实例"
                    selected == null -> "选择安装目标"
                    else -> "${selected.name} · ${selected.sourceLabel}"
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(gameVersion.takeIf(String::isNotBlank)?.let { "Minecraft $it" }, loader.takeIf(String::isNotBlank))
                    .joinToString(" · ").ifBlank { "本地实例或云端 Daemon" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TargetRow(server: ManagedServer, selected: Boolean, onClick: () -> Unit) {
    MslxCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), highlighted = selected) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${server.sourceLabel} · ${server.detail.ifBlank { "Minecraft 服务端" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VersionRow(version: ResourceVersion, gameVersion: String, loader: String, onAdd: () -> Unit) {
    val matching = (gameVersion.isBlank() || gameVersion in version.gameVersions) &&
        (loader.isBlank() || loader in version.loaders) && version.serverCompatible
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(version.name.ifBlank { version.number }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(version.number, version.loaders.take(3).joinToString(), version.gameVersions.take(3).joinToString()).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onAdd, enabled = matching) { Text(if (matching) "加入" else "不匹配") }
    }
}

@Composable
private fun CartRow(project: ResourceProject, version: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("版本 $version · ${typeLabel(project.type)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "移除 ${project.name}") }
    }
}

@Composable
private fun IssueRow(issue: ResourceIssue) {
    val color = if (issue.severity == ResourceIssue.Severity.BLOCKING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun CartBar(count: Int, onReview: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("$count 项待安装", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Button(onClick = onReview, modifier = Modifier.height(48.dp)) { Text("检查并安装") }
        }
    }
}

@Composable
private fun InstallProgressBar(progress: Float, message: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.ifBlank { "正在安装资源" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onCancel) { Text("取消") }
            }
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MslApiBanner(count: Int, onOpenCreate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("MSLAPI 服务端核心", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("${count} 个核心可在新建流程中选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            TextButton(onClick = onOpenCreate) { Text("新建服务端") }
        }
    }
}

@Composable
private fun EmptyMessage(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    MslxCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun compactCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", value / 10_000.0)
    value >= 1_000 -> String.format(Locale.getDefault(), "%.1f千", value / 1_000.0)
    else -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
}

private fun typeLabel(type: String): String = when (type) {
    "mod" -> "模组"
    "plugin" -> "插件"
    "modpack" -> "整合包"
    "datapack" -> "数据包"
    else -> type
}
