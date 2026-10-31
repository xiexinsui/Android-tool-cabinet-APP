package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMB 连接的可调项，都存在 SharedPreferences 的 {@code smb_settings} 里。
 *
 * 之所以要有这些开关：局域网里的文件服务器跨度太大。Windows 10 1803 之后默认关掉来宾访问、
 * 老 NAS 与路由器只认 SMB1、而 SMB1 在现代系统上又是要被禁掉的。一套写死的参数必然照顾不到
 * 某一类设备，而且失败原因（"协议版本不对" vs "权限不足"）在用户看来一模一样。
 *
 * 默认值偏保守：SMB1 关着（明文、无签名强制、被勒索软件当作主要入口），签名交给协商（jcifs
 * 默认 preferred），来宾回退开着（NAS 的公开共享常见）。打开 SMB1 时界面会说明这是兼容老设备的
 * 取舍，而不是「更安全」。
 *
 * jcifs-ng 的 {@code minVersion}/{@code maxVersion} 读的是枚举名（SMB1、SMB202、SMB311……），
 * 不是 "2.02" 这样的版本号——填错会直接抛 IllegalArgumentException。
 */
public final class SmbSettings {

    /** jcifs-ng 的 DialectVersion 枚举名。 */
    private static final String DIALECT_SMB1 = "SMB1";
    private static final String DIALECT_SMB202 = "SMB202";
    private static final String DIALECT_SMB311 = "SMB311";

    private static final String PREFS = "smb_settings";
    private static final String KEY_SMB1 = "allow_smb1";
    private static final String KEY_SIGNING = "signing_enforced";
    private static final String KEY_GUEST = "guest_fallback";
    private static final String KEY_TIMEOUT = "conn_timeout";
    private static final String KEY_HIDDEN = "show_hidden";
    private static final String KEY_SORT = "sort_by";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_THUMBS = "thumbnails";
    private static final String KEY_GRID = "grid_mode";
    private static final String KEY_CACHE_MB = "cache_mb";
    private static final String KEY_ENCRYPT = "smb3_encrypt";
    private static final String KEY_LAST_PATH_PREFIX = "last_path_";
    private static final String KEY_RECENT = "recent_hosts";
    private static final String KEY_BOOKMARKS = "folder_bookmarks";
    private static final int RECENT_LIMIT = 8;
    private static final int BOOKMARK_LIMIT = 24;

    /** 排序方式，与界面菜单的顺序一致。 */
    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_TIME = 2;

    /**
     * 文件读取走哪个协议库。
     *
     * jcifs-ng 是默认，也是唯一能枚举共享的；SMBJ 只在「读文件字节」这一步可选（见
     * {@link SmbLink#openRange}），而且它不支持 SMB1——开着 SMB1 兼容时切过来会连不上，
     * 所以这个开关在界面上标了「实验」并说明限制。
     */
    public static final int ENGINE_JCIFS = 0;
    public static final int ENGINE_SMBJ = 1;

    /** 传输/套接字缓冲。与 {@code SmbClient.BUFFER_SIZE} 保持一致，见那边的注释。 */
    private static final int TRANSFER_BUFFER_SIZE = 1024 * 1024;

    private final SharedPreferences prefs;

    public SmbSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean allowSmb1() {
        return prefs.getBoolean(KEY_SMB1, false);
    }

    public void setAllowSmb1(boolean value) {
        prefs.edit().putBoolean(KEY_SMB1, value).apply();
    }

    public boolean signingEnforced() {
        return prefs.getBoolean(KEY_SIGNING, false);
    }

    public void setSigningEnforced(boolean value) {
        prefs.edit().putBoolean(KEY_SIGNING, value).apply();
    }

    public boolean guestFallback() {
        return prefs.getBoolean(KEY_GUEST, true);
    }

    public void setGuestFallback(boolean value) {
        prefs.edit().putBoolean(KEY_GUEST, value).apply();
    }

