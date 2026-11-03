/*
   Android Session view

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.utils.DoubleGestureDetector;

import java.util.Stack;

public class SessionView extends View
{
	/** 用户倍率的上下限：1 = 刚好适配视口，4 = 再大就只剩几个像素了。 */
	public static final float MIN_USER_ZOOM = 1.0f;
	public static final float MAX_USER_ZOOM = 4.0f;
	/** 直控：手指按在哪就打在哪（Win11 触摸屏那套）。 */
	public static final int MODE_TOUCH = 0;
	/** 触摸板：整块画面当触摸板，相对位移推远端光标。 */
	public static final int MODE_MOUSE = 1;
	private static final float SCALE_FACTOR_DELTA = 0.0001f;
	/** 按住多久算长按（松手发右键）。 */
	private static final long LONG_PRESS_MS = 600;
	/** 触控模式的"按住"：比鼠标模式短一点，手机上等太久像卡住了。 */
	private static final long TOUCH_HOLD_MS = 500;
	/** 轻点之后按多久就直接进入拖拽（不必先拖出去一段）。 */
	private static final long ARM_HOLD_MS = 180;
	/** 双指滚轮：屏幕上攒够这么多像素算滚一格。 */
	private static final float TWO_FINGER_NOTCH_DP = 30f;
	/**
	 * 拖动时画面跟随光标的增益。1.0 是「进带即推到位」，等价于增益 1；试过 0.35 的缓动，
	 * 手感反而不跟手。要更缓就调小这一个数。
	 */
	private static final float FOLLOW_GAIN = 1.0f;
	/** 单指滑动的滚轮格距（按远端像素算，不然不同缩放下手感不一样）。 */
	private static final float TOUCH_NOTCH_PX = 56f;
	/** 甩动：每 16 ms 补一格的衰减系数与起停阈值（远端像素/毫秒）。 */
	private static final long FLING_INTERVAL_MS = 16;
	private static final float FLING_DECAY = 0.90f;
	private static final float FLING_START = 0.35f;
	private static final float FLING_MIN = 0.05f;
	private static final int BUTTON_NONE = 0;
	private static final int BUTTON_LEFT = 1;
	private static final int BUTTON_RIGHT = 2;
	private static final int BUTTON_MIDDLE = 3;
	/** 单指手势的判定阶段。 */
	private static final int G_NONE = 0;
	private static final int G_SCROLL = 1;
	private static final int G_DRAG = 2;
	private int width;
	private int height;
	private BitmapDrawable surface;
	private Stack<Rect> invalidRegions;
	private int touchPointerPaddingWidth = 0;
	private int touchPointerPaddingHeight = 0;
	private SessionViewListener sessionViewListener = null;
	private OnZoomChangedListener zoomChangedListener = null;
	private boolean railMode = false;
	// helpers for scaling gesture handling
	/** 有效缩放 = 适配基数 × 用户倍率（等比，两轴永远相同）。 */
	private float scaleFactor = 1.0f;
	private float baseScale = 1.0f;
	private float userZoom = MIN_USER_ZOOM;
	private Matrix scaleMatrix;
	private Matrix invScaleMatrix;
	private RectF invalidRegionF;
	private SessionState currentSession;

	/* 指针手势：模式、位移容差与计时器 */
	private int pointerMode = MODE_TOUCH;
	private int clickSlop;
	private int dragSlop;
	private int scrollSlop;
	private int twoFingerNotchPx;
	private int dragArmMs;
	private float density;
	private int gesture = G_NONE;
	private boolean holdArmed;
	private boolean dragHeld;
	private boolean tapArmed;
	private boolean multiFingerActive;
	private boolean swallowUntilUp;
	private int maxPointers;
	private float multiTravel;
	private float multiAnchorX;
	private float multiAnchorY;
	private float travel;
	private float downX, downY, lastX, lastY;
	private long lastMoveAt;
	private long lastTapAt;
	private float scrollAccum;
	private float flingVelocity;
	private float flingAccum;
	private ScrollView2D scrollView;

	private int[] cursorPixels = null;
	private int cursorWidth = 0;
	private int cursorHeight = 0;
	private int cursorHotX = 0;
	private int cursorHotY = 0;

	/* 远端光标直接画进画面里（随缩放一起变），界面上不再有悬浮的光标层 */
	private Bitmap scaledCursorBitmap = null;
	private boolean cursorIsDefault = true;
	private int cursorRemoteX = -1;
	private int cursorRemoteY = -1;
	private final Paint cursorPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private Drawable fallbackCursor = null;

	private DoubleGestureDetector doubleGestureDetector;
	public SessionView(Context context)
	{
		super(context);
		initSessionView(context);
	}

	public SessionView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		initSessionView(context);
	}

	public SessionView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
		initSessionView(context);
	}

	private void initSessionView(Context context)
	{
		// Ensure the view is focusable so the soft keyboard can attach to it (required for API 30+)
		setFocusable(true);
		setFocusableInTouchMode(true);

		invalidRegions = new Stack<>();
		doubleGestureDetector =
		    new DoubleGestureDetector(context, null, new SessionDoubleGestureListener());

		density = context.getResources().getDisplayMetrics().density;
		int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		// 轻点的容差比系统 touchSlop 宽一半；拖拽一旦是"轻点之后那一按"，半个就够
		clickSlop = touchSlop * 3 / 2;
		scrollSlop = clickSlop;
		dragSlop = Math.max(4, touchSlop / 2);
		twoFingerNotchPx = Math.max(1, Math.round(TWO_FINGER_NOTCH_DP * density));
		dragArmMs = ViewConfiguration.getDoubleTapTimeout();

		scaleMatrix = new Matrix();
		invScaleMatrix = new Matrix();
		invalidRegionF = new RectF();
		// 光标只画在远端画面里，本地这层不再显示任何指针（含外接鼠标）
		setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
	}

	/* External Mouse Hover */
	@Override public boolean onHoverEvent(MotionEvent event)
	{
		if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE)
		{
			// Handle hover move event
			float x = event.getX();
			float y = event.getY();
			// Perform actions based on the hover position (x, y)
			MotionEvent mappedEvent = mapTouchEvent(event);
			sessionViewListener.onSessionViewMouseMove((int)mappedEvent.getX(),
			                                           (int)mappedEvent.getY());
			mappedEvent.recycle();
		}
		// Return true to indicate that you've handled the event
		return true;
	}

	public void setScaleGestureDetector(ScaleGestureDetector scaleGestureDetector)
	{
		doubleGestureDetector.setScaleGestureDetector(scaleGestureDetector);
	}

	public void setSessionViewListener(SessionViewListener sessionViewListener)
	{
		this.sessionViewListener = sessionViewListener;
	}

	public void addInvalidRegion(Rect invalidRegion)
	{
		// correctly transform invalid region depending on current scaling
		invalidRegionF.set(invalidRegion);
		scaleMatrix.mapRect(invalidRegionF);
		invalidRegionF.roundOut(invalidRegion);

		invalidRegions.add(invalidRegion);
	}

	public void invalidateRegion()
	{
		invalidate(invalidRegions.pop());
	}

	public void onSurfaceChange(SessionState session)
	{
		surface = session.getSurface();
		Bitmap bitmap = surface.getBitmap();
		width = bitmap.getWidth();
		height = bitmap.getHeight();
		surface.setBounds(0, 0, width, height);

		setMinimumWidth(width);
		setMinimumHeight(height);

		requestLayout();
		currentSession = session;
	}

	/** 等比缩放：远端一个像素占多少屏幕像素。 */
	public float getZoom()
	{
		return scaleFactor;
	}

	public void setRailMode(boolean rail)
	{
		if (railMode == rail)
			return;
		railMode = rail;
		invalidate();
	}

	public interface OnZoomChangedListener
	{
		void onZoomChanged(float zoom);
	}

	public void setOnZoomChangedListener(OnZoomChangedListener l)
	{
		zoomChangedListener = l;
	}

	/**
	 * 适配基数：把远端画面**整幅放下**的比例（`min(视口/画面)`，横竖屏都一样）。
	 * 画面永远完整——宁可留黑边也不裁掉任务栏；要更大用捏合放大，要没黑边就选
	 * 和屏幕同比例的桌面分辨率。由 SessionActivity 在可视区变化时重算，转屏时只有
	 * 它变，用户倍率保留。
	 *
	 * @return 是否真的变了——没变就别再触发重排与跟随
	 */
	public boolean setBaseScale(float base)
	{
		base = Math.max(0.05f, Math.min(base, 8.0f));
		if (Math.abs(base - baseScale) < SCALE_FACTOR_DELTA)
			return false;
		baseScale = base;
		applyScale();
		return true;
	}

	/** 用户倍率：捏合与双击放大改的是它，1× 就是「刚好适配」。 */
	public void setUserZoom(float zoom)
	{
		zoom = Math.max(MIN_USER_ZOOM, Math.min(zoom, MAX_USER_ZOOM));
		if (Math.abs(zoom - userZoom) < SCALE_FACTOR_DELTA)
			return;
		userZoom = zoom;
		applyScale();
	}

	public float getUserZoom()
	{
		return userZoom;
	}

	private void applyScale()
	{
		scaleFactor = baseScale * userZoom;
		scaleMatrix.setScale(scaleFactor, scaleFactor);
		invScaleMatrix.setScale(1.0f / scaleFactor, 1.0f / scaleFactor);

		if (cursorPixels != null)
			applyScaledCursor();

		if (zoomChangedListener != null)
			zoomChangedListener.onZoomChanged(scaleFactor);

		requestLayout();
	}

	public boolean isAtMaxZoom()
	{
		return (userZoom > (MAX_USER_ZOOM - SCALE_FACTOR_DELTA));
	}

	public boolean isAtMinZoom()
	{
		return (userZoom < (MIN_USER_ZOOM + SCALE_FACTOR_DELTA));
	}

	public void setTouchPointerPadding(int width, int height)
	{
		touchPointerPaddingWidth = width;
		touchPointerPaddingHeight = height;
		requestLayout();
	}

	public int getTouchPointerPaddingWidth()
	{
		return touchPointerPaddingWidth;
	}

	public int getTouchPointerPaddingHeight()
	{
		return touchPointerPaddingHeight;
	}

	@Override public void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		this.setMeasuredDimension((int)(width * scaleFactor) + touchPointerPaddingWidth,
		                          (int)(height * scaleFactor) + touchPointerPaddingHeight);
	}

	@Override public void onDraw(@NonNull Canvas canvas)
	{
		super.onDraw(canvas);

		canvas.save();
		canvas.concat(scaleMatrix);
		canvas.drawColor(Color.BLACK);
		if (!railMode && surface != null)
		{
			surface.draw(canvas);
		}
		canvas.restore();

		drawRemoteCursor(canvas);
	}

	// perform mapping on the touch event's coordinates according to the current scaling
	private MotionEvent mapTouchEvent(MotionEvent event)
	{
		MotionEvent mappedEvent = MotionEvent.obtain(event);
		float[] coordinates = { mappedEvent.getX(), mappedEvent.getY() };
		invScaleMatrix.mapPoints(coordinates);
		mappedEvent.setLocation(coordinates[0], coordinates[1]);
		return mappedEvent;
	}

	// perform mapping on the double touch event's coordinates according to the current scaling
	private MotionEvent mapDoubleTouchEvent(MotionEvent event)
	{
		MotionEvent mappedEvent = MotionEvent.obtain(event);
		float[] coordinates = { (mappedEvent.getX(0) + mappedEvent.getX(1)) / 2,
			                    (mappedEvent.getY(0) + mappedEvent.getY(1)) / 2 };
		invScaleMatrix.mapPoints(coordinates);
		mappedEvent.setLocation(coordinates[0], coordinates[1]);
		return mappedEvent;
	}

	@Override public boolean onTouchEvent(MotionEvent event)
	{
		// Physical mouse events: buttons come via onGenericMotionEvent, hover moves via
		// onHoverEvent; only a held-button drag shows up here.
		if (event.isFromSource(InputDevice.SOURCE_MOUSE))
		{
			if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getButtonState() != 0)
			{
				MotionEvent mapped = mapTouchEvent(event);
				sessionViewListener.onSessionViewMouseMove((int)mapped.getX(), (int)mapped.getY());
				mapped.recycle();
			}
			return true;
		}

		return dispatchFinger(event);
	}

	/**
	 * 画面外那圈黑边也要能推光标：黑边属于 ScrollView，事件到不了 SessionView，
	 * 由 ScrollView 的 OnTouchListener 转进来。
	 *
	 * 触摸板只吃相对位移，坐标系差一个常量偏移无所谓；直控模式点黑边没有任何意义，不接。
	 */
	public boolean handleExternalTouch(MotionEvent event)
	{
		if (pointerMode != MODE_MOUSE)
			return false;
		return dispatchFinger(event);
	}

	private boolean dispatchFinger(MotionEvent event)
	{
		int action = event.getActionMasked();
		boolean multi = event.getPointerCount() >= 2 || swallowUntilUp;

		if (action == MotionEvent.ACTION_POINTER_DOWN)
		{
			maxPointers = Math.max(maxPointers, event.getPointerCount());
			multiAnchorX = averageX(event);
			multiAnchorY = averageY(event);
			multiTravel = 0f;
			// 第二根手指落下就不再是"按住/接着拖"了，计时和按住的键都得收掉
			cancelSingleFinger();
			swallowUntilUp = true;
		}
		else if (multi && action == MotionEvent.ACTION_MOVE)
		{
			multiTravel = Math.max(multiTravel, (float)Math.hypot(
			    averageX(event) - multiAnchorX, averageY(event) - multiAnchorY));
		}

		// 双指检测器全程都要看到事件：它自己区分滚动与捏合，且从第一次按下开始跟踪
		doubleGestureDetector.onTouchEvent(event);

		if (!multi)
		{
			if (action == MotionEvent.ACTION_DOWN)
				startSingleFinger(event);
			else
				trackSingleFinger(event);
		}

		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
		{
			// 三指轻点 = 中键：检测器只认两指，这一段得自己判
			if (maxPointers >= 3 && multiTravel <= clickSlop)
				clickAtCursor(BUTTON_MIDDLE);
			swallowUntilUp = false;
			maxPointers = 0;
			multiTravel = 0f;
			// 松手时**不**再补一次动画：拖动期间已经跟到位了，每松一次手都滑一下才是顿挫
		}
		return true;
	}

	private static float averageX(MotionEvent event)
	{
		float sum = 0f;
		for (int i = 0; i < event.getPointerCount(); i++)
			sum += event.getX(i);
		return sum / event.getPointerCount();
	}

	private static float averageY(MotionEvent event)
	{
		float sum = 0f;
		for (int i = 0; i < event.getPointerCount(); i++)
			sum += event.getY(i);
		return sum / event.getPointerCount();
	}

	@Override protected void onAttachedToWindow()
	{
		super.onAttachedToWindow();
		ViewParent parent = getParent();
		while (parent != null && !(parent instanceof ScrollView2D))
			parent = parent.getParent();
		scrollView = (ScrollView2D)parent;
		applyModeToScroller();
	}

	// Handle all physical mouse buttons here; finger taps come via onSingleTapUp.
	@Override public boolean onGenericMotionEvent(MotionEvent event)
	{
		final boolean isPointer = event.isFromSource(InputDevice.SOURCE_CLASS_POINTER);
		if (!isPointer)
			return false;

		final boolean isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE);
		int action = event.getActionMasked();

		if (isMouse && (action == MotionEvent.ACTION_BUTTON_PRESS ||
		                action == MotionEvent.ACTION_BUTTON_RELEASE))
		{
			boolean down = action == MotionEvent.ACTION_BUTTON_PRESS;
			MotionEvent mapped = mapTouchEvent(event);
			int x = (int)mapped.getX();
			int y = (int)mapped.getY();
			mapped.recycle();

			switch (event.getActionButton())
			{
				case MotionEvent.BUTTON_PRIMARY:
					if (down)
						sessionViewListener.onSessionViewBeginTouch();
					sessionViewListener.onSessionViewLeftTouch(x, y, down);
					if (!down)
						sessionViewListener.onSessionViewEndTouch();
					return true;
				case MotionEvent.BUTTON_SECONDARY:
					if (down)
						sessionViewListener.onSessionViewBeginTouch();
					sessionViewListener.onSessionViewRightTouch(x, y, down);
					return true;
				case MotionEvent.BUTTON_TERTIARY:
					sessionViewListener.onSessionViewMiddleTouch(x, y, down);
					return true;
				default:
					return true; // consume unknown buttons silently
			}
		}

		if (action == MotionEvent.ACTION_SCROLL)
		{
			float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
			float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
			if (vScroll != 0)
				sessionViewListener.onSessionViewScroll(vScroll > 0);
			if (hScroll != 0)
				sessionViewListener.onSessionViewHScroll(hScroll > 0);
			return true;
		}

		return false;
	}

	public interface SessionViewListener
	{
		void onSessionViewBeginTouch();

		void onSessionViewEndTouch();

		void onSessionViewLeftTouch(int x, int y, boolean down);

		void onSessionViewMiddleTouch(int x, int y, boolean down);

		void onSessionViewRightTouch(int x, int y, boolean down);

		void onSessionViewMove(int x, int y);

		void onSessionViewMouseMove(int x, int y);

		void onSessionViewScroll(boolean down);

		void onSessionViewHScroll(boolean right);
	}

	public void setRemoteCursor(int[] pixels, int width, int height, int hotX, int hotY)
	{
		if (pixels == null || width == 0 || height == 0)
		{
			// 服务端要隐藏光标（全屏视频一类）。触屏客户端一旦看不见指针就没法操作了，
			// 所以退回到默认箭头继续画，只把物理鼠标的系统图标关掉，避免和画面里的重影。
			cursorPixels = null;
			scaledCursorBitmap = null;
			setPointerIcon(PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL));
			cursorIsDefault = true;
			invalidate();
			return;
		}
		cursorPixels = pixels;
		cursorWidth = width;
		cursorHeight = height;
		cursorHotX = hotX;
		cursorHotY = hotY;
		cursorIsDefault = false;
		applyScaledCursor();
	}

	/** 远端坐标下的光标位置；由触摸/移动事件更新。 */
	public void setRemoteCursorPosition(int x, int y)
	{
		if (x == cursorRemoteX && y == cursorRemoteY)
			return;
		cursorRemoteX = x;
		cursorRemoteY = y;
		invalidate();
		// 边滑边走：光标进到边缘带里，画面按比例跟过去（增益小于 1，比光标慢）
		followCursorDuringDrag();
	}

	public int getRemoteCursorX()
	{
		return cursorRemoteX;
	}

	public int getRemoteCursorY()
	{
		return cursorRemoteY;
	}

	/**
	 * 让光标回到边缘带内所需的滚动量，0 表示已经在带子里。
	 *
	 * 差值是「现在的位置 − 想要的位置」，`scrollBy` 吃的正是这个数。写成 `想要 − 现在`
	 * 方向就反了：往左推光标，画面跟着往左跑，看上去像鼠标在倒退。
	 */
	private static int axisNeeded(float pos, float edge, int view)
	{
		if (pos < edge)
			return Math.round(pos - edge);
		if (pos > view - edge)
			return Math.round(pos - (view - edge));
		return 0;
	}

	/** 视图可用尺寸；画面装得下（没超视口）时返回 null——没有可移的余地。 */
	private int[] scrollableViewport()
	{
		if (scrollView == null || width <= 0 || height <= 0)
			return null;
		int viewW = scrollView.getWidth() - scrollView.getPaddingLeft() - scrollView.getPaddingRight();
		int viewH = scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
		if (viewW <= 0 || viewH <= 0)
			return null;
		// 判据是「画面比视口大」而不是「用户放大了」：换了桌面分辨率之后即使 1× 也会超出视口。
		// 内容尺寸按位图 × 缩放算，不读 getWidth()——刚 setZoom 后布局还没跑，读到的会是旧值。
		int contentW = Math.round(width * scaleFactor);
		int contentH = Math.round(height * scaleFactor);
		if (contentW <= viewW && contentH <= viewH)
			return null;
		return new int[] {viewW, viewH};
	}

	/**
	 * 拖动过程中的跟随，照 1.x 的 `keepCursorVisible`：光标一进边缘带就把画面推到位，
	 * 光标随即停在带子边上，画面继续跟着手指滑——边滑边走，不是松手才跳。
	 *
	 * 边缘带用 1.x 的 15% / 64dp。增益见 {@link #FOLLOW_GAIN}（1.0 = 原样，
	 * 想要更缓就调小，代价是光标会更快靠近边线）。
	 */
	private void followCursorDuringDrag()
	{
		int[] viewport = scrollableViewport();
		if (viewport == null || cursorRemoteX < 0)
			return;
		int viewW = viewport[0];
		int viewH = viewport[1];

		int contentW = Math.round(width * scaleFactor);
		int contentH = Math.round(height * scaleFactor);
		float vx = cursorRemoteX * scaleFactor - scrollView.getScrollX();
		float vy = cursorRemoteY * scaleFactor - scrollView.getScrollY();
		float edgeX = Math.min(viewW * 0.15f, 64 * density);
		float edgeY = Math.min(viewH * 0.15f, 64 * density);

		int needX = contentW > viewW ? axisNeeded(vx, edgeX, viewW) : 0;
		int needY = contentH > viewH ? axisNeeded(vy, edgeY, viewH) : 0;
		int dx = Math.round(needX * FOLLOW_GAIN);
		int dy = Math.round(needY * FOLLOW_GAIN);
		if (dx != 0 || dy != 0)
			scrollView.scrollBy(dx, dy);
	}

	/**
	 * 手势结束 / 捏合结束 / 换分辨率 / 转屏之后，把视角重新对准光标：
	 * 整只在视野外就拉回正中，只是贴出边缘就推到留一小条。
	 */
	private void focusOnCursor()
	{
		int[] viewport = scrollableViewport();
		if (viewport == null || cursorRemoteX < 0)
			return;
		int viewW = viewport[0];
		int viewH = viewport[1];

		float vx = cursorRemoteX * scaleFactor - scrollView.getScrollX();
		float vy = cursorRemoteY * scaleFactor - scrollView.getScrollY();
		// 回到视野内时贴边留一小条（8% 或 32dp，取小的那个），够看清箭头，又不多吃掉视野
		float edgeX = Math.min(viewW * 0.08f, 32 * density);
		float edgeY = Math.min(viewH * 0.08f, 32 * density);

		int dx;
		int dy;
		if (vx < 0 || vx > viewW)
			dx = Math.round(vx - viewW / 2f);
		else
			dx = axisNeeded(vx, edgeX, viewW);
		if (vy < 0 || vy > viewH)
			dy = Math.round(vy - viewH / 2f);
		else
			dy = axisNeeded(vy, edgeY, viewH);

		if (dx != 0 || dy != 0)
			scrollView.smoothScrollBy(dx, dy);
	}

	/**
	 * 松手 / 捏合结束 / 换分辨率 / 转屏之后，把视角重新对准光标。
	 *
	 * 延到下一帧再做：可滚动的范围是按内容尺寸算的，布局还没跑完就被旧范围夹住，
	 * 表现就是"放大后鼠标找不到了"。
	 *
	 * 滚动量一律是「现在位置 − 想要位置」，写成「想要位置 − 现在位置」方向就反了：
	 * 往左推光标，画面跟着往左跑，看上去像鼠标在倒退。
	 */
	public void revealCursorIfOffscreen()
	{
		post(this::focusOnCursor);
	}

	// ---------------------------------------------------------------- 指针模式

	public int getPointerMode()
	{
		return pointerMode;
	}

	public void setPointerMode(int mode)
	{
		mode = mode == MODE_MOUSE ? MODE_MOUSE : MODE_TOUCH;
		if (mode == pointerMode)
			return;
		// 换模式前把当前按下的状态结掉：可能正按着左键，留着不发的话远端认为按键一直没抬
		cancelSingleFinger();
		pointerMode = mode;
		applyModeToScroller();
	}

	private void applyModeToScroller()
	{
		// 触摸板模式下单指是"推光标"，不能被本地平移抢走；直控模式仍靠平移看放大的画面
		if (scrollView != null)
			scrollView.setScrollEnabled(pointerMode == MODE_TOUCH);
	}

	private void setLocalScrollEnabled(boolean enabled)
	{
		if (scrollView != null)
			scrollView.setScrollEnabled(enabled && pointerMode == MODE_TOUCH);
	}

	private void drawRemoteCursor(Canvas canvas)
	{
		if (cursorRemoteX < 0 || cursorRemoteY < 0)
			return;

		final float cs = cursorScale();
		if (cursorIsDefault || scaledCursorBitmap == null)
		{
			if (fallbackCursor == null)
				fallbackCursor = ContextCompat.getDrawable(getContext(), R.drawable.ic_cursor);
			if (fallbackCursor == null)
				return;
			int size = Math.round(32 * cs);
			int left = Math.round(cursorRemoteX * scaleFactor) - size / 4;
			int top = Math.round(cursorRemoteY * scaleFactor) - size / 12;
			fallbackCursor.setBounds(left, top, left + size, top + size);
			fallbackCursor.draw(canvas);
			return;
		}

		int left = Math.round((cursorRemoteX - cursorHotX) * scaleFactor);
		int top = Math.round((cursorRemoteY - cursorHotY) * scaleFactor);
		canvas.drawBitmap(scaledCursorBitmap, left, top, cursorPaint);
	}

	/**
	 * 光标的绘制倍率。画面缩到 0.56× 时光标若跟着缩只剩 18px，在手机上几乎找不到，
	 * 所以光标最小按原始尺寸画，只有画面被放大时才跟着放大。
	 */
	private float cursorScale()
	{
		return Math.max(scaleFactor, 1.0f);
	}

	private void applyScaledCursor()
	{
		float cs = cursorScale();
		int scaledWidth = Math.max(1, (int)(cursorWidth * cs));
		int scaledHeight = Math.max(1, (int)(cursorHeight * cs));
		Bitmap bm =
		    Bitmap.createBitmap(cursorPixels, cursorWidth, cursorHeight, Bitmap.Config.ARGB_8888);
		scaledCursorBitmap = Bitmap.createScaledBitmap(bm, scaledWidth, scaledHeight, true);
		invalidate();
	}

	/** 服务端要求「用系统默认箭头」：画在画面里的箭头，本地光标仍然隐藏。 */
	public void setDefaultCursor()
	{
		setPointerIcon(PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL));
		cursorIsDefault = true;
		scaledCursorBitmap = null;
		invalidate();
	}

	// ---------------------------------------------------------------- 单指手势
	//
	// 两种模式共用一套状态机骨架，区别只在「手指位移换成什么」：
	//   触控：点哪儿打哪儿；滑动=滚轮（画面能本地平移时让给 ScrollView2D）；按住=右键；
	//         按住后再动=拖拽。
	//   鼠标：整块画面当触摸板，相对位移推远端光标（带加速）；轻点=左键；
	//         轻点后按住=tap-and-a-half 直接进拖拽；按住 600 ms 松手=右键。

	private void startSingleFinger(MotionEvent e)
	{
		gesture = G_NONE;
		holdArmed = false;
		dragHeld = false;
		travel = 0f;
		scrollAccum = 0f;
		flingVelocity = 0f;
		flingAccum = 0f;
		removeCallbacks(flingStep);
		downX = lastX = e.getX();
		downY = lastY = e.getY();
		lastMoveAt = e.getEventTime();

		if (pointerMode == MODE_MOUSE)
		{
			// 刚点过一下又按下来：这是"接着拖"的候选，按住不动也算数
			tapArmed = SystemClock.uptimeMillis() - lastTapAt <= dragArmMs;
			postDelayed(tapArmed ? dragArm : longPress, tapArmed ? ARM_HOLD_MS : LONG_PRESS_MS);
		}
		else
		{
			tapArmed = false;
			postDelayed(touchHold, TOUCH_HOLD_MS);
		}
	}

	private void trackSingleFinger(MotionEvent e)
	{
		int action = e.getActionMasked();
		if (action == MotionEvent.ACTION_POINTER_UP)
			return;

		float dx = e.getX() - lastX;
		float dy = e.getY() - lastY;
		lastX = e.getX();
		lastY = e.getY();
		travel += Math.abs(dx) + Math.abs(dy);

		if (action == MotionEvent.ACTION_MOVE)
		{
			if (pointerMode == MODE_MOUSE)
				moveTrackpad(dx, dy, e.getEventTime());
			else
				moveDirect(dy, e);
			return;
		}
		endSingleFinger(action == MotionEvent.ACTION_UP);
	}

	private void moveDirect(float dy, MotionEvent e)
	{
		if (gesture == G_NONE)
		{
			if (holdArmed)
			{
				// 长按已经到位：这时候再动就是拖拽，静止不动松手才是右键
				float moved = (float)Math.hypot(e.getX() - downX, e.getY() - downY);
				if (moved > dragSlop)
					startDrag();
			}
			else if (travel > scrollSlop)
			{
				removeCallbacks(touchHold);
				gesture = G_SCROLL;
			}
		}
		if (gesture == G_SCROLL)
			applyTouchScroll(dy, e.getEventTime());
		else if (gesture == G_DRAG)
			dragMoveTo(e.getX(), e.getY());
	}

	private void endSingleFinger(boolean up)
	{
		removeCallbacks(touchHold);
		removeCallbacks(longPress);
		removeCallbacks(dragArm);
		tapArmed = false;

		if (!up)
		{
			releaseDrag();
			holdArmed = false;
			return;
		}

		if (pointerMode == MODE_MOUSE)
		{
			if (dragHeld)
			{
				// 拖拽结束：放开左键，这一次不算点击
				releaseDrag();
			}
			else if (holdArmed)
				clickAtCursor(BUTTON_RIGHT);
			else if (travel > clickSlop)
			{
				// 只是推了光标，不算点击
			}
			else if (resetZoomOnDoubleTap())
			{
				// 已经放大了：这一下算"回到适配"，不再发给远端
			}
			else
			{
				clickAtCursor(BUTTON_LEFT);
				// 记下这次轻点：紧接着再按住就是要拖，也顺便让双击正常计时
				lastTapAt = SystemClock.uptimeMillis();
			}
			holdArmed = false;
			return;
		}

		if (gesture == G_SCROLL)
			startFling();
		else if (gesture == G_DRAG)
			releaseDrag();
		else if (holdArmed)
			clickAtFinger(BUTTON_RIGHT);
		else if (travel > clickSlop)
		{
			// 滑了一下但没到滚动的门槛：什么都不发，免得点图标时误触
		}
		else if (resetZoomOnDoubleTap())
		{
		}
		else
		{
			clickAtFinger(BUTTON_LEFT);
			lastTapAt = SystemClock.uptimeMillis();
		}

		gesture = G_NONE;
		holdArmed = false;
		// 无障碍服务靠 performClick 感知「这里可以点」；触控被我们自己吃掉了，
		// 不补这一句的话 TalkBack 用户会觉得这块画面是死的
		performClick();
	}

	/**
	 * 放大状态下的双击 = 回到适配。
	 *
	 * 没放大时不拦双击：桌面图标、文件列表全靠双击打开，本地合成掉就点不动了。
	 * 所以"双击放大"这一半交给捏合，双击只负责把视角收回来。
	 */
	private boolean resetZoomOnDoubleTap()
	{
		if (userZoom <= MIN_USER_ZOOM + SCALE_FACTOR_DELTA)
			return false;
		if (SystemClock.uptimeMillis() - lastTapAt > dragArmMs)
			return false;
		lastTapAt = 0;
		setUserZoom(MIN_USER_ZOOM);
		revealCursorIfOffscreen();
		return true;
	}

	/** 放弃当前单指手势：计时器、甩动和可能按下的左键都收干净。 */
	private void cancelSingleFinger()
	{
		removeCallbacks(touchHold);
		removeCallbacks(longPress);
		removeCallbacks(dragArm);
		removeCallbacks(flingStep);
		releaseDrag();
		gesture = G_NONE;
		holdArmed = false;
		tapArmed = false;
		flingVelocity = 0f;
		flingAccum = 0f;
	}

	private final Runnable touchHold = new Runnable() {
		@Override public void run()
		{
			if (gesture != G_NONE || holdArmed)
				return;
			holdArmed = true;
			haptic();
		}
	};

	private final Runnable longPress = new Runnable() {
		@Override public void run()
		{
			if (dragHeld || holdArmed || width <= 0)
				return;
			// 按够时长只是**标记**下来：松手才算右键，这期间手指一动就取消——
			// 这样"点完紧接着挪光标"永远不会变成右键
			holdArmed = true;
			haptic();
		}
	};

	private final Runnable dragArm = new Runnable() {
		@Override public void run()
		{
			if (!tapArmed || dragHeld)
				return;
			commitDrag();
		}
	};

	private final Runnable flingStep = new Runnable() {
		@Override public void run()
		{
			if (Math.abs(flingVelocity) < FLING_MIN)
			{
				flingVelocity = 0f;
				flingAccum = 0f;
				return;
			}
			flingAccum += flingVelocity * FLING_INTERVAL_MS;
			int notches = (int)(flingAccum / TOUCH_NOTCH_PX);
			if (notches != 0)
			{
				flingAccum -= notches * TOUCH_NOTCH_PX;
				wheel(notches, true);
			}
			flingVelocity *= FLING_DECAY;
			postDelayed(this, FLING_INTERVAL_MS);
		}
	};

	/** 进入拖拽：按下左键并震一下；两条入口（按住、移动）都收敛到这里。 */
	private void commitDrag()
	{
		removeCallbacks(dragArm);
		if (!tapArmed || dragHeld)
			return;
		if (cursorRemoteX < 0 || cursorRemoteY < 0)
		{
			cursorRemoteX = width / 2;
			cursorRemoteY = height / 2;
			invalidate();
		}
		tapArmed = false;
		dragHeld = true;
		gesture = G_DRAG;
		setLocalScrollEnabled(false);
		press(BUTTON_LEFT, cursorRemoteX, cursorRemoteY, true);
		haptic();
	}

	private void startDrag()
	{
		gesture = G_DRAG;
		setLocalScrollEnabled(false);
		int[] remote = remoteAt(downX, downY);
		setRemoteCursorPosition(remote[0], remote[1]);
		dragHeld = true;
		press(BUTTON_LEFT, remote[0], remote[1], true);
	}

	private void dragMoveTo(float viewX, float viewY)
	{
		int[] remote = remoteAt(viewX, viewY);
		setRemoteCursorPosition(remote[0], remote[1]);
		// 拖动中只发"移动"：按下状态在远端是持续的，重复发按下会变成连点
		sessionViewListener.onSessionViewMove(remote[0], remote[1]);
	}

	private void releaseDrag()
	{
		if (dragHeld && cursorRemoteX >= 0)
			press(BUTTON_LEFT, cursorRemoteX, cursorRemoteY, false);
		dragHeld = false;
		if (gesture == G_DRAG)
			gesture = G_NONE;
		setLocalScrollEnabled(true);
	}

	/** 触摸板模式：手指位移 → 远端光标位移，带指针加速。 */
	private void moveTrackpad(float dx, float dy, long when)
	{
		if (cursorRemoteX < 0 || cursorRemoteY < 0)
		{
			// 以画面中心为起点比"沿用上一次的光标"更好解释，用户知道往哪儿划
			cursorRemoteX = width / 2;
			cursorRemoteY = height / 2;
		}
		if (tapArmed && !dragHeld && travel > dragSlop)
			// 轻点之后手指一动就开始拖，不等到拖出一段距离
			commitDrag();
		else if (holdArmed && !dragHeld && travel > clickSlop)
			// 按住之后又挪了：那是在移动光标，不该再发右键
			holdArmed = false;
		else if (!holdArmed && !dragHeld && travel > clickSlop)
			removeCallbacks(longPress);

		long dt = Math.max(1L, when - lastMoveAt);
		lastMoveAt = when;
		float speed = (float)Math.hypot(dx, dy) / dt;
		float factor = accelFactor(speed);
		int nx = clamp(Math.round(cursorRemoteX + dx / Math.max(scaleFactor, 0.01f) * factor), 0,
		               width - 1);
		int ny = clamp(Math.round(cursorRemoteY + dy / Math.max(scaleFactor, 0.01f) * factor), 0,
		               height - 1);
		setRemoteCursorPosition(nx, ny);
		sessionViewListener.onSessionViewMove(nx, ny);
	}

	/**
	 * 指针加速曲线：0.75×（慢速）→ 1.5×（1 px/ms）→ 2.25×（4 px/ms 封顶）。
	 * 用 sqrt 而不是线性，慢速段不会太迟钝，快速段也不会一下甩飞——这是触摸板最影响手感的一条。
	 */
	private static float accelFactor(float speed)
	{
		float s = Math.max(0f, Math.min(4f, speed));
		return 0.75f + 0.75f * (float)Math.sqrt(s);
	}

	/** 单指滑动：位移按远端像素累计，攒够一格发一次滚轮。 */
	private void applyTouchScroll(float dy, long eventTime)
	{
		float remoteDy = dy / Math.max(scaleFactor, 0.01f);
		scrollAccum += remoteDy;
		int notches = (int)(scrollAccum / TOUCH_NOTCH_PX);
		if (notches != 0)
		{
			scrollAccum -= notches * TOUCH_NOTCH_PX;
			wheel(notches, true);
		}
		long dt = Math.max(1L, eventTime - lastMoveAt);
		lastMoveAt = eventTime;
		float speed = remoteDy / dt;
		flingVelocity = Math.max(-4f, Math.min(4f, flingVelocity * 0.6f + speed * 0.4f));
	}

	private void startFling()
	{
		// 松手前已经停住的不算甩动：手指压着不动再抬起来，内容不该继续跑
		if (Math.abs(flingVelocity) < FLING_START ||
		    SystemClock.uptimeMillis() - lastMoveAt > 120)
		{
			flingVelocity = 0f;
			return;
		}
		removeCallbacks(flingStep);
		post(flingStep);
	}

	// ---------------------------------------------------------------- 发往远端的事件

	private void press(int button, int x, int y, boolean down)
	{
		switch (button)
		{
			case BUTTON_LEFT:
				sessionViewListener.onSessionViewLeftTouch(x, y, down);
				break;
			case BUTTON_RIGHT:
				sessionViewListener.onSessionViewRightTouch(x, y, down);
				break;
			case BUTTON_MIDDLE:
				sessionViewListener.onSessionViewMiddleTouch(x, y, down);
				break;
			default:
				break;
		}
	}

	private void click(int button, int x, int y)
	{
		press(button, x, y, true);
		press(button, x, y, false);
	}

	private void clickAtCursor(int button)
	{
		if (cursorRemoteX < 0 || cursorRemoteY < 0)
			return;
		click(button, cursorRemoteX, cursorRemoteY);
	}

	private void clickAtFinger(int button)
	{
		int[] remote = remoteAt(lastX, lastY);
		setRemoteCursorPosition(remote[0], remote[1]);
		click(button, remote[0], remote[1]);
	}

	/** 一次跨过几格就发几条：FreeRDP 的滚轮标志只表示一格。 */
	private void wheel(int notches, boolean vertical)
	{
		boolean direction = notches > 0;
		for (int i = 0; i < Math.abs(notches); i++)
		{
			if (vertical)
				sessionViewListener.onSessionViewScroll(direction);
			else
				sessionViewListener.onSessionViewHScroll(direction);
		}
	}

	/** 屏幕坐标 → 远端坐标，夹在画面内；绘制与触控共用同一套缩放参数。 */
	private int[] remoteAt(float viewX, float viewY)
	{
		float[] p = {viewX, viewY};
		invScaleMatrix.mapPoints(p);
		return new int[] {clamp(Math.round(p[0]), 0, Math.max(width - 1, 0)),
		                  clamp(Math.round(p[1]), 0, Math.max(height - 1, 0))};
	}

	private static int clamp(int value, int min, int max)
	{
		return value < min ? min : (value > max ? max : value);
	}

	private void haptic()
	{
		performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
	}

	private class SessionDoubleGestureListener
	    implements DoubleGestureDetector.OnDoubleGestureListener
	{
		private MotionEvent prevEvent = null;
		private float accumX;
		private float accumY;

		public boolean onDoubleTouchDown(MotionEvent e)
		{
			setLocalScrollEnabled(false);
			prevEvent = MotionEvent.obtain(e);
			accumX = 0f;
			accumY = 0f;
			return true;
		}

		public boolean onDoubleTouchUp(MotionEvent e)
		{
			if (prevEvent != null)
			{
				prevEvent.recycle();
				prevEvent = null;
			}
			setLocalScrollEnabled(true);
			return true;
		}

		/**
		 * 两指平移：横竖各攒各的格（30 dp 一格，比一屏一格细、又不至于每动一下都发一条）。
		 * 滚轮事件落在指针当前位置——Windows 是按指针所在窗口决定滚谁的。
		 */
		public boolean onDoubleTouchScroll(MotionEvent e1, MotionEvent e2)
		{
			accumX += e2.getX() - prevEvent.getX();
			accumY += e2.getY() - prevEvent.getY();
			boolean moved = false;
			int notchesX = (int)(accumX / twoFingerNotchPx);
			if (notchesX != 0)
			{
				accumX -= notchesX * twoFingerNotchPx;
				wheel(notchesX, false);
				moved = true;
			}
			int notchesY = (int)(accumY / twoFingerNotchPx);
			if (notchesY != 0)
			{
				accumY -= notchesY * twoFingerNotchPx;
				wheel(notchesY, true);
				moved = true;
			}
			if (moved)
			{
				MotionEvent mapped = mapDoubleTouchEvent(e2);
				setRemoteCursorPosition((int)mapped.getX(), (int)mapped.getY());
				mapped.recycle();
				prevEvent.recycle();
				prevEvent = MotionEvent.obtain(e2);
			}
			return true;
		}

		public boolean onDoubleTouchSingleTap(MotionEvent e)
		{
			// 两指轻点 = 右键（触摸板的常规映射）
			MotionEvent mappedEvent = mapDoubleTouchEvent(e);
			int x = (int)mappedEvent.getX();
			int y = (int)mappedEvent.getY();
			mappedEvent.recycle();
			setRemoteCursorPosition(x, y);
			click(BUTTON_RIGHT, x, y);
			return true;
		}
	}

	@Override public InputConnection onCreateInputConnection(EditorInfo outAttrs)
	{
		outAttrs.actionLabel = null;
		outAttrs.inputType = InputType.TYPE_NULL;
		outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI |
		                      EditorInfo.IME_FLAG_NO_FULLSCREEN;
		return new BaseInputConnection(this, false);
	}
}
