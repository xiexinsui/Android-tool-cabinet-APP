package com.decibelmeter.app

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.Executors

/**
 * PDF 的首页缩略图。
 *
 * 与视频首帧不同，这条路不需要回环 HTTP：[SmbPdf] 已经能把缓存文件交给 `PdfRenderer`
 * 渲第一页，而「整份文件落进 [SmbSourceCache]」这件事本来就要做——用户点开 PDF 时那份缓存
 * 直接复用，等于缩略图是白送的。
 *
 * 超过 [SmbSourceCache.THUMB_CAP] 的大 PDF 不取缩略图：为一张网格图拉 60 MB 不划算，
 * 界面上退回显示「PDF」角标。
 */
object SmbPdfThumbs {

    /** 缩略图宽度。网格一列 104 dp，2~3 倍屏下 240 px 足够。 */
    private const val WIDTH = 240

    private val memory = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val workers = Executors.newFixedThreadPool(2)
    private val pending = Collections.synchronizedSet(HashSet<String>())

    /** 命中缓存直接回调，否则后台渲染一次。回调在任意线程。 */
    fun request(context: Context, link: SmbLink, path: String, key: String,
                onReady: (Bitmap?) -> Unit) {
        memory.get(key)?.let {
            onReady(it)
            return
        }
        if (!pending.add(key)) {
            return
        }
        val app = context.applicationContext
        workers.execute {
            val page = try {
                renderFirstPage(app, link, path, key)
            } catch (e: Exception) {
                null
            }
            pending.remove(key)
            if (page != null) {
                memory.put(key, page)
            }
            onReady(page)
        }
    }

    fun clear() {
        memory.evictAll()
    }

    private fun renderFirstPage(context: Context, link: SmbLink, path: String,
                                key: String): Bitmap? {
        var file = SmbSourceCache.get(context, key)
        if (file == null) {
            val range = link.openRange(path)
            try {
                if (range.size > SmbSourceCache.THUMB_CAP) {
                    return null
                }
                file = SmbSourceCache.put(context, key, range, SmbSourceCache.THUMB_CAP)
            } finally {
                runCatching { range.close() }
            }
        }
        val pdf = SmbPdf.open(file)
        return try {
            if (pdf.pageCount <= 0) null else pdf.render(0, WIDTH)
        } finally {
            runCatching { pdf.close() }
        }
    }
}
