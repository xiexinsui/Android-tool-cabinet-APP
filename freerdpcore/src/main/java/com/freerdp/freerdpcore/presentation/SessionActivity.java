/*
   Android Session Activity

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.presentation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.inputmethodservice.KeyboardView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.RoundedCorner;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ConnectionReference;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.ClipboardManagerProxy;

public class SessionActivity extends AppCompatActivity
    implements LibFreeRDP.UIEventListener, ClipboardManagerProxy.OnClipboardChangedListener
{
	public static final String PARAM_CONNECTION_REFERENCE = "conRef";
	/** 工具箱连接簿直接传一个 BookmarkBase 进来（不再走 aFreeRDP 的数据库） */
	public static final String PARAM_BOOKMARK = "bookmark";
	/** 启动器测得的 TCP 握手耗时（毫秒），信息面板里显示 */
	public static final String PARAM_CONNECT_MS = "connectMs";
	/** 用户填的原始主机名（可能被换成了实测可达的 IP，面板里两者都显示） */
	public static final String PARAM_HOST_NAME = "hostName";
	public static final String PARAM_INSTANCE = "instance";
	private static final String TAG = "FreeRDP.SessionActivity";
	static volatile SessionActivity activeSession;
	private Bitmap bitmap;
	private SessionState session;
	private SessionView sessionView;
	private TouchPointerView touchPointerView;

	private static final int REFRESH_SESSIONVIEW = 1;
	private static final int DISPLAY_TOAST = 2;
	private static final int GRAPHICS_CHANGED = 6;
	private static final int POINTER_SET = 7;
	private static final int REQUEST_MEDIA_PERMISSIONS = 100;

	private RailWindowManager railManager;

	private final Handler uiHandler = new Handler(Looper.getMainLooper()) {
		@Override public void handleMessage(Message msg)
		{
			switch (msg.what)
			{
				case GRAPHICS_CHANGED:
				{
					sessionView.onSurfaceChange(session);
					scrollView.requestLayout();
					break;
				}
				case REFRESH_SESSIONVIEW:
				{
					sessionView.invalidateRegion();
					break;
				}
				case DISPLAY_TOAST:
				{
					Toast errorToast = Toast.makeText(getApplicationContext(), msg.obj.toString(),
					                                  Toast.LENGTH_LONG);
					errorToast.show();
					break;
				}
				case POINTER_SET:
				{
					Bundle data = msg.getData();
					if (data != null && data.containsKey("pixels"))
					{
						int[] pixels = data.getIntArray("pixels");
						int width = data.getInt("width");
						int height = data.getInt("height");
						int hotX = data.getInt("hotX");
						int hotY = data.getInt("hotY");
						sessionView.setRemoteCursor(pixels, width, height, hotX, hotY);
					}
					else
						sessionView.setRemoteCursor(null, 0, 0, 0, 0);
					break;
				}
			}
		}
	};

	private int screen_width;
	private int screen_height;

	private BookmarkBase pendingConnectBookmark = null;
	private boolean connectCancelledByUser = false;
	private boolean sessionRunning = false;
	private long backPressedTime = 0;

	private SessionViewModel sessionViewModel;
	private ScrollView2D scrollView;
	private ClipboardManagerProxy mClipboardManager;
	private SessionInputManager inputManager;
	private SessionDialogs dialogs;

	private FloatingToolbar floatingToolbar;
	private boolean forceFullscreen = false;
	private long connectMs = 0;

	/* 信息面板用的运行时统计 */
	private final java.util.concurrent.atomic.AtomicInteger frameTicks =
	    new java.util.concurrent.atomic.AtomicInteger();
	private long statsAt = 0;
	private long sessionUptimeAt = 0;
	private int lastFrameTicks = 0;
	private int framesPerSecond = 0;
	private long lastRxBytes = -1;
	private long rxBaseBytes = -1;
	private long rxBytesPerSecond = 0;
	private long totalRxBytes = -1;
	private LibFreeRDP.ProtocolInfo lastProtocol;

	/**
	 * 每秒采一次「服务端发了多少帧位图更新」和本进程流量，供信息面板显示。
	 * 桌面静止时帧率就是 0——那是实话，不是没采到。
	 * 流量用 TrafficStats：原生层的 `freerdp_get_stats` 要内部的 rdpRdp*，客户端取不到。
	 */
	private final Runnable statsSampler = new Runnable() {
		@Override public void run()
		{
			long now = android.os.SystemClock.uptimeMillis();
			long elapsed = now - statsAt;
			if (elapsed >= 1000L)
			{
				int ticks = frameTicks.get();
				framesPerSecond = (int)((ticks - lastFrameTicks) * 1000L / elapsed);
				lastFrameTicks = ticks;
				long rx = android.net.TrafficStats.getUidRxBytes(
				    android.os.Process.myUid());
				if (rx >= 0 && lastRxBytes >= 0)
					rxBytesPerSecond = (rx - lastRxBytes) * 1000L / elapsed;
				if (rx >= 0)
				{
					if (rxBaseBytes < 0)
						rxBaseBytes = rx; // 会话开始时的累计值，之后只报会话期间的量
					totalRxBytes = rx - rxBaseBytes;
					lastRxBytes = rx;
				}
				else
					totalRxBytes = -1;
				if (session != null)
					lastProtocol = LibFreeRDP.getProtocolInfo(session.getInstance());
				statsAt = now;
			}
			if (sessionView != null)
				sessionView.postDelayed(this, 1000L);
		}
	};

	private void hideSystemBars()
	{
		boolean hideStatusBar = forceFullscreen || ApplicationSettingsActivity.getHideStatusBar(this);
		boolean hideNavBar = forceFullscreen || ApplicationSettingsActivity.getHideNavigationBar(this);

		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

		if (getSupportActionBar() != null)
			getSupportActionBar().hide();

		WindowInsetsControllerCompat controller =
		    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
		controller.setAppearanceLightStatusBars(false);
		controller.setAppearanceLightNavigationBars(false);

		getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
		getWindow().setNavigationBarContrastEnforced(false);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
		{
			int toHide = 0;
			if (hideStatusBar)
				toHide |= WindowInsetsCompat.Type.statusBars();
			if (hideNavBar)
				toHide |= WindowInsetsCompat.Type.navigationBars();

			if (toHide != 0)
			{
				controller.hide(toHide);
				controller.setSystemBarsBehavior(
				    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			}
			else
			{
				controller.show(WindowInsetsCompat.Type.systemBars());
			}
		}
		else
		{
			// API 29: layout flags must be set explicitly to keep drawing behind system bars.
			int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
			            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
			if (hideStatusBar)
				flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
			if (hideNavBar)
				flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
			if ((flags & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) !=
			    0)
				flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

			getWindow().getDecorView().setSystemUiVisibility(flags);
		}

		WindowManager.LayoutParams lp = getWindow().getAttributes();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			lp.layoutInDisplayCutoutMode =
			    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
		else
			lp.layoutInDisplayCutoutMode =
			    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		getWindow().setAttributes(lp);
	}

	@Override public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		hideSystemBars();

		connectMs = getIntent() != null ? getIntent().getLongExtra(PARAM_CONNECT_MS, 0) : 0;
		this.setContentView(R.layout.session);

		Log.v(TAG, "Session.onCreate");

		// ATTENTION: We use the onGlobalLayout notification to start our
		// session.
		// This is because only then we can know the exact size of our session
		// when using fit screen
		// accounting for any status bars etc. that Android might throws on us.
		// A bit weird looking
		// but this is the only way ...
		final View activityRootView = findViewById(R.id.session_root_view);
		activityRootView.setFitsSystemWindows(false);
		ViewCompat.setOnApplyWindowInsetsListener(activityRootView,
		                                          (v, insets) -> onWindowInsetsChanged(v, insets));
		activityRootView.getViewTreeObserver().addOnGlobalLayoutListener(
		    new OnGlobalLayoutListener() {
			    @Override public void onGlobalLayout()
			    {
				    screen_width = scrollView.getWidth() - scrollView.getPaddingLeft() -
				                   scrollView.getPaddingRight();
				    screen_height = scrollView.getHeight() - scrollView.getPaddingTop() -
				                    scrollView.getPaddingBottom();

				    // start session
				    if (!sessionRunning && getIntent() != null)
				    {
					    processIntent(getIntent());
					    sessionRunning = true;
				    }
			    }
		    });

		sessionView = findViewById(R.id.sessionView);
		sessionView.requestFocus();

		touchPointerView = findViewById(R.id.touchPointerView);
		// 鼠标光标现在由 SessionView 画进远端画面里，悬浮的触摸指针层不再需要
		touchPointerView.setVisibility(View.GONE);

		floatingToolbar = new FloatingToolbar(this, new FloatingToolbar.Listener() {
			@Override public void onPointerMode()
			{
				togglePointerMode();
			}
			@Override public void onResolution()
			{
				SessionResolution.showDialog(SessionActivity.this, SessionActivity.this::applyResolution);
			}
			@Override public void onKeyboard()
			{
				if (inputManager != null)
					inputManager.toggleSystemKeyboard();
			}
			@Override public void onInfo()
			{
				showSessionInfo();
			}
			@Override public void onOrient()
			{
				toggleOrientation();
			}
			@Override public void onFullscreen()
			{
				toggleFullscreen();
			}
			@Override public void onDisconnect()
			{
				if (session != null)
					LibFreeRDP.disconnect(session.getInstance());
			}
		});

		KeyboardView keyboardView = findViewById(R.id.extended_keyboard);
		KeyboardView modifiersKeyboardView = findViewById(R.id.extended_keyboard_header);

		scrollView = findViewById(R.id.sessionScrollView);
		// 全屏、横竖屏、软键盘都会改变可视区大小：尺寸一变就重新算适配缩放
		scrollView.addOnLayoutChangeListener(
		    (v, left, top, right, bottom, ol, ot, or, ob) -> applyFitZoom());
		scrollView.setScrollViewListener(null);
		// 画面铺不满时四周是黑边，那些像素属于 ScrollView 而不是 SessionView；
		// 触摸板模式下把落在黑边上的手势转给 SessionView，黑边上也能推光标
		scrollView.setOnTouchListener((v, event) -> sessionView.handleExternalTouch(event));
		railManager = new RailWindowManager(this, findViewById(R.id.railContainer), sessionView);
		sessionViewModel = new ViewModelProvider(this).get(SessionViewModel.class);
		sessionViewModel.getState().observe(this, this::onConnectionStateChanged);

		dialogs = new SessionDialogs(this, new SessionDialogs.OnUserCancelListener() {
			@Override public void onUserCancel()
			{
				connectCancelledByUser = true;
			}
		});

		// Wire up the input manager (instance is attached later in bindSession()).
		inputManager = new SessionInputManager(this, scrollView, sessionView, touchPointerView,
		                                       keyboardView, modifiersKeyboardView);
		sessionView.setSessionViewListener(inputManager);
		touchPointerView.setTouchPointerListener(inputManager);
		sessionView.setScaleGestureDetector(
		    new ScaleGestureDetector(this, inputManager.getPinchZoomListener()));

		// 指针模式与显示方式沿用上一次的选择（工具栏上可切）
		sessionView.setPointerMode(ApplicationSettingsActivity.getPointerMode(this));
		floatingToolbar.setPointerMode(sessionView.getPointerMode());
		syncOrientLabel();

		mClipboardManager = ClipboardManagerProxy.getClipboardManager(this);
		mClipboardManager.addClipboardChangedListener(this);

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed()
			{
				handleBackPressed();
			}
		});

		hideSystemBars();
	}

	@Override public void onWindowFocusChanged(boolean hasFocus)
	{
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus)
		{
			hideSystemBars();
			mClipboardManager.getPrimaryClipManually();
		}
	}

	@Override protected void onStart()
	{
		super.onStart();
		Log.v(TAG, "Session.onStart");
	}

	@Override protected void onRestart()
	{
		super.onRestart();
		Log.v(TAG, "Session.onRestart");
	}

	@Override protected void onResume()
	{
		super.onResume();
		Log.v(TAG, "Session.onResume");
		activeSession = this;
	}

	@Override protected void onPause()
	{
		super.onPause();
		Log.v(TAG, "Session.onPause");
		if (activeSession == this)
			activeSession = null;
		// hide any visible keyboards
		inputManager.hideKeyboards();
	}

	@Override protected void onStop()
	{
		super.onStop();
		Log.v(TAG, "Session.onStop");
	}

	@Override protected void onDestroy()
	{
		if (sessionView != null)
			sessionView.removeCallbacks(statsSampler);
		if (connectThread != null)
		{
			connectThread.interrupt();
		}
		super.onDestroy();
		Log.v(TAG, "Session.onDestroy");

		// Cancel running disconnect timers.
		GlobalApp.cancelDisconnectTimer();

		// Disconnect only this activity's session.
		if (session != null)
			LibFreeRDP.disconnect(session.getInstance());

		// unregister freerdp session listener
		sessionViewModel.unregister();

		// remove clipboard listener
		mClipboardManager.removeClipboardboardChangedListener(this);

		// free session
		GlobalApp.freeSession(session.getInstance());

		session = null;
	}

	@Override public void onConfigurationChanged(Configuration newConfig)
	{
		super.onConfigurationChanged(newConfig);

		// reload keyboard resources (changed from landscape)
		inputManager.reloadKeyboards();

		// 旋转后立刻重算适配缩放：等这一轮布局结束再量滚动区尺寸
		if (sessionView != null)
			sessionView.post(this::applyFitZoom);
		// 标签以真实方向为准：自动旋转会把方向转过去，按钮却不能停在「横屏」
		syncOrientLabel();

		hideSystemBars();

		// screen_width/screen_height will be updated by the next onGlobalLayout callback;
		if (session != null && session.getBookmark() != null &&
		    session.getBookmark().getActiveScreenSettings().isFitScreen())
		{
			scrollView.post(() -> {
				if (screen_width > 0 && screen_height > 0)
					LibFreeRDP.sendMonitorLayout(session.getInstance(), screen_width,
					                             screen_height);
			});
		}
	}

	private WindowInsetsCompat onWindowInsetsChanged(View rootView, WindowInsetsCompat windowInsets)
	{
		// 会话内「全屏」开关必须一起参与判断：只看设置项的话，隐藏了系统栏却仍然按
		// 状态栏/导航栏留白，画面上下会各出一条没人用的黑边。
		boolean hideStatusBar = forceFullscreen || ApplicationSettingsActivity.getHideStatusBar(this);
		boolean hideNavBar = forceFullscreen || ApplicationSettingsActivity.getHideNavigationBar(this);
		boolean fitSafeArea =
		    !forceFullscreen && ApplicationSettingsActivity.getFitRoundedCorners(this);

		// 状态栏那一条不再从布局里挖走。挖了会在画面顶上留一块纯黑：实测横屏 194 px、
		// 竖屏 211 px，而系统真正让给状态栏的只有 35 / ~100 px——MIUI 把刘海的内边距也
		// 并进了 top，照着它留白就凭空多出一截没人用的黑块。
		// 现在桌面一直铺到屏幕顶（状态栏是半透明的，压在画面上），工具栏自己往下让。
		rootView.setPadding(0, 0, 0, 0);
		int statusBarTop = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
		Insets navInsets = hideNavBar
		                       ? Insets.NONE
		                       : windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
		// 布局不再为状态栏留白了，工具栏改自己往下让，免得按钮压在系统图标上
		if (floatingToolbar != null)
			floatingToolbar.setInsets(navInsets.left, hideStatusBar ? 0 : statusBarTop,
			                          navInsets.right, 0);

		int safeLeft = 0, safeTop = 0, safeRight = 0, safeBottom = 0;
		if (fitSafeArea && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			WindowInsets platformInsets = windowInsets.toWindowInsets();
			if (platformInsets != null)
			{
				boolean landscape = getResources().getConfiguration().orientation ==
				                    Configuration.ORIENTATION_LANDSCAPE;

				int radTL = cornerRadius(platformInsets, RoundedCorner.POSITION_TOP_LEFT);
				int radBL = cornerRadius(platformInsets, RoundedCorner.POSITION_BOTTOM_LEFT);
				int radTR = cornerRadius(platformInsets, RoundedCorner.POSITION_TOP_RIGHT);
				int radBR = cornerRadius(platformInsets, RoundedCorner.POSITION_BOTTOM_RIGHT);

				if (landscape)
				{
					safeLeft = Math.max(0, Math.max(radTL, radBL) - rootView.getPaddingLeft());
					safeRight = Math.max(0, Math.max(radTR, radBR) - rootView.getPaddingRight());
				}
				else
				{
					safeTop = Math.max(0, Math.max(radTL, radTR) - rootView.getPaddingTop());
					safeBottom = Math.max(0, Math.max(radBL, radBR) - rootView.getPaddingBottom());
				}
			}
		}

		scrollView.setPadding(Math.max(safeLeft, navInsets.left), safeTop,
		                      Math.max(safeRight, navInsets.right),
		                      Math.max(safeBottom, navInsets.bottom));
		if (inputManager != null)
			inputManager.setSafeInsets(safeLeft, safeTop);

		return WindowInsetsCompat.CONSUMED;
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private static int cornerRadius(WindowInsets insets, int position)
	{
		RoundedCorner corner = insets.getRoundedCorner(position);
		return (corner != null) ? corner.getRadius() : 0;
	}

	private void processIntent(Intent intent)
	{
		// get either session instance or create one from a bookmark/uri
		Bundle bundle = intent.getExtras();
		Uri openUri = intent.getData();
		if (openUri != null)
		{
			// Launched from URI, e.g:
			// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-
			connect(openUri);
		}
		else if (bundle.containsKey(PARAM_INSTANCE))
		{
			int inst = bundle.getInt(PARAM_INSTANCE);
			session = GlobalApp.getSession(inst);
			bitmap = session.getSurface().getBitmap();
			bindSession();
		}
		else if (bundle.containsKey(PARAM_BOOKMARK))
		{
			BookmarkBase bookmark = bundle.getParcelable(PARAM_BOOKMARK);
			if (bookmark != null)
				connect(bookmark);
			else
				closeSessionActivity(RESULT_CANCELED);
		}
		else if (bundle.containsKey(PARAM_CONNECTION_REFERENCE))
		{
			String refStr = bundle.getString(PARAM_CONNECTION_REFERENCE);
			if (ConnectionReference.isHostnameReference(refStr))
			{
				BookmarkBase bookmark = new BookmarkBase();
				bookmark.setHostname(ConnectionReference.getHostname(refStr));
				connect(bookmark);
			}
			else
			{
				closeSessionActivity(RESULT_CANCELED);
			}
		}
		else
		{
			// no session found - exit
			closeSessionActivity(RESULT_CANCELED);
		}
	}

	private void connect(BookmarkBase bookmark)
	{
		session = GlobalApp.createSession(bookmark, getApplicationContext());

		BookmarkBase.ScreenSettings screenSettings =
		    session.getBookmark().getActiveScreenSettings();
		Log.v(TAG, "Screen Resolution: " + screenSettings.getResolutionString());
		if (screenSettings.isAutomatic())
		{
			// Instead of enforcing obsolete ratios based on screen categories,
			// directly map to actual device metrics without arbitrary multi-scaling.
			screenSettings.setHeight(screen_height);
			screenSettings.setWidth(screen_width);
		}
		if (screenSettings.isFitScreen())
		{
			screenSettings.setHeight(screen_height);
			screenSettings.setWidth(screen_width);
		}

		// RECORD_AUDIO / CAMERA: only if the matching redirect is enabled.
		java.util.ArrayList<String> needed = new java.util.ArrayList<>();
		if (bookmark.getAdvancedSettings().getRedirectMicrophone() &&
		    checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
		        PackageManager.PERMISSION_GRANTED)
			needed.add(Manifest.permission.RECORD_AUDIO);
		if (bookmark.getAdvancedSettings().getRedirectCamera() &&
		    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
			needed.add(Manifest.permission.CAMERA);

		if (!needed.isEmpty())
		{
			pendingConnectBookmark = bookmark;
			requestPermissions(needed.toArray(new String[0]), REQUEST_MEDIA_PERMISSIONS);
			return;
		}

		connectWithTitle(bookmark.getLabel());
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	                                       @NonNull int[] grantResults)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_MEDIA_PERMISSIONS && pendingConnectBookmark != null)
		{
			BookmarkBase bookmark = pendingConnectBookmark;
			pendingConnectBookmark = null;
			connectWithTitle(bookmark.getLabel());
		}
	}

	private void connect(Uri openUri)
	{
		session = GlobalApp.createSession(openUri, getApplicationContext());

		connectWithTitle(openUri.getAuthority());
	}

	static class ConnectThread extends Thread
	{
		private final SessionState runnableSession;
		private final Context context;

		public ConnectThread(@NonNull Context context, @NonNull SessionState session)
		{
			this.context = context;
			runnableSession = session;
		}

		public void run()
		{
			runnableSession.connect(context.getApplicationContext());
		}
	}

	private ConnectThread connectThread = null;

	private void connectWithTitle(String title)
	{
		session.setUIEventListener(this);

		sessionViewModel.register(session.getInstance());

		dialogs.showProgress(title, () -> {
			connectCancelledByUser = true;
			LibFreeRDP.cancelConnection(session.getInstance());
		});

		connectThread = new ConnectThread(getApplicationContext(), session);
		connectThread.start();
	}

	// binds the current session to the activity by wiring it up with the
	// sessionView and updating all internal objects accordingly
	private void bindSession()
	{
		Log.v(TAG, "bindSession called");
		session.setUIEventListener(this);
		sessionView.onSurfaceChange(session);
		scrollView.requestLayout();

		Bitmap surface = session.getSurface() != null ? session.getSurface().getBitmap() : null;
		inputManager.attachSession(session.getInstance(), surface);
		sessionView.post(this::applyFitZoom);
		// 运行时统计从会话真正建立开始采，每秒一次
		statsAt = android.os.SystemClock.uptimeMillis();
		sessionUptimeAt = statsAt;
		lastFrameTicks = frameTicks.get();
		lastRxBytes = -1;
		rxBaseBytes = -1;
		rxBytesPerSecond = 0;
		totalRxBytes = -1;
		sessionView.removeCallbacks(statsSampler);
		sessionView.post(statsSampler);
		inputManager.setScreenSize(screen_width, screen_height);
		hideSystemBars();
		View rootView = findViewById(R.id.session_root_view);
		if (rootView != null)
			ViewCompat.requestApplyInsets(rootView);
	}

	/**
	 * 适配屏幕：把 1920×1080 的远端画面按「能整幅放下」的比例缩放（只缩不放），
	 * 请求的分辨率不变，变的只是本地显示缩放；横竖屏切换后重新计算。
	 */
	private void applyFitZoom()
	{
		if (sessionView == null || scrollView == null)
			return;
		Bitmap bitmap =
		    session != null && session.getSurface() != null ? session.getSurface().getBitmap()
		                                                    : null;
		if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0)
			return;

		int viewWidth =
		    scrollView.getWidth() - scrollView.getPaddingLeft() - scrollView.getPaddingRight();
		int viewHeight =
		    scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
		if (viewWidth <= 0 || viewHeight <= 0)
			return;

		// 永远「整幅放下」：画面一定完整，宁可留黑边也不裁掉任务栏。要更大用捏合放大，
		// 要没黑边就把桌面分辨率设成和屏幕同比例（连接簿的「显示设置」里）。
		float base = Math.min((float)viewWidth / bitmap.getWidth(),
		                      (float)viewHeight / bitmap.getHeight());
		// setBaseScale 会 requestLayout，而 requestLayout 又会回调这里的 onLayoutChange；
		// 等值判断在 SessionView 里做，否则两边互相触发（实测 4 秒内 onMeasure 1500+ 次）
		if (sessionView.setBaseScale(base))
			// 视口变了之后光标可能已经不在眼里
			sessionView.revealCursorIfOffscreen();
	}

	/**
	 * 会话内改分辨率：走 display-control（MS-RDPEDISP）动态调整，不用断线重连。
	 *
	 * 服务端不认这个 PDU 时画面尺寸根本不会变，所以过一会儿确认一次再给结论——
	 * 不确认的话用户会以为改了，回到桌面发现还是老的。
	 */
	private void applyResolution(int[] size)
	{
		if (session == null || scrollView == null || size[0] <= 0 || size[1] <= 0)
			return;
		LibFreeRDP.sendMonitorLayout(session.getInstance(), size[0], size[1]);
		final int wantedWidth = size[0];
		final int wantedHeight = size[1];
		scrollView.postDelayed(() -> {
			Bitmap bitmap = session != null && session.getSurface() != null
			                    ? session.getSurface().getBitmap()
			                    : null;
			boolean applied =
			    bitmap != null && bitmap.getWidth() == wantedWidth && bitmap.getHeight() == wantedHeight;
			android.widget.Toast.makeText(this,
			                               applied
			                                   ? getString(R.string.session_resolution_applied, wantedWidth,
			                                   wantedHeight)
			                                   : getString(R.string.session_resolution_unsupported,
			                                   wantedWidth, wantedHeight),
			                               android.widget.Toast.LENGTH_SHORT)
			    .show();
		}, 1800);
	}

	private void togglePointerMode()
	{
		int next = sessionView.getPointerMode() == SessionView.MODE_MOUSE
		               ? SessionView.MODE_TOUCH
		               : SessionView.MODE_MOUSE;
		sessionView.setPointerMode(next);
		ApplicationSettingsActivity.savePointerMode(this, next);
		floatingToolbar.setPointerMode(next);
		android.widget.Toast.makeText(this,
		                               next == SessionView.MODE_MOUSE
		                                   ? R.string.session_pointer_mode_mouse
		                                   : R.string.session_pointer_mode_touch,
		                               android.widget.Toast.LENGTH_SHORT)
		    .show();
	}

	private void toggleOrientation()
	{
		// 以真实方向为判断依据：自动旋转可能已经把屏幕转过去了，按钮状态却还是旧的
		boolean landscape = getResources().getConfiguration().orientation ==
		                    Configuration.ORIENTATION_LANDSCAPE;
		// SENSOR_LANDSCAPE 而不是 LANDSCAPE：前者允许两个方向的横屏，翻过来也能用
		setRequestedOrientation(landscape
		                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
		                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		floatingToolbar.setLandscape(!landscape);
	}

	private void syncOrientLabel()
	{
		if (floatingToolbar != null)
			floatingToolbar.setLandscape(
			    getResources().getConfiguration().orientation ==
			    Configuration.ORIENTATION_LANDSCAPE);
	}

	private void toggleFullscreen()
	{
		boolean fullscreen = !floatingToolbar.isFullscreen();
		floatingToolbar.setFullscreen(fullscreen);
		forceFullscreen = fullscreen;
		if (fullscreen)
		{
			hideSystemBars();
		}
		else
		{
			WindowInsetsControllerCompat controller =
			    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
			controller.show(WindowInsetsCompat.Type.systemBars());
		}
		// 系统栏没变化时不会再派发 insets，这里主动走一遍留白计算
		ViewCompat.requestApplyInsets(findViewById(R.id.session_root_view));
		sessionView.post(this::applyFitZoom);
	}

	/** 连接信息：主机、桌面尺寸/色深、引擎版本、客户端版本与握手耗时。 */
	private void showSessionInfo()
	{
		BookmarkBase bookmark = session != null ? session.getBookmark() : null;
		String host = bookmark != null ? bookmark.getHostname() : "-";
		String requestedHost = getIntent() != null ? getIntent().getStringExtra(PARAM_HOST_NAME) : null;
		if (requestedHost != null && !requestedHost.isEmpty() && !requestedHost.equals(host))
			host = requestedHost + " → " + host;
		int port = bookmark != null && bookmark.getPort() > 0 ? bookmark.getPort() : 3389;
		int depth = bookmark != null ? bookmark.getActiveScreenSettings().getColors() : 0;

		// 桌面尺寸取远端位图，不能取 sessionView 的宽高——那是缩放后的显示尺寸
		Bitmap bitmap =
		    session != null && session.getSurface() != null ? session.getSurface().getBitmap() : null;
		int width = bitmap != null ? bitmap.getWidth() : screen_width;
		int height = bitmap != null ? bitmap.getHeight() : screen_height;

		String client = "";
		try
		{
			client = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		}
		catch (Exception ignored)
		{
		}

		StringBuilder text = new StringBuilder();
		text.append(getString(R.string.session_info_host)).append("：")
		    .append(host).append(':').append(port).append('\n');
		text.append(getString(R.string.session_info_desktop)).append("：")
		    .append(width).append('×').append(height);
		if (depth > 0)
			text.append(" · ").append(depth).append(" 位色");
		text.append('\n');

		text.append(getString(R.string.session_info_display)).append("：")
		    .append(Math.round(sessionView.getZoom() * 100)).append("% · ")
		    .append(sessionView.getPointerMode() == SessionView.MODE_MOUSE
		                ? getString(R.string.session_pointer_short_mouse)
		                : getString(R.string.session_pointer_short_touch))
		    .append('\n');

		LibFreeRDP.ProtocolInfo protocol = lastProtocol;
		if (protocol != null)
		{
			text.append(getString(R.string.session_info_protocol)).append("：")
			    .append(protocolVersionLabel(protocol.rdpVersion)).append(" · ")
			    .append(protocol.nla ? "NLA" : "无 NLA").append(" + ")
			    .append(protocol.tls ? "TLS" : (protocol.rdpSecurity ? "标准 RDP 安全" : "不加密"))
			    .append(" · ").append(protocol.remoteFx ? "RemoteFX" : "位图")
			    .append(" + RDPGFX").append(" · ")
			    .append(getString(protocol.udpActive() ? R.string.session_info_transport_udp
			                                          : R.string.session_info_transport_tcp));
			if (!protocol.udpActive() && protocol.udpSupported())
				// 服务端能力集里开了 UDP，但 FreeRDP 客户端的 multitransport 处理器
				// 直接回 E_ABORT（multitransport_no_udp），所以实际永远走 TCP
				text.append(getString(R.string.session_info_udp_unsupported));
			text.append('\n');
			text.append(getString(R.string.session_info_fps)).append("：")
			    .append(framesPerSecond).append(" fps · ")
			    .append(getString(R.string.session_info_traffic)).append("：")
			    .append(totalRxBytes < 0 ? getString(R.string.session_info_traffic_unavailable)
			                             : formatRate(rxBytesPerSecond) + " ↓ · " +
			                                   getString(R.string.session_info_traffic_session) + " " +
			                                   formatBytes(totalRxBytes))
			    .append('\n');
		}

		text.append(getString(R.string.session_info_engine)).append("：")
		    .append(LibFreeRDP.getVersion()).append('\n');
		if (!client.isEmpty())
			text.append(getString(R.string.session_info_client)).append("：")
			    .append(client).append('\n');
		if (sessionUptimeAt > 0)
			text.append(getString(R.string.session_info_uptime)).append("：")
			    .append(formatDuration(android.os.SystemClock.uptimeMillis() - sessionUptimeAt))
			    .append('\n');
		if (connectMs > 0)
			text.append(getString(R.string.session_info_rtt)).append("：")
			    .append(connectMs).append(" ms");

		new androidx.appcompat.app.AlertDialog.Builder(this)
		    .setTitle(R.string.session_info_title)
		    .setMessage(text.toString())
		    .setPositiveButton(R.string.session_info_ok, null)
		    .show();
	}

	/** `freerdp_rdp_version_string` 给的是 "RDP_VERSION_10_11" 这种，面板上太长了。 */
	private static String protocolVersionLabel(String raw)
	{
		if (raw == null || raw.isEmpty())
			return "-";
		String trimmed = raw.replace("RDP_VERSION_", "").replace("_", ".");
		return "RDP " + trimmed;
	}

	private static String formatBytes(long bytes)
	{
		if (bytes < 1024L)
			return bytes + " B";
		if (bytes < 1024L * 1024L)
			return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
		return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	private static String formatRate(long bytesPerSecond)
	{
		return formatBytes(Math.max(0, bytesPerSecond)) + "/s";
	}

	private static String formatDuration(long millis)
	{
		long seconds = Math.max(0, millis / 1000L);
		return String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600,
		                     (seconds % 3600) / 60, seconds % 60);
	}

	private void closeSessionActivity(int resultCode)
	{
		// Go back to home activity (and send intent data back to home)
		setResult(resultCode, getIntent());
		finish();
	}

	public void handleBackPressed()
	{
		// hide keyboards (if any visible) or send alt+f4 to the session
		if (inputManager.isAnyKeyboardVisible())
		{
			inputManager.hideKeyboards();
			return;
		}
		if (inputManager.handleBackAsAltF4())
		{
			return;
		}
		if (System.currentTimeMillis() - backPressedTime < 2000)
		{
			connectCancelledByUser = true;
			LibFreeRDP.disconnect(session.getInstance());
		}
		else
		{
			backPressedTime = System.currentTimeMillis();
			Toast.makeText(this, R.string.session_double_back_to_exit, Toast.LENGTH_SHORT).show();
		}
	}

	@Override public boolean onKeyLongPress(int keyCode, KeyEvent event)
	{
		if (inputManager.onAndroidKeyLongPress(keyCode))
			return true;
		return super.onKeyLongPress(keyCode, event);
	}

	boolean handleKeyEvent(KeyEvent event)
	{
		return inputManager != null && inputManager.onAndroidKeyEvent(event);
	}

	// android keyboard input handling
	// We always use the unicode value to process input from the android
	// keyboard except if key modifiers
	// (like Win, Alt, Ctrl) are activated. In this case we will send the
	// virtual key code to allow key
	// combinations (like Win + E to open the explorer).
	@Override public boolean onKeyDown(int keycode, KeyEvent event)
	{
		if (keycode == KeyEvent.KEYCODE_BACK)
			return super.onKeyDown(keycode, event);
		return inputManager.onAndroidKeyEvent(event);
	}

	@Override public boolean onKeyUp(int keycode, KeyEvent event)
	{
		if (keycode == KeyEvent.KEYCODE_BACK)
			return super.onKeyUp(keycode, event);
		return inputManager.onAndroidKeyEvent(event);
	}

	// onKeyMultiple is called for input of some special characters like umlauts
	// and some symbol characters
	@Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event)
	{
		return inputManager.onAndroidKeyEvent(event);
	}

	// ****************************************************************************
	// KeyboardMapper.KeyProcessingListener — delegated to SessionInputManager

	// ****************************************************************************
	// LibFreeRDP UI event listener implementation
	@Override public void OnSettingsChanged(int width, int height, int bpp)
	{

		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);

		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		if (inputManager != null)
			inputManager.setBitmap(bitmap);

		if (session.getBookmark() == null)
		{
			// Return immediately if we launch from URI
			return;
		}
		// check this settings and initial settings - if they are not equal the
		// server doesn't support our settings
		// FIXME: the additional check (settings.getWidth() != width + 1) is for
		// the RDVH bug fix to avoid accidental notifications
		// (refer to android_freerdp.c for more info on this problem)
		BookmarkBase.ScreenSettings settings = session.getBookmark().getActiveScreenSettings();
		if ((settings.getWidth() != width && settings.getWidth() != width + 1) ||
		    settings.getHeight() != height || settings.getColors() != bpp)
			uiHandler.sendMessage(Message.obtain(
			    null, DISPLAY_TOAST, getResources().getText(R.string.info_capabilities_changed)));
	}

	@Override public void OnGraphicsUpdate(int x, int y, int width, int height)
	{
		frameTicks.incrementAndGet();
		LibFreeRDP.updateGraphics(session.getInstance(), bitmap, x, y, width, height);

		sessionView.addInvalidRegion(new Rect(x, y, x + width, y + height));

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */

		uiHandler.sendEmptyMessage(REFRESH_SESSIONVIEW);
	}

	@Override public void OnGraphicsResize(int width, int height, int bpp)
	{
		// replace bitmap
		if (bpp > 16)
			bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
		else
			bitmap = Bitmap.createBitmap(width, height, Config.RGB_565);
		session.setSurface(new BitmapDrawable(getResources(), bitmap));

		if (inputManager != null)
			inputManager.setBitmap(bitmap);

		/*
		 * since sessionView can only be modified from the UI thread any
		 * modifications to it need to be scheduled
		 */
		uiHandler.sendEmptyMessage(GRAPHICS_CHANGED);
	}

	@Override
	public boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
	                              StringBuilder password)
	{
		return dialogs.promptCredentials(username, domain, password);
	}

	@Override
	public boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
	                                     StringBuilder password)
	{
		return dialogs.promptCredentials(username, domain, password);
	}

	@Override
	public int OnVerifiyCertificateEx(String host, long port, String commonName, String subject,
	                                  String issuer, String fingerprint, long flags)
	{
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;
		return dialogs.verifyCertificate(host, port, subject, issuer, fingerprint, flags);
	}

	@Override
	public int OnVerifyChangedCertificateEx(String host, long port, String commonName,
	                                        String subject, String issuer, String fingerprint,
	                                        String oldSubject, String oldIssuer,
	                                        String oldFingerprint, long flags)
	{
		if (ApplicationSettingsActivity.getAcceptAllCertificates(this))
			return 0;
		return dialogs.verifyChangedCertificate(host, port, subject, issuer, fingerprint, flags);
	}

	@Override public boolean OnExperimentalFeature(int feature)
	{
		final String featureKey;
		final String displayName;
		switch (feature)
		{
			case LibFreeRDP.EXPERIMENTAL_REMOTEAPP:
				featureKey = "remoteapp";
				displayName = getString(R.string.experimental_feature_remoteapp);
				break;
			case LibFreeRDP.EXPERIMENTAL_CAMERA:
				featureKey = "camera";
				displayName = getString(R.string.experimental_feature_camera);
				break;
			default:
				return true;
		}
		if (ApplicationSettingsActivity.isExperimentalEnabled(this, featureKey))
			return true;
		// suppress the generic failure toast; the dialog explains the abort
		connectCancelledByUser = true;
		dialogs.showExperimentalBlocked(displayName);
		return false;
	}

	@Override public void OnRemoteClipboardChanged(String data)
	{
		Log.v(TAG, "OnRemoteClipboardChanged: " + data);
		mClipboardManager.setClipboardData(data);
	}

	@Override public void OnRemoteClipboardImageChanged(byte[] data)
	{
		Log.v(TAG, "OnRemoteClipboardImageChanged: " + data.length + " bytes");
		mClipboardManager.setClipboardImage(data);
	}

	@Override public void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY)
	{
		Bundle data = new Bundle();
		data.putIntArray("pixels", pixels);
		data.putInt("width", width);
		data.putInt("height", height);
		data.putInt("hotX", hotX);
		data.putInt("hotY", hotY);
		Message msg = uiHandler.obtainMessage(POINTER_SET);
		msg.setData(data);
		uiHandler.sendMessage(msg);
	}

	@Override public void OnPointerSetNull()
	{
		uiHandler.sendEmptyMessage(POINTER_SET);
	}

	@Override public void OnPointerSetDefault()
	{
		sessionView.setDefaultCursor();
	}

	@Override public void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels)
	{
		railManager.onWindowUpdate(windowId, width, height, pixels);
	}

	@Override public void OnRailWindowMove(long windowId, int x, int y, int w, int h)
	{
		railManager.onWindowMove(windowId, x, y, w, h);
	}

	@Override public void OnRailWindowHide(long windowId)
	{
		railManager.onWindowHide(windowId);
	}

	@Override public void OnRailWindowDestroy(long windowId)
	{
		railManager.onWindowDestroy(windowId);
	}

	@Override public void OnRailSessionEnd()
	{
		railManager.onSessionEnd();
	}

	@Override public void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId)
	{
		railManager.onMonitoredDesktop(windowIds, activeWindowId);
	}

	// ****************************************************************************
	// SessionView.SessionViewListener and TouchPointerView.TouchPointerListener
	// — delegated to SessionInputManager

	@Override public boolean onGenericMotionEvent(MotionEvent e)
	{
		super.onGenericMotionEvent(e);
		return inputManager != null && inputManager.onGenericMotionEvent(e);
	}

	// ****************************************************************************
	// ClipboardManagerProxy.OnClipboardChangedListener
	@Override public void onClipboardChanged(String data)
	{
		Log.v(TAG, "onClipboardChanged: " + data);
		if (session != null)
			LibFreeRDP.sendClipboardData(session.getInstance(), data);
	}

	@Override public void onClipboardImageChanged(byte[] data, String mimeType)
	{
		if (session != null && data != null)
			LibFreeRDP.sendClipboardImageData(session.getInstance(), data, mimeType);
	}

	private void onConnectionStateChanged(SessionViewModel.ConnectionState state)
	{
		if (session == null)
			return;
		switch (state)
		{
			case CONNECTED:
				onSessionConnected();
				break;
			case FAILED:
				onSessionFailed();
				break;
			case DISCONNECTED:
				onSessionDisconnected();
				break;
			default:
				break;
		}
	}

	private void onSessionConnected()
	{
		Log.v(TAG, "onSessionConnected");

		if (connectCancelledByUser)
		{
			LibFreeRDP.disconnect(session.getInstance());
			closeSessionActivity(RESULT_CANCELED);
			return;
		}

		// bind session
		bindSession();

		if (ApplicationSettingsActivity.getKeepScreenOnWhenConnected(this))
		{
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		dialogs.dismissProgress();

		if (session.getBookmark() == null)
		{
			// Return immediately if we launch from URI
			return;
		}

	}

	private void onSessionFailed()
	{
		Log.v(TAG, "onSessionFailed");

		// cancel any pending input events
		if (inputManager != null)
			inputManager.cancelPendingEvents();

		dialogs.dismissProgress();

		// post error message on UI thread
		if (!connectCancelledByUser)
			uiHandler.sendMessage(Message.obtain(
			    null, DISPLAY_TOAST, getResources().getText(R.string.error_connection_failure)));

		closeSessionActivity(RESULT_CANCELED);
	}

	private void onSessionDisconnected()
	{
		Log.v(TAG, "onSessionDisconnected");

		// cancel any pending input events
		if (inputManager != null)
			inputManager.cancelPendingEvents();

		if (ApplicationSettingsActivity.getKeepScreenOnWhenConnected(this))
		{
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		dialogs.dismissProgress();

		railManager.clear();

		session.setUIEventListener(null);
		closeSessionActivity(RESULT_OK);
	}
}
