// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactprofile

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.ContactManager

class ContactProfileViewModel @Inject constructor(
    contactManager: ContactManager,
    private val tox: com.stoyko.smessenger.domain.tox.Tox,
    private val messages: com.stoyko.smessenger.core.repository.MessageRepository,
    private val transfers: com.stoyko.smessenger.core.repository.FileTransferRepository,
    private val context: android.content.Context,
    private val contactRepository: com.stoyko.smessenger.core.repository.ContactRepository,
) : ViewModel() {
    /** Своё имя для контакта; пустое - вернуть имя, которое задал собеседник (придёт при подключении). */
    fun rename(name: String) {
        com.stoyko.smessenger.profile.ContactAlias.set(context, publicKey.string(), name)
        val pk = publicKey.string()
        val card = com.stoyko.smessenger.profile.ProfileCard.peer(context, pk)?.name.orEmpty()
        val target = name.trim().ifBlank { card }
        if (target.isBlank()) return
        Thread { runCatching { contactRepository.setName(pk, target) } }.start()
    }

    var publicKey: PublicKey = PublicKey("")
    val contact: LiveData<Contact> by lazy { contactManager.get(publicKey).filterNotNull().asLiveData() }

    /**
     * Код безопасности: одинаков у обоих собеседников (от двух публичных
     * ключей). Если коды совпадают - между вами никто не «встроился».
     */
    /** Ядро - для живой проверки шифрования из профиля. */
    val toxCore: com.stoyko.smessenger.domain.tox.Tox get() = tox

    fun securityCode(): String {
        if (!tox.started) return ""
        val keys = listOf(tox.publicKey.string().uppercase(), publicKey.string().uppercase()).sorted()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((keys[0] + keys[1]).toByteArray())
        val groups = (0 until 12).map { i ->
            val v = ((digest[i * 2].toInt() and 0xFF) shl 8) or (digest[i * 2 + 1].toInt() and 0xFF)
            "%05d".format(v % 100000)
        }
        return groups.chunked(4).joinToString("\n") { it.joinToString(" ") }
    }

    /** Общие медиа, файлы, голосовые и ссылки из переписки. */
    suspend fun shared(): SharedData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pk = publicKey.string()
        val msgs = messages.getNow(pk)
        val times = msgs.filter { it.type == com.stoyko.smessenger.core.vo.MessageType.FileTransfer }
            .associate { it.correlationId to it.timestamp }
        val files = transfers.get(pk).first().mapNotNull { ft ->
            val done = ft.outgoing || (ft.fileSize > 0 && ft.progress >= ft.fileSize)
            if (!done || ft.destination.isEmpty()) return@mapNotNull null
            val uri = SharedMediaView.providerUri(context, ft.destination) ?: return@mapNotNull null
            SharedFile(ft.fileName, ft.fileSize, uri, ft.destination, times[ft.id] ?: 0L, ft.outgoing)
        }
        val texts = msgs.filter {
            it.type == com.stoyko.smessenger.core.vo.MessageType.Normal && !com.stoyko.smessenger.chat.Markup.isHidden(it.message)
        }.map { it.message to it.timestamp }
        SharedData.split(files, texts)
    }
}
