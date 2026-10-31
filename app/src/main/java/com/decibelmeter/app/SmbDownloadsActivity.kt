package com.decibelmeter.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 已下载与最近打开。
 *
 * 「下载」列的是公共 `下载/工具箱` 目录里的文件（Android 10 以下退回应用私有下载目录）；
 * 「最近打开」来自 [RecentFiles]，点一条直接回到预览页继续看。
 *
 * 为什么单独开一页而不是塞进浏览页：这两份数据的来源都跟「当前连的是哪台机器」无关，
 * 而浏览页的每个入口都是「一台机器 + 一个目录」。
 */
class SmbDownloadsActivity : ComposeBaseActivity() {

    private class DownloadItem(val name: String, val size: Long, val time: Long, val uri: Uri)

    private data class Ui(
            val loading: Boolean = true,
            val recent: List<RecentFiles.Entry> = emptyList(),
            val downloads: List<DownloadItem> = emptyList(),
            val unfinished: List<UnfinishedDownload> = emptyList(),
    )

    private val state = MutableStateFlow(Ui())
    private val worker = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.smb_downloads_title)
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    override fun onMenuItemSelected(id: Int): Boolean {
        if (id == MENU_CLEAR_RECENT) {
            RecentFiles.clear(this)
            load()
            return true
        }
        return false
    }

    override fun onCreateComposeOptions(menu: android.view.Menu) {
        menu.add(0, MENU_CLEAR_RECENT, 0, R.string.smb_downloads_clear_recent)
    }

    private fun load() {
        state.value = state.value.copy(loading = true)
        // 正在跑的任务也有存档（那是给进程被杀准备的），不能把它们列进「未完成」
        val running = TransferHub.runningKeys()
        worker.execute {
            val downloads = try {
                queryDownloads()
            } catch (e: Exception) {
                emptyList()
            }
            val recent = RecentFiles.list(this)
            val unfinished = UnfinishedStore.list(this).filter { record ->
                if (record.key in running) {
                    return@filter false
                }
                // 落点被用户删掉的记录留着也没用，顺手清掉
                if (DownloadSink.adopt(this, record.token, record.label) == null) {
                    UnfinishedStore.remove(this, record.key)
                    false
                } else {
                    true
                }
            }
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                state.value = Ui(loading = false, recent = recent,
                        downloads = downloads, unfinished = unfinished)
            }
        }
    }

    private fun queryDownloads(): List<DownloadItem> {
        val result = ArrayList<DownloadItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED)
            val selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            contentResolver.query(collection, projection, selection,
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + DownloadSink.PUBLIC_FOLDER + "/%"),
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val item = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    result.add(DownloadItem(cursor.getString(1), cursor.getLong(2),
                            cursor.getLong(3) * 1000, item))
                }
            }
            return result
        }
        // Android 10 以下下载落在应用私有目录
        val dir: File? = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            result.add(DownloadItem(file.name, file.length(), file.lastModified(),
                    LocalFileProvider.uriFor(this, file)))
        }
        return result
    }

    @Composable
    override fun Content() {
        val ui by state.collectAsState()
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (ui.unfinished.isNotEmpty()) {
                    item { SectionTitle(R.string.smb_downloads_unfinished) }
                    items(ui.unfinished, key = { it.key }) { record ->
                        UnfinishedRow(record)
                        HorizontalDivider()
                    }
                }
                if (ui.recent.isNotEmpty()) {
                    item { SectionTitle(R.string.smb_downloads_recent) }
                    items(ui.recent) { entry ->
                        Row(Modifier.fillMaxWidth()
                                .clickable { openRecent(entry) }
                                .padding(vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge)
                                Text(entry.host + " " + entry.path,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                item { SectionTitle(R.string.smb_downloads_files) }
                if (ui.downloads.isEmpty() && !ui.loading) {
                    item {
                        Text(stringResource(R.string.smb_downloads_empty),
                                modifier = Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(ui.downloads) { entry ->
                    DownloadRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }

    @Composable
    private fun UnfinishedRow(record: UnfinishedDownload) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                val done = FileUtil.humanSize(record.doneBytes)
                val total = if (record.total > 0) FileUtil.humanSize(record.total) else "?"
                Text("$done / $total", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { resumeDownload(record) }) {
                Text(stringResource(R.string.smb_downloads_resume))
            }
            TextButton(onClick = { confirmAbandon(record) }) {
                Text(stringResource(R.string.smb_downloads_abandon))
            }
        }
    }

    /** 在工作线程上把「续传要用的东西」备齐：查落点在不在、要不要重新登录这台机器。 */
    private class ResumePlan(val link: SmbLink?, val sink: DownloadSink?, val error: String?)

    private fun resumeDownload(record: UnfinishedDownload) {
        // adopt 与 acquire 都可能走网络（新建一条 SMB 连接是要解析主机的），一律放工作线程；
        // 入队必须回主线程，任务表是主线程独占的
        worker.execute {
            val plan = try {
                val sink = DownloadSink.adopt(this, record.token, record.label)
                if (sink == null) {
                    UnfinishedStore.remove(this, record.key)
                    ResumePlan(null, null, getString(R.string.smb_resume_missing))
                } else {
                    ResumePlan(SmbHub.acquire(this, record.host, record.host), sink, null)
                }
            } catch (e: Exception) {
                ResumePlan(null, null, SmbErrors.describe(this, e))
            }
            runOnUiThread {
                if (isFinishing) {
                    plan.link?.let { SmbHub.release(it.host) }
                    return@runOnUiThread
                }
                val link = plan.link
                if (link == null || plan.sink == null) {
                    Toast.makeText(this, plan.error, Toast.LENGTH_LONG).show()
                    load()
                    return@runOnUiThread
                }
                TransferHub.resume(this, record, plan.sink, link.client)
                // 入队时队列自己为这台机器加过一次引用，这里还掉我们多拿的那一份
                SmbHub.release(link.host)
                Toast.makeText(this, R.string.smb_downloads_resume_queued,
                        Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    private fun confirmAbandon(record: UnfinishedDownload) {
        AlertDialog.Builder(this)
                .setMessage(getString(R.string.smb_downloads_abandon_confirm, record.label))
                .setPositiveButton(R.string.smb_downloads_abandon) { _, _ -> abandon(record) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun abandon(record: UnfinishedDownload) {
        worker.execute {
            DownloadSink.adopt(this, record.token, record.label)?.discard()
            UnfinishedStore.remove(this, record.key)
            runOnUiThread {
                if (!isFinishing) {
                    load()
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DownloadRow(entry: DownloadItem) {
        Row(Modifier.fillMaxWidth()
                .combinedClickable(
                        onClick = { openDownload(entry) },
                        onLongClick = { showDownloadMenu(entry) })
                .padding(vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                val size = if (entry.size >= 0) FileUtil.humanSize(entry.size) else ""
                val time = if (entry.time > 0) dateFormat.format(Date(entry.time)) else ""
                Text(listOf(size, time).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /** 长按一条已下载的文件：分享、复制路径、删除。 */
    private fun showDownloadMenu(entry: DownloadItem) {
        val labels = arrayOf(getString(R.string.share_chooser_send),
                getString(R.string.smb_copy_path),
                getString(R.string.smb_action_delete))
        AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> FileShare.sendUri(this, entry.uri, FileUtil.mimeOf(entry.name))
                        1 -> copyPath(entry)
                        2 -> confirmDelete(entry)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun copyPath(entry: DownloadItem) {
        val path = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DownloadSink.PUBLIC_FOLDER + "/" + entry.name)
                .absolutePath
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, path))
        Toast.makeText(this, R.string.smb_copy_path_done, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(entry: DownloadItem) {
        AlertDialog.Builder(this)
                .setMessage(getString(R.string.smb_downloads_delete_confirm, entry.name))
                .setPositiveButton(R.string.smb_action_delete) { _, _ -> deleteDownload(entry) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun deleteDownload(entry: DownloadItem) {
        worker.execute {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.delete(entry.uri, null, null) > 0
                } else {
                    File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                            DownloadSink.PUBLIC_FOLDER + "/" + entry.name).delete()
                }
            } catch (e: SecurityException) {
                // 属于别的应用创建的下载项时系统会拒绝，界面如实说一声
                false
            }
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                Toast.makeText(this,
                        if (ok) getString(R.string.smb_deleted, entry.name)
                        else getString(R.string.smb_downloads_delete_failed),
                        Toast.LENGTH_SHORT).show()
                load()
            }
        }
    }

    @Composable
    private fun SectionTitle(res: Int) {
        Text(stringResource(res), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
    }

    private fun openDownload(entry: DownloadItem) {
        val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(entry.uri, FileUtil.mimeOf(entry.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                    .setMessage(R.string.smb_open_after_message)
                    .setPositiveButton(R.string.share_chooser_send) { _, _ ->
                        FileShare.sendUri(this, entry.uri, FileUtil.mimeOf(entry.name))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
        }
    }

    private fun openRecent(entry: RecentFiles.Entry) {
        // 预览页只带这一条：它的目录可能早就不是用户当前停留的地方了
        val packed = entry.path + SmbPreviewActivity.SEP + entry.name +
                SmbPreviewActivity.SEP + "-1" + SmbPreviewActivity.SEP + "0"
        startActivity(Intent(this, SmbPreviewActivity::class.java)
                .putExtra(SmbPreviewActivity.EXTRA_HOST, entry.host)
                .putExtra(SmbPreviewActivity.EXTRA_HOST_LABEL, entry.host)
                .putExtra(SmbPreviewActivity.EXTRA_INDEX, 0)
                .putStringArrayListExtra(SmbPreviewActivity.EXTRA_ITEMS, arrayListOf(packed)))
    }

    private companion object {
        const val MENU_CLEAR_RECENT = android.view.Menu.FIRST + 1
    }
}
