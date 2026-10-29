package com.stellar.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.stellar.player.contract.RejectionReason
import java.text.NumberFormat

@Composable
fun numberLabel(value: Int): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)

@Composable
fun speedLabel(speed: Float): String = stringResource(R.string.speed_value,
    NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
        maximumFractionDigits = 1
    }.format(speed))

@Composable
fun rejectionLabel(reason: RejectionReason): String = stringResource(when (reason) {
    RejectionReason.STALE_SESSION -> R.string.reason_stale
    RejectionReason.INVALID_STATE -> R.string.reason_state
    RejectionReason.SEEK_UNAVAILABLE -> R.string.reason_seek
    RejectionReason.SPEED_UNAVAILABLE -> R.string.reason_speed
    RejectionReason.TRACK_UNAVAILABLE -> R.string.reason_track
    RejectionReason.FRAME_UNAVAILABLE -> R.string.frame_unavailable
    RejectionReason.OPERATION_BUSY -> R.string.frame_busy
})

@Composable
internal fun hillsSpeedLabel(speed: Float): String = stringResource(R.string.hills_speed_value,
    NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(speed))
