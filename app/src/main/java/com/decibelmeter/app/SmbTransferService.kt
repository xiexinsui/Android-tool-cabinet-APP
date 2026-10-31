package com.decibelmeter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 传输队列的前台承载。
 *
 * 它自己不做传输——真正的任务在 [TransferHub] 里跑。这个服务的职责只有一个：**让进程
 * 在页面退出之后继续活着**，并把进度摆在通知栏上（带「全部取消」）。
 *
 * 为什么不能只靠绑定的服务：绑定服务的寿命跟着客户端走，浏览页一 finish 绑定就断了；
 * 而前台服务的寿命由自己控制，只在「队列空了并且过了宽限期」时收摊。
 */
class SmbTransferService : Service() {

    companion object {
        private const val CHANNEL_ID = "smb_transfer"
        private const val NOTIFICATION_ID = 0x5355
        private const val ACTION_CANCEL_ALL = "com.decibelmeter.app.CANCEL_TRANSFERS"

        /** 队列空了再等这么久才退出：连着丢几个任务时不必反复起停服务。 */
        private const val STOP_GRACE_MS = 3_000L

        fun ensure(context: Context) {
            val intent = Intent(context, SmbTransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var started = false

    private val stopWatch = Runnable {
        if (TransferHub.activeCount == 0) {
            stopSelf()
        }
    }

    private val listener: () -> Unit = {
        if (started) {
            updateNotification()
            main.removeCallbacks(stopWatch)
            if (TransferHub.activeCount == 0) {
                main.postDelayed(stopWatch, STOP_GRACE_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            TransferHub.views.value
                    .filter { it.state == TransferState.RUNNING || it.state == TransferState.QUEUED }
                    .forEach { TransferHub.cancel(it.id) }
        }
        if (!started) {
            started = true
            // startForeground 必须在 onStartCommand 里同步调用（5 秒限制），不能等任务建好
            startForeground(NOTIFICATION_ID, build())
            TransferHub.onChange = listener
        }
        updateNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TransferHub.onChange = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, build())
    }

    private fun build(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                    getString(R.string.smb_transfer_channel),
                    NotificationManager.IMPORTANCE_LOW))
        }
        val active = TransferHub.views.value.filter {
            it.state == TransferState.RUNNING || it.state == TransferState.QUEUED
        }
        val running = active.firstOrNull { it.state == TransferState.RUNNING }
                ?: active.firstOrNull()
        val title = if (active.isEmpty()) {
            getString(R.string.smb_jobs_recent)
        } else {
            getString(R.string.smb_jobs_running, active.size)
        }
        val text = when {
            running == null -> getString(R.string.smb_transfer_idle)
            running.total > 0 -> running.label + " · " + FileUtil.humanSize(running.done) +
                    " / " + FileUtil.humanSize(running.total)
            else -> running.label + " · " + FileUtil.humanSize(running.done)
        }
        val cancel = PendingIntent.getService(this, 0,
                Intent(this, SmbTransferService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
                Intent(this, ToolBoxActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null,
                        getString(R.string.smb_transfer_cancel_all), cancel).build())
                .build()
    }
}
