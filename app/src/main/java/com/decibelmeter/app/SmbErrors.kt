package com.decibelmeter.app

import android.content.Context
import java.io.IOException

/**
 * 把两个 SMB 库的英文报错归到同几句中文说明上。
 *
 * 存在的理由：[SmbClient.describeError] 是按 jcifs 的措辞（"Access is denied"、"timeout"）做
 * 关键字匹配的，而 SMBJ 抛出来的是 `STATUS_ACCESS_DENIED`、`No dialects could be negotiated`
 * 这种 NT 状态码风格。选了 SMBJ 通道之后如果不做这层映射，用户看到的就只有
 * 「失败：com.hierynomus.mssmb2.SMBApiException: STATUS_...」，等于没有提示。
 *
 * 映射不上的一律退回原来的兜底文案，并把原文带出来——宁可啰嗦，也不要把一个真实存在的原因
 * 归类成另一个原因，那样用户会照着错的提示去改设置。
 */
object SmbErrors {

    /** NT 状态码片段 → 现有文案资源。顺序即优先级。 */
    private val MESSAGES = listOf(
            R.string.smb_error_logon to arrayOf(
                    "status_logon_failure", "status_account_disabled", "status_account_expired",
                    "status_account_locked_out", "status_bad_workstation", "password"),
            R.string.smb_error_denied to arrayOf(
                    "status_access_denied", "status_privilege_not_held", "status_map_first",
                    "no more available connections", "access is denied",
                    "status_invalid_parameter", "status_sharing_violation"),
            R.string.smb_error_missing to arrayOf(
                    "status_bad_network_name", "status_object_name_not_found", "status_no_such_file",
                    "status_file_does_not_exist", "status_path_not_covered"),
            R.string.smb_error_dialect to arrayOf(
                    "dialect", "status_smb_bad_nic", "unsupported version", "not supported",
                    "smb1", "smb_1"),
            R.string.smb_error_timeout to arrayOf(
                    "timeout", "timed out", "socket closed", "connection reset"),
    )

    @JvmStatic
    fun describe(context: Context, error: Exception): String {
        val chain = flatten(error).lowercase(java.util.Locale.US)
        for ((res, keys) in MESSAGES) {
            for (key in keys) {
                if (chain.contains(key)) {
                    // 原文照样附上：SMBJ 的状态码后面往往还带共享名和文件名，排障时要用
                    return context.getString(res, SmbClient.describe(leaf(error)))
                }
            }
        }
        return SmbClient.describeError(context, error)
    }

    /** 最内层的原因通常才是真的原因（外层多半是「读取失败」这种包装）。 */
    private fun leaf(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun flatten(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            builder.append(current.javaClass.name).append(": ").append(current.message).append(' ')
            current = if (current.cause === current) null else current.cause
            depth++
        }
        return builder.toString()
    }

    /** 预览页要区分「文件太大」这类自己的原因，先给一句能看懂的再说。 */
    @JvmStatic
    fun io(context: Context, error: Exception): String = describe(context, error)
}

/** 读一段远端文件到本地字节数组，用于文本预览这类「只要前若干 KB」的场景。 */
fun SmbRange.readBlock(offset: Long, length: Int): ByteArray {
    val dst = ByteArray(length)
    var done = 0
    while (done < length) {
        val read = readAt(offset + done, dst, done, length - done)
        if (read <= 0) {
            break
        }
        done += read
    }
    if (done == length) {
        return dst
    }
    return dst.copyOf(done)
}

/** 整份读到内存（只在确定文件不大的地方用，调用方负责先把大小卡住）。 */
@Throws(IOException::class)
fun SmbRange.readAll(limit: Long): ByteArray {
    val total = if (size > 0) size else limit
    require(total <= Int.MAX_VALUE) { "文件过大" }
    return readBlock(0, total.toInt())
}
