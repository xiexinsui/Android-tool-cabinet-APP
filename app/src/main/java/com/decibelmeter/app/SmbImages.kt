package com.decibelmeter.app

import android.content.Context
import android.os.SystemClock
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path.Companion.toPath

/**
 * smb:// 图片地址的生成与解析。
 *
 * 形状：`smb://<主机>/<逐段转义的路径>?i=<身份哈希>&v=<修改时间>&s=<字节数>&w=<取图意图>`
 *
 * 四个查询参数都不是装饰：
 *  * `i` 把「谁登录的」编进 key，换账号后不会看到上一个账号缓存下来的图；
 *  * `v` + `s` 让远端文件一改，缓存 key 就变——否则照片被覆盖后网格永远显示旧的；
 *  * `w` 区分「网格缩略图」和「全屏原图」，两者大小差一个数量级，共用一个 key 会互相顶掉。
 */
object SmbUris {

    /** 缩略图意图：受 [SmbSourceCache.THUMB_CAP] 限制，过大的文件不取。 */
    const val MODE_THUMB = "thumb"

    /** 全屏意图：不限大小，交解码器按目标尺寸降采样。 */
    const val MODE_FULL = "full"

    fun forFile(link: SmbLink, path: String, size: Long, modified: Long, mode: String): String {
        val builder = StringBuilder("smb://").append(link.host).append('/')
        for (segment in path.split('/')) {
            if (segment.isEmpty()) {
                continue
            }
            builder.append(android.net.Uri.encode(segment)).append('/')
        }
        // 路径末尾不要留斜杠：那是「目录」的约定，混进图片地址会让解码器以为在读目录
        val trimmed = builder.toString().trimEnd('/')
        val identity = Integer.toHexString(link.identity.hashCode())
        return trimmed + String.format(Locale.US,
                "?i=%s&v=%d&s=%d&w=%s", identity, modified, size, mode)
    }

    /** 解析出来的目标；参数缺失时按「全屏、无元数据」处理。 */
    class Target(val host: String, val path: String, val mode: String, val size: Long,
                 val raw: String)

    fun parse(uri: coil3.Uri): Target? {
        if (uri.scheme != "smb") {
            return null
        }
        val host = uri.authority ?: return null
        if (host.isEmpty()) {
            return null
        }
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
                .map { android.net.Uri.decode(it) }
        val query = uri.query ?: ""
        var mode = MODE_FULL
        var declared = -1L
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (key == "w") {
                mode = value
            } else if (key == "s") {
                declared = value.toLongOrNull() ?: -1L
            }
        }
        // raw 是整个 URI 字符串：它就是缓存 key 的全部来源，i/v/s 三个参数因此真的能起
        // 失效作用（远端一改文件，key 就变）
        return Target(host, segments.joinToString("/"), mode, declared, uri.toString())
    }
}

/**
 * 已取字节的本地缓存：`cacheDir/smb_preview/<key>.img`。
 *
 * 为什么自己写而不是交给我们刚接上的回环 HTTP：
 *  * Coil 3 的磁盘缓存只由 network 那套 fetcher 写入，自定义 fetcher 拿到的流**不会**自动进
 *    缓存（读过 3.6.3 的 EngineInterceptor 确认过），网格来回滚动会反复走 SMB 重传；
 *  * Media3 需要一个能 Range 随机读的 URI，回环服务正好；而解码图片只要顺序读全量字节，
 *    落到文件反而最省。
 * 两边共用这一份「原样字节」缓存，容量由 [SmbSettings.cacheLimitMb] 控制，
 * 超了按最后修改时间淘汰。
 */
object SmbSourceCache {

    /** 缩略图模式下最多取这么多字节；再大的文件直接显示占位图标，不为一张 120 px 的图拉 2 GB 电影。 */
    const val THUMB_CAP = 8L * 1024 * 1024

