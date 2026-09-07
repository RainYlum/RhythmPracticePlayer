package com.rythm.app.model

/**
 * 统一错误模型（架构 10）：所有 UI 错误仅从 PlaybackUiState.error 渲染，禁止散落 toast。
 */
data class PlayerError(
    val code: PlayerErrorCode,
    val message: String,
    val recoverable: Boolean,
)

enum class PlayerErrorCode {
    /** 解码失败 / 不支持格式。 */
    DECODE_FAILED,

    /** 文件被移动/删除 / IO 错误。 */
    IO_ERROR,

    /** 选区非法（B 早于 A / 区间过短 / 缺失 A）。 */
    LOOP_INVALID,

    /** seek 卡死（watchdog：位置长时间不动）。 */
    SEEK_STUCK,

    /** 未知/兜底。 */
    UNKNOWN,
}