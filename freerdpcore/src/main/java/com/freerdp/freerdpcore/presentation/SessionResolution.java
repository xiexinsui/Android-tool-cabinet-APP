/*
   Session desktop resolution choice

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.freerdp.freerdpcore.R;

/**
 * 会话向服务端要的桌面分辨率，存在 freerdpcore 的默认偏好里：会话页和连接簿用的是同一个
 * 对话框，改哪儿都是同一份值。
 *
 * 分辨率是握手时谈定的，所以会话内改要走 display-control（MS-RDPEDISP）动态调整；
 * 服务端不认的时候只能重连一次。想要没有黑边靠的是让分辨率对上屏幕比例，而不是把画面拽变形。
 */
public final class SessionResolution
{
	/** 常见 Windows 桌面尺寸；默认第一个。 */
	public static final int[][] PRESETS = {
		{1920, 1080},
		{1600, 900},
		{1366, 768},
		{1280, 800},
		{1024, 768},
	};
	private static final String KEY_WIDTH = "session_resolution_width";
	private static final String KEY_HEIGHT = "session_resolution_height";
	/** 0×0 = 适配屏幕分辨率（按手机此刻的实际像素要桌面）。 */
	public static final int ADAPTIVE = 0;

	private SessionResolution()
	{
	}

	public static int[] load(Context context)
	{
		int width = ApplicationSettingsActivity.get(context).getInt(KEY_WIDTH, PRESETS[0][0]);
		int height = ApplicationSettingsActivity.get(context).getInt(KEY_HEIGHT, PRESETS[0][1]);
		return new int[] {width, height};
	}

	public static void save(Context context, int width, int height)
	{
		ApplicationSettingsActivity.get(context).edit().putInt(KEY_WIDTH, width)
		    .putInt(KEY_HEIGHT, height)
		    .apply();
	}

	public static boolean isAdaptive(int[] size)
	{
		return size[0] == ADAPTIVE || size[1] == ADAPTIVE;
	}

	/** 把「适配」换成手机当前方向的实际像素；宽度取偶数（协议要求）。 */
	public static int[] resolve(Context context, int[] size)
	{
		if (!isAdaptive(size))
			return new int[] {size[0], size[1]};
		int[] screen = screenSize(context);
		return new int[] {even(screen[0]), even(screen[1])};
	}

	/** 当前方向的实际屏幕像素（竖屏 1080×2340、横屏 2340×1080）。 */
	public static int[] screenSize(Context context)
	{
		WindowManager manager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
		DisplayMetrics metrics = new DisplayMetrics();
		manager.getDefaultDisplay().getRealMetrics(metrics);
		return new int[] {metrics.widthPixels, metrics.heightPixels};
	}

	public static String label(Context context, int[] size)
	{
		if (isAdaptive(size))
		{
			int[] screen = resolve(context, size);
			return context.getString(R.string.session_resolution_match_phone_format, screen[0],
			                         screen[1]);
		}
		return size[0] + "×" + size[1];
	}

	/**
	 * 分辨率对话框。
	 *
	 * @param onApply 会话页传非 null，拿到的是已解析成真实像素的尺寸，由调用方去发
	 *                display-control；连接簿传 null，只保存、下次连接生效
	 */
	public static void showDialog(Activity activity,
	                              @Nullable java.util.function.Consumer<int[]> onApply)
	{
		int[] current = load(activity);
		final boolean currentAdaptive = isAdaptive(current);
		int pad = (int)(20 * activity.getResources().getDisplayMetrics().density);

		LinearLayout body = new LinearLayout(activity);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPadding(pad, pad / 2, pad, 0);

		final CheckBox adaptive = new CheckBox(activity);
		adaptive.setText(R.string.session_resolution_adaptive);
		adaptive.setChecked(currentAdaptive);
		body.addView(adaptive);

		final RadioGroup sizes = new RadioGroup(activity);
		int selected = index(PRESETS, current);
		for (int i = 0; i < PRESETS.length; i++)
		{
			RadioButton item = new RadioButton(activity);
			item.setId(View.generateViewId());
			item.setText(label(activity, PRESETS[i]));
			item.setChecked(!currentAdaptive && i == Math.max(selected, 0));
			item.setTag(i);
			sizes.addView(item, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
			                                                ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		body.addView(sizes);

		sizes.setEnabled(!currentAdaptive);
		adaptive.setOnCheckedChangeListener((button, checked) -> sizes.setEnabled(!checked));

		new AlertDialog.Builder(activity)
		    .setTitle(R.string.session_resolution_title)
		    .setView(body)
		    .setPositiveButton(R.string.session_resolution_apply, (dialog, which) -> {
			    boolean on = adaptive.isChecked();
			    if (on)
				    save(activity, ADAPTIVE, ADAPTIVE);
			    else
			    {
				    int index = indexByTag(sizes, sizes.getCheckedRadioButtonId());
				    if (index >= 0)
					    save(activity, PRESETS[index][0], PRESETS[index][1]);
			    }
			    int[] applied = resolve(activity, load(activity));
			    if (onApply != null)
				    onApply.accept(applied);
			    else
				    toast(activity, activity.getString(R.string.session_resolution_saved_reconnect));
		    })
		    .setNegativeButton(android.R.string.cancel, null)
		    .show();
	}

	private static void toast(Context context, String text)
	{
		android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
	}

	public static int index(int[][] presets, int[] size)
	{
		for (int i = 0; i < presets.length; i++)
		{
			if (presets[i][0] == size[0] && presets[i][1] == size[1])
				return i;
		}
		return -1;
	}

	private static int indexByTag(RadioGroup group, int checkedId)
	{
		for (int i = 0; i < group.getChildCount(); i++)
		{
			View child = group.getChildAt(i);
			if (child.getId() == checkedId)
			{
				Object tag = child.getTag();
				return tag instanceof Integer ? (Integer)tag : -1;
			}
		}
		return -1;
	}

	private static int even(int value)
	{
		return value % 2 == 0 ? value : value - 1;
	}
}
