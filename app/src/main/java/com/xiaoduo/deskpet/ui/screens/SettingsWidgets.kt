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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
internal fun RowSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    var state by remember(checked) { mutableStateOf(checked) }
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
            checked = state,
            enabled = enabled,
            onCheckedChange = { onChecked(it); state = it },
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
