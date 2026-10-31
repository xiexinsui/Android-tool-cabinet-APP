package com.decibelmeter.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
        primary = Color(0xFF0072CE),
        onPrimary = Color.White,
        secondary = Color(0xFF00696E),
        surface = Color(0xFFF7F8FA),
        background = Color(0xFFF7F8FA))

private val DarkColors = darkColorScheme(
        primary = Color(0xFF9FCAFF),
        onPrimary = Color(0xFF00315F),
        secondary = Color(0xFF6FC6CD),
        surface = Color(0xFF16181C),
        background = Color(0xFF111316))

/**
 * 共享浏览 / 预览两个 Compose 页面的主题。
 *
 * 跟随系统深浅色：这两页大量是看图看视频，深色下更舒服，Material3 的深色调色板本身就和
 * DeviceDefault 的深色接近；老的系统控件页面仍然按各自的主题走，不互相影响。
 */
@Composable
fun ToolboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            content = content)
}
