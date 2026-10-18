// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.FriendRequestRepository
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.domain.tox.Tox

class FriendRequestManager @Inject constructor(
    private val scope: CoroutineScope,
    private val contactRepository: ContactRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val messageRepository: MessageRepository,
    private val tox: Tox,
) {
    fun getAll(): Flow<List<FriendRequest>> = friendRequestRepository.getAll()
    fun get(id: PublicKey): Flow<FriendRequest?> = friendRequestRepository.get(id.string())

    fun accept(friendRequest: FriendRequest) = scope.launch {
        val acceptTime = Date().time
        tox.acceptFriendRequest(PublicKey(friendRequest.publicKey))
        // Контакт добавляем до сообщения: сообщение ссылается на контакт.
        if (!contactRepository.exists(friendRequest.publicKey)) contactRepository.add(Contact(friendRequest.publicKey))
        messageRepository.add(
            Message(
                friendRequest.publicKey,
                runCatching { FriendRequestHooks.clean?.invoke(friendRequest.message) }.getOrNull() ?: friendRequest.message,
                Sender.Received,
                MessageType.Normal,
                0,
                acceptTime,
            ),
        )
        contactRepository.setLastMessage(friendRequest.publicKey, acceptTime)
        runCatching { FriendRequestHooks.onAccepted?.invoke(friendRequest.publicKey, friendRequest.message) }
        friendRequestRepository.delete(friendRequest)
    }

    fun reject(friendRequest: FriendRequest) = scope.launch { friendRequestRepository.delete(friendRequest) }
}
