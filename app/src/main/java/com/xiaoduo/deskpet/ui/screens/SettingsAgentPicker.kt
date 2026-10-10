package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S16 平台智能体选择：行显当前 agent 名，点击展开内联单选列表。
 * 数据 POST /agent/list（443 反代，登录态复用 AguiClient）；失败可重试；
 * 选择即存 Prefs（id=请求头路由 + 显示名离线缓存），thread 连续性与本地历史不动
 * （用户 08-25 拍板：切换 agent 只换提示词/MCP，对话连续）。
 */
@Composable
internal fun AgentPicker(context: Context) {
    var expanded by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(Prefs.agentDisplayName(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf<List<AguiClient.AgentBrief>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun fetch() {
        loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            // ROB-13：非取消异常收敛为错误态复位 loading（原样逸出会顶穿组合且 loading 卡 true）
            val outcome = guardedUi { AguiClientFactory.create(context).listAgents() }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is UiOutcome.Ok -> when (val result = outcome.value) {
                        is AguiClient.AgentListResult.Ok -> agents = result.agents
                        is AguiClient.AgentListResult.Error -> error = result.message
                    }
                    is UiOutcome.Failed -> {
                        PetLogger.e("Settings", "agent list crashed: ${outcome.error.message}", outcome.error)
                        error = outcome.error.message ?: "未知错误"
                    }
                }
                loading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && agents.isEmpty() && error == null) fetch()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("平台智能体", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "当前：$displayName（不同 agent 提示词/MCP 不同，切换即生效）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "收起" else "选择",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            when {
                loading -> Text(
                    "正在拉取 agent 列表…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
                )
                error != null -> Column {
                    Text(
                        "拉取失败：$error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { fetch() }) { Text("重试") }
                }
                agents.isEmpty() -> Text(
                    "平台无可用 agent",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
                )
                else -> Column(
                    // S48 agent 全量后可能数十条：限高滚动，不撑爆设置页
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    agents.forEach { agent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Prefs.setAgent(context, agent.agentId, agent.agentName)
                                displayName = agent.agentName
                                expanded = false
                                PetLogger.i("Settings", "agent selected -> ${agent.agentId}")
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = Prefs.agentId(context) == agent.agentId, onClick = null)
                        Column {
                            Text(agent.agentName, style = MaterialTheme.typography.bodyLarge)
                            agent.agentDescription?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
