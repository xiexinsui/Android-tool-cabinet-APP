package com.decibelmeter.app

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.addCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 共享文件夹：浏览一台机器的 SMB 共享，列目录、看缩略图、在应用内预览，也能把文件放回去。
 *
 * 入口有两个：局域网扫描页里对着某台设备选「查看共享文件」（带主机地址进来），或从主页直接进
 * （弹选择框，列出最近连过的主机或手输地址）。凭据一律由 [CredentialStore] 按主机取，不从
 * Intent 里带明文密码。类名与 `EXTRA_HOST_IP`/`EXTRA_HOST_NAME` 两个键沿用旧实现，
 * [LanScanActivity] 的调用点不必跟着改。
 *
 * 页面自己不做协议的事：列目录/改名这类元数据走 [SmbClient]（jcifs-ng），读文件字节走
 * [SmbRange]（引擎可选），两者都挂在 [SmbHub] 按主机复用的同一条登录上。界面只维护一条
 * `trail` 历史栈和一份不可变快照 [BrowseSnapshot]，异步结果一次性换掉整份快照。
 *
 * 两条独立的后台队列：元数据走 `metadata`，传输走进程级的 [TransferHub]。分开是因为一次几百兆的
 * 拷贝不该把用户的「点进下一个目录」堵死；传输放在页面之外，是因为 1.7 GB 的电影不该要求用户
 * 一直停在浏览页上——那是 `SmbTransferService` 与 `TransferHub` 存在的理由。
 */
class SmbBrowseActivity : ComposeBaseActivity() {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_HOST_NAME = "host_name"

        /** 选目标文件夹模式：先带着一批要上传的文件进来挑目录（见 SmbSendActivity） */
        const val EXTRA_PICK = "pick_mode"

        /** 待上传文件的 content:// URI（字符串数组，跨进程传 Uri 对象在部分机型上不可靠） */
        const val EXTRA_INCOMING = "pick_incoming"

        private const val REQ_UPLOAD = 1
        private const val REQ_SAVE_AS = 2
        private const val REQ_PICK_FILES = 3
        private const val REQ_NOTIFY = 4
        private const val REQ_BACKUP = 5

        private const val MENU_UPLOAD = Menu.FIRST + 1
        private const val MENU_FOLDER = Menu.FIRST + 2
        private const val MENU_REFRESH = Menu.FIRST + 3
        private const val MENU_FILTER = Menu.FIRST + 4
        private const val MENU_HIDDEN = Menu.FIRST + 5
        private const val MENU_SORT = Menu.FIRST + 6
        private const val MENU_VIEW = Menu.FIRST + 7
        private const val MENU_THUMBS = Menu.FIRST + 8
        private const val MENU_SETTINGS = Menu.FIRST + 9
        private const val MENU_CACHE = Menu.FIRST + 10
        private const val MENU_BOOKMARK = Menu.FIRST + 11
        private const val MENU_BOOKMARKS = Menu.FIRST + 12
        private const val MENU_UPLOAD_PICK = Menu.FIRST + 13
        private const val MENU_SEARCH = Menu.FIRST + 14
        private const val MENU_BACKUP = Menu.FIRST + 15
        private const val MENU_TARGET_HOST = Menu.FIRST + 16

        /**
         * 被别的页面改过的目录（目前是解压页写完想让用户一眼看到结果）。
         *
         * 为什么不直接让那一页回调过来：任务是丢进 [TransferHub] 后台跑的，用户很可能在解压
         * 完成之前就退回浏览页了，「谁发起谁通知」在这种情况下必然漏。记一个「脏」标记，
         * 回到这一层时自己重读一次最省事也最准。
         */
        private val dirty = java.util.Collections.synchronizedSet(HashSet<String>())

        /** 给别的页面（目前是解压页）标记「这个目录内容变了，回到浏览页时重读一次」。 */
        @JvmStatic
        fun markDirty(host: String, folder: String) {
            dirty.add("$host|$folder")
        }

        private fun takeDirty(host: String, folder: String): Boolean = dirty.remove("$host|$folder")

        /** 搜索的三道闸：目录数、条目数、命中数。SMB 没有搜索接口，只能逐目录扫。 */
        private const val SEARCH_MAX_DIRS = 400
        private const val SEARCH_MAX_ENTRIES = 20000
        private const val SEARCH_MAX_HITS = 50

        /** 缓存多少层目录：够覆盖「进三层、退回去看别的、再进来」的深度，又不会攒一堆没用的大目录。 */
        private const val LISTING_MAX = 14

