package com.decibelmeter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近打开过的共享文件（最多 [LIMIT] 条，按时间倒序）。
 *
 * 只记「主机 + 路径 + 名字 + 时间」：不存凭据、不存大小——文件可能已经改名或删掉，
 * 点开时让 SMB 那边如实报错，比在本地维护一份会过期的副本更省心。
 */
object RecentFiles {

    private const val PREFS = "smb_recent_files"
    private const val KEY = "items"
    private const val LIMIT = 20

    class Entry(val host: String, val path: String, val name: String, val at: Long)

    fun record(context: Context, host: String, path: String, name: String) {
        val list = list(context).toMutableList()
        list.removeAll { it.host == host && it.path == path }
        list.add(0, Entry(host, path, name, System.currentTimeMillis()))
        while (list.size > LIMIT) {
            list.removeAt(list.size - 1)
        }
        val array = JSONArray()
        for (item in list) {
            array.put(JSONObject().put("host", item.host).put("path", item.path)
                    .put("name", item.name).put("at", item.at))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun list(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val result = ArrayList<Entry>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val object0 = array.optJSONObject(i) ?: continue
                val host = object0.optString("host", "")
                val path = object0.optString("path", "")
                if (host.isNotEmpty() && path.isNotEmpty()) {
                    result.add(Entry(host, path, object0.optString("name", path),
                            object0.optLong("at", 0)))
                }
            }
        } catch (e: Exception) {
            // 存档坏了就当没有
        }
        return result
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
