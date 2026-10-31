package com.decibelmeter.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 「把共享里的音视频交给外部播放器」这一件事，浏览页与预览页共用一份。
 *
 * 具体机制在 [SmbStreamService]：起前台服务 → 内部起回环 HTTP 代理 → 把
 * `http://127.0.0.1:端口/…` 交给系统选择器。这里只负责「说一句在准备」和「把 Intents 发出去」。
 *
 * 为什么不做成「先下载再打开」：一部 1.7 GB 的电影要先落盘才能播，而外部播放器本来就能
 * 边下边播，多等的这几分钟没有意义。
 */
object SmbExternalPlay {

    fun play(activity: Activity, host: String, path: String, name: String) {
        toast(activity, activity.getString(R.string.smb_stream_preparing))
        SmbStreamService.start(activity, host, path, name) { url, error ->
            if (activity.isFinishing) {
                return@start
            }
            if (url == null) {
                toast(activity, error ?: activity.getString(R.string.preview_stream_failed))
                return@start
            }
            val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(url), FileUtil.mimeOf(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(Intent.createChooser(view,
                        activity.getString(R.string.smb_action_open_other_player)))
            } catch (e: ActivityNotFoundException) {
                toast(activity, activity.getString(R.string.preview_no_player))
            }
        }
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
