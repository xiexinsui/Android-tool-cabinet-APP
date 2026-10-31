package com.decibelmeter.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条传输任务在界面上的样子。进度与状态都只在主线程改，面板读到的永远是一致的一份。 */
enum class TransferState { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/** 「选目标文件夹」这一流程有哪几种用途：决定横幅文案与确认后的动作。 */
enum class PickKind { UPLOAD, COPY, MOVE }

data class TransferJobView(
        val id: Long,
        val label: String,
        val done: Long = 0,
        val total: Long = -1,
        val state: TransferState = TransferState.QUEUED,
        val message: String? = null,
        /** 平均速率（字节/秒），算不出来是 0 */
        val rate: Long = 0,
        /** 失败的下载可以「从已传字节继续」，其余任务失败只能重来 */
        val retryable: Boolean = false,
)

/**
 * 共享浏览页的界面数据。整体不可变，由 Activity 一次性替换——这样 Compose 不会读到
 * 「列表已经是新目录、状态行还是上一个目录的条数」这种半新半旧的状态。
 */
data class BrowseSnapshot(
        val trail: List<String> = listOf(""),
        val entries: List<SmbClient.Item> = emptyList(),
        val loading: Boolean = false,
        /** 屏上显示的是缓存内容，后台正在悄悄重新取——不置 loading，是为了让翻页立刻有反应 */
        val refreshing: Boolean = false,
        val status: String = "",
        val error: String? = null,
        val grid: Boolean = false,
        val thumbnails: Boolean = true,
        val filter: String = "",
        val searchOpen: Boolean = false,
        val sortLabel: String = "",
        /** 类型筛选当前值（“全部”也显示出来，免得筛着筛着忘了） */
        val kindLabel: String = "",
        val hint: String = "",
        val scrollIndex: Int = 0,
        /** 选择目标文件夹模式（从手机选好文件再进来挑目录）：列表只剩目录，顶部出一条确认横幅 */
        val pickMode: Boolean = false,
        val pickCount: Int = 0,
        val pickKind: PickKind = PickKind.UPLOAD,
        /** 选目标时补一句来源说明（跨机搬运时要说清源在哪台机器上）。 */
        val pickNote: String = "",
        /** 多选模式：长按条目进入，点条目切换，返回键退出 */
        val selecting: Boolean = false,
        val selected: Set<String> = emptySet(),
) {
    val canGoUp: Boolean get() = trail.size > 1

    /** 当前所在目录（空串＝共享列表那一层）。 */
    val at: String get() = trail.lastOrNull() ?: ""
}

/**
 * 浏览页的动作集合。参数已经多到用位置传会读不动，所以收成一个对象；
 * 由 Activity 建一次，屏幕只调不改。
 */
class SmbBrowseActions(
        val jumpCrumb: (Int) -> Unit,
        val toggleView: () -> Unit,
        val openSort: () -> Unit,
        val refresh: () -> Unit,
        val toggleSearch: () -> Unit,
        val filterChange: (String) -> Unit,
        val copyPath: () -> Unit,
        val retry: () -> Unit,
        val scrollChanged: (Int) -> Unit,
        val click: (SmbClient.Item) -> Unit,
        val menu: (SmbClient.Item) -> Unit,
        val cancelJob: (Long) -> Unit,
        val clearJobs: () -> Unit,
        val confirmPick: () -> Unit,
        val toggleSelect: (SmbClient.Item) -> Unit,
        val selectAll: () -> Unit,
        val exitSelection: () -> Unit,
        val downloadSelected: () -> Unit,
        val deleteSelected: () -> Unit,
        val menuOfSelection: () -> Unit,
        val copySelected: () -> Unit,
        val moveSelected: () -> Unit,
        val openKind: () -> Unit,
        val retryJob: (Long) -> Unit,
)

/**
 * 浏览页正文：面包屑 + 工具条 + 状态行 + 列表/网格。
 *
 * 没有用 Compose 的 Scaffold/TopAppBar——标题栏与 options menu 走系统 ActionBar
 * （工程其它页面同样是 `Theme.DeviceDefault` 的 DarkActionBar），两套标题栏叠在一起只会让
 * 人分不清返回键和 overflow 菜单归谁管。
 *
 * 工具条把「过滤 / 排序 / 视图 / 刷新」四件事从 overflow 菜单里提到明面上：这几项是翻共享时
 * 每分钟都要用的，藏在三点菜单里等于每次都要先开菜单再找条目。
 */
@Composable
fun SmbBrowseScreen(state: BrowseSnapshot, jobs: List<TransferJobView>, link: SmbLink?,
                    actions: SmbBrowseActions) {
    val dir = state.at
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (state.pickMode) {
            PickBanner(state, actions)
        }
        CrumbRow(state, actions)
        if (state.selecting) {
            SelectionBar(state, actions)
        } else {
            ToolRow(state, actions)
        }
        if (state.loading && state.entries.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        } else if (state.refreshing) {
            // 缓存命中时的后台刷新用一条细线表示，不遮住内容也不让人以为卡住
            LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp))
        }
        StatusRow(state, actions)
        TransferPanel(jobs, actions)
        if (state.entries.isEmpty() && !state.loading && state.error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.smb_empty_list),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.grid) {
            key(dir) { GridBody(state, link, dir, actions) }
        } else {
            key(dir) { ListBody(state, link, dir, actions) }
        }
    }
}

