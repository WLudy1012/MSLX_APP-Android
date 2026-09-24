package com.mslx.console.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.mslx.console.data.model.UserInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserCenterScreen(
    onBack: () -> Unit,
    viewModel: UserCenterViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSelfEditor by remember { mutableStateOf(false) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<UserInfo?>(null) }
    var deletingUser by remember { mutableStateOf<UserInfo?>(null) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(user = state.user, size = 30)
                        Text("用户中心", modifier = Modifier.padding(start = 10.dp))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading && state.user == null -> BoxLoading(Modifier.fillMaxSize().padding(innerPadding))
            state.user == null -> ErrorContent(
                message = state.error ?: "未获取到用户信息",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onRetry = viewModel::load,
            )
            else -> {
                val user = state.user!!
                val isSystemUser = user.isSystemUser
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ProfileCard(user = user, onEdit = { showSelfEditor = true }, canEdit = !isSystemUser)
                    }
                    item {
                        Text("账号安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        Card(shape = RoundedCornerShape(12.dp)) {
                            val clipboard = LocalClipboardManager.current
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("API Key", style = MaterialTheme.typography.labelLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(user.apiKey ?: "未返回", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(enabled = !user.apiKey.isNullOrBlank(), onClick = { clipboard.setText(AnnotatedString(user.apiKey.orEmpty())) }) {
                                        Icon(Icons.Filled.Settings, contentDescription = "复制 API Key")
                                    }
                                }
                                Text("可复制 API Key 用于连接 Daemon。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (user.role.equals("admin", ignoreCase = true) || isSystemUser) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("用户管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showCreateEditor = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Text("创建用户")
                                }
                            }
                        }
                        if (state.users.isEmpty()) {
                            item { Text("暂无其他用户", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            items(state.users, key = { it.id.orEmpty() }) { managedUser ->
                                ManagedUserCard(
                                    user = managedUser,
                                    currentUserId = user.id,
                                    onEdit = { editingUser = managedUser },
                                    onDelete = { deletingUser = managedUser },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSelfEditor) {
        SelfEditorDialog(
            user = state.user!!,
            saving = state.saving,
            onDismiss = { showSelfEditor = false },
            onSave = { username, name, avatar, password, reset ->
                viewModel.updateSelf(username, name, avatar, password, reset)
                showSelfEditor = false
            },
        )
    }
    if (showCreateEditor) {
        AdminEditorDialog(
            title = "创建用户",
            user = null,
            resources = state.resources,
            saving = state.saving,
            onDismiss = { showCreateEditor = false },
            onSave = { username, password, name, role, selected, _ ->
                viewModel.createUser(username, password, name, role, selected)
                showCreateEditor = false
            },
        )
    }
    editingUser?.let { userToEdit ->
        AdminEditorDialog(
            title = "编辑用户",
            user = userToEdit,
            resources = state.resources,
            saving = state.saving,
            onDismiss = { editingUser = null },
            onSave = { _, password, name, role, selected, reset ->
                viewModel.updateUser(userToEdit.id.orEmpty(), name, password, role, reset, selected)
                editingUser = null
            },
        )
    }
    deletingUser?.let { userToDelete ->
        AlertDialog(
            onDismissRequest = { deletingUser = null },
            title = { Text("删除用户") },
            text = { Text("确定删除 ${userToDelete.name ?: userToDelete.username ?: "该用户"} 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUser(userToDelete.id.orEmpty())
                    deletingUser = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingUser = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun UserAvatar(user: UserInfo?, size: Int) {
    val dimen = size.dp
    val initial = (user?.name ?: user?.username ?: "?").take(1)
    val avatar = user?.avatar?.takeIf { it.isNotBlank() }
    if (avatar == null) {
        LetteredAvatar(initial, dimen)
    } else {
        SubcomposeAsyncImage(
            model = avatar,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(dimen)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            loading = { LetteredAvatar(initial, dimen) },
            error = { LetteredAvatar(initial, dimen) },
        )
    }
}

@Composable
private fun LetteredAvatar(initial: String, dimen: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(dimen)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ProfileCard(user: UserInfo, onEdit: () -> Unit, canEdit: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!user.avatar.isNullOrBlank()) {
                AsyncImage(
                    model = user.avatar,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(CircleShape),
                )
            } else {
                Row(
                    modifier = Modifier.size(84.dp).clip(CircleShape),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text((user.name ?: user.username ?: "?").take(1), style = MaterialTheme.typography.headlineMedium) }
            }
            Text(user.name ?: user.username ?: "未知用户", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("@${user.username ?: "unknown"}", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
            Text("角色：${if (user.isSystemUser) "System" else user.role ?: "user"}", color = MaterialTheme.colorScheme.primary)
            if (canEdit) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("编辑资料")
                }
            }
        }
    }
}

@Composable
private fun ManagedUserCard(user: UserInfo, currentUserId: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(user.name ?: user.username ?: "未知用户", fontWeight = FontWeight.SemiBold)
                val roleLabel = if (user.isSystemUser) "System" else user.role ?: "user"
                Text("@${user.username ?: "unknown"} · $roleLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("资源：${user.resources.orEmpty().size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
            if (user.id != currentUserId) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun SelfEditorDialog(
    user: UserInfo,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, Boolean) -> Unit,
) {
    var username by remember { mutableStateOf(user.username.orEmpty()) }
    var name by remember { mutableStateOf(user.name.orEmpty()) }
    var avatar by remember { mutableStateOf(user.avatar.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var resetApiKey by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(avatar, { avatar = it }, label = { Text("头像 URL") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("新密码（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(resetApiKey, { resetApiKey = it })
                    Text("重置 API Key")
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = { onSave(username, name, avatar, password, resetApiKey) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminEditorDialog(
    title: String,
    user: UserInfo?,
    resources: List<ResourceOption>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, List<String>, Boolean) -> Unit,
) {
    var username by remember { mutableStateOf(user?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(user?.name.orEmpty()) }
    var role by remember { mutableStateOf(user?.role ?: "user") }
    var resetApiKey by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(user?.resources.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user == null) OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
                if (user == null) OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                Text("角色", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("user" to "普通用户", "admin" to "管理员").forEach { (value, label) ->
                        FilterChip(selected = role == value, onClick = { role = value }, label = { Text(label) })
                    }
                }
                if (user != null) {
                    OutlinedTextField(password, { password = it }, label = { Text("新密码（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(resetApiKey, { resetApiKey = it })
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Text("重置 API Key")
                    }
                }
                if (resources.isNotEmpty() && role != "admin") {
                    Text("资源权限", style = MaterialTheme.typography.labelLarge)
                    resources.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(option.value in selected, { checked -> selected = if (checked) selected + option.value else selected - option.value })
                            Text(option.label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = { onSave(username, password, name, role, selected.distinct(), resetApiKey) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BoxLoading(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorContent(message: String, modifier: Modifier, onRetry: () -> Unit) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
