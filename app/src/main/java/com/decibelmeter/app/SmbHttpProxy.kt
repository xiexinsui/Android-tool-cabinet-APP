package com.decibelmeter.app

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机回环 HTTP 服务：把一个 SMB 文件按 Range 暴露成 `http://127.0.0.1:随机端口/…` 给播放器。
 *
 * 为什么要这一层，而不是直接把流喂给 Media3：视频容器（MP4/MKV）的索引表常在文件末尾，
 * 播放器一定会**跳着读**，拖动进度更是如此。我们手里只有 [SmbRange] 这种「按偏移读」的原语，
 * 而 Media3 能随机读的数据源只有 `MediaDataSource`（API 31 起才有）与 HTTP。用一个只在
 * 127.0.0.1 上监听的极小 HTTP/1.1 服务，minSdk 24 起就能拿到完整的「边取边播 + 任意拖动」，
 * 而且这条路是所有播放器（连系统 VideoView 都算）通吃的。
 *
 * 三个刻意的取舍：
 *  * **只绑 127.0.0.1**：监听 0.0.0.0 等于给同网段的别的机器开一个能读你共享的口子。
 *  * URL 里带一次性 [token]：手机上其它进程同样能连回环，没有这道门槛就等于随便谁都能读。
 *  * 每个连接各开一条 [SmbRange]：播放器会并发开好几条，共用一个句柄会读到错位的字节。
 *
 * 只服务一个文件、随播放页一起 stop()，不做成全局常驻服务——生命周期跟着页面走，退出即关，
 * 不留监听端口给别人复用。
 */
