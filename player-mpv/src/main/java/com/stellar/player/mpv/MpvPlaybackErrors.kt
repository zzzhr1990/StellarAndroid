package com.stellar.player.mpv

import com.stellar.player.contract.PlaybackError
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

/** Source errors are more specific than libmpv's approximate END_FILE error. */
internal fun mpvPlaybackError(nativeError: Int, sourceError: Int = 0): PlaybackError = when (sourceError) {
    1 -> PlaybackError.AUTHENTICATION_FAILED
    2 -> PlaybackError.SOURCE_NOT_FOUND
    3 -> PlaybackError.ACCESS_DENIED
    4 -> PlaybackError.NETWORK_TIMEOUT
    5 -> PlaybackError.ENCRYPTION_FAILED
    6 -> PlaybackError.SOURCE_UNAVAILABLE
    // Stable mpv_error values from vendor/include/mpv/client.h. In particular,
    // UNSUPPORTED (-18) refers to system requirements, not a media format.
    else -> when (nativeError) {
        -13 -> PlaybackError.SOURCE_UNAVAILABLE // MPV_ERROR_LOADING_FAILED
        -14 -> PlaybackError.AUDIO_OUTPUT_FAILED
        -15 -> PlaybackError.VIDEO_OUTPUT_FAILED
        -16 -> PlaybackError.NO_PLAYABLE_TRACKS
        -17 -> PlaybackError.UNSUPPORTED_MEDIA // MPV_ERROR_UNKNOWN_FORMAT
        else -> PlaybackError.PLAYBACK_FAILED
    }
}

/** Do not turn provider access errors or native initialization failures into codec errors. */
internal fun mpvOpenError(error: Throwable): PlaybackError = when (error) {
    is SecurityException -> PlaybackError.ACCESS_DENIED
    is FileNotFoundException -> PlaybackError.SOURCE_NOT_FOUND
    is SocketTimeoutException -> PlaybackError.NETWORK_TIMEOUT
    is IOException -> PlaybackError.SOURCE_UNAVAILABLE
    else -> PlaybackError.PLAYBACK_FAILED
}
