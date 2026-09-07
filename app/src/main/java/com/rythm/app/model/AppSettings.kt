package com.rythm.app.model

/**
 * 全局设置（架构 8.1 settings.*）。
 * @property countdownSec 倒计时预听时长（0=关闭预听 / 3 / 5，默认 3，FR-10.1）。
 */
data class AppSettings(
    val defaultSpeed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    val countdownSec: Int = 3,
    val keepScreenOn: Boolean = false,
)