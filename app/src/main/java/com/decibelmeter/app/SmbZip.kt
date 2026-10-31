package com.decibelmeter.app

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 直接在共享上读的 ZIP。
 *
 * 为什么不用现成的 [java.util.zip.ZipFile]：它要一个本地 File 或 RandomAccessFile，而字节还在
 * 远端。那为什么不用 [java.util.zip.ZipInputStream]：它只能从头顺序扫，「列一个 4 GB 的包」就
 * 等于先把 4 GB 拉下来。ZIP 的条目表在文件末尾（中央目录 + EOCD），读那几十 KB 就能列出全部
 * 条目，之后只取被选中那一条的字节——这才叫在线浏览。
 *
 * 必须在工作线程构造：整个解析过程都在走网络。
 */
class SmbZip private constructor(
        private val source: SmbRange,
        val entries: List<Entry>,
) {

    /** 中央目录里的一条。[name] 一律用 '/' 分隔（包里常见的 `\` 已经换算过）。 */
    class Entry(
            val name: String,
            val directory: Boolean,
            val size: Long,
            val compressedSize: Long,
            val method: Int,
            val localOffset: Long,
            val modified: Long,
    ) {
        val displayName: String get() = name.trimEnd('/').substringAfterLast('/')

        /**
         * 能不能安全地解出去。
         *
         * Zip Slip：包里塞一条 `../../Windows/startup/x.exe`，照着名字建路径就写到目标目录外面
         * 去了。所以 `..`、绝对路径、盘符一律拒绝，而不是只是警告一句。
         */
        val safe: Boolean
            get() {
                if (name.isEmpty() || name.startsWith("/")) {
                    return false
                }
                return name.split('/').none { it == ".." }
            }
    }

    /** 一次解压的结果。 */
    data class Summary(val written: Int, val skipped: Int)

    /** 打开一个条目的数据流（已解压）。调用方负责 close。 */
    @Throws(IOException::class)
    fun open(entry: Entry): InputStream {
        val local = bufferAt(entry.localOffset, LOCAL_HEADER_SIZE)
        if (local.getInt(0) != LOCAL_SIG) {
            throw IOException("包里有一条损坏的条目：" + entry.displayName)
        }
        // 本地头里的名字/扩展长度才是数据起点依据：它们可以和中央目录里的不一样（ZIP64 就会）
        val nameLength = local.getShort(26).toInt() and 0xFFFF
        val extraLength = local.getShort(28).toInt() and 0xFFFF
        val dataStart = entry.localOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
        val limit = if (entry.method == METHOD_STORED) entry.size else entry.compressedSize
        val raw = SmbRangeStream(source, dataStart, limit)
        return when (entry.method) {
            METHOD_STORED -> raw
            // ZIP 里的 deflate 流是 raw 的（没有 zlib 那 2 字节头），必须显式 nowrap=true：
            // InflaterInputStream 的单参构造在设备上按 zlib 头解，喂它 raw 流只会报
            // 「incorrect header check」（真机上解压就是这么失败的）
            METHOD_DEFLATED -> ZipInflaterStream(raw)
            else -> throw IOException("这个包用了不支持的压缩方式（method=" + entry.method + "）")
        }
    }

    /**
     * 把若干条目解到共享里的 [dirPath]（以 '/' 结尾），保持包内的目录结构。
     *
     * 重名走 [SmbClient.writeFile] 的避让，绝不覆盖；带 `..` 的条目计进 skipped 直接丢掉。
     */
    @Throws(IOException::class)
    fun extractTo(client: SmbClient, dirPath: String, picked: List<Entry>,
                  progress: SmbClient.Progress): Summary {
        val total = picked.filter { !it.directory && it.safe }.sumOf { it.size }
        var done = 0L
        var written = 0
        var skipped = 0
        // 建过的目录记下来：一个包里几百个文件常常同在一个目录下，每解一个都去 exists + mkdir
        // 就是几百次白跑的往返
        val created = HashSet<String>()
        for (entry in picked) {
            if (progress.isCancelled()) {
                break
            }
            if (entry.directory || !entry.safe) {
                skipped++
                continue
            }
            val parent = entry.name.substringBeforeLast('/', "")
            val targetDir = if (parent.isEmpty()) dirPath else dirPath + parent + "/"
            if (parent.isNotEmpty() && created.add(targetDir)) {
                client.mkdirs(targetDir)
            }
            val input = open(entry)
            try {
                val base = done
                client.writeFile(targetDir, entry.displayName, input, entry.size,
                        object : SmbClient.Progress {
                            override fun isCancelled(): Boolean = progress.isCancelled()

                            override fun onProgress(read: Long, sum: Long) {
                                // 单项进度换算成整批：面板上那条进度条要跨所有条目继续往前走
                                progress.onProgress(base + minOf(read, entry.size), total)
                            }
                        })
                written++
            } finally {
                runCatching { input.close() }
            }
            done += entry.size
        }
        return Summary(written, skipped)
    }

    fun close() {
        runCatching { source.close() }
    }

    private fun bufferAt(at: Long, length: Int): ByteBuffer {
        val bytes = ByteArray(length)
        source.readFully(at, bytes, 0, length)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val EOCD64_LOC_SIG = 0x07064b50
        private const val EOCD64_SIG = 0x06064b50
        private const val CD_SIG = 0x02014b50
        private const val LOCAL_SIG = 0x04034b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val FLAG_UTF8 = 0x800

        /** 中央目录最多读这么多：再大的包不是给人翻的，是镜像文件而已。 */
        private const val DIRECTORY_CAP = 24L * 1024 * 1024

        /** 末尾扫 EOCD 的窗口：EOCD 本身 22 字节，注释最长 64 KB。 */
        private const val TAIL_WINDOW = 66_000

        /**
         * 解析。顺序：末尾窗口里找 EOCD →（条目数或偏移越界时经 ZIP64 定位器拿真的目录位置）
         * → 读中央目录 → 逐条解析。
         */
        @Throws(IOException::class)
        fun open(source: SmbRange): SmbZip {
            val total = source.size
            if (total < 22) {
                throw IOException("这不是一个完整的 ZIP 文件")
            }
            val tailLength = minOf(total, TAIL_WINDOW.toLong()).toInt()
            val tail = ByteBuffer.wrap(ByteArray(tailLength).also {
                source.readFully(total - tailLength, it, 0, tailLength)
            }).order(ByteOrder.LITTLE_ENDIAN)
            var eocd = -1
            for (index in tail.limit() - 22 downTo 0) {
                if (tail.getInt(index) == EOCD_SIG) {
                    eocd = index
                    break
                }
            }
            if (eocd < 0) {
                throw IOException("找不到 ZIP 的目录记录（文件可能损坏，或者它本来就不是 zip）")
            }
            var count = tail.getShort(eocd + 10).toLong() and 0xFFFF
            var directoryOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            var directorySize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            if (count == 0xFFFFL || directoryOffset == 0xFFFFFFFFL ||
                    directorySize == 0xFFFFFFFFL) {
                val zip64 = readZip64(source, tail, eocd, total - tailLength)
                if (zip64 != null) {
                    count = zip64[0]
                    directoryOffset = zip64[1]
                    directorySize = zip64[2]
                }
            }
            if (directoryOffset <= 0 || directorySize <= 0 ||
                    directoryOffset + directorySize > total) {
                throw IOException("这个包的目录记录看着不对，解不开")
            }
            if (directorySize > DIRECTORY_CAP) {
                throw IOException("这个包的条目表有 " + FileUtil.humanSize(directorySize) +
                        "，太大：先整份下载到本机再解压")
            }
            val directory = ByteBuffer.wrap(ByteArray(directorySize.toInt()).also {
                source.readFully(directoryOffset, it, 0, it.size)
            }).order(ByteOrder.LITTLE_ENDIAN)
            val entries = ArrayList<Entry>(count.toInt().coerceIn(0, 200_000))
            var position = 0
            while (position + 46 <= directory.limit()) {
                if (directory.getInt(position) != CD_SIG) {
                    break
                }
                val flags = directory.getShort(position + 8).toInt() and 0xFFFF
                val method = directory.getShort(position + 10).toInt() and 0xFFFF
                val time = directory.getShort(position + 12).toInt() and 0xFFFF
                val date = directory.getShort(position + 14).toInt() and 0xFFFF
                var compressed = directory.getInt(position + 20).toLong() and 0xFFFFFFFFL
                var size = directory.getInt(position + 24).toLong() and 0xFFFFFFFFL
                val nameLength = directory.getShort(position + 28).toInt() and 0xFFFF
                val extraLength = directory.getShort(position + 30).toInt() and 0xFFFF
                val commentLength = directory.getShort(position + 32).toInt() and 0xFFFF
                var localOffset = directory.getInt(position + 42).toLong() and 0xFFFFFFFFL
                if (size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL ||
                        localOffset == 0xFFFFFFFFL) {
                    val extra = zip64Extra(directory, position + 46 + nameLength, extraLength)
                    if (extra != null) {
                        var slot = 0
                        // ZIP64 扩展字段里出现的顺序固定：原始大小 → 压缩后大小 → 本地头偏移，
                        // 而且只写「真的是 0xFFFFFFFF 的那几个」，所以要按上面的判断逐个取
                        if (size == 0xFFFFFFFFL && slot < extra.size) {
                            size = extra[slot++]
                        }
                        if (compressed == 0xFFFFFFFFL && slot < extra.size) {
                            compressed = extra[slot++]
                        }
                        if (localOffset == 0xFFFFFFFFL && slot < extra.size) {
                            localOffset = extra[slot]
                        }
                    }
                }
                val name = decodeName(directory.array(), position + 46, nameLength,
                        flags and FLAG_UTF8 != 0)
                if (name != null) {
                    entries.add(Entry(name.replace('\\', '/'), name.endsWith("/"), size,
                            compressed, method, localOffset, dosToMillis(date, time)))
                }
                position += 46 + nameLength + extraLength + commentLength
            }
            if (entries.isEmpty()) {
                throw IOException("这个包里没有条目")
            }
            return SmbZip(source, entries)
        }

        private fun decodeName(raw: ByteArray, at: Int, length: Int, utf8: Boolean): String? {
            if (length <= 0 || at < 0 || at + length > raw.size) {
                return null
            }
            val name = raw.copyOfRange(at, at + length)
            if (utf8) {
                return String(name, StandardCharsets.UTF_8)
            }
            // Windows 上打的包常用本地代码页写名字（GBK 是中文机器上最常见的一种），没置 UTF-8
            // 标志时按 UTF-8 硬解会得到一串乱码，所以先严格试 UTF-8，不行再退回 GBK
            return try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(name)).toString()
            } catch (e: CharacterCodingException) {
                try {
                    String(name, charset("GBK"))
                } catch (e2: Exception) {
                    String(name, StandardCharsets.ISO_8859_1)
                }
            }
        }

        /** ZIP64 的扩展信息字段（id=0x0001）里的 64 位值，按出现顺序给出。 */
        private fun zip64Extra(buffer: ByteBuffer, at: Int, length: Int): LongArray? {
            var position = at
            val end = (at + length).coerceAtMost(buffer.limit())
            while (position + 4 <= end) {
                val id = buffer.getShort(position).toInt() and 0xFFFF
                val size = buffer.getShort(position + 2).toInt() and 0xFFFF
                if (id == 0x0001 && position + 4 + size <= end) {
                    val values = ArrayList<Long>()
                    var cursor = position + 4
                    val stop = cursor + size
                    while (cursor + 8 <= stop) {
                        values.add(buffer.getLong(cursor))
                        cursor += 8
                    }
                    return values.toLongArray()
                }
                position += 4 + size
            }
            return null
        }

        /** 返回 [条目数, 中央目录偏移, 中央目录大小]；没有 ZIP64 记录时 null。 */
        private fun readZip64(source: SmbRange, tail: ByteBuffer, eocd: Int,
                              tailStart: Long): LongArray? {
            // 定位器固定在 EOCD 前面 20 字节
            if (eocd < 20 || tail.getInt(eocd - 20) != EOCD64_LOC_SIG) {
                return null
            }
            val offset = tail.getLong(eocd - 20 + 8)
            if (offset < 0 || offset > tailStart) {
                return null
            }
            val record = ByteBuffer.wrap(ByteArray(56).also {
                source.readFully(offset, it, 0, it.size)
            }).order(ByteOrder.LITTLE_ENDIAN)
            if (record.getInt(0) != EOCD64_SIG) {
                return null
            }
            // 记录里：总条目数在 32，中央目录大小在 40，中央目录偏移在 48
            return longArrayOf(record.getLong(32), record.getLong(48), record.getLong(40))
        }

        private fun dosToMillis(date: Int, time: Int): Long {
            if (date == 0 && time == 0) {
                return 0
            }
            return try {
                val calendar = Calendar.getInstance()
                calendar.clear()
                calendar.set(1980 + ((date shr 9) and 0x7FFF), ((date shr 5) and 0x0F) - 1,
                        date and 0x1F, (time shr 11) and 0x1F, (time shr 5) and 0x3F,
                        (time and 0x1F) * 2)
                calendar.timeInMillis
            } catch (e: Exception) {
                0L
            }
        }
    }
}

/**
 * 把远端文件的一段区间伪装成 [InputStream] 的实现见 [SmbRangeStream]——跨机搬运也用它，
 * 解压这里只是它的一个使用者。
 */

/**
 * 解 ZIP 条目的 inflate 流。
 *
 * 只为了显式给出 `Inflater(true)`：`InflaterInputStream` 的单参构造按 zlib 头去解，而包里的
 * 条目是 raw deflate。缓冲给 32 KB——每次 inflate 都要从共享拉一段，太小会让读循环变成
 * 「几十次 SMB 往返」。
 */
private class ZipInflaterStream(input: InputStream) :
        InflaterInputStream(input, Inflater(true), 32 * 1024)
