package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描页的本机数据：收藏与手动添加的设备，以及上一次的扫描偏好（网段、端口集、过滤、排序）。
 *
 * 收藏要落盘是因为局域网设备的地址本来就是 DHCP 租来的——不收藏的话每次重扫都得从头认一遍
 * 「哪台是我的 NAS」。设备表用 JSON 数组存在 SharedPreferences 里；MAC 用来在换地址后把记录
 * 跟过去，lastSeen 用来在设备不在线时说明「上次是什么时候看到的」。
 *
 * 读出来的内容一律当作不可信处理：坏条目跳过而不是抛异常，用户不该因为一次写入被中断就在
 * 下次进扫描页时看到崩溃。
 */
final class LanDevices {

    /** 一条持久化过的设备记录。 */
    static final class Saved {
        final String ip;
        final String name;
        final String mac;
        final boolean favorite;
        final boolean manual;
        final long lastSeen;

        Saved(String ip, String name, String mac, boolean favorite, boolean manual, long lastSeen) {
            this.ip = ip;
            this.name = name;
            this.mac = mac;
            this.favorite = favorite;
            this.manual = manual;
            this.lastSeen = lastSeen;
        }

        /** 变成扫描结果里的一个条目：名称、MAC 与上次在线时间按已知值填，端口留空等重新探测。 */
        LanHost toHost() {
            LanHost host = new LanHost(ip);
            host.pinName(name);
            host.mac = mac == null || mac.isEmpty() ? null : mac;
            host.favorite = favorite;
            host.manual = manual;
            host.lastSeen = lastSeen;
            return host;
        }
    }

    private static final String PREFS = "lan_devices";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_PREFIX = "prefix";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_USABLE_ONLY = "usable_only";
    private static final String KEY_CUSTOM_PORTS = "custom_ports";
    private static final String KEY_FILTER = "filter";
    private static final String KEY_SORT = "sort";
    private static final String KEY_KNOWN = "known";

    private final SharedPreferences prefs;

    LanDevices(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Saved> loadAll() {
        List<Saved> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String raw = prefs.getString(KEY_ITEMS, null);
        if (raw == null) {
            return items;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String ip = item.optString("ip", "");
                if (ip.isEmpty() || !seen.add(ip)) {
                    continue;
                }
                items.add(new Saved(ip, item.optString("name", ""), item.optString("mac", ""),
                        item.optBoolean("favorite", false), item.optBoolean("manual", false),
                        item.optLong("lastSeen", 0)));
            }
        } catch (JSONException e) {
            // 存进去的内容被改坏了就当作没有，不清空用户数据
        }
        return items;
    }

    /**
     * 记下这台设备的当前状态。收藏与手动添加都会保留名称：名称是 NetBIOS/mDNS 费了半天劲才
     * 问出来的，下次进来不必再问一遍。两者都不是了就整条删掉。
     */
    void save(LanHost host) {
        List<Saved> items = loadAll();
        boolean keep = host.favorite || host.manual;
        for (int i = 0; i < items.size(); i++) {
            Saved item = items.get(i);
            if (!item.ip.equals(host.ip)) {
                continue;
            }
            if (keep) {
                long seenAt = host.lastSeen != 0 ? host.lastSeen : item.lastSeen;
                items.set(i, toSaved(host, seenAt));
            } else {
                items.remove(i);
            }
            write(items);
            return;
        }
        if (keep) {
            items.add(toSaved(host, host.lastSeen));
            write(items);
        }
    }

    private static Saved toSaved(LanHost host, long lastSeen) {
        return new Saved(host.ip, host.name, host.mac, host.favorite, host.manual, lastSeen);
    }

