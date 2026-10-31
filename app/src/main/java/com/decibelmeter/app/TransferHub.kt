package com.decibelmeter.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程级传输队列。
 *
 * 为什么从 Activity 里搬出来：原来任务表挂在浏览页上，`onDestroy` 会取消所有任务并归还连接——
 * 下 1.7 GB 的电影期间不能退出页面。现在任务活在进程级单例里，配合 [SmbTransferService]
 * 的前台通知，页面退出之后照样传；页面再进来时从 [views] 里读到同一份进度。
 *
 * 连接的寿命也在这里管：每个有活跃任务的机器被「钉」一次（[SmbHub] 引用计数 +1），
 * 全部任务结束才放开。这样 Activity 退出只会减少它自己那一份，不会把正在用的连接关掉。
 *
 * 线程模型：任务表与状态只在主线程改（进度回调也 post 回主线程），后台线程只跑实际的 IO
 * 并且只读 [Job.cancel]。
 */
object TransferHub {

    /** 可续传的下载任务：失败后从「已经写了多少字节」继续。 */
    class DownloadSpec(
            val client: SmbClient,
            val path: String,
            val sink: DownloadSink,
    ) {
        /**
         * @param offset 续传起点。**只能来自任务自己记录的 doneBytes**：
         *   MediaStore 的 pending 项查不到 SIZE（未提交的条目对查询不可见），
         *   所以 sink.resumableBytes() 在公共下载目录这条路上永远是 0——真机上试出来的。
         *   每次 write 都会在返回后才上报进度，所以 doneBytes 与磁盘上的字节数是准的。
         */
        fun run(offset: Long, progress: SmbClient.Progress): Any? {
            sink.openAt(offset).use { out -> client.download(path, offset, out, progress) }
            sink.applyModified(client.lastModified(path))
            // 续传一旦错位就是坏文件：commit 之前核对一次总字节数，对不上宁可报错
            val expected = client.size(path)
            val actual = sink.size()
            if (expected > 0 && actual >= 0 && actual != expected) {
                throw java.io.IOException("续传后大小不符：期望 $expected 字节，实际 $actual 字节")
            }
            sink.commit()
            return sink
        }
    }

    class Job(
            val id: Long,
            val host: String,
            val label: String,
            val body: (SmbClient.Progress) -> Any?,
            /** 非空表示这是一条可续传的下载 */
            val download: DownloadSpec?,
            val done: (Any?) -> Unit,
            private val appContext: Context,
    ) {
        val cancel = AtomicBoolean(false)
        @Volatile var doneBytes = 0L
        @Volatile var total = -1L
        @Volatile var state = TransferState.QUEUED
        @Volatile var message: String? = null
        @Volatile var startedAt = 0L
        @Volatile var finishedAt = 0L
        @Volatile var lastPush = 0L
        @Volatile var lastPersist = 0L

        fun describe(error: Exception): String = SmbErrors.describe(appContext, error)

        /** 续传记录的键；不是下载任务时为 null。 */
        val resumeKey: String?
            get() = download?.let { "$host|${it.path}|${it.sink.token}" }

        /**
         * 把「下到哪儿了」写进存档。[force] 为 false 时按秒节流——进度回调一秒会来几十次。
         * 只在真正写完一段之后调用，存档里的字节数只会落后于磁盘，不会超前。
         */
        fun persistResume(force: Boolean) {
            val spec = download ?: return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastPersist < PERSIST_MS) {
                return
            }
            lastPersist = now
            UnfinishedStore.put(appContext, UnfinishedDownload(
                    host = host,
                    path = spec.path,
                    token = spec.sink.token,
                    label = label,
                    doneBytes = doneBytes,
                    total = total,
                    at = System.currentTimeMillis()))
        }

        fun forgetResume() {
            val key = resumeKey ?: return
            UnfinishedStore.remove(appContext, key)
        }

