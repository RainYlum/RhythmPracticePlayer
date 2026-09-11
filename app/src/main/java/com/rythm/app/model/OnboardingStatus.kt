package com.rythm.app.model

/**
 * 教学引导状态（三阶段，独立完成标记）：
 * - [introDone] 首次引导（App 简介 + 引导导入第一个视频）已完成；
 * - [playerTutorialDone] 播放页教学（进度条/步进/播放/退出/更多功能）已完成；
 * - [moreMenuTutorialDone] 更多功能教学（AB 循环/倍速/音频延迟/倒计时/帧标记）已完成。
 * 视频库页「再次教学」确认后整体重置，用户可重新体验完整教学流程。
 */
data class OnboardingStatus(
    val introDone: Boolean = false,
    val playerTutorialDone: Boolean = false,
    val moreMenuTutorialDone: Boolean = false,
)