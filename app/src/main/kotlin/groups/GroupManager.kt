// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.core.repository.GroupRepository
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.GROUP_PKT_AUDIO
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_FOUNDER
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_MODERATOR
import com.stoyko.smessenger.domain.tox.GROUP_ROLE_OBSERVER
import com.stoyko.smessenger.domain.tox.GroupEventListener
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.bytesToHex
import com.stoyko.smessenger.domain.tox.hexToBytes
import com.stoyko.smessenger.ui.NotificationHelper

private const val TAG = "GroupManager"

// Типы служебных пакетов внутри группы (первый байт). Всё шифруется ядром.
private const val PKT_META: Byte = 1
private const val PKT_TEXT: Byte = 2
private const val PKT_HIST_REQ: Byte = 3
private const val PKT_HIST_ITEM: Byte = 4
// Название и аватарка группы/канала (меняют владелец и модераторы).
private const val PKT_PROFILE: Byte = 5
private const val PKT_AVATAR: Byte = 6

/** Реакции участника на сообщение/пост: [7][JSON {u: uid, e: «❤️,👍»}]. */
private const val PKT_REACT: Byte = 7

/** Настройки от администратора (закреп, медленный режим, медиа, правила): [8][JSON]. */
private const val PKT_SETTINGS: Byte = 8
/** Видео профиля участника для всей группы: объявление [9][отпечаток], запрос [10][отпечаток], кусок [11]…. */
private const val PKT_PV_ANN: Byte = 9
private const val PKT_PV_REQ: Byte = 10
private const val PKT_PV_DATA: Byte = 11
private const val PV_CHUNK = 1150

/** Просмотры постов канала: [12][JSON-массив uid]. */
private const val PKT_VIEWS: Byte = 12
private const val AVATAR_CHUNK = 1100
private const val PKT_FILE_START: Byte = 16
private const val PKT_FILE_CHUNK: Byte = 17

// Приветствие для новых участников (см. Welcome): от администраторов, всем и каждому новому.
private const val PKT_WELCOME: Byte = 20

private const val UID_LEN = 8
private const val FILE_ID_LEN = 16
private const val KEY_LEN = 32
private const val MAX_NAME_BYTES = 64

// Лимит пакета ядра - 1373 байта; оставляем запас под заголовки.
private const val MAX_TEXT_BYTES = 1200
private const val BIG_CHUNK = 32_000 // если библиотека поддерживает большие пакеты - файлы идут быстрее
private const val SMALL_CHUNK = 1_300
const val GROUP_MAX_FILE_SIZE = 50L * 1024 * 1024
private const val PROGRESS_STEP = 128 * 1024

private const val HISTORY_LIMIT = 150
private const val HISTORY_MARGIN_MS = 10 * 60 * 1000L // часы у телефонов расходятся
private const val STALE_FILE_MS = 10 * 60 * 1000L

/**
 * Группы и каналы: создание, вход, приглашения, сообщения, файлы и синхронизация истории.
 * Всё сквозным образом шифрует ядро.
 *
 * История: когда участник появляется в сети, каждая сторона просит у другой сообщения новее
 * своего последнего. Повторы отбрасываются по уникальному ID сообщения.
 */