/** 选目标文件夹时的横幅：说清「选了几个文件」并给一个明确的落点按钮。 */
@Composable
private fun PickBanner(state: BrowseSnapshot, actions: SmbBrowseActions) {
    val textRes = when (state.pickKind) {
        PickKind.UPLOAD -> R.string.smb_pick_banner
        PickKind.COPY -> R.string.smb_pick_banner_copy
        PickKind.MOVE -> R.string.smb_pick_banner_move
    }
    val buttonRes = when (state.pickKind) {
        PickKind.UPLOAD -> R.string.smb_pick_confirm
        PickKind.COPY -> R.string.smb_pick_confirm_copy
        PickKind.MOVE -> R.string.smb_pick_confirm_move
    }
    Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                    text = stringResource(textRes, state.pickCount),
                    style = MaterialTheme.typography.labelLarge,
            )
            if (state.pickNote.isNotEmpty()) {
                Text(state.pickNote, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 共享列表那一层没有可写的落点（目录路径以共享名为第一段），所以到那层就置灰
        Button(onClick = actions.confirmPick, enabled = state.at.isNotEmpty()) {
            Text(stringResource(buttonRes))
        }
    }
}

/**
 * 多选工具条：替代平时的工具条，只留这一屏用得上的动作。
 *
 * 「全选」只选**当前过滤后看得到的**条目——列表里没显示的东西不该被一个「全选」悄悄带走。
 */
@Composable
private fun SelectionBar(state: BrowseSnapshot, actions: SmbBrowseActions) {
    val hasFiles = state.entries.any { it.name in state.selected && !it.directory }
    val any = state.selected.isNotEmpty()
    // 动作一多，一行放不下：计数与「全选/取消」放上面一行，具体动作放下面一行
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    text = stringResource(R.string.smb_selected_count, state.selected.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.selectAll) {
                Text(stringResource(R.string.smb_select_all))
            }
            TextButton(onClick = actions.exitSelection) {
                Text(stringResource(R.string.smb_selected_exit))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.downloadSelected, enabled = hasFiles) {
                Text(stringResource(R.string.smb_selected_download))
            }
            TextButton(onClick = actions.copySelected, enabled = any) {
                Text(stringResource(R.string.smb_selected_copy))
            }
            TextButton(onClick = actions.moveSelected, enabled = any) {
                Text(stringResource(R.string.smb_selected_move))
            }
            TextButton(onClick = actions.deleteSelected, enabled = any) {
                Text(stringResource(R.string.smb_selected_delete))
            }
            // 只选了一项时给回「完整的条目菜单」：目录没有右侧 ⋮，改名之类的动作否则就够不着了
            if (state.selected.size == 1) {
                TextButton(onClick = actions.menuOfSelection) { Text("⋯") }
            }
        }
    }
}

/** 多选的勾选标记：圆形、选中时填充主色。列表与网格共用。 */
@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
            modifier = Modifier.size(20.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary
                    else Color.Transparent)
                    .then(
                            if (selected) Modifier
                            else Modifier.border(1.dp,
                                    MaterialTheme.colorScheme.outline, CircleShape)),
            contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

