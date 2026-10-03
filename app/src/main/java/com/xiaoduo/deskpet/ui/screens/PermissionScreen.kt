package com.xiaoduo.deskpet.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger

/**
 * 权限引导页（S0 迁移悬浮窗权限引导；麦克风/通知/电池豁免为 Spec UI 结构占位，M3/M4 接入）。
 */

/** ROB-11：悬浮窗设置跳转候选（顺序尝试，纯逻辑可 JVM 单测）。部分 OEM 精简系统无
 *  MANAGE_OVERLAY_PERMISSION 的解析目标，兜底回退应用详情页。 */
internal fun overlaySettingsActions(): List<String> = listOf(
    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
)

/** ROB-11：按候选顺序跳转（每步 catch ActivityNotFoundException），全部不可解析仅记
 *  日志不崩（机型相关）；成功即返回。 */
internal fun openOverlaySettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    for (action in overlaySettingsActions()) {
        try {
            context.startActivity(Intent(action, packageUri))
            return
        } catch (e: ActivityNotFoundException) {
            PetLogger.w("Permission", "settings action not resolvable: $action")
        }
    }
    PetLogger.e("Permission", "no overlay settings activity resolvable on this device")
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                PetLogger.i("Permission", "onResume, canDrawOverlays=$overlayGranted")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = XiaoduoDimens.SpaceM),
        ) {
            PermissionRow(
                title = "悬浮窗权限",
                status = if (overlayGranted) "已授权 ✓" else "未授权（点右侧按钮去系统设置开启）",
            ) {
                Button(onClick = {
                    PetLogger.i("Permission", "jump to overlay permission settings")
                    openOverlaySettings(context)
                }) { Text("去开启") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            PermissionRow(title = "麦克风权限", status = "M3 语音链路接入") { }
            PermissionRow(title = "通知权限", status = "M3 前台服务常驻通知接入") { }
            PermissionRow(title = "电池优化豁免", status = "M4 保活接入") { }

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            Text(
                text = "国产 ROM 提示：小米/HyperOS 还需开「后台弹出界面」（设置→应用设置→授权管理→应用权限管理）；\n" +
                    "vivo/iQOO 需开「后台弹出界面」；OPPO/一加需允许自启动（手机管家→自启动管理）；\n" +
                    "华为/荣耀需允许后台活动。悬浮窗打不开或退后台消失时检查以上项。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceM),
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, status: String, action: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = XiaoduoDimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}
