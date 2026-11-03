package com.decibelmeter.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网扫描页：把 {@link LanScanner} 四路发现的设备列出来，并按每台设备能做什么给出入口。
 *
 * 界面层负责把异步结果按「手动 → 收藏 → 网关 → 有开放端口 → 仅名称」稳定排进列表，再叠加
 * 搜索、筛选和排序；决定点开条目时问什么（缺凭据才问登录，已有就直接进）；把用户的收藏、
 * 手动添加、自定义端口与筛选偏好落盘，让它们活过下一轮扫描。所有扫描回调都来自工作线程，
 * 一律 runOnUiThread 加 isFinishing 判断之后才动数据，列表因此只在主线程被改。
 *
 * 单台设备的「重新探测」与「详扫」复用同一个扫描器与同一套回调，但会先让整轮扫描停下来——
 * 48 条线程同时被两轮任务抢，只会让两边都变慢。
 */
public class LanScanActivity extends BaseActivity {

    private static final int ACTION_SMB = 0;
    private static final int ACTION_ACCOUNT = 2;
    private static final int ACTION_WEB = 3;
    private static final int ACTION_FAVORITE = 4;
    private static final int ACTION_RECHECK = 5;
    private static final int ACTION_DETAIL = 6;
    private static final int ACTION_SAVE_RDP = 7;
    private static final int ACTION_REMOVE = 8;
    private static final int ACTION_COPY = 9;
    private static final int ACTION_FORGET = 10;
    private static final int ACTION_EXPORT_RDP = 11;
    private static final int ACTION_BUILTIN_RDP = 12;
    private static final int ACTION_PROBE = 13;
    private static final int ACTION_PORTS = 14;
    private static final int ACTION_WEB_PORTS = 15;
    private static final int ACTION_WAKE = 16;
    private static final int ACTION_IDENTIFY = 17;
    private static final int ACTION_SET_MAC = 18;

    private static final int MENU_PRESET = Menu.FIRST + 1;
    private static final int MENU_SEGMENT = Menu.FIRST + 2;
    private static final int MENU_ADD = Menu.FIRST + 3;
    private static final int MENU_USABLE = Menu.FIRST + 4;
    private static final int MENU_SHARES = Menu.FIRST + 5;
    private static final int MENU_RDP_BOOK = Menu.FIRST + 6;
    private static final int MENU_FILTER = Menu.FIRST + 7;
    private static final int MENU_SORT = Menu.FIRST + 8;
    private static final int MENU_EXPORT = Menu.FIRST + 9;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_SMB = 1;
    private static final int FILTER_RDP = 2;
    private static final int FILTER_WEB = 3;
    private static final int FILTER_PRINTER = 4;
    private static final int FILTER_NEW = 5;

    private static final int SORT_DEFAULT = 0;
    private static final int SORT_IP = 1;
    private static final int SORT_NAME = 2;
    private static final int SORT_TYPE = 3;

    /** 条目前面直接展示的端口个数上限。 */
    private static final int PORT_LABEL_LIMIT = 6;

    private final Map<String, LanHost> hosts = new LinkedHashMap<>();
    private final List<LanHost> visible = new ArrayList<>();
    private final HostAdapter adapter = new HostAdapter();
    /** 排序稳定：手动项在最前，收藏其次，探到服务的再次，只靠名称发现的最后。 */
    private final Comparator<LanHost> order = new Comparator<LanHost>() {
        @Override
        public int compare(LanHost a, LanHost b) {
            int rank = rankOf(a) - rankOf(b);
            if (rank != 0) {
                return rank;
            }
            int byIp = compareAddress(a.ip, b.ip);
            return byIp != 0 ? byIp : a.displayName().compareToIgnoreCase(b.displayName());
        }
    };

