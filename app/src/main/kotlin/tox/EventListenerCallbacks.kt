// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.tox

import android.content.Context
import android.util.Log
import java.net.URLConnection
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.FtAutoAccept
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.ui.NotificationHelper
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.FriendRequestRepository
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.repository.UserRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FileKind
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.core.vo.UserStatus
import com.stoyko.smessenger.domain.av.AudioPlayer
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.ChatManager
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxAvEventListener
import com.stoyko.smessenger.domain.tox.ToxEventListener
import com.stoyko.smessenger.domain.tox.ToxFileControl
import com.stoyko.smessenger.domain.tox.ToxavFriendCallState
import com.stoyko.smessenger.domain.tox.toMessageType

private const val MAX_ACTIVE_FRIEND_REQUESTS = 32
private const val TAG = "EventListenerCallbacks"

private fun isImage(filename: String) = try {
    URLConnection.guessContentTypeFromName(filename).startsWith("image/")
} catch (e: Exception) {
    Log.e(TAG, e.toString())
    false
}

private const val FINGERPRINT_LEN = 8
private fun String.fingerprint() = this.take(FINGERPRINT_LEN)

@Singleton
class EventListenerCallbacks @Inject constructor(
    private val ctx: Context,
    private val contactRepository: ContactRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val callManager: CallManager,
    private val chatManager: ChatManager,
    private val fileTransferManager: FileTransferManager,
    private val notificationHelper: NotificationHelper,
    private val tox: Tox,
    private val settings: Settings,
    private val videoCallManager: com.stoyko.smessenger.call.VideoCallManager,
    private val selfAvatar: com.stoyko.smessenger.profile.SelfAvatar,
    private val autoDelete: com.stoyko.smessenger.chat.AutoDelete,
    private val chatExtras: com.stoyko.smessenger.chat.ChatExtras,
    // Создаётся заранее: подключает приём историй к передаче файлов.
    private val stories: com.stoyko.smessenger.stories.StoryStore,
    private val profileCard: com.stoyko.smessenger.profile.ProfileCard,
    // Статус «в сети» в базе пишет только он (задержка «не в сети», «был(а) в сети», сверка с ядром).
    private val presence: Presence,
) {
    private var maxFriendRequestsWarningActive = false
    private var audioPlayer: AudioPlayer? = null
    private val scope = CoroutineScope(
        Dispatchers.Default + com.stoyko.smessenger.domain.Diagnostics.coroutineHandler("события ядра"),
    )

    /**
     * Друг уже есть в ядре, но строки в базе нет (гонка при принятии запроса) -
     * без неё имя, статус и сообщения терялись, и контакт оставался «Неизвестным».
     */
    private val lastStatus = java.util.concurrent.ConcurrentHashMap<String, ConnectionStatus>()
    private val recentIncoming = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Pair<Int, Long>>>()

    /** Тот же текст от того же собеседника за последние 3 минуты (короткие эмодзи-ответы не трогаем). */
    private fun isDuplicate(pk: String, msg: String): Boolean {
        if (msg.length < 4) return false
        val now = System.currentTimeMillis()
        val hash = msg.hashCode()
        val q = recentIncoming.getOrPut(pk) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first().second > 180_000L) q.removeFirst()
            if (q.any { it.first == hash }) return true
            q.addLast(hash to now)
            while (q.size > 50) q.removeFirst()
        }
        return false
    }

    private fun ensureContact(pk: String) {
        try {
            if (!contactRepository.exists(pk)) contactRepository.add(Contact(pk))
        } catch (e: Exception) {
            Log.w(TAG, "ensureContact: $e")
        }
    }

    private suspend fun tryGetContact(pk: String, tag: String) = contactRepository.get(pk).firstOrNull().let {
        if (it == null) Log.e(TAG, "$tag -> unable to get contact for ${pk.fingerprint()}")
        it
    }

    private fun notifyMessage(contact: Contact, message: String) =
        if (com.stoyko.smessenger.chat.ChatMute.isMuted(ctx, contact.publicKey)) {
            // Уведомления чата выключены (на время или навсегда) - только отметка «непрочитано».
            Unit
        } else if (com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, contact.publicKey)) {
            // Скрытый чат: ни имени, ни текста в уведомлении.
            notificationHelper.showMessageNotification(
                contact.copy(name = ctx.getString(R.string.hidden_chat_notification), avatarUri = ""),
                ctx.getString(R.string.hidden_chat_new_message),
                silent = tox.getStatus() == UserStatus.Busy,
            )
        } else {
            notificationHelper.showMessageNotification(contact, message, silent = tox.getStatus() == UserStatus.Busy)
        }

    private suspend fun notifyReaction(publicKey: String, key: String, emoji: String) {
        if (com.stoyko.smessenger.chat.ChatMute.isMuted(ctx, publicKey)) return
        if (com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, publicKey)) return
        val contact = tryGetContact(publicKey, "Reaction") ?: return
        val target = messageRepository.getNow(publicKey).lastOrNull {
            it.sender == Sender.Sent && com.stoyko.smessenger.chat.Markup.key(it.message) == key
        } ?: return
        val snippet = com.stoyko.smessenger.chat.Markup.snippet(target.message).take(60)
        notificationHelper.showMessageNotification(
            contact,
            ctx.getString(R.string.reaction_notification, emoji, snippet),
            silent = tox.getStatus() == UserStatus.Busy,
        )
    }

    private val playerLock = Any()

    private fun releasePlayer() = synchronized(playerLock) {
        audioPlayer?.release()
        audioPlayer = null
    }

    fun setUp(listener: ToxEventListener) = with(listener) {
        // Новый запуск ядра: статусы прошлого сеанса недостоверны. Иначе первое «в сети» после
        // перезапуска считалось сменой TCP <-> UDP, и ожидающие сообщения не досылались.
        lastStatus.clear()
        presence.reset()
        // Звонок закончен с нашей стороны - освобождаем динамик.
        scope.launch {
            callManager.inCall.collect { if (it is com.stoyko.smessenger.domain.feature.CallState.NotInCall) releasePlayer() }
        }
        // Гудки «Вызов…» замолкают, как только собеседник ответил или звонок закончился -
        // даже если экран звонка уже закрыт (вернулись в чат).
        scope.launch {
            callManager.phase.collect {
                if (it != com.stoyko.smessenger.domain.feature.CallPhase.RINGING) com.stoyko.smessenger.call.CallTones.stopRingback()
                // Ответили - время в уведомлении «Идёт звонок» считается с этого момента.
                if (it == com.stoyko.smessenger.domain.feature.CallPhase.ACTIVE) {
                    val s = callManager.inCall.value as? com.stoyko.smessenger.domain.feature.CallState.InCall
                    if (s != null) tryGetContact(s.publicKey.string(), "CallActive")?.let { c -> notificationHelper.showOngoingCallNotification(c) }
                }
            }
        }
        // Живая проверка сквозного шифрования (пакеты без потерь, в чат не попадают).
        friendLosslessPacketHandler = { publicKey, data ->
            // «Записывает аудио», «отправляет файл»… - тоже невидимыми пакетами.
            // Ходы и приглашения в онлайн-игры (шахматы, карты) - тоже невидимыми пакетами.
            if (com.stoyko.smessenger.games.GameNet.handle(publicKey, data)) {
                Unit
            } else if (!com.stoyko.smessenger.chat.PeerActivity.handle(publicKey, data)) {
                runCatching { com.stoyko.smessenger.chat.E2eCheck.handle(tox, publicKey, data) }
            }
        }
        friendStatusMessageHandler = { publicKey, message ->
            ensureContact(publicKey)
            contactRepository.setStatusMessage(publicKey, message)
        }

        friendReadReceiptHandler = { publicKey, messageId ->
            messageRepository.setReceipt(publicKey, messageId, Date().time)
        }

        friendStatusHandler = { publicKey, status ->
            contactRepository.setUserStatus(publicKey, status)
        }

        friendConnectionStatusHandler = { publicKey, status ->
            ensureContact(publicKey)
            // Интерфейс: «в сети» - сразу, «не в сети» - после короткой задержки (без мигания).
            presence.onFriend(publicKey, status)
            // Переход TCP <-> UDP - это не новое подключение. Раньше на каждый такой переход
            // все неподтверждённые сообщения отправлялись заново - у собеседника они дублировались 2-3 раза.
            val was = lastStatus.put(publicKey, status) ?: ConnectionStatus.None
            val cameOnline = status != ConnectionStatus.None && was == ConnectionStatus.None
            if (!cameOnline && status != ConnectionStatus.None) {
                // Смена TCP <-> UDP: досылаем только то, что ядро так и не приняло (без дублей).
                scope.launch {
                    val failed = messageRepository.getPending(publicKey).filter { it.correlationId < 0 }
                    if (failed.isNotEmpty()) chatManager.resend(failed)
                }
            }
            if (cameOnline) {
                scope.launch {
                    val pending = messageRepository.getPending(publicKey)
                    if (pending.isNotEmpty()) {
                        chatManager.resend(pending)
                    }
                    // Голосовые и фото, не успевшие уйти, досылаем автоматически; заодно - свой аватар.
                    kotlinx.coroutines.delay(1500)
                    // Имя, статус и расширенный профиль - чтобы контакт не остался «Неизвестным».
                    profileCard.sendTo(PublicKey(publicKey))
                    stories.sendMissing(publicKey)
                    selfAvatar.sendTo(PublicKey(publicKey))
                    try {
                        fileTransferManager.retryOutgoing(PublicKey(publicKey))
                    } catch (e: Exception) {
                        Log.e(TAG, "retry files: $e")
                    }
                }
            } else if (status == ConnectionStatus.None) {
                fileTransferManager.resetForContact(publicKey)
            }
        }

        friendRequestHandler = handler@{ publicKey, _, message ->
            if (friendRequestRepository.count() > MAX_ACTIVE_FRIEND_REQUESTS) {
                if (!maxFriendRequestsWarningActive) {
                    Log.w(TAG, "Ignoring friend requests w/ $MAX_ACTIVE_FRIEND_REQUESTS already active")
                    maxFriendRequestsWarningActive = true
                }

                return@handler
            }

            maxFriendRequestsWarningActive = false
            val request = FriendRequest(publicKey, message)
            friendRequestRepository.add(request)
            notificationHelper.showFriendRequestNotification(request, silent = tox.getStatus() == UserStatus.Busy)
        }

        friendMessageHandler = handler@{ publicKey, type, _, msg ->
            // Подпись к файлу (приходит вместе с файлом) - показывается под ним, отдельной строкой не пишем.
            if (com.stoyko.smessenger.chat.Captions.onIncoming(ctx, publicKey, msg)) return@handler
            // Голос в опросе - служебный: учитываем, в чат не пишем.
            if (com.stoyko.smessenger.chat.Polls.onIncoming(ctx, publicKey, msg)) return@handler
            // Реакции и закрепы - служебные: применяем, в чат не пишем.
            if (chatExtras.onIncoming(publicKey, msg)) {
                // Реакция на наше сообщение - уведомление.
                com.stoyko.smessenger.chat.Markup.parseReaction(msg)?.let { (key, emoji) ->
                    if (emoji != null && chatManager.activeChat != publicKey) {
                        scope.launch { notifyReaction(publicKey, key, emoji) }
                    }
                }
                return@handler
            }
            if (profileCard.onIncoming(publicKey, msg)) return@handler
            // Подпись к истории друга.
            if (com.stoyko.smessenger.stories.StoryMeta.isCaptionMessage(msg)) {
                com.stoyko.smessenger.stories.StoryMeta.onCaptionMessage(ctx, msg)
                return@handler
            }
            // Аватарка к приглашению в группу/канал - сохраняем, карточка в чате её покажет.
            if (com.stoyko.smessenger.chat.Cards.isInviteAvatar(msg)) {
                com.stoyko.smessenger.chat.Cards.onInviteAvatar(ctx, msg)
                return@handler
            }
            // Приглашение с описанием: дальше идёт в чат карточкой (а данные ядра - отдельно).
            if (com.stoyko.smessenger.chat.Cards.isMeta(msg)) com.stoyko.smessenger.chat.Cards.markMetaFrom(ctx, publicKey)
            // Ответ друга на мой подарок (принял / отклонил) - обновляем карточку в чате.
            if (com.stoyko.smessenger.chat.Gifts.isStatus(msg)) {
                com.stoyko.smessenger.chat.Gifts.onStatus(ctx, msg)
                return@handler
            }
            // Получатель просит прислать файл снова (не докачался) - отправляем с места обрыва.
            if (msg.startsWith(com.stoyko.smessenger.domain.feature.RESEND_PREFIX)) {
                fileTransferManager.onResendMessage(publicKey, msg)
                return@handler
            }
            // Друг принял / отклонил мои монеты (отклонил - монеты возвращаются).
            if (com.stoyko.smessenger.chat.CoinGift.isStatus(msg)) {
                com.stoyko.smessenger.chat.CoinGift.onStatus(ctx, msg)
                return@handler
            }
            // Сам подарок приходит обычным сообщением: в чате - карточка с анимацией и кнопками
            // «Принять» / «Отклонить», в уведомлении - название подарка и подпись.
            // Живая проверка шифрования (запасной путь через скрытое сообщение).
            if (com.stoyko.smessenger.chat.E2eCheck.handleMessage(tox, publicKey, msg)) return@handler
            ensureContact(publicKey)
            // Изменение длинного сообщения - по частям; применяем, когда пришли все.
            com.stoyko.smessenger.chat.Markup.parsePartEdit(msg)?.let { pe ->
                com.stoyko.smessenger.chat.Markup.assemble(publicKey, pe)?.let { (keys, text) ->
                    scope.launch { applyLongEdit(publicKey, keys, text) }
                }
                return@handler
            }
            // Собеседник изменил или удалил своё сообщение.
            val edit = com.stoyko.smessenger.chat.Markup.parseEdit(msg)
            val del = com.stoyko.smessenger.chat.Markup.parseDelete(msg)
            if (edit != null || del != null) {
                scope.launch { applyRemoteChange(publicKey, edit, del) }
                return@handler
            }
            // Защита от дублей: то же самое сообщение, пришедшее повторно после обрыва связи
            // (собеседник не получил подтверждение и отправил снова), не показываем второй раз.
            if (isDuplicate(publicKey, msg)) return@handler
            messageRepository.add(
                Message(publicKey, msg, Sender.Received, type.toMessageType(), Int.MIN_VALUE, Date().time),
            )

            // Смена таймера автоудаления: применяем, без уведомления.
            if (autoDelete.onIncoming(publicKey, msg)) return@handler

            if (chatManager.activeChat != publicKey) {
                scope.launch {
                    val contact = tryGetContact(publicKey, "Message") ?: return@launch
                    notifyMessage(contact, com.stoyko.smessenger.chat.Cards.preview(ctx, msg) ?: com.stoyko.smessenger.chat.RichText.plain(com.stoyko.smessenger.chat.Markup.body(msg)))
                }
                contactRepository.setHasUnreadMessages(publicKey, true)
            }
        }

        friendNameHandler = { publicKey, newName ->
            // Пустое имя не затирает уже известное; если строки контакта ещё нет - создаём её.
            if (!contactRepository.exists(publicKey)) {
                contactRepository.add(Contact(publicKey, name = newName))
            } else if (newName.isNotBlank() && !com.stoyko.smessenger.profile.ContactAlias.has(ctx, publicKey)) {
                // Контакт переименован вами - его новое имя не затирает ваше.
                contactRepository.setName(publicKey, newName)
            }
        }

        fileRecvChunkHandler = { publicKey, fileNumber, position, data ->
            fileTransferManager.addDataToTransfer(publicKey, fileNumber, position, data)
        }

        fileRecvHandler = fileRecvHandler@{ publicKey, fileNo, kind, fileSize, filename ->
            val name = if (kind == FileKind.Avatar.ordinal) publicKey else filename

            val id = fileTransferManager.add(FileTransfer(publicKey, fileNo, kind, fileSize, name, outgoing = false))
            // Докачка оборванного файла: пузырь в чате прежний, уведомление и принятие не нужны.
            if (id >= 0 && fileTransferManager.isResumed(id)) return@fileRecvHandler
            // Повтор уже принятого файла (собеседник не узнал о доставке до обрыва связи) - молча, без второго пузыря.
            if (id < 0) return@fileRecvHandler
            // Тот же файл предложен снова и попал в прежний пузырь - второе уведомление не нужно.
            val again = kind == FileKind.Data.ordinal && fileTransferManager.isReoffer(id)

            if (kind == FileKind.Data.ordinal && (com.stoyko.smessenger.stories.StoryMeta.isStory(filename) ||
                    com.stoyko.smessenger.profile.ProfilePhotos.isPhotoFile(filename) ||
                    com.stoyko.smessenger.profile.ProfileVideo.isVideoFile(filename))
            ) {
                // История: принимаем сразу и молча, в чат не пишем.
                if (id >= 0) fileTransferManager.accept(id)
            } else if (kind == FileKind.Data.ordinal) {
                if (chatManager.activeChat != publicKey && !again) {
                    scope.launch {
                        val contact = tryGetContact(publicKey, "FileRecv") ?: return@launch
                        val msg = when {
                            com.stoyko.smessenger.ui.chat.VoiceMeta.isVoice(name) -> "🎤 " + ctx.getString(R.string.voice_message)
                            com.stoyko.smessenger.media.VideoNoteMeta.isVideoNote(name) -> "⭕ " + ctx.getString(R.string.video_message)
                            else -> com.stoyko.smessenger.ui.chat.FileTypes.sentText(ctx, name)
                        }
                        notifyMessage(contact, msg)
                    }
                    contactRepository.setHasUnreadMessages(publicKey, true)
                }

                val autoAccept = settings.ftAutoAccept
                if (autoAccept == FtAutoAccept.All || autoAccept == FtAutoAccept.Images && isImage(filename)) {
                    fileTransferManager.accept(id)
                }
            }
        }

        fileRecvControlHandler = { publicKey: String, fileNo: Int, control: ToxFileControl ->
            fileTransferManager.setStatus(publicKey, fileNo, control)
        }

        fileChunkRequestHandler = { publicKey: String, fileNo: Int, position: Long, length: Int ->
            fileTransferManager.sendChunk(publicKey, fileNo, position, length)
        }

        selfConnectionStatusHandler = { status ->
            com.stoyko.smessenger.vpn.VpnNodeMatch.toxOnline = status != com.stoyko.smessenger.core.vo.ConnectionStatus.None
            // Подключились к сети - сразу проверяем обновление приложения.
            runCatching { com.stoyko.smessenger.update.Updater.onNetwork(ctx, status != com.stoyko.smessenger.core.vo.ConnectionStatus.None) }
            userRepository.updateConnection(tox.publicKey.string(), status)
            // Пропала наша связь - друзья вскоре «не в сети»; вернулась - сверка с ядром.
            presence.onSelf(status)
        }

        friendTypingHandler = { publicKey, isTyping ->
            contactRepository.setTyping(publicKey, isTyping)
        }
    }

    /** Изменение/удаление сообщения собеседником: ищем его сообщение с тем же ключом. */
    /** Длинное сообщение могло прийти несколькими строками: первая получает новый текст, остальные убираются. */
    private suspend fun applyLongEdit(publicKey: String, keys: List<String>, text: String) {
        val rows = messageRepository.getNow(publicKey).filter {
            it.sender == Sender.Received && com.stoyko.smessenger.chat.Markup.key(it.message) in keys
        }.sortedBy { it.id }
        val first = rows.firstOrNull() ?: return
        messageRepository.updateText(first.id, text)
        // Реакции и закреп - на новый текст.
        val nk = com.stoyko.smessenger.chat.Markup.key(text)
        rows.forEach { runCatching { chatExtras.moveKey(publicKey, com.stoyko.smessenger.chat.Markup.key(it.message), nk) } }
        rows.drop(1).forEach { messageRepository.deleteMessage(it.id) }
    }

    private suspend fun applyRemoteChange(publicKey: String, edit: Pair<String, String>?, del: String?) {
        val key = edit?.first ?: del ?: return
        val target = messageRepository.getNow(publicKey).lastOrNull {
            it.sender == Sender.Received && com.stoyko.smessenger.chat.Markup.key(it.message) == key
        } ?: return
        if (edit != null) {
            messageRepository.updateText(target.id, edit.second)
            runCatching { chatExtras.moveKey(publicKey, key, com.stoyko.smessenger.chat.Markup.key(edit.second)) }
        } else {
            if (target.type == com.stoyko.smessenger.core.vo.MessageType.FileTransfer) {
                runCatching { fileTransferManager.delete(target.correlationId) }
            }
            messageRepository.deleteMessage(target.id)
            // Удалённое у всех сообщение больше не может быть закреплённым - снимаем закреп.
            runCatching { com.stoyko.smessenger.chat.ChatExtras(ctx).unpin(publicKey, key) }
        }
    }

    fun setUp(listener: ToxAvEventListener) = with(listener) {
        callHandler = { pk, audioEnabled, videoEnabled ->
            Log.e(TAG, "call ${pk.fingerprint()} $audioEnabled $videoEnabled")
            scope.launch {
                val contact = tryGetContact(pk, "Call") ?: return@launch
                notificationHelper.showPendingCallNotification(tox.getStatus(), contact)
                callManager.addPendingCall(contact)
            }
        }

        callStateHandler = { pk, callState ->
            Log.e(TAG, "callState ${pk.fingerprint()} $callState")
            if (callState.contains(ToxavFriendCallState.FINISHED) || callState.contains(ToxavFriendCallState.ERROR)) {
                releasePlayer()
                notificationHelper.dismissCallNotification(PublicKey(pk))
                videoCallManager.reset()
                callManager.endCall(PublicKey(pk))
            } else if (callState.any {
                    it == ToxavFriendCallState.SENDING_A || it == ToxavFriendCallState.ACCEPTING_A ||
                        it == ToxavFriendCallState.SENDING_V || it == ToxavFriendCallState.ACCEPTING_V
                }
            ) {
                // Собеседник взял трубку: только теперь идёт разговор (таймер, микрофон).
                callManager.onPeerAnswered(PublicKey(pk))
            }
        }

        // Автоподстройка качества (Настройки → «Качество видео: Авто»): ядро сообщает, сколько
        // сейчас выдерживает связь, - применяем в разумных пределах. Хуже связь - ниже битрейт,
        // звонок не рвётся; лучше - качество само растёт.
        videoBitRateHandler = { pk, bitRate ->
            Log.i(TAG, "videoBitRate ${pk.fingerprint()} $bitRate")
            if (settings.callVideoQuality == 0 && bitRate > 0) {
                val kbps = bitRate.toLong().coerceIn(250L, 2500L)
                com.stoyko.smessenger.domain.tox.VideoQuality.bitRate = kbps
                tox.setCallBitRate(PublicKey(pk), -1, kbps)
            }
        }

        videoReceiveFrameHandler = {
                pk,
                width,
                height,
                y,
                u,
                v,
                yStride,
                uStride,
                vStride,
            ->
            Log.v(
                TAG,
                "videoReceiveFrame ${pk.fingerprint()}" +
                    "$width $height" +
                    "${y.size} ${u.size} ${v.size}" +
                    "$yStride $uStride $vStride",
            )
        }

        // Видео собеседника: сразу на отрисовку (из видеопотока ядра).
        videoFrameHandler = { _, width, height, frame -> videoCallManager.onRemoteFrame(width, height, frame) }

        audioBitRateHandler = { pk, bitRate ->
            Log.i(TAG, "audioBitRate ${pk.fingerprint()} $bitRate")
            // Нижний предел 12 кбит/с: на очень плохой связи Opus всё ещё разборчив, а звонок не рвётся.
            if (bitRate > 0) tox.setCallBitRate(PublicKey(pk), bitRate.toLong().coerceIn(12L, 64L), -1)
        }

        audioReceiveFrameHandler = { _, pcm, channels, samplingRate -> synchronized(playerLock) {
            if (callManager.inCall.value !is com.stoyko.smessenger.domain.feature.CallState.InCall) return@synchronized
            val current = audioPlayer
            val player = if (current != null && current.matches(samplingRate, channels)) {
                current
            } else {
                // Первый кадр звонка или собеседник сменил формат звука - новый проигрыватель.
                current?.release()
                AudioPlayer(samplingRate, channels).also {
                    it.start()
                    audioPlayer = it
                }
            }
            player.buffer(pcm)
        } }
    }
}