class SmbHttpProxy private constructor(
        private val link: SmbLink,
        private val path: String,
        private val name: String,
        private val token: String,
        private val server: ServerSocket,
        private val pool: ExecutorService,
        private val stopped: AtomicBoolean,
) {

    /** 交给 Media3 的地址。文件名放最后一段，只为了让日志和错误提示看得出播的是哪个文件。 */
    val url: String
        get() = "http://127.0.0.1:" + server.localPort + "/" + token + "/" + Uri.encode(name)

    /** 幂等：重复调用只会真正关一次。关之后接受线程退出、工作线程池收掉。 */
    /** 还在服务中（stop 之后为 false）。前台服务据此判断要不要另起一个代理。 */
    val alive: Boolean
        get() = !stopped.get()

    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun runAcceptLoop() {
        while (!stopped.get()) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                // stop() 关掉监听必然走到这里，是正常退出
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: RuntimeException) {
                // 池子已关（页面正在退出）：这条连接直接丢掉即可
                runCatching { client.close() }
            }
        }
    }

    /** 最近一次被客户端请求的时间：前台服务据此判断「播放器还在不在用」。 */
    @Volatile
    var lastAccess = System.currentTimeMillis()
        private set

    private fun handle(client: Socket) {
        lastAccess = System.currentTimeMillis()
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val request = parse(readRequest(socket.getInputStream()))
                if (request == null) {
                    respond(socket.getOutputStream(), "400 Bad Request", -1, null, 0)
                    return
                }
                if (!request.get && !request.head) {
                    respond(socket.getOutputStream(), "405 Method Not Allowed", -1, null, 0)
                    return
                }
                if (!request.path.contains("/$token/")) {
                    respond(socket.getOutputStream(), "403 Forbidden", -1, null, 0)
                    return
                }
                transfer(socket, request)
            } catch (e: IOException) {
                // 播放器提前断开（用户退出播放页、seek 时关掉旧连接）是常态，不值得上报
            } catch (e: InterruptedIOException) {
                // 同上，超时被 shutdownNow 打断
            }
        }
    }

    private fun transfer(socket: Socket, request: Request) {
        val out = socket.getOutputStream()
        var source: SmbRange? = null
        try {
            source = link.openRange(path)
            val total = source.size
            if (total <= 0) {
                respond(out, "502 Bad Gateway", -1, null, 0)
                return
            }
            val range = request.range
            val start = if (range == null) 0L else range[0]
            val end = if (range == null || range[1] < 0) total - 1 else minOf(range[1], total - 1)
            if (start >= total || start > end) {
                // 播放器探长度时会先问一个越界范围，416 + 总长度才是它要的答复
                respond(out, "416 Requested Range Not Satisfiable", total,
                        "bytes */$total", 0)
                return
            }
            respond(out, if (range == null) "200 OK" else "206 Partial Content", total,
                    if (range == null) null else "bytes $start-$end/$total", end - start + 1)
            if (request.head) {
                return
            }
            pipe(source, start, end - start + 1, out)
        } catch (e: IOException) {
            // 半路断流：已发出去的头部没法收回，剩下的字节丢了由播放器自己重试
        } finally {
            runCatching { source?.close() }
        }
    }

    /** 按偏移搬 [length] 字节到输出流。每块 [CHUNK]，够把百兆网口跑满又不至于堆内存。
     *
     * 64 KB → 256 KB 是因为这条流是给播放器用的：播放器读得比拷贝快，64 KB 一次意味着
     * 每 64 KB 就要等一个 SMB 往返，1080p 高码率片子在拖动后容易卡一下。 */
    private fun pipe(source: SmbRange, start: Long, length: Long, out: OutputStream) {
        val buffer = ByteArray(CHUNK)
        var done = 0L
        while (done < length && !stopped.get()) {
            val want = minOf(CHUNK.toLong(), length - done).toInt()
            val read = source.readAt(start + done, buffer, 0, want)
            if (read <= 0) {
                break
            }
            out.write(buffer, 0, read)
            done += read
        }
        out.flush()
    }

    /**
     * 写响应头。[body] 是要跟着发的字节数（错误答复传 0）。
     *
     * `Accept-Ranges: bytes` 是这一整套的意义所在：没有它播放器就知道不能拖，会把整个文件
     * 当成一次顺序流读下来。
     */
    private fun respond(out: OutputStream, status: String, total: Long,
                        contentRange: String?, body: Long) {
        val text = StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(FileUtil.mimeOf(name)).append("\r\n")
                .append("Content-Length: ").append(body).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) {
            text.append("Content-Range: ").append(contentRange).append("\r\n")
        }
        if (total > 0) {
            text.append("X-File-Size: ").append(total).append("\r\n")
        }
        text.append("Connection: close\r\n\r\n")
        val bytes = text.toString().toByteArray()
        try {
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            // 客户端已走
        }
    }

    /** 一次请求解析出来的三件事：方法、路径、Range。 */
    private class Request(val get: Boolean, val head: Boolean, val path: String,
                          val range: LongArray?)

    private fun parse(raw: String): Request? {
        val lines = raw.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim()
        if (requestLine.isNullOrEmpty()) {
            return null
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            return null
        }
        val method = parts[0].uppercase(Locale.US)
        if (method != "GET" && method != "HEAD") {
            return Request(false, false, parts[1], null)
        }
        var start = 0L
        var end = -1L
        var hasRange = false
        for (index in 1 until lines.size) {
            val line = lines[index]
            val value = line.substringAfter(':', "").trim()
            if (line.startsWith("Range", ignoreCase = true)) {
                val spec = value.removePrefix("bytes=").substringBefore(',')
                val dash = spec.indexOf('-')
                if (dash < 0) {
                    continue
                }
                val fromText = spec.substring(0, dash).trim()
                val toText = spec.substring(dash + 1).trim()
                start = fromText.toLongOrNull() ?: continue
                if (toText.isNotEmpty()) {
                    end = toText.toLongOrNull() ?: -1L
                }
                hasRange = true
            }
        }
        // `bytes=-512` 这种「尾部若干字节」的写法：起点未知，用 -1 交给调用方按总长度折算
        if (hasRange && start == 0L && end < 0) {
            hasRange = false
        }
        return Request(method == "GET", method == "HEAD", parts[1],
                if (hasRange) longArrayOf(start, end) else null)
    }

    /** 读到请求头结束（连续 CRLFCRLF）为止；超过上限就当恶意/异常请求丢掉。 */
    private fun readRequest(input: InputStream): String {
        val collected = ByteArrayOutputStream()
        var matched = 0
        while (collected.size() < MAX_HEADER) {
            val byte = input.read()
            if (byte < 0) {
                break
            }
            collected.write(byte)
            // 逐字节推进状态机，避免每读一字节就把整个头拷一遍
            matched = when {
                byte == '\r'.code && matched % 2 == 0 -> matched + 1
                byte == '\n'.code && matched % 2 == 1 -> matched + 1
                else -> 0
            }
            if (matched >= 4) {
                break
            }
        }
        return collected.toString("ISO-8859-1")
    }

    companion object {

        private const val CHUNK = 256 * 1024
        private const val MAX_HEADER = 8 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000

        /**
         * 起服务。必须在工作线程调用——[SmbLink.openRange] 会走网络，构造本身也要占一个端口。
         *
         * 用完一定要 [stop]：端口和 SMB 连接都挂在返回的对象上。
         */
        @Throws(IOException::class)
        fun start(link: SmbLink, path: String, name: String): SmbHttpProxy {
            val stopped = AtomicBoolean(false)
            val token = "%x".format(System.nanoTime())
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            val pool = Executors.newCachedThreadPool()
            val proxy = SmbHttpProxy(link, path, name, token, server, pool, stopped)
            val accept = Thread(proxy::runAcceptLoop)
            accept.isDaemon = true
            accept.name = "smb-http-proxy"
            accept.start()
            return proxy
        }
    }
}
