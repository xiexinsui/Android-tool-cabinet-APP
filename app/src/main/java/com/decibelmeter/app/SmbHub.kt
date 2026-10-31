package com.decibelmeter.app

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbjFile
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一台机器的已登录会话：一个 [SmbClient]（元数据与传输）+ 按需开的随机读句柄。
 *
 * 为什么要有这一层：浏览页、缩略图、播放器、PDF 分页是四个不同的调用点，各自去建连接的话
 * 同一台 NAS 会被登录四五次——jcifs 的连接池是按 `CIFSContext` 分的，context 一换池子就换，
 * 翻相册时每次滑动都在重新握手。[SmbHub] 按主机把它们收成一条。
 */
class SmbLink internal constructor(
        val host: String,
        val displayName: String,
        val credential: CredentialStore.Credential,
        val settings: SmbSettings,
        val client: SmbClient,
) {

    /** 缓存 key 与回环 URL 用的身份串：换账号或换机器都会变，避免把别人的缩略图当成自己的。 */
    val identity: String
        get() = host + "|" + credential.user

    /**
     * 打开一个远端文件的随机读句柄。必须在工作线程调用。
     *
     * 引擎由 [SmbSettings.engine] 决定：默认 jcifs-ng；选 SMBJ 时只有这一步走 SMBJ，
     * 共享与目录枚举仍然用 jcifs-ng（SMBJ 没有 srvsvc NetrShareEnum，列不出共享名）。
     */
    @Throws(IOException::class)
    fun openRange(path: String): SmbRange {
        return if (settings.engine() == SmbSettings.ENGINE_SMBJ) {
            SmbjRange(this, path)
        } else {
            JcifsRange(this, path)
        }
    }
}

/** 缺少凭据时抛出，界面据此弹登录框而不是显示一句「失败」。 */
class SmbNeedCredential(val host: String) : IOException("没有凭据")

/**
 * 按主机复用 [SmbLink]，带引用计数。
 *
 * 计数是为了让「浏览页 + 预览页」同时持有同一台机器时不会互相踩：预览页退出不能把浏览页
 * 正在用的连接关掉。归零时才真正关，而且关的动作丢到后台线程——jcifs 的 close 会真的
 * 关 socket，主线程上跑就是 NetworkOnMainThreadException（这条在共享页已经实测过）。
 */
object SmbHub {

    private class Entry(val link: SmbLink) {
        var refs = 0
    }

    private val entries = HashMap<String, Entry>()
    private val closer = Executors.newSingleThreadExecutor()

    /**
     * 取一条连接，必要时新建。必须在工作线程调用（新建时会走名称解析与登录）。
     *
     * @throws SmbNeedCredential 这台机器还没有凭据
     */
    @Throws(IOException::class)
    fun acquire(context: Context, host: String, displayName: String): SmbLink {
        val credential = CredentialStore(context).get(host)
                ?: throw SmbNeedCredential(host)
        val settings = SmbSettings(context)
        val stale: SmbLink?
        val link: SmbLink
        synchronized(entries) {
            val existing = entries[host]
            if (existing != null && sameLogin(existing.link.credential, credential)) {
                existing.refs++
                return existing.link
            }
            // 凭据换过了（改过密码或从匿名切到账号）：旧连接必须丢，否则界面看到的是上一次的登录态
            stale = existing?.link
            entries.remove(host)
            // 建连接放在锁里：同一时刻只让一个线程去登录这台机器，宁可排队也不要建出两条
            link = SmbLink(host, displayName, credential, settings,
                    SmbClient(host, credential, settings))
            entries[host] = Entry(link).apply { refs = 1 }
        }
        stale?.let { doomed -> closer.execute { closeEverything(doomed) } }
        return link
    }

    /** 已经建好就返回（预览页据此判断能不能省掉一次登录）。 */
    fun peek(host: String): SmbLink? = synchronized(entries) { entries[host]?.link }

    /** 引用计数减一，归零后把这条连接关掉。 */
    fun release(host: String) {
        val doomed: SmbLink?
        synchronized(entries) {
            val entry = entries[host] ?: return
            entry.refs--
            if (entry.refs > 0) {
                return
            }
            entries.remove(host)
            doomed = entry.link
        }
        doomed?.let { closer.execute { closeEverything(it) } }
    }

    /** 换协议参数、换凭据后重建用：立刻丢掉，下一次 acquire 重新登录。 */
    fun invalidate(host: String) {
        val doomed: SmbLink?
        synchronized(entries) {
            doomed = entries.remove(host)?.link
        }
        if (doomed != null) {
            closer.execute { closeEverything(doomed) }
        }
    }

    private fun sameLogin(old: CredentialStore.Credential,
                          new: CredentialStore.Credential): Boolean {
        return old.user == new.user && old.password == new.password
    }

    /** SMBJ 的会话也要一起丢：它独立于 jcifs 的连接池，只关 SmbClient 会漏一条 TCP。 */
    private fun closeEverything(link: SmbLink) {
        SmbjPool.drop(link.host)
        try {
            link.client.close()
        } catch (e: Exception) {
            // 关不掉也只是连接晚点超时
        }
    }
}

/**
 * SMBJ 实现（[SmbSettings.ENGINE_SMBJ]，实验项）。
 *
 * 定位要说明白：SMBJ 在这里**只**负责文件字节读取。共享列表与目录枚举仍然由 jcifs-ng 提供，
 * 因为 SMBJ 到 0.15.0 都没有实现 srvsvc 的 NetrShareEnum（在 jar 里 grep 过确实没有），
 * 而且它连 SMB1 方言都不支持（SMB2Dialect 最低是 SMB_2_0_2）——开着 SMB1 兼容的用户切过来
 * 会直接连不上，设置对话框里对此有说明。
 */
