package com.decibelmeter.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.TrackSelectionOverride
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/** 预览页的界面状态。 */
data class PreviewUi(
        val error: String? = null,
        val busy: Boolean = false,
)

/**
 * 共享文件的预览页：图片、视频/音频、PDF、文本，四种内容左右翻页。
 *
 * 只有一种进入方式——从 [SmbBrowseActivity] 点一个能预览的文件。浏览页会把**当前目录里所有
 * 能预览的项**一起打包传过来（[EXTRA_ITEMS]），这样翻页不必再去列一次目录，也不会因为目录在
 * 预览期间被别人改动而翻到一半失效。
 *
 * 连接走 [SmbHub]：浏览页那条已登录的直接复用（引用计数 +1），所以不会二次弹登录框；万一
 * 浏览页已经退出，这里会按保存的凭据重新登录一次。
 */
class SmbPreviewActivity : ComposeBaseActivity() {

    companion object {
        const val EXTRA_HOST = "preview_host"
        const val EXTRA_HOST_LABEL = "preview_host_label"
        const val EXTRA_INDEX = "preview_index"
        const val EXTRA_ITEMS = "preview_items"

        /** 一条待预览项的字段分隔符：不可打印字符，路径与文件名里都不可能出现。 */
        const val SEP = "\u0001"

        private const val MENU_EXTERNAL = Menu.FIRST + 1
        private const val MENU_DOWNLOAD = Menu.FIRST + 2
        private const val MENU_COPY = Menu.FIRST + 3
        private const val MENU_OTHER_PLAYER = Menu.FIRST + 4
        private const val MENU_SPEED = Menu.FIRST + 5
        private const val MENU_SUBTITLE = Menu.FIRST + 6
        private const val MENU_ROTATE = Menu.FIRST + 7
    }

