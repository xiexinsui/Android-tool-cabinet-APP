package com.decibelmeter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条「没下完」的下载：远端是什么、落到哪儿、写了多少。 */
data class UnfinishedDownload(
        val host: String,
        val path: String,
        /** 落点句柄，见 [DownloadSink.token]。 */
        val token: String,
        val label: String,
        val doneBytes: Long,
        val total: Long,
        val at: Long,
) {
    /** 同一条下载的稳定标识：换了落点或片源就是另一条任务。 */
    val key: String get() = "$host|$path|$token"
}

/**
 * 未完成下载的落盘记录。
 *
 * 为什么需要它：传输任务活在进程里，进程一被杀（用户划掉、系统回收）任务表就空了，而
 * MediaStore 里那个 `IS_PENDING=1` 的条目和已经写进去的字节都还在。不记下来的话，用户下次
 * 打开只看到「下载目录里有个看不见的文件」，只能整条重下。
 *
 * 只记「足够续传」的字段，进度写回按秒节流：`apply()` 虽然异步，但每次进度都写一遍 JSON
 * 也会把主线程的 SharedPreferences 缓存搅得很忙。
 */
object UnfinishedStore {

    private const val PREFS = "smb_unfinished"
    private const val KEY = "items"

    /**
     * 内存里的那份表。
     *
     * 进度每 1.5 秒就要回写一次，而每次回写都得先知道别的任务是什么样：不缓存的话，两条并发
     * 下载一秒就要重新解析一遍 JSON。存档被外部改坏的情况不存在（只有这个进程读写它）。
     */
    private var cache: MutableList<UnfinishedDownload>? = null

    @Synchronized
    fun put(context: Context, record: UnfinishedDownload) {
        val items = read(context)
        items.removeAll { it.key == record.key }
        items.add(record)
        write(context, items)
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        val items = read(context)
        if (items.removeAll { it.key == key }) {
            write(context, items)
        }
    }

    /** 最近的排前面。 */
    @Synchronized
    fun list(context: Context): List<UnfinishedDownload> =
            read(context).sortedByDescending { it.at }

    private fun read(context: Context): MutableList<UnfinishedDownload> {
        cache?.let { return it }
        val result = ArrayList<UnfinishedDownload>()
        val raw = prefs(context).getString(KEY, null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val record = UnfinishedDownload(
                            host = item.optString("host"),
                            path = item.optString("path"),
                            token = item.optString("token"),
                            label = item.optString("label"),
                            doneBytes = item.optLong("done"),
                            total = item.optLong("total"),
                            at = item.optLong("at"),
                    )
                    if (record.host.isNotEmpty() && record.path.isNotEmpty() &&
                            record.token.isNotEmpty()) {
                        result.add(record)
                    }
                }
            } catch (e: Exception) {
                // 存档坏了就当没有：宁可让用户重下，也不要每次启动都在这里崩
                result.clear()
            }
        }
        cache = result
        return result
    }

    private fun write(context: Context, items: List<UnfinishedDownload>) {
        cache = ArrayList(items)
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("host", item.host)
                put("path", item.path)
                put("token", item.token)
                put("label", item.label)
                put("done", item.doneBytes)
                put("total", item.total)
                put("at", item.at)
            })
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
