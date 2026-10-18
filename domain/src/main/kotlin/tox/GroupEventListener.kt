// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import javax.inject.Inject
import javax.inject.Singleton

/**
 * События групп и каналов от ядра. Вызывается из потока ядра (tox_iterate),
 * поэтому обработчики должны быть быстрыми и уводить тяжёлую работу в корутины.
 * Группы идентифицируются chatId (64 hex-символа) - он постоянен, в отличие от номера.
 */
@Singleton
class GroupEventListener @Inject constructor() {
    var onMessage: (chatId: String, peerId: Long, peerKey: String, peerName: String, message: String) -> Unit =
        { _, _, _, _, _ -> }
    var onInvite: (friendNumber: Long, inviteData: ByteArray, groupName: String) -> Unit = { _, _, _ -> }
    var onPeerJoin: (chatId: String, peerId: Long) -> Unit = { _, _ -> }
    var onPeerExit: (chatId: String, peerId: Long) -> Unit = { _, _ -> }
    var onSelfJoin: (chatId: String) -> Unit = { }
    /** Служебный пакет. peerId нужен, чтобы ответить отправителю лично (синхронизация истории). */
    var onCustomPacket: (chatId: String, peerId: Long, peerKey: String, peerName: String, data: ByteArray) -> Unit =
        { _, _, _, _, _ -> }
}
