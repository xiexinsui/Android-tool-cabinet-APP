package com.decibelmeter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log

/**
 * 把共享里的一个文件以**本机 HTTP 流**的形式交给别的播放器（VLC、MX Player、系统播放器等）。
 *
 * 为什么必须是前台服务：外部播放器拿到的是 `http://127.0.0.1:端口/…` 这个地址，流由本进程的
 * [SmbHttpProxy] 现场从 SMB 读出来。我们一 finish()，Activity 里的代理随之关闭，播放器下一
 * 次 seek 就会拿到连接被拒——所以这个代理必须活在一个不依赖界面的组件里，并且带通知
 * （用户随时能看到、能停）。
 *
 * 结果通过 [ResultReceiver] 回传而不是 Binder：只需要「拿到 URL 就发 Intent」这一件事，
 * 为此写一套 AIDL/回调绑定不划算；而且 URL 是异步准备好的（要先登录 SMB、起监听端口）。
 *
 * 收摊条件有三条：用户点通知里的停止、空闲超过 [IDLE_TIMEOUT_MS]、或者进程被杀。
 * 空闲判断看 [SmbHttpProxy.lastAccess]——播放器退出后不会再有请求进来。
 */
class SmbStreamService : Service() {

    companion object {
        const val EXTRA_HOST = "stream_host"
        const val EXTRA_PATH = "stream_path"
        const val EXTRA_NAME = "stream_name"
        const val EXTRA_RECEIVER = "stream_receiver"
        const val RESULT_URL = 1
        const val RESULT_ERROR = 2
        const val ACTION_STOP = "com.decibelmeter.app.STOP_STREAM"

        private const val CHANNEL_ID = "smb_stream"
        private const val NOTIFICATION_ID = 0x5354
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val WATCH_INTERVAL_MS = 30 * 1000L
        private const val TAG = "SmbStream"

        /**
         * 起流服务并等 URL。[onReady] 在主线程回调：成功给 url，失败给 error（两者互斥）。
         *
         * API 26 起后台服务必须走 startForegroundService，服务自己会立刻 startForeground。
         */
        fun start(context: Context, host: String, path: String, name: String,
                  onReady: (String?, String?) -> Unit) {
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val url = if (resultCode == RESULT_URL) resultData?.getString("url") else null
                    val error = if (resultCode == RESULT_ERROR) {
                        resultData?.getString("error")
                    } else {
                        null
                    }
                    onReady(url, error)
                }
            }
            val intent = Intent(context, SmbStreamService::class.java)
                    .putExtra(EXTRA_HOST, host)
                    .putExtra(EXTRA_PATH, path)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_RECEIVER, receiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var proxy: SmbHttpProxy? = null
    private var link: SmbLink? = null
    private var displayName = ""

    private val idleWatch = object : Runnable {
        override fun run() {
            val current = proxy
            if (current != null &&
                    System.currentTimeMillis() - current.lastAccess > IDLE_TIMEOUT_MS) {
                Log.i(TAG, "空闲超时，收掉流服务")
                stopSelf()
                return
            }
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: "stream"
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        displayName = name

        // 已经在跑：直接把现成地址回给调用方，别重复登录
        val running = proxy
        if (running != null && running.alive) {
            startForeground(NOTIFICATION_ID, buildNotification(PLAYING))
            receiver?.send(RESULT_URL, Bundle().apply { putString("url", running.url) })
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.smb_stream_preparing)))
        Thread {
            var url: String? = null
            var error: String? = null
            try {
                val acquired = SmbHub.acquire(this, host, host)
                link = acquired
                val started = SmbHttpProxy.start(acquired, path, name)
                proxy = started
                url = started.url
            } catch (e: Exception) {
                error = SmbErrors.describe(this, e)
            }
            val readyUrl = url
            val failure = error
            handler.post {
                if (readyUrl != null) {
                    startForeground(NOTIFICATION_ID, buildNotification(PLAYING))
                    receiver?.send(RESULT_URL, Bundle().apply { putString("url", readyUrl) })
                    handler.postDelayed(idleWatch, WATCH_INTERVAL_MS)
                } else {
                    receiver?.send(RESULT_ERROR, Bundle().apply { putString("error", failure) })
                    stopSelf()
                }
            }
        }.apply { isDaemon = true }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        proxy?.stop()
        proxy = null
        link?.let { SmbHub.release(it.host) }
        link = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 低优先级：这是一条「还能不能播」的说明，不该在状态栏上抢眼
            val channel = NotificationChannel(CHANNEL_ID,
                    getString(R.string.smb_stream_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val stop = PendingIntent.getService(this, 0,
                Intent(this, SmbStreamService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
                .setContentTitle(displayName)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null,
                        getString(R.string.smb_stream_stop), stop).build())
                .build()
    }

    private val PLAYING: String
        get() = getString(R.string.smb_stream_playing, displayName)
}
