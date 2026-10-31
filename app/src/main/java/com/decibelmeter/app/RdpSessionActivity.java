package com.decibelmeter.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置远程桌面的画面页：原生会话线程出帧，这里按 30 fps 轮询取帧并画到 Surface 上。
 *
 * 刻意不做「回调进 JVM」：Java 侧主动拉帧，线程模型就只有一条原生会话线程 + 一个 UI Handler，
 * 不需要处理 JNI 全局引用与 AttachCurrentThread。代价是最多一帧的延迟，人手操作察觉不到。
 *
 * 两种指针模式（手机控桌面绕不开的分歧，两种都得给）：
 * <ul>
 *   <li><b>触控模式</b>：和 Win11 触摸屏同一套——轻点=左键、单指滑动=滚动、按住=右键、
 *       按住后拖动=拖拽；直观，但小图标会点不准；</li>
 *   <li><b>鼠标模式</b>：画面当触摸板，手指相对移动推动远端光标，轻点=左键、双指轻点=右键、
 *       双指上下滑=滚轮，另配左/右/中键与滚轮按钮。多一步，但能点中 16×16 的目标。</li>
 * </ul>
 *
 * 所有坐标换算都走同一个 {@link Layout}：画面怎么画和手指点哪儿必须用同一套缩放参数，
 * 否则越靠边差得越多。鼠标模式的光标位置按**远端像素**记，换分辨率或旋转都不会让光标跳走。
 *
 * 清单里声明了 configChanges，所以旋转不会重建 Activity：会话线程与原生状态都在进程里，
 * Activity 一重建就会看到「画面还在、会话已经对不上」的错乱。
 */
public class RdpSessionActivity extends BaseActivity {

    public static final String EXTRA_HOST = "rdp_host";
    public static final String EXTRA_PORT = "rdp_port";
    public static final String EXTRA_NAME = "rdp_name";

    private static final int BUTTON_NONE = 0;
    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_RIGHT = 2;
    private static final int BUTTON_MIDDLE = 3;
    /** Windows 一次滚轮格定的 delta 值，不是灵敏度参数。 */
    private static final int WHEEL_NOTCH = 120;
    /** 触屏甩动：每 16 ms 补一格的距离、衰减系数，以及起停阈值（远端像素/毫秒）。 */
    private static final long FLING_INTERVAL_MS = 16;
    private static final float FLING_DECAY = 0.90f;
    private static final float FLING_START = 0.35f;
    private static final float FLING_MIN = 0.05f;
    /** 轮询间隔：30 fps 对手动操作足够，再快也只是白耗电与白拷像素。 */
    private static final long FRAME_INTERVAL_MS = 33;
    /** 轻点的容差：手指移动超过它就不算"点了一下"（比系统 touchSlop 宽一半）。 */
    private int clickSlop;
    /** 拖拽的启动阈值：轻点之后再按住时，移动超过它才真的按下左键（半个 touchSlop，约 8 px）。 */
    private int dragSlop;
    /** 双指滑动攒够多少像素算一格滚轮（60 dp 左右的手感，比一屏一格细）。 */
    private int scrollNotchPx;
    /** 双指间距变化超过这个比例才算"在缩放"，否则看手指整体是否在平移。 */
    private static final float PINCH_SPAN_RATIO = 0.12f;
    /** 溢出菜单里的「输入统计」。 */
    private static final int MENU_STATS = Menu.FIRST + 20;
    /** 双指缩放的上限：再大就只剩几个像素，没有意义。 */
    private static final float MAX_ZOOM = 4f;
    /** 鼠标模式下按住多久算长按（松手发右键）。 */
    private static final long LONG_PRESS_MS = 600;
    /** 轻点之后那一按按住多久就直接进入拖拽：不必先拖出去一段距离。 */
    private static final long ARM_HOLD_MS = 180;
    /** 手势提示显示多久后淡出。 */
    private static final long HINT_MS = 4000;
    /** 状态行最短重建间隔：内容没变时不必每帧拼字符串。 */
    private static final long STATUS_REFRESH_MS = 500;
    /** 轻点之后多久内再按下去，算"接着拖"（笔记本触摸板的 tap-and-a-half）。 */
    private long dragArmMs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 取帧的中转数组，按远端分辨率增长；不重新分配是为了避免每 33 ms 造一个 3 MB 对象。 */
    private int[] pixels = new int[0];

    private SurfaceView surface;
    private TextView tvStatus;
    private TextView tvPad;
    private EditText etInput;
    private Bitmap bitmap;
    private final Layout layout = new Layout();

    private int serial;
    private boolean surfaceReady;
    /** 设置改了缩放方式：下一帧不管有没有新画面都要重画。 */
    private boolean repaintRequested;
    /** 每秒采样一次原生层的帧数与字节数，算出实际帧率与流量。 */
    private long lastSampleAt;
    private long lastStatusAt;
    private int lastStatusSignature;
    private long lastSampleFrames;
    private long lastSampleBytes;
    private long lastSampleNet;
    private int framesPerSecond;
    private long bytesPerSecond;
    /** 应用这一秒真正从网上收到的字节（TrafficStats），-1 = 这台设备不支持统计。 */
    private long networkBytesPerSecond;
    /** 指针形状的序号与元数据：变了才向原生层重新取一次位图。 */
    private int pointerSerial;
    private final int[] pointerMeta = new int[5];
    private int[] pointerPixels = new int[0];
    private Bitmap cursorBitmap;
    private CursorView cursorView;
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    /** 预分配：光标每次移动都会 onDraw，不该在里面造对象。 */
    private final Matrix cursorMatrix = new Matrix();
    private String host;
    /** 这次会话的开始时刻 / 活动 ID / 连接时的累计接收字节，连接详情里用。 */
    private long sessionStartedAt;
    private String sessionId = "";
    private long rxAtConnect;
    private int port;
    private CredentialStore credentials;
    private RdpSettings settings;

    /** 双指缩放倍数（1 = 适应屏幕）与相对居中位置的平移量（像素）。 */
    private float zoom = 1f;
    private float panX;
    private float panY;
    /** 平移控件：开启后单指拖动是平移画面而不是点击远端（与微软客户端的同名开关一致）。 */
    private boolean panMode;
    /** 工具栏是否展开；收起时只剩一个 ☰，把屏幕让给桌面。 */
    private boolean barExpanded;
    private boolean fullscreen;
    private View bar;
    /** 本次连接向服务端要的尺寸：自适应模式靠它判断旋转后要不要重连。 */
    private int connectedWidth;
    private int connectedHeight;
    private Button modeButton;
    private Button panButton;
    private Button orientButton;
    private Button fullscreenButton;
    private Button disconnectButton;
    /** 上一帧看到的会话状态：变了才刷新按钮文案、弹"连接已断开"的提示。 */
    private int lastSeenState = RdpNative.STATE_IDLE;
    /** 收起工具栏时要一起藏起来的按钮（☰ 除外）。 */
    private final List<View> barExtras = new ArrayList<>();
    /** 解析域名用的单线程：查一次 DNS 就够，不该占着主线程，也不该占一个线程池。 */
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    /** 真正拿去做 TCP 连的地址：字面量 IP 就是它本身，域名则是解析结果。 */
    private String connectHost;
    /** 鼠标模式下"按住拖动"是否已经按下左键。 */
    private boolean dragHeld;
    /** 已经按够了长按时长：松手是右键。 */
    private boolean longPressArmed;
    /** 上一次轻点的时刻，用来识别"轻点后按住拖动"。 */
    private long lastTapAt;
    /** 这一按是"轻点之后"的候选拖拽，手指动起来才真的按下左键。 */
    private boolean dragArmed;
    /** 双指手势：0=还没判定、1=缩放、2=滚动。判定一次就锁定，直到手指数降到 1。 */
    private int twoFingerMode;
    private static final int TWO_UNDECIDED = 0;
    private static final int TWO_PINCH = 1;
    private static final int TWO_SCROLL = 2;
    private boolean twoFingerActive;
    /** 双指抬起一根之后，剩下的那根不再当作新的单指手势（否则光标会跳）。 */
    private boolean swallowUntilUp;
    private float twoStartSpan;
    private float twoStartFocusX;
    private float twoStartFocusY;
    /** 双指缩放：按下时的初始间距、初始倍数，以及捏合焦点下的远端像素。 */
    private float pinchStartDistance;
    private float pinchStartZoom;
    private float pinchFocusX;
    private float pinchFocusY;
    private int pinchRemoteX;
    private int pinchRemoteY;

    private int mode;
    /** 鼠标模式下的远端光标位置（远端像素）。 */
    private int cursorX;
    private int cursorY;
    private float lastTouchX;
    private float lastTouchY;
    private float travel;
    private int pointerCount;
    private float pinchLastY;
    private float pinchAccum;
    private float pinchLastX;
    private float pinchAccumX;
    /** 这次双指手势里最多同时按下过几根手指：三指轻点 = 中键。 */
    private int twoFingerMaxPointers;

    /** 触屏模式下的单指手势：还没判定 / 滚动 / 长按之后的拖拽。判定一次就锁定。 */
    private static final int TOUCH_UNDECIDED = 0;
    private static final int TOUCH_SCROLL = 1;
    private static final int TOUCH_DRAG = 2;
    private int touchGesture = TOUCH_UNDECIDED;
    /** 按住多久算长按（Windows 触屏是 500 ms）。长按之后松手是右键、再动是拖拽。 */
    private static final long TOUCH_HOLD_MS = 500;
    /** 单指滑动多少像素开始滚动：与轻点容差同宽，手抖几下仍然算"点了一下"。 */
    private int scrollSlop;
    /** 触屏滚动攒够多少**远端**像素算一格滚轮（约三行文字，和双指滚动同一手感）。 */
    private static final float TOUCH_NOTCH_PX = 56f;
    /** 长按已经到位：松手是右键，继续移动才是拖拽。 */
    private boolean touchHoldArmed;
    private float touchDownX;
    private float touchDownY;
    private long lastMoveTime;
    /** 单指滚动的累积量与甩动速度（远端像素；正=滚轮向上）。 */
    private float touchAccum;
    private float flingVelocity;
    private float flingAccum;
    /** 长按提示方块：位置用视图坐标，画在 CursorView 上。 */
    private boolean holdCue;
    private float holdCueX;
    private float holdCueY;
    private float holdCueSize;
    private float holdCueRadius;
    private final Paint holdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF holdRect = new RectF();

