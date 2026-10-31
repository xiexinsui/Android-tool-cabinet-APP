package com.decibelmeter.app

import org.codelibs.jcifs.smb.SmbRandomAccess
import java.io.Closeable
import java.io.IOException

/**
 * 「按偏移读一个远端文件」的能力，预览三条路（缩略图、边取边播、PDF 分页）都建立在它上面。
 *
 * 为什么不能继续用 [SmbClient.download]：那个接口只会从头顺序拉到尾。播放器要能拖动就得
 * 随机跳读，PDF 要翻到第 40 页也不能先把前 39 页传完，缩略图更不该为了看一张 2 MB 的图标
 * 把整张原图（可能 20 MB）拉下来。
 *
 * 每个实例代表**一条已打开的文件句柄**，不是线程安全的：一个实例只在一个线程里用，
 * 需要并发读同一个文件（播放器就会这样）时各开一条。
 */
interface SmbRange : Closeable {

    /** 远端文件字节数；未知时为 -1。 */
    val size: Long

    /**
     * 从 [offset] 起最多读 [length] 字节到 [dst] 的 [dstOffset] 处。
     *
     * @return 实际读到的字节数，读到文件末尾时可能小于 [length]，已到末尾返回 -1
     */
    fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int

    /**
     * 从 [offset] 起读满 [length] 字节（不足则抛异常）。
     *
     * 默认实现把「短读」这个容易忘的处理收在一处：底层每次能返回多少字节取决于协商出来的
     * IO 缓冲大小，直接信任单次返回值会在大偏移读取时悄悄少读。
     */
    fun readFully(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = readAt(offset + done, dst, dstOffset + done, length - done)
            if (read < 0) {
                if (done == 0) {
                    throw IOException("偏移 $offset 处没有数据")
                }
                return
            }
            done += read
        }
    }
}

/**
 * 把远端文件的一段区间伪装成 [InputStream]，到 [limit] 就 EOF。
 *
 * EOF 必须限死：多读一个字节就会顺着跑到下一条数据上（解压时是下一条条目，跨机搬运时是
 * 下一个文件），报出来的错完全看不出原因。
 */
internal class SmbRangeStream(
        private val source: SmbRange,
        private val start: Long,
        private val limit: Long,
) : java.io.InputStream() {

    private var position = 0L

    override fun available(): Int = (limit - position).coerceAtLeast(0L).toInt()

    override fun read(): Int {
        if (position >= limit) {
            return -1
        }
        val single = ByteArray(1)
        val read = source.readAt(start + position, single, 0, 1)
        if (read <= 0) {
            return -1
        }
        position += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        if (position >= limit || length <= 0) {
            return -1
        }
        val want = minOf(length.toLong(), limit - position).toInt()
        val read = source.readAt(start + position, dst, offset, want)
        if (read <= 0) {
            return -1
        }
        position += read
        return read
    }

    override fun skip(count: Long): Long {
        val move = minOf(count, limit - position).coerceAtLeast(0L)
        position += move
        return move
    }
}

/**
 * jcifs-ng 实现（默认引擎）。
 *
 * 走 `SmbResource.openRandomAccess("r")`：它复用 [SmbLink] 里那个已经登录好的
 * `CIFSContext`，因此连接是按主机的一个池，不是每张缩略图一次 NTLM 握手。
 */
internal class JcifsRange(private val link: SmbLink, private val path: String) : SmbRange {

    private val access: SmbRandomAccess =
            link.client.resource(path, false).openRandomAccess("r")
    private var closed = false

    override val size: Long
        get() = try {
            access.length()
        } catch (e: Exception) {
            -1L
        }

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        access.seek(offset)
        return access.read(dst, dstOffset, length)
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            access.close()
        } catch (e: Exception) {
            // 底层连接由池子统一收，句柄关失败没有可挽回的
        }
    }
}