        fun view(): TransferJobView {
            val end = if (finishedAt > 0) finishedAt else SystemClock.elapsedRealtime()
            val span = if (startedAt > 0) (end - startedAt).coerceAtLeast(1) else 0L
            val rate = if (span > 0 && doneBytes > 0) doneBytes * 1000 / span else 0L
            return TransferJobView(
                    id = id,
                    label = label,
                    done = doneBytes,
                    total = total,
                    state = state,
                    message = message,
                    rate = rate,
                    retryable = download != null &&
                            (state == TransferState.FAILED || state == TransferState.CANCELED),
            )
        }
    }

    private const val PROGRESS_PUSH_MS = 100L
    private const val PERSIST_MS = 1_500L
    private const val MAX_JOBS = 12

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = LinkedHashMap<Long, Job>()
    private var nextId = 1L
    private val hostPins = HashMap<String, Int>()

    private val _views = MutableStateFlow<List<TransferJobView>>(emptyList())
    val views: StateFlow<List<TransferJobView>> = _views

    /** 队列变化时回调：前台服务用它刷新通知。 */
    @Volatile
    var onChange: (() -> Unit)? = null

    val activeCount: Int
        get() = jobs.values.count {
            it.state == TransferState.QUEUED || it.state == TransferState.RUNNING
        }

    /** 正在跑的任务的存档键：「已下载」页据此把进行中的条目从「未完成」里剔掉。主线程调用。 */
    fun runningKeys(): Set<String> = jobs.values
            .filter { it.state == TransferState.QUEUED || it.state == TransferState.RUNNING }
            .mapNotNull { it.resumeKey }
            .toSet()

    /** 普通后台动作（上传、删除、改名、新建、复制、移动…）。 */
    fun enqueue(context: Context, host: String, label: String,
                done: (Any?) -> Unit = { }, body: (SmbClient.Progress) -> Any?) {
        submit(context, Job(nextId++, host, label, body, null, done,
                context.applicationContext))
    }

    /** 可续传的下载。失败/取消后 [retry] 会从已写入的字节继续。 */
    fun enqueueDownload(context: Context, host: String, label: String, spec: DownloadSpec,
                        doneBytes: Long = 0L, done: (Any?) -> Unit = { }) {
        val job = Job(nextId++, host, label, { null }, spec, done,
                context.applicationContext)
        // 从存档里恢复的任务要接着上次的字节数往下写
        job.doneBytes = doneBytes.coerceAtLeast(0L)
        submit(context, job)
    }

    /**
     * 接着一条存档记录继续下。
     *
     * [client] 必须由调用方在**工作线程**上取好：这台机器可能早就没连接了，新建一条会走网络，
     * 而这个方法要在主线程调用（任务表只在主线程改）。真机上就是在这里主线程登录，直接吃了
     * 一个 NetworkOnMainThreadException。
     */
    fun resume(context: Context, record: UnfinishedDownload, sink: DownloadSink,
               client: SmbClient) {
        // 连点两下「继续」会让两条任务同时往一个文件里写
        if (jobs.values.any { it.resumeKey == record.key &&
                        (it.state == TransferState.QUEUED || it.state == TransferState.RUNNING) }) {
            return
        }
        enqueueDownload(context, record.host, record.label,
                DownloadSpec(client, record.path, sink), record.doneBytes)
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel?.set(true)
    }

    /** 重试一条失败/取消的下载：从已落盘处继续。 */
    fun retry(context: Context, id: Long) {
        val job = jobs[id] ?: return
        if (job.download == null || job.state == TransferState.RUNNING ||
                job.state == TransferState.QUEUED) {
            return
        }
        job.cancel.set(false)
        job.state = TransferState.QUEUED
        job.message = null
        job.startedAt = 0
        job.finishedAt = 0
        pin(context, job.host)
        SmbTransferService.ensure(context)
        publish()
        start(job)
    }

    /** 清掉已完成/失败/取消的记录；失败下载的半截文件也一并删掉。 */
    fun clearFinished() {
        val finished = jobs.values.filter {
            it.state == TransferState.DONE || it.state == TransferState.FAILED ||
                    it.state == TransferState.CANCELED
        }
        for (job in finished) {
            if (job.state != TransferState.DONE) {
                job.download?.sink?.discard()
                // 用户明确丢掉这条任务：存档也要一起清，否则下次启动还会冒出来
                job.forgetResume()
            }
            jobs.remove(job.id)
        }
        publish()
    }

    private fun submit(context: Context, job: Job) {
        pin(context, job.host)
        SmbTransferService.ensure(context)
        jobs[job.id] = job
        trim()
        publish()
        start(job)
    }

    private fun start(job: Job) {
        pool.execute {
            main.post {
                if (!job.cancel.get() && job.state == TransferState.QUEUED) {
                    job.state = TransferState.RUNNING
                    job.startedAt = SystemClock.elapsedRealtime()
                    publish()
                }
            }
            // 开工前先落一条存档：真要在第一个字节之前被杀，也能从 0 接着下
            job.persistResume(true)
            val progress = object : SmbClient.Progress {
                override fun isCancelled(): Boolean = job.cancel.get()

                override fun onProgress(done: Long, total: Long) {
                    job.doneBytes = done
                    job.total = total
                    job.persistResume(false)
                    // 每 64 KB 回一次会让整页快照一秒重建几十遍；按 100 ms 推，最后一个字节必推
                    val now = SystemClock.elapsedRealtime()
                    if (now - job.lastPush < PROGRESS_PUSH_MS &&
                            (total <= 0 || done < total)) {
                        return
                    }
                    job.lastPush = now
                    main.post { publish() }
                }
            }
            var value: Any? = null
            var failure: String? = null
            try {
                // 下载走 DownloadSpec（起点取自任务自己记录的 doneBytes，重试时天然续传）；
                // 其它动作就是一段普通闭包
                value = job.download?.run(job.doneBytes, progress) ?: job.body(progress)
            } catch (e: Exception) {
                failure = job.describe(e)
            }
            val outcome = value
            val error = failure
            val cancelled = job.cancel.get()
            main.post {
                job.state = when {
                    cancelled -> TransferState.CANCELED
                    error != null -> TransferState.FAILED
                    else -> TransferState.DONE
                }
                // 只有真正完成才把进度顶到 100%。**取消/失败时不能顶**：
                // doneBytes 同时是续传起点，顶成 total 会让重试从文件末尾开始读，
                // 瞬间「完成」而文件其实只有一半（真机上就是这么丢掉 700 MB 的）
                if (job.state == TransferState.DONE && job.total > 0) {
                    job.doneBytes = job.total
                }
                job.message = error
                job.finishedAt = SystemClock.elapsedRealtime()
                if (job.state == TransferState.DONE) {
                    job.forgetResume()
                } else {
                    // 失败/取消也记一次：这次活着的重试走 retry，下次启动走存档
                    job.persistResume(true)
                }
                publish()
                unpin(job.host)
                if (job.state == TransferState.DONE) {
                    job.done(outcome)
                }
            }
        }
    }

    /** 把连接钉住：任务要活过发起它的页面。 */
    private fun pin(context: Context, host: String) {
        val refs = hostPins[host] ?: 0
        if (refs == 0) {
            runCatching { SmbHub.acquire(context, host, host) }
        }
        hostPins[host] = refs + 1
    }

    private fun unpin(host: String) {
        val refs = (hostPins[host] ?: 1) - 1
        if (refs <= 0) {
            hostPins.remove(host)
            SmbHub.release(host)
        } else {
            hostPins[host] = refs
        }
    }

    /** 完成的任务最多留 [MAX_JOBS] 条，超出的从最老的开始丢。 */
    private fun trim() {
        if (jobs.size <= MAX_JOBS) {
            return
        }
        val removable = jobs.values.filter {
            it.state == TransferState.DONE || it.state == TransferState.FAILED ||
                    it.state == TransferState.CANCELED
        }
        for (job in removable) {
            if (jobs.size <= MAX_JOBS) {
                break
            }
            jobs.remove(job.id)
        }
    }

    private fun publish() {
        _views.value = jobs.values.reversed().map { it.view() }
        onChange?.invoke()
    }
}
