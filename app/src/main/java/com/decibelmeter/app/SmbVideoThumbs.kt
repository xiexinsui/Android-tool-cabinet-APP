package com.decibelmeter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 共享里视频文件的首帧缩略图。
 *
 * 走的是「回环 HTTP + [MediaMetadataRetriever]」这条最土但唯一在所有 API 上都成立的路：
 * 解首帧要求数据源能随机定位（MP4 的 moov 可能在文件尾），而我们能给的只有 [SmbRange]。
 * 系统没有暴露「把任意随机读接口接给播放器」的钩子（`MediaDataSource` 要 API 31），
 * 所以给每个要抽帧的文件临时起一个 [SmbHttpProxy]，抽完立刻关。
 *
 * 三条限制是有意的：
 *  * 并发压到 2：一次抽帧会读掉几十 KB 到几 MB，抢不过用户正在看的图；
 *  * 结果既进内存 [LruCache] 也进 [SmbSourceCache]（存成 JPEG），来回滚动和下次再进这个目录
 *    都不必重新抽；
 *  * 失败就当没有缩略图，界面上显示视频图标——一个抽不出帧的监控录像不该让整页报错。
 */
object SmbVideoThumbs {

    /** 抽帧时按这个宽度解码，网格用 256 px 足够，又不会为一张缩略图解出 4K 帧。 */
    private const val FRAME_WIDTH = 256

    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val workers = Executors.newFixedThreadPool(2)
    private val pending = Collections.synchronizedSet(HashSet<String>())

    /** 外部（预览页）也用它判断「这个键已经在算了」，避免两边重复起服务。 */
    fun has(key: String): Boolean = memory.get(key) != null

    /**
     * 取一帧。命中缓存直接返回，否则登记一次后台抽取并通过 [onReady] 回调（回调在任意线程）。
     *
     * @param key 与 [SmbUris.forFile] 生成的地址一致即可，缓存与去重都按它算
     */
    fun request(context: Context, link: SmbLink, path: String, name: String, key: String,
                onReady: (Bitmap?) -> Unit) {
        memory.get(key)?.let {
            onReady(it)
            return
        }
        val disk = SmbSourceCache.get(context, key)
        if (disk != null) {
            val fromDisk = decode(disk.absolutePath)
            if (fromDisk != null) {
                memory.put(key, fromDisk)
                onReady(fromDisk)
                return
            }
        }
        if (!pending.add(key)) {
            return
        }
        val app = context.applicationContext
        workers.execute {
            val frame = try {
                grab(app, link, path)
            } catch (e: Exception) {
                null
            }
            pending.remove(key)
            if (frame != null) {
                memory.put(key, frame)
                store(app, key, frame)
            }
            onReady(frame)
        }
    }

    /** 同步取（预览页给播放器做封面时用），没有就返回 null。 */
    fun peek(context: Context, key: String): Bitmap? {
        memory.get(key)?.let { return it }
        val disk = SmbSourceCache.get(context, key) ?: return null
        val frame = decode(disk.absolutePath) ?: return null
        memory.put(key, frame)
        return frame
    }

    fun clear() {
        memory.evictAll()
    }

    private fun grab(context: Context, link: SmbLink, path: String): Bitmap? {
        val name = path.substringAfterLast('/')
        var proxy: SmbHttpProxy? = null
        val retriever = MediaMetadataRetriever()
        try {
            proxy = SmbHttpProxy.start(link, path, name)
            val uri = proxy.url
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                retriever.setDataSource(context, Uri.parse(uri))
            } else {
                // API 29 才加上新式的 (Context, Uri) 重载；24~28 只有 (String, Map) 这条路，
                // 第二参数是附加 HTTP 头，这里不需要任何头
                retriever.setDataSource(uri, emptyMap())
            }
            val raw = retriever.frameAtTime ?: return null
            return if (raw.width <= FRAME_WIDTH) {
                raw
            } else {
                val ratio = FRAME_WIDTH.toFloat() / raw.width
                Bitmap.createScaledBitmap(raw, FRAME_WIDTH,
                        (raw.height * ratio).toInt().coerceAtLeast(1), true)
                        .also { if (it !== raw) raw.recycle() }
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
            proxy?.stop()
        }
    }

    private fun decode(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }

    /** 抽出来的帧存成 JPEG 而不是 PNG：位图大且是照片内容，JPEG 小一个数量级。 */
    private fun store(context: Context, key: String, frame: Bitmap) {
        try {
            val out = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val file = SmbSourceCache.file(context, key)
            file.outputStream().use { it.write(out.toByteArray()) }
        } catch (e: Exception) {
            // 存盘失败只影响下次重新抽帧，不影响这次显示
        }
    }
}
