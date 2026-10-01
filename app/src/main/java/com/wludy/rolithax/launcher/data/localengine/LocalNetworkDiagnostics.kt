package com.wludy.rolithax.launcher.data.localengine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address

data class LocalNetworkInfo(
    val connected: Boolean = false,
    val transport: String = "未连接",
    val addresses: List<String> = emptyList(),
    val hotspotHint: String = "",
    val firewallHint: String = "",
) {
    val addressText: String
        get() = addresses.joinToString("、").ifBlank { "暂无可用局域网地址" }
}

object LocalNetworkDiagnostics {
    fun inspect(context: Context, port: Int = 25565): LocalNetworkInfo {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return LocalNetworkInfo(hotspotHint = "系统不提供网络管理服务")
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val links = network?.let { manager.getLinkProperties(it) }
        val addresses = links?.linkAddresses.orEmpty()
            .mapNotNull { it.address }
            .filterIsInstance<Inet4Address>()
            .map { "${it.hostAddress}:$port" }
            .distinct()
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            network != null -> "其它网络"
            else -> "未连接"
        }
        val hotspotHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "如需直连，可在系统热点设置中开启本地热点；Android ${Build.VERSION.SDK_INT} 的热点创建需用户授权。"
        } else {
            "请在系统设置中开启热点后，让其它设备连接同一网络。"
        }
        return LocalNetworkInfo(
            connected = network != null,
            transport = transport,
            addresses = addresses,
            hotspotHint = hotspotHint,
            firewallHint = "普通应用无法直接修改系统防火墙；请在路由器放行端口，或授权 Shizuku/root 后再评估。",
        )
    }
}
