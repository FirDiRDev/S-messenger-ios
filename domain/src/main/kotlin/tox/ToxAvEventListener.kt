// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.util.Log
import java.util.EnumSet
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.PublicKey

typealias CallHandler = (pk: String, audioEnabled: Boolean, videoEnabled: Boolean) -> Unit
typealias CallStateHandler = (pk: String, callState: EnumSet<ToxavFriendCallState>) -> Unit
typealias VideoBitRateHandler = (pk: String, bitRate: Int) -> Unit
typealias VideoReceiveFrameHandler = (
    pk: String,
    width: Int,
    height: Int,
    y: ByteArray,
    u: ByteArray,
    v: ByteArray,
    yStride: Int,
    uStride: Int,
    vStride: Int,
) -> Unit

typealias AudioReceiveFrameHandler = (pk: String, pcm: ShortArray, channels: Int, samplingRate: Int) -> Unit
typealias AudioBitRateHandler = (pk: String, bitRate: Int) -> Unit

private const val TAG = "ToxAvEventListener"

/** События звонков. Вызывается из ToxWrapper (отдельный поток, не из нативных AV-потоков). */
class ToxAvEventListener @Inject constructor() {
    @Volatile
    var contactMapping: List<Pair<PublicKey, Int>> = listOf()

    var callHandler: CallHandler = { _, _, _ -> }
    var callStateHandler: CallStateHandler = { _, _ -> }
    var videoBitRateHandler: VideoBitRateHandler = { _, _ -> }
    var videoReceiveFrameHandler: VideoReceiveFrameHandler = { _, _, _, _, _, _, _, _, _ -> }
    var audioReceiveFrameHandler: AudioReceiveFrameHandler = { _, _, _, _ -> }
    var audioBitRateHandler: AudioBitRateHandler = { _, _ -> }

    /** Кадр видео собеседника: плотный I420. Вызывается из видеопотока ядра - обработчик должен быть быстрым. */
    var videoFrameHandler: (pk: String, width: Int, height: Int, i420: ByteArray) -> Unit = { _, _, _, _ -> }

    private inline fun withKey(friendNo: Long, block: (String) -> Unit) {
        val pk = contactMapping.find { it.second == friendNo.toInt() }?.first?.string()
        if (pk == null) {
            Log.w(TAG, "AV event for unknown friend #$friendNo")
            return
        }
        block(pk)
    }

    internal fun call(friendNo: Long, audioEnabled: Boolean, videoEnabled: Boolean) =
        withKey(friendNo) { callHandler(it, audioEnabled, videoEnabled) }

    internal fun callState(friendNo: Long, state: Int) =
        withKey(friendNo) { callStateHandler(it, ToxavFriendCallState.fromBits(state)) }

    internal fun bitRate(friendNo: Long, audioBitRate: Long, videoBitRate: Long) = withKey(friendNo) {
        audioBitRateHandler(it, audioBitRate.toInt())
        videoBitRateHandler(it, videoBitRate.toInt())
    }

    internal fun videoFrame(friendNo: Long, width: Int, height: Int, i420: ByteArray) =
        withKey(friendNo) { videoFrameHandler(it, width, height, i420) }

    internal fun audioReceiveFrame(friendNo: Long, pcm: ShortArray, channels: Int, samplingRate: Int) =
        withKey(friendNo) { audioReceiveFrameHandler(it, pcm, channels, samplingRate) }
}