        /** 新鲜期（毫秒）：期内命中缓存直接上屏且不发任何请求，翻回来是瞬时的。 */
        private const val FRESH_MS = 60_000L

    }

    private val snapshot = MutableStateFlow(BrowseSnapshot())
    private val metadata = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var credentials: CredentialStore
    private lateinit var settings: SmbSettings
    private var link: SmbLink? = null
    private var hostIp: String? = null
    private var hostName: String? = null
    private var filter = ""
    private var searchOpen = false

    /** 类型筛选：null＝全部。目录永远显示，筛的只是文件。 */
    private var kindFilter: SmbKind? = null
    private var pendingDir: String? = null
    private var pendingPath: String? = null
    private var pendingName: String? = null

    /** 选目标文件夹模式：列表只留目录，顶部出横幅，确认后按 [pickKind] 执行上传/复制/移动。 */
    private var pickMode = false
    private var pickKind = PickKind.UPLOAD
    private var incoming: List<Uri> = emptyList()

    /** 复制/移动的源（路径，目录带尾斜杠）。目标在确认时取当前目录。 */
    private var pendingSources: List<String> = emptyList()

    /** 源在哪台机器上：与当前机器不同就是「跨机搬运」，要走手机中转而不是服务端复制。 */
    private var pendingSourceHost: String? = null

    /**
     * 多选：长按进入，点条目切换，返回键退出。
     *
     * 用文件名做键而不是下标——列表会因排序/过滤/刷新重排，下标一变选中就串位了；
     * 同一目录内文件名本来就唯一。
     */
    private var selecting = false
    private val selected = LinkedHashSet<String>()

    /**
     * 目录列表缓存（按访问顺序淘汰，只留最近用过的十几层）。
     *
     * 为什么必须有：局域网列一次目录看着不贵，其实是「一次 RTT + 一次 srvsvc/查询」，
     * 而翻共享的动作全是回头路——进三层、退回来看别的、再进去。不做缓存的话每次返回都要
     * 转一遍进度条，用户的感觉就是「这 App 点一下要等一下」。
     *
     * 命中且还在新鲜期内就直接上屏、不发请求；过期就先上屏（用户立刻看到内容）再后台静默
     * 重取，取完替换——也就是 stale-while-revalidate。写操作（上传/新建/改名/删除）会显式
     * 作废对应目录，不会让用户看到自己刚改过的东西又变回旧的。
     */
    private class CachedListing(val listing: SmbClient.Listing, val takenAt: Long)

    private val listings = object : LinkedHashMap<String, CachedListing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>?):
                Boolean = size > LISTING_MAX
    }

    /** 每个目录滚到第几行，回来时接着看。 */
    private val scrollMarks = HashMap<String, Int>()

    /** 后台静默刷新同一时刻只跑一条，避免来回切目录攒出一堆并发列目录。 */
    private var silentInFlight = false

    /** 上次重建 options menu 时的目录：菜单里「收藏/取消收藏」的文案要跟着当前目录变。 */
    private var menuDir = "\u0000"

    /** 屏幕用的动作集合，一次建好，免得每次重组换一批 lambda 引用。 */
    private val actions by lazy {
        SmbBrowseActions(
                jumpCrumb = ::jumpCrumb,
                toggleView = ::toggleView,
                openSort = ::showSortDialog,
                refresh = ::refreshNow,
                toggleSearch = ::toggleSearch,
                filterChange = ::setFilter,
                copyPath = { copyText(uncOf(dir)) },
                retry = ::refreshNow,
                scrollChanged = { index -> scrollMarks[dir] = index },
                click = ::onEntryClick,
                menu = ::showItemMenu,
                cancelJob = { id -> TransferHub.cancel(id) },
                clearJobs = { TransferHub.clearFinished() },
                retryJob = { id -> TransferHub.retry(this, id) },
                confirmPick = ::confirmPick,
                toggleSelect = ::toggleSelect,
                selectAll = ::selectAll,
                exitSelection = ::exitSelection,
                downloadSelected = ::downloadSelected,
                deleteSelected = ::deleteSelected,
                menuOfSelection = ::menuOfSelection,
                copySelected = { startCopyMove(selectedItems(), copy = true) },
                moveSelected = { startCopyMove(selectedItems(), copy = false) },
                openKind = ::showKindDialog,
        )
    }

    private val current: BrowseSnapshot get() = snapshot.value
    private val dir: String get() = current.at

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentials = CredentialStore(this)
        settings = SmbSettings(this)
        snapshot.value = current.copy(grid = settings.gridMode(),
                thumbnails = settings.thumbnails())
        setTitle(R.string.title_smb)

        // 系统返回键先逐级回到上一层目录，回到「共享列表」那一层才真的退出。
        // 不接管的话，翻了三层目录想回头看别的共享，只能退回扫描页再点进来一次，
        // 而界面右上角明明就有「上一级」——两条路的行为不该不一样。
        // 列表正在读取时 goUp() 自己会跳过（避免读一半换目录，回来对不上号），
        // 这一次返回就被吃掉，等列表落地再按即可。
        onBackPressedDispatcher.addCallback(this) {
            if (selecting) {
                // 多选时返回先退出多选，而不是退到上一层目录
                exitSelection()
                return@addCallback
            }
            if (pickMode) {
                // 选目录是个可取消的步骤：返回先退出它，别把整个浏览页关掉
                exitPickMode()
                return@addCallback
            }
            if (current.trail.size > 1) {
                goUp()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        hostIp = intent.getStringExtra(EXTRA_HOST_IP)
        hostName = intent.getStringExtra(EXTRA_HOST_NAME)
        if (intent.getBooleanExtra(EXTRA_PICK, false)) {
            incoming = intent.getStringArrayListExtra(EXTRA_INCOMING)
                    ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                    ?: emptyList()
            pickMode = incoming.isNotEmpty()
            if (pickMode) {
                setTitle(R.string.smb_send_title)
                snapshot.value = current.copy(pickMode = true, pickCount = incoming.size)
            }
        }
        val host = hostIp
        if (host == null) {
            showTargetDialog()
        } else {
            openHost(host, hostName, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val old = link
        link = null
        // 预览页可能还挂着同一条连接，计数交给 SmbHub 决定什么时候真关
        old?.let { SmbHub.release(it.host) }
        metadata.shutdown()
    }

    @Composable
    override fun Content() {
        val state by snapshot.collectAsState()
        // 任务表在进程级的 TransferHub 里：页面关掉再进来，还是同一份进度。
        // 它不进快照：每隔 100 ms 换一份快照会让整张列表跟着重组，257 个电影的目录白烧 CPU
        val jobs by TransferHub.views.collectAsState()
        SmbBrowseScreen(state = state, jobs = jobs, link = link, actions = actions)
    }

    // ---------------------------------------------------------------- 菜单

    /** 别的页面（解压）改过这个目录就重读一次；标记取走即用掉，不会每次回到页面都刷。 */
    override fun onResume() {
        super.onResume()
        val host = hostIp
        if (host != null && takeDirty(host, dir)) {
            reload()
        }
    }

    override fun onCreateComposeOptions(menu: Menu) {
        menu.add(0, MENU_UPLOAD, 0, R.string.smb_menu_upload)
        menu.add(0, MENU_UPLOAD_PICK, 0, R.string.smb_menu_upload_pick)
        menu.add(0, MENU_BACKUP, 0, R.string.smb_menu_backup)
        menu.add(0, MENU_FOLDER, 0, R.string.smb_menu_new_folder)
        menu.add(0, MENU_REFRESH, 0, R.string.smb_menu_refresh)
        menu.add(0, MENU_FILTER, 0, R.string.smb_menu_filter)
        menu.add(0, MENU_SEARCH, 0, R.string.smb_menu_search)
        menu.add(0, MENU_HIDDEN, 0, R.string.smb_menu_hidden).isCheckable = true
        menu.add(0, MENU_VIEW, 0, R.string.smb_menu_view)
        menu.add(0, MENU_THUMBS, 0, R.string.smb_menu_thumbnails).isCheckable = true
        menu.add(0, MENU_SORT, 0, R.string.smb_menu_sort)
        menu.add(0, MENU_SETTINGS, 0, R.string.smb_menu_compat)
        menu.add(0, MENU_CACHE, 0, R.string.smb_menu_cache)
        val host = hostIp
        val bookmarked = host != null && dir.isNotEmpty() && settings.isBookmarked(host, dir)
        menu.add(0, MENU_BOOKMARK, 0,
                if (bookmarked) R.string.smb_bookmark_remove else R.string.smb_bookmark_add)
        menu.add(0, MENU_BOOKMARKS, 0, R.string.smb_bookmarks_menu)
        if (pickMode && pickKind != PickKind.UPLOAD) {
            // 只在「挑目标目录」时出现：这时候换机器才有意义（目标是另一台机器上的共享）
            menu.add(0, MENU_TARGET_HOST, 0, R.string.smb_pick_other_host)
        }
        menu.findItem(MENU_HIDDEN).isChecked = settings.showHidden()
        menu.findItem(MENU_THUMBS).isChecked = settings.thumbnails()
    }

    override fun onMenuItemSelected(id: Int): Boolean {
        when (id) {
            MENU_UPLOAD -> pickUploadFile()
            MENU_UPLOAD_PICK -> pickFilesThenChooseFolder()
            MENU_FOLDER -> showNewFolderDialog()
            MENU_REFRESH -> reload()
            MENU_FILTER -> toggleSearch()
            MENU_SEARCH -> showSearchDialog()
            MENU_BACKUP -> startBackup()
            MENU_TARGET_HOST -> showTargetHostDialog()
            MENU_HIDDEN -> toggleChecked { settings.setShowHidden(!settings.showHidden()) }
            MENU_VIEW -> toggleView()
            MENU_THUMBS -> toggleChecked { settings.setThumbnails(!settings.thumbnails()) }
            MENU_SORT -> showSortDialog()
            MENU_SETTINGS -> showCompatDialog()
            MENU_CACHE -> showCacheDialog()
            MENU_BOOKMARK -> toggleBookmarkCurrent()
            MENU_BOOKMARKS -> showBookmarks(false)
            else -> return false
        }
        return true
    }

    /** 改一个开关 → 同步菜单勾选 → 用内存里的列表重算，不必再问一次服务器。 */
    private fun toggleChecked(apply: () -> Unit) {
        apply()
        val menu = optionsMenu
        menu?.findItem(MENU_HIDDEN)?.isChecked = settings.showHidden()
        menu?.findItem(MENU_THUMBS)?.isChecked = settings.thumbnails()
        publish(null, dir)
    }

    private fun toggleView() {
        settings.setGridMode(!settings.gridMode())
        snapshot.value = current.copy(grid = settings.gridMode())
    }

    // ---------------------------------------------------------------- 常用文件夹（收藏）

    /** 一键收藏/取消收藏当前目录。名字取路径末段，改名在收藏列表的「管理」里做。 */
    private fun toggleBookmarkCurrent() {
        val host = hostIp ?: return
        if (dir.isEmpty()) {
            toast(getString(R.string.smb_bookmark_root))
            return
        }
        if (settings.isBookmarked(host, dir)) {
            settings.removeBookmark(host, dir)
            toast(getString(R.string.smb_bookmark_removed))
        } else {
            val label = dir.trimEnd('/').substringAfterLast('/')
            settings.addBookmark(host, dir, label)
            toast(getString(R.string.smb_bookmark_added, label))
        }
        invalidateOptionsMenu()
    }

    /**
     * 相册增量备份。
     *
     * 先查 MediaStore 统计数量与总字节，再让用户确认——一上来就跑几千个文件的上传，
     * 用户看到的只有「咦怎么开始动了」。统计走工作线程：几万条记录查一遍不该占主线程。
     */
    private fun startBackup() {
        val client = link?.client ?: return
        if (dir.isEmpty()) {
            toast(getString(R.string.smb_backup_needs_folder))
            return
        }
        if (!SmbBackup.hasPermission(this)) {
            requestPermissions(SmbBackup.requiredPermissions(), REQ_BACKUP)
            return
        }
        confirmBackup(client)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BACKUP && SmbBackup.hasPermission(this)) {
            link?.client?.let { confirmBackup(it) }
        }
    }

    private fun confirmBackup(client: SmbClient) {
        val at = dir
        metadata.execute {
            val photos = SmbBackup.media(this)
            val target = at.trimEnd('/').substringAfterLast('/')
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                if (photos.isEmpty()) {
                    toast(getString(R.string.smb_backup_no_media))
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                        .setTitle(R.string.smb_backup_title)
                        .setMessage(getString(R.string.smb_backup_confirm, photos.size,
                                FileUtil.humanSize(photos.sumOf { it.size }), target))
                        .setPositiveButton(R.string.smb_backup_run) { _, _ ->
                            runBackup(client, at, photos)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
            }
        }
    }

    private fun runBackup(client: SmbClient, at: String, photos: List<SmbBackup.Photo>) {
        ensureNotificationPermission()
        val app = applicationContext
        TransferHub.enqueue(this, hostIp ?: return,
                getString(R.string.smb_backup_job, photos.size), done = { result ->
                    val summary = result as? SmbBackup.Summary ?: return@enqueue
                    toast(getString(R.string.smb_backup_done, summary.uploaded,
                            summary.skipped, summary.failed))
                    invalidateListing(at)
                    if (current.at == at) {
                        reload()
                    }
                }) { progress ->
            // 任务体活在被进程级队列持有的闭包里，所以只递 applicationContext，不递这一页
            SmbBackup.backup(app, client, at, photos, progress)
        }
    }

    /**
     * 从收藏直达。
     *
     * 标题取最近主机里记住的显示名：用户认的是「XXS」这种机器名，直接拿 IP 当标题
     * 等于每次都让人心里确认一遍「这是哪台」。
     */
    private fun openBookmark(mark: SmbSettings.Bookmark) {
        val display = settings.recentHosts().firstOrNull { it.host == mark.host }?.name
                ?: mark.host
        openHost(mark.host, display, mark.path)
    }

    /**
     * 收藏列表。点一条直达；点「管理」进入改名/删除。
     *
     * 之所以分成两段而不是在列表里直接做长按菜单：`AlertDialog.setItems` 的条目拿不到长按，
     * 而「打开」是高频动作，「管理」是低频动作——把低频的藏在第二层，比让每次打开都多点一下好。
     */
    private fun showBookmarks(manage: Boolean) {
        val marks = settings.bookmarks()
        if (marks.isEmpty()) {
            toast(getString(R.string.smb_bookmarks_empty))
            return
        }
        val labels = marks.map { mark -> mark.name + "\n" + mark.host + " " + mark.path }
        val builder = AlertDialog.Builder(this)
                .setTitle(if (manage) R.string.smb_bookmark_manage else R.string.smb_bookmarks_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    val mark = marks[which]
                    if (manage) {
                        showBookmarkActions(mark)
                    } else {
                        openBookmark(mark)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        if (!manage) {
            builder.setNeutralButton(R.string.smb_bookmark_manage) { _, _ -> showBookmarks(true) }
        }
        builder.show()
    }

    private fun showBookmarkActions(mark: SmbSettings.Bookmark) {
        val actions = arrayOf(getString(R.string.smb_action_enter),
                getString(R.string.smb_action_rename),
                getString(R.string.smb_action_delete))
        AlertDialog.Builder(this)
                .setTitle(mark.name)
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> openBookmark(mark)
                        1 -> showTextDialog(R.string.smb_action_rename, mark.name,
                                R.string.smb_action_rename) { name ->
                            if (name.isEmpty()) {
                                toast(getString(R.string.smb_bad_name))
                            } else {
                                // 同 host+path 会覆盖旧条目，改名因此不会留下重复的一条
                                settings.addBookmark(mark.host, mark.path, name)
                                showBookmarks(true)
                            }
                        }
                        else -> {
                            settings.removeBookmark(mark.host, mark.path)
                            invalidateOptionsMenu()
                            showBookmarks(true)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    // ---------------------------------------------------------------- 目标选择与连接

    private fun showTargetDialog() {
        val marks = settings.bookmarks()
        val recent = settings.recentHosts()
        val labels = ArrayList<String>()
        // 收藏排在最前：它们是「每天要开的那几个目录」，一点直达，不用先选机器再点两层
        for (mark in marks) {
            labels.add("★ " + mark.name + "\n" + mark.host + " " + mark.path)
        }
        for (host in recent) {
            labels.add(host.name + " (" + host.host + ")")
        }
        labels.add(getString(R.string.smb_target_manual))
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_target_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which < marks.size -> {
                            val mark = marks[which]
                            openBookmark(mark)
                        }
                        which < marks.size + recent.size -> {
                            val host = recent[which - marks.size]
                            openHost(host.host, host.name, null)
                        }
                        else -> showManualTargetDialog()
                    }
                }
                .setOnCancelListener { finish() }
                .show()
    }

    /**
     * 选目标期间换一台机器。
     *
     * 只有复制/移动进来：上传的源是手机里的文件，本来就不属于任何一台共享机器，
     * 「换一台」对它毫无意义。当前这台机器从候选里去掉（它就是默认答案）。
     */
    private fun showTargetHostDialog() {
        val here = hostIp
        val marks = settings.bookmarks().filter { it.host != here }
        val recent = settings.recentHosts().filter { it.host != here }
        val labels = ArrayList<String>()
        for (mark in marks) {
            labels.add("★ " + mark.name + "\n" + mark.host + " " + mark.path)
        }
        for (host in recent) {
            labels.add(host.name + " (" + host.host + ")")
        }
        labels.add(getString(R.string.smb_target_manual))
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_pick_other_host)
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which < marks.size ->
                            openHost(marks[which].host, null, marks[which].path)
                        which < marks.size + recent.size -> {
                            val host = recent[which - marks.size]
                            openHost(host.host, host.name, null)
                        }
                        else -> showManualTargetDialog { }
                    }
                }
                // 取消就是「目标还在这台机器上」，选目录模式必须原样留着
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun showManualTargetDialog(cancel: () -> Unit = { finish() }) {
        val content = layoutInflater.inflate(R.layout.dialog_smb_target, null)
        val etHost = content.findViewById<EditText>(R.id.et_host)
        val etShare = content.findViewById<EditText>(R.id.et_share)
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_target_manual)
                .setView(content)
                .setPositiveButton(R.string.lan_cred_connect) { _, _ ->
                    val host = normalizeHost(etHost.text.toString())
                    val share = etShare.text.toString().trim().replace("/", "")
                    if (host.isEmpty()) {
                        toast(getString(R.string.smb_bad_target))
                        return@setPositiveButton
                    }
                    // 共享名留空就从「列共享」开始；填了则直接进那个共享，省两次点击
                    openHost(host, if (share.isEmpty()) host else share,
                            if (share.isEmpty()) "" else share + "/")
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
                .show()
    }

    /** 用户可能贴来 `\\NAS\照片` 或 `smb://192.168.1.5/` 这类写法，先剥成主机名。 */
    private fun normalizeHost(raw: String): String {
        var text = raw.trim().replace('\\', '/')
        if (text.startsWith("smb://")) {
            text = text.substring("smb://".length)
        }
        for (segment in text.split("/")) {
            if (segment.isNotEmpty()) {
                return segment
            }
        }
        return ""
    }

    /** @param startPath null 表示用上次停留的位置（没记住就列共享） */
    private fun openHost(host: String, display: String?, startPath: String?) {
        // 换机器时先把旧连接还回去：引用计数只加不减的话，在几台机器之间跳来跳去
        // 会攒下一堆再也关不掉的连接（真机上翻收藏翻出来的）
        val previous = link
        link = null
        if (previous != null && previous.host != host) {
            SmbHub.release(previous.host)
        }
        hostIp = host
        hostName = display
        title = if (display.isNullOrEmpty()) host else display
        // 换机器就把缓存与筛选条件一起清掉：缓存的键只有路径，不同机器上「Share/照片/」
        // 这种同名路径是可能撞上的，撞上一次就会把另一台机器的目录当成这台的内容显示
        filter = ""
        searchOpen = false
        listings.clear()
        scrollMarks.clear()
        snapshot.value = current.copy(trail = listOf(""), entries = emptyList(), error = null,
                filter = "", searchOpen = false)
        ensureConnected {
            val start = startPath ?: settings.lastPath(host)
            if (start.isNullOrEmpty()) {
                load("", listOf(""))
            } else {
                load(start, levelsTo(start))
            }
        }
    }

    /** 到达 [path] 经过的每一级，供面包屑与「上一级」使用。 */
    private fun levelsTo(path: String): List<String> {
        val levels = ArrayList<String>()
        levels.add("")
        val builder = StringBuilder()
        for (segment in path.split("/")) {
            if (segment.isEmpty()) {
                continue
            }
            builder.append(segment).append('/')
            levels.add(builder.toString())
        }
        return levels
    }

    private fun ensureConnected(then: Runnable) {
        val host = hostIp ?: return
        val credential = credentials.get(host)
        if (credential == null) {
            CredentialDialog.show(this, credentials, host,
                    getString(R.string.smb_cred_title, host), R.string.lan_cred_connect
            ) { saved -> connectWith(saved, then) }
            return
        }
        connectWith(credential, then)
    }

    /**
     * 建连接。一定在工作线程做：jcifs 构造根资源与上下文时会走名称解析，主线程上直接
     * `NetworkOnMainThreadException`（真机实测），错误还会被显示成「失败：…」让人以为网络断了。
     */
    private fun connectWith(credential: CredentialStore.Credential, then: Runnable) {
        val host = hostIp ?: return
        // 这里只改状态文字，不能顺手把 loading 置真：load() 开头有「已有列表在读就跳过」的
        // 并发保护，而 then 正是 load()。置了真就等于连接成功之后那一次列目录被自己挡掉，
        // 界面会永远停在「正在读取…」（真机实测踩过）
        snapshot.value = current.copy(error = null, status = getString(R.string.smb_loading))
        metadata.execute {
            var built: SmbLink? = null
            var failure: String? = null
            var needLogin = false
            try {
                built = SmbHub.acquire(this, host, hostName ?: host)
            } catch (e: SmbNeedCredential) {
                needLogin = true
            } catch (e: Exception) {
                failure = SmbErrors.describe(this, e)
            }
            val result = built
            val error = failure
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                if (result == null) {
                    snapshot.value = current.copy(loading = false, error = error)
                    if (needLogin) {
                        ensureConnected(then)
                    }
                    return@runOnUiThread
                }
                link = result
                then.run()
            }
        }
    }

    // ---------------------------------------------------------------- 目录浏览

    private fun reload() = refreshNow()

    private fun enter(item: SmbClient.Item) =
            load(SmbClient.child(dir, item.name, true), null)

    private fun goUp() {
        val trail = current.trail
        if (trail.size <= 1) {
            return
        }
        val keep = trail.subList(0, trail.size - 1).toList()
        load(keep.last(), keep)
    }

    /** 面包屑直接跳某一级（0＝共享列表那一层）。当前级不需要重发请求。 */
    private fun jumpCrumb(index: Int) {
        val trail = current.trail
        if (index < 0 || index >= trail.size || index == trail.lastIndex) {
            return
        }
        val keep = trail.subList(0, index + 1).toList()
        load(keep.last(), keep)
    }

    /**
     * @param replacement 要替换的历史栈；null 表示在当前路径下追加一级
     * @param force 忽略缓存新鲜期重新取（手动刷新、写操作之后）
     */
    private fun load(path: String, replacement: List<String>?, force: Boolean = false) {
        val client = link?.client
        if (client == null) {
            ensureConnected(Runnable { load(path, replacement, force) })
            return
        }
        val wasRootRestore = current.trail.size <= 1 && path.isNotEmpty()
        val trail = replacement ?: (current.trail + path)
        val targetTrail = if (trail.isEmpty()) listOf("") else trail
        // 换目录就把多选清掉：选中项是按名字记的，带到另一个目录里只会误伤同名文件
        if (targetTrail.lastOrNull() != current.at && selecting) {
            selecting = false
            selected.clear()
        }
        snapshot.value = current.copy(
                trail = targetTrail,
                error = null,
                scrollIndex = scrollMarks[path] ?: 0,
        )
        val at = current.at
        settings.saveLastPath(hostIp!!, at)
        if (path.isEmpty()) {
            // 共享列出来了才算「这台值得记住」，主页直接进共享页时有得选
            settings.rememberHost(hostIp!!, hostName)
        }

        val cached = listings[path]
        if (cached != null) {
            // 命中缓存：先立刻上屏，过期的话后台静默补一次，用户不必等一个 RTT
            publish(cached.listing, at)
            val stale = SystemClock.elapsedRealtime() - cached.takenAt > FRESH_MS
            if (stale && !force) {
                refreshSilently(path)
            }
            if (!force) {
                return
            }
        }
        if (current.loading) {
            return
        }
        snapshot.value = current.copy(loading = true, status = getString(R.string.smb_loading))
        metadata.execute {
            var listing: SmbClient.Listing? = null
            var failure: String? = null
            try {
                listing = client.list(path, true)
            } catch (e: Exception) {
                failure = SmbErrors.describe(this, e)
            }
            val result = listing
            val error = failure
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                if (result == null) {
                    snapshot.value = current.copy(loading = false, error = error)
                    // 只有在「恢复上次停留的目录」失败时才退回共享列表——那种情况下用户本来就
                    // 还没进来；正常浏览中某个目录读失败，把他踢出当前目录反而是帮倒忙
                    if (wasRootRestore && link != null) {
                        snapshot.value = current.copy(trail = listOf(""))
                        load("", listOf(""))
                    }
                    return@runOnUiThread
                }
                if (current.at != at) {
                    // 用户已经走到别的目录了：这份结果只进缓存，不上屏。
                    // 但必须把 loading 交还——否则后续每次 load 都被「已有列表在读」挡死，
                    // 界面会停在「新目录 + 空列表 + 再也不动」的状态（真机踩过）。
                    listings[path] = CachedListing(result, SystemClock.elapsedRealtime())
                    snapshot.value = current.copy(loading = false)
                    if (current.entries.isEmpty()) {
                        load(current.at, ArrayList(current.trail))
                    }
                    return@runOnUiThread
                }
                listings[path] = CachedListing(result, SystemClock.elapsedRealtime())
                publish(result, at)
            }
        }
    }

    /**
     * 后台静默重取当前目录：屏上继续显示缓存内容，只有一条细线表示在刷新。
     *
     * 失败不弹错误也不清空列表——用户看到的仍然是上一次成功的内容，这比「因为一次超时而变白屏」
     * 有用得多；真要重来的话工具条上有刷新按钮。
     */
    private fun refreshSilently(path: String) {
        val client = link?.client ?: return
        if (silentInFlight) {
            return
        }
        silentInFlight = true
        snapshot.value = current.copy(refreshing = true)
        metadata.execute {
            var listing: SmbClient.Listing? = null
            try {
                listing = client.list(path, true)
            } catch (e: Exception) {
                // 静默失败，见方法注释
            }
            val result = listing
            silentInFlight = false
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                snapshot.value = current.copy(refreshing = false)
                if (result == null) {
                    return@runOnUiThread
                }
                listings[path] = CachedListing(result, SystemClock.elapsedRealtime())
                if (current.at == path) {
                    publish(result, path)
                }
            }
        }
    }

    /** 作废某个目录的缓存（写操作之后必须调，否则用户会看到自己刚改完的东西又变回旧的）。 */
    private fun invalidateListing(path: String) {
        listings.remove(path)
    }

    /**
     * 把某个目录的原始列表加工后上屏：隐藏项过滤 + 关键字过滤 + 排序，全在内存里做。
     *
     * [listing] 传 null 表示「只是过滤/排序/显示设置变了」，用缓存里的那份重算即可——
     * 否则改一次排序就要再问一次服务器，这是这条屏最不该有的延迟。
     */
    private fun publish(listing: SmbClient.Listing?, at: String) {
        val cached = listing ?: listings[at]?.listing
        if (cached == null) {
            // 没有缓存时不清屏：保留现有内容并补一次读取，比让界面变白强
            if (current.entries.isEmpty() && !current.loading) {
                load(at, ArrayList(current.trail))
            }
            return
        }
        val source = cached.items
        val hideHidden = !settings.showHidden()
        val needle = filter.lowercase(Locale.US)
        val shown = ArrayList<SmbClient.Item>()
        var afterHidden = 0
        var afterPick = 0
        for (item in source) {
            if (hideHidden && item.hidden) {
                continue
            }
            afterHidden++
            // 选目标文件夹时只留目录：目标是「往哪儿放」，列出文件只会让人手滑点错
            if (pickMode && !item.directory) {
                continue
            }
            afterPick++
            // 类型筛选只筛文件：目录是路，筛掉就没法往下走了
            if (!item.directory && kindFilter != null && SmbKind.of(item.name) != kindFilter) {
                continue
            }
            if (needle.isNotEmpty() && !item.name.lowercase(Locale.US).contains(needle)) {
                continue
            }
            shown.add(item)
        }
        val sort = settings.sortBy()
        shown.sortWith { a, b ->
            if (a.directory != b.directory) {
                // 三种排序都让目录置顶：翻共享时先找路，再在同组里比名称/大小/时间
                return@sortWith if (a.directory) -1 else 1
            }
            if (sort == SmbSettings.SORT_SIZE) {
                return@sortWith b.size.compareTo(a.size)
            }
            if (sort == SmbSettings.SORT_TIME) {
                return@sortWith b.modified.compareTo(a.modified)
            }
            val byName = a.name.compareTo(b.name, ignoreCase = true)
            if (byName != 0) byName else java.lang.Boolean.compare(a.directory, b.directory)
        }
        val status = StringBuilder()
        // 「少了东西」有三种原因，各说各的：选目录模式、关键字、隐藏属性。
        // 混成一句会把人指到错的开关上——真机上「电影」目录里 256 个电影文件被选目录模式滤掉，
        // 状态行却写「另有 256 项带隐藏属性」，看着像文件丢了
        if (pickMode) {
            status.append(getString(R.string.smb_count_dirs_format, shown.size))
            if (source.size > afterPick) {
                status.append(" · ").append(
                        getString(R.string.smb_pick_hidden_files, source.size - afterPick))
            }
        } else {
            status.append(getString(R.string.smb_count_format, shown.size))
            if (needle.isNotEmpty() && shown.size != afterHidden) {
                status.append(" · ").append(getString(R.string.smb_filtered_format, afterHidden))
            } else if (afterHidden != source.size) {
                status.append(" · ").append(getString(R.string.smb_hidden_dropped_format,
                        source.size - afterHidden))
            }
        }
        if (cached.truncated) {
            status.append(" · ").append(getString(R.string.smb_truncated, SmbClient.LIST_LIMIT))
        }
        if (cached.free >= 0) {
            status.append(" · ").append(getString(R.string.smb_free_format,
                    FileUtil.humanSize(cached.free)))
        }
        // 提示只在真的有文件可操作时出现：手机上没人知道条目可以长按，明面上写一句比教用户强。
        // 选目录模式下这条提示是错的（列里根本没有文件），直接不显示
        val hint = if (!pickMode && shown.any { !it.directory }) {
            getString(R.string.smb_hint_actions)
        } else {
            ""
        }
        snapshot.value = current.copy(
                entries = shown,
                loading = false,
                error = null,
                status = status.toString(),
                hint = hint,
                filter = filter,
                searchOpen = searchOpen,
                sortLabel = sortLabel(),
                kindLabel = kindLabel(),
                scrollIndex = scrollMarks[at] ?: 0,
                pickMode = pickMode,
                pickCount = incoming.size.coerceAtLeast(if (pickMode) pendingSources.size else 0),
                pickKind = pickKind,
                pickNote = pickNote(),
                selecting = selecting,
                selected = LinkedHashSet(selected),
        )
        // 换目录时让系统重建一次 options menu：「收藏当前目录 / 取消收藏」的文案取决于当前路径
        if (menuDir != at) {
            menuDir = at
            invalidateOptionsMenu()
        }
    }

    /** 工具条上的排序胶囊直接显示当前排序方式，不用打开菜单才知道自己按什么排的。 */
    private fun sortLabel(): String = getString(when (settings.sortBy()) {
        SmbSettings.SORT_SIZE -> R.string.smb_sort_size
        SmbSettings.SORT_TIME -> R.string.smb_sort_time
        else -> R.string.smb_sort_name
    })

    /** 类型筛选胶囊显示「全部/图片/视频…」，筛着筛着也不会忘。 */
    private fun kindLabel(): String = getString(when (kindFilter) {
        SmbKind.IMAGE -> R.string.smb_kind_image
        SmbKind.VIDEO -> R.string.smb_kind_video
        SmbKind.AUDIO -> R.string.smb_kind_audio
        SmbKind.PDF -> R.string.smb_kind_pdf
        SmbKind.TEXT -> R.string.smb_kind_text
        else -> R.string.smb_kind_all
    })

    /** 一条搜索命中的记录。 */
    private class SearchHit(val path: String, val name: String, val directory: Boolean,
                            val size: Long, val modified: Long)

    /**
     * 在子目录里搜文件名。
     *
     * 为什么要限流：SMB 没有搜索接口，只能一个目录一个目录地列，一个以万计的共享能扫到天荒地老。
     * 所以给了三道闸（[SEARCH_MAX_DIRS]/[SEARCH_MAX_ENTRIES]/[SEARCH_MAX_HITS]），
     * 并且把「扫了多少目录」写进结果标题——用户能看到这是有边界的搜索，而不是以为漏了。
     */
    private fun showSearchDialog() {
        showTextDialog(R.string.smb_search_title, "", R.string.smb_menu_search) { keyword ->
            if (keyword.isNotEmpty()) {
                startSearch(keyword)
            }
        }
    }

    private fun startSearch(keyword: String) {
        val client = link?.client ?: return
        val base = dir
        if (base.isEmpty()) {
            toast(getString(R.string.smb_search_root))
            return
        }
        toast(getString(R.string.smb_search_running, keyword))
        metadata.execute {
            val hits = ArrayList<SearchHit>()
            var scanned = 0
            try {
                scanned = client.search(base, keyword, SEARCH_MAX_DIRS, SEARCH_MAX_ENTRIES
                ) { path, name, directory, size, modified ->
                    hits.add(SearchHit(path, name, directory, size, modified))
                    hits.size < SEARCH_MAX_HITS
                }
            } catch (e: Exception) {
                // 中途出错也把已经找到的给用户，总比什么都没有强
            }
            val found = ArrayList(hits)
            val visited = scanned
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                showSearchResults(keyword, found, visited)
            }
        }
    }

    private fun showSearchResults(keyword: String, hits: List<SearchHit>, scanned: Int) {
        if (hits.isEmpty()) {
            toast(getString(R.string.smb_search_empty, keyword, scanned))
            return
        }
        val labels = hits.map { hit -> hit.name + "\n" + hit.path }
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.smb_search_found, hits.size, scanned))
                .setItems(labels.toTypedArray()) { _, which -> showSearchHitActions(hits[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun showSearchHitActions(hit: SearchHit) {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (!hit.directory && SmbKind.of(hit.name).previewable) {
            entries.add(getString(R.string.smb_action_open_here) to { previewRemote(hit) })
        }
        if (!hit.directory) {
            entries.add(getString(R.string.smb_action_download) to {
                startDownload(SmbClient.Item(hit.name, false, hit.size, hit.modified, false),
                        hit.path, keepOnly = true)
            })
        }
        entries.add(getString(R.string.smb_action_open_dir) to {
            // 跳回它所在的目录：搜索完最常见的下一步就是把东西挪走/改掉
            val at = SmbClient.parentOf(hit.path)
            if (at != current.at) {
                load(at, levelsTo(at))
            }
        })
        entries.add(getString(R.string.smb_copy_path) to { copyText(uncOf(hit.path)) })
        AlertDialog.Builder(this)
                .setTitle(hit.name)
                .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                    entries[which].second()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /** 预览一条「不在当前目录里」的结果：把它单独打包成一条清单交给预览页。 */
    private fun previewRemote(hit: SearchHit) {
        val host = hostIp ?: return
        val item = SmbClient.Item(hit.name, false, hit.size, hit.modified, false)
        startActivity(Intent(this, SmbPreviewActivity::class.java)
                .putExtra(SmbPreviewActivity.EXTRA_HOST, host)
                .putExtra(SmbPreviewActivity.EXTRA_HOST_LABEL, hostName)
                .putExtra(SmbPreviewActivity.EXTRA_INDEX, 0)
                .putStringArrayListExtra(SmbPreviewActivity.EXTRA_ITEMS,
                        arrayListOf(encodeEntry(hit.path, item))))
    }

    private fun showKindDialog() {
        val kinds = arrayOf<SmbKind?>(null, SmbKind.IMAGE, SmbKind.VIDEO, SmbKind.AUDIO,
                SmbKind.PDF, SmbKind.TEXT)
        val labels = arrayOf(
                getString(R.string.smb_kind_all),
                getString(R.string.smb_kind_image),
                getString(R.string.smb_kind_video),
                getString(R.string.smb_kind_audio),
                getString(R.string.smb_kind_pdf),
                getString(R.string.smb_kind_text))
        val checked = kinds.indexOf(kindFilter).coerceAtLeast(0)
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_kind_title)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    kindFilter = kinds[which]
                    dialog.dismiss()
                    publish(null, dir)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun uncOf(path: String): String {
        val client = link?.client ?: return "\\\\" + (hostIp ?: "")
        val unc = client.unc(path)
        if (unc.isEmpty()) {
            return "\\\\" + hostIp
        }
        return if (path.endsWith("/")) unc + "\\" else unc
    }

    // ---------------------------------------------------------------- 条目操作

    private fun onEntryClick(item: SmbClient.Item) {
        if (selecting) {
            toggleSelect(item)
            return
        }
        if (item.directory) {
            enter(item)
            return
        }
        // 传输不再阻断浏览：队列在后台跑，点开预览、翻目录、再加几个任务都允许
        val kind = SmbKind.of(item.name)
        if (kind == SmbKind.ARCHIVE) {
            openZip(item)
        } else if (kind.previewable) {
            openPreview(item)
        } else {
            showItemMenu(item)
        }
    }

    /** 压缩包：进单独的浏览/解压页，落点就是包所在的那个目录。 */
    private fun openZip(item: SmbClient.Item) {
        val host = hostIp ?: return
        SmbZipActivity.open(this, host, hostName ?: host,
                SmbClient.child(dir, item.name, false), item.name, dir)
    }

    // ---------------------------------------------------------------- 多选

    private fun enterSelection(item: SmbClient.Item) {
        selecting = true
        selected.clear()
        selected.add(item.name)
        publish(null, dir)
    }

    /** 长按与「选中状态下点击」走同一条：没在多选里就以这条为起点进入。 */
    private fun toggleSelect(item: SmbClient.Item) {
        if (!selecting) {
            enterSelection(item)
            return
        }
        if (!selected.remove(item.name)) {
            selected.add(item.name)
        }
        if (selected.isEmpty()) {
            selecting = false
        }
        publish(null, dir)
    }

    /** 全选只针对**当前过滤后看得到的**条目：屏幕外的东西不该被一个「全选」悄悄带走。 */
    private fun selectAll() {
        current.entries.forEach { selected.add(it.name) }
        selecting = selected.isNotEmpty()
        publish(null, dir)
    }

    private fun exitSelection() {
        if (!selecting && selected.isEmpty()) {
            return
        }
        selecting = false
        selected.clear()
        publish(null, dir)
    }

    private fun selectedItems(): List<SmbClient.Item> =
            current.entries.filter { it.name in selected }

    /**
     * 批量下载：每个文件一条任务，走同一个队列。
     *
     * 不逐条弹 Toast（一场 30 张照片会刷满屏幕），结束语只报一次「已加入 N 个」；
     * 每条的成败在传输面板里看。
     */
    private fun downloadSelected() {
        val files = selectedItems().filter { !it.directory }
        if (files.isEmpty()) {
            toast(getString(R.string.smb_selected_no_file))
            return
        }
        val downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloads == null) {
            toast(getString(R.string.smb_no_download_dir))
            return
        }
        val at = dir
        for (item in files) {
            startDownload(item, SmbClient.child(at, item.name, false), keepOnly = true, quiet = true)
        }
        toast(getString(R.string.smb_selected_queued, files.size))
        exitSelection()
    }

    private fun deleteSelected() {
        val client = link?.client ?: return
        val items = selectedItems()
        if (items.isEmpty()) {
            return
        }
        val at = dir
        AlertDialog.Builder(this)
                .setMessage(getString(R.string.smb_selected_delete_confirm, items.size))
                .setPositiveButton(R.string.smb_action_delete) { _, _ ->
                    for (item in items) {
                        val path = SmbClient.child(at, item.name, item.directory)
                        runTransfer(getString(R.string.smb_deleting_format, item.name),
                                done = { result ->
                                    if (result != null) {
                                        afterWrite(at)
                                    }
                                }) { _ ->
                            client.delete(path)
                            item.name
                        }
                    }
                    exitSelection()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /** 只选了一项时给出完整条目菜单（目录没有右侧 ⋮，否则改名之类的动作够不着）。 */
    private fun menuOfSelection() {
        val item = selectedItems().firstOrNull() ?: return
        exitSelection()
        showItemMenu(item)
    }

    /**
     * 进预览页，并把当前目录里**所有能预览的文件**一起带过去。
     *
     * 带整份清单是为了左右翻页：看照片时「看完一张要退回列表再点下一张」是最打断的一件事。
     * 清单在点的那一刻定型，预览页因此不需要再去列目录，也不会因为目录被改而翻到一半失效。
     */
    private fun openPreview(item: SmbClient.Item) {
        val host = hostIp ?: return
        val items = ArrayList<String>()
        var index = -1
        for (candidate in current.entries) {
            if (candidate.directory || !SmbKind.of(candidate.name).previewable) {
                continue
            }
            if (candidate.name == item.name) {
                index = items.size
            }
            items.add(encodeEntry(SmbClient.child(dir, candidate.name, false), candidate))
        }
        if (index < 0) {
            items.add(encodeEntry(SmbClient.child(dir, item.name, false), item))
            index = 0
        }
        startActivity(Intent(this, SmbPreviewActivity::class.java)
                .putExtra(SmbPreviewActivity.EXTRA_HOST, host)
                .putExtra(SmbPreviewActivity.EXTRA_HOST_LABEL, hostName)
                .putExtra(SmbPreviewActivity.EXTRA_INDEX, index)
                .putStringArrayListExtra(SmbPreviewActivity.EXTRA_ITEMS, items))
    }

    private fun encodeEntry(path: String, item: SmbClient.Item): String =
            "$path\u0001${item.name}\u0001${item.size}\u0001${item.modified}"

    /**
     * 条目菜单。
     *
     * 用「文案 + 动作」的列表而不是并行的两个数组：动作多到十个之后，下标对不上就是一次
     * 误删。媒体文件多出「直接播放到外部播放器」——不用先落盘，1.7 G 的电影点了就开始放。
     */
    private fun showItemMenu(item: SmbClient.Item) {
        val path = SmbClient.child(dir, item.name, item.directory)
        val kind = SmbKind.of(item.name)
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (item.directory) {
            entries.add(getString(R.string.smb_action_enter) to { enter(item) })
        } else {
            if (kind.previewable) {
                entries.add(getString(R.string.smb_action_open_here) to { openPreview(item) })
            }
            if (kind == SmbKind.ARCHIVE) {
                entries.add(getString(R.string.smb_action_open_zip) to { openZip(item) })
            }
            if (kind == SmbKind.VIDEO || kind == SmbKind.AUDIO) {
                entries.add(getString(R.string.smb_action_stream_open) to {
                    val host = hostIp
                    if (host != null) {
                        SmbExternalPlay.play(this, host, path, item.name)
                    }
                })
            }
            entries.add(getString(R.string.smb_action_download) to {
                startDownload(item, path, keepOnly = true)
            })
            entries.add(getString(R.string.smb_action_open) to {
                startDownload(item, path, keepOnly = false)
            })
            entries.add(getString(R.string.smb_action_save_as) to { beginSaveAs(item, path) })
        }
        entries.add(getString(R.string.smb_action_copy_to) to {
            startCopyMove(listOf(item), copy = true)
        })
        entries.add(getString(R.string.smb_action_move_to) to {
            startCopyMove(listOf(item), copy = false)
        })
        entries.add(getString(R.string.smb_copy_path) to { copyText(uncOf(path)) })
        entries.add(getString(R.string.smb_action_properties) to { showProps(item, path) })
        entries.add(getString(R.string.smb_action_rename) to { showRenameDialog(item, path) })
        entries.add(getString(R.string.smb_action_delete) to { confirmDelete(item, path) })
        AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                    entries[which].second()
                }
                .show()
    }

    private fun showProps(item: SmbClient.Item, path: String) {
        AlertDialog.Builder(this)
                .setTitle(item.name)
                .setMessage(getString(R.string.smb_props_format, item.name, uncOf(path),
                        if (item.directory) getString(R.string.smb_dir)
                        else FileUtil.humanSize(item.size),
                        if (item.modified > 0) dateFormat.format(Date(item.modified))
                        else getString(R.string.smb_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    // ---------------------------------------------------------------- 传输

    /**
     * 下载一个文件到本机。
     *
     * @param keepOnly 真＝只下载到本机；假＝下完立刻交给别的 App 打开
     * @param quiet 批量下载时用：不逐条 Toast，成败交给传输面板
     *
     * 落点由 [DownloadSink] 决定：Android 10+ 落在公共「下载/工具箱」，其它应用能直接看到；
     * 更低版本退回应用私有目录（那边要 WRITE_EXTERNAL_STORAGE 才写得了公共目录）。
     */
    private fun startDownload(item: SmbClient.Item, path: String, keepOnly: Boolean,
                              quiet: Boolean = false) {
        val client = link?.client ?: return
        val host = hostIp ?: return
        val sink = try {
            DownloadSink.create(this, item.name)
        } catch (e: Exception) {
            toast(SmbErrors.describe(this, e))
            return
        }
        ensureNotificationPermission()
        TransferHub.enqueueDownload(this, host, getString(R.string.smb_downloading_format, item.name),
                TransferHub.DownloadSpec(client, path, sink), done = { result ->
                    if (result == null) {
                        // 失败或取消：半截文件留着，面板上会给「重试」从断点继续
                        return@enqueueDownload
                    }
                    if (keepOnly) {
                        if (!quiet) {
                            toast(doneText(sink))
                        }
                    } else {
                        offerOpen(sink)
                    }
                })
    }

    /** 「已保存 x（大小） · 位置」，大小问不到就只报名字。 */
    private fun doneText(sink: DownloadSink): String {
        val size = sink.size()
        val base = if (size >= 0) {
            getString(R.string.smb_download_done_format, sink.label, FileUtil.humanSize(size))
        } else {
            sink.label
        }
        return "$base · ${sink.location}"
    }

    private fun offerOpen(sink: DownloadSink) {
        AlertDialog.Builder(this)
                .setTitle(doneText(sink))
                .setMessage(R.string.smb_open_after_message)
                .setPositiveButton(R.string.smb_action_open) { _, _ -> openSink(sink) }
                .setNeutralButton(R.string.share_chooser_send) { _, _ -> shareSink(sink) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun openSink(sink: DownloadSink) {
        val mime = FileUtil.mimeOf(sink.label)
        if (FileShare.openUri(this, sink.contentUri(this), mime) == FileShare.NO_APP) {
            toast(getString(R.string.share_no_app, sink.label))
        }
    }

    private fun shareSink(sink: DownloadSink) {
        val mime = FileUtil.mimeOf(sink.label)
        if (FileShare.sendUri(this, sink.contentUri(this), mime) == FileShare.NO_APP) {
            toast(getString(R.string.share_no_app, sink.label))
        }
    }

    private fun beginSaveAs(item: SmbClient.Item, path: String) {
        pendingPath = path
        pendingName = item.name
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(FileUtil.mimeOf(item.name))
                .putExtra(Intent.EXTRA_TITLE, item.name)
        launch(create, REQ_SAVE_AS, getString(R.string.smb_save_as_title))
    }

    private fun pickUploadFile() {
        pendingDir = dir
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
        launch(open, REQ_UPLOAD, getString(R.string.smb_pick_file_title))
    }

    /**
     * 「从手机出发」的上传：先选一批文件，再进选目录模式挑落点。
     *
     * 与「上传到此处…」的分工：后者是「我已经站在目标目录里了，丢个文件进来」，
     * 这条是「我要把这几张照片送到某个目录去」——起点在手机相册/文件管理器那边。
     */
    private fun pickFilesThenChooseFolder() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        launch(open, REQ_PICK_FILES, getString(R.string.smb_pick_file_title))
    }

    /** 进入选目录模式。还没连机器时先弹目标选择框（收藏 / 最近主机），选完自然落进同一个模式。 */
    private fun startPickMode(uris: List<Uri>) {
        incoming = uris
        pickMode = true
        pickKind = PickKind.UPLOAD
        setTitle(R.string.smb_send_title)
        snapshot.value = current.copy(pickMode = true, pickCount = uris.size,
                pickKind = PickKind.UPLOAD)
        if (link == null) {
            showTargetDialog()
        } else {
            publish(null, dir)
        }
    }

    /**
     * 进入「复制/移动到…」：复用同一个选目录界面，只是确认之后的动作不同。
     *
     * 复制走 jcifs 的服务端复制（SMB2 的 FSCTL_SRV_COPYCHUNK），移动优先走服务端改名——
     * 两者都不把数据绕手机，所以在同一台 NAS 内搬几个 G 也是秒级。
     */
    private fun startCopyMove(items: List<SmbClient.Item>, copy: Boolean) {
        if (items.isEmpty()) {
            return
        }
        val at = dir
        pendingSources = items.map { SmbClient.child(at, it.name, it.directory) }
        pendingSourceHost = hostIp
        pickMode = true
        invalidateOptionsMenu()
        pickKind = if (copy) PickKind.COPY else PickKind.MOVE
        selecting = false
        selected.clear()
        setTitle(if (copy) R.string.smb_copy_title else R.string.smb_move_title)
        snapshot.value = current.copy(
                pickMode = true,
                pickCount = items.size,
                pickKind = pickKind,
                selecting = false,
                selected = emptySet(),
        )
        publish(null, dir)
    }

    /**
     * 选目录横幅下面那句补充说明。
     *
     * 跨机搬运必须在确认前就说清「源在另一台机器上，字节要过手机一趟」：同一台机器上秒完成的
     * 量，跨机可能要几分钟，用户以为按错了会更糟。
     */
    private fun pickNote(): String {
        if (!pickMode || pickKind == PickKind.UPLOAD) {
            return ""
        }
        val source = pendingSourceHost ?: return ""
        if (source == hostIp) {
            return ""
        }
        return getString(R.string.smb_pick_note_cross, source)
    }

    /**
     * 确认落点。三种用途共用一个确认按钮，动作在 [pickKind] 上分流。
     */
    private fun confirmPick() {
        val target = dir
        if (target.isEmpty()) {
            toast(getString(R.string.smb_pick_root))
            return
        }
        when (pickKind) {
            PickKind.UPLOAD -> confirmUploadPick(target)
            PickKind.COPY -> confirmCopyMove(target, move = false)
            PickKind.MOVE -> confirmCopyMove(target, move = true)
        }
    }

    /**
     * 整批文件丢进上传队列，退出选目录模式、留在这页看进度。
     *
     * 故意不 finish()：任务跟着 Activity 走（onDestroy 会标停、连接也会还回去），
     * 一边传一边走等于把上传掐掉。
     */
    private fun confirmUploadPick(target: String) {
        val client = link?.client ?: return
        val uris = incoming
        exitPickMode()
        if (uris.isEmpty()) {
            finish()
            return
        }
        uris.forEachIndexed { index, uri ->
            // 一批文件会有好几条同名任务，标签带上序号才分得清哪条卡住了
            val label = if (uris.size == 1) getString(R.string.smb_uploading)
            else getString(R.string.smb_uploading_n, index + 1, uris.size)
            runTransfer(label, done = { result ->
                if (result != null) {
                    afterWrite(target)
                }
            }) { progress ->
                client.upload(this, uri, target, progress)
                uri
            }
        }
        incoming = emptyList()
        toast(getString(R.string.smb_upload_queued, uris.size))
        publish(null, target)
    }

    private fun confirmCopyMove(target: String, move: Boolean) {
        val sources = pendingSources
        val sourceHost = pendingSourceHost
        exitPickMode()
        if (sources.isEmpty()) {
            return
        }
        if (sourceHost != null && sourceHost != hostIp) {
            confirmCrossHostMove(sourceHost, target, sources, move)
            return
        }
        val client = link?.client ?: return
        val sourceDir = SmbClient.parentOf(sources.first())
        // 移动会让源目录少东西，那份缓存必须作废；目标就是当前目录，交给 afterWrite 刷新
        invalidateListing(sourceDir)
        val labelRes = if (move) R.string.smb_moving_format else R.string.smb_copying_format
        for (source in sources) {
            val name = source.trimEnd('/').substringAfterLast('/')
            runTransfer(getString(labelRes, name), done = { result ->
                if (result != null) {
                    afterWrite(target)
                }
            }) { _ ->
                if (move) client.move(source, target) else client.copy(source, target)
            }
        }
        toast(getString(if (move) R.string.smb_moved_count else R.string.smb_copied_count,
                sources.size))
        publish(null, target)
    }

    /**
     * 跨机搬运：源在这一页之外的那台机器上，只能把字节读进手机再写到对面。
     *
     * 每台机器的连接都在任务体里现取现还：页面的 [link] 已经跟着「换目标机器」切走了，
     * 而队列是进程级的，捕获页面手里的连接等于把一个已经不该活着的对象塞进后台任务。
     */
    private fun confirmCrossHostMove(sourceHost: String, target: String,
                                     sources: List<String>, move: Boolean) {
        val targetHost = hostIp ?: return
        val app = applicationContext
        val labelRes = if (move) R.string.smb_moving_format else R.string.smb_copying_format
        for (source in sources) {
            val name = source.trimEnd('/').substringAfterLast('/')
            TransferHub.enqueue(this, targetHost, getString(labelRes, name), done = { result ->
                if (result != null) {
                    afterWrite(target)
                }
            }) { progress ->
                val from = SmbHub.acquire(app, sourceHost, sourceHost)
                try {
                    val to = SmbHub.acquire(app, targetHost, targetHost)
                    try {
                        SmbCrossTransfer.run(from, to, source, target, move, progress)
                    } finally {
                        SmbHub.release(to.host)
                    }
                } finally {
                    SmbHub.release(from.host)
                }
            }
        }
        toast(getString(if (move) R.string.smb_moved_count else R.string.smb_copied_count,
                sources.size))
        publish(null, target)
    }

    private fun exitPickMode() {
        if (!pickMode) {
            return
        }
        pickMode = false
        pendingSources = emptyList()
        pendingSourceHost = null
        invalidateOptionsMenu()
        title = hostName ?: hostIp ?: getString(R.string.title_smb)
        snapshot.value = current.copy(pickMode = false, pickCount = 0, pickKind = PickKind.UPLOAD)
    }

    private fun launch(intent: Intent, requestCode: Int, title: String) {
        try {
            startActivityForResult(Intent.createChooser(intent, title), requestCode)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.smb_no_file_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            return
        }
        // 多选的结果可能只放在 clipData 里，data 本身为空——所以这条要在 data.data 判空之前
        if (requestCode == REQ_PICK_FILES) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            }
            data.data?.let { if (uris.isEmpty()) uris.add(it) }
            if (uris.isNotEmpty()) {
                startPickMode(uris)
            }
            return
        }
        if (data.data == null) {
            return
        }
        val uri = data.data ?: return
        val client = link?.client
        if (client == null) {
            return
        }
        if (requestCode == REQ_UPLOAD) {
            val at = pendingDir ?: dir
            runTransfer(getString(R.string.smb_uploading), done = { result ->
                if (result != null) {
                    toast(getString(R.string.smb_upload_done))
                    invalidateListing(at)
                    if (current.at == at) {
                        reload()
                    }
                }
            }) { progress ->
                client.upload(this, uri, at, progress)
                uri
            }
            return
        }
        if (requestCode == REQ_SAVE_AS) {
            val path = pendingPath ?: return
            runTransfer(getString(R.string.smb_downloading_format, pendingName ?: path),
                    done = { result ->
                        if (result != null) {
                            toast(getString(R.string.smb_saved_to))
                        }
                    }) { progress ->
                val out = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("无法写入所选位置")
                client.download(path, out, progress)
                uri
            }
        }
    }

    /**
     * 往传输队列里丢一个后台动作。
     *
     * 真正的执行、任务表与并发数都在 [TransferHub]（进程级）：这样页面退出之后任务照常跑，
     * 前台服务只负责把进度摆到通知栏。这里只把「哪台机器」和「当前连接」递过去。
     */
    private fun runTransfer(label: String, done: (Any?) -> Unit = { },
                            body: (SmbClient.Progress) -> Any?) {
        val host = hostIp
        if (host == null || link == null) {
            done(null)
            return
        }
        ensureNotificationPermission()
        TransferHub.enqueue(this, host, label, done, body)
    }

    /**
     * 传输进度要在通知栏上看得见，API 33 起这是运行时权限。
     * 只在真的要开始传的时候问一次：进页面就问会显得莫名其妙。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    // ---------------------------------------------------------------- 写操作与设置

    private fun showNewFolderDialog() = showTextDialog(R.string.smb_new_folder_title, "",
            R.string.smb_menu_new_folder) { name ->
        if (!isNameOk(name)) {
            toast(getString(R.string.smb_bad_name))
        } else {
            val at = dir
            runTransfer(getString(R.string.smb_creating_format, name), done = { result ->
                if (result != null) {
                    toast(getString(R.string.smb_created_folder, name))
                    afterWrite(at)
                }
            }) { _ ->
                link!!.client.createFolder(SmbClient.child(at, name, true))
                name
            }
        }
    }

    private fun showRenameDialog(item: SmbClient.Item, path: String) =
            showTextDialog(R.string.smb_rename_title, item.name, R.string.smb_action_rename) { name ->
                if (!isNameOk(name) || name == item.name) {
                    toast(getString(R.string.smb_bad_name))
                } else {
                    val at = dir
                    runTransfer(getString(R.string.smb_renaming_format, item.name),
                            done = { result ->
                                if (result != null) {
                                    toast(getString(R.string.smb_renamed_format, item.name, name))
                                    afterWrite(at)
                                }
                            }) { _ ->
                        link!!.client.rename(path, name)
                        name
                    }
                }
            }

    private fun confirmDelete(item: SmbClient.Item, path: String) {
        AlertDialog.Builder(this)
                .setMessage(getString(R.string.smb_delete_confirm, item.name))
                .setPositiveButton(R.string.smb_action_delete) { _, _ ->
                    val at = dir
                    runTransfer(getString(R.string.smb_deleting_format, item.name),
                            done = { result ->
                                if (result != null) {
                                    toast(getString(R.string.smb_deleted, item.name))
                                    afterWrite(at)
                                }
                            }) { _ ->
                        link!!.client.delete(path)
                        item.name
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * 写操作落地后的一致性问题：那个目录的缓存必须作废，否则「改完名字翻出去再回来还是旧名」。
     * 只有用户还停在该目录时才立刻重取——他已经走到别处的话，下次进去自然会取新的。
     */
    private fun afterWrite(at: String) {
        invalidateListing(at)
        if (current.at == at) {
            reload()
        }
    }

    private fun showTextDialog(titleRes: Int, preset: String, buttonRes: Int,
                               done: (String) -> Unit) {
        val content = layoutInflater.inflate(R.layout.dialog_smb_text, null)
        val text = content.findViewById<EditText>(R.id.et_text)
        text.setText(preset)
        text.setSelection(preset.length)
        AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(buttonRes) { _, _ -> done(text.text.toString().trim()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun isNameOk(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") {
            return false
        }
        for (element in name) {
            if (element == '/' || element == '\\' || element == ':' || element == '*' ||
                    element == '?' || element == '"' || element == '<' || element == '>' ||
                    element == '|' || element.code < 0x20) {
                return false
            }
        }
        return true
    }

    /**
     * 工具条的「过滤」胶囊：点开就地展开输入框，边打字边筛。
     *
     * 原来是弹一个对话框问一次关键字，改一次要开一次对话框；列表已经在内存里，
     * 这件事根本没有网络开销，不该用模态对话框挡路。收起时把关键字清掉——
     * 留着的话条目会被看不见的条件筛掉，用户只会觉得「文件怎么少了」。
     */
    private fun toggleSearch() {
        searchOpen = !searchOpen
        if (!searchOpen) {
            filter = ""
        }
        publish(null, dir)
    }

    private fun setFilter(text: String) {
        filter = text
        searchOpen = true
        publish(null, dir)
    }

    /** 手动刷新：绕开缓存新鲜期重取当前目录（写操作之后走的也是这条）。 */
    private fun refreshNow() {
        if (current.loading) {
            return
        }
        load(dir, ArrayList(current.trail), force = true)
    }

    private fun showSortDialog() {
        val labels = arrayOf(
                getString(R.string.smb_sort_name),
                getString(R.string.smb_sort_size),
                getString(R.string.smb_sort_time))
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_menu_sort)
                .setSingleChoiceItems(labels, settings.sortBy()) { dialog, which ->
                    settings.setSortBy(which)
                    dialog.dismiss()
                    publish(null, dir)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * 兼容与显示设置。
     *
     * 三个协议开关改完要重建连接：协议版本与签名是协商时定下来的，会话已经建立后再改属性
     * 不会重新谈，只有丢掉 CIFSContext 才会生效——而这正是老 NAS 连不上时用户最想试的那一步。
     * 新的「读文件用哪个库」同样要重连（SMBJ 的会话池按参数缓存）。
     */
    private fun showCompatDialog() {
        val labels = arrayOf(
                getString(R.string.smb_opt_smb1),
                getString(R.string.smb_opt_signing),
                getString(R.string.smb_opt_guest),
                getString(R.string.smb_opt_thumbs),
                getString(R.string.smb_opt_engine_smbj),
                getString(R.string.smb_opt_encrypt))
        val values = booleanArrayOf(settings.allowSmb1(), settings.signingEnforced(),
                settings.guestFallback(), settings.thumbnails(),
                settings.engine() == SmbSettings.ENGINE_SMBJ, settings.encryptionEnabled())
        AlertDialog.Builder(this)
                // 当前协商范围写进标题：AlertDialog 的消息区会和选项列表抢位置（实测 MIUI 13
                // 直接把列表挤没），见 LanScanActivity 的端口集对话框里同样的处理
                .setTitle(getString(R.string.smb_menu_compat) + "\n" +
                        settings.dialectSummary(this))
                .setMultiChoiceItems(labels, values) { _, which, checked -> values[which] = checked }
                .setNeutralButton(R.string.smb_opt_timeout) { _, _ -> showTimeoutDialog() }
                .setPositiveButton(R.string.smb_settings_apply) { _, _ ->
                    val protocolChanged = values[0] != settings.allowSmb1() ||
                            values[1] != settings.signingEnforced() ||
                            values[2] != settings.guestFallback() ||
                            values[5] != settings.encryptionEnabled() ||
                            (values[4] != (settings.engine() == SmbSettings.ENGINE_SMBJ))
                    settings.setAllowSmb1(values[0])
                    settings.setSigningEnforced(values[1])
                    settings.setGuestFallback(values[2])
                    settings.setThumbnails(values[3])
                    settings.setEncryptionEnabled(values[5])
                    settings.setEngine(if (values[4]) SmbSettings.ENGINE_SMBJ
                    else SmbSettings.ENGINE_JCIFS)
                    snapshot.value = current.copy(thumbnails = settings.thumbnails())
                    val menu = optionsMenu
                    menu?.findItem(MENU_THUMBS)?.isChecked = settings.thumbnails()
                    if (protocolChanged) {
                        toast(getString(R.string.smb_settings_applied))
                        reconnect()
                    } else {
                        publish(null, dir)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /**
     * 预览缓存：显示占用与清空。
     *
     * 之所以要放在界面上：缩略图、原图、PDF 全文、视频抽帧都往 `cacheDir/smb_preview` 里落
     * 字节（见 [SmbSourceCache]），上限默认 128 MB。用户在一台 64 GB 的手机上装了几年缓存之后
     * 需要有一个看得见、按得动的地方把它清掉，而不是只能去系统设置里「清除存储」。
     */
    private fun showCacheDialog() {
        metadata.execute {
            val used = SmbSourceCache.usedBytes(this)
            runOnUiThread {
                if (isFinishing) {
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                        .setTitle(R.string.smb_menu_cache)
                        .setMessage(getString(R.string.smb_cache_summary,
                                FileUtil.humanSize(used),
                                FileUtil.humanSize(settings.cacheLimitMb() * 1024L * 1024L)))
                        .setPositiveButton(R.string.smb_cache_clear) { _, _ ->
                            SmbSourceCache.clear(this)
                            SmbVideoThumbs.clear()
                            toast(getString(R.string.smb_cache_cleared))
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
            }
        }
    }

    private fun showTimeoutDialog() {
        val seconds = intArrayOf(2, 5, 10, 20)
        val labels = Array(seconds.size) { getString(R.string.smb_timeout_seconds, seconds[it]) }
        var checked = 0
        for (i in seconds.indices) {
            if (seconds[i] == settings.connTimeoutSeconds()) {
                checked = i
            }
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.smb_opt_timeout)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    settings.setConnTimeoutSeconds(seconds[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun reconnect() {
        if (TransferHub.activeCount > 0) {
            // 换参数会关掉正在传输用的那条连接：让用户先在面板里停掉手上的任务
            toast(getString(R.string.smb_jobs_blocking))
            return
        }
        val host = hostIp ?: return
        link = null
        SmbHub.invalidate(host)
        snapshot.value = current.copy(trail = listOf(""), entries = emptyList())
        listings.clear()
        scrollMarks.clear()
        ensureConnected { load("", listOf("")) }
    }

    // ---------------------------------------------------------------- 杂项

    private fun copyText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("smb", text))
        toast(getString(R.string.dns_copied, text))
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
