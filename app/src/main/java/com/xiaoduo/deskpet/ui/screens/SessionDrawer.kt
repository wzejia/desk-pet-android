package com.xiaoduo.deskpet.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoduo.deskpet.chat.ChatSession
import com.xiaoduo.deskpet.chat.SessionSummary
import com.xiaoduo.deskpet.ui.PetView
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话边栏抽屉内容（真边栏票，用户拍板「点击后不要翻页，对标小布/ChatGPT」）：
 * 收窄露 scrim（屏宽 80% 与 300dp 取小）→ 头部（宠物头像 + 「小朵」）→ 搜索会话框
 * （实时过滤，空结果提示）→「新建会话」显著入口 → 会话列表（标题 + 相对时间/搜索摘要，
 * 当前会话高亮，点击切换，长按弹操作菜单：重命名 / 删除（二次确认））→ 底部「设置」。
 * ADR 0002：废弃独立聊天记录页，看历史＝进入会话，会话管理全在本抽屉。
 *
 * 本组件不直接持 vm：数据与回调全部参数化，搜索经 [onSearch] 回调（IO 切到
 * [Dispatchers.IO]，避免主线程逐击键读盘）。
 */
@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    onSearch: (String) -> List<SessionSummary>,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // 长按目标会话：先弹操作菜单（重命名/删除），菜单选择再进各自确认/输入弹窗
    var actionTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteCandidate by remember { mutableStateOf<ChatSession?>(null) }
    var renameCandidate by remember { mutableStateOf<ChatSession?>(null) }
    // null = 未搜索（query 空白）；搜索中短暂 null 由「搜索中…」占位
    val searchResults by produceState<List<SessionSummary>?>(initialValue = null, query, sessions) {
        value = if (query.isBlank()) {
            null
        } else {
            // ROB-10：搜索 IO 异常降级空结果，不逸出 producer 撕毁组合
            withContext(Dispatchers.IO) {
                searchCatching(query, onSearch) {
                    PetLogger.w("SessionDrawer", "search failed, degrade to empty: ${it.message}")
                }
            }
        }
    }
    // 收窄露 scrim：参照 ChatGPT 边栏约占屏宽 80%，300dp 封顶（默认 360dp 满屏像翻页）
    val sheetWidth = minOf(300.dp, (LocalConfiguration.current.screenWidthDp * 0.8f).dp)

    ModalDrawerSheet(modifier = Modifier.width(sheetWidth)) {
        Row(
            modifier = Modifier.padding(XiaoduoDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 静态头像：默认帧即可，不与顶栏相位驱动精灵抢状态
            AndroidView(
                factory = { context -> PetView(context) },
                modifier = Modifier.size(40.dp),
            )
            Text(
                "小朵",
                modifier = Modifier.padding(start = XiaoduoDimens.SpaceM),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = XiaoduoDimens.SpaceM),
            placeholder = { Text("搜索会话") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceS))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = XiaoduoDimens.SpaceM)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onNewSession)
                .padding(horizontal = XiaoduoDimens.SpaceM, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "新建会话",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceS))
        val results = searchResults
        when {
            query.isNotBlank() && results == null -> DrawerHint("搜索中…", Modifier.weight(1f))
            query.isNotBlank() && results.isNullOrEmpty() ->
                DrawerHint("无匹配会话", Modifier.weight(1f))
            query.isNotBlank() -> {
                val snippets = results.orEmpty().associate { it.session.id to it.snippet }
                SessionList(
                    sessions = results.orEmpty().map { it.session },
                    currentSessionId = currentSessionId,
                    subtitleFor = { snippets[it.id].orEmpty() },
                    onClick = onSwitchSession,
                    onLongClick = { actionTarget = it },
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                if (sessions.isEmpty()) {
                    DrawerHint("暂无会话", Modifier.weight(1f))
                } else {
                    val now = System.currentTimeMillis()
                    SessionList(
                        sessions = sessions,
                        currentSessionId = currentSessionId,
                        subtitleFor = { relativeTimeLabel(now, it.updatedAt) },
                        onClick = onSwitchSession,
                        onLongClick = { actionTarget = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        NavigationDrawerItem(
            label = { Text("设置") },
            icon = {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceM))
    }

    // 长按 → 操作菜单（ADR 0002 入口范式 b：重命名/删除 两选项，替代直接弹删除确认）
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("要对这条会话做什么？") },
            confirmButton = {
                TextButton(onClick = {
                    renameCandidate = target
                    actionTarget = null
                }) { Text("重命名") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        deleteCandidate = target
                        actionTarget = null
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { actionTarget = null }) { Text("取消") }
                }
            },
        )
    }

    // 删除二次确认（保持原交互）
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除会话") },
            text = { Text("删除会话『${candidate.title}』？删除后不可恢复") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(candidate.id)
                    deleteCandidate = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            },
        )
    }

    // 重命名：AlertDialog + OutlinedTextField（预填现标题，非空才可确认）
    renameCandidate?.let { candidate ->
        var newTitle by remember(candidate.id) { mutableStateOf(candidate.title) }
        AlertDialog(
            onDismissRequest = { renameCandidate = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(candidate.id, newTitle.trim())
                        renameCandidate = null
                    },
                    enabled = newTitle.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameCandidate = null }) { Text("取消") }
            },
        )
    }
}

/** 居中占位提示（搜索中 / 无匹配会话），占满列表区剩余高度。 */
@Composable
private fun DrawerHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 会话列表：标题 + 副行（相对时间或搜索摘要），当前会话浅底高亮；长按弹操作菜单。 */
@Composable
private fun SessionList(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    subtitleFor: (ChatSession) -> String,
    onClick: (String) -> Unit,
    onLongClick: (ChatSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(sessions, key = { it.id }) { session ->
            SessionItem(
                session = session,
                subtitle = subtitleFor(session),
                selected = session.id == currentSessionId,
                onClick = { onClick(session.id) },
                onLongClick = { onLongClick(session) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: ChatSession,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = XiaoduoDimens.SpaceS, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = XiaoduoDimens.SpaceM, vertical = 10.dp),
    ) {
        Text(
            session.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 相对时间标签（会话列表副行）：<1 分钟「刚刚」→ N 分钟/小时/天前 → ≥7 天落日期。
 * 纯逻辑（入参注入 now），JVM 单测覆盖。
 */
internal fun relativeTimeLabel(nowMillis: Long, ts: Long): String {
    val diff = (nowMillis - ts).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "$minutes 分钟前"
        hours < 24L -> "$hours 小时前"
        days < 7L -> "$days 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

/**
 * ROB-10：搜索 producer 兜底——非取消异常降级为空结果（抽屉显示「无匹配会话」），
 * 不逸出 produceState 撕毁组合；CancellationException 穿透 rethrow。
 * 纯逻辑（错误经 [onError] sink 注入，保持零 Android 依赖），JVM 单测覆盖。
 */
internal suspend fun <T> searchCatching(
    query: String,
    onSearch: (String) -> List<T>,
    onError: (Throwable) -> Unit = {},
): List<T> =
    when (val outcome = guardedUi { onSearch(query) }) {
        is UiOutcome.Ok -> outcome.value
        is UiOutcome.Failed -> {
            onError(outcome.error)
            emptyList()
        }
    }
