// SPDX-FileCopyrightText: 2019-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.addcontact

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.stoyko.smessenger.tox.ToxStarter
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.domain.feature.ContactManager
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxID
import com.stoyko.smessenger.domain.tox.ToxSaveStatus

class AddContactViewModel @Inject constructor(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val messageRepository: MessageRepository,
    private val contactManager: ContactManager,
    private val tox: Tox,
    private val toxStarter: ToxStarter,
) : ViewModel() {
    val toxId by lazy { tox.toxId }
    val contacts: LiveData<List<Contact>> = contactManager.getAll().asLiveData()

    fun isToxRunning() = tox.started
    fun tryLoadTox(): Boolean = toxStarter.tryLoadTox(null) == ToxSaveStatus.Ok

    private fun addToChatLog(publicKey: String, message: String) = scope.launch {
        messageRepository.add(
            Message(
                publicKey,
                message,
                Sender.Sent,
                MessageType.Normal,
                0,
                Date().time,
            ),
        )
    }

    fun addContact(toxId: ToxID, message: String) = runBlocking {
        contactManager.add(toxId, message).join()
        // Запоминаем полный ID - его можно будет скопировать в профиле контакта.
        com.stoyko.smessenger.profile.ContactIds.saveAdded(context, toxId.string())
        addToChatLog(toxId.toPublicKey().string(), message)
    }
}
