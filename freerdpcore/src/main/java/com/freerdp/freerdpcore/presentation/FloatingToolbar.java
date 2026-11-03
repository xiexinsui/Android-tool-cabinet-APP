/*
   Floating toolbar for RDP session controls

   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.app.Activity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.freerdp.freerdpcore.R;

/**
 * 会话上那排半透明按钮。
 *
 * 上游这里是一个可拖动的折叠工具条；工具箱改成固定一排，覆盖在远端画面上、不占布局高度。
 * 文字一律写「点下去会切到什么」，和横屏/全屏同一套读法。
 *
 * **长按 500 ms 进入移动**，之后手指带着整条走，松手吸附到最近的一侧；短按仍然是点击。
 * 收起后只剩一枚半透明把手停在原处，所以把手同样可以长按拖走。
 */
public class FloatingToolbar
{
	public interface Listener
	{
		void onPointerMode();

		void onResolution();

		void onKeyboard();

		void onInfo();

		void onOrient();

		void onFullscreen();

		void onDisconnect();
	}

	private final View container;
	private final TextView toggleButton;
	private final TextView orientButton;
	private final TextView fullscreenButton;
	private final TextView pointerButton;
	private final View[] extras;
	private final int baseMargin;
	private final int touchSlop;
	private final long longPressTimeout;

	private boolean fullscreen = false;
	private boolean collapsed = false;
	/** 手动挪过之后 setInsets 只更新边界，不再改 margin——否则转屏一布局又被打回原位。 */
	private boolean manualPosition = false;
	private int insetLeft;
	private int insetTop;
	private int insetRight;

	public FloatingToolbar(Activity activity, Listener listener)
	{
		container = activity.findViewById(R.id.session_toolbar);
		bind(activity, R.id.session_toolbar_pointer, listener::onPointerMode);
		bind(activity, R.id.session_toolbar_resolution, listener::onResolution);
		bind(activity, R.id.session_toolbar_keyboard, listener::onKeyboard);
		bind(activity, R.id.session_toolbar_info, listener::onInfo);
		bind(activity, R.id.session_toolbar_disconnect, listener::onDisconnect);
		bind(activity, R.id.session_toolbar_orient, listener::onOrient);
		bind(activity, R.id.session_toolbar_fullscreen, listener::onFullscreen);

		toggleButton = activity.findViewById(R.id.session_toolbar_toggle);
		extras = new View[] {
			activity.findViewById(R.id.session_toolbar_pointer),
			activity.findViewById(R.id.session_toolbar_resolution),
			activity.findViewById(R.id.session_toolbar_keyboard),
			activity.findViewById(R.id.session_toolbar_info),
			activity.findViewById(R.id.session_toolbar_orient),
			activity.findViewById(R.id.session_toolbar_fullscreen),
			activity.findViewById(R.id.session_toolbar_disconnect),
		};
		orientButton = activity.findViewById(R.id.session_toolbar_orient);
		fullscreenButton = activity.findViewById(R.id.session_toolbar_fullscreen);
		pointerButton = activity.findViewById(R.id.session_toolbar_pointer);

		baseMargin = Math.round(8 * activity.getResources().getDisplayMetrics().density);
		touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
		longPressTimeout = ViewConfiguration.getLongPressTimeout();

		if (toggleButton != null)
			toggleButton.setOnClickListener(v -> setCollapsed(!collapsed));
		attachDrag();
	}

	private static void bind(Activity activity, int id, Runnable action)
	{
		View view = activity.findViewById(id);
		if (view != null)
			view.setOnClickListener(v -> action.run());
	}

	// ---------------------------------------------------------------- 长按拖动

	/**
	 * 每个按钮都挂同一套拖动监听：短按转发成点击，长按之后才带着整条走。
	 *
	 * 触摸事件必须自己吃掉——交给 HorizontalScrollView 之后，长按后的 MOVE 就收不到了。
	 * 代价是这排按钮不能横向滚动，但按钮就那么几个，横竖屏都放得下。
	 */
	private void attachDrag()
	{
		if (!(container instanceof ViewGroup))
			return;
		DragTouch drag = new DragTouch();
		ViewGroup group = (ViewGroup)container;
		for (int i = 0; i < group.getChildCount(); i++)
			attachRecursive(group.getChildAt(i), drag);
	}

	private static void attachRecursive(View view, DragTouch drag)
	{
		if (view instanceof ViewGroup)
		{
			ViewGroup group = (ViewGroup)view;
			for (int i = 0; i < group.getChildCount(); i++)
				attachRecursive(group.getChildAt(i), drag);
			return;
		}
		view.setOnTouchListener(drag);
	}

	private final class DragTouch implements View.OnTouchListener
	{
		private float downRawX;
		private float downRawY;
		private int startLeft;
		private int startTop;
		private boolean armed;
		private boolean moved;