    private void write(List<Saved> items) {
        JSONArray array = new JSONArray();
        for (Saved item : items) {
            JSONObject object = new JSONObject();
            try {
                object.put("ip", item.ip);
                object.put("name", item.name == null ? "" : item.name);
                object.put("mac", item.mac == null ? "" : item.mac);
                object.put("favorite", item.favorite);
                object.put("manual", item.manual);
                object.put("lastSeen", item.lastSeen);
                array.put(object);
            } catch (JSONException e) {
                // 单个字段写不进去就丢掉这一条，不值得为它放弃整张表
            }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    /** 手动项能不能整体删掉：收藏着的手动项只是不再置顶，记录还得留着。 */
    void remove(String ip) {
        List<Saved> items = loadAll();
        boolean changed = items.removeIf(item -> item.ip.equals(ip));
        if (changed) {
            write(items);
        }
    }

    /**
     * 如果这台设备的 MAC 已经在别处收藏过，把记录跟到新 IP 上并返回旧地址。
     * 只按 MAC 迁移，没有 MAC 的设备仍然只能按 IP 认。
     */
    String migrateByMac(LanHost host) {
        if (host.mac == null) {
            return null;
        }
        List<Saved> items = loadAll();
        String movedFrom = null;
        for (int i = 0; i < items.size(); i++) {
            Saved item = items.get(i);
            if (item.mac == null || !item.mac.equalsIgnoreCase(host.mac)
                    || item.ip.equals(host.ip)) {
                continue;
            }
            items.set(i, new Saved(host.ip,
                    host.name == null ? item.name : host.name, host.mac,
                    item.favorite, item.manual, item.lastSeen));
            movedFrom = item.ip;
            break;
        }
        if (movedFrom != null) {
            write(items);
        }
        return movedFrom;
    }

    /** 已存记录在这个 IP 上看到的 MAC；没有记录或记录里没 MAC 时返回 null。 */
    String macFor(String ip) {
        for (Saved item : loadAll()) {
            if (item.ip.equals(ip)) {
                return item.mac == null || item.mac.isEmpty() ? null : item.mac;
            }
        }
        return null;
    }

    /** 本轮真的看到的收藏/手动项才更新 lastSeen，没出现的保留上次时间。 */
    void markSeen(Collection<LanHost> hosts, long now) {
        Map<String, LanHost> byIp = new HashMap<>();
        for (LanHost host : hosts) {
            if (host.seen) {
                byIp.put(host.ip, host);
            }
        }
        if (byIp.isEmpty()) {
            return;
        }
        List<Saved> items = loadAll();
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            Saved item = items.get(i);
            LanHost host = byIp.get(item.ip);
            if (host == null) {
                continue;
            }
            items.set(i, new Saved(item.ip,
                    host.name == null ? item.name : host.name,
                    host.mac == null ? item.mac : host.mac,
                    item.favorite, item.manual, now));
            changed = true;
        }
        if (changed) {
            write(items);
        }
    }

    /**
     * 新设备基线。返回 null 表示还没建立过基线（第一次扫描不该把所有设备都标成新）。
     * 身份优先用 MAC，没有 MAC 的 mDNS 设备退回 IP。
     */
    Set<String> knownIdentities() {
        Set<String> stored = prefs.getStringSet(KEY_KNOWN, null);
        return stored == null ? null : new HashSet<>(stored);
    }

    void saveKnown(Set<String> identities) {
        prefs.edit().putStringSet(KEY_KNOWN, new HashSet<>(identities)).apply();
    }

    String lastPrefix() {
        return prefs.getString(KEY_PREFIX, null);
    }

    void savePrefix(String prefix) {
        prefs.edit().putString(KEY_PREFIX, prefix).apply();
    }

    int presetIndex() {
        return prefs.getInt(KEY_PRESET, 0);
    }

    void savePresetIndex(int index) {
        prefs.edit().putInt(KEY_PRESET, index).apply();
    }

    String customPorts() {
        return prefs.getString(KEY_CUSTOM_PORTS, "");
    }

    void saveCustomPorts(String ports) {
        prefs.edit().putString(KEY_CUSTOM_PORTS, ports).apply();
    }

    boolean usableOnly() {
        return prefs.getBoolean(KEY_USABLE_ONLY, false);
    }

    void saveUsableOnly(boolean value) {
        prefs.edit().putBoolean(KEY_USABLE_ONLY, value).apply();
    }

    int filterIndex() {
        return prefs.getInt(KEY_FILTER, 0);
    }

    void saveFilterIndex(int index) {
        prefs.edit().putInt(KEY_FILTER, index).apply();
    }

    int sortIndex() {
        return prefs.getInt(KEY_SORT, 0);
    }

    void saveSortIndex(int index) {
        prefs.edit().putInt(KEY_SORT, index).apply();
    }
}
