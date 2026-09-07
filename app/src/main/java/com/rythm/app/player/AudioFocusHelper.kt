package com.rythm.app.player

import android.content.Context
import android.media.AudioManager

/**
 * 音频焦点管理（架构 6.4：关闭 ExoPlayer 内建焦点处理，由本类接管）：
 * - AUDIOFOCUS_LOSS_TRANSIENT_MAY_DUCK → 仅 duck（不暂停，onFocusChange(true, duckOnly=true)）；
 * - 其余焦点丢失 → 暂停（onFocusChange(false, ...)）；
 * - **恢复焦点不自动播放**（wasPlayingBeforeInterrupt 由 ViewModel 记录，决策在 UI 层）。
 */
class AudioFocusHelper(
    private val context: Context,
    private val onFocusChange: (granted: Boolean, duckOnly: Boolean) -> Unit,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusChange(true, false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusChange(true, true) // SDK 37 中 MAY_DUCK 已改名
            else -> onFocusChange(false, false) // AUDIOFOCUS_LOSS / AUDIOFOCUS_LOSS_TRANSIENT
        }
    }

    /** 请求焦点；返回是否授予（未授予不强制，播放继续）。 */
    fun request(): Boolean =
        audioManager.requestAudioFocus(
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocus(listener)
    }
}