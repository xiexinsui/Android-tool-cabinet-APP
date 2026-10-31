package com.decibelmeter.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 域名解析：把域名查询成 IP 地址。
 *
 * 原理：DNS 里 A 记录对应 IPv4、AAAA 记录对应 IPv6，一次 {@link InetAddress#getAllByName}
 * 会同时取回两类记录，因此本页面能并列显示双栈地址（动态域名的 IP 会变，可用标题栏刷新按钮
 * 或长按单条域名重新查询）。查询是网络操作、可能阻塞数秒，统一放到单线程池执行，
 * 结果回填后再刷新列表。
 * 域名由用户自己添加，只保存在本机 SharedPreferences，代码与文档里不预置任何真实域名。
 */
public class DnsActivity extends BaseActivity {

    private static final String PREFS = "dns";
    private static final String KEY_DOMAINS = "domains";
    private static final int MENU_RESOLVE_ALL = Menu.FIRST + 1;

    private static final class Result {
        final List<String> ipv4;
        final List<String> ipv6;
        /** null 表示查询成功，否则为异常信息原文。 */
        final String error;
        final long timeMillis;

        Result(List<String> ipv4, List<String> ipv6) {
            this.ipv4 = ipv4;
            this.ipv6 = ipv6;
            this.error = null;
            this.timeMillis = System.currentTimeMillis();
        }

        Result(String error) {
            this.ipv4 = new ArrayList<>();
            this.ipv6 = new ArrayList<>();
            this.error = error;
            this.timeMillis = System.currentTimeMillis();
        }
    }

    private SharedPreferences prefs;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final List<String> domains = new ArrayList<>();
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BaseAdapter adapter = new DomainAdapter();

    private EditText etDomain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns);
        setTitle(R.string.title_dns);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadDomains();

        etDomain = findViewById(R.id.et_domain);
        findViewById(R.id.btn_resolve).setOnClickListener(v -> submitInput());
        etDomain.setOnEditorActionListener((v, actionId, event) -> {
            submitInput();
            return true;
        });
        ListView list = findViewById(R.id.lv_domains);
        list.setEmptyView(findViewById(R.id.tv_empty));
        list.setAdapter(adapter);

        resolveAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // 刷新是本页最高频的操作，直接放标题栏按钮，不进溢出菜单
        MenuItem refresh = menu.add(0, MENU_RESOLVE_ALL, 1, R.string.dns_resolve_all);
        refresh.setIcon(R.drawable.ic_dns_refresh);
        refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_RESOLVE_ALL) {
            resolveAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadDomains() {
        List<String> loaded = new ArrayList<>();
        String saved = prefs.getString(KEY_DOMAINS, "");
        for (String line : saved.split("\n")) {
            String domain = line.trim();
            if (!domain.isEmpty()) {
                loaded.add(domain);
            }
        }
        domains.clear();
        // LinkedHashSet 顺带去重，同时保持添加顺序
        domains.addAll(new LinkedHashSet<>(loaded));
    }

    private void saveDomains() {
        prefs.edit().putString(KEY_DOMAINS, TextUtils.join("\n", domains)).apply();
    }

    private void submitInput() {
        String domain = normalize(etDomain.getText().toString());
        if (domain.isEmpty()) {
            toast(getString(R.string.dns_empty_input));
            return;
        }
        etDomain.setText("");
        if (!domains.contains(domain)) {
            domains.add(domain);
            saveDomains();
        }
        adapter.notifyDataSetChanged();
        resolveAsync(domain);
    }

    /** 容忍粘贴整段网址、大小写混写、结尾多点号等常见输入形式。 */
    private static String normalize(String raw) {
        String s = raw.trim().toLowerCase(Locale.US);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void resolveAll() {
        for (String domain : new ArrayList<>(domains)) {
            resolveAsync(domain);
        }
    }

    /** 同一域名只允许一个查询在排队/执行，避免重复点按堆积请求。 */
    private void resolveAsync(String domain) {
        if (!pending.add(domain)) {
            return;
        }
        executor.execute(() -> {
            Result result = lookup(domain);
            results.put(domain, result);
            pending.remove(domain);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private Result lookup(String domain) {
        List<String> v4 = new ArrayList<>();
        List<String> v6 = new ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(domain)) {
                // 链路本地 IPv6 会带 %wlan0 之类的接口后缀，展示纯地址
                String text = address.getHostAddress();
                int scope = text.indexOf('%');
                if (scope > 0) {
                    text = text.substring(0, scope);
                }
                if (address instanceof Inet6Address) {
                    v6.add(text);
                } else if (address instanceof Inet4Address) {
                    v4.add(text);
                }
            }
        } catch (Exception e) {
            return new Result(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return new Result(v4, v6);
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("dns", text));
        toast(getString(R.string.dns_copied, text));
    }

    private void showDomainMenu(String domain) {
        String[] items = {
                getString(R.string.dns_action_rdp),
                getString(R.string.dns_copy_domain),
                getString(R.string.dns_reparse),
                getString(R.string.dns_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(domain)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        openRdp(domain);
                    } else if (which == 1) {
                        copyText(domain);
                    } else if (which == 2) {
                        resolveAsync(domain);
                    } else {
                        confirmDelete(domain);
                    }
                })
                .show();
    }

    /**
     * 用这个域名开远程桌面。
     *
     * 不在这里先把 IP 解出来再传过去：会话页自己会解析并把结果写进状态行，而且换个网络
     * 解析结果会变——连接簿里存域名比存某一次解析出来的 IP 更合适。
     */
    private void openRdp(String domain) {
        Intent intent = new Intent(this, RdpSessionActivity.class);
        intent.putExtra(RdpSessionActivity.EXTRA_HOST, domain);
        intent.putExtra(RdpSessionActivity.EXTRA_PORT, LanHost.PORT_RDP);
        intent.putExtra(RdpSessionActivity.EXTRA_NAME, domain);
        startActivity(intent);
    }

    private void confirmDelete(String domain) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.dns_delete_confirm, domain))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    domains.remove(domain);
                    results.remove(domain);
                    saveDomains();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class DomainAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return domains.size();
        }

        @Override
        public Object getItem(int position) {
            return domains.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(DnsActivity.this)
                            .inflate(R.layout.item_dns_domain, parent, false);
            TextView tvDomain = view.findViewById(R.id.tv_domain);
            TextView tvMeta = view.findViewById(R.id.tv_meta);
            LinearLayout llAddresses = view.findViewById(R.id.ll_addresses);

            String domain = domains.get(position);
            tvDomain.setText(domain);
            tvDomain.setOnLongClickListener(v -> {
                showDomainMenu(domain);
                return true;
            });
            llAddresses.removeAllViews();

            Result result = pending.contains(domain) ? null : results.get(domain);
            if (result == null) {
                tvMeta.setText(R.string.dns_resolving);
            } else if (result.error != null) {
                tvMeta.setText(getString(R.string.dns_failed, result.error));
            } else {
                tvMeta.setText(getString(R.string.dns_result_format,
                        result.ipv4.size(), result.ipv6.size(),
                        timeFormat.format(new Date(result.timeMillis))));
                addAddresses(llAddresses, "IPv4", result.ipv4);
                addAddresses(llAddresses, "IPv6", result.ipv6);
            }
            return view;
        }

        private void addAddresses(LinearLayout container, String label, List<String> addresses) {
            for (String address : addresses) {
                TextView tv = new TextView(DnsActivity.this);
                tv.setText(label + "  " + address);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setPadding(0, dp(2), 0, dp(2));
                tv.setOnLongClickListener(v -> {
                    copyText(address);
                    return true;
                });
                container.addView(tv);
            }
        }
    }
}
