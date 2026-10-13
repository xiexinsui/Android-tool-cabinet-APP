package com.decibelmeter.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * STM32 固件下载页（工具箱的第 3 个工具）。
 * <p>
 * 工作方式：手机通过 OTG 接 USB 转串口小板（CH340 等），小板的 TX/RX 接芯片的串口引脚。
 * 芯片上电时若 BOOT0=1，会从片内固化的 Bootloader 启动，它听得懂 AN3155 串口 ISP 协议，
 * 于是手机就能完成「擦除 -> 写入 -> 校验 -> 跳转运行」这一整套烧录动作，
 * 不再需要 ST-Link/J-Link 这类调试器。
 * <p>
 * 线程模型：串口读写是阻塞操作，全部放在单线程池里跑，界面只负责投递任务和接收回调；
 * 「中止」按钮设置 volatile 标志，协议层的每个等待循环都会检查它。
 */
public class IspActivity extends BaseActivity {

    private static final String PREFS = "isp";
    private static final String KEY_BAUD_INDEX = "baudIndex";
    private static final String KEY_MODE = "bootMode";
    private static final String KEY_DELAY = "delayMs";
    private static final String KEY_VERIFY = "verifyEachPage";
    private static final String KEY_RUN_AFTER = "runAfter";
    private static final String KEY_BIN_ADDR = "binAddress";
    private static final String KEY_DEVICE = "deviceIds";
    private static final String KEY_CHIP_EXPANDED = "chipExpanded";
    /** 最近打开的固件：每行「URI · 文件名 · 字节数」，用分隔符拼成一条字符串存起来。 */
    private static final String KEY_RECENTS = "recentFirmware";
    private static final int MAX_RECENTS = 5;
    private static final String RECENT_SEPARATOR = "\u001F";

    private static final String ACTION_USB_PERMISSION = "com.decibelmeter.app.USB_PERMISSION";

    private static final int REQUEST_OPEN_FIRMWARE = 1001;
    private static final int REQUEST_SAVE_FLASH = 1002;
    private static final int REQUEST_SAVE_LOG = 1003;

    /** 日志缓冲区上限（字符数）：超限丢弃最前面一半，避免长时间跑把内存吃满。 */
    private static final int LOG_LIMIT = 120000;
    /**
     * 日志合并刷新间隔。
     *
     * 下载时每写一页会产生好几行日志（写命令、ACK、校验读、数据），
     * 若每行都立刻 setText 整段文本，TextView 要重新排版已有的一二十万字符，
     * 界面就会被拖死（实测下载几十页后明显卡顿）。这里改成攒够一批再增量追加。
     */
    private static final long LOG_FLUSH_INTERVAL_MS = 150;
    /** 进度回调最小间隔，避免每页都往主线程投一条消息。 */
    private static final long PROGRESS_INTERVAL_MS = 100;

    private SharedPreferences prefs;
    private UsbManager usbManager;
    private final List<UsbSerialDriver> drivers = new ArrayList<>();

    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private IspSession session;
    private String portDescription = "";

    private FirmwareImage firmware;
    private String firmwareName = "";
    /** 最近打开过的固件：{URI 字符串, 文件名, 字节数}，越靠前越新 */
    private final List<String[]> recentFiles = new ArrayList<>();
    private LinearLayout llRecent;
    private TextView tvRecentTitle;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean cancelled;
    /** 本次任务里是否出现过 ERROR 级日志，用于结束时给出可见的失败提示。 */
    private volatile boolean taskHadError;

    private final SimpleDateFormat timestamp =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder logBuffer = new StringBuilder();
    /** 后台线程写入、等待刷新到界面的日志行 */
    private final StringBuilder pendingLines = new StringBuilder();
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean logFlushScheduled;
    private long lastProgressUptime;

    private Spinner spDevice;
    private Spinner spBaud;
    private Spinner spBootMode;
    private EditText etDelay;
    private EditText etBinAddr;
    private CheckBox cbVerify;
    private CheckBox cbRunAfter;
    private TextView tvUsbState;
    private TextView tvFirmware;
    private TextView tvChip;
    private TextView tvChipSummary;
    private TextView tvChipToggle;
    private boolean chipExpanded;
    private TextView tvLog;
    private TextView tvProgress;
    private ScrollView svLog;
    private ProgressBar pbProgress;
    private Button btnConnect;

    private final IspLog log = new IspLog() {
        @Override
        public void log(int level, String message) {
            if (level == IspLog.LEVEL_ERROR) {
                taskHadError = true;
            }
            appendLog(level, message);
        }

        @Override
        public void progress(int percent, int status, String detail) {
            long now = android.os.SystemClock.uptimeMillis();
            // 完成/失败必须立刻显示，中间进度按时间节流
            if (percent != 100 && status != IspLog.STATUS_FAILED
                    && now - lastProgressUptime < PROGRESS_INTERVAL_MS) {
                return;
            }
            lastProgressUptime = now;
            String text = statusText(status)
                    + (detail == null || detail.isEmpty() ? "" : "  " + detail)
                    + "  " + percent + "%";
            runOnUiThread(() -> {
                pbProgress.setProgress(percent);
                tvProgress.setText(text);
            });
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @SuppressWarnings("deprecation") // 兼容 API 33 以下仍要用无 Class 参数的旧写法
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                        : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    openSelectedDriver();
                } else {
                    toast(getString(R.string.isp_permission_needed));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                runOnUiThread(IspActivity.this::closePort);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_isp);
        setTitle(R.string.title_isp);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        spDevice = findViewById(R.id.sp_device);
        spBaud = findViewById(R.id.sp_baud);
        spBootMode = findViewById(R.id.sp_boot_mode);
        etDelay = findViewById(R.id.et_delay);
        etBinAddr = findViewById(R.id.et_bin_addr);
        cbVerify = findViewById(R.id.cb_verify);
        cbRunAfter = findViewById(R.id.cb_run_after);
        tvUsbState = findViewById(R.id.tv_usb_state);
        tvFirmware = findViewById(R.id.tv_firmware);
        tvChip = findViewById(R.id.tv_chip);
        tvChipSummary = findViewById(R.id.tv_chip_summary);
        tvChipToggle = findViewById(R.id.tv_chip_toggle);
        tvLog = findViewById(R.id.tv_log);
        tvProgress = findViewById(R.id.tv_progress);
        svLog = findViewById(R.id.sv_log);
        pbProgress = findViewById(R.id.pb_progress);
        btnConnect = findViewById(R.id.btn_connect);
        // 设成 EDITABLE 缓冲，日志才能用 append() 增量追加（见 flushLogToView）
        tvLog.setText("", TextView.BufferType.EDITABLE);
        // 日志区是嵌在页面滚动容器里的子滚动容器：不禁止父级拦截的话，
        // 手指上下滑动永远是被外层页面抢走，日志根本翻不动。
        // tvLog 开了可选中文本、会先吃掉 ACTION_DOWN，所以两个控件都要挂。
        View.OnTouchListener keepLogScroll = (view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            // 返回 false 让 ScrollView / TextView 继续处理滚动与文本选择
            return false;
        };
        svLog.setOnTouchListener(keepLogScroll);
        tvLog.setOnTouchListener(keepLogScroll);

        // 芯片信息默认只占一行（型号 + 容量 + 保护），点标题行才展开完整内容
        findViewById(R.id.ll_chip_header).setOnClickListener(v -> setChipExpanded(!chipExpanded));
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> clearLog());
        findViewById(R.id.btn_save_log).setOnClickListener(v -> exportLog());
        findViewById(R.id.btn_log_full).setOnClickListener(v -> showFullLog());
        setChipExpanded(prefs.getBoolean(KEY_CHIP_EXPANDED, false));