    /**
     * 全屏看图时允许整份取到本地的上限。
     *
     * 为什么必须有：解码一张图要么拿到全部字节、要么用降采样，而 Android 的降采样解码器
     * （ BitmapFactory 的 inSampleSize）需要能重复读同一份数据——流式只能先落盘。64 MB 是
     * 「RAW/超长截图还在范围内、视频和 ISO 镜像不进缓存」的分界。
     */
    const val FULL_CAP = 64L * 1024 * 1024

    private const val DIR = "smb_preview"
    private const val EXT = ".img"

    /** 两次全目录统计之间的最短间隔：统计本身要 stat 几千个文件，不能每写一张图就做一次。 */
    private const val TRIM_INTERVAL_MS = 30_000L

    fun file(context: Context, key: String): File {
        val dir = dir(context)
        return File(dir, hash(key) + EXT)
    }

    /** 命中就返回文件，否则 null。调用方要在工作线程。 */
    fun get(context: Context, key: String): File? {
        val file = file(context, key)
        return if (file.isFile && file.length() > 0) file else null
    }

    /**
     * 写入：先写 .part 再改名，中途失败或进程被杀都不会留下半个文件被当成命中。
     *
     * @param limit 最多取这么多字节（0＝不限）；PDF 这种「整份才看得了」的调用方需要进度，
     *              不然一个 40 MB 的扫描件下载期间界面就是纯静态
     * @return 落盘后的文件
     */
    @Throws(IOException::class)
    fun put(context: Context, key: String, source: SmbRange, limit: Long,
            onProgress: ((Long, Long) -> Unit)? = null): File {
        val dir = dir(context)
        val target = file(context, key)
        val temp = File(dir, target.name + ".part")
        val total = source.size
        val want = if (limit <= 0 || total < 0) total else minOf(total, limit)
        try {
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                onProgress?.invoke(0, want)
                while (want > 0 && done < want) {
                    val read = source.readAt(done, buffer, 0,
                            minOf(buffer.size.toLong(), want - done).toInt())
                    if (read <= 0) {
                        break
                    }
                    out.write(buffer, 0, read)
                    done += read
                    onProgress?.invoke(done, want)
                }
                out.flush()
            }
            if (!temp.renameTo(target)) {
                throw IOException("缓存文件写入失败：" + target.name)
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
        trim(context)
        return target
    }

    /** 上次真正扫目录的时间：每写一张缩略图就全目录统计一次，几千个文件之后会越来越慢。 */
    @Volatile
    private var lastTrim = 0L

    /** 按最后修改时间从旧到新删，直到总量不超过设置的上限。 */
    fun trim(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrim < TRIM_INTERVAL_MS) {
            return
        }
        lastTrim = now
        val limitBytes = SmbSettings(context).cacheLimitMb().toLong() * 1024 * 1024
        val files = dir(context).listFiles() ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
        }
        if (total <= limitBytes) {
            return
        }
        val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= limitBytes) {
                break
            }
            total -= file.length()
            file.delete()
        }
    }

    /** 清空（设置里的手动入口）。正在读的文件的句柄已经打开，删了也不影响本次解码。 */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun usedBytes(context: Context): Long {
        var total = 0L
        dir(context).listFiles()?.forEach { total += it.length() }
        return total
    }

    private fun dir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        if (!dir.isDirectory) {
            dir.mkdirs()
        }
        return dir
    }

    private fun hash(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
            StringBuilder(digest.size * 2).apply {
                for (byte in digest) {
                    append(String.format(Locale.US, "%02x", byte))
                }
            }.toString()
        } catch (e: Exception) {
            // MD5 一定有；真取不到就退化成转义后的原串，缓存顶多命中率下降
            text.filter { it.isLetterOrDigit() }.take(48)
        }
    }
}