internal class SmbjRange(link: SmbLink, path: String) : SmbRange {

    private val host = link.host
    private val file: SmbjFile
    private val cachedSize: Long

    init {
        // 任何一步失败都把整台机器的 SMBJ 会话丢掉：SMBJ 的 Session 在传输异常之后状态未定义，
        // 留着只会让后面每个请求都失败，不如让用户下一次点操作时重新登录
        val share = try {
            SmbjPool.diskShare(link, path.substringBefore('/'))
        } catch (e: Exception) {
            SmbjPool.drop(host)
            throw asIo(e)
        }
        // SMBJ 用反斜杠分隔，且路径是相对共享根的（共享名已经在上一步连掉了）
        val relative = path.substringAfter('/', "").trimEnd('/').replace('/', '\\')
        file = try {
            share.openFile(relative, EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        } catch (e: Exception) {
            SmbjPool.drop(host)
            throw asIo(e)
        }
        // length 是一次 RPC，读几百次缩略图时不能每次都问，所以固定在构造时取一次
        cachedSize = runCatching { file.length }.getOrDefault(-1L)
    }

    override val size: Long
        get() = cachedSize

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (cachedSize >= 0 && offset >= cachedSize) {
            return -1
        }
        // 单次请求切到协商的读缓冲量级：一次要得太多服务端只回它能给的那截，
        // 补齐由 SmbRange.readFully 的循环负责
        return file.read(dst, offset, dstOffset, minOf(length, MAX_CHUNK))
    }

    override fun close() {
        runCatching { file.close() }
    }

    private companion object {
        /**
         * 单次请求字节数。和 jcifs 那条路一样，粒度决定吞吐：256 KB 在视频边取边播时
         * 每 256 KB 就要等一个往返，拖动进度条会明显卡一下；抬到 1 MB 与 jcifs 侧对齐。
         */
        const val MAX_CHUNK = 1024 * 1024

        fun asIo(e: Exception): IOException =
                if (e is IOException) e else IOException(SmbClient.describe(e), e)
    }
}

/**
 * SMBJ 的会话缓存：一台主机一条 Session，一个共享一个 DiskShare。
 *
 * SMBJ 建会话比 jcifs 更贵（一次协商 + 一次登录 + 每个共享一次 TREE_CONNECT），而播放器拖
 * 一次进度条会反复开文件，不复用的话就是每拖一下重连一次。
 */
internal object SmbjPool {

    private class HostEntry(
            val client: SMBClient,
            val connection: Connection,
            val session: Session,
    ) {
        val shares = HashMap<String, DiskShare>()
    }

    private val hosts = HashMap<String, HostEntry>()

    @Synchronized
    @Throws(IOException::class)
    fun diskShare(link: SmbLink, name: String): DiskShare {
        val entry = hosts.getOrPut(link.host) { connect(link) }
        entry.shares[name]?.let { return it }
        val share = entry.session.connectShare(name)
        if (share !is DiskShare) {
            throw IOException("共享「$name」不是磁盘共享，SMBJ 通道读不了它")
        }
        entry.shares[name] = share
        return share
    }

    @Synchronized
    fun drop(host: String) {
        val entry = hosts.remove(host) ?: return
        runCatching { entry.session.close() }
        runCatching { entry.connection.close() }
        runCatching { entry.client.close() }
    }

    @Synchronized
    fun has(host: String): Boolean = hosts.containsKey(host)

    private fun connect(link: SmbLink): HostEntry {
        val client = SMBClient(configOf(link.settings))
        try {
            val connection = client.connect(link.host)
            val session = connection.authenticate(authOf(link))
            return HostEntry(client, connection, session)
        } catch (e: Exception) {
            // 建到一半失败也要把 socket 收掉，否则重试几次会攒下一堆半开连接
            runCatching { client.close() }
            throw if (e is IOException) e else IOException(SmbClient.describe(e), e)
        }
    }

    private fun authOf(link: SmbLink): AuthenticationContext {
        val credential = link.credential
        return if (credential.isAnonymous) {
            // 匿名/来宾：SMBJ 走空身份，服务端一般映射到 Guest
            AuthenticationContext("", "".toCharArray(), null)
        } else {
            AuthenticationContext(credential.user, credential.password.toCharArray(),
                    credential.domain.takeIf { it.isNotEmpty() })
        }
    }

    /**
     * 把 [SmbSettings] 翻译成 SMBJ 的配置。
     *
     * 方言表从 SMB_2_0_2 起：SMBJ 没有 SMB1 支持，所以「允许 SMB1」这个开关在这里无法对应，
     * 界面上选 SMBJ 时会提示这一条。多协议协商开着，让服务端自己挑最高的版本。
     */
    private fun configOf(settings: SmbSettings): SmbConfig {
        val seconds = settings.connTimeoutSeconds().toLong()
        return SmbConfig.builder()
                .withTimeout(seconds, TimeUnit.SECONDS)
                .withReadTimeout(seconds, TimeUnit.SECONDS)
                .withMultiProtocolNegotiate(true)
                .withSigningRequired(settings.signingEnforced())
                .withDfsEnabled(false)
                .withDialects(SMB2Dialect.SMB_2_0_2, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0,
                        SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1)
                .build()
    }
}