    /** 按住的功能键（Ctrl/Alt/Shift/Win）的扫描码。 */
    private final List<Integer> latched = new ArrayList<>();
    private ToggleButton[] latchButtons;
    private static final int[] LATCH_CODES = {
            RdpKeys.CTRL_LEFT, RdpKeys.ALT_LEFT, RdpKeys.SHIFT_LEFT, RdpKeys.WIN_LEFT,
    };

    /**
     * 一次绘制用到的缩放参数，画面与触控共用。
     *
     * 横竖轴分开存：拉满模式两轴缩放不同，只记一个 scale 的话点哪儿和实际点到的地方
     * 会在一个轴上系统性偏移。
     */
    private final class Layout {
        int frameWidth;
        int frameHeight;
        int viewWidth;
        int viewHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        float left;
        float top;

        boolean ready() {
            return frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0;
        }

        void update(int scaleMode) {
            if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return;
            }
            float baseX;
            float baseY;
            if (scaleMode == RdpSettings.SCALE_STRETCH) {
                baseX = (float) viewWidth / frameWidth;
                baseY = (float) viewHeight / frameHeight;
            } else {
                float both = Math.min((float) viewWidth / frameWidth,
                        (float) viewHeight / frameHeight);
                baseX = both;
                baseY = both;
            }
            scaleX = baseX * zoom;
            scaleY = baseY * zoom;
            // 缩小到比视图还小时居中（平移量无意义）；放大后以"相对居中"的平移量为准
            float drawWidth = frameWidth * scaleX;
            float drawHeight = frameHeight * scaleY;
            left = (viewWidth - drawWidth) / 2f + (drawWidth > viewWidth ? panX : 0f);
            top = (viewHeight - drawHeight) / 2f + (drawHeight > viewHeight ? panY : 0f);
        }

        Matrix matrix() {
            Matrix matrix = new Matrix();
            matrix.postScale(scaleX, scaleY);
            matrix.postTranslate(left, top);
            return matrix;
        }

