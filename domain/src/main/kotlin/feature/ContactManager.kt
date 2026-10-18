// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxID

class ContactManager @Inject constructor(
    private val scope: CoroutineScope,
    private val contactRepository: ContactRepository,
    private val tox: Tox,
) {
    fun get(publicKey: PublicKey) = contactRepository.get(publicKey.string())
    fun getAll() = contactRepository.getAll()

    fun add(toxID: ToxID, message: String) = scope.launch {
        val publicKeyTxt = toxID.toPublicKey().string()
        // Ник и аватарка отправителя - внутри текста запроса (см. FriendRequestHooks).
        tox.addContact(toxID, runCatching { FriendRequestHooks.decorate?.invoke(message) }.getOrNull() ?: message)
        contactRepository.add(Contact(publicKeyTxt))
        contactRepository.setLastMessage(publicKeyTxt, Date().time)
    }

    fun delete(publicKey: PublicKey) = scope.launch {
        tox.deleteContact(publicKey)
        contactRepository.delete(Contact(publicKey.string()))
    }

    fun setDraft(pk: PublicKey, draft: String) = scope.launch {
        contactRepository.setDraftMessage(pk.string(), draft)
    }
}
