package com.mslx.console.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mslx.console.ui.MslxEmptyState

@Composable
fun ResourceCenterScreen(onOpenCreate: () -> Unit) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MslxEmptyState(
                icon = Icons.Filled.Info,
                title = "资源中心即将开放",
                description = "后续版本将在这里提供核心、整合包和扩展资源。现在可以从中央“+”创建服务端。",
                actionLabel = "创建服务端",
                onAction = onOpenCreate,
            )
        }
    }
}
