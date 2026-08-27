package com.mslx.console.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 全局连接中断弹窗宿主：挂载在导航根节点外层。
 * 使用过程中在线→离线时弹出一次提醒，用户关闭后不再重复弹，直到再次发生在线→离线跳变。
 */
@Composable
fun ConnectivityHost(
    viewModel: ConnectivityViewModel = viewModel(),
) {
    var showOfflineDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ConnectivityEvent.WentOffline -> showOfflineDialog = true
            }
        }
    }

    if (showOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineDialog = false },
            title = { Text("守护进程连接已断开") },
            text = { Text("与守护进程的连接中断，请检查网络或确认守护进程仍在运行。连接恢复后状态会自动更新。") },
            confirmButton = {
                TextButton(onClick = { showOfflineDialog = false }) { Text("知道了") }
            },
        )
    }
}
