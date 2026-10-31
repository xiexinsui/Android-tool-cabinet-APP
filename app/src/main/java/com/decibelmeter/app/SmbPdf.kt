package com.decibelmeter.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 在应用内看 PDF。
 *
 * 用系统的 [android.graphics.pdf.PdfRenderer]（API 21 起自带）而不是引第三方库：它渲染出来
 * 就是 Bitmap，Compose 直接能画，而工程已经因为 Compose/Coil/Media3 涨了一大截体积，
 * 为一个「看 PDF」再加一个 PDF 库不值得。
 *
 * 代价写在接口上：`PdfRenderer` 只接受**可 seek 的 FileDescriptor**，管道流（pipe）不行，
 * 所以必须先把整份 PDF 落到缓存里才能开始翻。这就是 [open] 要带进度回调的原因——
 * 一个 40 MB 的扫描件下载完才能看第一页，不给出进度用户会以为卡住了。
 */
class SmbPdf private constructor(
        private val renderer: PdfRenderer,
        private val descriptor: ParcelFileDescriptor,
        private val file: File,
) {

    val pageCount: Int = renderer.pageCount

    /** 前若干页探到的宽高比，[ratioAt] 用它撑版面。 */
    private val ratios: List<Float> = try {
        (0 until minOf(pageCount, PROBE_LIMIT)).map { index ->
            renderer.openPage(index).use { page ->
                if (page.height <= 0) 0.707f else page.width.toFloat() / page.height.toFloat()
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 第 [index] 页的宽高比。
     *
     * 只在前 [PROBE_LIMIT] 页真的探过（探测要开页，几百页的扫描件全开一遍要好几秒），
     * 后面的页沿用一个探到的比例；一个都没探到就按 A4 竖版算。撑住版面用的，差一点不影响正确性。
     */
    fun ratioAt(index: Int): Float = ratios.getOrNull(index) ?: ratios.firstOrNull() ?: 0.707f

    private val lock = Any()

    /**
     * 渲染第 [index] 页到指定宽度。
     *
     * 必须串行：`PdfRenderer` 明确不允许同时打开两页（真同时调会抛 IllegalStateException），
     * 而 Compose 的懒加载列表完全可能一次要两页，所以整把锁锁住。
     */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (index < 0 || index >= pageCount) {
            return null
        }
        try {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width)
                        .toInt().coerceIn(1, MAX_DIMENSION)
                val safeWidth = widthPx.coerceAtLeast(1).coerceAtMost(MAX_DIMENSION)
                val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
                // PDF 页面背景是透明的，不铺白底的话深色主题下等于看不见
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    /** 缓存文件名，出错提示里给用户看清楚是哪份文件。 */
    val cachedName: String get() = file.name

    companion object {

        /** 探测页宽的比例时最多开这么多页：几百页的扫描件全开一遍要好几秒。 */
        private const val PROBE_LIMIT = 24
        private const val MAX_DIMENSION = 4096

        /**
         * 从缓存文件打开。文件必须已经完整落地（见类注释里对可 seek 的说明）。
         */
        @Throws(IOException::class)
        fun open(file: File): SmbPdf {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return SmbPdf(PdfRenderer(descriptor), descriptor, file)
            } catch (e: Exception) {
                descriptor.close()
                throw IOException(if (e.message.isNullOrBlank()) "不是有效的 PDF" else e.message, e)
            }
        }

        /**
         * 取整个文件到缓存并打开。必须在工作线程调用。
         *
         * @param onProgress 已下载字节与总字节（总长未知时是 -1）
         */
        @Throws(IOException::class)
        fun fetch(context: Context, link: SmbLink, path: String, key: String,
                  limit: Long, onProgress: (Long, Long) -> Unit): SmbPdf {
            var file = SmbSourceCache.get(context, key)
            if (file == null) {
                val opened = link.openRange(path)
                try {
                    val total = opened.size
                    if (total > limit) {
                        throw IOException(context.getString(R.string.smb_error_too_big,
                                FileUtil.humanSize(total), FileUtil.humanSize(limit)))
                    }
                    file = SmbSourceCache.put(context, key, opened, limit, onProgress)
                } finally {
                    runCatching { opened.close() }
                }
            }
            return open(file)
        }
    }
}

/**
 * 文本预览的编码判断。
 *
 * 为什么不能直接 `String(bytes, UTF_8)`：Kotlin 的构造对非法字节是替换成 U+FFFD，不报错，
 * 于是局域网里大量 GBK 的中文日志会显示成一堆豆腐块，而且没有任何线索告诉用户「这不是编码
 * 问题，是没猜对」。这里用 [CodingErrorAction.REPORT] 让严格解码真的抛出来，按
 * UTF-8 → GB18030 → Big5 的顺序试，最后才退回 Latin-1（它接受任意字节，永远"成功"）。
 *
 * BOM 单独处理：带 BOM 的 UTF-8 文件在 Windows 共享上很常见。
 */
object TextDecode {

    private val CANDIDATES = listOf("UTF-8", "GB18030", "Big5", "ISO-8859-1")

    /** @return 解出来的文本与实际用的字符集名 */
    fun decode(bytes: ByteArray): Pair<String, String> {
        val body = stripBom(bytes)
        for (name in CANDIDATES) {
            val text = tryStrict(body, name)
            if (text != null) {
                return text to name
            }
        }
        return String(body) to "UTF-8"
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte()
                && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }

    private fun tryStrict(bytes: ByteArray, name: String): String? {
        return try {
            val charset = Charset.forName(name)
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
        } catch (e: Exception) {
            null
        }
    }
}
