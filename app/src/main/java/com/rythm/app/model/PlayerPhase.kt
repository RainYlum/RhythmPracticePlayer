package com.rythm.app.model

/**
 * 播放会话状态机（架构 6.1，详细设计 4.1）。
 * 说明：单次区间练习播到 B' 后由 SelectionMonitor 触发暂停复位，回到 READY（不经 ENDED）；
 * 仅无选区播放到结尾进入 ENDED（FR-02.5）。
 */
enum class PlayerPhase {
    /** 无媒体。 */
    IDLE,

    /** 初次 prepare 中（仅初始加载；播放中缓冲归 BUFFERING）。 */
    LOADING,

    /** 可播放；暂停控制态，选区/倍速/位置调整均在此态进行。 */
    READY,

    /** 倒计时预听（FR-10）：画面定格 + 音频先行 + 3-2-1 计数。 */
    COUNTDOWN,

    /** 播放中缓冲（P1-9：不进 LOADING，UI 只显示缓冲圈）。 */
    BUFFERING,

    /** 循环关闭且播到结尾（FR-02.5：停住、复位开头、显示控制 UI）。 */
    ENDED,

    /** 解码/IO 失败等（携带 PlayerError）。 */
    ERROR,
}