/** 面包屑：每一级都是可以直接点的圆角块，当前级高亮；长按当前级复制这一层的 UNC 路径。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrumbRow(state: BrowseSnapshot, actions: SmbBrowseActions) {
    val levels = state.trail
    Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in levels.indices) {
            if (index > 0) {
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 2.dp))
            }
            val label = if (levels[index].isEmpty()) stringResource(R.string.smb_root_level)
            else levels[index].trimEnd('/').substringAfterLast('/')
            val last = index == levels.lastIndex
            CrumbChip(label = label, active = last,
                    onClick = { actions.jumpCrumb(index) },
                    onLongClick = if (last) actions.copyPath else ({ }))
        }
    }
}

@Composable
private fun CrumbChip(label: String, active: Boolean, onClick: () -> Unit,
                      onLongClick: () -> Unit) {
    Box(
            modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp))
    }
}

/** 工具条：搜索（就地展开成输入框）+ 排序 + 视图 + 刷新。 */
@Composable
private fun ToolRow(state: BrowseSnapshot, actions: SmbBrowseActions) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.searchOpen) {
            // 展开就抢焦点并弹键盘：点「过滤」的人下一步就是要打字，还要再点一次输入框
            // 属于凭空多出来的一跳
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
            OutlinedTextField(
                    value = state.filter,
                    onValueChange = actions.filterChange,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.smb_filter_title)) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.filter.isNotEmpty()) {
                            IconButton(onClick = { actions.filterChange("") }) {
                                Icon(Icons.Default.Close, stringResource(R.string.smb_filter_clear))
                            }
                        }
                    },
            )
        } else {
            ToolChip(text = stringResource(R.string.smb_menu_filter),
                    onClick = actions.toggleSearch)
            // 类型筛选：257 个片子的目录里「只看视频」比按文件名猜有用得多
            ToolChip(text = state.kindLabel, onClick = actions.openKind)
            Spacer(modifier = Modifier.weight(1f))
        }
        ToolChip(text = state.sortLabel, onClick = actions.openSort)
        ToolChip(
                text = stringResource(if (state.grid) R.string.smb_view_list else R.string.smb_view_grid),
                onClick = actions.toggleView)
        IconButton(onClick = actions.refresh) {
            Icon(Icons.Default.Refresh, stringResource(R.string.smb_menu_refresh))
        }
    }
}

