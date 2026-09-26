package com.mslx.console.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mslx.console.R
import com.mslx.console.data.model.PairCodeData
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.ui.MslxCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onBack: (() -> Unit)? = null,
    onAutoConnectFailed: () -> Unit = {},
    autoConnect: Boolean = true,
    editingDaemonId: String? = null,
) {
    // 新增页面也有返回回调；用路由中的 Daemon ID 区分新增和编辑，避免隐藏扫码入口。
    val isEditing = !editingDaemonId.isNullOrBlank()
    val viewModel: ConnectViewModel = viewModel(
        key = "connect_${autoConnect}_$editingDaemonId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ConnectViewModel(app, autoConnect, editingDaemonId)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 扫码配对：ScanContract 用户取消时 result.contents 为 null（由 ViewModel 静默忽略）
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.onScanResult(result.contents)
    }
    var showKey by remember { mutableStateOf(false) }
    var failMessage by remember { mutableStateOf<String?>(null) }
    var showHttpWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.connected.collect { onConnected() }
    }

    // 启动自动连接失败：弹窗提示，确认后回退主页
    LaunchedEffect(Unit) {
        viewModel.autoConnectFailed.collect { failMessage = it }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(if (isEditing) "编辑 Daemon" else "添加 Daemon") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 键盘弹起时内容上移，避免底部输入框被 IME 遮挡
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            if (onBack == null) {
                Image(
                    painter = painterResource(R.drawable.slime_logo),
                    contentDescription = "MSLX",
                    modifier = Modifier.size(92.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "MSLX 控制台",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "连接守护程序",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("名称（可选）") },
                placeholder = { Text("例如：家里的服务器") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Daemon 地址") },
                placeholder = { Text("https://192.168.1.100:1027") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                placeholder = { Text("请输入 API Key") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
                ),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))

            // 允许 HTTP 明文连接勾选框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.allowHttp,
                    onCheckedChange = { checked ->
                        if (checked) {
                            // 勾选时弹警告，5 秒后可确认；取消则保持不勾选
                            showHttpWarning = true
                        } else {
                            viewModel.onAllowHttpChange(false)
                        }
                    },
                )
                Text(
                    text = "允许 HTTP 明文连接（不推荐）",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                MslxCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::connect,
                enabled = !state.loading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = if (state.autoChecking) "正在自动连接…" else "连接",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // 扫码配对：添加模式扫码接入；编辑模式用当前凭据为其它设备生成配对码
            if (!isEditing) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setCaptureActivity(PairingScanActivity::class.java)
                                .setPrompt("")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true),
                        )
                    },
                    enabled = !state.loading && !state.pairing,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (state.pairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("正在配对…", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("扫码配对", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = viewModel::createPairCode,
                    enabled = !state.loading && !state.generating,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (state.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("正在生成…", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("生成配对二维码", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            MslxCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "提示：手机访问时请填写运行 MSLX 守护程序的电脑在内网中的 IP 地址，" +
                            "例如 https://192.168.1.100:1027。请确保手机与电脑处于同一网络，" +
                            "且守护程序已启用 HTTPS（可使用 MSLX 自签证书或自行准备的证书）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 配对二维码弹窗（生成成功时展示，供另一台设备扫码接入）
    state.pairCode?.let { pairCode ->
        PairCodeDialog(data = pairCode, onDismiss = viewModel::dismissPairCode)
    }

    // 自动连接失败弹窗
    if (failMessage != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                failMessage = null
                onAutoConnectFailed()
            },
            title = { Text("连接失败") },
            text = { Text(failMessage.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        failMessage = null
                        onAutoConnectFailed()
                    },
                ) { Text("确定") }
            },
        )
    }

    // 允许 HTTP 明文连接警告（5 秒倒计时后可确认）
    if (showHttpWarning) {
        var countdown by remember { mutableIntStateOf(5) }
        LaunchedEffect(Unit) {
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHttpWarning = false },
            title = {
                Text(
                    text = "安全警告：明文连接风险",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = "您正在尝试启用 HTTP 明文连接。这是最坏的连接方式，存在以下严重风险：\n\n" +
                        "1. API Key 将明文传输，同一网络中的任何设备都可抓包窃取您的完整密钥；\n" +
                        "2. 攻击者可实施中间人攻击，篡改您发送的命令与文件，向服务器注入恶意指令；\n" +
                        "3. 窃取密钥与篡改流量后，攻击者可以完全接管您的守护进程与所有 Minecraft 服务器，\n" +
                        "   包括删除数据、安装恶意插件、读取玩家隐私；\n" +
                        "4. 由此造成的一切损失（数据丢失、服务瘫痪、设备被控）均由您自行承担。\n\n" +
                        "另请注意：为兼容守护进程的自签证书，本应用对守护进程连接已关闭证书校验" +
                        "（即信任所有证书），HTTPS 连接同样存在被中间人攻击的可能。\n\n" +
                        "强烈建议使用 HTTPS（MSLX 自签证书或自备证书）并仅在自己的可信网络中使用。" +
                        "请仔细阅读以上内容，5 秒后方可确认。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = countdown <= 0,
                    onClick = {
                        viewModel.onAllowHttpChange(true)
                        showHttpWarning = false
                    },
                ) {
                    Text(
                        if (countdown > 0) "请等待 $countdown 秒" else "我已知晓风险，仍然继续",
                        color = if (countdown > 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showHttpWarning = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 一次性配对二维码弹窗：展示供另一台设备扫描的二维码与配对码。
 * 有效期由服务端 TTL 决定（默认 120 秒、仅可使用一次），过期需关闭后重新生成。
 */
@Composable
private fun PairCodeDialog(data: PairCodeData, onDismiss: () -> Unit) {
    var remaining by remember(data.payload) { mutableIntStateOf(data.expiresInSeconds.coerceAtLeast(0)) }
    LaunchedEffect(data.payload) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }
    val qrBitmap = remember(data.payload) { renderQrCodeBitmap(data.payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配对二维码") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp))
                            .padding(10.dp),
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "配对二维码",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Text(
                        text = "二维码渲染失败，请关闭后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "配对码 ${data.code}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (remaining > 0) "剩余 $remaining 秒，仅可使用一次" else "已过期，请关闭后重新生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remaining > 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "在另一台设备的 MSLX App 连接页选择「扫码配对」扫描此二维码，" +
                        "将自动创建独立凭据（默认 ${data.deviceTtlDays} 天有效，可在服务端随时撤销）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 用 zxing core 把文本渲染成二维码位图（白底黑码 + 1 模块静区，暗色主题下也能正常扫描）。 */
private fun renderQrCodeBitmap(content: String, sizePx: Int = 720): android.graphics.Bitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content,
        com.google.zxing.BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(sizePx * sizePx) { index ->
        val x = index % sizePx
        val y = index / sizePx
        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    android.graphics.Bitmap.createBitmap(pixels, sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
}.getOrNull()
