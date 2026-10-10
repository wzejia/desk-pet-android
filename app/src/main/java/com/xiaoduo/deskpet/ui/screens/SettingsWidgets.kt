package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger

@Composable
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
    )
}

/** 悬浮窗开关动作（见 [OverlaySwitchPolicy]）。 */
internal enum class OverlaySwitchAction { ENABLE, DISABLE, BLOCKED_TO_PERMISSION }

/**
 * 悬浮窗开关判定（审计 ocr[79] 开关失步配套，纯 JVM 可测）：
 * 未授权请求开 = 只跳权限页——不落 pref、不启服务（写被阻止时不产生乐观假象，Switch 由外部真源驱动保持 OFF）；
 * 授权请求开 = ENABLE；请求关 = DISABLE（无论授权态，显式关闭都落 pref 停服务）。
 * 守卫被删（未授权放行）→ OverlaySwitchPolicyTest 红。
 */
internal object OverlaySwitchPolicy {
    fun decide(requestOn: Boolean, canDrawOverlays: Boolean): OverlaySwitchAction =
        when {
            !requestOn -> OverlaySwitchAction.DISABLE
            canDrawOverlays -> OverlaySwitchAction.ENABLE
            else -> OverlaySwitchAction.BLOCKED_TO_PERMISSION
        }
}

/**
 * 行开关（无状态，审计 ocr[79] 修复）：Switch 恒显示外部真源 [checked]；
 * 用户 toggle 经 [onChecked] 由调用方同步写回快照状态驱动重组（写入被阻止时不产生乐观假象，
 * 根除旧 `state = it` 无条件回写与外部失步：未授权开悬浮窗后 Switch 假 ON 直至某次 checked 真变化）。
 */
@Composable
internal fun RowSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChecked,
        )
    }
}

@Composable
internal fun TextButtonRow(label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onClick) { Text(label) }
    }
}


internal fun versionName(context: Context): String =    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

/** 导出日志（偏差 D 裁决补做，迁移自 M1）：系统分享面板，FileProvider 授权。 */
internal fun exportLogs(context: Context) {
    val files = PetLogger.logFiles().filter { it.exists() }
    if (files.isEmpty()) {
        Toast.makeText(context, "暂无日志文件", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    PetLogger.i("Settings", "export logs, ${uris.size} file(s)")
    runCatching { context.startActivity(Intent.createChooser(send, "导出日志")) }
        .onFailure {
            PetLogger.e("Settings", "export logs failed", it)
            Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
}