    private val ui = MutableStateFlow(PreviewUi())
    private val worker = Executors.newSingleThreadExecutor()
    /** 连接本身是状态：发布到 StateFlow 里，界面才会在「连上了」那一刻重组成可播放的样子 */
    private val linkFlow = MutableStateFlow<SmbLink?>(null)
    private var items: List<PreviewItem> = emptyList()
    private var startIndex = 0
    private var currentPage = 0
    private var host: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        items = (intent.getStringArrayListExtra(EXTRA_ITEMS) ?: ArrayList<String>())
                .mapNotNull { decodeEntry(it) }
        if (host.isEmpty() || items.isEmpty()) {
            ui.value = PreviewUi(error = getString(R.string.preview_broken))
            return
        }
        title = items[startIndex.coerceIn(0, items.size - 1)].name
        // 记进「最近打开」：下载页那个入口靠它把用户带回刚才看的东西
        val first = items[startIndex.coerceIn(0, items.size - 1)]
        RecentFiles.record(this, host, first.path, first.name)
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        linkFlow.value?.let { SmbHub.release(it.host) }
        linkFlow.value = null
    }

    /** 建立或复用这条连接。必须在后台做，理由同浏览页（jcifs 建上下文会走网络）。 */
    private fun connect() {
        worker.execute {
            var failure: String? = null
            var built: SmbLink? = null
            try {
                built = SmbHub.acquire(this, host, intent.getStringExtra(EXTRA_HOST_LABEL) ?: host)
            } catch (e: SmbNeedCredential) {
                failure = getString(R.string.preview_need_login, host)
            } catch (e: Exception) {
                failure = SmbErrors.describe(this, e)
            }
            val result = built
            val error = failure
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                linkFlow.value = result
                ui.value = PreviewUi(error = error)
            }
        }
    }

    private fun decodeEntry(raw: String): PreviewItem? {
        val parts = raw.split(SEP)
        if (parts.size < 4) {
            return null
        }
        return PreviewItem(parts[0], parts[1],
                parts[2].toLongOrNull() ?: -1L, parts[3].toLongOrNull() ?: 0L)
    }

    private fun item(): PreviewItem? = items.getOrNull(currentPage)

    // ---------------------------------------------------------------- 界面

    @Composable
    override fun Content() {
        val state by ui.collectAsState()
        // link 必须从 StateFlow 里读：它原来是普通字段，而连接成功的那个赋值与 ui 的初始值相等，
        // StateFlow 去重之后不会触发重组，页面会永远停在「正在准备…」（真机上踩了很久）
        val link by linkFlow.collectAsState()
        if (state.error != null) {
            PreviewErrorPane(state.error!!)
            return
        }
        if (items.isEmpty()) {
            return
        }
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                initialPage = startIndex.coerceIn(0, items.size - 1),
                pageCount = { items.size },
        )
        // 标题跟着当前页走：翻到哪一个就是哪一个的文件名，用户从标题栏就能确认没翻错
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { page ->
                currentPage = page
                items.getOrNull(page)?.let { title = it.name }
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        ) { page ->
            val entry = items[page]
            SmbPreviewPage(entry, link, active = pagerState.settledPage == page,
                    onExternalPlay = ::openInExternalPlayer)
        }
    }

    // ---------------------------------------------------------------- 菜单

    override fun onCreateComposeOptions(menu: Menu) {
        menu.add(0, MENU_EXTERNAL, 0, R.string.smb_action_open)
        menu.add(0, MENU_OTHER_PLAYER, 0, R.string.smb_action_open_other_player)
        menu.add(0, MENU_SPEED, 0, R.string.preview_menu_speed)
        menu.add(0, MENU_SUBTITLE, 0, R.string.preview_menu_subtitle)
        menu.add(0, MENU_ROTATE, 0, R.string.preview_menu_rotate)
        menu.add(0, MENU_DOWNLOAD, 0, R.string.smb_action_download)
        menu.add(0, MENU_COPY, 0, R.string.smb_copy_path)
    }

    override fun onMenuItemSelected(id: Int): Boolean {
        val entry = item() ?: return false
        when (id) {
            MENU_EXTERNAL -> dispatch(entry, keepOnly = false)
            MENU_OTHER_PLAYER -> openInExternalPlayer()
            MENU_SPEED -> showSpeedDialog()
            MENU_SUBTITLE -> showSubtitleDialog()
            MENU_ROTATE -> toggleOrientation()
            MENU_DOWNLOAD -> dispatch(entry, keepOnly = true)
            MENU_COPY -> copyText(uncOf(entry))
            else -> return false
        }
        return true
    }

    private fun uncOf(entry: PreviewItem): String {
        val builder = StringBuilder("\\\\").append(host)
        for (segment in entry.path.split('/')) {
            if (segment.isNotEmpty()) {
                builder.append('\\').append(segment)
            }
        }
        return builder.toString()
    }

    /**
     * 取到本地再交给外部应用（或只保存）。
     *
     * 预览页留这个出口是必需的：Media3 播不了 wmv/avi、PdfRenderer 遇到加了密或损坏的 PDF
     * 也会失败，这时候唯一有用的动作就是「拿回去用别的应用开」。
     */
    /** 倍速：0.5×~2×。选过的值记在 [PlaybackBridge] 上，翻页重建播放器也跟着走。 */
    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { if (it == 1f) "1×" else "${it}×" }
        val checked = speeds.indexOfFirst { it == PlaybackBridge.speed }.coerceAtLeast(2)
        AlertDialog.Builder(this)
                .setTitle(R.string.preview_menu_speed)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    PlaybackBridge.setSpeed(speeds[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * 字幕轨选择。
     *
     * 轨道在 `player.currentTracks` 里：容器内嵌的字幕（MKV 常见）和播放页挂在 MediaItem 上的
     * 同名外挂字幕都在这里。选择靠 `trackSelectionParameters` 的 override，而不是另建
     * SubtitleView——PlayerView 自带字幕层，改选择就够了。
     */
    @OptIn(UnstableApi::class)
    private fun showSubtitleDialog() {
        val player = PlaybackBridge.player
        if (player == null) {
            toast(getString(R.string.preview_no_subtitles))
            return
        }
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        if (groups.isEmpty()) {
            toast(getString(R.string.preview_no_subtitles))
            return
        }
        val labels = ArrayList<String>()
        labels.add(getString(R.string.preview_subtitle_off))
        for (group in groups) {
            val format = group.getTrackFormat(0)
            labels.add(format.label ?: format.language ?: getString(R.string.preview_subtitle_track,
                    labels.size))
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.preview_menu_subtitle)
                .setItems(labels.toTypedArray()) { _, which ->
                    val params = player.trackSelectionParameters.buildUpon()
                    if (which == 0) {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    } else {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        params.setOverrideForType(TrackSelectionOverride(
                                groups[which - 1].mediaTrackGroup, listOf(0)))
                    }
                    player.trackSelectionParameters = params.build()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /** 横屏/竖屏切换：清单里声明了 configChanges，转屏不会重建页面，播放不中断。 */
    private fun toggleOrientation() {
        requestedOrientation =
                if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
    }

    /**
     * 把当前音视频以本机 HTTP 流交给别的播放器（VLC / MX / 系统播放器…）。
     *
     * 为什么必须走 [SmbStreamService] 而不是页面里那个代理：外部播放器拿到的只是一个
     * `http://127.0.0.1:端口/…` 地址，数据还得由本进程读 SMB。页面一关、代理一停，
     * 播放器下次 seek 就会被拒；服务则带着通知活着，用户随时能停（也支持空闲自动收摊）。
     */
    private fun openInExternalPlayer() {
        val entry = item() ?: return
        if (host.isEmpty()) {
            return
        }
        if (entry.kind != SmbKind.VIDEO && entry.kind != SmbKind.AUDIO) {
            toast(getString(R.string.smb_stream_media_only))
            return
        }
        SmbExternalPlay.play(this, host, entry.path, entry.name)
    }

    /**
     * 先整份落到本机，再决定是「留在手机里」还是「交给别的应用打开」。
     *
     * 走 [TransferHub] 而不是本页的 worker：一部 2.6 G 的电影要传一分多钟，用户在等的过程中
     * 会退出预览页去看别的目录，挂在页面上的任务会跟着一起没了，也看不到进度。队列是进程级的，
     * 通知栏与「已下载」页里那条未完成的记录都靠它。
     */
    private fun dispatch(entry: PreviewItem, keepOnly: Boolean) {
        val client = linkFlow.value?.client
        if (client == null) {
            toast(getString(R.string.preview_need_login, host))
            return
        }
        if (ui.value.busy) {
            toast(getString(R.string.smb_transfer_running))
            return
        }
        val sink = try {
            DownloadSink.create(this, entry.name)
        } catch (e: Exception) {
            toast(SmbErrors.describe(this, e))
            return
        }
        ui.value = ui.value.copy(busy = true)
        toast(getString(R.string.preview_preparing, entry.name))
        TransferHub.enqueueDownload(this, host,
                getString(R.string.smb_downloading_format, entry.name),
                TransferHub.DownloadSpec(client, entry.path, sink), done = { result ->
                    ui.value = ui.value.copy(busy = false)
                    if (result == null) {
                        // 失败或取消：半截文件留着，传输面板上有「重试」，这里不再补一句提示
                        return@enqueueDownload
                    }
                    val size = sink.size()
                    val text = if (size >= 0) {
                        getString(R.string.smb_download_done_format, sink.label,
                                FileUtil.humanSize(size))
                    } else {
                        sink.label
                    }
                    if (keepOnly) {
                        toast("$text · ${sink.location}")
                    } else if (FileShare.openUri(this, sink.contentUri(this),
                                    FileUtil.mimeOf(sink.label)) == FileShare.NO_APP) {
                        toast(getString(R.string.share_no_app, sink.label))
                    }
                })
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("smb", text))
        toast(getString(R.string.dns_copied, text))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

/** 连接没建起来时的整页说明——预览页没有可操作的内容，报错就直接占满页面。 */
@Composable
private fun PreviewErrorPane(message: String) {
    androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
    ) {
        Text(text = message, modifier = Modifier.padding(24.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

