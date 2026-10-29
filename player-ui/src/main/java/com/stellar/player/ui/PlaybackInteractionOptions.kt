package com.stellar.player.ui

/** Shared presentation policy for both playback backends. No engine or Android objects. */
data class PlaybackInteractionOptions(
    val doubleTapPlay: Boolean = true,
    val doubleTapSeek: Boolean = false,
    val showPlayButton: Boolean = false,
    val showSeekButtons: Boolean = false,
    val forwardSeconds: Int = 10,
    val rewindSeconds: Int = 10,
    val longPress: Boolean = true,
    val speedGesture: Boolean = true,
    val holdSpeed: Float = 2f,
    val seekGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val volumeGesture: Boolean = true,
    val showTime: Boolean = true,
    val showNetworkSpeed: Boolean = true,
    val showBattery: Boolean = true,
    val ignoreSafeArea: Boolean = false,
)
