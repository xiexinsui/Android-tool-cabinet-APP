package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程桌面连接簿：存在 SharedPreferences 的一段 JSON 数组，按添加顺序显示。
 *
 * 只存地址与名称，不存密码（见 {@link RdpConnection} 的说明）。同一目标重复添加会被挡下，
 * 免得列表里堆一串只差一个备注名的条目。读出来的内容当作不可信处理，坏条目跳过。
 */
public final class RdpBook {

    private static final String PREFS = "rdp_connections";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences prefs;

    public RdpBook(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<RdpConnection> load() {
        List<RdpConnection> items = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, null);
        if (raw == null) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String host = object.optString("host", "");
                if (host.isEmpty()) {
                    continue;
                }
                items.add(new RdpConnection(object.optString("name", ""), host,
                        object.optInt("port", LanHost.PORT_RDP)));
            }
        } catch (JSONException e) {
            // 内容被改坏就当作空簿，不动用户已经存下的东西
        }
        return items;
    }

    /** @return 空字符串表示添加成功，否则是「和哪一条重复」的说明用地址 */
    public String add(RdpConnection connection) {
        if (connection.host.isEmpty()) {
            return "";
        }
        List<RdpConnection> items = load();
        for (RdpConnection item : items) {
            if (item.sameTarget(connection)) {
                return item.address();
            }
        }
        items.add(connection);
        write(items);
        return "";
    }

    public boolean contains(RdpConnection connection) {
        for (RdpConnection item : load()) {
            if (item.sameTarget(connection)) {
                return true;
            }
        }
        return false;
    }

    public void remove(RdpConnection connection) {
        List<RdpConnection> items = load();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).sameTarget(connection)) {
                items.remove(i);
                write(items);
                return;
            }
        }
    }

    /** 编辑＝按原地址定位、整条替换，名称改了也不会变成两条。 */
    public void replace(RdpConnection oldItem, RdpConnection newItem) {
        List<RdpConnection> items = load();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).sameTarget(oldItem)) {
                items.set(i, newItem);
                write(items);
                return;
            }
        }
        items.add(newItem);
        write(items);
    }

    private void write(List<RdpConnection> items) {
        JSONArray array = new JSONArray();
        for (RdpConnection item : items) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", item.name);
                object.put("host", item.host);
                object.put("port", item.port);
                array.put(object);
            } catch (JSONException e) {
                // 单条写不进去就丢掉这条
            }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
