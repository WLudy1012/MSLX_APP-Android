package com.mslx.console.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mslx.console.ui.update.DISCLAIMER_TEXT

/**
 * 应用内可查看的合规文档。
 *
 * [key] 直接进路由（`legal/{key}`），新增文档只要在这里加一项，页面与入口自动跟上。
 */
enum class LegalDoc(val key: String, val title: String) {
    /** 第三方组件与 Java 运行时许可（正本随 APK 打包，离线可看）。 */
    NOTICES("notices", "第三方组件与许可"),

    /** 与首次开屏完全一致的第三方免责声明（同一份文本，不另写一份）。 */
    DISCLAIMER("disclaimer", "第三方免责声明"),
    ;

    companion object {
        fun fromKey(key: String?): LegalDoc = entries.firstOrNull { it.key == key } ?: NOTICES
    }
}

/** 许可清单在 assets 里的固定路径（同时也是仓库里的文件位置）。 */
private const val NOTICES_ASSET = "legal/THIRD_PARTY_NOTICES.md"

/** 合规文档查看页：不引第三方 Markdown 库，只做轻量分行渲染。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    docKey: String,
    onBack: () -> Unit,
) {
    val doc = LegalDoc.fromKey(docKey)
    val context = LocalContext.current
    val content = remember(doc) {
        when (doc) {
            LegalDoc.NOTICES -> runCatching {
                context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
            }.getOrElse { "未找到随包许可清单（$NOTICES_ASSET），请重新安装或到仓库查看同名文件。" }

            LegalDoc.DISCLAIMER -> DISCLAIMER_TEXT
        }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(doc.title, fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            LegalDocText(content)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 极简渲染：标题加粗、表格行等宽（避免列错位）、正文常规。
 * 文档本身是给人看的纯文本，不做完整 Markdown 解析，省掉一个依赖。
 */
@Composable
private fun LegalDocText(source: String) {
    val typography = MaterialTheme.typography
    source.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("### ") -> Text(line.removePrefix("### "), style = typography.titleSmall, fontWeight = FontWeight.SemiBold)
            line.startsWith("## ") -> Text(line.removePrefix("## "), style = typography.titleMedium, fontWeight = FontWeight.SemiBold)
            line.startsWith("# ") -> Text(line.removePrefix("# "), style = typography.headlineSmall, fontWeight = FontWeight.Bold)
            line.startsWith("|") -> Text(line, style = typography.bodySmall, fontFamily = FontFamily.Monospace)
            line.startsWith("- ") -> Text(line, style = typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
            line.isBlank() -> Spacer(Modifier.height(8.dp))
            else -> Text(line, style = typography.bodyMedium)
        }
    }
}
