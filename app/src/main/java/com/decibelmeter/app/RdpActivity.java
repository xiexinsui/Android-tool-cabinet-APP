package com.decibelmeter.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程桌面连接簿：把「连哪台机器」变成可以反复点的条目，而不是每次都要先扫一遍网段。
 *
 * 连接走内置客户端（{@link RdpSessionActivity}）。这个页面负责连接之前的事：记住目标、
 * 确认端口现在通不通、探测对端的安全层，以及把 .rdp 文件导出去给电脑用。
 *
 * 耗时的两件事（端口测试、写导出文件）走单线程 executor，回调一律切回主线程再动界面。
 */
public class RdpActivity extends BaseActivity {

    private static final int ACTION_TEST = 1;
    private static final int ACTION_ACCOUNT = 2;
    private static final int ACTION_EXPORT = 3;
    private static final int ACTION_COPY = 4;
    private static final int ACTION_EDIT = 5;
    private static final int ACTION_DELETE = 6;
    /** 探测要两次 TCP 往返，超时给得比端口测试宽松些。 */
    private static final int PROBE_TIMEOUT_MS = 6000;
    private static final int ACTION_BUILTIN = 7;
    private static final int ACTION_PROBE = 8;

    private final List<RdpConnection> items = new ArrayList<>();
    private final ConnectionAdapter adapter = new ConnectionAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RdpBook book;
    private CredentialStore credentials;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rdp);
        setTitle(R.string.title_rdp);

        book = new RdpBook(this);
        credentials = new CredentialStore(this);

        tvStatus = (TextView) findViewById(R.id.tv_status);
        ListView list = (ListView) findViewById(R.id.lv_connections);
        list.setEmptyView(findViewById(R.id.tv_empty));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showMenu(items.get(position)));
        ((Button) findViewById(R.id.btn_new)).setOnClickListener(v -> showEdit(null));

        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从扫描页「存到连接簿」过来时列表要立刻能看到新条目
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(book.load());
        adapter.notifyDataSetChanged();
    }

    private void showMenu(final RdpConnection connection) {
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        if (RdpNative.isAvailable()) {
            // 内置通道只有原生库加载成功才给：跑不了这个 ABI 的设备上看到「内置连接」却打不开，
            // 比看不到这个选项更糟
            labels.add(getString(R.string.rdp_action_builtin));
            actions.add(ACTION_BUILTIN);
        }
        labels.add(getString(R.string.rdp_action_test));
        actions.add(ACTION_TEST);
        labels.add(getString(R.string.rdp_probe_menu));
        actions.add(ACTION_PROBE);
        labels.add(getString(R.string.lan_action_account));
        actions.add(ACTION_ACCOUNT);
        labels.add(getString(R.string.rdp_action_export));
        actions.add(ACTION_EXPORT);
        labels.add(getString(R.string.lan_rdp_copy));
        actions.add(ACTION_COPY);
        labels.add(getString(R.string.rdp_action_edit));
        actions.add(ACTION_EDIT);
        labels.add(getString(R.string.rdp_action_delete));
        actions.add(ACTION_DELETE);
        new AlertDialog.Builder(this)
                .setTitle(connection.displayLabel())
                .setItems(labels.toArray(new String[labels.size()]), (dialog, which) ->
                        dispatch(actions.get(which), connection))
                .show();
    }

    private void dispatch(int action, final RdpConnection connection) {
        switch (action) {
            case ACTION_BUILTIN:
                openBuiltin(connection);
                break;
            case ACTION_TEST:
                testPort(connection);
                break;
            case ACTION_PROBE:
                probeSecurity(connection);
                break;
            case ACTION_ACCOUNT:
                CredentialDialog.show(this, credentials, connection.host,
                        getString(R.string.lan_cred_title_account, connection.displayLabel()),
                        R.string.lan_cred_save, credential ->
                                toast(getString(R.string.lan_cred_saved)));
                break;
            case ACTION_EXPORT:
                export(connection);
                break;
            case ACTION_COPY:
                copyText(RdpLauncher.buildUri(connection, credentials.get(connection.host))
                        .toString());
                break;
            case ACTION_EDIT:
                showEdit(connection);
                break;
            case ACTION_DELETE:
                confirmDelete(connection);
                break;
            default:
                break;
        }
    }

    /**
     * 走内置通道。
     *
     * 只把地址与端口放进 Intent——密码一律不经 Intent 传（会落进系统日志），原生层将来要用的
     * 时候在同一进程里向 {@link CredentialStore} 取。
     */
    private void openBuiltin(RdpConnection connection) {
        Intent intent = new Intent(this, RdpSessionActivity.class);
        intent.putExtra(RdpSessionActivity.EXTRA_HOST, connection.host);
        intent.putExtra(RdpSessionActivity.EXTRA_PORT, connection.port);
        intent.putExtra(RdpSessionActivity.EXTRA_NAME, connection.displayLabel());
        startActivity(intent);
    }

    /**
     * 端口连通性测试。
     *
     * 结论写进状态行而不是只弹 toast：toast 三秒就消失，而且连续两条在部分系统上会被限流
     * （实测 MIUI 13 第二条直接被吞），用户看不到刚等出来的答案。
     */
    private void testPort(final RdpConnection connection) {
        tvStatus.setText(getString(R.string.rdp_testing_format, connection.address()));
        executor.execute(() -> {
            final long millis = RdpLauncher.probe(connection.host, connection.port);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                tvStatus.setText(millis >= 0
                        ? getString(R.string.rdp_test_ok_format, connection.address(), millis)
                        : getString(R.string.rdp_test_failed_format, connection.address()));
            });
        });
    }

    /**
     * 安全层探测。结论也写状态行而不是只弹 toast：它决定内置客户端要实现到哪一层
     * （要不要 CredSSP + NTLM），是要拿去对照文档的一句话，不该三秒就消失。
     */
    private void probeSecurity(final RdpConnection connection) {
        tvStatus.setText(getString(R.string.rdp_probe_running, connection.address()));
        executor.execute(() -> {
            final RdpProbe.Result result =
                    RdpProbe.run(connection.host, connection.port, PROBE_TIMEOUT_MS);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    tvStatus.setText(result.describe(RdpActivity.this));
                }
            });
        });
    }

    private void export(RdpConnection connection) {
        RdpLauncher.shareRdpFile(this, connection, credentials.get(connection.host));
    }

    /** @param existing 非空表示编辑，空表示新建 */
    private void showEdit(final RdpConnection existing) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rdp_edit, null);
        final EditText etName = (EditText) content.findViewById(R.id.et_name);
        final EditText etHost = (EditText) content.findViewById(R.id.et_host);
        final EditText etPort = (EditText) content.findViewById(R.id.et_port);
        if (existing != null) {
            etName.setText(existing.name);
            etHost.setText(existing.host);
            etPort.setText(String.valueOf(existing.port));
        } else {
            etPort.setText(String.valueOf(LanHost.PORT_RDP));
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.rdp_add_title : R.string.rdp_edit_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String host = etHost.getText().toString().trim();
                    if (!isHost(host)) {
                        toast(getString(R.string.rdp_bad_host));
                        return;
                    }
                    int port;
                    try {
                        port = etPort.getText().toString().trim().isEmpty()
                                ? LanHost.PORT_RDP
                                : Integer.parseInt(etPort.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast(getString(R.string.rdp_bad_port));
                        return;
                    }
                    if (port < 1 || port > 65535) {
                        toast(getString(R.string.rdp_bad_port));
                        return;
                    }
                    save(new RdpConnection(etName.getText().toString().trim(), host, port),
                            existing);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save(RdpConnection connection, RdpConnection existing) {
        if (existing != null) {
            book.replace(existing, connection);
            toast(getString(R.string.rdp_saved, connection.displayLabel()));
        } else {
            String duplicated = book.add(connection);
            if (!duplicated.isEmpty()) {
                toast(getString(R.string.rdp_duplicate, duplicated));
            } else {
                toast(getString(R.string.rdp_saved, connection.displayLabel()));
            }
        }
        refresh();
    }

    private void confirmDelete(final RdpConnection connection) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.rdp_delete_confirm, connection.displayLabel()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    book.remove(connection);
                    refresh();
                    toast(getString(R.string.rdp_deleted, connection.displayLabel()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** IPv4 或主机名都收：NAS 往往只有名字没有固定地址，而 .rdp 里 host 也可以是 FQDN。 */
    private static boolean isHost(String text) {
        if (text.isEmpty() || text.length() > 126 || text.contains(" ")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 多了 IPv6 用到的字符：冒号、方括号、%zone
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ':' || c == '[' || c == ']'
                    || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("rdp", text));
        toast(getString(R.string.dns_copied, text));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private final class ConnectionAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(RdpActivity.this)
                            .inflate(R.layout.item_rdp_connection, parent, false);
            TextView tvName = (TextView) view.findViewById(R.id.tv_name);
            TextView tvMeta = (TextView) view.findViewById(R.id.tv_meta);

            RdpConnection connection = items.get(position);
            tvName.setText(connection.displayLabel());
            List<String> parts = new ArrayList<>();
            parts.add(connection.address());
            CredentialStore.Credential credential = credentials.get(connection.host);
            if (credential != null && !credential.user.isEmpty()) {
                parts.add(credential.domain.isEmpty() ? credential.user
                        : credential.domain + "\\" + credential.user);
            } else {
                parts.add(getString(R.string.rdp_no_account));
            }
            // 只有真存了密码才说「已存密码」：勾了记住密码但密码留空（来宾访问）时，
            // 标一句「已存密码」会让人以为本机藏着一个能登录的口令
            if (credentials.isStored(connection.host) && credential != null
                    && !credential.password.isEmpty()) {
                parts.add(getString(R.string.rdp_password_saved));
            }
            tvMeta.setText(TextUtils.join(" · ", parts));
            return view;
        }
    }
}