        int[] toRemote(float x, float y) {
            return new int[]{
                    clamp(Math.round((x - left) / scaleX), 0, frameWidth - 1),
                    clamp(Math.round((y - top) / scaleY), 0, frameHeight - 1),
            };
        }
    }

    /**
     * 远端光标。单独一层 View 而不是画进帧缓冲里：鼠标模式下光标每移动一次就要重画，
     * 而整帧重绘得把 3 MB 位图重新刷上 Surface，拖动时会明显卡。这一层只失效自己，
     * SurfaceView 是独立图层，底下那一帧完全不用动。
     */
    private final class CursorView extends View {
        CursorView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (holdCue) {
                holdRect.set(holdCueX - holdCueSize / 2f, holdCueY - holdCueSize / 2f,
                        holdCueX + holdCueSize / 2f, holdCueY + holdCueSize / 2f);
                canvas.drawRoundRect(holdRect, holdCueRadius, holdCueRadius, holdPaint);
            }
            if (layout.frameWidth <= 0 || pointerMeta[4] == 1) {
                return;
            }
            // 触屏模式不画鼠标指针：手指点哪儿就在哪儿生效，屏幕上不该多一个箭头跟着跑
            if (mode != RdpSettings.MODE_MOUSE) {
                return;
            }
            if (pointerMeta[4] == 0 && cursorBitmap != null) {
                cursorMatrix.reset();
                cursorMatrix.postScale(layout.scaleX, layout.scaleY);
                cursorMatrix.postTranslate(
                        layout.left + (cursorX - pointerMeta[2]) * layout.scaleX,
                        layout.top + (cursorY - pointerMeta[3]) * layout.scaleY);
                canvas.drawBitmap(cursorBitmap, cursorMatrix, null);
                return;
            }
            // 服务端说"用你的默认箭头"：自己画一个，热点在箭尖（0,0）
            canvas.save();
            canvas.translate(layout.left + cursorX * layout.scaleX,
                    layout.top + cursorY * layout.scaleY);
            canvas.drawPath(arrowPath, cursorPaint);
            canvas.restore();
        }
    }

    /**
     * 鼠标模式的长按：按够 600 ms 只是**标记**下来，还不发任何键。
     *
     * 松手时才算右键、这期间手指一动就取消标记——这样"点完紧接着挪光标"永远不会变成拖拽。
     * 拖拽走的是另一条路（轻点之后的第二次按下），见 {@link #dragCommit}。
     */
    private final Runnable longPressTrigger = new Runnable() {
        @Override
        public void run() {
            if (pointerCount != 1 || dragHeld || longPressArmed || layout.frameWidth <= 0) {
                return;
            }
            longPressArmed = true;
            // 震一下告诉用户"现在松手是右键、拖动是拖拽"
            surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    /**
     * 轻点之后按住了：不等手指移动就先按下左键。
     *
     * 之前要移动 3 倍 touchSlop（这块屏约 51 px）才按下左键，用户得先"拖出去一段"拖动才生效；
     * 现在按住 {@link #ARM_HOLD_MS} 就已经进入拖拽状态，手指一动就是真的在拖。
     */
    private final Runnable dragCommit = new Runnable() {
        @Override
        public void run() {
            if (pointerCount == 1) {
                commitDrag();
            }
        }
    };

    /** 进入拖拽：按下左键并震一下；两条入口（按住、移动）都收敛到这里，避免重复发按键。 */
    private void commitDrag() {
        handler.removeCallbacks(dragCommit);
        if (!dragArmed || dragHeld) {
            return;
        }
        dragArmed = false;
        dragHeld = true;
        RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, true);
        surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            drawFrame();
            handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rdp_session);

        // 老连接簿里可能存着带方括号的 IPv6，统一成裸地址再往下走
        host = RdpConnection.normalizeHost(getIntent().getStringExtra(EXTRA_HOST));
        port = getIntent().getIntExtra(EXTRA_PORT, LanHost.PORT_RDP);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        setTitle(name == null || name.isEmpty() ? host : name);
        if (host == null || host.isEmpty() || !RdpNative.isAvailable()) {
            finish();
            return;
        }
        // 这一页要的是「整块屏幕都是远端桌面」：连标题栏一起藏掉，返回键退出会话
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        // 轻点的容差取系统 touchSlop 的 1.5 倍；拖拽一旦在"轻点后按住"的状态下
        // 只需要半个 touchSlop 就启动——手指一动就开始拖，不会先空走一段。
        // 误触靠"必须在轻点后 300 ms 内按下"这个前提挡着，而不是靠大位移阈值。
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        clickSlop = touchSlop * 3 / 2;
        dragSlop = Math.max(4, touchSlop / 2);
        scrollSlop = clickSlop;
        scrollNotchPx = dp(30f);
        holdCueSize = dp(28f);
        holdCueRadius = dp(7f);
        holdPaint.setStyle(Paint.Style.STROKE);
        holdPaint.setStrokeWidth(dp(2f));
        holdPaint.setColor(0xD9F2F2F2);
        // 与系统的"双击"用同一个时间窗：超过就不该再算"接着上一次点击"
        dragArmMs = ViewConfiguration.getDoubleTapTimeout();
        settings = new RdpSettings(this);
        mode = settings.pointerMode();

        bar = findViewById(R.id.ll_rdp_bar);
        tvStatus = (TextView) findViewById(R.id.tv_rdp_status);
        tvPad = (TextView) findViewById(R.id.tv_rdp_pad);
        etInput = (EditText) findViewById(R.id.et_rdp_input);
        surface = (SurfaceView) findViewById(R.id.sv_rdp);
        surface.getHolder().setFormat(PixelFormat.OPAQUE);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = true;
                layout.viewWidth = width;
                layout.viewHeight = height;
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
            }
        });
        final GestureDetector gestures = new GestureDetector(this, new GestureListener());
        surface.setOnTouchListener((v, event) -> {
            gestures.onTouchEvent(event);
            handleTouch(event);
            return true;
        });
        // 双击放大（平移模式）要靠 GestureDetector，它需要一个可点击的宿主
        surface.setClickable(true);

        cursorView = new CursorView(this);
        // 插在 SurfaceView 之上、隐藏输入框与提示之下；它不消费触摸，事件照样落到 Surface
        FrameLayout screen = (FrameLayout) findViewById(R.id.fl_rdp_screen);
        screen.addView(cursorView, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildArrow();

        Button barToggle = (Button) findViewById(R.id.btn_rdp_bar_toggle);
        barToggle.setOnClickListener(v -> setBarExpanded(!barExpanded));
        modeButton = (Button) findViewById(R.id.tb_rdp_mode);
        modeButton.setOnClickListener(v -> setMode(mode == RdpSettings.MODE_MOUSE
                ? RdpSettings.MODE_TOUCH : RdpSettings.MODE_MOUSE));
        panButton = (Button) findViewById(R.id.btn_rdp_pan);
        panButton.setOnClickListener(v -> setPanMode(!panMode));
        orientButton = (Button) findViewById(R.id.btn_rdp_orient);
        orientButton.setOnClickListener(v -> toggleOrientation());
        fullscreenButton = (Button) findViewById(R.id.btn_rdp_fullscreen);
        fullscreenButton.setOnClickListener(v -> setFullscreen(!fullscreen));
        Button keyboardButton = (Button) findViewById(R.id.btn_rdp_keyboard);
        keyboardButton.setOnClickListener(v -> showKeyboard());
        Button functionsButton = (Button) findViewById(R.id.btn_rdp_functions);
        functionsButton.setOnClickListener(v -> showKeyPanel());
        Button displayButton = (Button) findViewById(R.id.btn_rdp_display);
        displayButton.setOnClickListener(v -> showDisplayDialog());
        disconnectButton = (Button) findViewById(R.id.btn_rdp_disconnect);
        disconnectButton.setOnClickListener(v -> onDisconnectClicked());

        // 收起时只留 ☰：手机上纵向空间最值钱，一条常驻工具栏能吃掉十分之一的桌面
        barExtras.add(modeButton);
        barExtras.add(keyboardButton);
        barExtras.add(panButton);
        barExtras.add(orientButton);
        barExtras.add(fullscreenButton);
        barExtras.add(functionsButton);
        barExtras.add(displayButton);
        barExtras.add(disconnectButton);
        // 会话页藏了标题栏，溢出菜单点不到；连接详情挂在状态行上，点一下就看
        tvStatus.setOnClickListener(v -> showConnectionDetails());
        tintBarButtons();
        setBarExpanded(false);
        applyMode();
        updateBarLabels();

        etInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 输入法正在组合（拼音还没选字）时什么都不发：那串东西是候选码，不是用户要
                // 输入的字。等它提交，afterTextChanged 会再响一次，那时 text 里已经没有
                // 组合区，才把真正的字符送进远端。不清空是因为清空会打断输入法的组合。
                if (isComposing(s)) {
                    return;
                }
                // 软键盘给的是最终文本。没按住修饰键时走 Unicode 事件（不必管远端键盘布局）；
                // 按住 Ctrl/Alt 时必须换成扫描码，否则远端不会把它当成组合键
                for (int i = 0; i < s.length(); i++) {
                    char value = s.charAt(i);
                    if (value != '\n' && value != '\r') {
                        typeChar(value);
                    }
                }
                s.clear();
            }
        });

        credentials = new CredentialStore(this);
        startSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(pump);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 只停轮询，不停会话：切出去回来看的还是同一个连接。
        // 但按住的键必须放开——否则远端会一直以为 Ctrl 还按着
        handler.removeCallbacks(pump);
        cancelTouchGesture();
        releaseLatched();
        RdpNative.releaseAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pump);
        handler.removeCallbacks(longPressTrigger);
        handler.removeCallbacks(dragCommit);
        cancelTouchGesture();
        releaseLatched();
        RdpNative.stop();
        resolver.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_STATS, 0, R.string.rdp_btn_stats);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_STATS) {
            showConnectionDetails();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 连接详情。
     *
     * 用对话框而不是 toast：MIUI 会把连续的第二条 toast 直接吞掉，而"刚才那个提示写了什么"
     * 恰恰是查问题时最需要的。这里把目标、状态、尺寸、指针模式、输入统计和引擎版本一次列全。
     */
    /**
     * 连接详情：参照 Windows 远程桌面客户端的「连接质量」对话框——
     * 顶部一句质量评价，下面分客户端 / 网络 / 远程计算机三节。区别只在传输：
     * IronRDP 目前只有 TCP，没有 RDP 的 UDP 传输，这一行如实写出来。
     *
     * 正文用可选中的 TextView：用户可以像 mstsc 一样把整段复制出来发人。
     */
    private void showConnectionDetails() {
        StringBuilder text = new StringBuilder();
        long rtt = RdpNative.connectMillis();
        text.append(getString(R.string.rdp_details_quality, qualityLabel(rtt))).append("\n");
        text.append(getString(R.string.rdp_details_timestamp, formatTimestamp(sessionStartedAt)))
                .append('\n');
        text.append(getString(R.string.rdp_details_activity, sessionId)).append("\n\n");

        text.append(getString(R.string.rdp_details_client)).append('\n');
        text.append(getString(R.string.rdp_details_app, appVersionName(), appVersionCode()))
                .append('\n');
        text.append(getString(R.string.rdp_details_local,
                "Android " + Build.VERSION.RELEASE, Build.MODEL)).append('\n');
        text.append(getString(R.string.rdp_details_kernel, RdpNative.version())).append("\n\n");

        text.append(getString(R.string.rdp_details_network)).append('\n');
        text.append(getString(R.string.rdp_details_transport)).append('\n');
        if (rtt > 0) {
            text.append(getString(R.string.rdp_details_rtt, rtt)).append('\n');
        }
        if (networkBytesPerSecond >= 0) {
            long total = TrafficStats.getUidRxBytes(Process.myUid()) - rxAtConnect;
            text.append(getString(R.string.rdp_details_rx,
                    formatBytes(networkBytesPerSecond),
                    formatBytes(Math.max(0L, total)))).append('\n');
        }
        text.append(getString(R.string.rdp_detail_rate, getString(R.string.rdp_rate_format,
                framesPerSecond, formatBytes(bytesPerSecond)))).append('\n');
        text.append(getString(R.string.rdp_detail_audio, getString(RdpNative.audioEnabled()
                        ? R.string.rdp_audio_on : R.string.rdp_audio_off),
                RdpNative.audioPackets())).append("\n\n");

        text.append(getString(R.string.rdp_details_remote)).append('\n');
        text.append(getString(R.string.rdp_details_session_type)).append('\n');
        text.append(getString(R.string.rdp_details_gateway)).append('\n');
        String target = host;
        if (connectHost != null && !connectHost.equals(host)) {
            target = host + " → " + connectHost;
        }
        // IPv6 在 host:port 里要带方括号，和外面显示规则保持一致
        target = RdpConnection.isIpv6(target) ? "[" + target + "]" : target;
        target = target + ":" + port;
        text.append(getString(R.string.rdp_details_remote_computer, target)).append('\n');
        text.append(getString(R.string.rdp_detail_state,
                getString(stateLabel(RdpNative.state())))).append('\n');
        if (layout.frameWidth > 0) {
            text.append(getString(R.string.rdp_detail_size,
                    layout.frameWidth + "×" + layout.frameHeight)).append('\n');
        }
        text.append(getString(R.string.rdp_detail_quality, getString(
                settings.qualityPreset() == RdpSettings.PRESET_SAVE
                        ? R.string.rdp_quality_save_short
                        : settings.qualityPreset() == RdpSettings.PRESET_SHARP
                                ? R.string.rdp_quality_sharp_short
                                : R.string.rdp_quality_balanced_short))).append('\n');
        text.append(getString(R.string.rdp_detail_mode, getString(mode == RdpSettings.MODE_MOUSE
                ? R.string.rdp_mode_mouse : RdpSettings.MODE_TOUCH))).append('\n');
        text.append(getString(R.string.rdp_detail_input, RdpNative.inputStats())).append("\n\n");
        text.append(getString(R.string.rdp_details_copy_hint));

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rdp_details, null);
        ((TextView) content.findViewById(R.id.tv_rdp_details)).setText(text);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rdp_details_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 质量评价只看 TCP 往返：帧率取决于远端画面有没有变化，拿来判网络好坏会误伤。 */
    private String qualityLabel(long rtt) {
        if (rtt <= 0) {
            return getString(R.string.rdp_quality_unknown);
        }
        if (rtt <= 30) {
            return getString(R.string.rdp_quality_best, rtt);
        }
        if (rtt <= 80) {
            return getString(R.string.rdp_quality_good, rtt);
        }
        if (rtt <= 150) {
            return getString(R.string.rdp_quality_fair, rtt);
        }
        return getString(R.string.rdp_quality_poor, rtt);
    }

    private static String formatTimestamp(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date(millis));
    }

    @SuppressWarnings("deprecation")
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "-";
        }
    }

    @SuppressWarnings("deprecation")
    private long appVersionCode() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 物理键盘（OTG / 蓝牙）直接翻译成扫描码。
     *
     * 不接这一句的话，插了键盘也只能靠那个隐藏的软键盘输入框打字，功能键全丢。
     * 认识就返回 true 表示已经送进远端，别再让本地界面处理一遍；BACK 例外，要留给退出页面。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int scancode = event.getKeyCode() == KeyEvent.KEYCODE_BACK || !RdpNative.isAvailable()
                ? 0 : RdpKeys.forKeyCode(event.getKeyCode());
        if (scancode == 0) {
            return super.dispatchKeyEvent(event);
        }
        RdpNative.scancode(scancode, event.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }

    // ---------------------------------------------------------------- 会话

    /**
     * NLA 是这台目标机的硬要求（探测结论写在 RdpProbe 的头注释里），没有账号密码就握不了手，
     * 所以缺凭据时先要一份，而不是带着空用户名去撞一个看不懂的失败码。
     */
    private void startSession() {
        if (RdpConnection.isLiteralIp(host)) {
            connectHost = host;
            askForAccount();
            return;
        }
        // 域名先在本进程解析：一来能把解析结果写进状态行（用户得知道到底连到了哪儿），
        // 二来解析失败时能给一句"域名解析失败"而不是原生层那句笼统的"无法解析"
        tvStatus.setText(getString(R.string.rdp_resolving, host));
        setBarExpanded(true);
        resolver.execute(() -> {
            String ip = null;
            try {
                ip = preferIpv4(InetAddress.getAllByName(host));
            } catch (UnknownHostException ignored) {
                // 落到下面的失败分支
            }
            final String resolved = ip;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (resolved == null) {
                    tvStatus.setText(getString(R.string.rdp_resolve_failed, host));
                    return;
                }
                connectHost = resolved;
                askForAccount();
            });
        });
    }

    /**
     * 优先挑 IPv4 地址。
     *
     * 同一个名字往往同时有 A 和 AAAA 记录，默认解析会给出 IPv6。家用局域网里 IPv4 才是
     * 那条稳的路（手机不一定有 IPv6 出口，对端也可能只有链路本地地址），而且凭据是按
     * "扫描页看到的那个 IPv4"存的——解析成 IPv6 就变成另一台主机，密码得重输一遍。
     */
    private static String preferIpv4(InetAddress[] addresses) {
        if (addresses.length == 0) {
            return null;
        }
        InetAddress picked = addresses[0];
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                picked = address;
                break;
            }
        }
        return picked.getHostAddress();
    }

    /**
     * 取账号并连接。
     *
     * 按域名连时要回退查一遍按 IP 存过的凭据：扫描页里存的是 IP，用户在连接簿里写的是域名，
     * 同一台机器不该因为写法不同就再问一次密码。
     */
    private void askForAccount() {
        CredentialStore.Credential saved = credentials.get(host);
        if (saved == null && !host.equals(connectHost)) {
            saved = credentials.get(connectHost);
        }
        if (saved == null || saved.user.isEmpty()) {
            CredentialDialog.show(this, credentials, host,
                    getString(R.string.rdp_session_need_account, host),
                    android.R.string.ok, this::connect);
            return;
        }
        connect(saved);
    }

    private void connect(CredentialStore.Credential credential) {
        int[] size = settings.effectiveSize(this);
        connectedWidth = size[0];
        connectedHeight = size[1];
        sessionStartedAt = System.currentTimeMillis();
        // 活动 ID：只求一眼能区分两次连接，不必是真正的 UUID
        sessionId = String.format(Locale.US, "%08x",
                (int) (sessionStartedAt ^ (sessionStartedAt >>> 32)));
        rxAtConnect = TrafficStats.getUidRxBytes(Process.myUid());
        int code = RdpNative.start(connectHost, port, credential.user, credential.domain,
                credential.password, Build.MODEL, size[0], size[1], settings.colorDepth(),
                settings.lossyCodecs(), getApplicationContext());
        if (code != 0) {
            tvStatus.setText(getString(R.string.rdp_session_start_failed, code));
        }
    }

    // ---------------------------------------------------------------- 指针

    private void setMode(int next) {
        if (mode == next) {
            return;
        }
        // 换模式前把当前按下状态结掉：从触控切到鼠标时可能正按着左键，
        // 留着不发的话远端会认为按键一直没抬
        RdpNative.releaseAll();
        mode = next;
        settings.savePointerMode(next);
        if (next == RdpSettings.MODE_MOUSE && layout.frameWidth > 0) {
            // 以画面中心为起点比"沿用上一次的光标"更好解释，用户知道往哪儿划
            cursorX = layout.frameWidth / 2;
            cursorY = layout.frameHeight / 2;
        }
        applyMode();
    }

    private void applyMode() {
        tvPad.setText(mode == RdpSettings.MODE_MOUSE
                ? R.string.rdp_pad_hint : R.string.rdp_pad_hint_touch);
        showPadHint();
        updateBarLabels();
    }

    /** 手势提示只亮几秒：常驻会一直压在桌面上，而用户看一遍就记住了。 */
    private void showPadHint() {
        tvPad.setVisibility(View.VISIBLE);
        tvPad.animate().cancel();
        tvPad.setAlpha(1f);
        handler.removeCallbacks(hidePadHint);
        handler.postDelayed(hidePadHint, HINT_MS);
    }

    private final Runnable hidePadHint = () -> tvPad.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction(() -> tvPad.setVisibility(View.GONE))
            .start();

    // ---------------------------------------------------------------- 工具栏与显示

    /** 收起时只剩 ☰，其余按钮与状态行一起藏起来。 */
    private void setBarExpanded(boolean expanded) {
        barExpanded = expanded;
        for (View view : barExtras) {
            view.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        // 收起时贴着右边缘、半透明，像一枚挂边的小把手；点开才回到顶部成全宽工具条
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.gravity = expanded
                ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                : (Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(lp);
        bar.setAlpha(expanded ? 1f : 0.55f);
        tvStatus.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button toggle = (Button) findViewById(R.id.btn_rdp_bar_toggle);
        toggle.setText(getString(expanded ? R.string.rdp_bar_collapse : R.string.rdp_bar_handle));
        toggle.setContentDescription(getString(expanded
                ? R.string.rdp_bar_collapse : R.string.rdp_bar_expand));
    }

    private void tintBarButtons() {
        // 控件条是半透明深色底，默认按钮文字是深的，不改就看不清
        tintButtons(findViewById(R.id.ll_rdp_bar));
    }

    private void tintButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button) {
                ((Button) child).setTextColor(0xFFEEEEEE);
            } else {
                tintButtons(child);
            }
        }
    }

    private void updateBarLabels() {
        if (modeButton == null) {
            return;
        }
        modeButton.setText(getString(mode == RdpSettings.MODE_MOUSE
                ? R.string.rdp_mode_mouse : R.string.rdp_mode_touch));
        panButton.setText(getString(panMode ? R.string.rdp_btn_pan_on : R.string.rdp_btn_pan));
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        orientButton.setText(getString(landscape
                ? R.string.rdp_btn_portrait : R.string.rdp_btn_landscape));
        fullscreenButton.setText(getString(fullscreen
                ? R.string.rdp_btn_exit_fullscreen : R.string.rdp_btn_fullscreen));
        if (disconnectButton != null) {
            int state = RdpNative.state();
            boolean live = state == RdpNative.STATE_RUNNING
                    || state == RdpNative.STATE_CONNECTING;
            disconnectButton.setText(getString(
                    live ? R.string.rdp_btn_disconnect : R.string.rdp_btn_reconnect));
        }
    }

    /** 断开 / 重连：会话还活着就断开并退出，已经断了就直接重建一条。 */
    private void onDisconnectClicked() {
        int state = RdpNative.state();
        if (state != RdpNative.STATE_RUNNING && state != RdpNative.STATE_CONNECTING) {
            RdpNative.stop();
            startSession();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rdp_disconnect_title)
                .setMessage(getString(R.string.rdp_disconnect_message, host))
                .setPositiveButton(R.string.rdp_btn_disconnect, (dialog, which) -> {
                    RdpNative.stop();
                    toast(getString(R.string.rdp_disconnected_toast, host));
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 平移控件：开着的时候单指拖动是挪画面，关着的时候单指是点击。 */
    private void setPanMode(boolean on) {
        panMode = on;
        updateBarLabels();
    }

    /**
     * 横屏 / 竖屏切换。
     *
     * 用 SENSOR_LANDSCAPE 而不是 LANDSCAPE：前者允许两个方向的横屏，用户把手机翻过来
     * 也能用；后者只认一个方向，插着线躺床上用会很难受。
     */
    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        updateBarLabels();
    }

    /** 全屏：连系统栏一起藏掉，把整块屏幕给桌面。 */
    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        fullscreen = on;
        View decor = getWindow().getDecorView();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        repaintRequested = true;
        updateBarLabels();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转不重建 Activity（清单里声明了 configChanges），但画面区域变了：重算并重画
        layout.viewWidth = surface.getWidth();
        layout.viewHeight = surface.getHeight();
        clampPan();
        repaintRequested = true;
        updateBarLabels();
        // 自适应分辨率：转了方向就该按新方向的实际像素重连。等一帧再量，
        // 个别机型在 onConfigurationChanged 里拿到的还是旋转前的 Metrics
        if (settings.adaptiveSize()) {
            handler.postDelayed(this::reconnectIfAdaptiveSizeChanged, 400);
        }
    }

    /** 自适应分辨率下尺寸变了（旋转）就重连；分辨率是握手时定的，改不了只能重来。 */
    private void reconnectIfAdaptiveSizeChanged() {
        if (!settings.adaptiveSize() || isFinishing() || RdpNative.state() != RdpNative.STATE_RUNNING) {
            return;
        }
        int[] size = RdpSettings.adaptiveSize(this);
        if (size[0] != connectedWidth || size[1] != connectedHeight) {
            RdpNative.stop();
            startSession();
        }
    }

    private void handleTouch(MotionEvent event) {
        layout.viewWidth = surface.getWidth();
        layout.viewHeight = surface.getHeight();
        if (!layout.ready()) {
            return;
        }
        layout.update(settings.scaleMode());
        if (swallowUntilUp) {
            int leftover = event.getActionMasked();
            if (leftover == MotionEvent.ACTION_UP || leftover == MotionEvent.ACTION_CANCEL) {
                swallowUntilUp = false;
            }
            return;
        }
        if (handleTwoFinger(event)) {
            return;
        }
        if (panMode && handlePan(event)) {
            return;
        }
        if (mode == RdpSettings.MODE_MOUSE) {
            handleTrackpad(event);
        } else {
            handleDirectTouch(event);
        }
    }

    /**
     * 双指手势：**缩放**还是**滚动**，由手指的动法决定，判定一次就锁定。
     *
     * 之前这里是"只要有双指移动就当缩放"，结果双指上下滑根本进不了滚动那段代码——
     * 两根手指平行滑动时间距几乎不变，缩放量为 0，屏幕上什么都不会发生。
     * 现在的判据是"哪个变化更大"：间距变化 vs 两指中点位移，谁大听谁的。
     */
    private boolean handleTwoFinger(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            twoFingerActive = true;
            twoFingerMode = TWO_UNDECIDED;
            twoStartSpan = Math.max(spacing(event), 1f);
            twoStartFocusX = averageX(event);
            twoStartFocusY = averageY(event);
            pinchLastY = twoStartFocusY;
            pinchAccum = 0f;
            pinchLastX = twoStartFocusX;
            pinchAccumX = 0f;
            twoFingerMaxPointers = event.getPointerCount();
            pinchStartZoom = zoom;
            pinchFocusX = twoStartFocusX;
            pinchFocusY = twoStartFocusY;
            int[] remote = layout.toRemote(pinchFocusX, pinchFocusY);
            pinchRemoteX = remote[0];
            pinchRemoteY = remote[1];
            // 第二根手指落下就不再是"长按/接着拖"了
            handler.removeCallbacks(longPressTrigger);
            handler.removeCallbacks(dragCommit);
            longPressArmed = false;
            dragArmed = false;
            cancelTouchGesture();
            if (dragHeld) {
                dragHeld = false;
                RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, false);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && twoFingerActive
                && event.getPointerCount() >= 2) {
            twoFingerMaxPointers = Math.max(twoFingerMaxPointers, event.getPointerCount());
            if (twoFingerMode == TWO_UNDECIDED) {
                float spanDelta = Math.abs(spacing(event) - twoStartSpan);
                float moveDelta = Math.abs(averageX(event) - twoStartFocusX)
                        + Math.abs(averageY(event) - twoStartFocusY);
                if (spanDelta < twoStartSpan * PINCH_SPAN_RATIO && moveDelta < clickSlop) {
                    return true;
                }
                twoFingerMode = spanDelta > moveDelta ? TWO_PINCH : TWO_SCROLL;
            }
            if (twoFingerMode == TWO_PINCH) {
                applyPinch(event);
            } else {
                applyScroll(event);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (!twoFingerActive) {
                return false;
            }
            twoFingerActive = false;
            if (twoFingerMode == TWO_UNDECIDED) {
                // 按下又抬起、既没缩放也没滚动 = 轻点：两指右键、三指中键（触摸板的常见映射）
                int button = twoFingerMaxPointers >= 3 ? BUTTON_MIDDLE : BUTTON_RIGHT;
                RdpNative.pointer(cursorX, cursorY, button, true);
                RdpNative.pointer(cursorX, cursorY, button, false);
                surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else if (twoFingerMode == TWO_PINCH) {
                // 放大之后光标可能已经不在视野里：不提醒的话用户会以为"鼠标丢了"
                revealCursorIfOffscreen();
            }
            twoFingerMode = TWO_UNDECIDED;
            // 还剩手指在屏幕上：整段都吞掉，不然剩下那根会被当成一次新的单指手势，
            // 光标会从旧位置猛地跳一下
            swallowUntilUp = action == MotionEvent.ACTION_POINTER_UP;
            return true;
        }
        return twoFingerActive;
    }

    /**
     * 缩放：焦点下的那个远端像素要待在原地——不然每次捏合画面都往中心滑。
     */
    private void applyPinch(MotionEvent event) {
        float next = clampF(pinchStartZoom * spacing(event) / twoStartSpan, 1f, MAX_ZOOM);
        if (Math.abs(next - zoom) <= 0.002f) {
            return;
        }
        zoom = next;
        layout.update(settings.scaleMode());
        panX += pinchFocusX - (layout.left + pinchRemoteX * layout.scaleX);
        panY += pinchFocusY - (layout.top + pinchRemoteY * layout.scaleY);
        clampPan();
        layout.update(settings.scaleMode());
        cursorView.invalidate();
        repaintRequested = true;
    }

    /**
     * 滚动：按"手指带着内容走"的方向（和手机一样）——两指上滑，内容跟着往上走，
     * 也就是滚轮向下。
     *
     * 攒够一格才发一次滚轮事件，比每像素发一条省往返；一次滑动跨过几格就一次发几格。
     */
    private void applyScroll(MotionEvent event) {
        // 触摸板的两指平移两个方向都能滚：纵向按 y 攒格，横向按 x 攒格
        float x = averageX(event);
        float y = averageY(event);
        pinchAccumX += x - pinchLastX;
        pinchLastX = x;
        int notchesX = (int) (pinchAccumX / scrollNotchPx);
        if (notchesX != 0) {
            pinchAccumX -= notchesX * (float) scrollNotchPx;
            // 手指向左滑（x 变小）→ 内容向左走 → 横向滚轮向右（负值）
            RdpNative.wheel(cursorX, cursorY, notchesX * WHEEL_NOTCH, false);
        }
        // 手指上滑时 y 变小 → 累加值为负 → 滚轮向下
        pinchAccum += y - pinchLastY;
        pinchLastY = y;
        int notches = (int) (pinchAccum / scrollNotchPx);
        if (notches != 0) {
            pinchAccum -= notches * (float) scrollNotchPx;
            RdpNative.wheel(cursorX, cursorY, notches * WHEEL_NOTCH, true);
        }
    }

    private float spacing(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 平移控件开启时的单指拖动：只挪画面，不动远端光标。 */
    private boolean handlePan(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            panX += event.getX() - lastTouchX;
            panY += event.getY() - lastTouchY;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            clampPan();
            layout.update(settings.scaleMode());
            cursorView.invalidate();
            repaintRequested = true;
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }

    /**
     * 双击放大：以双击位置为焦点在 1× 与 2× 之间切换。
     *
     * 双指捏合当然更好，但一只手拿着手机时捏合很别扭；双击是这类"看远处字"的操作里
     * 最省事的补充。只挂在平移模式下，避免影响触控模式的双击操作。
     */
    private void toggleZoom(float focusX, float focusY) {
        layout.update(settings.scaleMode());
        int[] remote = layout.toRemote(focusX, focusY);
        zoom = zoom > 1.01f ? 1f : 2f;
        if (zoom <= 1.01f) {
            panX = 0f;
            panY = 0f;
        } else {
            layout.update(settings.scaleMode());
            panX += focusX - (layout.left + remote[0] * layout.scaleX);
            panY += focusY - (layout.top + remote[1] * layout.scaleY);
        }
        clampPan();
        layout.update(settings.scaleMode());
        cursorView.invalidate();
        repaintRequested = true;
        // 放大之后光标可能已经不在视野里：用户明确要的是"视角聚焦到鼠标位置"
        revealCursorIfOffscreen();
    }

    /** 平移量夹在"画面仍然铺满视图"的范围内；放不大到超出视图时归零。 */
    private void clampPan() {
        float drawWidth = layout.frameWidth * layout.scaleX;
        float drawHeight = layout.frameHeight * layout.scaleY;
        float maxX = Math.max(0f, (drawWidth - layout.viewWidth) / 2f);
        float maxY = Math.max(0f, (drawHeight - layout.viewHeight) / 2f);
        panX = clampF(panX, -maxX, maxX);
        panY = clampF(panY, -maxY, maxY);
    }

    /**
     * 触控模式：和 Win11 的触摸屏一样，手指按下去**不立刻**按左键，先看这一下是什么动作。
     *
     * <ul>
     *   <li><b>轻点</b> → 左键（抬起时才按下再抬起，和触屏一样点哪儿点哪儿）；</li>
     *   <li><b>单指滑动</b> → 滚动（内容跟着手指走，松手带甩动惯性）；</li>
     *   <li><b>按住 500 ms</b> → 弹出方块提示，松手 = 右键；</li>
     *   <li><b>按住之后继续移动</b> → 拖拽（这时才按下左键，用来拖窗口、选文字）。</li>
     * </ul>
     *
     * 按下去就立刻按左键是以前的做法，代价是"想滑一下页面"会变成拖拽/框选。Windows 的触摸屏
     * 靠目标控件自己区分"能滚就滚、不能滚才当拖动"，RDP 报文里没有这层信息，所以这里统一按
     * 触摸屏的主流手感来：滑动=滚动，要拖就长按一下再拖。
     */
    private void handleDirectTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelTouchGesture();
            travel = 0f;
            touchAccum = 0f;
            flingVelocity = 0f;
            touchDownX = event.getX();
            touchDownY = event.getY();
            lastTouchX = touchDownX;
            lastTouchY = touchDownY;
            lastMoveTime = event.getEventTime();
            int[] remote = layout.toRemote(touchDownX, touchDownY);
            cursorX = remote[0];
            cursorY = remote[1];
            handler.postDelayed(touchHold, TOUCH_HOLD_MS);
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lastTouchX;
            float dy = event.getY() - lastTouchY;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            travel += Math.abs(dx) + Math.abs(dy);
            if (touchGesture == TOUCH_UNDECIDED) {
                if (touchHoldArmed) {
                    // 长按已经到位：这时候再动就是拖拽，静止不动松手才是右键
                    float moved = (float) Math.hypot(event.getX() - touchDownX,
                            event.getY() - touchDownY);
                    if (moved > dragSlop) {
                        startTouchDrag();
                        moveTouchDrag(event);
                    }
                } else if (travel > scrollSlop) {
                    touchGesture = TOUCH_SCROLL;
                    dismissHoldCue();
                    handler.removeCallbacks(touchHold);
                    applyTouchScroll(dy, event.getEventTime());
                }
            } else if (touchGesture == TOUCH_SCROLL) {
                applyTouchScroll(dy, event.getEventTime());
            } else {
                moveTouchDrag(event);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(touchHold);
            if (touchGesture == TOUCH_SCROLL) {
                startFling();
            } else if (touchGesture == TOUCH_DRAG) {
                RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, false);
            } else if (touchHoldArmed) {
                // 长按之后没怎么动就松手：右键（Windows 的"按住"菜单）
                click(BUTTON_RIGHT);
            } else if (travel <= clickSlop) {
                click(BUTTON_LEFT);
            }
            dismissHoldCue();
            touchGesture = TOUCH_UNDECIDED;
            touchHoldArmed = false;
            // 无障碍服务靠 performClick 感知「这里可以点」；触控被我们自己吃掉了，
            // 不补这一句的话 TalkBack 用户会觉得这块画面是死的
            surface.performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelTouchGesture();
        }
    }

    /** 长按到位：弹方块、震一下；这时松手是右键、动是拖拽。 */
    private final Runnable touchHold = new Runnable() {
        @Override
        public void run() {
            if (touchGesture != TOUCH_UNDECIDED || touchHoldArmed) {
                return;
            }
            touchHoldArmed = true;
            holdCue = true;
            holdCueX = touchDownX;
            holdCueY = touchDownY;
            cursorView.invalidate();
            surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    /** 甩动：松手后按剩余速度继续滚，每帧衰减，停下来就结束。 */
    private final Runnable flingStep = new Runnable() {
        @Override
        public void run() {
            if (Math.abs(flingVelocity) < FLING_MIN) {
                flingVelocity = 0f;
                flingAccum = 0f;
                return;
            }
            flingAccum += flingVelocity * FLING_INTERVAL_MS;
            int notches = (int) (flingAccum / TOUCH_NOTCH_PX);
            if (notches != 0) {
                flingAccum -= notches * TOUCH_NOTCH_PX;
                RdpNative.wheel(cursorX, cursorY, notches * WHEEL_NOTCH, true);
            }
            flingVelocity *= FLING_DECAY;
            handler.postDelayed(this, FLING_INTERVAL_MS);
        }
    };

    /** 单指滑动：把手指位移换成滚轮格数；手指上滑 = 内容上移 = 滚轮向下。 */
    private void applyTouchScroll(float dy, long eventTime) {
        float remoteDy = dy / Math.max(layout.scaleY, 0.01f);
        touchAccum += remoteDy;
        int notches = (int) (touchAccum / TOUCH_NOTCH_PX);
        if (notches != 0) {
            touchAccum -= notches * TOUCH_NOTCH_PX;
            RdpNative.wheel(cursorX, cursorY, notches * WHEEL_NOTCH, true);
        }
        long dt = Math.max(1L, eventTime - lastMoveTime);
        lastMoveTime = eventTime;
        float speed = remoteDy / dt;
        flingVelocity = Math.max(-4f, Math.min(4f, flingVelocity * 0.6f + speed * 0.4f));
    }

    private void startFling() {
        // 松手前已经停住的不算甩动：手指压着不动再抬起来，内容不该继续跑
        if (Math.abs(flingVelocity) < FLING_START
                || SystemClock.uptimeMillis() - lastMoveTime > 120) {
            flingVelocity = 0f;
            return;
        }
        handler.removeCallbacks(flingStep);
        handler.post(flingStep);
    }

    private void startTouchDrag() {
        touchGesture = TOUCH_DRAG;
        dismissHoldCue();
        // 左键按在按住的那个点上，再跟着手指走——和触摸屏"按住拖动"一致
        RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, true);
    }

    private void moveTouchDrag(MotionEvent event) {
        int[] remote = layout.toRemote(event.getX(), event.getY());
        cursorX = remote[0];
        cursorY = remote[1];
        // 拖动过程中只发"移动"：按键的按下状态在远端是持续的，重复发按下会变成连点
        RdpNative.pointer(remote[0], remote[1], BUTTON_NONE, false);
    }

    private void dismissHoldCue() {
        if (holdCue) {
            holdCue = false;
            cursorView.invalidate();
        }
    }

    /** 放弃当前触屏手势：计时器、甩动、方块提示和可能按下的左键都收干净。 */
    private void cancelTouchGesture() {
        handler.removeCallbacks(touchHold);
        handler.removeCallbacks(flingStep);
        if (touchGesture == TOUCH_DRAG) {
            RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, false);
        }
        touchGesture = TOUCH_UNDECIDED;
        touchHoldArmed = false;
        flingVelocity = 0f;
        flingAccum = 0f;
        dismissHoldCue();
    }

    /**
     * 鼠标模式：整块画面当触摸板，手势对齐笔记本触摸板——
     * <ul>
     *   <li><b>单指轻点</b> → 左键；</li>
     *   <li><b>双指轻点</b> → 右键（在 {@link #handleTwoFinger} 里判定）；</li>
     *   <li><b>轻点后紧接着按住拖动</b> → 拖拽（笔记本上的 tap-and-a-half，先点一下再按住拖）；</li>
     *   <li><b>按住不动 600 ms 再松手</b> → 右键（手机上"按住"比双指更顺手，留作等价入口）。</li>
     * </ul>
     *
     * 拖拽的入口只有"轻点之后 300 ms 内再按下"这一条，进门之后有两条路立刻生效：
     * 按住 {@link #ARM_HOLD_MS}（不用动），或者移动超过半个 touchSlop。这样既不会
     * "点完接着挪光标"变成拖拽，也不用为了开始拖先空走一段距离。
     */
    private void handleTrackpad(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointerCount = 1;
            travel = 0f;
            longPressArmed = false;
            dragHeld = false;
            handler.removeCallbacks(dragCommit);
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            // 加速曲线要按"每毫秒走了多远"算，先记下按下时刻
            lastMoveTime = event.getEventTime();
            if (SystemClock.uptimeMillis() - lastTapAt <= dragArmMs) {
                // 刚点过一下：这一按就是"接着拖"的候选，按住不动也算数
                dragArmed = true;
                handler.postDelayed(dragCommit, ARM_HOLD_MS);
            } else {
                dragArmed = false;
                handler.postDelayed(longPressTrigger, LONG_PRESS_MS);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lastTouchX;
            float dy = event.getY() - lastTouchY;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
            travel += Math.abs(dx) + Math.abs(dy);
            if (dragArmed && !dragHeld && travel > dragSlop) {
                // 轻点之后手指一动就开始拖，不等到拖出一段距离
                commitDrag();
            } else if (longPressArmed && !dragHeld && travel > clickSlop) {
                // 按住之后又挪了：那是在移动光标，不该再发右键
                longPressArmed = false;
            } else if (!longPressArmed && !dragHeld && travel > clickSlop) {
                // 手指在动，那是在推光标而不是"按住"：撤掉长按计时
                handler.removeCallbacks(longPressTrigger);
            }
            int[] next = moveCursor(dx, dy, event.getEventTime());
            cursorView.invalidate();
            // 拖动中光标照走，左键已在按下状态；否则只是移动
            RdpNative.pointer(next[0], next[1], BUTTON_NONE, false);
        } else if (action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(longPressTrigger);
            handler.removeCallbacks(dragCommit);
            pointerCount = 0;
            dragArmed = false;
            if (dragHeld) {
                // 拖拽结束：放开左键，这一次不算点击
                dragHeld = false;
                RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, false);
                return;
            }
            if (longPressArmed) {
                // 长按之后直接松手 = 右键
                longPressArmed = false;
                RdpNative.pointer(cursorX, cursorY, BUTTON_RIGHT, true);
                RdpNative.pointer(cursorX, cursorY, BUTTON_RIGHT, false);
                return;
            }
            if (travel > clickSlop) {
                // 只是推了光标，不算点击
                return;
            }
            click(BUTTON_LEFT);
            // 记下这次轻点：紧接着再按住就是要拖，也顺便让双击正常计时
            lastTapAt = SystemClock.uptimeMillis();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(longPressTrigger);
            handler.removeCallbacks(dragCommit);
            longPressArmed = false;
            dragArmed = false;
            if (dragHeld) {
                dragHeld = false;
                RdpNative.pointer(cursorX, cursorY, BUTTON_LEFT, false);
            }
            pointerCount = 0;
        }
    }

    /** 触控模式的长按=右键，由这里分派；双击交给两次真实的按下抬起，本地不合成。 */
    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            // 只在平移模式下双击放大：触控模式的双击要照常送给远端（双击桌面图标是高频操作）
            if (!panMode) {
                return false;
            }
            toggleZoom(event.getX(), event.getY());
            return true;
        }

        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }
    }

    private float averageY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }

    private float averageX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2f;
    }

    /** 按下再抬起，落在当前光标位置上。 */
    private void click(int button) {
        if (layout.frameWidth <= 0) {
            return;
        }
        RdpNative.pointer(cursorX, cursorY, button, true);
        RdpNative.pointer(cursorX, cursorY, button, false);
    }

    /**
     * 放大之后光标跑出视野就等于"丢了鼠标"——触控板上没有实体指针可以回头看。
     *
     * 这里做的是"软跟随"：光标靠近视野边缘一圈（约 15% 或 64 dp）时把画面往那个方向推，
     * 而不是一越界就整屏跳到中心——跳变会让人失去方向感，边缘留一圈的推法更像摄像机跟拍。
     * 只在放大状态生效；没放大时整张桌面都在视野里，平移没有意义。
     */
    private void keepCursorVisible() {
        if (zoom <= 1.01f || !layout.ready()) {
            return;
        }
        layout.update(settings.scaleMode());
        float cx = layout.left + cursorX * layout.scaleX;
        float cy = layout.top + cursorY * layout.scaleY;
        float marginX = Math.min(layout.viewWidth * 0.15f, dp(64f));
        float marginY = Math.min(layout.viewHeight * 0.15f, dp(64f));
        float shiftX = 0f;
        if (cx < marginX) {
            shiftX = marginX - cx;
        } else if (cx > layout.viewWidth - marginX) {
            shiftX = (layout.viewWidth - marginX) - cx;
        }
        float shiftY = 0f;
        if (cy < marginY) {
            shiftY = marginY - cy;
        } else if (cy > layout.viewHeight - marginY) {
            shiftY = (layout.viewHeight - marginY) - cy;
        }
        if (shiftX == 0f && shiftY == 0f) {
            return;
        }
        panX += shiftX;
        panY += shiftY;
        clampPan();
        layout.update(settings.scaleMode());
        // 画面本身要重画一次，不然只有光标层在动
        repaintRequested = true;
        cursorView.invalidate();
    }

    /**
     * 捏合结束时如果光标已经整只跑到视野外，把视角挪过去。
     *
     * 与 {@link #keepCursorVisible()} 的区别：这里只在**完全看不见**时才动，而且直接居中。
     * 捏合是"我要看这块区域"的动作，光标只是恰好不在这儿；如果它还在边上就往里推，
     * 会把用户刚对准的地方推走。
     */
    private void revealCursorIfOffscreen() {
        if (zoom <= 1.01f || !layout.ready()) {
            return;
        }
        layout.update(settings.scaleMode());
        float cx = layout.left + cursorX * layout.scaleX;
        float cy = layout.top + cursorY * layout.scaleY;
        if (cx >= 0f && cx <= layout.viewWidth && cy >= 0f && cy <= layout.viewHeight) {
            return;
        }
        panX += layout.viewWidth / 2f - cx;
        panY += layout.viewHeight / 2f - cy;
        clampPan();
        layout.update(settings.scaleMode());
        repaintRequested = true;
        cursorView.invalidate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 把手指位移换成远端光标位移。灵敏度是基准增益，另外加一层**指针加速**：
     * 慢速移动增益小于 1（好点小目标），快速移动增益变大（一划能横跨桌面）。
     * 这是笔记本触摸板最影响手感的一条——恒定增益下要么点不准、要么挪得慢。
     */
    private int[] moveCursor(float dx, float dy, long eventTime) {
        long dt = Math.max(1L, eventTime - lastMoveTime);
        lastMoveTime = eventTime;
        float speed = (float) Math.hypot(dx, dy) / dt;
        float factor = settings.sensitivity() / 100f * accelFactor(speed);
        cursorX = clamp(Math.round(cursorX + dx / Math.max(layout.scaleX, 0.01f) * factor),
                0, layout.frameWidth - 1);
        cursorY = clamp(Math.round(cursorY + dy / Math.max(layout.scaleY, 0.01f) * factor),
                0, layout.frameHeight - 1);
        keepCursorVisible();
        return new int[]{cursorX, cursorY};
    }

    /**
     * 指针加速曲线：0.75×（慢速）→ 1.5×（1 px/ms）→ 2.25×（4 px/ms 封顶）。
     * 用 sqrt 而不是线性，慢速段不会太迟钝，快速段也不会一下甩飞。
     */
    private static float accelFactor(float speed) {
        float s = Math.max(0f, Math.min(4f, speed));
        return 0.75f + 0.75f * (float) Math.sqrt(s);
    }

    // ---------------------------------------------------------------- 键盘

    private void showKeyboard() {
        etInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
    }

    /** 输入法是否正在组合：组合区的判定是公开 API，不必去猜 TextWatcher 的时序。 */
    private static boolean isComposing(Editable text) {
        int start = BaseInputConnection.getComposingSpanStart(text);
        int end = BaseInputConnection.getComposingSpanEnd(text);
        return start != -1 && end != -1 && start != end;
    }

    /** 输入一个字符：按住修饰键时走扫描码，否则走 Unicode。 */
    private void typeChar(char value) {
        if (!latched.isEmpty()) {
            int scancode = RdpKeys.forChar(value);
            if (scancode != 0) {
                // 大写字母要额外补一次 Shift；符号的上档形态（如 Ctrl+_）不在这里兜，
                // 那种组合改走功能键面板的修饰键
                boolean needShift = Character.isUpperCase(value);
                if (needShift) {
                    RdpNative.scancode(RdpKeys.SHIFT_LEFT, true);
                }
                RdpNative.scancode(scancode, true);
                RdpNative.scancode(scancode, false);
                if (needShift) {
                    RdpNative.scancode(RdpKeys.SHIFT_LEFT, false);
                }
                return;
            }
        }
        RdpNative.unicode(value, true);
        RdpNative.unicode(value, false);
    }

    /**
     * 功能键面板：一整屏键位 + 四个可以"按住"的修饰键。
     *
     * 修饰键做成开关而不是按住不放，是因为手机上"一只手按 Ctrl、另一只手点屏幕上的 C"
     * 这个动作做不出来；做成开关后一次点击就能完成组合键。
     */
    private void showKeyPanel() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rdp_keys, null);
        GridLayout grid = (GridLayout) content.findViewById(R.id.gl_rdp_keys);
        for (RdpKeys.Key key : RdpKeys.FUNCTION_KEYS) {
            final int scancode = key.scancode;
            Button button = new Button(this, null, android.R.attr.buttonStyleSmall);
            button.setAllCaps(false);
            button.setText(key.label);
            button.setOnClickListener(v -> tapScancode(scancode));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(button, params);
        }
        latchButtons = new ToggleButton[]{
                (ToggleButton) content.findViewById(R.id.tb_rdp_ctrl),
                (ToggleButton) content.findViewById(R.id.tb_rdp_alt),
                (ToggleButton) content.findViewById(R.id.tb_rdp_shift),
                (ToggleButton) content.findViewById(R.id.tb_rdp_win),
        };
        for (int i = 0; i < latchButtons.length; i++) {
            final int index = i;
            // 先按当前状态回填再挂监听：否则重建面板时 setChecked 会把锁存状态发一遍按下/抬起
            latchButtons[i].setChecked(latched.contains(LATCH_CODES[i]));
            latchButtons[i].setOnCheckedChangeListener((view, checked) -> latch(index, checked));
        }
        content.findViewById(R.id.btn_rdp_cad).setOnClickListener(v -> sendCtrlAltDel());
        // 刻意不在 dismiss 时释放锁存：Win+E、Ctrl+C 这类组合要"锁存修饰键 → 关面板 →
        // 用软键盘打字母"，关面板就释放的话这条路根本走不通。状态行会一直标着按住了谁，
        // onPause / 换模式 / 会话结束都会释放
        new AlertDialog.Builder(this)
                .setTitle(R.string.rdp_keys_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void tapScancode(int scancode) {
        RdpNative.scancode(scancode, true);
        RdpNative.scancode(scancode, false);
    }

    /**
     * Ctrl+Alt+Del 按扫描码序列发一遍。
     *
     * 真机验证过它能真的唤出安全注意序列界面（锁定 / 退出登录 / 更改密码 / 任务管理器）——
     * 也就是说 RDP 服务端认这串键位，不需要专门的 SAS 报文。
     *
     * 但安全桌面会把服务端的键盘状态整体重置：退出它以后远端不认为还按着任何一个键，
     * 而我们还以为 Ctrl 锁存着。所以发完随手把锁存清掉，否则之后每次点击都成了组合键。
     */
    private void sendCtrlAltDel() {
        int[] sequence = {RdpKeys.CTRL_LEFT, RdpKeys.ALT_LEFT, 0x53 | RdpKeys.EXT};
        for (int code : sequence) {
            RdpNative.scancode(code, true);
        }
        for (int i = sequence.length - 1; i >= 0; i--) {
            RdpNative.scancode(sequence[i], false);
        }
        releaseLatched();
    }

    /** 修饰键开关：按下后保持键位状态，直到再点一次或面板关闭。 */
    private void latch(int index, boolean on) {
        int code = LATCH_CODES[index];
        if (on) {
            if (!latched.contains(code)) {
                latched.add(code);
            }
            RdpNative.scancode(code, true);
        } else {
            latched.remove(Integer.valueOf(code));
            RdpNative.scancode(code, false);
        }
    }

    private void releaseLatched() {
        for (int code : latched) {
            RdpNative.scancode(code, false);
        }
        latched.clear();
        if (latchButtons != null) {
            for (ToggleButton button : latchButtons) {
                if (button != null) {
                    button.setChecked(false);
                }
            }
        }
    }

    /** 键名是国际通用的，不进双语文案（lint 的 SetTextI18n 在这里属刻意）。 */
    @SuppressLint("SetTextI18n")
    private String latchedNames() {
        StringBuilder names = new StringBuilder();
        for (int code : latched) {
            if (names.length() > 0) {
                names.append('+');
            }
            switch (code) {
                case RdpKeys.CTRL_LEFT:
                    names.append("Ctrl");
                    break;
                case RdpKeys.ALT_LEFT:
                    names.append("Alt");
                    break;
                case RdpKeys.SHIFT_LEFT:
                    names.append("Shift");
                    break;
                default:
                    names.append("Win");
                    break;
            }
        }
        return names.toString();
    }

    // ---------------------------------------------------------------- 显示

    /**
     * 分辨率与缩放设置。
     *
     * 改分辨率走 display-control 动态调整（Win8+ 的 RDP 支持，不用重连）；服务端不认时
     * IronRDP 自己会带新尺寸重连，这里不需要先判断支持与否。
     */
    private void showDisplayDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rdp_display, null);
        final RadioGroup rgSize = (RadioGroup) content.findViewById(R.id.rg_rdp_size);
        RadioGroup rgScale = (RadioGroup) content.findViewById(R.id.rg_rdp_scale);
        RadioGroup rgQuality = (RadioGroup) content.findViewById(R.id.rg_rdp_quality);
        final CheckBox cbAdaptive = (CheckBox) content.findViewById(R.id.cb_rdp_adaptive);
        cbAdaptive.setChecked(settings.adaptiveSize());
        setSizeGroupEnabled(rgSize, !settings.adaptiveSize());
        cbAdaptive.setOnCheckedChangeListener((button, checked) -> setSizeGroupEnabled(rgSize, !checked));
        SeekBar seek = (SeekBar) content.findViewById(R.id.sb_rdp_sensitivity);
        final TextView tvSensitivity = (TextView) content.findViewById(R.id.tv_rdp_sensitivity);

        int selected = settings.presetIndex(this);
        for (int i = 0; i < RdpSettings.PRESETS.length; i++) {
            RadioButton item = new RadioButton(this);
            item.setId(View.generateViewId());
            item.setText(RdpSettings.label(this, RdpSettings.PRESETS[i]));
            item.setChecked(i == selected);
            rgSize.addView(item, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        rgScale.check(settings.scaleMode() == RdpSettings.SCALE_STRETCH
                ? R.id.rb_rdp_stretch : R.id.rb_rdp_fit);
        rgQuality.check(settings.qualityPreset() == RdpSettings.PRESET_SAVE
                ? R.id.rb_rdp_quality_save
                : settings.qualityPreset() == RdpSettings.PRESET_SHARP
                        ? R.id.rb_rdp_quality_sharp : R.id.rb_rdp_quality_balanced);
        seek.setMax(400 - 50);
        seek.setProgress(settings.sensitivity() - 50);
        tvSensitivity.setText(getString(R.string.rdp_sensitivity_format, settings.sensitivity()));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvSensitivity.setText(getString(R.string.rdp_sensitivity_format, progress + 50));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.rdp_display_title)
                .setView(content)
                .setPositiveButton(R.string.rdp_apply, (dialog, which) -> {
                    settings.saveScaleMode(rgScale.getCheckedRadioButtonId() == R.id.rb_rdp_stretch
                            ? RdpSettings.SCALE_STRETCH : RdpSettings.SCALE_FIT);
                    repaintRequested = true;
                    settings.saveSensitivity(seek.getProgress() + 50);
                    int quality = rgQuality.getCheckedRadioButtonId() == R.id.rb_rdp_quality_save
                            ? RdpSettings.PRESET_SAVE
                            : rgQuality.getCheckedRadioButtonId() == R.id.rb_rdp_quality_sharp
                                    ? RdpSettings.PRESET_SHARP : RdpSettings.PRESET_BALANCED;
                    boolean qualityChanged = settings.qualityPreset() != quality;
                    settings.saveQualityPreset(quality);
                    boolean adaptive = cbAdaptive.isChecked();
                    boolean adaptiveChanged = settings.adaptiveSize() != adaptive;
                    settings.saveAdaptiveSize(adaptive);
                    boolean resized = false;
                    int index = adaptive ? -1 : indexById(rgSize, rgSize.getCheckedRadioButtonId());
                    if (index >= 0) {
                        int[] preset = RdpSettings.PRESETS[index];
                        int[] size = preset[0] == 0
                                ? RdpSettings.deviceSize(this)
                                : new int[]{preset[0], preset[1]};
                        int[] before = settings.effectiveSize(this);
                        settings.saveSize(size[0], size[1]);
                        // 只改了缩放方式时不要顺带重连一次：那是三四秒的会话重建
                        if (before[0] != size[0] || before[1] != size[1]) {
                            applySize(size[0], size[1]);
                            resized = true;
                        }
                    }
                    // 色深、编解码、是否自适应都是连接时协商的，改完只能重连；
                    // 分辨率那条路已经重连过就不重复
                    if ((qualityChanged || adaptiveChanged) && !resized
                            && RdpNative.state() == RdpNative.STATE_RUNNING) {
                        RdpNative.stop();
                        startSession();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 应用新尺寸：按新尺寸重连。
     *
     * 协议里有更优雅的路——MS-RDPEDISP 的 display-control 可以在不断会话的情况下改分辨率，
     * IronRDP 也把那条通道接上了。但这台 Windows 11 主机实测**完全不理**这个 PDU：发出去
     * 既没有回执，也没有 Deactivation-Reactivation，画面还是原来的尺寸。所以这里走确定能成
     * 的那条路：带新尺寸重连（两三秒），而不是发一条没人理的消息然后让用户以为点坏了。
     */
    private void applySize(int width, int height) {
        if (RdpNative.state() != RdpNative.STATE_RUNNING) {
            // 还没连上（或者已经断了）：改的只是下一次连接要请求的尺寸，不必重连
            return;
        }
        RdpNative.stop();
        startSession();
    }

    /** 自适应打开时把尺寸选项灰掉：此刻的尺寸由屏幕决定，选也别扭。 */
    private static void setSizeGroupEnabled(ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            child.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private static int indexById(RadioGroup group, int id) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 取帧

    /** 画一个默认箭头当光标：服务端只给「用你的默认形状」时用，热点在箭尖。 */
    private void buildArrow() {
        float unit = getResources().getDisplayMetrics().density;
        arrowPath.reset();
        arrowPath.moveTo(0f, 0f);
        arrowPath.lineTo(0f, 16f * unit);
        arrowPath.lineTo(4f * unit, 12f * unit);
        arrowPath.lineTo(6.5f * unit, 17.5f * unit);
        arrowPath.lineTo(9f * unit, 16.3f * unit);
        arrowPath.lineTo(6.6f * unit, 11f * unit);
        arrowPath.lineTo(11f * unit, 11f * unit);
        arrowPath.close();
        cursorPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        cursorPaint.setStrokeJoin(Paint.Join.ROUND);
        cursorPaint.setStrokeWidth(1.2f * unit);
        cursorPaint.setColor(Color.WHITE);
    }

    /**
     * 取服务端下发的光标形状。
     *
     * 序号没变就什么都不做：逐帧比 5 个整数，比每帧拷一张 32×32 位图便宜得多。
     * 形状会随鼠标所在位置变（文本工字形、调整边框的双箭头等），这正是自己画光标
     * 比让服务端烤进画面强的地方。
     */
    private void refreshPointer() {
        int next = RdpNative.pointerInfo(pointerSerial, pointerMeta);
        if (next == 0) {
            return;
        }
        pointerSerial = next;
        int kind = pointerMeta[4];
        if (kind != 0) {
            // 隐藏或"用默认箭头"：服务端位图没用了
            if (cursorBitmap != null) {
                cursorBitmap.recycle();
                cursorBitmap = null;
            }
            cursorView.invalidate();
            return;
        }
        int width = pointerMeta[0];
        int height = pointerMeta[1];
        int count = width * height;
        if (width <= 0 || height <= 0) {
            return;
        }
        if (pointerPixels.length < count) {
            pointerPixels = new int[count];
        }
        if (RdpNative.pointerPixels(pointerPixels) != count) {
            return;
        }
        if (cursorBitmap == null || cursorBitmap.getWidth() != width
                || cursorBitmap.getHeight() != height) {
            if (cursorBitmap != null) {
                cursorBitmap.recycle();
            }
            cursorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // 原生层给的是非预乘 alpha，标清楚否则边缘会发黑
            cursorBitmap.setPremultiplied(false);
        }
        cursorBitmap.setPixels(pointerPixels, 0, width, 0, 0, width, height);
        cursorView.invalidate();
    }

    /** 取帧并绘制。帧号没变时原生侧直接返回 0，这里就不碰画布。 */
    private void drawFrame() {
        sampleRate();
        // 光标形状与画面无关，画面还没准备好也该先取回来
        refreshPointer();
        if (!surfaceReady || surface.getHolder().getSurface() == null
                || !surface.getHolder().getSurface().isValid()) {
            refreshStatus();
            return;
        }
        int w = RdpNative.width();
        int h = RdpNative.height();
        if (w <= 0 || h <= 0) {
            refreshStatus();
            return;
        }
        if (bitmap == null || layout.frameWidth != w || layout.frameHeight != h) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            boolean first = layout.frameWidth <= 0;
            layout.frameWidth = w;
            layout.frameHeight = h;
            if (first) {
                cursorX = w / 2;
                cursorY = h / 2;
            } else {
                // 服务端把尺寸改了（动态调整或重连），光标要留在新画面里
                cursorX = Math.min(cursorX, w - 1);
                cursorY = Math.min(cursorY, h - 1);
            }
        }
        int[] buffer = ensureBuffer(w * h);
        int next = RdpNative.copyFrame(serial, buffer);
        boolean fresh = next > 0;
        if (fresh) {
            serial = next;
            bitmap.setPixels(buffer, 0, w, 0, 0, w, h);
        }
        // 改了缩放方式要立刻重画一次：远端画面静止时不会有新帧，不补这一下要等到
        // 下一次屏幕变化才看得到效果，用户会以为设置没生效
        if (fresh || repaintRequested) {
            repaintRequested = false;
            Canvas canvas = null;
            try {
                canvas = surface.getHolder().lockCanvas();
                if (canvas != null) {
                    drawScaled(canvas);
                }
            } finally {
                if (canvas != null) {
                    surface.getHolder().unlockCanvasAndPost(canvas);
                }
            }
        }
        refreshStatus();
    }

    /** 按设置画：等比居中留黑边，或者拉满整个画面。 */
    private void drawScaled(Canvas canvas) {
        layout.viewWidth = canvas.getWidth();
        layout.viewHeight = canvas.getHeight();
        layout.update(settings.scaleMode());
        canvas.drawARGB(255, 0, 0, 0);
        canvas.drawBitmap(bitmap, layout.matrix(), null);
        // 缩放参数可能刚变：光标的位置换算跟着变，让它重画一次
        cursorView.invalidate();
    }

    private int[] ensureBuffer(int size) {
        if (pixels.length < size) {
            pixels = new int[size];
        }
        return pixels;
    }

    /**
     * 每秒采一次样：连接详情里显示"这台机器此刻真实的帧率与流量"。
     *
     * 像素流量（宽×高×4）和网络流量分开统计：前者说明画面有多大，后者才是真正上了网的
     * 字节数——调「码率与画质」时看它，能直接看出省流档到底省了多少。
     */
    private void sampleRate() {
        long now = SystemClock.uptimeMillis();
        if (lastSampleAt != 0L && now - lastSampleAt < 1000L) {
            return;
        }
        long frames = RdpNative.frameCount();
        long bytes = RdpNative.frameBytes();
        long net = TrafficStats.getUidRxBytes(Process.myUid());
        if (lastSampleAt == 0L) {
            lastSampleAt = now;
            lastSampleFrames = frames;
            lastSampleBytes = bytes;
            lastSampleNet = net;
            return;
        }
        long elapsed = now - lastSampleAt;
        if (elapsed < 1000L) {
            return;
        }
        framesPerSecond = (int) ((frames - lastSampleFrames) * 1000L / elapsed);
        bytesPerSecond = (bytes - lastSampleBytes) * 1000L / elapsed;
        // TrafficStats 在个别设备上返回 UNSUPPORTED（-1），这时就不显示网速
        networkBytesPerSecond = net < 0 || lastSampleNet < 0
                ? -1 : (net - lastSampleNet) * 1000L / elapsed;
        lastSampleAt = now;
        lastSampleFrames = frames;
        lastSampleBytes = bytes;
        lastSampleNet = net;
    }

    /**
     * 状态行。每帧（33 ms）都会被调一次，所以这里按"内容签名 + 500 ms"节流：
     * 状态/尺寸/模式/锁存键没变就不重建字符串，也不跨 JNI 取错误文本。
     */
    private void refreshStatus() {
        int state = RdpNative.state();
        if (state != lastSeenState) {
            boolean lost = lastSeenState == RdpNative.STATE_RUNNING
                    && (state == RdpNative.STATE_CLOSED || state == RdpNative.STATE_FAILED);
            lastSeenState = state;
            // 会话没了要立刻看得见：状态行是唯一能说明"为什么停了"的地方，顺手把
            // 工具栏上的「断开」换成「重连」，否则用户只剩一个退出的选择
            updateBarLabels();
            if (lost) {
                toast(getString(R.string.rdp_session_lost_toast));
                setBarExpanded(true);
            }
            lastStatusAt = 0L;
        }
        long now = SystemClock.uptimeMillis();
        int signature = (((state * 31 + mode) * 31 + layout.frameWidth) * 31 + layout.frameHeight);
        signature = (signature * 31 + latched.hashCode()) * 31 + (connectHost == null ? 0 : connectHost.hashCode());
        if (signature == lastStatusSignature && now - lastStatusAt < STATUS_REFRESH_MS) {
            return;
        }
        lastStatusSignature = signature;
        lastStatusAt = now;
        // 跑着的时候不会有错误文本，少一次 JNI 取字节数组
        String error = state == RdpNative.STATE_RUNNING ? "" : RdpNative.error();
        StringBuilder builder = new StringBuilder();
        builder.append(getString(stateLabel(state)))
                .append(" · ")
                .append(host);
        if (connectHost != null && !connectHost.equals(host)) {
            // 按域名连的时候把解析结果一并写出来，出问题一眼能看出是不是解析错了
            builder.append(" → ").append(connectHost);
        }
        builder.append(':').append(port);
        if (layout.frameWidth > 0) {
            builder.append(" · ").append(layout.frameWidth).append('×').append(layout.frameHeight);
        }
        builder.append(" · ")
                .append(getString(mode == RdpSettings.MODE_MOUSE
                        ? R.string.rdp_mode_mouse : R.string.rdp_mode_touch));
        if (!latched.isEmpty()) {
            // 锁存的修饰键必须一直看得见：忘了松开 Win 的话，之后每次点击都成了 Win+点击
            builder.append(" · ")
                    .append(getString(R.string.rdp_latched_format, latchedNames()));
        }
        if (!error.isEmpty()) {
            builder.append('\n').append(error);
            if (!barExpanded) {
                // 收起时看不到状态行，出错必须先弹开，否则用户只会觉得"画面卡住了"
                setBarExpanded(true);
            }
        }
        tvStatus.setText(builder);
    }

    private static int stateLabel(int state) {
        switch (state) {
            case RdpNative.STATE_CONNECTING:
                return R.string.rdp_state_connecting;
            case RdpNative.STATE_RUNNING:
                return R.string.rdp_state_running;
            case RdpNative.STATE_FAILED:
                return R.string.rdp_state_failed;
            case RdpNative.STATE_CLOSED:
                return R.string.rdp_state_closed;
            default:
                return R.string.rdp_state_idle;
        }
    }

    /** 只用来在详情里显示一个量级，不必精确到 KB。 */
    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    private static float clampF(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