@Singleton
class GroupManager @Inject constructor(
    private val context: Context,
    private val tox: Tox,
    private val repo: GroupRepository,
    private val listener: GroupEventListener,
    private val notifications: NotificationHelper,
    private val callManager: GroupCallManager,
    private val messages: com.stoyko.smessenger.core.repository.MessageRepository,
) {
    // Ошибка в фоне не должна ронять приложение.
    private val errors = CoroutineExceptionHandler { _, e -> Log.e(TAG, "background error", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    private val dir: File get() = File(context.filesDir, "ft/groups").apply { mkdirs() }

    /** Чат, открытый на экране (для него не показываем уведомления). */
    @Volatile
    var visibleChat: String? = null

    private class Incoming(val messageId: Long, val file: RandomAccessFile, val size: Long) {
        var received = 0L
        var lastReported = 0L

        @Volatile
        var lastActivity = System.currentTimeMillis()
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()

    // Отменённые пользователем отправки (id сообщения).
    private val cancelledSends = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Отменить отправку или приём файла (крестик на круге загрузки). */
    fun cancelTransfer(messageId: Long) = scope.launch {
        cancelledSends.add(messageId)
        incoming.entries.firstOrNull { it.value.messageId == messageId }?.let { (id, inc) ->
            incoming.remove(id)
            runCatching { inc.file.close() }
        }
        com.stoyko.smessenger.domain.feature.TransferMonitor.finish(
            com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(messageId),
        )
        repo.setProgress(messageId, -1)
    }
    private val chunkSize = ConcurrentHashMap<String, Int>()

    /** Принимаемая аватарка группы: версия и буфер. */
    private class PendingAvatar(val version: Long, val data: ByteArray) {
        var received = 0
    }
    private val pendingAvatars = ConcurrentHashMap<String, PendingAvatar>()
    private val sendMutex = Mutex()

    // Историю просим не больше чем у 3 участников за минуту: остальные прислали бы то же самое.
    private val historyRequests = ConcurrentHashMap<String, MutableList<Long>>()

    /** Вызывается до запуска ядра: подписка на события групп. */
    fun attach() = with(listener) {
        // Сменили/убрали видео профиля - участники групп увидят новое.
        lastVideoHash = com.stoyko.smessenger.profile.GroupVideo.myHash(context).orEmpty()
        com.stoyko.smessenger.profile.ProfileVideo.listen { onMyVideoChanged() }
        // Просмотры постов: копим и раз в несколько секунд сообщаем одним пакетом на канал.
        scope.launch {
            while (true) {
                delay(6_000)
                ChannelStats.drain().forEach { (chatId, uids) ->
                    val body = ChannelStats.toJson(uids).toByteArray(Charsets.UTF_8)
                    runCatching { tox.sendGroupPacket(chatId, byteArrayOf(PKT_VIEWS) + body, lossless = true) }
                }
            }
        }
        onMessage = { chatId, peerId, peerKey, peerName, message ->
            // Обычное текстовое сообщение группы (совместимость): ID генерируем сами.
            scope.launch {
                if (allowedToPost(chatId, peerId)) {
                    storeIncoming(chatId, newUid(), System.currentTimeMillis(), peerKey, peerName, message, notify = true)
                }
            }
        }
        onInvite = { friend, data, name ->
            // Приглашение - карточкой в чате с пригласившим: вступить или отказаться решаете сами.
            scope.launch {
                val pk = runCatching { tox.friendPublicKey(friend) }.getOrNull()
                // Новые версии сначала присылают карточку-описание (группа или канал, описание,
                // аватарка) - тогда второй карточки не нужно, только запоминаем данные ядра.
                kotlinx.coroutines.delay(1500)
                if (pk != null && com.stoyko.smessenger.chat.Cards.recentMetaFrom(context, pk)) {
                    com.stoyko.smessenger.chat.Cards.saveInviteData(context, pk, data)
                    return@launch
                }
                if (pk == null) {
                    // Не знаем, от кого (так быть не должно) - принимаем, как раньше.
                    val chatId = tox.acceptGroupInvite(friend, data, selfName())
                    if (chatId != null) repo.ensure(chatId, name)
                    return@launch
                }
                val card = com.stoyko.smessenger.chat.Cards.saveInvite(context, pk, data, name)
                messages.add(
                    com.stoyko.smessenger.core.vo.Message(
                        pk,
                        card,
                        com.stoyko.smessenger.core.vo.Sender.Received,
                        com.stoyko.smessenger.core.vo.MessageType.Normal,
                        Int.MIN_VALUE,
                        System.currentTimeMillis(),
                    ),
                )
                runCatching {
                    notifications.showGroupInviteNotification(pk, name)
                }
            }
        }
        onPeerJoin = { chatId, peerId ->
            scope.launch {
                val g = repo.find(chatId)
                if (g != null && tox.groupSelfRole(chatId) == GROUP_ROLE_FOUNDER) {
                    // Читатели канала - обычные участники ядра (иначе не смогли бы комментировать);
                    // писать посты им не даёт само приложение: их посты отбрасываются у всех.
                    if (g.isChannel) tox.makePeerUser(chatId, peerId)
                    announceMeta(chatId, g.isChannel)
                }
                requestHistory(chatId, peerId)
                // Новому участнику - отпечаток моего видео профиля (видео он попросит сам, если нужно).
                announceVideo(chatId, peerId)
                // Новому участнику - название и аватарку (если их меняли).
                if (isAdmin(chatId) && GroupProfile.version(context, chatId) > 0) sendProfile(chatId, peerId)
                // И настройки группы (закреп, медленный режим, правила).
                if (isAdmin(chatId) && GroupSettings.get(context, chatId).version > 0) {
                    val body = packSettings(GroupSettings.toJson(GroupSettings.get(context, chatId)))
                    runCatching { tox.sendGroupPrivatePacket(chatId, peerId, body) }
                }
                // И приветствие - его увидит только он (как новый участник).
                if (isAdmin(chatId) && Welcome.get(context, chatId).version > 0) {
                    val body = byteArrayOf(PKT_WELCOME) + Welcome.pack(Welcome.toJson(Welcome.get(context, chatId)))
                    runCatching { tox.sendGroupPrivatePacket(chatId, peerId, body) }
                }
                refreshState(chatId)
            }
        }
        onPeerExit = { chatId, _ -> scope.launch { refreshState(chatId) } }
        onSelfJoin = { chatId ->
            scope.launch {
                val g = repo.find(chatId)
                if (g != null && g.isChannel && tox.groupSelfRole(chatId) == GROUP_ROLE_FOUNDER) {
                    // Старые каналы: читателей-«наблюдателей» переводим в участники - для комментариев.
                    tox.makeAllUsers(chatId)
                    announceMeta(chatId, true)
                }
                announceVideo(chatId, null)
                refreshState(chatId)
            }
        }
        onCustomPacket = { chatId, peerId, peerKey, peerName, data ->
            when (data[0]) {
                GROUP_PKT_AUDIO -> callManager.onAudioPacket(chatId, peerKey, peerName, data)
                PKT_META -> scope.launch {
                    if (data.size > 1 && tox.groupPeerRole(chatId, peerId) <= GROUP_ROLE_MODERATOR) {
                        repo.setChannel(chatId, data[1].toInt() and 1 == 1)
                        refreshState(chatId)
                    }
                }
                PKT_TEXT -> scope.launch { receiveText(chatId, peerId, peerKey, peerName, data) }
                PKT_HIST_REQ -> scope.launch { answerHistory(chatId, peerId, data) }
                PKT_HIST_ITEM -> scope.launch { receiveHistoryItem(chatId, peerId, data) }
                // Пакеты одного участника приходят по порядку - обрабатываем сразу, в потоке ядра.
                PKT_PROFILE -> runCatching { receiveProfile(chatId, peerId, data) }
                PKT_AVATAR -> runCatching { receiveAvatarChunk(chatId, data) }
                PKT_SETTINGS -> scope.launch {
                    if (tox.groupPeerRole(chatId, peerId) <= GROUP_ROLE_MODERATOR) {
                        unpackSettings(data)?.let { GroupSettings.onRemote(context, chatId, it) }
                    }
                }
                PKT_WELCOME -> scope.launch {
                    if (tox.groupPeerRole(chatId, peerId) <= GROUP_ROLE_MODERATOR) {
                        Welcome.unpack(data, 1)?.let { Welcome.onRemote(context, chatId, it) }
                    }
                }
                PKT_REACT -> runCatching {
                    GroupReactions.onRemote(context, chatId, peerKey, String(data, 1, data.size - 1, Charsets.UTF_8))
                }
                // Начало файла обрабатываем сразу (в потоке ядра), иначе первые куски пришли бы раньше.
                PKT_FILE_START -> runCatching {
                    if (allowedToPost(chatId, peerId)) receiveFileStart(chatId, peerKey, peerName, data)
                }
                PKT_FILE_CHUNK -> receiveFileChunk(data)
                PKT_VIEWS -> runCatching {
                    ChannelStats.onRemote(context, chatId, peerKey, String(data, 1, data.size - 1, Charsets.UTF_8))
                }
                PKT_PV_ANN -> runCatching { onVideoAnnounce(chatId, peerId, peerKey, data) }
                PKT_PV_REQ -> scope.launch { runCatching { serveVideo(chatId, peerId, peerKey, data) } }
                PKT_PV_DATA -> runCatching { onVideoChunk(peerKey, data) }
                else -> Unit
            }
            Unit
        }
    }

    /** После запуска ядра: переносим сохранённые в профиле группы в базу. */
    fun syncFromCore() = scope.launch {
        val existing = tox.groups()
        existing.forEach { (chatId, name) ->
            // Переименованная группа: ядро помнит только исходное название.
            repo.ensure(chatId, GroupProfile.name(context, chatId) ?: name)
            refreshState(chatId)
        }
        runCatching { ensureTechGroup(existing.map { it.first }) }.onFailure { Log.w(TAG, "tech group: $it") }
    }

    /** Техгруппа убрана: один раз выходим из неё у всех и снимаем закрепление. */
    private fun ensureTechGroup(current: List<String>) {
        val prefs = context.getSharedPreferences("tech_group", Context.MODE_PRIVATE)
        if (prefs.getBoolean(TechGroup.RETIRE_KEY, false)) return
        val id = TechGroup.CHAT_ID.uppercase()
        if (current.any { TechGroup.isTech(it) }) leave(id)
        if (com.stoyko.smessenger.chat.PinnedChats.isPinned(context, id)) {
            com.stoyko.smessenger.chat.PinnedChats.toggle(context, id)
        }
        prefs.edit().putBoolean(TechGroup.RETIRE_KEY, true).apply()
    }

    private fun selfName(): String = runCatching { tox.getName() }.getOrNull()?.ifEmpty { null } ?: "S-messenger"

    private fun selfKey(chatId: String): String = tox.groupSelfKey(chatId).ifEmpty { tox.publicKey.string() }

    private fun refreshState(chatId: String) {
        repo.setState(chatId, tox.groupSelfRole(chatId), tox.groupPeerCount(chatId))
    }

    /** В канале писать могут только владелец и модераторы; сообщения остальных отбрасываем. */
    private fun allowedToPost(chatId: String, peerId: Long): Boolean {
        val g = repo.find(chatId) ?: return true
        return !g.isChannel || tox.groupPeerRole(chatId, peerId) <= GROUP_ROLE_MODERATOR
    }

    private fun announceMeta(chatId: String, isChannel: Boolean) {
        tox.sendGroupPacket(chatId, byteArrayOf(PKT_META, if (isChannel) 1 else 0), lossless = true)
    }

    private fun newUid(): String = Random.nextBytes(UID_LEN).bytesToHex()

    // ---------------- создание / вход / выход ----------------

    suspend fun create(name: String, isChannel: Boolean): String = withContext(Dispatchers.IO) {
        val chatId = tox.createGroup(name, selfName())
        repo.ensure(chatId, name)
        repo.setChannel(chatId, isChannel)
        refreshState(chatId)
        chatId
    }

    suspend fun join(chatIdInput: String): String? = withContext(Dispatchers.IO) {
        // Принимаем и голый ID, и текст приглашения «Присоединяйся…: <ID>».
        val chatId = Regex("[0-9A-Fa-f]{64}").find(chatIdInput)?.value?.uppercase() ?: return@withContext null
        if (!tox.joinGroup(chatId, selfName())) return@withContext null
        repo.ensure(chatId, "")
        Welcome.markJoined(context, chatId)
        chatId
    }

    fun leave(chatId: String) = scope.launch {
        if (callManager.activeChat.value == chatId) callManager.leave()
        tox.leaveGroup(chatId)
        repo.delete(chatId)
        GroupProfile.forget(context, chatId)
        File(dir, chatId).deleteRecursively()
    }

    /**
     * Вступить по карточке-приглашению. null - удалось (возвращаем id группы через [done]),
     * иначе - причина. Пригласивший должен быть в сети.
     */
    fun acceptInvite(cardText: String, done: (String?) -> Unit) = scope.launch {
        val inv = com.stoyko.smessenger.chat.Cards.invite(context, cardText)
        if (inv == null) {
            done(null)
            return@launch
        }
        val number = runCatching { tox.friendNumber(PublicKey(inv.pk)) }.getOrDefault(-1L)
        val chatId = if (number >= 0) runCatching { tox.acceptGroupInvite(number, inv.data, selfName()) }.getOrNull() else null
        if (chatId != null) {
            repo.ensure(chatId, inv.name)
            Welcome.markJoined(context, chatId)
        }
        done(chatId)
    }

    fun invite(chatId: String, friend: PublicKey) = scope.launch {
        // Сначала - карточка с описанием: друг видит, что это канал (или группа), название,
        // описание и аватарку, и может вступить даже по ID, если вы уже не в сети.
        runCatching {
            val g = repo.find(chatId)
            val avatar = com.stoyko.smessenger.chat.Cards.thumb(GroupProfile.avatarBytes(context, chatId))
            com.stoyko.smessenger.chat.Cards.metaMessages(
                chatId,
                g?.isChannel == true,
                GroupProfile.name(context, chatId) ?: g?.name.orEmpty(),
                GroupProfile.description(context, chatId),
                avatar,
            ).forEach { tox.sendMessage(friend, it, com.stoyko.smessenger.core.vo.MessageType.Normal) }
        }.onFailure { Log.w(TAG, "invite meta: $it") }
        tox.inviteToGroup(chatId, friend)
    }

    /**
     * Вступить по карточке-приглашению с описанием: по ID группы (работает, даже если
     * пригласивший не в сети), а если не вышло - по данным приглашения ядра.
     */
    fun acceptMetaInvite(fromPk: String, cardText: String, done: (String?) -> Unit) = scope.launch {
        val meta = com.stoyko.smessenger.chat.Cards.parseMeta(cardText)
        if (meta == null) {
            done(null)
            return@launch
        }
        if (repo.find(meta.chatId) != null) {
            done(meta.chatId)
            return@launch
        }
        var chatId: String? = if (runCatching { tox.joinGroup(meta.chatId, selfName()) }.getOrDefault(false)) meta.chatId else null
        if (chatId == null) {
            val data = com.stoyko.smessenger.chat.Cards.inviteData(context, fromPk)
            val number = runCatching { tox.friendNumber(PublicKey(fromPk)) }.getOrDefault(-1L)
            if (data != null && number >= 0) chatId = runCatching { tox.acceptGroupInvite(number, data, selfName()) }.getOrNull()
        }
        if (chatId != null) {
            repo.ensure(chatId, meta.name)
            repo.setChannel(chatId, meta.channel)
            Welcome.markJoined(context, chatId)
            if (GroupProfile.description(context, chatId).isBlank() && meta.desc.isNotBlank()) GroupProfile.setDescription(context, chatId, meta.desc)
            if (GroupProfile.avatarFile(context, chatId) == null) {
                com.stoyko.smessenger.chat.Cards.inviteAvatar(context, meta.chatId)?.let { GroupProfile.saveAvatarBytes(context, chatId, it) }
            }
        }
        done(chatId)
    }

    fun markRead(chatId: String) = scope.launch {
        repo.markRead(chatId)
        notifications.dismissGroupNotification(chatId)
    }

    // ---------------- сообщения ----------------

    fun sendText(chatId: String, text: String) = scope.launch {
        if (text.isBlank()) return@launch
        val name = selfName()
        val nameBytes = utf8Prefix(name, MAX_NAME_BYTES)
        val key = selfKey(chatId)
        // Длинный текст режем по байтам (кириллица - 2 байта на букву).
        splitUtf8(text, MAX_TEXT_BYTES).forEach { part ->
            val uid = newUid()
            val ts = System.currentTimeMillis()
            val body = part.toByteArray()
            val pkt = ByteBuffer.allocate(1 + UID_LEN + 8 + 1 + nameBytes.size + body.size)
                .put(PKT_TEXT).put(uid.hexToBytes()).putLong(ts)
                .put(nameBytes.size.toByte()).put(nameBytes).put(body).array()
            // Своё сообщение сохраняем всегда: даже если сейчас никого нет, его получат при синхронизации.
            tox.sendGroupPacket(chatId, pkt, lossless = true)
            repo.addMessage(
                GroupMessage(
                    chatId = chatId, senderKey = key, senderName = name, message = part,
                    timestamp = ts, outgoing = true, msgUid = uid,
                ),
                unread = false,
            )
        }
    }

    private fun receiveText(chatId: String, peerId: Long, peerKey: String, peerName: String, data: ByteArray) {
        if (data.size < 1 + UID_LEN + 8 + 1) return
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        val uid = ByteArray(UID_LEN).also { buf.get(it) }.bytesToHex()
        val ts = buf.long
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen) return
        val name = String(ByteArray(nameLen).also { buf.get(it) })
        val text = String(ByteArray(buf.remaining()).also { buf.get(it) })
        // В канале посты пишут только владелец и модераторы; комментарии и правка своих - все.
        val control = com.stoyko.smessenger.chat.Markup.isGroupControl(text) ||
            com.stoyko.smessenger.chat.Markup.isAnyComment(text) ||
            // Голос в опросе: в канале голосуют подписчики (сами посты им писать нельзя).
            com.stoyko.smessenger.chat.Polls.isVote(text)
        if (!control && !allowedToPost(chatId, peerId)) return
        val isAdmin = tox.groupPeerRole(chatId, peerId) <= GROUP_ROLE_MODERATOR
        storeIncoming(chatId, uid, ts, peerKey, name.ifEmpty { peerName }, text, notify = true, senderIsAdmin = isAdmin)
    }

    private fun storeIncoming(
        chatId: String,
        uid: String,
        ts: Long,
        senderKey: String,
        senderName: String,
        text: String,
        notify: Boolean,
        senderIsAdmin: Boolean = false,
    ) {
        if (text.isEmpty()) return
        if (repo.find(chatId) == null) repo.ensure(chatId, "")
        val visible = visibleChat == chatId
        val outgoing = senderKey.isNotEmpty() && senderKey == tox.groupSelfKey(chatId)
        // Изменение/удаление: применяем к сообщению и храним (чтобы передать тем, кто был не в сети).
        if (com.stoyko.smessenger.chat.Markup.isGroupControl(text)) {
            if (repo.addMessage(
                    GroupMessage(
                        chatId = chatId, senderKey = senderKey, senderName = senderName, message = text,
                        timestamp = ts, outgoing = outgoing, msgUid = uid,
                    ),
                    unread = false,
                ) >= 0
            ) {
                applyControl(chatId, senderKey, text, senderIsAdmin)
            }
            return
        }
        // Голос в опросе: учитываем; храним (без уведомления) - его получат и те, кто был не в сети.
        if (com.stoyko.smessenger.chat.Polls.isVote(text)) {
            com.stoyko.smessenger.chat.Polls.onVote(context, senderKey, senderName, text)
            repo.addMessage(
                GroupMessage(
                    chatId = chatId, senderKey = senderKey, senderName = senderName, message = text,
                    timestamp = ts, outgoing = outgoing, msgUid = uid,
                ),
                unread = false,
            )
            return
        }
        // Комментарии и «тихие» посты канала (без звука) - без уведомления.
        val quiet = com.stoyko.smessenger.chat.Markup.isAnyComment(text) || ChannelPost.meta(text)?.silent == true
        val id = repo.addMessage(
            GroupMessage(
                chatId = chatId, senderKey = senderKey, senderName = senderName, message = text,
                timestamp = ts, outgoing = outgoing, msgUid = uid,
            ),
            unread = !visible && !outgoing && !quiet,
        )
        // Изменение/удаление могло прийти раньше самого сообщения (история) - применяем.
        if (id >= 0) replayControls(chatId, uid)
        if (id >= 0 && notify && !visible && !outgoing && !quiet) {
            val g = repo.find(chatId)
            notifications.showGroupMessageNotification(
                chatId,
                g?.name.orEmpty(),
                senderName,
                com.stoyko.smessenger.chat.RichText.plain(com.stoyko.smessenger.chat.Markup.body(text)),
                g?.isChannel == true,
            )
        }
    }

    // ---------------- изменение, удаление, комментарии ----------------

    /**
     * Применить изменение/удаление. Изменять можно только своё; удалять - своё, а владелец и
     * модераторы - любое (например, комментарии в канале).
     */
    private fun applyControl(chatId: String, senderKey: String, text: String, senderIsAdmin: Boolean) {
        com.stoyko.smessenger.chat.Markup.parseGroupPartEdit(text)?.let { pe ->
            val target = repo.findByUid(chatId, pe.key) ?: return
            if (target.senderKey != senderKey || target.isFile()) return
            val parts = arrayOfNulls<String>(pe.total)
            for (m in repo.findLike(chatId, com.stoyko.smessenger.chat.Markup.groupPartPattern(pe.key))) {
                if (m.senderKey != senderKey) continue
                val p = com.stoyko.smessenger.chat.Markup.parseGroupPartEdit(m.message) ?: continue
                if (p.total == pe.total) parts[p.index] = p.chunk
            }
            if (parts.all { it != null }) repo.updateText(chatId, pe.key, parts.joinToString(""))
            return
        }
        val edit = com.stoyko.smessenger.chat.Markup.parseGroupEdit(text)
        val del = com.stoyko.smessenger.chat.Markup.parseGroupDelete(text)
        val uid = edit?.first ?: del ?: return
        val target = repo.findByUid(chatId, uid) ?: return
        val own = target.senderKey == senderKey
        if (edit != null && own && !target.isFile()) {
            repo.updateText(chatId, uid, edit.second)
        } else if (del != null && (own || senderIsAdmin)) {
            repo.deleteByUid(chatId, uid)
            if (target.isFile()) runCatching { File(target.filePath).delete() }
        }
    }

    private fun replayControls(chatId: String, uid: String) {
        for (pattern in com.stoyko.smessenger.chat.Markup.controlLikePatterns(uid)) {
            for (c in repo.findLike(chatId, pattern)) applyControl(chatId, c.senderKey, c.message, false)
        }
    }

    /** Изменить своё сообщение/пост у всех. false - текст слишком длинный. */
    fun editMessage(chatId: String, target: GroupMessage, newText: String, raw: Boolean = false): Boolean {
        if (newText.toByteArray().size > com.stoyko.smessenger.chat.Markup.EDIT_LONG_MAX_BYTES) return false
        val stored = if (raw) {
            // Живое сообщение с новыми параметрами - целиком, со своим заголовком.
            com.stoyko.smessenger.chat.Markup.editedRaw(newText)
        } else if (com.stoyko.smessenger.chat.Markup.isAnyComment(target.message)) {
            // Комментарий остаётся комментарием к тому же посту.
            val post = com.stoyko.smessenger.chat.Markup.parseComment(target.message)!!.first
            com.stoyko.smessenger.chat.Markup.edited("", com.stoyko.smessenger.chat.Markup.comment(post, newText))
        } else {
            com.stoyko.smessenger.chat.Markup.edited(target.message, newText)
        }
        scope.launch {
            repo.updateText(chatId, target.msgUid, stored)
            val single = com.stoyko.smessenger.chat.Markup.groupEdit(target.msgUid, stored)
            if (single.toByteArray().size <= MAX_TEXT_BYTES) {
                sendText(chatId, single)
            } else {
                // Длинный текст - частями; у участников собирается в одно сообщение.
                com.stoyko.smessenger.chat.Markup.groupEditParts(target.msgUid, stored).forEach { sendText(chatId, it).join() }
            }
        }
        return true
    }

    /** Удалить сообщение/пост/комментарий у всех. */
    /** Поставить/снять реакцию в группе или канале - увидят все участники. */
    fun react(chatId: String, uid: String, emoji: String) = scope.launch {
        val set = GroupReactions.toggleMine(context, chatId, uid, emoji)
        val body = GroupReactions.payload(uid, set).toByteArray(Charsets.UTF_8)
        runCatching { tox.sendGroupPacket(chatId, byteArrayOf(PKT_REACT) + body, lossless = true) }
    }

    fun deleteForAll(chatId: String, target: GroupMessage) = scope.launch {
        repo.deleteByUid(chatId, target.msgUid)
        if (target.isFile()) runCatching { File(target.filePath).delete() }
        sendText(chatId, com.stoyko.smessenger.chat.Markup.groupDelete(target.msgUid))
    }

    /** Может ли пользователь удалять чужие сообщения (владелец/модератор). */
    fun isAdmin(chatId: String): Boolean = runCatching { tox.groupSelfRole(chatId) <= GROUP_ROLE_MODERATOR }.getOrDefault(false)

    /** Комментарий к посту канала. */
    fun sendComment(chatId: String, postUid: String, text: String): Boolean {
        if (text.isBlank() || text.toByteArray().size > com.stoyko.smessenger.chat.Markup.EDIT_MAX_BYTES) return false
        sendText(chatId, com.stoyko.smessenger.chat.Markup.comment(postUid, text))
        return true
    }

    // ---------------- участники, название, аватарка ----------------

    fun members(chatId: String): List<com.stoyko.smessenger.domain.tox.GroupPeerInfo> =
        runCatching { tox.groupPeers(chatId) }.getOrDefault(emptyList())

    /** Мой ключ в группе (для учёта просмотров). */
    fun selfKeyOf(chatId: String): String = runCatching { selfKey(chatId) }.getOrDefault("")

    fun selfRole(chatId: String): Int = runCatching { tox.groupSelfRole(chatId) }.getOrDefault(2)

    /** Удалить участника. true - участника больше нет в группе. */
    suspend fun kick(chatId: String, peerId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!tox.kickGroupPeer(chatId, peerId)) return@withContext false
        // Ядро удаляет асинхронно - проверяем, что участник действительно исчез.
        repeat(10) {
            delay(300)
            if (!tox.groupHasPeer(chatId, peerId)) {
                refreshState(chatId)
                return@withContext true
            }
        }
        false
    }

    /** Изменить настройки группы (только администраторы) и разослать всем участникам. */
    fun pushSettings(chatId: String, change: (GroupSettings.Settings) -> GroupSettings.Settings): Boolean {
        if (!isAdmin(chatId)) return false
        val json = GroupSettings.update(context, chatId, change)
        scope.launch { runCatching { sendReliable(chatId, packSettings(json), attempts = 100) } }
        return true
    }

    /**
     * Задать или выключить ([w] = null) приветствие для новых участников (только администраторы)
     * и разослать всем. false - не администратор или приветствие не помещается в пакет ядра.
     */
    fun pushWelcome(chatId: String, w: Welcome.W?): Boolean {
        if (!isAdmin(chatId)) return false
        val next = w ?: Welcome.W()
        // Проверяем размер до сохранения: пакет ядра - не больше 1373 байт.
        val probe = byteArrayOf(PKT_WELCOME) + Welcome.pack(Welcome.toJson(next.copy(version = Long.MAX_VALUE)))
        if (probe.size > MAX_TEXT_BYTES + 150) return false
        val json = Welcome.update(context, chatId, next)
        scope.launch { runCatching { sendReliable(chatId, byteArrayOf(PKT_WELCOME) + Welcome.pack(json), attempts = 100) } }
        return true
    }

    /**
     * Пакет настроек. Правила и бизнес-информация на кириллице могут не влезть в один пакет
     * ядра - тогда JSON сжимается (zlib); старый формат (обычный JSON) тоже понимаем.
     */
    private fun packSettings(json: String): ByteArray {
        val raw = json.toByteArray(Charsets.UTF_8)
        if (raw.size <= MAX_TEXT_BYTES) return byteArrayOf(PKT_SETTINGS) + raw
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(raw.size + 64)
        val n = d.deflate(buf)
        d.end()
        return byteArrayOf(PKT_SETTINGS) + buf.copyOf(n)
    }

    private fun unpackSettings(data: ByteArray): String? {
        if (data.size < 2) return null
        // JSON начинается с «{», сжатое zlib - с 0x78.
        if (data[1] != 0x78.toByte()) return String(data, 1, data.size - 1, Charsets.UTF_8)
        return runCatching {
            val inf = java.util.zip.Inflater()
            inf.setInput(data, 1, data.size - 1)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 16_384) break
            }
            inf.end()
            out.toString("UTF-8")
        }.getOrNull()
    }

    /** Запретить/разрешить участнику писать (наблюдатель - только читает). */
    suspend fun setMuted(chatId: String, peerId: Long, muted: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (muted) tox.makePeerObserver(chatId, peerId) else tox.makePeerUser(chatId, peerId)
            delay(400)
            val role = tox.groupPeerRole(chatId, peerId)
            refreshState(chatId)
            if (muted) role == GROUP_ROLE_OBSERVER else role != GROUP_ROLE_OBSERVER
        }.getOrDefault(false)
    }

    suspend fun setModerator(chatId: String, peerId: Long, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        tox.setGroupModerator(chatId, peerId, on)
    }

    /** Переименовать группу/канал у всех (владелец и модераторы). */
    suspend fun rename(chatId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim().take(64)
        if (clean.isEmpty() || !isAdmin(chatId)) return@withContext false
        GroupProfile.setName(context, chatId, clean)
        repo.setName(chatId, clean)
        GroupProfile.setVersion(context, chatId, System.currentTimeMillis())
        sendProfile(chatId, null)
        true
    }

    /** Описание группы/канала - у всех участников (владелец и модераторы). */
    suspend fun setDescription(chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAdmin(chatId)) return@withContext false
        GroupProfile.setDescription(context, chatId, text.trim())
        GroupProfile.setVersion(context, chatId, System.currentTimeMillis())
        sendProfile(chatId, null)
        true
    }

    /** Новая аватарка (null - убрать) - у всех участников. */
    suspend fun setAvatar(chatId: String, uri: Uri?): Boolean = withContext(Dispatchers.IO) {
        if (!isAdmin(chatId)) return@withContext false
        val bytes = if (uri != null) GroupProfile.encodeAvatar(context, uri) ?: return@withContext false else null
        GroupProfile.saveAvatarBytes(context, chatId, bytes)
        GroupProfile.setVersion(context, chatId, System.currentTimeMillis())
        sendProfile(chatId, null)
        true
    }

    /** Название + аватарка: всем (peerId == null) или одному участнику. */
    private suspend fun sendProfile(chatId: String, peerId: Long?) {
        val version = GroupProfile.version(context, chatId)
        val name = repo.find(chatId)?.name.orEmpty()
        val nameBytes = utf8Prefix(name, MAX_NAME_BYTES)
        val avatar = GroupProfile.avatarBytes(context, chatId)?.takeIf { it.size <= GroupProfile.MAX_AVATAR_BYTES }
        val descBytes = utf8Prefix(GroupProfile.description(context, chatId), 600)
        val head = ByteBuffer.allocate(1 + 8 + 1 + nameBytes.size + 2 + descBytes.size + 4)
            .put(PKT_PROFILE).putLong(version).put(nameBytes.size.toByte()).put(nameBytes)
            .putShort(descBytes.size.toShort()).put(descBytes)
            .putInt(avatar?.size ?: 0).array()
        suspend fun send(pkt: ByteArray) =
            if (peerId == null) sendReliable(chatId, pkt, attempts = 100) else sendPrivateReliable(chatId, peerId, pkt)
        if (!send(head) || avatar == null) return
        var off = 0
        while (off < avatar.size) {
            val len = minOf(AVATAR_CHUNK, avatar.size - off)
            val pkt = ByteBuffer.allocate(1 + 8 + 4 + len)
                .put(PKT_AVATAR).putLong(version).putInt(off).put(avatar, off, len).array()
            if (!send(pkt)) return
            off += len
        }
    }

    private fun receiveProfile(chatId: String, peerId: Long, data: ByteArray) {
        if (data.size < 1 + 8 + 1 + 2 + 4) return
        if (tox.groupPeerRole(chatId, peerId) > GROUP_ROLE_MODERATOR) return
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        val version = buf.long
        if (version <= GroupProfile.version(context, chatId)) return
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen + 6) return
        val name = String(ByteArray(nameLen).also { buf.get(it) }).trim()
        if (buf.remaining() < 2) return
        val descLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < descLen + 4) return
        val desc = String(ByteArray(descLen).also { buf.get(it) }).trim()
        val avatarSize = buf.int
        GroupProfile.setDescription(context, chatId, desc)
        if (name.isNotEmpty()) {
            GroupProfile.setName(context, chatId, name)
            repo.setName(chatId, name)
        }
        when {
            avatarSize == 0 -> {
                pendingAvatars.remove(chatId)
                GroupProfile.saveAvatarBytes(context, chatId, null)
                GroupProfile.setVersion(context, chatId, version)
            }
            avatarSize in 1..GroupProfile.MAX_AVATAR_BYTES -> pendingAvatars[chatId] = PendingAvatar(version, ByteArray(avatarSize))
            else -> GroupProfile.setVersion(context, chatId, version)
        }
        GroupProfile.notifyChanged()
    }

    private fun receiveAvatarChunk(chatId: String, data: ByteArray) {
        if (data.size < 1 + 8 + 4) return
        val p = pendingAvatars[chatId] ?: return
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        if (buf.long != p.version) return
        val off = buf.int
        val len = buf.remaining()
        if (off < 0 || off + len > p.data.size) return
        buf.get(p.data, off, len)
        p.received += len
        if (p.received >= p.data.size) {
            pendingAvatars.remove(chatId)
            // Версию запоминаем только когда аватарка пришла целиком - иначе её пришлют снова.
            GroupProfile.saveAvatarBytes(context, chatId, p.data)
            GroupProfile.setVersion(context, chatId, p.version)
        }
    }

    // ---------------- видео профиля участников (видно всем в группе) ----------------

    private var lastVideoHash: String? = null

    /** Моё видео сменилось - сообщаем во все группы. Вызывается из [attach]-подписки. */
    private fun onMyVideoChanged() {
        val h = com.stoyko.smessenger.profile.GroupVideo.myHash(context).orEmpty()
        if (h == lastVideoHash) return
        lastVideoHash = h
        scope.launch {
            runCatching { tox.groups() }.getOrDefault(emptyList()).forEach { (chatId, _) -> announceVideo(chatId, null) }
        }
    }

    /** Отпечаток моего видео: всей группе (peerId == null) или одному участнику. */
    private fun announceVideo(chatId: String, peerId: Long?) {
        val h = com.stoyko.smessenger.profile.GroupVideo.myHash(context)
        // Кто видео никогда не ставил - молчим (лишних пакетов нет).
        if (h == null && com.stoyko.smessenger.profile.ProfileVideo.mine(context) == null &&
            !context.getSharedPreferences("profile_video", Context.MODE_PRIVATE).getBoolean("ever", false)
        ) {
            return
        }
        val pkt = byteArrayOf(PKT_PV_ANN) + h.orEmpty().toByteArray(Charsets.US_ASCII)
        runCatching {
            if (peerId == null) tox.sendGroupPacket(chatId, pkt, lossless = true) else tox.sendGroupPrivatePacket(chatId, peerId, pkt)
        }
    }

    private fun onVideoAnnounce(chatId: String, peerId: Long, peerKey: String, data: ByteArray) {
        val hash = String(data, 1, data.size - 1, Charsets.US_ASCII).trim()
        if (com.stoyko.smessenger.profile.GroupVideo.onAnnounce(context, peerKey, hash)) {
            tox.sendGroupPrivatePacket(chatId, peerId, byteArrayOf(PKT_PV_REQ) + hash.toByteArray(Charsets.US_ASCII))
        }
    }

    /** Участник просит моё видео: отдаём кусками личными пакетами (шифрует ядро). */
    private suspend fun serveVideo(chatId: String, peerId: Long, peerKey: String, data: ByteArray) {
        val hash = String(data, 1, data.size - 1, Charsets.US_ASCII).trim()
        if (hash != com.stoyko.smessenger.profile.GroupVideo.myHash(context)) return
        if (!com.stoyko.smessenger.profile.GroupVideo.mayServe(peerKey, hash)) return
        val bytes = com.stoyko.smessenger.profile.GroupVideo.myFile(context)?.readBytes() ?: return
        val hb = hash.toByteArray(Charsets.US_ASCII)
        var off = 0
        while (off < bytes.size) {
            val len = minOf(PV_CHUNK, bytes.size - off)
            val pkt = ByteBuffer.allocate(1 + hb.size + 4 + 4 + len)
                .put(PKT_PV_DATA).put(hb).putInt(bytes.size).putInt(off).put(bytes, off, len).array()
            if (!sendPrivateReliable(chatId, peerId, pkt)) return
            off += len
            // Видео - фоновая доставка: не забиваем канал, сообщения группы идут без задержек.
            if ((off / PV_CHUNK) % 16 == 0) delay(20)
        }
    }

    private fun onVideoChunk(peerKey: String, data: ByteArray) {
        if (data.size < 1 + 16 + 8 + 1) return
        val hash = String(data, 1, 16, Charsets.US_ASCII)
        val buf = ByteBuffer.wrap(data, 17, data.size - 17)
        val total = buf.int
        val off = buf.int
        com.stoyko.smessenger.profile.GroupVideo.chunk(context, peerKey, hash, total, off, data, 25, data.size - 25)
    }

    // ---------------- история для тех, кто был не в сети ----------------

    private fun requestHistory(chatId: String, peerId: Long) {
        val now = System.currentTimeMillis()
        val recent = historyRequests.getOrPut(chatId) { ArrayList() }
        synchronized(recent) {
            recent.removeAll { now - it > 60_000 }
            if (recent.size >= 3) return
            recent.add(now)
        }
        val since = (repo.lastTimestamp(chatId) - HISTORY_MARGIN_MS).coerceAtLeast(0)
        val pkt = ByteBuffer.allocate(1 + 8).put(PKT_HIST_REQ).putLong(since).array()
        tox.sendGroupPrivatePacket(chatId, peerId, pkt)
    }

    private suspend fun answerHistory(chatId: String, peerId: Long, data: ByteArray) {
        if (data.size < 9) return
        val g = repo.find(chatId) ?: return
        // В канале историю раздают только администраторы - читателям всё равно не поверят.
        if (g.isChannel && tox.groupSelfRole(chatId) > GROUP_ROLE_MODERATOR) return
        val since = ByteBuffer.wrap(data, 1, 8).long
        // Небольшая случайная пауза: ответы от разных участников не приходят одной пачкой.
        delay(Random.nextLong(0, 1500))
        val items = repo.recentTexts(chatId, since, HISTORY_LIMIT).asReversed()
        for (m in items) {
            val uid = m.msgUid.takeIf { it.length == UID_LEN * 2 } ?: continue
            val key = m.senderKey.takeIf { it.length == KEY_LEN * 2 } ?: continue
            val nameBytes = utf8Prefix(m.senderName, MAX_NAME_BYTES)
            val body = utf8Prefix(m.message, MAX_TEXT_BYTES)
            val pkt = ByteBuffer.allocate(1 + UID_LEN + 8 + KEY_LEN + 1 + nameBytes.size + body.size)
                .put(PKT_HIST_ITEM).put(uid.hexToBytes()).putLong(m.timestamp).put(key.hexToBytes())
                .put(nameBytes.size.toByte()).put(nameBytes).put(body).array()
            if (!sendPrivateReliable(chatId, peerId, pkt)) return
        }
    }

    private fun receiveHistoryItem(chatId: String, fromPeer: Long, data: ByteArray) {
        if (data.size < 1 + UID_LEN + 8 + KEY_LEN + 1) return
        val g = repo.find(chatId) ?: return
        if (g.isChannel && tox.groupPeerRole(chatId, fromPeer) > GROUP_ROLE_MODERATOR) return
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        val uid = ByteArray(UID_LEN).also { buf.get(it) }.bytesToHex()
        val ts = buf.long
        val key = ByteArray(KEY_LEN).also { buf.get(it) }.bytesToHex()
        val nameLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < nameLen) return
        val name = String(ByteArray(nameLen).also { buf.get(it) })
        val text = String(ByteArray(buf.remaining()).also { buf.get(it) })
        // История не шумит уведомлениями - только отметка «непрочитано».
        storeIncoming(chatId, uid, ts, key, name, text, notify = false)
    }

    // ---------------- файлы и медиа ----------------

    /**
     * Отправка файла всем участникам: без подтверждения, файл сразу появляется у всех, кто в сети.
     * [displayName] - имя для участников (пересылка принятого файла: на диске у него служебное имя
     * без расширения, и в группу раньше уходил «file_…» без типа).
     */
    fun sendFile(chatId: String, uri: Uri, displayName: String? = null) = scope.launch {
        val name = displayName?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.ifBlank { null } ?: queryName(uri)
        val target = File(File(dir, chatId).apply { mkdirs() }, "${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: return@launch
        val size = target.length()
        if (size <= 0 || size > GROUP_MAX_FILE_SIZE) {
            target.delete()
            return@launch
        }
        val fileId = Random.nextBytes(FILE_ID_LEN)
        val msgId = repo.addMessage(
            GroupMessage(
                chatId = chatId, senderKey = selfKey(chatId), senderName = selfName(), message = "",
                timestamp = System.currentTimeMillis(), outgoing = true,
                filePath = target.absolutePath, fileName = name, fileSize = size, progress = 0,
                fileId = fileId.bytesToHex(), msgUid = fileId.bytesToHex(),
            ),
            unread = false,
        )
        // Файлы уходят по одному, чтобы не забить канал связи.
        sendMutex.withLock { transmit(chatId, fileId, target, name, msgId) }
    }

    private suspend fun transmit(chatId: String, fileId: ByteArray, file: File, name: String, msgId: Long) {
        val size = file.length()
        val nameBytes = utf8Prefix(name, 200)
        val start = ByteBuffer.allocate(1 + FILE_ID_LEN + 8 + nameBytes.size)
            .put(PKT_FILE_START).put(fileId).putLong(size).put(nameBytes).array()
        if (!sendReliable(chatId, start)) {
            repo.setProgress(msgId, -1)
            return
        }
        var offset = 0L
        var lastReported = 0L
        val liveKey = com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(msgId)
        com.stoyko.smessenger.domain.feature.TransferMonitor.start(liveKey, size, true)
        try {
        RandomAccessFile(file, "r").use { raf ->
            while (offset < size) {
                if (msgId in cancelledSends) {
                    repo.setProgress(msgId, -1)
                    return
                }
                val chunk = chunkSize[chatId] ?: BIG_CHUNK
                val len = minOf(chunk.toLong(), size - offset).toInt()
                val data = ByteArray(len)
                raf.seek(offset)
                raf.readFully(data)
                val pkt = ByteBuffer.allocate(1 + FILE_ID_LEN + 8 + len)
                    .put(PKT_FILE_CHUNK).put(fileId).putLong(offset).put(data).array()
                // Большой кусок пробуем коротко: если библиотека его не принимает - сразу на маленькие.
                val ok = if (chunk == BIG_CHUNK) sendReliable(chatId, pkt, attempts = 3) else sendReliable(chatId, pkt)
                if (!ok) {
                    if (chunk == BIG_CHUNK) {
                        chunkSize[chatId] = SMALL_CHUNK
                        continue
                    }
                    repo.setProgress(msgId, -1)
                    return
                }
                if (chunk == BIG_CHUNK) chunkSize[chatId] = BIG_CHUNK
                offset += len
                com.stoyko.smessenger.domain.feature.TransferMonitor.set(liveKey, offset)
                if (offset - lastReported >= PROGRESS_STEP || offset == size) {
                    lastReported = offset
                    repo.setProgress(msgId, offset)
                }
            }
        }
        } finally {
            com.stoyko.smessenger.domain.feature.TransferMonitor.finish(liveKey)
        }
    }

    /** Ядро может временно не принять пакет (очередь полна) - повторяем с паузами (по умолчанию до ~20 с). */
    private suspend fun sendReliable(chatId: String, data: ByteArray, attempts: Int = 400): Boolean {
        repeat(attempts) {
            if (tox.sendGroupPacket(chatId, data, lossless = true)) return true
            delay(50)
        }
        return false
    }

    private suspend fun sendPrivateReliable(chatId: String, peerId: Long, data: ByteArray): Boolean {
        repeat(100) {
            if (tox.sendGroupPrivatePacket(chatId, peerId, data)) return true
            delay(50)
        }
        return false
    }

    private fun receiveFileStart(chatId: String, peerKey: String, peerName: String, data: ByteArray) {
        if (data.size < 1 + FILE_ID_LEN + 8) return
        dropStaleIncoming()
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        val id = ByteArray(FILE_ID_LEN).also { buf.get(it) }
        val size = buf.long
        if (size <= 0 || size > GROUP_MAX_FILE_SIZE) return
        val nameBytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val name = String(nameBytes).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val target = File(File(dir, chatId).apply { mkdirs() }, "${System.currentTimeMillis()}_$name")
        val visible = visibleChat == chatId
        val msgId = repo.addMessage(
            GroupMessage(
                chatId = chatId, senderKey = peerKey, senderName = peerName, message = "",
                timestamp = System.currentTimeMillis(), outgoing = false,
                filePath = target.absolutePath, fileName = name, fileSize = size, progress = 0,
                fileId = id.bytesToHex(), msgUid = id.bytesToHex(),
            ),
            unread = !visible,
        )
        if (msgId < 0) return // уже получали этот файл
        val raf = RandomAccessFile(target, "rw").apply { setLength(size) }
        incoming[id.bytesToHex()] = Incoming(msgId, raf, size)
        com.stoyko.smessenger.domain.feature.TransferMonitor.start(
            com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(msgId),
            size,
            false,
        )
        if (!visible) {
            val g = repo.find(chatId)
            notifications.showGroupMessageNotification(chatId, g?.name.orEmpty(), peerName, "📎 $name", g?.isChannel == true)
        }
    }

    /** Закрываем файлы, отправка которых оборвалась (отправитель ушёл из сети). */
    private fun dropStaleIncoming() {
        val now = System.currentTimeMillis()
        incoming.entries.removeAll { (_, inc) ->
            val stale = now - inc.lastActivity > STALE_FILE_MS
            if (stale) {
                runCatching { inc.file.close() }
                com.stoyko.smessenger.domain.feature.TransferMonitor.finish(
                    com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(inc.messageId),
                )
                scope.launch { repo.setProgress(inc.messageId, -1) }
            }
            stale
        }
    }

    private fun receiveFileChunk(data: ByteArray) {
        if (data.size < 1 + FILE_ID_LEN + 8) return
        val buf = ByteBuffer.wrap(data, 1, data.size - 1)
        val id = ByteArray(FILE_ID_LEN).also { buf.get(it) }.bytesToHex()
        val offset = buf.long
        val inc = incoming[id] ?: return
        val len = buf.remaining()
        if (offset < 0 || offset + len > inc.size) return
        try {
            synchronized(inc) {
                inc.file.seek(offset)
                inc.file.write(data, data.size - len, len)
                inc.received += len
                inc.lastActivity = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "write chunk failed", e)
            return
        }
        val done = inc.received >= inc.size
        val liveKey = com.stoyko.smessenger.domain.feature.TransferMonitor.groupKey(inc.messageId)
        if (done) {
            com.stoyko.smessenger.domain.feature.TransferMonitor.finish(liveKey)
        } else {
            com.stoyko.smessenger.domain.feature.TransferMonitor.set(liveKey, inc.received)
        }
        if (done || inc.received - inc.lastReported >= PROGRESS_STEP) {
            inc.lastReported = inc.received
            val progress = if (done) inc.size else inc.received
            scope.launch { repo.setProgress(inc.messageId, progress) }
        }
        if (done) {
            incoming.remove(id)
            runCatching { inc.file.close() }
        }
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "file") {
            uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it.replace(Regex("[\\\\/:*?\"<>|]"), "_") }
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            }
        }
        return "file_${System.currentTimeMillis()}"
    }
}

/** Байты UTF-8 строки, обрезанные до max без разрыва символов. */
private fun utf8Prefix(s: String, max: Int): ByteArray =
    splitUtf8(s, max).firstOrNull()?.toByteArray() ?: ByteArray(0)

/** Режет строку на части не длиннее maxBytes в UTF-8, не разрывая символы. */
internal fun splitUtf8(text: String, maxBytes: Int): List<String> {
    val parts = ArrayList<String>()
    val sb = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val len = Character.charCount(cp)
        val cpBytes = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + cpBytes > maxBytes && sb.isNotEmpty()) {
            parts.add(sb.toString())
            sb.setLength(0)
            bytes = 0
        }
        sb.appendCodePoint(cp)
        bytes += cpBytes
        i += len
    }
    if (sb.isNotEmpty()) parts.add(sb.toString())
    return parts
}