    private EditText etPrefix;
    private EditText etSearch;
    private Button btnScan;
    private TextView tvStatus;
    private CredentialStore credentials;
    private LanDevices devices;
    private LanScanner scanner;
    private LanServices.Preset preset;
    /** 本机网段的真实范围；手动改过前缀后按 /24 处理。 */
    private LanScanner.Range range;
    /** 本轮实际扫的范围（大网段会退回本机 /24）。 */
    private LanScanner.Range lastRange;
    private String gateway;
    private Set<String> knownIdentities = new HashSet<>();
    private boolean knownBaseline;
    private int filterIndex;
    private int sortIndex;
    private volatile boolean scanning;
    /** 本轮是不是被用户中途停掉的：停掉的轮次没有覆盖全部地址，不能声称「都探过了」。 */
    private volatile boolean stopRequested;
    /** 正在单独探测的设备地址；非空表示列表的单项操作要排队。 */
    private volatile String busyIp;
    /** 安全层探测与指纹用的单线程队列，不占扫描线程池。 */
    private final ExecutorService probes =
            Executors.newSingleThreadExecutor();
    private static final int PROBE_TIMEOUT_MS = 6000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lan_scan);
        setTitle(R.string.title_lan);

        credentials = new CredentialStore(this);
        devices = new LanDevices(this);
        scanner = new LanScanner(this);
        preset = loadPreset();
        filterIndex = Math.max(FILTER_ALL, Math.min(devices.filterIndex(), FILTER_NEW));
        sortIndex = Math.max(SORT_DEFAULT, Math.min(devices.sortIndex(), SORT_TYPE));

        etPrefix = (EditText) findViewById(R.id.et_prefix);
        etSearch = (EditText) findViewById(R.id.et_search);
        btnScan = (Button) findViewById(R.id.btn_scan);
        tvStatus = (TextView) findViewById(R.id.tv_status);

        String prefix = pickPrefix();
        if (prefix != null) {
            etPrefix.setText(prefix);
        }
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshList();
            }
        });
        retainSaved();

        ListView list = (ListView) findViewById(R.id.lv_hosts);
        list.setEmptyView(findViewById(R.id.tv_empty));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showHostMenu(visible.get(position)));

        btnScan.setOnClickListener(v -> toggleScan());
        etPrefix.setOnEditorActionListener((v, actionId, event) -> {
            if (!scanning) {
                toggleScan();
            }
            return true;
        });

        // 进这一页就开始扫：这里本来就是「看看家里现在都有什么」的地方，再让人点一次按钮
        // 只是多一步。放一帧之后再扫，让已保存的条目先画出来，扫描结果再往里合并。
        // 没有可用网段（比如只连了蜂窝）时不自动扫，否则一进来就弹一句"网段格式不对"。
        btnScan.post(() -> {
            if (!isFinishing() && !scanning && isPrefix(etPrefix.getText().toString().trim())) {
                toggleScan();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanner.shutdown();
        probes.shutdown();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_PRESET, 0, R.string.lan_menu_preset);
        menu.add(0, MENU_SEGMENT, 0, R.string.lan_menu_segment);
        menu.add(0, MENU_ADD, 0, R.string.lan_menu_add);
        menu.add(0, MENU_USABLE, 0, R.string.lan_menu_usable)
                .setCheckable(true)
                .setChecked(devices.usableOnly());
        menu.add(0, MENU_FILTER, 0, R.string.lan_menu_filter);
        menu.add(0, MENU_SORT, 0, R.string.lan_menu_sort);
        menu.add(0, MENU_EXPORT, 0, R.string.lan_menu_export);
        // 合并成一个入口后，这两个页面从扫描页进：共享簿与连接簿都是「不针对某台具体设备」时的去处
        menu.add(0, MENU_SHARES, 0, R.string.lan_menu_shares);
        menu.add(0, MENU_RDP_BOOK, 0, R.string.lan_menu_rdp_book);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_PRESET:
                showPresetDialog();
                return true;
            case MENU_SEGMENT:
                showSegmentDialog();
                return true;
            case MENU_ADD:
                showAddDialog();
                return true;
            case MENU_USABLE:
                boolean next = !devices.usableOnly();
                devices.saveUsableOnly(next);
                item.setChecked(next);
                refreshList();
                toast(getString(next ? R.string.lan_usable_on : R.string.lan_usable_off));
                return true;
            case MENU_FILTER:
                showFilterDialog();
                return true;
            case MENU_SORT:
                showSortDialog();
                return true;
            case MENU_EXPORT:
                exportResults();
                return true;
            case MENU_SHARES:
                // 不带主机地址进去，共享页会先问「连哪台」（最近连过的主机或手输）
                startActivity(new Intent(this, SmbBrowseActivity.class));
                return true;
            case MENU_RDP_BOOK:
                startActivity(new Intent(this, RdpActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /** 本机地址优先；取不到时回到上次扫过的网段，最后才留空让用户自己填。 */
    private String pickPrefix() {
        List<LanScanner.Segment> segments = LanScanner.localSegments(this);
        if (!segments.isEmpty()) {
            LanScanner.Segment segment = segments.get(0);
            range = segment.range;
            gateway = segment.gateway;
            showLocalStatus(segment.ip);
            return segment.range.prefixText();
        }
        range = null;
        gateway = null;
        tvStatus.setText(R.string.lan_no_local_ip);
        return devices.lastPrefix();
    }

    private void showLocalStatus(String ip) {
        if (range == null) {
            tvStatus.setText(R.string.lan_no_local_ip);
            return;
        }
        LanScanner.Range effective = range.effective(ip, LanScanner.MAX_TARGETS);
        boolean truncated = !effective.equals(range);
        long count = Math.max(0, effective.hostCount() - (effective.contains(ip) ? 1 : 0));
        String target = getString(truncated ? R.string.lan_targets_truncated_format
                : R.string.lan_targets_format, effective.cidr(), count);
        tvStatus.setText(gateway == null
                ? getString(R.string.lan_local_range_no_gateway_format, ip, target)
                : getString(R.string.lan_local_range_format, ip, gateway, target));
    }

    private LanServices.Preset loadPreset() {
        int index = devices.presetIndex();
        if (index == LanServices.PRESET_CUSTOM_INDEX) {
            int[] ports = LanServices.parsePorts(devices.customPorts());
            if (ports != null) {
                return LanServices.customPreset(ports);
            }
            devices.savePresetIndex(0);
            return LanServices.presetAt(0);
        }
        return LanServices.presetAt(index);
    }

    private void toggleScan() {
        if (scanning) {
            stopRequested = true;
            scanner.cancel();
            return;
        }
        String prefix = etPrefix.getText().toString().trim();
        if (!isPrefix(prefix)) {
            toast(getString(R.string.lan_bad_prefix));
            return;
        }
        LanScanner.Range scanRange = range != null && range.prefixText().equals(prefix)
                ? range : LanScanner.Range.fromPrefixText(prefix);
        if (scanRange == null) {
            toast(getString(R.string.lan_bad_prefix));
            return;
        }
        String selfIp = LanScanner.localIpv4(this);
        lastRange = scanRange.effective(selfIp, LanScanner.MAX_TARGETS);
        devices.savePrefix(prefix);
        knownIdentities = devices.knownIdentities();
        knownBaseline = knownIdentities != null;
        if (knownIdentities == null) {
            knownIdentities = new HashSet<>();
        }
        stopRequested = false;
        // 只留下会一直想看到的条目，其余按本轮结果重建
        retainSaved();
        scanning = true;
        btnScan.setText(R.string.lan_stop);
        long targetCount = Math.max(0, lastRange.hostCount()
                - (lastRange.contains(selfIp) ? 1 : 0));
        tvStatus.setText(getString(R.string.lan_scanning_range_format,
                lastRange.cidr(), targetCount));
        scanner.start(scanRange, selfIp, preset, new LanScanner.Listener() {
            @Override
            public void onProgress(final int done, final int total) {
                runOnUiThread(() -> {
                    if (!isFinishing() && scanning) {
                        tvStatus.setText(getString(R.string.lan_scanning_format,
                                done, total, visible.size()));
                    }
                });
            }

            @Override
            public void onHost(final LanHost host) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        merge(host);
                    }
                });
            }

            @Override
            public void onFinished(final int scanned, final LanScanner.Stats stats) {
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    scanning = false;
                    btnScan.setText(R.string.lan_scan);
                    if (stopRequested) {
                        tvStatus.setText(getString(R.string.lan_stopped_format, visible.size()));
                        return;
                    }
                    markRoundCovered();
                    devices.markSeen(hosts.values(), System.currentTimeMillis());
                    Set<String> known = new HashSet<>(knownIdentities);
                    for (LanHost item : hosts.values()) {
                        if (!item.seen) {
                            continue;
                        }
                        if (item.mac != null && !item.mac.isEmpty()) {
                            // 有了 MAC 之后把旧的「按 IP 认」条目换掉，避免以后换手被当成同一台
                            known.remove("ip:" + item.ip);
                            known.add(identityOf(item));
                        } else {
                            known.add(identityOf(item));
                        }
                    }
                    devices.saveKnown(known);
                    knownIdentities = known;
                    knownBaseline = true;
                    refreshList();
                    String base = getString(R.string.lan_done_format, scanned, visible.size());
                    String hint = diagnose(stats);
                    tvStatus.setText(hint == null ? base : base + " · " + hint);
                });
            }
        });
    }

    /**
     * 收尾诊断：广播/mDNS 全被吞时，用户看到的只是「设备怎么少了」，这里把可能的原因写出来。
     */
    private String diagnose(LanScanner.Stats stats) {
        if (stats == null) {
            return null;
        }
        int named = stats.nbnsHosts + stats.mdnsHosts + stats.ssdpHosts;
        if (stats.tcpHosts == 0 && named == 0) {
            return getString(R.string.lan_hint_unreachable);
        }
        if (stats.tcpHosts > 0 && named == 0) {
            return getString(R.string.lan_hint_ap_isolation);
        }
        if (stats.tcpHosts == 0 && named > 0) {
            return getString(R.string.lan_hint_ports_blocked);
        }
        return null;
    }

    private static String identityOf(LanHost host) {
        if (host.mac != null && !host.mac.isEmpty()) {
            return "mac:" + host.mac.toLowerCase(Locale.US);
        }
        return "ip:" + host.ip;
    }

    /** 单台设备的端口探测：重新探测只走当前预设的在场端口，详扫则走 1–1024 加常见高位端口。 */
    private void probeSingle(final LanHost target, final int[] ports, final boolean detail) {
        if (scanning || busyIp != null) {
            toast(getString(R.string.lan_busy));
            return;
        }
        busyIp = target.ip;
        btnScan.setEnabled(false);
        tvStatus.setText(detail
                ? getString(R.string.lan_detail_format, target.ip, 0, ports.length)
                : getString(R.string.lan_recheck_format, target.ip));
        scanner.startDetail(target.ip, ports, new LanScanner.Listener() {
            @Override
            public void onProgress(final int done, final int total) {
                runOnUiThread(() -> {
                    if (!isFinishing() && detail) {
                        tvStatus.setText(getString(R.string.lan_detail_format,
                                target.ip, done, total));
                    }
                });
            }

            @Override
            public void onHost(final LanHost host) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        merge(host);
                    }
                });
            }

            @Override
            public void onError(final String ip) {
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    busyIp = null;
                    btnScan.setEnabled(true);
                    tvStatus.setText(getString(R.string.lan_probe_resolve_failed, ip));
                });
            }

            @Override
            public void onFinished(final int scanned, final LanScanner.Stats stats) {
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    busyIp = null;
                    btnScan.setEnabled(true);
                    LanHost fresh = hosts.get(target.ip);
                    Set<Integer> open = fresh == null ? target.openPorts() : fresh.openPorts();
                    // 结论写进状态行而不是只弹 toast：toast 三秒就没了，而「这台到底开了什么」
                    // 是用户刚刚花几秒等出来的答案，得留在屏幕上
                    tvStatus.setText(open.isEmpty()
                            ? getString(detail ? R.string.lan_detail_none
                                    : R.string.lan_recheck_none, target.ip)
                            : getString(detail ? R.string.lan_detail_done_format
                                        : R.string.lan_recheck_done_format,
                                    target.ip, portLabels(open)));
                });
            }
        });
    }

    /** 上一轮的结论丢掉，只把用户标记过的条目留下：收藏与手动添加要活过每一轮扫描。 */
    private void retainSaved() {
        hosts.clear();
        for (LanDevices.Saved saved : devices.loadAll()) {
            hosts.put(saved.ip, saved.toHost());
        }
        refreshList();
    }

    /**
     * 整轮跑完后，把本段里还没结论的条目改成「探过了、确实没开端口」。
     *
     * 手动添加或上一轮留下的条目不在扫描器的结果表里，扫描器只会为「探到端口」的地址回调，
     * 所以这一层要自己补——否则一个都关着的手动设备会永远显示「还没探测」。
     */
    private void markRoundCovered() {
        if (lastRange == null) {
            return;
        }
        boolean changed = false;
        for (LanHost host : hosts.values()) {
            if (!host.probed && lastRange.contains(host.ip)) {
                host.probed = true;
                changed = true;
            }
        }
        if (changed) {
            refreshList();
        }
    }

    /** 同一台设备始终合并成列表里的一条：新信息补进来，用户加的标记不会被扫描覆盖掉。 */
    private void merge(LanHost host) {
        host.seen = true;
        String movedFrom = devices.migrateByMac(host);
        if (movedFrom != null) {
            LanHost moved = hosts.remove(movedFrom);
            if (moved != null) {
                host.favorite = host.favorite || moved.favorite;
                host.manual = host.manual || moved.manual;
                if (host.name == null) {
                    host.considerName(moved.name, 1);
                }
                if (host.lastSeen == 0) {
                    host.lastSeen = moved.lastSeen;
                }
            }
            credentials.rename(movedFrom, host.ip);
            toast(getString(R.string.lan_moved_format, host.displayName(), movedFrom, host.ip));
        }
        LanHost old = hosts.get(host.ip);
        if (old != null && old != host) {
            host.favorite = host.favorite || old.favorite;
            host.manual = host.manual || old.manual;
            if (host.name == null) {
                host.considerName(old.name, 1);
            }
            if (host.mac == null) {
                host.mac = old.mac;
            }
            if (host.mdnsType == null) {
                host.mdnsType = old.mdnsType;
            }
            // 复探时单播 NBNS 可能不答，上一轮的角色线索不能因此消失
            host.nbnsFileServer = host.nbnsFileServer || old.nbnsFileServer;
            host.nbnsDomainController = host.nbnsDomainController || old.nbnsDomainController;
            host.nbnsBrowser = host.nbnsBrowser || old.nbnsBrowser;
            if (host.model == null) {
                host.model = old.model;
            }
            if (host.vendor == null) {
                host.vendor = old.vendor;
            }
            if (host.ssdpType == null) {
                host.ssdpType = old.ssdpType;
            }
            if (host.fingerprint == null) {
                host.fingerprint = old.fingerprint;
                host.fingerprintDetail = old.fingerprintDetail;
            }
            if (host.mdnsPort == 0) {
                host.mdnsPort = old.mdnsPort;
            }
            if (host.rttMs == 0) {
                host.rttMs = old.rttMs;
            }
            if (host.lastSeen == 0) {
                host.lastSeen = old.lastSeen;
            }
            host.identityChanged = host.identityChanged || old.identityChanged;
        }
        String savedMac = devices.macFor(host.ip);
        if (savedMac != null && host.mac != null && !savedMac.equalsIgnoreCase(host.mac)) {
            host.identityChanged = true;
        }
        host.gateway = host.ip.equals(gateway);
        boolean knownByIdentity = knownIdentities.contains(identityOf(host));
        boolean knownByIp = host.mac != null
                && knownIdentities.contains("ip:" + host.ip);
        host.newDevice = knownBaseline && !knownByIdentity && !knownByIp;
        hosts.put(host.ip, host);
        refreshList();
    }

    private void refreshList() {
        visible.clear();
        boolean usableOnly = devices.usableOnly();
        String query = etSearch == null ? "" : etSearch.getText().toString().trim()
                .toLowerCase(Locale.US);
        for (LanHost host : hosts.values()) {
            if (usableOnly && !host.isUsable()) {
                continue;
            }
            if (!matchesFilter(host)) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(host, query)) {
                continue;
            }
            visible.add(host);
        }
        Collections.sort(visible, comparatorForSort());
        adapter.notifyDataSetChanged();
    }

    private boolean matchesFilter(LanHost host) {
        switch (filterIndex) {
            case FILTER_SMB:
                return host.mayOfferSmb();
            case FILTER_RDP:
                return host.mayOfferRdp();
            case FILTER_WEB:
                return !LanServices.webPorts(host.openPorts()).isEmpty();
            case FILTER_PRINTER:
                return LanServices.roleRes(host) == R.string.lan_role_printer;
            case FILTER_NEW:
                return host.newDevice;
            default:
                return true;
        }
    }

    private boolean matchesQuery(LanHost host, String query) {
        String text = host.displayName() + " " + host.ip + " "
                + (host.mac == null ? "" : host.mac) + " " + describeRole(host);
        return text.toLowerCase(Locale.US).contains(query);
    }

    private Comparator<LanHost> comparatorForSort() {
        switch (sortIndex) {
            case SORT_IP:
                return (a, b) -> {
                    int byIp = compareAddress(a.ip, b.ip);
                    return byIp != 0 ? byIp : a.displayName().compareToIgnoreCase(b.displayName());
                };
            case SORT_NAME:
                return (a, b) -> {
                    int byName = a.displayName().compareToIgnoreCase(b.displayName());
                    return byName != 0 ? byName : compareAddress(a.ip, b.ip);
                };
            case SORT_TYPE:
                return (a, b) -> {
                    String left = describeRole(a);
                    String right = describeRole(b);
                    if (left.isEmpty() != right.isEmpty()) {
                        return left.isEmpty() ? 1 : -1;
                    }
                    int byType = left.compareToIgnoreCase(right);
                    return byType != 0 ? byType : compareAddress(a.ip, b.ip);
                };
            default:
                return order;
        }
    }

    private static int rankOf(LanHost host) {
        if (host.manual) {
            return 0;
        }
        if (host.favorite) {
            return 1;
        }
        if (host.gateway) {
            return 2;
        }
        return host.openPorts().isEmpty() ? 4 : 3;
    }

    private String describeRole(LanHost host) {
        StringBuilder builder = new StringBuilder();
        int roleRes = LanServices.roleRes(host);
        if (roleRes != 0) {
            builder.append(getString(roleRes));
        }
        appendHint(builder, host.model);
        appendHint(builder, host.vendor);
        appendHint(builder, host.fingerprint);
        if (host.identityChanged) {
            appendHint(builder, getString(R.string.lan_identity_changed_short));
        }
        return builder.toString();
    }

    private static void appendHint(StringBuilder builder, String hint) {
        if (hint == null || hint.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" · ");
        }
        builder.append(LanHttp.truncate(hint, 48));
    }

    private void showFilterDialog() {
        String[] labels = {
                getString(R.string.lan_filter_all),
                getString(R.string.lan_filter_smb),
                getString(R.string.lan_filter_rdp),
                getString(R.string.lan_filter_web),
                getString(R.string.lan_filter_printer),
                getString(R.string.lan_filter_new),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_filter_title)
                .setSingleChoiceItems(labels, filterIndex, (dialog, which) -> {
                    filterIndex = which;
                    devices.saveFilterIndex(which);
                    dialog.dismiss();
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSortDialog() {
        String[] labels = {
                getString(R.string.lan_sort_default),
                getString(R.string.lan_sort_ip),
                getString(R.string.lan_sort_name),
                getString(R.string.lan_sort_type),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_sort_title)
                .setSingleChoiceItems(labels, sortIndex, (dialog, which) -> {
                    sortIndex = which;
                    devices.saveSortIndex(which);
                    dialog.dismiss();
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exportResults() {
        if (hosts.isEmpty()) {
            toast(getString(R.string.lan_export_empty));
            return;
        }
        List<LanHost> all = new ArrayList<>(hosts.values());
        Collections.sort(all, order);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder csv = new StringBuilder();
        csv.append(getString(R.string.lan_export_header)).append("\r\n");
        for (LanHost host : all) {
            csv.append(csv(host.ip)).append(',')
                    .append(csv(host.displayName())).append(',')
                    .append(csv(host.mac)).append(',')
                    .append(csv(TextUtils.join(" ", host.openPorts()))).append(',')
                    .append(csv(describeRole(host))).append(',')
                    .append(csv(host.lastSeen > 0 ? format.format(new Date(host.lastSeen)) : ""))
                    .append(',')
                    .append(csv(host.newDevice ? getString(R.string.lan_new_tag) : ""))
                    .append("\r\n");
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.lan_export_subject));
        send.putExtra(Intent.EXTRA_TEXT, csv.toString());
        startActivity(Intent.createChooser(send, getString(R.string.lan_export_title)));
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 按四段数值比较，末段 2 排在 10 前面；不是点分四段的（主机名）统一排到数字地址后面。 */
    private static int compareAddress(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        boolean leftNumeric = left.length == 4;
        boolean rightNumeric = right.length == 4;
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        if (!leftNumeric) {
            return a.compareToIgnoreCase(b);
        }
        for (int i = 0; i < 4; i++) {
            int x = parseOrZero(left[i]);
            int y = parseOrZero(right[i]);
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

    private static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 只接受点分三段、每段 0–255 的网段前缀。 */
    private static boolean isPrefix(String text) {
        String[] parts = text.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 端口集选择框。
     *
     * 说明文字不放进 setMessage：AlertDialog 的消息区与列表区在部分定制系统（实测 MIUI 13）上
     * 会互相挤掉，结果就是只剩说明、选项看不见。所以把代价写进每个选项自己后面。
     */
    private void showPresetDialog() {
        String[] labels = new String[LanServices.PRESET_CUSTOM_INDEX + 1];
        for (int i = 0; i < LanServices.PRESET_CUSTOM_INDEX; i++) {
            LanServices.Preset candidate = LanServices.presetAt(i);
            String cost = candidate.extra.length == 0
                    ? getString(R.string.lan_preset_cost_simple_format,
                            candidate.presenceCount())
                    : getString(R.string.lan_preset_cost_format,
                            candidate.presenceCount(), candidate.extra.length);
            labels[i] = getString(candidate.labelRes) + "（" + cost + "）";
        }
        labels[LanServices.PRESET_CUSTOM_INDEX] = getString(R.string.lan_preset_custom);
        int checked = preset.labelRes == R.string.lan_preset_custom
                ? LanServices.PRESET_CUSTOM_INDEX : LanServices.presetIndexOf(preset);
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_preset_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == LanServices.PRESET_CUSTOM_INDEX) {
                        showCustomPortsDialog();
                        return;
                    }
                    preset = LanServices.presetAt(which);
                    devices.savePresetIndex(which);
                    toast(getString(R.string.lan_preset_changed,
                            getString(preset.labelRes)));
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomPortsDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.lan_custom_hint);
        input.setText(devices.customPorts());
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_custom_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int[] ports = LanServices.parsePorts(input.getText().toString());
                    if (ports == null) {
                        toast(getString(R.string.lan_custom_invalid));
                        return;
                    }
                    StringBuilder normalized = new StringBuilder();
                    for (int port : ports) {
                        if (normalized.length() > 0) {
                            normalized.append(',');
                        }
                        normalized.append(port);
                    }
                    devices.saveCustomPorts(normalized.toString());
                    devices.savePresetIndex(LanServices.PRESET_CUSTOM_INDEX);
                    preset = LanServices.customPreset(ports);
                    toast(getString(R.string.lan_preset_changed,
                            getString(R.string.lan_preset_custom)));
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSegmentDialog() {
        final List<LanScanner.Segment> segments = LanScanner.localSegments(this);
        if (segments.isEmpty()) {
            toast(getString(R.string.lan_no_local_ip));
            return;
        }
        String[] labels = new String[segments.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = segments.get(i).displayName();
        }
        new AlertDialog.Builder(this)
                // 蜂窝网络为什么不在列表里，写进标题而不是消息区，理由见 showPresetDialog
                .setTitle(getString(R.string.lan_segment_title) + "\n"
                        + getString(R.string.lan_segment_note))
                .setItems(labels, (dialog, which) -> {
                    LanScanner.Segment segment = segments.get(which);
                    range = segment.range;
                    gateway = segment.gateway;
                    etPrefix.setText(segment.range.prefixText());
                    showLocalStatus(segment.ip);
                    toast(getString(R.string.lan_segment_changed, segment.range.cidr()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddDialog() {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_lan_add, null);
        final EditText etHost = (EditText) content.findViewById(R.id.et_host);
        final EditText etName = (EditText) content.findViewById(R.id.et_name);
        final EditText etMac = (EditText) content.findViewById(R.id.et_mac);
        new AlertDialog.Builder(this)
                .setTitle(R.string.lan_add_title)
                .setView(content)
                .setPositiveButton(R.string.lan_action_add_ok, (dialog, which) -> {
                    String host = etHost.getText().toString().trim();
                    if (host.isEmpty() || host.contains(" ") || host.contains("/")) {
                        toast(getString(R.string.lan_bad_host));
                        return;
                    }
                    String macText = etMac.getText().toString().trim();
                    String mac = macText.isEmpty() ? null : LanMac.normalize(macText);
                    if (!macText.isEmpty() && mac == null) {
                        toast(getString(R.string.lan_bad_mac));
                        return;
                    }
                    LanHost item = new LanHost(host);
                    item.manual = true;
                    item.favorite = true;
                    item.mac = mac;
                    String name = etName.getText().toString().trim();
                    if (!name.isEmpty()) {
                        item.pinName(name);
                    }
                    hosts.put(item.ip, item);
                    devices.save(item);
                    refreshList();
                    toast(getString(R.string.lan_added, host));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showHostMenu(final LanHost host) {
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add(getString(R.string.lan_action_smb));
        actions.add(ACTION_SMB);
        // 本进程直接连的那条排前面：FreeRDP 客户端始终随应用内置
        if (host.mayOfferRdp()) {
            labels.add(getString(R.string.lan_action_builtin_rdp));
            actions.add(ACTION_BUILTIN_RDP);
        }
        Set<Integer> openPorts = host.openPorts();
        List<Integer> webPorts = LanServices.webPorts(openPorts);
        if (webPorts.size() == 1) {
            labels.add(getString(R.string.lan_action_web_format, webPorts.get(0)));
            actions.add(ACTION_WEB);
        } else if (webPorts.size() > 1) {
            labels.add(getString(R.string.lan_action_web_ports, webPorts.size()));
            actions.add(ACTION_WEB_PORTS);
        }
        labels.add(getString(R.string.lan_action_account));
        actions.add(ACTION_ACCOUNT);
        labels.add(getString(host.favorite ? R.string.lan_action_unfavorite
                : R.string.lan_action_favorite));
        actions.add(ACTION_FAVORITE);
        labels.add(getString(R.string.lan_action_recheck));
        actions.add(ACTION_RECHECK);
        labels.add(getString(R.string.lan_action_detail));
        actions.add(ACTION_DETAIL);
        if (!openPorts.isEmpty()) {
            labels.add(getString(R.string.lan_action_ports, openPorts.size()));
            actions.add(ACTION_PORTS);
            labels.add(getString(R.string.lan_action_identify));
            actions.add(ACTION_IDENTIFY);
        }
        if (host.mac != null && !host.mac.isEmpty()) {
            labels.add(getString(R.string.lan_action_wake));
            actions.add(ACTION_WAKE);
        }
        labels.add(getString(R.string.lan_action_set_mac));
        actions.add(ACTION_SET_MAC);
        if (host.mayOfferRdp() || host.manual) {
            labels.add(getString(R.string.lan_action_save_rdp));
            actions.add(ACTION_SAVE_RDP);
        }
        if (host.mayOfferRdp()) {
            labels.add(getString(R.string.rdp_probe_menu));
            actions.add(ACTION_PROBE);
        }
        if (host.mayOfferRdp()) {
            labels.add(getString(R.string.lan_action_export_rdp));
            actions.add(ACTION_EXPORT_RDP);
        }
        if (host.favorite || host.manual) {
            labels.add(getString(R.string.lan_action_remove_saved));
            actions.add(ACTION_REMOVE);
        }
        labels.add(getString(R.string.lan_action_copy_ip));
        actions.add(ACTION_COPY);
        if (credentials.isStored(host.ip)) {
            labels.add(getString(R.string.lan_action_forget));
            actions.add(ACTION_FORGET);
        }
        new AlertDialog.Builder(this)
                .setTitle(host.displayName())
                .setItems(labels.toArray(new String[labels.size()]), (dialog, which) ->
                        dispatch(actions.get(which), host))
                .show();
    }

    private void dispatch(int action, final LanHost host) {
        switch (action) {
            case ACTION_SMB:
                if (host.mayOfferSmb()) {
                    openSmb(host);
                } else {
                    confirmUnopenedSmb(host);
                }
                break;
            case ACTION_BUILTIN_RDP:
                confirmIdentityIfNeeded(host, () -> launchBuiltinRdp(host));
                break;
            case ACTION_WEB:
                openWeb(host, LanServices.webPort(host.openPorts()));
                break;
            case ACTION_WEB_PORTS:
                showWebPorts(host);
                break;
            case ACTION_PORTS:
                showPorts(host);
                break;
            case ACTION_WAKE:
                wakeHost(host);
                break;
            case ACTION_IDENTIFY:
                identifyHost(host);
                break;
            case ACTION_SET_MAC:
                showMacDialog(host);
                break;
            case ACTION_ACCOUNT:
                CredentialDialog.show(this, credentials, host.ip,
                        getString(R.string.lan_cred_title_account, host.displayName()),
                        R.string.lan_cred_save, credential -> {
                            toast(getString(R.string.lan_cred_saved));
                            refreshList();
                        });
                break;
            case ACTION_FAVORITE:
                host.favorite = !host.favorite;
                devices.save(host);
                refreshList();
                toast(getString(host.favorite ? R.string.lan_favorited : R.string.lan_unfavorited,
                        host.ip));
                break;
            case ACTION_RECHECK:
                probeSingle(host, preset.presence, false);
                break;
            case ACTION_DETAIL:
                probeSingle(host, LanServices.detailPorts(), true);
                break;
            case ACTION_SAVE_RDP:
                saveToRdpBook(host);
                break;
            case ACTION_PROBE:
                probeSecurity(host);
                break;
            case ACTION_EXPORT_RDP:
                RdpLauncher.shareRdpFile(this, RdpConnection.fromHost(host),
                        credentials.get(host.ip));
                break;
            case ACTION_REMOVE:
                hosts.remove(host.ip);
                devices.remove(host.ip);
                refreshList();
                toast(getString(R.string.lan_removed_saved, host.ip));
                break;
            case ACTION_COPY:
                copyText(host.ip);
                break;
            default:
                confirmForget(host);
                break;
        }
    }

    /**
     * 探测这台的 RDP 安全层，结论写状态行。
     *
     * 内置客户端要不要实现 CredSSP/NTLM，完全取决于服务端是否强制 NLA，而这一点光看端口开不开
     * 是问不出来的——所以在最常用的入口上直接给一次提问的机会。
     */
    private void probeSecurity(final LanHost host) {
        tvStatus.setText(getString(R.string.rdp_probe_running, host.ip));
        probes.execute(() -> {
            final RdpProbe.Result result =
                    RdpProbe.run(host.ip, LanHost.PORT_RDP, PROBE_TIMEOUT_MS);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    tvStatus.setText(result.describe(this));
                }
            });
        });
    }

    private void saveToRdpBook(LanHost host) {
        String duplicated = new RdpBook(this).add(RdpConnection.fromHost(host));
        toast(getString(duplicated.isEmpty() ? R.string.lan_saved_rdp
                : R.string.rdp_duplicate, duplicated));
    }

    private void confirmUnopenedSmb(final LanHost host) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.lan_smb_not_open, host.ip))
                .setPositiveButton(R.string.lan_try_anyway, (dialog, which) -> openSmb(host))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchBuiltinRdp(LanHost host) {
        // 密码不经 Intent：会话页要用的时候在同一进程里向 CredentialStore 取
        Intent builtin = new Intent(this, RdpSessionActivity.class);
        builtin.putExtra(RdpSessionActivity.EXTRA_HOST, host.ip);
        builtin.putExtra(RdpSessionActivity.EXTRA_PORT, LanHost.PORT_RDP);
        builtin.putExtra(RdpSessionActivity.EXTRA_NAME, host.displayName());
        startActivity(builtin);
    }

    /** 同一 IP 上的 MAC 变了，说明可能是另一台设备；旧凭据不能不经确认就发过去。 */
    private void confirmIdentityIfNeeded(final LanHost host, final Runnable next) {
        if (!host.identityChanged || credentials.get(host.ip) == null) {
            next.run();
            return;
        }
        String oldMac = devices.macFor(host.ip);
        new AlertDialog.Builder(this)
                .setTitle(host.displayName())
                .setMessage(getString(R.string.lan_identity_changed_format,
                        oldMac == null ? "?" : oldMac, host.mac == null ? "?" : host.mac))
                .setPositiveButton(R.string.lan_identity_continue,
                        (dialog, which) -> next.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 有凭据直接进，没有才问；换账号靠菜单里的「删除已存凭据」。 */
    private void openSmb(final LanHost host) {
        confirmIdentityIfNeeded(host, () -> {
            if (credentials.get(host.ip) == null) {
                CredentialDialog.show(this, credentials, host.ip,
                        getString(R.string.lan_cred_title, host.displayName()),
                        R.string.lan_cred_connect, credential -> browse(host));
                return;
            }
            browse(host);
        });
    }

    private void wakeHost(final LanHost host) {
        if (host.mac == null || host.mac.isEmpty()) {
            toast(getString(R.string.lan_wake_no_mac));
            return;
        }
        final String broadcast = lastRange == null ? null : lastRange.broadcastAddress();
        tvStatus.setText(getString(R.string.lan_wake_sending, host.displayName()));
        probes.execute(() -> {
            final boolean sent = WakeOnLan.wake(host.mac, broadcast);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    tvStatus.setText(getString(sent ? R.string.lan_wake_sent_format
                            : R.string.lan_wake_failed_format, host.displayName(), host.mac));
                }
            });
        });
    }

    private void showMacDialog(final LanHost host) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("AA:BB:CC:DD:EE:FF");
        if (host.mac != null) {
            input.setText(host.mac);
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.lan_set_mac_title, host.displayName()))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String mac = LanMac.normalize(input.getText().toString());
                    if (mac == null) {
                        toast(getString(R.string.lan_bad_mac));
                        return;
                    }
                    host.mac = mac;
                    devices.save(host);
                    refreshList();
                    toast(getString(R.string.lan_mac_saved));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void identifyHost(final LanHost host) {
        if (scanning || busyIp != null) {
            toast(getString(R.string.lan_busy));
            return;
        }
        final Set<Integer> ports = host.openPorts();
        if (ports.isEmpty()) {
            return;
        }
        tvStatus.setText(getString(R.string.lan_identify_running, host.displayName()));
        probes.execute(() -> {
            final LanFingerprint.Result result =
                    LanFingerprint.identify(LanScanActivity.this, host.ip, ports);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (result == null) {
                    tvStatus.setText(getString(R.string.lan_identify_none, host.displayName()));
                    return;
                }
                host.fingerprint = result.summary;
                host.fingerprintDetail = result.detail;
                refreshList();
                tvStatus.setText(getString(R.string.lan_identify_done,
                        host.displayName(), result.summary));
                new AlertDialog.Builder(LanScanActivity.this)
                        .setTitle(getString(R.string.lan_identify_title, host.displayName()))
                        .setMessage(result.detail)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void browse(LanHost host) {
        Intent intent = new Intent(this, SmbBrowseActivity.class);
        intent.putExtra(SmbBrowseActivity.EXTRA_HOST_IP, host.ip);
        intent.putExtra(SmbBrowseActivity.EXTRA_HOST_NAME, host.displayName());
        startActivity(intent);
    }

    /**
     * 打开设备的网页入口：路由器、NAS、打印机大多把管理页面放在 80/443/8080 上，扫到了就不该
     * 让用户抄 IP 去浏览器里手输。HTTPS 端口优先，其余按 http 走。
     */
    private void openWeb(LanHost host, int port) {
        if (port <= 0) {
            return;
        }
        String url = LanServices.webUrl(host.ip, port);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            copyText(url);
            toast(getString(R.string.lan_web_no_browser));
        }
    }

    /** 同时开着 80/443/8080 的设备不少，菜单里只留一个入口会漏掉其它管理页。 */
    private void showWebPorts(final LanHost host) {
        final List<Integer> ports = LanServices.webPorts(host.openPorts());
        if (ports.isEmpty()) {
            return;
        }
        String[] labels = new String[ports.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = LanServices.webUrl(host.ip, ports.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.lan_web_ports_title, host.displayName()))
                .setItems(labels, (dialog, which) -> openWeb(host, ports.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 列表行只放前 6 个端口，详扫出的完整清单在这里看。 */
    private void showPorts(final LanHost host) {
        final List<Integer> ports = new ArrayList<>(host.openPorts());
        if (ports.isEmpty()) {
            return;
        }
        String[] labels = new String[ports.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = LanServices.label(this, ports.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.lan_ports_title, host.displayName(), ports.size()))
                .setItems(labels, (dialog, which) ->
                        copyText(host.ip + ":" + ports.get(which)))
                .setNeutralButton(R.string.lan_ports_copy_all,
                        (dialog, which) -> copyPorts(host, ports))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyPorts(LanHost host, List<Integer> ports) {
        StringBuilder all = new StringBuilder();
        for (int port : ports) {
            if (all.length() > 0) {
                all.append('\n');
            }
            all.append(host.ip).append(':').append(port);
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("lan", all.toString()));
        toast(getString(R.string.lan_ports_copied_all, ports.size()));
    }

    private void confirmForget(final LanHost host) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.lan_forget_confirm, host.ip))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    credentials.forget(host.ip);
                    toast(getString(R.string.lan_forgot, host.ip));
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 端口标签串。详扫可能一次开出二十几个端口，全列出来会把一行撑满，所以只显示前若干个，
     * 余下的折成「+N」，完整列表在操作菜单的「端口详情」里看。
     */
    private String portLabels(Set<Integer> ports) {
        List<String> labels = new ArrayList<>();
        for (int port : ports) {
            labels.add(LanServices.label(this, port));
        }
        if (labels.size() > PORT_LABEL_LIMIT) {
            int rest = labels.size() - PORT_LABEL_LIMIT;
            labels = labels.subList(0, PORT_LABEL_LIMIT);
            labels.add("+" + rest);
        }
        return TextUtils.join(" · ", labels);
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("lan", text));
        toast(getString(R.string.dns_copied, text));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private final class HostAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(LanScanActivity.this)
                            .inflate(R.layout.item_lan_host, parent, false);
            TextView tvName = (TextView) view.findViewById(R.id.tv_name);
            TextView tvRole = (TextView) view.findViewById(R.id.tv_role);
            TextView tvDetail = (TextView) view.findViewById(R.id.tv_detail);
            TextView tvPorts = (TextView) view.findViewById(R.id.tv_ports);

            LanHost host = visible.get(position);
            // 收藏与手动项靠前排列已经说明了身份，名称上加个标记，滚动时不必再看位置
            tvName.setText((host.favorite || host.manual ? "★ " : "")
                    + (host.newDevice ? getString(R.string.lan_new_prefix) + " " : "")
                    + host.displayName());
            tvRole.setText(describeRole(host));
            // 标题已经是 IP 时，明细不再重复一遍 IP，只把网卡地址补上
            StringBuilder detail = new StringBuilder();
            if (host.name != null && !host.name.isEmpty()) {
                detail.append(host.ip);
            }
            if (detail.length() > 0) {
                detail.append(" · ");
            }
            detail.append(host.mac == null
                    ? getString(R.string.lan_mac_unknown)
                    : getString(R.string.lan_mac_format, host.mac));
            if (host.rttMs > 0) {
                if (detail.length() > 0) {
                    detail.append(" · ");
                }
                detail.append(host.rttMs).append(" ms");
            }
            if (!host.seen && host.lastSeen > 0) {
                if (detail.length() > 0) {
                    detail.append(" · ");
                }
                detail.append(getString(R.string.lan_last_seen_format,
                        DateUtils.getRelativeTimeSpanString(host.lastSeen,
                                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)));
            }
            tvDetail.setText(detail);
            String ports = portLabels(host.openPorts());
            if (ports.isEmpty()) {
                // 分清楚「探过了确实没开」和「还没探」：手动添加的设备一开始属于后者
                if (host.mdnsPort > 0) {
                    tvPorts.setText(getString(R.string.lan_mdns_port_format, host.mdnsPort));
                } else {
                    tvPorts.setText(getString(host.probed ? R.string.lan_probed_no_ports
                            : R.string.lan_not_probed));
                }
            } else {
                tvPorts.setText(ports);
            }
            return view;
        }
    }
}