        llRecent = findViewById(R.id.ll_recent);
        tvRecentTitle = findViewById(R.id.tv_recent_title);
        loadRecents();
        renderRecents();

        spBaud.setAdapter(simpleAdapter(getResources().getStringArray(R.array.isp_baud_entries)));
        spBootMode.setAdapter(simpleAdapter(getResources().getStringArray(R.array.isp_boot_modes)));
        spBaud.setSelection(prefs.getInt(KEY_BAUD_INDEX, 4));
        spBootMode.setSelection(prefs.getInt(KEY_MODE, 1));
        // 已连接时改波特率要立刻作用到串口，否则界面参数和实际线路速率不一致
        spBaud.setOnItemSelectedListener(new SpinnerChoice() {
            @Override
            public void onItemSelected(int position) {
                if (port != null) {
                    try {
                        port.setParameters(currentBaud(), UsbSerialPort.DATABITS_8,
                                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN);
                        appendLog(IspLog.LEVEL_INFO, "8E1 @ " + currentBaud());
                    } catch (Exception e) {
                        appendLog(IspLog.LEVEL_ERROR,
                                getString(R.string.isp_open_failed, String.valueOf(e.getMessage())));
                    }
                }
            }
        });
        etDelay.setText(String.valueOf(prefs.getInt(KEY_DELAY, 100)));
        etBinAddr.setText(prefs.getString(KEY_BIN_ADDR, "08000000"));
        cbVerify.setChecked(prefs.getBoolean(KEY_VERIFY, true));
        cbRunAfter.setChecked(prefs.getBoolean(KEY_RUN_AFTER, true));

