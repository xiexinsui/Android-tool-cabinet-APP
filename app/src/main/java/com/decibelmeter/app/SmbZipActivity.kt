package com.decibelmeter.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 压缩包在线浏览与解压。
 *
 * 只列不下载：[SmbZip] 读的是包尾的目录表，几 MB 的包和几 GB 的包在这里的打开代价一样。
 * 解压是往**包所在的那个共享目录**里写，重名避让、绝不覆盖，包里的目录结构照建。
 *
 * 单独开一页而不是塞进预览页：预览页的模型是「一条内容 + 左右翻页」，这里是「一张表 + 多选」，
 * 硬套只会两边都别扭。
 */
class SmbZipActivity : ComposeBaseActivity() {

    companion object {
        const val EXTRA_HOST = "zip_host"
        const val EXTRA_HOST_LABEL = "zip_host_label"
        const val EXTRA_PATH = "zip_path"
        const val EXTRA_NAME = "zip_name"
        /** 解出来的落点目录（以 '/' 结尾），就是包所在的那个目录。 */
        const val EXTRA_TARGET_DIR = "zip_target_dir"

        private const val MENU_EXTRACT_ALL = Menu.FIRST + 1

        fun open(context: Context, host: String, hostLabel: String, path: String, name: String,
                 targetDir: String) {
            context.startActivity(Intent(context, SmbZipActivity::class.java)
                    .putExtra(EXTRA_HOST, host)
                    .putExtra(EXTRA_HOST_LABEL, hostLabel)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_TARGET_DIR, targetDir))
        }
    }

    private class ZipRow(val entry: SmbZip.Entry, val childCount: Int)

    private data class Ui(
            val loading: Boolean = true,
            val error: String? = null,
            val rows: List<ZipRow> = emptyList(),
            val prefix: String = "",
            val totalEntries: Int = 0,
            val totalBytes: Long = 0,
            val selected: Set<String> = emptySet(),
    )

    private val ui = MutableStateFlow(Ui())
    private val worker = Executors.newSingleThreadExecutor()
    private val linkFlow = MutableStateFlow<SmbLink?>(null)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var host = ""
    private var path = ""
    private var name = ""
    private var targetDir = ""
    /** 解析出来的全量条目：界面只显示 prefix 底下的那一层，解压要按名字回到这张表里找。 */
    private var entries: List<SmbZip.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        targetDir = intent.getStringExtra(EXTRA_TARGET_DIR) ?: SmbClient.parentOf(path)
        title = name
        if (host.isEmpty() || path.isEmpty()) {
            ui.value = Ui(loading = false, error = getString(R.string.preview_broken))
            return
        }
        read()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        linkFlow.value?.let { SmbHub.release(it.host) }
        linkFlow.value = null
    }

    override fun onCreateComposeOptions(menu: android.view.Menu) {
        menu.add(0, MENU_EXTRACT_ALL, 0, R.string.smb_zip_extract_all)
    }

    override fun onMenuItemSelected(id: Int): Boolean {
        if (id == MENU_EXTRACT_ALL) {
            if (entries.isEmpty()) {
                toast(getString(R.string.smb_zip_not_ready))
            } else {
                extract(entries.filter { !it.directory }.map { it.name })
            }
            return true
        }
        return false
    }

    /** 连上这台机器、读包尾的目录表。和预览页一样：连接与解析都不能在主线程。 */
    private fun read() {
        worker.execute {
            var failure: String? = null
            var built: SmbLink? = null
            var parsed: List<SmbZip.Entry>? = null
            try {
                val link = SmbHub.acquire(this, host,
                        intent.getStringExtra(EXTRA_HOST_LABEL) ?: host)
                built = link
                val range = link.openRange(path)
                try {
                    parsed = SmbZip.open(range).entries
                } finally {
                    runCatching { range.close() }
                }
            } catch (e: SmbNeedCredential) {
                failure = getString(R.string.preview_need_login, host)
            } catch (e: Exception) {
                failure = SmbErrors.describe(this, e)
            }
            val link = built
            val list = parsed
            val error = failure
            runOnUiThread {
                if (isFinishing) {
                    // 页面都没了也要把这一份引用还回去，不然这台机器的连接永远关不掉
                    link?.let { SmbHub.release(it.host) }
                    return@runOnUiThread
                }
                if (list == null) {
                    // 读失败就把这条引用还掉，别让这台机器的连接挂在一个开不了的包上
                    link?.let { SmbHub.release(it.host) }
                    ui.value = Ui(loading = false, error = error)
                    return@runOnUiThread
                }
                linkFlow.value = link
                entries = list.sortedWith(compareBy({ !it.directory }, { it.name }))
                ui.value = Ui(loading = false, totalEntries = list.size,
                        totalBytes = list.filter { !it.directory }.sumOf { it.size })
                showPrefix("")
            }
        }
    }

    /** 按 prefix 重算当前可见行；目录行的计数是「这个前缀底下还有几条」。 */
    private fun showPrefix(prefix: String) {
        val rows = ArrayList<ZipRow>()
        val seen = HashSet<String>()
        for (entry in entries) {
            if (!entry.name.startsWith(prefix)) {
                continue
            }
            val rest = entry.name.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                rows.add(ZipRow(entry, 0))
                continue
            }
            // 更深一层的东西：在这一层折成一个目录行，只列一次
            val folder = prefix + rest.substring(0, slash + 1)
            if (seen.add(folder)) {
                rows.add(ZipRow(SmbZip.Entry(folder, true, 0, 0, 0, 0, 0),
                        entries.count { it.name.startsWith(folder) }))
            }
        }
        val current = ui.value
        ui.value = current.copy(loading = false, error = null, rows = rows, prefix = prefix)
    }

    private fun toggle(entry: SmbZip.Entry) {
        if (entry.directory) {
            showPrefix(entry.name)
            return
        }
        val selected = HashSet(ui.value.selected)
        if (!selected.remove(entry.name)) {
            selected.add(entry.name)
        }
        ui.value = ui.value.copy(selected = selected)
    }

    @Composable
    override fun Content() {
        val state by ui.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            Header(state)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.smb_zip_loading),
                            modifier = Modifier.padding(start = 24.dp))
                }
                state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center) {
                    Text(state.error!!, style = MaterialTheme.typography.bodyMedium)
                }
                state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.smb_zip_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    if (state.prefix.isNotEmpty()) {
                        item {
                            Text(state.prefix + "▾",
                                    modifier = Modifier.fillMaxWidth()
                                            .clickable { showPrefix(parentPrefix(state.prefix)) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(state.rows) { row -> EntryRow(row, state.selected) }
                }
            }
            if (state.selected.isNotEmpty()) {
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.smb_zip_selected, state.selected.size),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { ui.value = ui.value.copy(selected = emptySet()) }) {
                        Text(stringResource(R.string.smb_zip_clear))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { extract(ui.value.selected.toList()) }) {
                        Text(stringResource(R.string.smb_zip_extract_selected))
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(state: Ui) {
        val detail = if (state.loading || state.error != null) {
            ""
        } else {
            getString(R.string.smb_zip_summary, state.totalEntries,
                    FileUtil.humanSize(state.totalBytes)) +
                    " · " + getString(R.string.smb_zip_target,
                    targetDir.trimEnd('/').substringAfterLast('/'))
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun EntryRow(row: ZipRow, selected: Set<String>) {
        val entry = row.entry
        val picked = entry.name in selected
        Row(modifier = Modifier.fillMaxWidth()
                .clickable { toggle(entry) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.displayName + if (entry.directory) "/" else "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                val meta = ArrayList<String>()
                if (entry.directory) {
                    meta.add(getString(R.string.smb_zip_children, row.childCount))
                } else if (entry.size >= 0) {
                    meta.add(FileUtil.humanSize(entry.size))
                }
                if (!entry.directory && entry.modified > 0) {
                    meta.add(dateFormat.format(Date(entry.modified)))
                }
                if (!entry.safe) {
                    meta.add(getString(R.string.smb_zip_unsafe))
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!entry.directory) {
                Box(modifier = Modifier.size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (picked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant)) {
                    if (picked) {
                        Text("✓", color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    /**
     * 起一条解压任务。
     *
     * 走 [TransferHub]：一个包里几百张照片，逐个写共享要几分钟，界面不该被占住——丢进队列
     * 就能退出这一页去干别的，进度在浏览页的传输面板和通知栏里继续看得见。
     */
    private fun extract(names: List<String>) {
        if (names.isEmpty()) {
            toast(getString(R.string.smb_zip_none))
            return
        }
        val wanted = names.toSet()
        val target = targetDir
        val app = applicationContext
        TransferHub.enqueue(this, host, getString(R.string.smb_zip_job, name), done = { result ->
            val summary = result as? SmbZip.Summary ?: return@enqueue
            toast(getString(R.string.smb_zip_done, summary.written, summary.skipped))
            SmbBrowseActivity.markDirty(host, target)
            // 解完回到包所在层的视图：用户多半就是想看看解出来的东西在不在
            if (!isFinishing) {
                showPrefix("")
            }
        }) { progress ->
            // 任务体活在被进程级队列持有的闭包里，所以只递 applicationContext，不递这一页
            val link = SmbHub.acquire(app, host, host)
            try {
                val range = link.openRange(path)
                val zip = SmbZip.open(range)
                try {
                    // 按名字回到新解出来的那份条目表里取：Entry 是纯数据，但读句柄必须是这次的
                    zip.extractTo(link.client, target,
                            zip.entries.filter { it.name in wanted }, progress)
                } finally {
                    zip.close()
                }
            } finally {
                SmbHub.release(link.host)
            }
        }
        ui.value = ui.value.copy(selected = emptySet())
    }

    private fun parentPrefix(prefix: String): String {
        val trimmed = prefix.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "" else "$parent/"
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
