package com.decibelmeter.app

import androidx.media3.exoplayer.ExoPlayer

/**
 * 预览页那个播放器的桥。
 *
 * 播放器活在 Compose 页面里（`MediaPage` 自己管创建与释放），而倍速/字幕/横屏这些动作挂在
 * Activity 的 options menu 上——两边不在同一层，所以留一个只放「当前播放器」的桥，
 * 由 MediaPage 注册、销毁时注销。
 *
 * 倍速单独存一份：左右翻页会把播放器释放重建，用户选过的倍速要跟着走。
 */
object PlaybackBridge {

    @Volatile
    var player: ExoPlayer? = null

    @Volatile
    private var currentSpeed = 1f

    val speed: Float
        get() = currentSpeed

    fun setSpeed(value: Float) {
        currentSpeed = value
        player?.setPlaybackSpeed(value)
    }
}
