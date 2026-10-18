// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.call

import kotlinx.coroutines.flow.filterNotNull

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.stoyko.smessenger.ProximityScreenOff
import com.stoyko.smessenger.ui.NotificationHelper
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.ContactManager

class CallViewModel @Inject constructor(
    private val scope: CoroutineScope,
    private val callManager: CallManager,
    private val notificationHelper: NotificationHelper,
    private val contactManager: ContactManager,
    private val proximityScreenOff: ProximityScreenOff,
    val video: com.stoyko.smessenger.call.VideoCallManager,
    private val tox: com.stoyko.smessenger.domain.tox.Tox,
) : ViewModel() {
    private var publicKey = PublicKey("")

    val contact: LiveData<Contact> by lazy {
        contactManager.get(publicKey).filterNotNull().asLiveData()
    }

    fun setActiveContact(pk: PublicKey) {
        publicKey = pk
    }

    /** false - звонок не начался (собеседник не в сети). */
    fun startCall(withVideo: Boolean): Boolean {
        if (!callManager.startCall(publicKey, withVideo)) return false
        scope.launch { contactManager.get(publicKey).first()?.let { notificationHelper.showOngoingCallNotification(it) } }
        return true
    }

    fun startCamera() = video.startCamera(publicKey)
    fun stopCamera() = video.stopCamera()
    fun switchCamera() = video.switchCamera()

    fun endCall() = scope.launch {
        video.reset()
        callManager.endCall(publicKey)
        notificationHelper.dismissCallNotification(publicKey)
    }

    fun startSendingAudio() = callManager.startSendingAudio()
    fun stopSendingAudio() = callManager.stopSendingAudio()

    fun toggleSpeakerphone() {
        speakerphoneOn = !speakerphoneOn
        if (speakerphoneOn) {
            proximityScreenOff.release()
        } else {
            proximityScreenOff.acquire()
        }
    }

    val inCall = callManager.inCall
    val phase = callManager.phase
    val outgoing get() = callManager.outgoing
    val activeSince get() = callManager.activeSince

    fun setPhase(p: com.stoyko.smessenger.domain.feature.CallPhase) = callManager.setPhase(p)

    /**
     * Проверка защищённого канала перед разговором: собеседнику уходит зашифрованный пакет со
     * случайным вызовом, ответить на него может только его телефон (его закрытый ключ).
     * Возвращает время туда-обратно (мс) или null, если проверка не успела.
     */
    suspend fun verifyTunnel(timeoutMs: Long): Long? =
        runCatching { com.stoyko.smessenger.chat.E2eCheck.ping(tox, publicKey.string(), timeoutMs) }.getOrNull()

    /** Как идёт канал: напрямую (UDP) или через узел (TCP) - узел видит только шифр. */
    fun connectionLabel(): Int? = when (contact.value?.connectionStatus) {
        com.stoyko.smessenger.core.vo.ConnectionStatus.UDP -> com.stoyko.smessenger.R.string.call_path_direct
        com.stoyko.smessenger.core.vo.ConnectionStatus.TCP -> com.stoyko.smessenger.R.string.call_path_relay
        else -> null
    }
    val sendingAudio = callManager.sendingAudio

    var speakerphoneOn by callManager::speakerphoneOn
}
