// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.GroupRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.groups.GroupCallManager
import com.stoyko.smessenger.groups.GroupManager

const val GROUP_CHAT_ID = "groupChatId"

class GroupListViewModel @Inject constructor(
    repo: GroupRepository,
    private val groupManager: GroupManager,
    private val tox: Tox,
) : ViewModel() {
    val groups: LiveData<List<GroupChat>> = repo.getAll().asLiveData()
    fun isToxRunning() = tox.started
    suspend fun create(name: String, isChannel: Boolean) = groupManager.create(name, isChannel)
    suspend fun join(id: String) = groupManager.join(id)
}

class GroupChatViewModel @Inject constructor(
    private val repo: GroupRepository,
    private val contactRepository: ContactRepository,
    private val groupManager: GroupManager,
    private val callManager: GroupCallManager,
    private val tox: Tox,
    val scheduled: com.stoyko.smessenger.chat.ScheduledMessages,
) : ViewModel() {
    lateinit var chatId: String
        private set

    lateinit var group: LiveData<GroupChat?>
        private set
    lateinit var messages: LiveData<List<GroupMessage>>
        private set

    val activeCall = callManager.activeChat.asLiveData()
    val micMuted = callManager.micMuted.asLiveData()
    val speakerOn = callManager.speakerOn.asLiveData()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun participantsNow() = callManager.participants.value

    fun setChat(id: String) {
        if (::chatId.isInitialized && chatId == id) return
        chatId = id
        group = repo.get(id).asLiveData()
        // В ленте - только посты/сообщения; служебные (изменить/удалить) и комментарии скрыты.
        messages = repo.messages(id).map { list ->
            latest = list
            list.filterNot {
                com.stoyko.smessenger.chat.Markup.isGroupControl(it.message) ||
                    com.stoyko.smessenger.chat.Markup.isAnyComment(it.message) ||
                    com.stoyko.smessenger.chat.Polls.isVote(it.message)
            }.let { com.stoyko.smessenger.chat.InlineFiles.hideEmbeddedGroup(it) }
        }.asLiveData()
    }

    @Volatile
    private var latest: List<GroupMessage> = emptyList()

    /** Все сообщения группы (вместе со скрытыми из ленты) - например, файлы, встроенные в текст. */
    fun allMessages(): List<GroupMessage> = latest

    /** Комментарии к посту (по времени). */
    fun commentsFor(postUid: String): List<GroupMessage> =
        latest.filter { com.stoyko.smessenger.chat.Markup.parseComment(it.message)?.first == postUid }

    fun commentCount(postUid: String): Int = commentsFor(postUid).size

    fun editMessage(m: GroupMessage, text: String, raw: Boolean = false) = groupManager.editMessage(chatId, m, text, raw)
    fun deleteForAll(m: GroupMessage) = groupManager.deleteForAll(chatId, m)
    fun react(m: GroupMessage, emoji: String) = groupManager.react(chatId, m.msgUid, emoji)
    fun isAdmin() = tox.started && groupManager.isAdmin(chatId)
    fun sendComment(postUid: String, text: String) = groupManager.sendComment(chatId, postUid, text)
    fun selfKey(): String = if (tox.started) tox.groupSelfKey(chatId) else ""

    fun isToxRunning() = tox.started
    fun peerCount() = if (tox.started) tox.groupPeerCount(chatId) else 0
    fun send(text: String) = groupManager.sendText(chatId, text)
    fun sendFile(uri: Uri, displayName: String? = null) = groupManager.sendFile(chatId, uri, displayName)
    fun leave() = groupManager.leave(chatId)
    fun cancelTransfer(messageId: Long) = groupManager.cancelTransfer(messageId)
    val scheduledChanges: LiveData<Int> by lazy { scheduled.changes.asLiveData() }
    fun scheduledTarget() = "g:$chatId"
    fun scheduledCount() = scheduled.forTarget(scheduledTarget()).size
    fun schedule(text: String, at: Long) = scheduled.add(scheduledTarget(), text, at)

    /** Человек уже в контактах (проверка в базе; вызывать не в главном потоке). */
    fun isContact(publicKey: String): Boolean = runCatching { contactRepository.exists(publicKey.uppercase()) || contactRepository.exists(publicKey) }.getOrDefault(false)

    /** Мой ID в мессенджере (для «контакта для заказов» канала). */
    fun myId(): String = runCatching { tox.toxId.string() }.getOrDefault("")
    fun invite(pk: PublicKey) = groupManager.invite(chatId, pk)

    // Информация о группе: участники, название, аватарка.
    suspend fun members() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (tox.started) groupManager.members(chatId) else emptyList()
    }
    fun selfRole(): Int = if (tox.started) groupManager.selfRole(chatId) else 2
    suspend fun kick(peerId: Long) = groupManager.kick(chatId, peerId)
    suspend fun setModerator(peerId: Long, on: Boolean) = groupManager.setModerator(chatId, peerId, on)
    suspend fun setMuted(peerId: Long, muted: Boolean) = groupManager.setMuted(chatId, peerId, muted)

    /** Поиск по сообщениям группы/канала (в т. ч. по подписям и названиям файлов). */
    suspend fun search(q: String): List<GroupMessage> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val safe = q.replace("%", "").replace("_", " ").trim()
        if (safe.length < 2) emptyList() else repo.findLike(chatId, "%$safe%").takeLast(200).reversed()
    }
    fun pushSettings(change: (com.stoyko.smessenger.groups.GroupSettings.Settings) -> com.stoyko.smessenger.groups.GroupSettings.Settings) =
        groupManager.pushSettings(chatId, change)
    fun pushWelcome(w: com.stoyko.smessenger.groups.Welcome.W?) = groupManager.pushWelcome(chatId, w)
    suspend fun rename(name: String) = groupManager.rename(chatId, name)
    suspend fun setAvatar(uri: Uri?) = groupManager.setAvatar(chatId, uri)
    suspend fun setDescription(text: String) = groupManager.setDescription(chatId, text)
    val profileChanges: LiveData<Int> by lazy { com.stoyko.smessenger.groups.GroupProfile.changes.asLiveData() }
    suspend fun contacts(): List<Contact> = contactRepository.getAll().first()

    fun onVisible(visible: Boolean) {
        groupManager.visibleChat = if (visible) chatId else null
        if (visible) groupManager.markRead(chatId)
    }

    fun joinCall(): Boolean = callManager.join(chatId)
    fun leaveCall() = callManager.leave()
    fun toggleMute() = callManager.toggleMute()
    fun togglePeerMute(key: String) = callManager.togglePeerMute(key)
}
