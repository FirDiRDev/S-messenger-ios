// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.av

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Куда идёт звук звонка: разговорный динамик (у уха, обычная громкость), громкая связь,
 * или гарнитура/наушники/Bluetooth, если подключены.
 *
 * На Android 12+ маршрут задаётся через setCommunicationDevice - старый isSpeakerphoneOn там
 * работает не на всех телефонах (из-за этого звонок шёл только через громкий динамик).
 */
object AudioRoute {
    private const val TAG = "AudioRoute"

    @Volatile
    private var speaker = false

    private fun am(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var inCall = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Начало звонка: режим связи (эхо- и шумоподавление) и нужный динамик.
     * Как в обычных мессенджерах: голосовой звонок - у уха, громкую связь включает сам человек.
     *
     * Некоторые телефоны (Xiaomi, Samsung) при старте звука звонка сами переключают его на громкий
     * динамик - поэтому выбранный маршрут повторно применяется через полсекунды и через 2 секунды.
     */
    fun enterCall(context: Context, speakerOn: Boolean) {
        val a = am(context) ?: return
        inCall = true
        runCatching { a.mode = AudioManager.MODE_IN_COMMUNICATION }
        setSpeaker(context, speakerOn)
        val app = context.applicationContext
        for (delay in longArrayOf(500, 2000)) {
            main.postDelayed({
                if (!inCall) return@postDelayed
                runCatching { if (a.mode != AudioManager.MODE_IN_COMMUNICATION) a.mode = AudioManager.MODE_IN_COMMUNICATION }
                if (isSpeaker(app) != speaker) setSpeaker(app, speaker)
            }, delay)
        }
    }

    fun isSpeaker(context: Context): Boolean {
        val a = am(context) ?: return speaker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = runCatching { a.communicationDevice }.getOrNull()
            if (dev != null) return dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            return speaker
        }
        @Suppress("DEPRECATION")
        return runCatching { a.isSpeakerphoneOn }.getOrDefault(speaker)
    }

    fun setSpeaker(context: Context, on: Boolean) {
        speaker = on
        CallDsp.speakerOn = on
        val a = am(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = a.availableCommunicationDevices
                val target = if (on) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    // Без громкой связи: гарнитура, если есть, иначе динамик у уха.
                    val preferred = intArrayOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    )
                    preferred.asSequence().mapNotNull { t -> devices.firstOrNull { it.type == t } }.firstOrNull()
                }
                if (target != null) {
                    a.setCommunicationDevice(target)
                } else {
                    a.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                a.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            Log.w(TAG, "route: $e")
            @Suppress("DEPRECATION")
            runCatching { a.isSpeakerphoneOn = on }
        }
    }

    /** Конец звонка: всё как было. */
    fun exitCall(context: Context) {
        inCall = false
        main.removeCallbacksAndMessages(null)
        val a = am(context) ?: return
        speaker = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) a.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = false
            a.mode = AudioManager.MODE_NORMAL
        }
    }
}
