package com.decibelmeter.app

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build

/** 小屏保持手机端既有的竖屏约束；平板交给系统，允许旋转与分屏。 */
object TabletCompat {
    private const val TABLET_SMALLEST_DP = 600

    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        val smallestDp = if (Build.VERSION.SDK_INT >= 36) {
            config.smallestScreenWidthDp
        } else {
            minOf(config.screenWidthDp, config.screenHeightDp)
        }
        return smallestDp >= TABLET_SMALLEST_DP
    }

    fun applyOrientation(activity: Activity) {
        activity.requestedOrientation = if (isTablet(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