@Composable
private fun ToolChip(text: String, onClick: () -> Unit) {
    Box(
            modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 状态行：条数/剩余空间（或错误 + 重试），右侧给一句操作提示。 */
@Composable
private fun StatusRow(state: BrowseSnapshot, actions: SmbBrowseActions) {
    if (state.error != null) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
            Text(state.error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = actions.retry) {
                Text(stringResource(R.string.smb_retry))
            }
        }
        return
    }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(state.status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.hint.isNotEmpty() && !state.selecting) {
            Text(state.hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListBody(state: BrowseSnapshot, link: SmbLink?, dir: String,
                     actions: SmbBrowseActions) {
    // 外层用 key(dir) 包住：换目录时这一组 remember 全部重建，滚动位置才真的按目录复位
    val listState = remember {
        LazyListState(firstVisibleItemIndex = state.scrollIndex, firstVisibleItemScrollOffset = 0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
                .collect { if (it != 0 || state.scrollIndex == 0) actions.scrollChanged(it) }
    }
    LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
    ) {
        items(state.entries) { item ->
            ListRow(link, dir, item, state.thumbnails,
                    selecting = state.selecting,
                    selected = item.name in state.selected,
                    onClick = { actions.click(item) },
                    onLongPress = { actions.toggleSelect(item) },
                    onMenu = { actions.menu(item) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun GridBody(state: BrowseSnapshot, link: SmbLink?, dir: String,
                     actions: SmbBrowseActions) {
    val gridState = remember {
        // 网格的起始位置只按「第几项」复位，行/偏移两个维度用默认值
        LazyGridState(firstVisibleItemIndex = state.scrollIndex)
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }
                .collect { if (it != 0 || state.scrollIndex == 0) actions.scrollChanged(it) }
    }
    LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.entries) { item ->
            GridCell(link, dir, item, state.thumbnails,
                    selecting = state.selecting,
                    selected = item.name in state.selected,
                    onClick = { actions.click(item) },
                    onLongPress = { actions.toggleSelect(item) })
        }
    }
}

/**
 * 传输面板：同时看得到几条任务的进度，各自能取消，跑完的留在原位等「清除」。
 *
 * 换成队列之后这里刻意不遮挡内容、也不阻断浏览：拷几百兆的同时翻目录、看预览、再丢几个
 * 文件进队列，都是正常用法。最多显示 4 行，其余折成「还有 N 项」——面板不该把列表挤没。
 */
@Composable
private fun TransferPanel(jobs: List<TransferJobView>, actions: SmbBrowseActions) {
    if (jobs.isEmpty()) {
        return
    }
    val active = jobs.count {
        it.state == TransferState.RUNNING || it.state == TransferState.QUEUED
    }
    val finished = jobs.size - active
    Column(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    text = if (active > 0) stringResource(R.string.smb_jobs_running, active)
                    else stringResource(R.string.smb_jobs_recent),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
            )
            if (finished > 0) {
                TextButton(onClick = actions.clearJobs) {
                    Text(stringResource(R.string.smb_jobs_clear))
                }
            }
        }
        for (job in jobs.take(PANEL_ROWS)) {
            JobRow(job, actions)
        }
        if (jobs.size > PANEL_ROWS) {
            Text(stringResource(R.string.smb_jobs_more, jobs.size - PANEL_ROWS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val PANEL_ROWS = 4

@Composable
private fun JobRow(job: TransferJobView, actions: SmbBrowseActions) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(job.label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            when (job.state) {
                TransferState.RUNNING -> {
                    val rate = if (job.rate > 0) {
                        " · " + stringResource(R.string.smb_rate_format, FileUtil.humanSize(job.rate))
                    } else {
                        ""
                    }
                    if (job.total > 0) {
                        LinearProgressIndicator(
                                progress = { (job.done.toFloat() / job.total.toFloat())
                                        .coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                        Text(stringResource(R.string.smb_progress_format,
                                FileUtil.humanSize(job.done), FileUtil.humanSize(job.total)) + rate,
                                style = MaterialTheme.typography.labelSmall)
                    } else {
                        // 问不出总长（共享不报配额）时切成不确定模式，至少让人知道还在动
                        LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                        Text(stringResource(R.string.smb_progress_bytes_format,
                                FileUtil.humanSize(job.done)) + rate,
                                style = MaterialTheme.typography.labelSmall)
                    }
                }
                TransferState.QUEUED -> Text(stringResource(R.string.smb_job_queued),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                TransferState.DONE -> Text(
                        text = if (job.rate > 0) {
                            stringResource(R.string.smb_job_done) + " · " +
                                    stringResource(R.string.smb_rate_format,
                                            FileUtil.humanSize(job.rate))
                        } else {
                            stringResource(R.string.smb_job_done)
                        },
                        style = MaterialTheme.typography.labelSmall)
                TransferState.CANCELED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.smb_job_canceled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                    if (job.retryable) {
                        TextButton(onClick = { actions.retryJob(job.id) }) {
                            Text(stringResource(R.string.smb_retry))
                        }
                    }
                }
                TransferState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                            text = job.message ?: stringResource(R.string.smb_job_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                    if (job.retryable) {
                        TextButton(onClick = { actions.retryJob(job.id) }) {
                            Text(stringResource(R.string.smb_retry))
                        }
                    }
                }
            }
        }
        if (job.state == TransferState.RUNNING || job.state == TransferState.QUEUED) {
            TextButton(onClick = { actions.cancelJob(job.id) }) {
                Text(stringResource(R.string.smb_transfer_cancel))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(link: SmbLink?, dir: String, item: SmbClient.Item, thumbnails: Boolean,
                    selecting: Boolean, selected: Boolean,
                    onClick: () -> Unit, onLongPress: () -> Unit, onMenu: () -> Unit) {
    val format = rememberDateFormat()
    val dirLabel = stringResource(R.string.smb_dir)
    val hiddenLabel = stringResource(R.string.smb_hidden_flag)
    Row(
            modifier = Modifier.fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer
                            .copy(alpha = 0.45f) else Color.Transparent)
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                    .padding(start = 4.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectionDot(selected)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (item.directory) {
            // 目录也占一个和文件角标同样大小的块，列表左边缘才不会一格深一格浅
            Box(modifier = Modifier.size(44.dp, 30.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                Text("▸", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall)
            }
        } else if (thumbnails) {
            EntryVisual(link, dir, item, 44.dp, 6.dp, false)
        } else {
            TypeBadge(item.name)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            Text(metaOf(item, format, dirLabel, hiddenLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 长按能做的事，明面上也要有一个入口：手机上没人知道可以长按。
        // 多选时去掉它——这一行现在整行都是「勾选」的点击区
        if (!item.directory && !selecting) {
            IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.smb_action_more),
                        modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(link: SmbLink?, dir: String, item: SmbClient.Item, thumbnails: Boolean,
                     selecting: Boolean, selected: Boolean,
                     onClick: () -> Unit, onLongPress: () -> Unit) {
    Column(
            modifier = Modifier.fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                    .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(104.dp, 78.dp)) {
            Box(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(
                                    if (selected) Modifier.border(2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(8.dp))
                                    else Modifier),
                    contentAlignment = Alignment.Center,
            ) {
                if (item.directory) {
                    Text("▸", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                } else if (thumbnails) {
                    EntryVisual(link, dir, item, 104.dp, 8.dp, true)
                } else {
                    TypeBadge(item.name)
                }
            }
            if (selecting) {
                // 勾选圆点压在缩略图右上角，缩略图仍能看全
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    SelectionDot(selected)
                }
            }
        }
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 一个文件的视觉：图片交给 Coil，视频首帧交给 [SmbVideoThumbs]，其它显示类型角标。
 *
 * 两条加载路径分开是有原因的：图片可以边下边解（Coil 自己管内存与降采样），视频必须先随机
 * 定位到索引表才能抽帧，只能走回环 HTTP + [android.media.MediaMetadataRetriever]。
 */
@Composable
private fun EntryVisual(link: SmbLink?, dir: String, item: SmbClient.Item,
                        width: Dp, radius: Dp, fill: Boolean) {
    val context = LocalContext.current
    if (link == null) {
        TypeBadge(item.name)
        return
    }
    val path = SmbClient.child(dir, item.name, false)
    val boxHeight = if (fill) 78.dp else width
    val box = Modifier.size(width, boxHeight).clip(RoundedCornerShape(radius))
    val model = SmbUris.forFile(link, path, item.size, item.modified, SmbUris.MODE_THUMB)
    when (SmbKind.of(item.name)) {
        SmbKind.IMAGE -> AsyncImage(
                model = model,
                contentDescription = null,
                imageLoader = ToolboxImages.loader(context),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = box,
        )
        SmbKind.VIDEO -> {
            val frame by produceState<Bitmap?>(initialValue = null, model) {
                // 停稳 250 ms 再抽帧：滚列表时每个视频都起一次回环代理，代价太大；
                // 划走的条目会随 produceState 一起被取消，帧也就不会白抽
                delay(250)
                SmbVideoThumbs.request(context, link, path, item.name, model) { value = it }
            }
            val bitmap = frame
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                        contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                        modifier = box)
            } else {
                TypeBadge(item.name)
            }
        }
        SmbKind.PDF -> {
            val page by produceState<Bitmap?>(initialValue = null, model) {
                delay(250)
                SmbPdfThumbs.request(context, link, path, model) { value = it }
            }
            val bitmap = page
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                        contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                        modifier = box)
            } else {
                TypeBadge(item.name)
            }
        }
        else -> TypeBadge(item.name)
    }
}

/** 解不了的内容也要看得出「这是个什么」：用扩展名当角标，比一个通用文件图标信息量大。 */
@Composable
private fun TypeBadge(name: String) {
    val dot = name.lastIndexOf('.')
    val ext = if (dot < 0) "" else name.substring(dot + 1)
    val label = ext.uppercase(Locale.US).take(4).ifEmpty { "•" }
    Box(modifier = Modifier.size(44.dp, 30.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * 元数据行：目录／大小 · 时间 · 隐藏。取不到的项一律不显示，而不是编一个 0 出来。
 *
 * 文案与日期格式都由调用方（组合作用域）传进来：`SimpleDateFormat` 不能放成进程级常量，
 * 本应用允许在运行时切换语言（`LocaleHelper` + `recreate()`），常量会带着切换前的 Locale
 * 一直显示错误格式的日期——这正是 lint 的 ConstantLocale 在提醒的事。
 */
private fun metaOf(item: SmbClient.Item, format: SimpleDateFormat,
                   dirLabel: String, hiddenLabel: String): String {
    val parts = ArrayList<String>()
    if (item.directory) {
        parts.add(dirLabel)
    } else if (item.size >= 0) {
        parts.add(FileUtil.humanSize(item.size))
    }
    if (item.modified > 0) {
        parts.add(format.format(Date(item.modified)))
    }
    if (item.hidden) {
        parts.add(hiddenLabel)
    }
    return parts.joinToString(" · ")
}

/**
 * 列表里的日期格式。以 [LocalContext] 为 key：语言切换会重建 Activity 并换来一个新 Context，
 * 这时必须重新建一个 `SimpleDateFormat`，不然日期会继续按切换前的地区排。
 */
@Composable
private fun rememberDateFormat(): SimpleDateFormat {
    val context = LocalContext.current
    return remember(context) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