		private final Runnable arm = new Runnable() {
			@Override public void run()
			{
				armed = true;
				container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			}
		};

		@Override public boolean onTouch(View view, MotionEvent event)
		{
			switch (event.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN:
					downRawX = event.getRawX();
					downRawY = event.getRawY();
					startLeft = container.getLeft();
					startTop = container.getTop();
					armed = false;
					moved = false;
					// 这排按钮在 HorizontalScrollView 里：不禁掉拦截的话，长按刚到手一动，
					// 父级就把手势抢走并丢给我们一个 CANCEL，拖动永远进不去
					if (view.getParent() != null)
						view.getParent().requestDisallowInterceptTouchEvent(true);
					container.removeCallbacks(arm);
					container.postDelayed(arm, longPressTimeout);
					return true;

				case MotionEvent.ACTION_MOVE:
					float dx = event.getRawX() - downRawX;
					float dy = event.getRawY() - downRawY;
					if (!armed)
					{
						// 长按还没到就动了：那是点按/滑动，不进移动模式
						if (Math.hypot(dx, dy) > touchSlop)
						{
							moved = true;
							container.removeCallbacks(arm);
						}
						return true;
					}
					container.setTranslationX(clampTransX(Math.round(startLeft + dx)));
					container.setTranslationY(clampTransY(Math.round(startTop + dy)));
					return true;

				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if (view.getParent() != null)
						view.getParent().requestDisallowInterceptTouchEvent(false);
					container.removeCallbacks(arm);
					if (armed)
					{
						armed = false;
						snapToNearestSide(event.getActionMasked() == MotionEvent.ACTION_UP);
					}
					else if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP)
						view.performClick();
					return true;

				default:
					return false;
			}
		}
	}

	private int parentWidth()
	{
		ViewGroup parent = (ViewGroup)container.getParent();
		return parent != null ? parent.getWidth() : 0;
	}

	private int parentHeight()
	{
		ViewGroup parent = (ViewGroup)container.getParent();
		return parent != null ? parent.getHeight() : 0;
	}

	/** 拖动时不许拖出屏幕：左右各留 inset + 8dp，上下同理。 */
	private float clampTransX(int targetLeft)
	{
		int min = insetLeft + baseMargin;
		int max = parentWidth() - container.getWidth() - insetRight - baseMargin;
		int clamped = Math.max(min, Math.min(targetLeft, Math.max(min, max)));
		return clamped - container.getLeft();
	}

	private float clampTransY(int targetTop)
	{
		int min = insetTop + baseMargin;
		int max = parentHeight() - container.getHeight() - baseMargin;
		int clamped = Math.max(min, Math.min(targetTop, Math.max(min, max)));
		return clamped - container.getTop();
	}

	/**
	 * 松手吸附：横向贴到最近的一侧，纵向留在原地。
	 *
	 * 落定时把位移写成 margin、并清掉对齐规则——一直挂着 translation 的话，下次布局
	 * （转屏、收起切换）会按旧规则重算位置，看上去就是自己跳回去了。
	 */
	private void snapToNearestSide(boolean animate)
	{
		int width = parentWidth();
		int left = Math.round(container.getLeft() + container.getTranslationX());
		int top = Math.round(container.getTop() + container.getTranslationY());
		if (width > 0)
		{
			int leftSnap = insetLeft + baseMargin;
			int rightSnap = width - container.getWidth() - insetRight - baseMargin;
			left = (left + container.getWidth() / 2 < width / 2) ? leftSnap : rightSnap;
			top = Math.max(insetTop + baseMargin,
			               Math.min(top, parentHeight() - container.getHeight() - baseMargin));
		}

		final int targetLeft = left;
		final int targetTop = top;
		float targetTransX = targetLeft - container.getLeft();
		float targetTransY = targetTop - container.getTop();
		boolean closeEnough = Math.abs(targetTransX - container.getTranslationX()) < 1 &&
		                      Math.abs(targetTransY - container.getTranslationY()) < 1;
		if (!animate || closeEnough)
		{
			commitPosition(targetLeft, targetTop);
			return;
		}
		container.animate()
		    .translationX(targetTransX)
		    .translationY(targetTransY)
		    .setDuration(140)
		    .withEndAction(() -> commitPosition(targetLeft, targetTop))
		    .start();
	}

	private void commitPosition(int left, int top)
	{
		if (!(container.getLayoutParams() instanceof RelativeLayout.LayoutParams))
			return;
		RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)container.getLayoutParams();
		if (!manualPosition)
		{
			// 第一次手动落位：把对齐规则全摘掉，之后只认 margin
			lp.removeRule(RelativeLayout.ALIGN_PARENT_START);
			lp.removeRule(RelativeLayout.ALIGN_PARENT_END);
			lp.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
			lp.removeRule(RelativeLayout.CENTER_VERTICAL);
		}
		// start/left 要一起写：只写 leftMargin 时 RelativeLayout 仍会取之前设过的 start 边距
		// （实测吸附后位置停在 0），反过来只写 start 在某些布局方向下又读不到 left。
		lp.setMarginStart(left);
		lp.leftMargin = left;
		lp.setMarginEnd(0);
		lp.topMargin = top;
		container.setLayoutParams(lp);
		container.setTranslationX(0);
		container.setTranslationY(0);
		manualPosition = true;
	}

	// ---------------------------------------------------------------- 状态与文案

	/** 避开状态栏/刘海。用户手动挪过之后就不再自动摆位，只更新可拖动的边界。 */
	public void setInsets(int left, int top, int right, int bottom)
	{
		insetLeft = left;
		insetTop = top;
		insetRight = right;
		if (!(container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))
			return;
		ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)container.getLayoutParams();
		if (manualPosition)
		{
			// 转屏后屏幕尺寸变了：把上次拖到的位置夹回可见范围，别让它跑到屏幕外
			int width = parentWidth();
			int height = parentHeight();
			if (width <= 0 || height <= 0)
				return;
			int minLeft = insetLeft + baseMargin;
			int minTop = insetTop + baseMargin;
			int maxLeft = Math.max(minLeft, width - container.getWidth() - insetRight - baseMargin);
			int maxTop = Math.max(minTop, height - container.getHeight() - baseMargin);
			lp.leftMargin = Math.max(minLeft, Math.min(lp.leftMargin, maxLeft));
			lp.topMargin = Math.max(minTop, Math.min(lp.topMargin, maxTop));
			container.setLayoutParams(lp);
			return;
		}
		lp.setMarginStart(baseMargin + left);
		lp.topMargin = baseMargin + top;
		lp.setMarginEnd(baseMargin + right);
		lp.bottomMargin = baseMargin + bottom;
		container.setLayoutParams(lp);
	}

	public void setLandscape(boolean on)
	{
		if (orientButton != null)
			orientButton.setText(on ? R.string.session_toolbar_portrait
			                        : R.string.session_toolbar_landscape);
	}

	/** 按钮写的是「切过去之后是什么模式」，所以传当前模式、显示另一个。 */
	public void setPointerMode(int currentMode)
	{
		if (pointerButton != null)
			pointerButton.setText(currentMode == SessionView.MODE_MOUSE
			                          ? R.string.session_toolbar_pointer_touch
			                          : R.string.session_toolbar_pointer);
	}

	public void setFullscreen(boolean on)
	{
		fullscreen = on;
		if (fullscreenButton != null)
			fullscreenButton.setText(on ? R.string.session_toolbar_exit_fullscreen
			                            : R.string.session_toolbar_fullscreen);
	}

	public boolean isFullscreen()
	{
		return fullscreen;
	}

	/**
	 * 收起 = 只剩把手、更透明；展开 = 其余按钮回来。
	 *
	 * 没手动挪过时，收起走"贴右边缘垂直居中"的对齐规则；挪过之后就停在原处只改显隐，
	 * 否则用户辛苦挪到的位置一收一展就没了。
	 */
	public void setCollapsed(boolean on)
	{
		collapsed = on;
		for (View view : extras)
		{
			if (view != null)
				view.setVisibility(on ? View.GONE : View.VISIBLE);
		}
		container.setAlpha(on ? 0.55f : 1.0f);
		if (toggleButton != null)
		{
			toggleButton.setText(on ? R.string.session_toolbar_expand
			                        : R.string.session_toolbar_collapse);
			toggleButton.setContentDescription(container.getContext().getString(
			    on ? R.string.session_toolbar_expand_desc : R.string.session_toolbar_collapse_desc));
		}
		if (manualPosition)
		{
			// 手动位置下高度/宽度变了（只剩把手 ↔ 一排），重新贴回最近的一侧，
			// 否则收起后的把手会浮在半空、离边一截
			container.post(() -> snapToNearestSide(false));
			return;
		}
		if (!(container.getLayoutParams() instanceof RelativeLayout.LayoutParams))
			return;
		RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)container.getLayoutParams();
		lp.removeRule(on ? RelativeLayout.ALIGN_PARENT_START : RelativeLayout.ALIGN_PARENT_END);
		lp.removeRule(on ? RelativeLayout.ALIGN_PARENT_TOP : RelativeLayout.CENTER_VERTICAL);
		lp.addRule(on ? RelativeLayout.ALIGN_PARENT_END : RelativeLayout.ALIGN_PARENT_START);
		lp.addRule(on ? RelativeLayout.CENTER_VERTICAL : RelativeLayout.ALIGN_PARENT_TOP);
		container.setLayoutParams(lp);
	}

	public boolean isCollapsed()
	{
		return collapsed;
	}
}
