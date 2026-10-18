// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared

import com.stoyko.smessenger.shared.audio.ThemeMusic
import com.stoyko.smessenger.shared.platform.BackgroundWork
import com.stoyko.smessenger.shared.platform.NetworkMonitor
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionSilenceSecondaryAudioHintNotification
import platform.AVFAudio.AVAudioSessionSilenceSecondaryAudioHintTypeBegin
import platform.AVFAudio.AVAudioSessionSilenceSecondaryAudioHintTypeKey
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Точка входа iOS-стороны. Swift вызывает `IosPlatform.shared.onLaunch()` в init() приложения -
 * это раньше, чем закончится didFinishLaunching (так требует BGTaskScheduler.register).
 *
 * Здесь iOS-аналог Android-логики ThemeMusic.install() и аудиофокуса:
 *  - приложение ушло в фон      -> hold("bg")    (в фоне музыка темы не играет);
 *  - звонок/Siri/будильник       -> hold("focus") (AVAudioSession interruption);
 *  - другое приложение играет    -> hold("other") (silenceSecondaryAudioHint).
 */
object IosPlatform {
    val network = NetworkMonitor()
    private val observers = mutableListOf<Any>()
    private var launched = false

    fun onLaunch() {
        if (launched) return
        launched = true
        BackgroundWork.register()
        network.start()

        on(UIApplicationDidEnterBackgroundNotification) {
            ThemeMusic.hold("bg")
            BackgroundWork.scheduleRefresh(15 * 60)
        }
        on(UIApplicationWillEnterForegroundNotification) { ThemeMusic.release("bg") }
        on(AVAudioSessionInterruptionNotification) { n ->
            val type = (n?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            ThemeMusic.set("focus", type == AVAudioSessionInterruptionTypeBegan)
        }
        on(AVAudioSessionSilenceSecondaryAudioHintNotification) { n ->
            val type = (n?.userInfo?.get(AVAudioSessionSilenceSecondaryAudioHintTypeKey) as? NSNumber)?.unsignedLongValue
            ThemeMusic.set("other", type == AVAudioSessionSilenceSecondaryAudioHintTypeBegin)
        }
        ThemeMusic.update()
    }

    private fun on(name: String?, block: (NSNotification?) -> Unit) {
        observers += NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = block,
        )
    }
}