        findViewById(R.id.btn_rescan).setOnClickListener(v -> scanDevices());
        btnConnect.setOnClickListener(v -> {
            if (port != null) {
                closePort();
            } else {
                connectSelected();
            }
        });
        findViewById(R.id.btn_pick_file).setOnClickListener(v -> pickFirmware());
        findViewById(R.id.btn_download).setOnClickListener(v -> startDownload());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            cancelled = true;
            appendLog(IspLog.LEVEL_WARN, getString(R.string.isp_cancel));
        });
        findViewById(R.id.btn_erase).setOnClickListener(v -> confirmErase());
        findViewById(R.id.btn_verify).setOnClickListener(v -> {
            // 回读校验前先确认已连接且选了固件，避免后台任务里才报「未选择」
            if (!requireConnection() || !requireFirmware()) {
                return;
            }
            session.setFirmware(firmware);
            runTask(() -> session.verifyDownloadedFirmware());
        });
        findViewById(R.id.btn_run).setOnClickListener(v -> runTask(this::taskRun));

        registerUsbReceiver();
        scanDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        uiHandler.removeCallbacks(logFlushTask);
        executor.shutdownNow();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
            // 未注册成功时（极少见）忽略
        }
        closePort();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_IDENTIFY, 0, R.string.isp_menu_identify);
        menu.add(0, MENU_READ_FLASH, 1, R.string.isp_menu_read_flash);
        menu.add(0, MENU_OPTION_BYTES, 2, R.string.isp_menu_option_bytes);
        menu.add(0, MENU_READ_MEMORY, 3, R.string.isp_menu_read_memory);
        menu.add(0, MENU_DEBUG, 4, R.string.isp_menu_debug);
        menu.add(0, MENU_DRIVER, 5, R.string.isp_menu_driver);
        menu.add(0, MENU_HELP, 6, R.string.isp_menu_help);
        menu.add(0, MENU_HELP, 6, R.string.isp_menu_help);
        menu.add(0, MENU_SAVE_LOG, 7, R.string.isp_menu_save_log);
        menu.add(0, MENU_CLEAR_LOG, 8, R.string.isp_menu_clear_log);
        return true;
    }

    private static final int MENU_IDENTIFY = Menu.FIRST + 1;
    private static final int MENU_READ_FLASH = Menu.FIRST + 2;
    private static final int MENU_OPTION_BYTES = Menu.FIRST + 3;
    private static final int MENU_READ_MEMORY = Menu.FIRST + 4;
    private static final int MENU_DEBUG = Menu.FIRST + 5;
    private static final int MENU_DRIVER = Menu.FIRST + 6;
    private static final int MENU_HELP = Menu.FIRST + 7;
    private static final int MENU_CLEAR_LOG = Menu.FIRST + 8;
    private static final int MENU_SAVE_LOG = Menu.FIRST + 9;

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_IDENTIFY) {
            runTask(this::taskIdentify);
            return true;
        }
        if (id == MENU_READ_FLASH) {
            readFlashAndSave();
            return true;
        }
        if (id == MENU_OPTION_BYTES) {
            showOptionBytesDialog();
            return true;
        }
        if (id == MENU_READ_MEMORY) {
            showReadMemoryDialog();
            return true;
        }
        if (id == MENU_DEBUG) {
            showDebugDialog();
            return true;
        }
        if (id == MENU_DRIVER) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.isp_driver_note_title)
                    .setMessage(R.string.isp_driver_note)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        if (id == MENU_HELP) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.isp_menu_help)
                    .setMessage(R.string.isp_help_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        if (id == MENU_SAVE_LOG) {
            exportLog();
            return true;
        }
        if (id == MENU_CLEAR_LOG) {
            clearLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------- USB 设备 ----------------

    /** 列出驱动库认识的 USB 串口设备，并把上次用的那台排到默认选中位置。 */
    private void scanDevices() {
        drivers.clear();
        drivers.addAll(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager));
        List<String> names = new ArrayList<>();
        String wanted = prefs.getString(KEY_DEVICE, "");
        int selected = 0;
        for (int i = 0; i < drivers.size(); i++) {
            names.add(describeDriver(drivers.get(i)));
            if (wanted.equals(deviceId(drivers.get(i)))) {
                selected = i;
            }
        }
        if (names.isEmpty()) {
            names.add(getString(R.string.isp_no_device));
            selected = 0;
        }
        spDevice.setAdapter(simpleAdapter(names));
        btnConnect.setEnabled(!drivers.isEmpty());
        // 刚设置完 adapter 时选中项还没生效，post 一次确保恢复上次选择的设备
        final int position = selected;
        spDevice.post(() -> spDevice.setSelection(position));
    }

    private static String deviceId(UsbSerialDriver driver) {
        UsbDevice device = driver.getDevice();
        return device.getVendorId() + ":" + device.getProductId();
    }

    /** 未授予 USB 权限前，Android 不返回产品名，因此优先显示芯片类型 + VID:PID。 */
    private String describeDriver(UsbSerialDriver driver) {
        UsbDevice device = driver.getDevice();
        String chip = driver.getClass().getSimpleName().replace("SerialDriver", "");
        String product = device.getProductName();
        String suffix = product == null || product.isEmpty() ? "" : " " + product;
        return String.format(Locale.US, "%s  %04X:%04X%s", chip,
                device.getVendorId(), device.getProductId(), suffix);
    }

    private void connectSelected() {
        if (drivers.isEmpty()) {
            scanDevices();
        }
        if (drivers.isEmpty()) {
            toast(getString(R.string.isp_no_device));
            return;
        }
        UsbSerialDriver driver = drivers.get(spDevice.getSelectedItemPosition());
        UsbDevice device = driver.getDevice();
        prefs.edit().putString(KEY_DEVICE, deviceId(driver)).apply();
        if (!usbManager.hasPermission(device)) {
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            // 指定包名，保证只广播给本应用（Android 13+ 要求接收器注册为非导出）
            intent.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pendingIntent);
            toast(getString(R.string.isp_permission_needed));
            return;
        }
        openSelectedDriver();
    }

    private void openSelectedDriver() {
        int position = spDevice.getSelectedItemPosition();
        if (position < 0 || position >= drivers.size()) {
            return;
        }
        UsbSerialDriver driver = drivers.get(position);
        if (driver.getPorts().isEmpty()) {
            toast(getString(R.string.isp_device_unsupported, describeDriver(driver)));
            return;
        }
        try {
            connection = usbManager.openDevice(driver.getDevice());
            if (connection == null) {
                toast(getString(R.string.isp_permission_needed));
                return;
            }
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(currentBaud(), UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN);
            portDescription = describeDriver(driver);
            session = new IspSession(port, log);
            applySettings();
            btnConnect.setText(R.string.isp_disconnect);
            tvUsbState.setText(getString(R.string.isp_usb_connected, portDescription));
            tvUsbState.setTextColor(themeColor(android.R.attr.colorAccent));
            appendLog(IspLog.LEVEL_SUCCESS, "串口已打开: " + portDescription
                    + " @ " + currentBaud() + " 8E1");
        } catch (Exception e) {
            appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_open_failed,
                    e.getMessage() == null ? e.toString() : e.getMessage()));
            closePort();
        }
    }

    private void closePort() {
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) {
                // 设备已拔出时关闭会抛异常，忽略
            }
        }
        if (connection != null) {
            connection.close();
        }
        port = null;
        connection = null;
        session = null;
        portDescription = "";
        if (btnConnect != null) {
            btnConnect.setText(R.string.isp_connect);
            tvUsbState.setText(R.string.isp_usb_disconnected);
            tvUsbState.setTextColor(themeColor(android.R.attr.textColorSecondary));
        }
    }

    /** 取主题里的颜色属性，避免写死颜色导致深色模式下看不清。 */
    private int themeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    private int currentBaud() {
        String text = getResources().getStringArray(R.array.isp_baud_entries)
                [spBaud.getSelectedItemPosition()];
        return Integer.parseInt(text);
    }

    private int currentDelay() {
        int value = parseInt(etDelay.getText().toString(), -1);
        return value > 0 && value <= 2000 ? value : 100;
    }

    /** 把界面上的参数同步给会话（连接后仍可改，改完立刻生效）。 */
    private void applySettings() {
        if (session == null) {
            return;
        }
        session.configure(spBootMode.getSelectedItemPosition(), currentDelay(), currentBaud());
    }

    private boolean requireConnection() {
        if (port == null || session == null) {
            toast(getString(R.string.isp_need_connection));
            return false;
        }
        return true;
    }

    private boolean requireFirmware() {
        if (firmware == null) {
            toast(getString(R.string.isp_need_firmware));
            return false;
        }
        return true;
    }

    private void saveSettings() {
        prefs.edit()
                .putInt(KEY_BAUD_INDEX, spBaud.getSelectedItemPosition())
                .putInt(KEY_MODE, spBootMode.getSelectedItemPosition())
                .putInt(KEY_DELAY, currentDelay())
                .putBoolean(KEY_VERIFY, cbVerify.isChecked())
                .putBoolean(KEY_RUN_AFTER, cbRunAfter.isChecked())
                .putString(KEY_BIN_ADDR, etBinAddr.getText().toString())
                .apply();
    }

    // ---------------- 固件文件 ----------------

    // 文件选择/保存用经典的 startActivityForResult：官方推荐的 ActivityResultLauncher 在
    // androidx.activity 里，本工程刻意不引入 AndroidX，因此这里的过时提示是有意保留的。
    @SuppressWarnings("deprecation")
    private void pickFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // 带上这两个标志才能 takePersistableUriPermission，退出应用后仍可重新读取该文件
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_FIRMWARE);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_OPEN_FIRMWARE) {
            loadFirmware(uri);
        } else if (requestCode == REQUEST_SAVE_FLASH) {
            saveFlashImage(uri);
        } else if (requestCode == REQUEST_SAVE_LOG) {
            saveLog(uri);
        }
    }

    /** 读入固件文件：按扩展名区分 BIN 和 Intel HEX（HEX 自带地址，BIN 用界面填写的起始地址）。 */
    private void loadFirmware(Uri uri) {
        byte[] bytes;
        try {
            bytes = readAll(uri);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_file_read_failed, reason));
            // 从最近列表进来时文件可能已被删除或授权被撤销，顺手把它清掉
            if (removeRecent(uri)) {
                toast(getString(R.string.isp_recent_missing, displayName(uri)));
                renderRecents();
            }
            return;
        }
        String name = displayName(uri);
        firmwareName = name;
        boolean isHex = name.toLowerCase(Locale.US).endsWith(".hex");
        try {
            if (isHex) {
                firmware = FirmwareImage.parseIntelHex(
                        new String(bytes, StandardCharsets.US_ASCII));
            } else if (name.toLowerCase(Locale.US).endsWith(".bin")) {
                if (bytes.length == 0) {
                    toast(getString(R.string.isp_bin_empty));
                    return;
                }
                if (bytes.length > 8 * 1024 * 1024) {
                    toast(getString(R.string.isp_bin_too_large, bytes.length));
                    return;
                }
                long start = parseAddress(etBinAddr.getText().toString());
                if (start < 0) {
                    toast(getString(R.string.isp_invalid_addr));
                    return;
                }
                firmware = FirmwareImage.fromBin(bytes, start);
            } else {
                firmware = null;
                toast(getString(R.string.isp_unsupported_format));
                appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_unsupported_format)
                        + " " + name);
                return;
            }
        } catch (FirmwareImage.HexFormatException e) {
            firmware = null;
            String message = hexErrorMessage(e);
            toast(message);
            appendLog(IspLog.LEVEL_ERROR, message);
            return;
        }
        refreshFirmwareLabel();
        rememberFirmware(uri, bytes.length);
        appendLog(IspLog.LEVEL_SUCCESS, "固件已加载: " + firmwareName);
    }

    // ---------------- 最近打开的固件 ----------------

    /** 读 SharedPreferences，丢掉已经失效（文件被删或授权被撤）的条目。 */
    private void loadRecents() {
        recentFiles.clear();
        String saved = prefs.getString(KEY_RECENTS, "");
        for (String line : saved.split("\n")) {
            String[] parts = line.split(RECENT_SEPARATOR);
            if (parts.length == 3 && hasPersistedRead(Uri.parse(parts[0]))) {
                recentFiles.add(parts);
            }
        }
    }

    private void saveRecents() {
        StringBuilder sb = new StringBuilder();
        for (String[] item : recentFiles) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item[0]).append(RECENT_SEPARATOR).append(item[1])
                    .append(RECENT_SEPARATOR).append(item[2]);
        }
        prefs.edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    /** 系统是否还允许我们读这个 URI（用户撤销授权或文件被删后会变 false）。 */
    private boolean hasPersistedRead(Uri uri) {
        String text = uri.toString();
        for (android.content.UriPermission permission
                : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().toString().equals(text)) {
                return permission.isReadPermission();
            }
        }
        return false;
    }

    /** 加载成功后记入最近列表：留住长期授权、去重、最多保留 5 条。 */
    private void rememberFirmware(Uri uri, int size) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // 个别文件管理器不给长期授权，只是下次要重新选文件，不影响本次下载
            appendLog(IspLog.LEVEL_TRACE, "无法记住该文件：" + e.getMessage());
            return;
        }
        String text = uri.toString();
        for (int i = recentFiles.size() - 1; i >= 0; i--) {
            if (recentFiles.get(i)[0].equals(text)) {
                recentFiles.remove(i);
            }
        }
        recentFiles.add(0, new String[]{text, firmwareName, String.valueOf(size)});
        while (recentFiles.size() > MAX_RECENTS) {
            recentFiles.remove(recentFiles.size() - 1);
        }
        saveRecents();
        renderRecents();
    }

    /** 移除一条最近记录，返回是否确实删掉了。 */
    private boolean removeRecent(Uri uri) {
        String text = uri.toString();
        boolean changed = false;
        for (int i = recentFiles.size() - 1; i >= 0; i--) {
            if (recentFiles.get(i)[0].equals(text)) {
                recentFiles.remove(i);
                changed = true;
            }
        }
        if (changed) {
            saveRecents();
            try {
                getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // 没有长期授权时释放会抛异常，忽略
            }
        }
        return changed;
    }

    /**
     * 渲染最近固件列表。
     *
     * 条目数量随用户打开的文件变化，所以用代码往布局预留的 ll_recent 容器里加 View；
     * 容器本身在 activity_isp.xml 里，位置仍可在 Layout Editor 中调整。
     */
    private void renderRecents() {
        llRecent.removeAllViews();
        boolean empty = recentFiles.isEmpty();
        tvRecentTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        llRecent.setVisibility(empty ? View.GONE : View.VISIBLE);
        for (final String[] item : recentFiles) {
            final Uri uri = Uri.parse(item[0]);
            TextView row = new TextView(this);
            long size = parseLong(item[2]);
            row.setText(getString(R.string.isp_recent_item, item[1],
                    String.format(Locale.getDefault(), "%,d", size)));
            row.setTextSize(14);
            row.setTypeface(android.graphics.Typeface.MONOSPACE);
            // 文件名可能很长（甚至带路径片段），单行 + 中间省略，保证末尾的字节数始终可见
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setPadding(dp(6), dp(9), dp(6), dp(9));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            // 当前已加载的那条用强调色标出，其余用正文色（都取主题属性，深浅色自适应）
            boolean loaded = !firmwareName.isEmpty() && firmwareName.equals(item[1]);
            row.setTextColor(themeColor(loaded ? android.R.attr.colorAccent
                    : android.R.attr.textColorPrimary));
            row.setOnClickListener(v -> loadFirmware(uri));
            row.setOnLongClickListener(v -> {
                confirmRemoveRecent(uri, item[1]);
                return true;
            });
            llRecent.addView(row);
        }
    }

    private void confirmRemoveRecent(final Uri uri, final String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{getString(R.string.isp_recent_remove)},
                        (dialog, which) -> {
                            if (removeRecent(uri)) {
                                renderRecents();
                                toast(getString(R.string.isp_recent_removed, name));
                            }
                        })
                .show();
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String hexErrorMessage(FirmwareImage.HexFormatException e) {
        String text;
        switch (e.kind) {
            case FirmwareImage.ERR_BAD_LENGTH:
                text = getString(R.string.isp_hex_bad_length, e.line);
                break;
            case FirmwareImage.ERR_LINE_TOO_LONG:
                text = getString(R.string.isp_hex_line_too_long, e.line);
                break;
            case FirmwareImage.ERR_BAD_HEX:
                text = getString(R.string.isp_hex_bad_char, e.line);
                break;
            case FirmwareImage.ERR_DATA_SHORT:
                text = getString(R.string.isp_hex_data_short, e.line);
                break;
            case FirmwareImage.ERR_CHECKSUM:
                text = getString(R.string.isp_hex_checksum, e.line);
                break;
            case FirmwareImage.ERR_NO_RECORD:
                text = getString(R.string.isp_hex_no_record);
                break;
            default:
                text = getString(R.string.isp_hex_no_colon, e.line);
                break;
        }
        return text;
    }

    private void refreshFirmwareLabel() {
        if (firmware == null) {
            tvFirmware.setText(R.string.isp_firmware_none);
            return;
        }
        tvFirmware.setText(getString(R.string.isp_firmware_loaded, firmwareName,
                String.format(Locale.getDefault(), "%,d", firmware.totalBytes()),
                firmware.segmentCount(), firmware.minAddress(), firmware.maxEndAddress()));
    }

    private String displayName(Uri uri) {
        String result = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getString(0);
            }
        } catch (Exception ignored) {
            // 某些文件管理器不支持查询，退回用 URI 末段
        }
        return result == null ? "firmware" : result;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new IllegalStateException("null stream");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 把读回的 Flash 写成 Intel HEX 文件（用 SAF 创建，用户选保存位置）。 */
    private void saveFlashImage(Uri uri) {
        FirmwareImage image = readBackImage;
        readBackImage = null;
        if (image == null) {
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IllegalStateException("null stream");
            }
            out.write(image.toIntelHex().getBytes(StandardCharsets.US_ASCII));
            int kb = (int) (image.totalBytes() / 1024);
            toast(getString(R.string.isp_read_flash_saved, displayName(uri), kb));
            appendLog(IspLog.LEVEL_SUCCESS, "Flash 已保存: " + displayName(uri));
        } catch (Exception e) {
            appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_read_flash_failed)
                    + ": " + e.getMessage());
        }
    }

    private FirmwareImage readBackImage;

    // ---------------- 任务调度 ----------------

    /** 所有串口动作都走这里：一次只允许一个任务，期间禁用「开始下载」并启用「中止」。 */
    private void runTask(Runnable task) {
        if (!requireConnection()) {
            return;
        }
        if (busy.get()) {
            toast(getString(R.string.isp_busy));
            return;
        }
        saveSettings();
        applySettings();
        cancelled = false;
        taskHadError = false;
        busy.set(true);
        runOnUiThread(() -> {
            findViewById(R.id.btn_download).setEnabled(false);
            findViewById(R.id.btn_cancel).setEnabled(true);
            // 新任务开始时回到日志底部，保证能看到当前这一步在做什么
            svLog.fullScroll(View.FOCUS_DOWN);
        });
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                taskHadError = true;
                appendLog(IspLog.LEVEL_ERROR, "异常: " + e);
            } finally {
                busy.set(false);
                // 失败必须看得见：光靠日志区容易被忽略（之前逐块校验读失败时就卡在原地没提示）
                final boolean failed = taskHadError;
                runOnUiThread(() -> {
                    cancelled = false;
                    findViewById(R.id.btn_download).setEnabled(true);
                    findViewById(R.id.btn_cancel).setEnabled(false);
                    if (failed) {
                        pbProgress.setProgress(0);
                        tvProgress.setText(getString(R.string.isp_status_failed)
                                + " — " + getString(R.string.isp_task_failed_hint));
                        toast(getString(R.string.isp_task_failed_hint));
                    }
                    refreshChipLabel();
                });
            }
        });
    }

    private void startDownload() {
        if (!requireConnection() || !requireFirmware()) {
            return;
        }
        session.setFirmware(firmware);
        boolean verify = cbVerify.isChecked();
        boolean runAfter = cbRunAfter.isChecked();
        runTask(() -> {
            boolean ok = session.download(verify, runAfter);
            appendLog(ok ? IspLog.LEVEL_SUCCESS : IspLog.LEVEL_ERROR,
                    ok ? getString(R.string.isp_status_done)
                            : getString(R.string.isp_status_failed));
        });
    }

    private void confirmErase() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.isp_confirm_erase)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        runTask(() -> session.eraseOnly()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void taskRun() {
        session.exitAndRun();
    }

    private void taskIdentify() {
        boolean ok = session.ensureBootloader();
        if (ok) {
            appendLog(IspLog.LEVEL_SUCCESS, "芯片识别完成");
        }
    }

    /** 读回整片 Flash：先在后台读，成功后再弹保存框（保存框必须从主线程发起）。 */
    @SuppressWarnings("deprecation") // 同上：不引 AndroidX，保留 startActivityForResult
    private void readFlashAndSave() {
        runTask(() -> {
            FirmwareImage image = session.readWholeFlash();
            if (image == null) {
                appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_read_flash_failed));
                return;
            }
            readBackImage = image;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "flash_backup.hex");
                startActivityForResult(intent, REQUEST_SAVE_FLASH);
            });
        });
    }

    // ---------------- 选项字节 ----------------

    /**
     * 选项字节对话框：16 字节成对显示（值 + 反值）。
     * 界面用代码动态搭建（行数是固定的 8 对，动态拼接比写 16 个控件更省心，
     * 但这一页无法在 Layout Editor 里拖拽）。
     */
    private void showOptionBytesDialog() {
        if (!requireConnection()) {
            return;
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        EditText[] valueEditors = new EditText[8];
        EditText[] complementEditors = new EditText[8];
        TextView[] descriptions = new TextView[8];

        for (int i = 0; i < 8; i++) {
            TextView label = new TextView(this);
            label.setText(getString(R.string.isp_option_pair, i + 1));
            label.setTextSize(12);
            rows.addView(label);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            EditText value = hexEditor();
            EditText complement = hexEditor();
            valueEditors[i] = value;
            complementEditors[i] = complement;
            line.addView(value);
            line.addView(complement);
            rows.addView(line);

            TextView description = new TextView(this);
            description.setTextSize(11);
            description.setTextColor(0xFF888888);
            descriptions[i] = description;
            rows.addView(description);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.isp_option_bytes_title,
                        session.optionBytesAddress()))
                .setView(scroll)
                .setNeutralButton(R.string.isp_option_read, null)
                .setPositiveButton(R.string.isp_option_write, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // show() 之后才能拿到按钮，改写「读取」按钮以免点击后关闭对话框
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            runTask(() -> {
                byte[] data = session.readOptionBytes();
                if (data == null) {
                    return;
                }
                for (int i = 0; i < 8; i++) {
                    valueEditors[i].setText(String.format(Locale.US, "%02X", data[i * 2] & 0xFF));
                    complementEditors[i].setText(String.format(Locale.US, "%02X",
                            data[i * 2 + 1] & 0xFF));
                    descriptions[i].setText(describeOptionRow(i, data, session.optionBytesAddress()));
                }
            });
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            byte[] data = new byte[16];
            for (int i = 0; i < 8; i++) {
                int a = parseHexByte(valueEditors[i].getText().toString());
                int b = parseHexByte(complementEditors[i].getText().toString());
                if (a < 0 || b < 0) {
                    toast(getString(R.string.isp_option_incomplete));
                    return;
                }
                data[i * 2] = (byte) a;
                data[i * 2 + 1] = (byte) b;
            }
            new AlertDialog.Builder(this)
                    .setMessage(R.string.isp_confirm_write_option)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        dialog.dismiss();
                        runTask(() -> {
                            if (session.writeOptionBytes(data)) {
                                appendLog(IspLog.LEVEL_SUCCESS,
                                        getString(R.string.isp_option_written));
                            }
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    }

    /**
     * 按系列解释成对的选项字节：F4/H7 这类新系列在 0x1FFFC000/0x1FF00000，
     * 第 1 对是 OPTCR（含 LOCK、BOR），第 2 对含独立看门狗与睡眠复位位，
     * 第 3 对是写保护位图，第 4 对是读保护 RDP（0xAA 无保护 / 0xCC Level2）。
     */
    private String describeOptionRow(int index, byte[] data, long base) {
        int value = data[index * 2] & 0xFF;
        int complement = data[index * 2 + 1] & 0xFF;
        boolean newStyle = base == 0x1FFFC000L || base == 0x1FF00000L || base == 0x1FFF0000L;
        if (newStyle) {
            switch (index) {
                case 0: {
                    String[] bor = {"BOR Lv0", "BOR Lv1", "BOR Lv2", "BOR Lv3"};
                    return getString(R.string.isp_option_f4_row0, value & 1,
                            (value >> 1) & 1, bor[(value >> 2) & 3]);
                }
                case 1:
                    return getString(R.string.isp_option_f4_row1, (value >> 6) & 1,
                            (value >> 5) & 1, (value >> 4) & 1, (value >> 7) & 1);
                case 2:
                    return getString(R.string.isp_option_f4_row2, value);
                case 3: {
                    String level = value == 0xAA ? getString(R.string.isp_rdp_none)
                            : value == 0xCC ? getString(R.string.isp_rdp_level2)
                            : getString(R.string.isp_rdp_level1);
                    return getString(R.string.isp_option_f4_row3, value, level);
                }
                default:
                    return String.format(Locale.US, "0x%08X=0x%02X/0x%02X",
                            base + index * 2L, value, complement);
            }
        }
        if (base == 0x1FFFF800L && index == 0) {
            String level = value == 0xA5 ? getString(R.string.isp_rdp_none)
                    : value == 0xCC ? getString(R.string.isp_rdp_level2) : "";
            return "RDP 0x" + String.format(Locale.US, "%02X", value) + " " + level;
        }
        return String.format(Locale.US, "0x%08X=0x%02X/0x%02X", base + index * 2L,
                value, complement);
    }

    private EditText hexEditor() {
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        editor.setLayoutParams(params);
        return editor;
    }

    // ---------------- 调试面板 ----------------

    /**
     * 单步执行协议命令的面板：接线或波特率可疑时，用它一条条发命令看芯片回什么，
     * 比直接跑完整下载更容易定位「是没同步上」还是「擦除被保护挡住」。
     */
    private void showDebugDialog() {
        if (!requireConnection()) {
            return;
        }
        String[] protocolItems = getResources().getStringArray(R.array.isp_debug_items);
        String[] lineItems = {
                getString(R.string.isp_dtr_high), getString(R.string.isp_dtr_low),
                getString(R.string.isp_rts_high), getString(R.string.isp_rts_low),
        };
        String[] items = new String[protocolItems.length + lineItems.length];
        System.arraycopy(protocolItems, 0, items, 0, protocolItems.length);
        System.arraycopy(lineItems, 0, items, protocolItems.length, lineItems.length);

        new AlertDialog.Builder(this)
                .setTitle(R.string.isp_debug_title)
                .setItems(items, (dialog, which) -> {
                    if (which < protocolItems.length) {
                        runDebugCommand(which);
                    } else {
                        int line = which - protocolItems.length;
                        runTask(() -> {
                            boolean level = line == 0 || line == 2;
                            if (line < 2) {
                                session.debugSetDtr(level);
                            } else {
                                session.debugSetRts(level);
                            }
                        });
                    }
                })
                .show();
    }

    private void runDebugCommand(int which) {
        switch (which) {
            case 0:
                runTask(() -> session.debugSync());
                break;
            case 1:
                runTask(() -> session.debugGetCommand());
                break;
            case 2:
                runTask(() -> session.debugGetId());
                break;
            case 3:
                runTask(() -> session.debugGetVersion());
                break;
            case 4:
                runTask(() -> session.debugEraseStandard());
                break;
            case 5:
                runTask(() -> session.debugEraseExtended());
                break;
            case 6:
                runTask(() -> session.debugGo());
                break;
            case 7:
                runTask(() -> session.debugWriteUnprotect());
                break;
            case 8:
                new AlertDialog.Builder(this)
                        .setMessage(R.string.isp_confirm_rdp)
                        .setPositiveButton(android.R.string.ok, (d, w) ->
                                runTask(() -> session.debugReadoutUnprotect()))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                break;
            case 9:
                runTask(() -> session.debugReadFlashSize());
                break;
            case 10:
                showRawSendDialog();
                break;
            default:
                taskLineTest();
                break;
        }
    }

    /** 线路自检：不依赖芯片型号，只判断转串口有没有真的发出偶校验。 */
    private void taskLineTest() {
        runTask(() -> {
            int result = session.lineSelfTest();
            int message;
            int level;
            if (result == IspProtocol.LINETEST_OK) {
                message = R.string.isp_linetest_ok;
                level = IspLog.LEVEL_SUCCESS;
            } else if (result == IspProtocol.LINETEST_NO_PARITY) {
                message = R.string.isp_linetest_no_parity;
                level = IspLog.LEVEL_ERROR;
            } else {
                message = R.string.isp_linetest_no_reply;
                level = IspLog.LEVEL_ERROR;
            }
            appendLog(level, getString(message));
        });
    }

    private void showRawSendDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.isp_raw_hint);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(R.string.isp_raw_hint)
                .setView(input)
                .setPositiveButton(R.string.isp_send, (dialog, which) -> {
                    byte[] bytes = parseHexBytes(input.getText().toString());
                    if (bytes == null) {
                        toast(getString(R.string.isp_invalid_addr));
                        return;
                    }
                    runTask(() -> session.debugSendRaw(bytes, bytes.length));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "00 FF 7f" -> [0x00,0xFF,0x7F]；含非法字符返回 null。 */
    private static byte[] parseHexBytes(String text) {
        String[] tokens = text.trim().split("[\\s,]+");
        List<Byte> bytes = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int value = parseHexByte(token);
            if (value < 0) {
                return null;
            }
            bytes.add((byte) value);
        }
        if (bytes.isEmpty()) {
            return null;
        }
        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    private static int parseHexByte(String text) {
        String s = text.trim().replace("0x", "");
        if (s.isEmpty() || s.length() > 2) {
            return -1;
        }
        try {
            return Integer.parseInt(s, 16) & 0xFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 读取任意地址段并以「地址 + 16 字节」的十六进制形式写进日志。 */
    private void showReadMemoryDialog() {
        if (!requireConnection()) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        box.setPadding(padding, padding, padding, 0);
        EditText etAddr = new EditText(this);
        etAddr.setHint(R.string.isp_read_addr);
        etAddr.setText("08000000");
        etAddr.setTypeface(android.graphics.Typeface.MONOSPACE);
        etAddr.setInputType(InputType.TYPE_CLASS_TEXT);
        EditText etLen = new EditText(this);
        etLen.setHint(R.string.isp_read_len);
        etLen.setText("64");
        etLen.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(etAddr);
        box.addView(etLen);

        new AlertDialog.Builder(this)
                .setTitle(R.string.isp_menu_read_memory)
                .setView(box)
                .setPositiveButton(R.string.isp_read, (dialog, which) -> {
                    long address = parseAddress(etAddr.getText().toString());
                    int length = parseInt(etLen.getText().toString(), -1);
                    if (address < 0 || length <= 0 || length > IspProtocol.MAX_BLOCK) {
                        toast(getString(R.string.isp_invalid_addr));
                        return;
                    }
                    runTask(() -> {
                        byte[] data = session.readMemory(address, length);
                        if (data == null) {
                            appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_read_failed));
                            return;
                        }
                        appendLog(IspLog.LEVEL_CMD, hexDump(address, data));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String hexDump(long startAddress, byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int offset = 0; offset < data.length; offset += 16) {
            sb.append(String.format(Locale.US, "%08X  ", startAddress + offset));
            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    sb.append(String.format(Locale.US, "%02X ", data[offset + i] & 0xFF));
                } else {
                    sb.append("   ");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------- 界面辅助 ----------------

    private ArrayAdapter<String> simpleAdapter(List<String> items) {
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> simpleAdapter(String[] items) {
        return simpleAdapter(Arrays.asList(items));
    }

    private void refreshChipLabel() {
        if (session == null || !session.chip.isKnown()) {
            tvChipSummary.setText(R.string.isp_chip_unknown);
            tvChip.setText(R.string.isp_chip_unknown);
            return;
        }
        IspSession.ChipInfo info = session.chip;
        String protection;
        String protectionShort;
        if (info.rdpLevel == 2) {
            protection = getString(R.string.isp_rdp_level2);
            protectionShort = "L2";
        } else if (info.rdpLevel == 1) {
            protection = getString(R.string.isp_rdp_level1);
            protectionShort = "L1";
        } else {
            protection = getString(R.string.isp_rdp_none);
            protectionShort = getString(R.string.isp_rdp_none);
        }
        StringBuilder commands = new StringBuilder();
        for (int command : session.supportedCommands()) {
            if (commands.length() > 0) {
                commands.append(' ');
            }
            commands.append(String.format(Locale.US, "%02X", command));
        }
        // 折叠状态只显示「型号 · 容量 · 保护」，展开后才给 PID、SRAM、命令表等细节
        tvChipSummary.setText(getString(R.string.isp_chip_summary_format,
                info.name, info.flashSizeKb, protectionShort));
        tvChip.setText(getString(R.string.isp_chip_info, info.name, info.pid,
                info.bootloaderVersion, info.flashSizeKb, info.sramKb, protection,
                info.rdpRaw, commands.toString()));
    }

    /** 展开/收起芯片详情，状态记下来，下次进页面保持同样的偏好。 */
    private void setChipExpanded(boolean expanded) {
        chipExpanded = expanded;
        tvChip.setVisibility(expanded ? View.VISIBLE : View.GONE);
        tvChipToggle.setText(expanded
                ? R.string.isp_toggle_expanded : R.string.isp_toggle_collapsed);
        prefs.edit().putBoolean(KEY_CHIP_EXPANDED, expanded).apply();
    }

    /** 清空日志（同时丢掉还没刷到界面上的缓冲行）。 */
    private void clearLog() {
        uiHandler.removeCallbacks(logFlushTask);
        synchronized (pendingLines) {
            pendingLines.setLength(0);
            logFlushScheduled = false;
        }
        logBuffer.setLength(0);
        tvLog.setText("", TextView.BufferType.EDITABLE);
    }

    /** 全屏看日志：小窗口里翻长日志很吃力，这里给一个占满屏幕、可自由滚动的视图。 */
    private void showFullLog() {
        flushLogToView();
        if (logBuffer.length() == 0) {
            toast(getString(R.string.isp_log_empty));
            return;
        }
        TextView content = new TextView(this);
        content.setText(logBuffer.toString());
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.setTextSize(12);
        content.setTextIsSelectable(true);
        int padding = dp(12);
        content.setPadding(padding, padding, padding, padding);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(R.string.isp_section_log)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 把日志另存为文本文件，方便出问题时发给自己或贴到论坛排障。 */
    @SuppressWarnings("deprecation")
    private void exportLog() {
        flushLogToView();
        if (logBuffer.length() == 0) {
            toast(getString(R.string.isp_log_empty));
            return;
        }
        String name = "isp_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQUEST_SAVE_LOG);
    }

    /**
     * 写出日志文件。开头加一段环境摘要，这样单看日志文件就能知道是哪次、什么参数跑的。
     */
    @SuppressWarnings("deprecation")
    private void saveLog(Uri uri) {
        flushLogToView();
        StringBuilder out = new StringBuilder(logBuffer.length() + 512);
        out.append(getString(R.string.app_name)).append(" · ")
                .append(getString(R.string.title_isp)).append('\n')
                .append(getString(R.string.isp_log_header,
                        Build.MODEL, Build.VERSION.RELEASE,
                        currentBaud(), spBootMode.getSelectedItemPosition(),
                        currentDelay(), firmwareName)).append('\n');
        out.append(logBuffer);
        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
            if (stream == null) {
                throw new IllegalStateException("null stream");
            }
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            appendLog(IspLog.LEVEL_ERROR, getString(R.string.isp_file_read_failed,
                    String.valueOf(e.getMessage())));
            return;
        }
        String name = displayName(uri);
        toast(getString(R.string.isp_log_saved, name));
        appendLog(IspLog.LEVEL_SUCCESS, getString(R.string.isp_log_saved, name));
    }

    private String statusText(int status) {
        switch (status) {
            case IspLog.STATUS_ERASING:
                return getString(R.string.isp_status_erasing);
            case IspLog.STATUS_VERIFY_ERASE:
                return getString(R.string.isp_status_verify_erase);
            case IspLog.STATUS_WRITING:
                return getString(R.string.isp_status_writing);
            case IspLog.STATUS_VERIFYING:
                return getString(R.string.isp_status_verifying);
            case IspLog.STATUS_READING_FLASH:
                return getString(R.string.isp_status_reading);
            case IspLog.STATUS_DONE:
                return getString(R.string.isp_status_done);
            case IspLog.STATUS_FAILED:
                return getString(R.string.isp_status_failed);
            default:
                return getString(R.string.isp_progress_idle);
        }
    }

    /** 日志一律带毫秒时间戳（排障时要看清复位、擦除、写入的先后顺序），并带等级标签便于筛查。 */
    private void appendLog(int level, String message) {
        final String tag;
        switch (level) {
            case IspLog.LEVEL_CMD:
                tag = "CMD ";
                break;
            case IspLog.LEVEL_WARN:
                tag = "WARN";
                break;
            case IspLog.LEVEL_ERROR:
                tag = "ERR ";
                break;
            case IspLog.LEVEL_SUCCESS:
                tag = "OK  ";
                break;
            case IspLog.LEVEL_TRACE:
                tag = "TRCE";
                break;
            default:
                tag = "INFO";
                break;
        }
        // SimpleDateFormat 不是线程安全的，加锁复用同一个实例
        String when;
        synchronized (timestamp) {
            when = timestamp.format(new Date());
        }
        synchronized (pendingLines) {
            pendingLines.append('[').append(when).append("] [").append(tag)
                    .append("] ").append(message).append('\n');
            if (!logFlushScheduled) {
                logFlushScheduled = true;
                uiHandler.postDelayed(logFlushTask, LOG_FLUSH_INTERVAL_MS);
            }
        }
    }

    private final Runnable logFlushTask = this::flushLogToView;

    /**
     * 把攒下的日志一次性追加到界面。
     *
     * 用 TextView.append 增量追加（控件在 onCreate 里设成了 EDITABLE 缓冲），
     * 只有超出上限需要裁剪时才整体 setText，避免每行都重排整段文本。
     */
    private void flushLogToView() {
        String chunk;
        synchronized (pendingLines) {
            logFlushScheduled = false;
            if (pendingLines.length() == 0) {
                return;
            }
            chunk = pendingLines.toString();
            pendingLines.setLength(0);
        }
        runOnUiThread(() -> {
            // 追加之前先判断是否正贴着底部：用户往上翻看历史时不要每次都把他拽回底部
            boolean followTail = isLogAtBottom();
            if (logBuffer.length() + chunk.length() > LOG_LIMIT) {
                int drop = Math.max(logBuffer.length() / 2,
                        logBuffer.length() + chunk.length() - LOG_LIMIT);
                logBuffer.delete(0, drop);
                logBuffer.append(chunk);
                tvLog.setText(logBuffer, TextView.BufferType.EDITABLE);
                followTail = true; // 刚裁剪过，位置已经不可预测，回到尾部继续跟随
            } else {
                logBuffer.append(chunk);
                tvLog.append(chunk);
            }
            if (followTail) {
                svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /** 日志是否已经滚到底部（留 16px 容差，避免亚像素抖动导致判定翻转）。 */
    private boolean isLogAtBottom() {
        View content = svLog.getChildAt(0);
        if (content == null) {
            return true;
        }
        int tolerance = dp(16);
        return svLog.getScrollY() + svLog.getHeight() >= content.getHeight() - tolerance;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 接受 "08000000" / "0x08000000"，失败返回 -1。 */
    private static long parseAddress(String text) {
        String s = text.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) {
            s = s.substring(2);
        }
        if (s.isEmpty() || s.length() > 8) {
            return -1;
        }
        try {
            return Long.parseLong(s, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 只处理 USB 权限/拔出两个广播，注册为非导出（Android 13+ 要求）。 */
    // 不带 flag 的重载在 API 33 才存在，无法与带 flag 的版本合并成一个调用，故 lint 的分级判断在此失效。
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    /** 下拉框选中监听的简化版：只关心选中的位置，不关心 View。 */
    private abstract static class SpinnerChoice
            implements android.widget.AdapterView.OnItemSelectedListener {
        public abstract void onItemSelected(int position);

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long id) {
            onItemSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
