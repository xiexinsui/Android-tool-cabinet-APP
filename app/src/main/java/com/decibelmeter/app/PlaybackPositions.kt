package com.decibelmeter.app

import android.content.Context

/**
 * 每部片子看到哪儿了。
 *
 * 键是「主机 + 路径」：换机器、改名都不会串到别的片子上；同一台机器上的同名文件也不会串。
 * 存在 SharedPreferences 里而不是数据库——这里最多几十条记录，够用就行。
 *
 * 两条不记：刚开始看（不到 [MIN_SAVE_MS]）和快看完了（超过 [DONE_RATIO]）。
 * 前者记下来只会让下次从头播的片子莫名跳到 12 秒；后者再点开应该从头开始。
 */
object PlaybackPositions {

    private const val PREFS = "smb_playback"
    private const val MIN_SAVE_MS = 8_000L
    private const val DONE_RATIO = 0.95

    fun key(host: String, path: String): String = host + "|" + path

    fun get(context: Context, key: String): Long = prefs(context).getLong(key, 0L)

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_SAVE_MS ||
                (durationMs > 0 && positionMs > durationMs * DONE_RATIO)) {
            forget(context, key)
            return
        }
        prefs(context).edit().putLong(key, positionMs).apply()
    }

    fun forget(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