    /** 连接超时秒数，响应超时会按三倍给（握手快慢和机器休眠是两件事）。 */
    public int connTimeoutSeconds() {
        int value = prefs.getInt(KEY_TIMEOUT, 5);
        return value < 2 || value > 30 ? 5 : value;
    }

    public void setConnTimeoutSeconds(int value) {
        prefs.edit().putInt(KEY_TIMEOUT, value).apply();
    }

    /**
     * 是否显示带「隐藏」属性的条目。**默认显示**。
     *
     * 为什么不是在默认里藏起来：这个属性由服务端说了算，各家实现差别很大——有的 NAS 会把
     * 目录里绝大多数文件都标上 hidden（或者靠 Samba 的 map hidden 从权限位映射过来），
     * 于是「默认隐藏」的代价是整个电影目录只剩一个文件夹，用户只会以为文件丢了。
     * 真在意的用户可以在菜单里把它关掉，界面上带隐藏属性的条目也会明确标「隐藏」。
     */
    public boolean showHidden() {
        return prefs.getBoolean(KEY_HIDDEN, true);
    }

    public void setShowHidden(boolean value) {
        prefs.edit().putBoolean(KEY_HIDDEN, value).apply();
    }

    public int sortBy() {
        int value = prefs.getInt(KEY_SORT, SORT_NAME);
        return value < SORT_NAME || value > SORT_TIME ? SORT_NAME : value;
    }

    public void setSortBy(int value) {
        prefs.edit().putInt(KEY_SORT, value).apply();
    }

    /** 读文件字节用哪个库；共享与目录枚举不受它影响。 */
    public int engine() {
        int value = prefs.getInt(KEY_ENGINE, ENGINE_JCIFS);
        return value != ENGINE_SMBJ ? ENGINE_JCIFS : value;
    }

    public void setEngine(int value) {
        prefs.edit().putInt(KEY_ENGINE, value == ENGINE_SMBJ ? ENGINE_SMBJ : ENGINE_JCIFS).apply();
    }

    /** 列表里要不要拉缩略图。关掉它等于回到「只看名字翻共享」的老行为，省流量也省一堆 SMB 读。 */
    public boolean thumbnails() {
        return prefs.getBoolean(KEY_THUMBS, true);
    }

    public void setThumbnails(boolean value) {
        prefs.edit().putBoolean(KEY_THUMBS, value).apply();
    }

    /** true = 网格（看图优先），false = 列表（看名字与大小优先）。 */
    public boolean gridMode() {
        return prefs.getBoolean(KEY_GRID, false);
    }

    public void setGridMode(boolean value) {
        prefs.edit().putBoolean(KEY_GRID, value).apply();
    }

    /** 强制 SMB3 传输加密。服务端不支持时会直接连不上，所以默认关。 */
    public boolean encryptionEnabled() {
        return prefs.getBoolean(KEY_ENCRYPT, false);
    }

