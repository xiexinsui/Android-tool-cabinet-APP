package com.decibelmeter.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * 「分享到工具箱」的落点。
 *
 * 存在的理由：现在上传只有一条路——先进到目标目录、再点「上传到此处」。而从相册或文件管理器
 * 出发时，人的起点是「这几个文件」，不是「哪个目录」。这条 Activity 接住别人发来的文件，
 * 直接把人带到共享浏览页的选目录模式，选完落点就整批入队（LocalSend 的「发送到某设备」是
 * 同一个思路，只不过它走自己的协议栈）。
 *
 * 它自己不做任何事：不建连接、不显示界面，只把 URI 转给 [SmbBrowseActivity]，所以用系统
 * Activity 就够，不需要 Compose 基类与语言菜单。
 *
 * 传的是 URI 字符串而不是 Parcelable：跨进程/跨版本传 Uri 对象在部分机型上会丢，
 * 字符串最稳；读取权限是按 UID 授予的，转给同进程的另一个 Activity 依然有效。
 */
class SmbSendActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streams = collectStreams(intent)
        if (streams.isEmpty()) {
            Toast.makeText(this, R.string.smb_send_empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startActivity(Intent(this, SmbBrowseActivity::class.java)
                .putExtra(SmbBrowseActivity.EXTRA_PICK, true)
                .putStringArrayListExtra(SmbBrowseActivity.EXTRA_INCOMING,
                        ArrayList(streams.map { it.toString() }))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun collectStreams(source: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else {
            source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            source.data?.let { uris.add(it) }
        }
        return uris.filter { it.scheme != null }
    }
}