/**
 * Coil 的 smb:// 取图器。
 *
 * 四条实现选择都写在代码里：
 *  * 只认 `smb` scheme，别的返回 null 让 Coil 继续往下找；
 *  * 用 [SmbHub.peek] 而不是 acquire——取图发生在滚动过程中，绝不该在这里弹登录框或重建连接，
 *    拿不到连接就按失败处理（界面显示占位图），用户回到浏览页自然会重新登录；
 *  * 缩略图有 [SmbSourceCache.THUMB_CAP] 上限、原图有 [SmbSourceCache.FULL_CAP] 上限，超了
 *    立刻报错而不是慢慢拉完：「滑相册卡住」比「某张大图没缩略图」糟糕得多；
 *  * 并发用信号量压到 [MAX_PARALLEL]。缩略图是几十张一起进来的，一条千兆链路上 20 个并发读
 *    互相抢 SMB  credits 与接收缓冲，实测比串行更慢，而且会把播放器那条连接饿死。
 */
internal class SmbFetcher(
        private val target: SmbUris.Target,
        private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val link = SmbHub.peek(target.host) ?: throw IOException("未连接到 " + target.host)
        val name = target.path.substringAfterLast('/')
        val key = target.raw
        val context = options.context
        SmbSourceCache.get(context, key)?.let { cached ->
            return result(cached, name, DataSource.DISK)
        }
        SEMAPHORE.withPermit {
            // 排队期间可能已经被人取过了（同一张图在网格里被复用、或用户来回滚）
            SmbSourceCache.get(context, key)?.let { cached ->
                return result(cached, name, DataSource.DISK)
            }
            var range: SmbRange? = null
            try {
                range = link.openRange(target.path)
                val declared = if (target.size > 0) target.size else range.size
                val cap = if (target.mode == SmbUris.MODE_THUMB) {
                    SmbSourceCache.THUMB_CAP
                } else {
                    SmbSourceCache.FULL_CAP
                }
                if (declared > cap) {
                    throw IOException(if (target.mode == SmbUris.MODE_THUMB) {
                        "文件过大（" + FileUtil.humanSize(declared) + "），不取缩略图"
                    } else {
                        "文件过大（" + FileUtil.humanSize(declared) + "），请用其他应用打开"
                    })
                }
                val written = SmbSourceCache.put(context, key, range, cap)
                range = null
                return result(written, name, DataSource.NETWORK)
            } finally {
                range?.safeClose()
            }
        }
    }

    private fun result(file: File, name: String, dataSource: DataSource): FetchResult {
        return SourceFetchResult(
                source = ImageSource(
                        file = file.absolutePath.toPath(),
                        fileSystem = options.fileSystem,
                ),
                mimeType = FileUtil.mimeOf(name),
                dataSource = dataSource,
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options,
                            imageLoader: ImageLoader): Fetcher? {
            val target = SmbUris.parse(data) ?: return null
            return SmbFetcher(target, options)
        }
    }

    private companion object {
        val SEMAPHORE = Semaphore(MAX_PARALLEL)
        const val MAX_PARALLEL = 4

        fun SmbRange.safeClose() {
            runCatching { close() }
        }
    }
}

/**
 * 全应用共用的图片加载器。
 *
 * Coil 默认的磁盘缓存这一层是关掉的：字节缓存在 [SmbSourceCache] 里自己管（同一份文件还要给
 * 播放器和 PDF 渲染用），Coil 只需要管内存里的解码结果。这样「占用空间/清空缓存」这件事
 * 只有一个地方要解释，不会出现两个缓存目录各自膨胀。
 */
object ToolboxImages {

    @Volatile
    private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader {
        loader?.let { return it }
        synchronized(this) {
            loader?.let { return it }
            val built = ImageLoader.Builder(context.applicationContext)
                    .diskCache(null)
                    .components { add(SmbFetcher.Factory(), coil3.Uri::class) }
                    // 缩略图是几十张一起进来的，并发压到 4：局域网里一条千兆链路上
                    // 20 个并发读反而互相抢缓冲，实测比串行还慢
                    .build()
            loader = built
            return built
        }
    }
}