    public void setEncryptionEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_ENCRYPT, value).apply();
    }

    /** 预览与缩略图的本地缓存上限（MB）。太大会把手机存储吃掉，太小则翻相册反复走网络。 */
    public int cacheLimitMb() {
        int value = prefs.getInt(KEY_CACHE_MB, 128);
        return value < 16 || value > 1024 ? 128 : value;
    }

    public void setCacheLimitMb(int value) {
        prefs.edit().putInt(KEY_CACHE_MB, value).apply();
    }

    /** 每台机器记住最后停在哪个目录：下次进同一台不必从头翻。 */
    public String lastPath(String host) {
        return prefs.getString(KEY_LAST_PATH_PREFIX + host, "");
    }

    public void saveLastPath(String host, String path) {
        prefs.edit().putString(KEY_LAST_PATH_PREFIX + host, path == null ? "" : path).apply();
    }

    /** 最近连成功过的主机，供「共享文件夹」独立入口的选择框用。 */
    public static final class Host {
        public final String host;
        public final String name;

        Host(String host, String name) {
            this.host = host;
            this.name = name;
        }
    }

    /**
     * 记一次成功连接。共享页现在也能从主页直接进，没有这份记录就只能每次都手输地址；
     * 最多留 8 个，把最近一次的挪到最前面。
     */
    public void rememberHost(String host, String name) {
        List<Host> hosts = recentHosts();
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).host.equals(host)) {
                hosts.remove(i);
                break;
            }
        }
        hosts.add(0, new Host(host, name == null || name.isEmpty() ? host : name));
        while (hosts.size() > RECENT_LIMIT) {
            hosts.remove(hosts.size() - 1);
        }
        JSONArray array = new JSONArray();
        for (Host item : hosts) {
            try {
                array.put(new JSONObject().put("host", item.host).put("name", item.name));
            } catch (JSONException e) {
                // 单条写不进去就丢掉
            }
        }
        prefs.edit().putString(KEY_RECENT, array.toString()).apply();
    }

    public List<Host> recentHosts() {
        List<Host> hosts = new ArrayList<>();
        String raw = prefs.getString(KEY_RECENT, null);
        if (raw == null) {
            return hosts;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String host = object.optString("host", "");
                if (!host.isEmpty()) {
                    hosts.add(new Host(host, object.optString("name", host)));
                }
            }
        } catch (JSONException e) {
            // 当作没有历史，不动别的设置
        }
        return hosts;
    }

    /**
     * 常用文件夹收藏：直达某个共享里的某个目录。
     *
     * 为什么要有它：进 NAS 的照片目录现在要「列共享 → 进共享 → 进目录」点三下，而每天要开的是
     * 同两三个地方。KDE Connect 与 Syncthing 都是「先选设备、再选那个设备上被标出来的目录」，
     * 一点直达，这个习惯值得抄。
     *
     * 路径约定与 {@link SmbClient} 一致：目录以 {@code /} 结尾，第一段是共享名。
     */
    public static final class Bookmark {
        public final String host;
        public final String path;
        public final String name;

        Bookmark(String host, String path, String name) {
            this.host = host;
            this.path = path;
            this.name = name;
        }
    }

    public List<Bookmark> bookmarks() {
        List<Bookmark> list = new ArrayList<>();
        String raw = prefs.getString(KEY_BOOKMARKS, null);
        if (raw == null) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String host = object.optString("host", "");
                String path = object.optString("path", "");
                if (!host.isEmpty() && !path.isEmpty()) {
                    list.add(new Bookmark(host, path,
                            object.optString("name", path)));
                }
            }
        } catch (JSONException e) {
            // 当作没有收藏，不动别的设置
        }
        return list;
    }

    /** 同 host + 同 path 视为同一条：重复添加只把它挪到最前，返回 false 表示本来就有。 */
    public boolean addBookmark(String host, String path, String name) {
        List<Bookmark> list = bookmarks();
        String normalized = path.endsWith("/") ? path : path + "/";
        boolean added = true;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).host.equals(host) && list.get(i).path.equals(normalized)) {
                list.remove(i);
                added = false;
                break;
            }
        }
        list.add(0, new Bookmark(host, normalized,
                name == null || name.isEmpty() ? normalized : name));
        while (list.size() > BOOKMARK_LIMIT) {
            list.remove(list.size() - 1);
        }
        JSONArray array = new JSONArray();
        for (Bookmark item : list) {
            try {
                array.put(new JSONObject().put("host", item.host)
                        .put("path", item.path).put("name", item.name));
            } catch (JSONException e) {
                // 单条写不进去就丢掉
            }
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
        return added;
    }

    public void removeBookmark(String host, String path) {
        List<Bookmark> list = bookmarks();
        String normalized = path.endsWith("/") ? path : path + "/";
        for (int i = 0; i < list.size(); i++) {
            Bookmark item = list.get(i);
            if (item.host.equals(host) && item.path.equals(normalized)) {
                list.remove(i);
                break;
            }
        }
        JSONArray array = new JSONArray();
        for (Bookmark item : list) {
            try {
                array.put(new JSONObject().put("host", item.host)
                        .put("path", item.path).put("name", item.name));
            } catch (JSONException e) {
                // 同上
            }
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
    }

    public boolean isBookmarked(String host, String path) {
        String normalized = path.endsWith("/") ? path : path + "/";
        for (Bookmark item : bookmarks()) {
            if (item.host.equals(host) && item.path.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把开关翻译成 jcifs 的属性。字符串值全部用枚举名，填成 "2.02" 会在构造配置时就炸掉。
     *
     * {@code minVersion} 一旦给出，jcifs 就不再读 {@code enableSMB2}/{@code disableSMB1}
     * （见 PropertyConfiguration 的分支），所以只设这一个——写两个会被忽略的那个假装在做事情。
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        int millis = connTimeoutSeconds() * 1000;
        properties.setProperty("jcifs.client.connTimeout", String.valueOf(millis));
        properties.setProperty("jcifs.client.soTimeout", String.valueOf(millis * 3));
        properties.setProperty("jcifs.client.responseTimeout", String.valueOf(millis * 3));
        // 只按 IP 或主机名访问，用不上域树；关掉能省掉一次多余的名称解析
        properties.setProperty("jcifs.client.dfs.disabled", "true");
        properties.setProperty("jcifs.resolveOrder", "DNS");
        properties.setProperty("jcifs.client.maxVersion", DIALECT_SMB311);
        // SMB1 只在用户明确打开兼容时才允许：它在现代系统上默认被服务端禁掉，而且明文、
        // 签名不是强制的——真出问题时影响面比 SMB2/3 大得多
        properties.setProperty("jcifs.client.minVersion",
                allowSmb1() ? DIALECT_SMB1 : DIALECT_SMB202);
        properties.setProperty("jcifs.client.signingEnforced",
                String.valueOf(signingEnforced()));
        properties.setProperty("jcifs.client.ipcSigningEnforced",
                String.valueOf(signingEnforced()));
        properties.setProperty("jcifs.client.allowGuestFallback",
                String.valueOf(guestFallback()));
        // 套接字与流缓冲：jcifs 默认只有 64 KB（SmbConstants.DEFAULT_RCV_BUF_SIZE），
        // 那同时就是 TCP 窗口——实测同一个文件下载 15.0 MB/s、上传 8.3 MB/s，而链路是
        // 1200 Mbps，抬到 1 MB 后变成 48.9 / 17.3 MB/s（见 CHANGELOG）。
        //
        // 但只在默认（SMB2+）路径上开：SMB1 且服务端没有 LARGE_READX 时，读大小会被算成
        // 这个缓冲值，1 MB 的 READ 老设备会直接拒掉。用户主动打开 SMB1 兼容，说明目标就是
        // 那类老设备，这时候宁可慢也别连不上。
        int transferBuffer = allowSmb1() ? 0xFFFF : TRANSFER_BUFFER_SIZE;
        properties.setProperty("jcifs.client.rcv_buf_size", String.valueOf(transferBuffer));
        properties.setProperty("jcifs.client.snd_buf_size", String.valueOf(transferBuffer));
        // SMB3 传输加密：服务端不支持时会直接协商失败，所以只在用户明确打开时发
        properties.setProperty("jcifs.client.encryptionEnabled",
                String.valueOf(encryptionEnabled()));
        return properties;
    }

    /** 一句话说明当前协商范围，设置对话框里显示，用户不用猜自己开的是哪一档。 */
    public String dialectSummary(Context context) {
        return allowSmb1() ? context.getString(R.string.smb_dialect_all)
                : context.getString(R.string.smb_dialect_modern);
    }
}
