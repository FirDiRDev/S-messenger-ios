// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.squareup.picasso.Picasso
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.ui.NotificationHelper
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FileTransfer
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.isComplete
import com.stoyko.smessenger.core.vo.isRejected
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.CallState
import com.stoyko.smessenger.domain.feature.ChatManager
import com.stoyko.smessenger.domain.feature.ContactManager
import com.stoyko.smessenger.domain.feature.ExportManager
import com.stoyko.smessenger.domain.feature.FileTransferManager

private const val TAG = "ChatViewModel"

enum class CallAvailability {
    Unavailable,
    Available,
    Active,
}

/** Двойник файла считается дублем, если пришёл (ушёл) не позже этого после первого. */
private const val DUPLICATE_FILE_WINDOW_MS = 24 * 3600_000L

/**
 * Защита ленты чата от дублей файлов:
 * - одна строка на одну передачу (две строки с тем же id файла - показываем первую);
 * - «мёртвый» двойник (отклонён обрывом связи, недокачан) того же файла от того же отправителя
 *   (имя + размер, в пределах суток), если есть более новая живая копия, - скрыт.
 * Намеренно отправленный дважды один и тот же файл (обе копии живые) остаётся двумя сообщениями.
 */
internal fun dedupeFileRows(list: List<Message>, fts: List<FileTransfer>): List<Message> {
    if (list.none { it.type == MessageType.FileTransfer }) return list
    val byId = fts.associateBy { it.id }
    val seen = HashSet<Int>()
    val unique = list.filter { m -> m.type != MessageType.FileTransfer || seen.add(m.correlationId) }
    val hidden = HashSet<Int>()
    unique.withIndex()
        .filter { it.value.type == MessageType.FileTransfer && byId[it.value.correlationId] != null }
        .groupBy { (_, m) -> byId.getValue(m.correlationId).let { "${m.sender}|${it.fileName}|${it.fileSize}" } }
        .values
        .filter { it.size > 1 }
        .forEach { same ->
            val sorted = same.sortedBy { it.value.timestamp }
            for (i in 0 until sorted.size - 1) {
                val earlier = sorted[i]
                val ft = byId.getValue(earlier.value.correlationId)
                val dead = ft.isRejected() || (!ft.outgoing && !ft.isComplete())
                if (!dead) continue
                val newerAlive = sorted.drop(i + 1).any { later ->
                    val lf = byId.getValue(later.value.correlationId)
                    later.value.timestamp - earlier.value.timestamp in 0..DUPLICATE_FILE_WINDOW_MS && !lf.isRejected()
                }
                if (newerAlive) hidden.add(earlier.index)
            }
        }
    return if (hidden.isEmpty()) unique else unique.filterIndexed { i, _ -> i !in hidden }
}

class ChatViewModel @Inject constructor(
    private val callManager: CallManager,
    private val chatManager: ChatManager,
    private val exportManager: ExportManager,
    private val contactManager: ContactManager,
    private val fileTransferManager: FileTransferManager,
    private val notificationHelper: NotificationHelper,
    private val resolver: ContentResolver,
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    val autoDelete: com.stoyko.smessenger.chat.AutoDelete,
    val extras: com.stoyko.smessenger.chat.ChatExtras,
    val scheduled: com.stoyko.smessenger.chat.ScheduledMessages,
    val tox: com.stoyko.smessenger.domain.tox.Tox,
    private val groupManager: com.stoyko.smessenger.groups.GroupManager,
    private val profileCard: com.stoyko.smessenger.profile.ProfileCard,
    private val groupRepository: com.stoyko.smessenger.core.repository.GroupRepository,
) : ViewModel() {
    private var publicKey = PublicKey("")
    private var sentTyping = false

    val contact: LiveData<Contact> by lazy { contactManager.get(publicKey).filterNotNull().asLiveData() }
    val messages: LiveData<List<Message>> by lazy {
        chatManager.messagesFor(publicKey)
            .map { list -> list.filterNot { com.stoyko.smessenger.chat.Markup.isHidden(it.message) } }
            // Страховка от дублей фото/видео в ленте (в том числе уже записанных в базу старыми версиями).
            .combine(fileTransferManager.transfersFor(publicKey)) { list, fts ->
                // Файлы, встроенные в текст сообщений, отдельными строками не показываем.
                com.stoyko.smessenger.chat.InlineFiles.hideEmbedded(dedupeFileRows(list, fts), fts)
            }
            .distinctUntilChanged().asLiveData()
    }
    val autoDeleteChanges: LiveData<Int> by lazy { autoDelete.changes.asLiveData() }
    val extrasChanges: LiveData<Int> by lazy { extras.changes.asLiveData() }

    fun reactionLine(m: Message): String = extras.reactionLine(publicKey.string(), com.stoyko.smessenger.chat.Markup.key(m.message))
    /** (эмодзи, моя, собеседника) - для значков-аватарок у реакций. */
    fun reactionParts(m: Message): List<Triple<String, Boolean, Boolean>> {
        val (mine, theirs) = extras.reactions(publicKey.string(), com.stoyko.smessenger.chat.Markup.key(m.message))
        val my = com.stoyko.smessenger.chat.ChatExtras.split(mine)
        val th = com.stoyko.smessenger.chat.ChatExtras.split(theirs)
        return (th + my).distinct().map { Triple(it, it in my, it in th) }
    }

    fun myReaction(m: Message): String? = extras.reactions(publicKey.string(), com.stoyko.smessenger.chat.Markup.key(m.message)).first

    /**
     * Поставить или снять реакцию (их может быть несколько) и сообщить собеседнику весь
     * набор. null - убрать все свои реакции.
     */
    fun react(m: Message, emoji: String?) {
        val key = com.stoyko.smessenger.chat.Markup.key(m.message)
        val set = com.stoyko.smessenger.chat.ChatExtras.toggle(extras.reactions(publicKey.string(), key).first, emoji)
        extras.setReaction(publicKey.string(), key, set, mine = true)
        // Последняя выбранная реакция - быстрая (для двойного касания).
        if (emoji != null && com.stoyko.smessenger.chat.ChatExtras.has(set, emoji)) {
            com.stoyko.smessenger.chat.QuickReaction.set(context, emoji)
            com.stoyko.smessenger.chat.ReactionPicker.remember(context, emoji)
        }
        chatManager.sendMessage(publicKey, com.stoyko.smessenger.chat.Markup.reaction(key, set))
    }

    fun pinned(): Pair<String, String>? = extras.pinned(publicKey.string())

    fun pins(): List<Pair<String, String>> = extras.pins(publicKey.string())

    fun isPinned(m: Message) = extras.isPinned(publicKey.string(), com.stoyko.smessenger.chat.Markup.key(m.message))

    /** Открепить одно сообщение у обоих собеседников. */
    fun unpin(key: String) {
        extras.unpin(publicKey.string(), key)
        chatManager.sendMessage(publicKey, com.stoyko.smessenger.chat.Markup.unpinOne(key))
    }

    /** Закрепить (или открепить все, m == null) сообщение у обоих собеседников. */
    fun pin(m: Message?) {
        val key = m?.let { com.stoyko.smessenger.chat.Markup.key(it.message) }
        val snippet = m?.let { com.stoyko.smessenger.chat.Markup.snippet(it.message) } ?: ""
        extras.setPinned(publicKey.string(), key, snippet)
        chatManager.sendMessage(publicKey, com.stoyko.smessenger.chat.Markup.pin(key, snippet))
    }

    // ---------- «Прочитано» ----------

    /** Ключ нашего последнего сообщения, которое прочитал собеседник. */
    fun readKey(): String? = extras.readKey(publicKey.string())

    /** Сообщить собеседнику, что мы прочитали его сообщения (до [last] включительно). */
    fun markRead(last: Message) {
        if (!settings.sendReadReceipts || publicKey.string().isEmpty()) return
        val key = com.stoyko.smessenger.chat.Markup.key(last.message)
        val pk = publicKey.string()
        if (extras.lastReadSent(pk) == key || !contactOnline) return
        extras.setLastReadSent(pk, key)
        chatManager.sendControl(publicKey, com.stoyko.smessenger.chat.Markup.read(key))
    }

    // ---------- Отложенная отправка ----------

    val scheduledChanges: LiveData<Int> by lazy { scheduled.changes.asLiveData() }
    fun scheduledCount(): Int = scheduled.forTarget(publicKey.string()).size
    fun schedule(text: String, at: Long) = scheduled.add(publicKey.string(), text, at)
    fun scheduledTarget(): String = publicKey.string()

    fun autoDeleteSeconds(): Long = autoDelete.seconds(publicKey.string())
    fun setAutoDelete(seconds: Long) = autoDelete.setByUser(publicKey, seconds)

    val fileTransfers: LiveData<List<FileTransfer>> by lazy { fileTransferManager.transfersFor(publicKey).asLiveData() }

    fun callingNeedsConfirmation(): Boolean = settings.confirmCalling
    val ongoingCall = callManager.inCall.asLiveData()

    /** Этап звонка: таймер в плашке «идёт звонок» считает только с ответа собеседника. */
    val callPhase = callManager.phase.asLiveData()
    val callActiveSince get() = callManager.activeSince

    val callState get() = contactManager.get(publicKey)
        .filterNotNull()
        .transform { emit(it.connectionStatus != ConnectionStatus.None) }
        .combine(callManager.inCall) { contactOnline, callState ->
            if (!contactOnline) return@combine CallAvailability.Unavailable
            when (callState) {
                CallState.NotInCall -> CallAvailability.Available
                is CallState.InCall -> {
                    if (callState.publicKey == publicKey) {
                        CallAvailability.Active
                    } else {
                        CallAvailability.Unavailable
                    }
                }
            }
        }.asLiveData()

    var contactOnline = false

    fun send(message: String, type: MessageType) = chatManager.sendMessage(publicKey, message, type)

    /** Запрет скриншотов и пересылки: настройка уходит собеседнику обычным сообщением. */
    fun setScreenGuard(on: Boolean) = com.stoyko.smessenger.chat.ScreenGuard.setByUser(context, chatManager, publicKey, on)

    fun clearHistory() = scope.launch {
        chatManager.clearHistory(publicKey)
        fileTransferManager.deleteAll(publicKey)
    }

    fun setActiveChat(pk: PublicKey) {
        if (pk.string().isEmpty()) {
            Log.i(TAG, "Clearing active chat")
            setTyping(false)
        } else {
            Log.i(TAG, "Setting active chat ${pk.fingerprint()}")
        }

        publicKey = pk
        notificationHelper.dismissNotifications(publicKey)
        chatManager.activeChat = publicKey.string()
    }

    fun setTyping(typing: Boolean) {
        if (publicKey.string().isEmpty()) return
        if (typing && !settings.sendTyping) return
        if (sentTyping != typing) {
            chatManager.setTyping(publicKey, typing)
            sentTyping = typing
        }
    }

    fun acceptFt(id: Int) = scope.launch {
        fileTransferManager.accept(id)
    }

    fun rejectFt(id: Int) = scope.launch {
        fileTransferManager.reject(id)
    }

    /** Повторить загрузку: свой файл - отправить снова, чужой - попросить прислать снова. */
    fun retryFt(ft: com.stoyko.smessenger.core.vo.FileTransfer) {
        if (ft.outgoing) fileTransferManager.restartOutgoing(ft.id) else fileTransferManager.requestResend(ft.id)
    }

    /** Все контакты - для выбора, куда переслать. */
    suspend fun contactsSnapshot(): List<com.stoyko.smessenger.core.vo.Contact> =
        contactManager.getAll().first()

    /** Подготовленный к пересылке элемент: текст, готовый файл или файл, который переслать нельзя. */
    private sealed class Fwd {
        data class Text(val body: String) : Fwd()
        data class File(val src: ForwardFiles.Source.Ready, val caption: String) : Fwd()
        data class Bad(val fail: ForwardFiles.Source.Fail) : Fwd()
    }

    /** Текст или файл сообщения [m]: файл проверяется и готовится (в фоновом потоке). */
    private suspend fun prepareForward(m: Message, ft0: FileTransfer?): Fwd {
        if (m.type != MessageType.FileTransfer) return Fwd.Text(com.stoyko.smessenger.chat.Markup.body(m.message))
        // Файл, которого нет в списке экрана (переслан из поиска или ответа), - берём из базы: раньше вместо
        // самого файла уходило только его имя текстом.
        val ft = ft0 ?: runCatching { fileTransferManager.get(m.correlationId).first() }.getOrNull()
            ?: return Fwd.Bad(ForwardFiles.Source.Fail(ForwardFiles.cleanName(m.message), R.string.forward_file_missing))
        return when (val src = withContext(Dispatchers.IO) { ForwardFiles.resolve(context, ft) }) {
            is ForwardFiles.Source.Fail -> Fwd.Bad(src)
            is ForwardFiles.Source.Ready -> {
                // Подпись исходного файла уходит вместе с пересланным.
                val cap = com.stoyko.smessenger.chat.Captions.get(context, ft.publicKey, ft.outgoing, ft.fileName, ft.fileSize).orEmpty()
                Fwd.File(src, cap)
            }
        }
    }

    /**
     * Пересылка нескольких сообщений (по порядку) в «Избранное», личные чаты и группы/каналы.
     * Файл уходит заново, как новый файл (со сквозным шифрованием), под своим настоящим именем;
     * пустой, недокачанный или удалённый файл не уходит - вместо «пустышки» показывается понятная ошибка.
     * [comment] к файлу уходит вместе с первым файлом (подписью под ним), к тексту - отдельным сообщением.
     */
    fun forwardAll(
        items: List<Pair<Message, FileTransfer?>>,
        toSaved: Boolean,
        contacts: List<String>,
        groups: List<String>,
        prefix: String,
        comment: String,
    ) = scope.launch {
        val prepared = items.map { (m, ft) -> prepareForward(m, ft) }
        val bad = prepared.filterIsInstance<Fwd.Bad>()
        val good = prepared.filter { it !is Fwd.Bad }
        var failedSends = 0
        val attach = comment.isNotBlank() && good.firstOrNull() is Fwd.File

        if (toSaved) {
            withContext(Dispatchers.IO) {
                for (f in good) {
                    when (f) {
                        is Fwd.File -> if (!com.stoyko.smessenger.chat.SavedMessages.addUri(context, f.src.uri, f.src.name)) failedSends++
                        is Fwd.Text -> com.stoyko.smessenger.chat.SavedMessages.addText(context, f.body)
                        is Fwd.Bad -> Unit
                    }
                }
                if (comment.isNotBlank()) com.stoyko.smessenger.chat.SavedMessages.addText(context, comment)
            }
        }

        for (t in contacts.take(100)) {
            val pk = PublicKey(t)
            good.forEachIndexed { i, f ->
                try {
                    when (f) {
                        is Fwd.File -> {
                            val cap = if (i == 0 && attach) comment else f.caption
                            if (cap.isNotBlank()) captionFor(pk, f.src.name, f.src.size, cap)
                            if (fileTransferManager.create(pk, f.src.uri, displayName = f.src.name) < 0) failedSends++
                        }
                        is Fwd.Text -> chatManager.sendMessage(pk, prefix + f.body)
                        is Fwd.Bad -> Unit
                    }
                } catch (e: Exception) {
                    failedSends++
                    Log.w(TAG, "forward: $e")
                }
            }
            if (!attach && comment.isNotBlank()) runCatching { chatManager.sendMessage(pk, comment) }
        }

        for (chatId in groups.take(100)) {
            for (f in good) {
                runCatching {
                    when (f) {
                        is Fwd.File -> groupManager.sendFile(chatId, f.src.uri, f.src.name)
                        is Fwd.Text -> groupManager.sendText(chatId, prefix + f.body)
                        is Fwd.Bad -> Unit
                    }
                }.onFailure { failedSends++ }
            }
            if (comment.isNotBlank()) groupManager.sendText(chatId, comment)
        }

        if (bad.isNotEmpty() || failedSends > 0) {
            withContext(Dispatchers.Main) {
                val text = if (bad.size == 1 && failedSends == 0) {
                    context.getString(bad[0].fail.reason, bad[0].fail.name)
                } else {
                    context.getString(R.string.forward_failed_some, bad.size + failedSends)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun groupsSnapshot(): List<com.stoyko.smessenger.core.vo.GroupChat> = groupRepository.getAll().first()

    /**
     * Файл с подписью: подпись уходит вместе с файлом (скрытым служебным сообщением) и
     * показывается прямо под фото/видео/файлом - и у вас, и у собеседника.
     */
    fun createFtWithCaption(file: Uri, caption: String, spoiler: Boolean = false) = scope.launch {
        pulseSending(file)
        Picasso.get().invalidate(file)
        val name = spoilerName(file, spoiler)
        captionFor(publicKey, file, caption, name)
        reportIfFailed(file, fileTransferManager.create(publicKey, file, displayName = name))
    }

    /** Имя «SPOILER_…» для фото/видео под спойлером (null - обычное имя). */
    private fun spoilerName(file: Uri, spoiler: Boolean): String? {
        if (!spoiler) return null
        val n = runCatching { fileTransferManager.describe(file)?.first }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return com.stoyko.smessenger.chat.MediaSpoiler.mark(n)
    }

    /** Файл не ушёл (пустой, удалён или нет доступа) - говорим об этом, а не шлём «пустышку». */
    private suspend fun reportIfFailed(file: Uri, id: Int) {
        if (id >= 0) return
        val name = ForwardFiles.displayName(context, file) ?: file.lastPathSegment.orEmpty()
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.file_send_failed, name), Toast.LENGTH_LONG).show()
        }
    }

    /** Подпись к файлу [file] для собеседника [pk] (до отправки самого файла). */
    private fun captionFor(pk: PublicKey, file: Uri, caption: String, nameOverride: String? = null) {
        val c = caption.trim()
        if (c.isEmpty()) return
        val info = runCatching { fileTransferManager.describe(file) }.getOrNull()
        if (info != null) {
            captionFor(pk, nameOverride ?: info.first, info.second, c)
        } else {
            // Не удалось узнать имя файла - подпись отдельным сообщением, как раньше.
            chatManager.sendMessage(pk, c)
        }
    }

    /** Подпись к файлу с именем [name] и размером [size] - получатель покажет её под этим файлом. */
    private fun captionFor(pk: PublicKey, name: String, size: Long, caption: String) {
        val c = caption.trim()
        if (c.isEmpty()) return
        com.stoyko.smessenger.chat.Captions.put(context, pk.string(), true, name, size, c)
        chatManager.sendMessage(pk, com.stoyko.smessenger.chat.Captions.message(name, size, c))
    }

    /** Карточка контакта: отправить запрос в друзья по полному ID. */
    fun addContactFromCard(fullId: String, hello: String) {
        runCatching { contactManager.add(com.stoyko.smessenger.domain.tox.ToxID(fullId), hello) }
    }

    /** Подарок в чате: принять (попадёт в профиль) или отклонить. Отправитель увидит ответ. */
    fun respondGift(text: String, accept: Boolean) {
        val name = contact.value?.name.orEmpty()
        com.stoyko.smessenger.chat.Gifts.respond(context, tox, publicKey.string(), name, text, accept)
        // Принятый подарок сразу виден друзьям в вашем профиле (карточка уходит в фоне).
        if (accept) scope.launch { runCatching { profileCard.sendToOnline() } }
    }

    /** Карточка-приглашение: вступить в группу/канал. [done] - id группы или null (не удалось). */
    fun acceptInvite(cardText: String, done: (String?) -> Unit) =
        if (com.stoyko.smessenger.chat.Cards.isMeta(cardText)) {
            groupManager.acceptMetaInvite(publicKey.string(), cardText, done)
        } else {
            groupManager.acceptInvite(cardText, done)
        }

    fun createFt(file: Uri, spoiler: Boolean = false) = scope.launch {
        pulseSending(file)
        // Make sure there's no stale cached image in Picasso.
        // This happens if the user sends 2 different files with the same path (e.g. by overwriting one with the other.)
        Picasso.get().invalidate(file)
        reportIfFailed(file, fileTransferManager.create(publicKey, file, displayName = spoilerName(file, spoiler)))
    }

    // ---------- Что я делаю: «записывает аудио», «отправляет фото»… ----------

    /** Показать собеседнику, что я делаю (NONE - перестать). */
    fun setActivity(kind: com.stoyko.smessenger.chat.PeerActivity.Kind) {
        val pk = publicKey.string()
        if (kind == com.stoyko.smessenger.chat.PeerActivity.Kind.NONE) {
            com.stoyko.smessenger.chat.PeerActivity.stop(tox, pk)
        } else {
            com.stoyko.smessenger.chat.PeerActivity.start(tox, pk, kind)
        }
    }

    /** Файл ушёл в отправку: у собеседника несколько секунд горит «отправляет фото/видео/файл». */
    private fun pulseSending(file: Uri) {
        val type = runCatching { context.contentResolver.getType(file) }.getOrNull().orEmpty()
        val kind = when {
            type.startsWith("image/") -> com.stoyko.smessenger.chat.PeerActivity.Kind.PHOTO
            type.startsWith("video/") -> com.stoyko.smessenger.chat.PeerActivity.Kind.VIDEO
            else -> com.stoyko.smessenger.chat.PeerActivity.Kind.FILE
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            com.stoyko.smessenger.chat.PeerActivity.pulse(tox, publicKey.string(), kind)
        }
    }

    // ---------- Изменение и удаление у всех ----------

    fun canEdit(m: Message) = m.sender == com.stoyko.smessenger.core.vo.Sender.Sent && m.type == MessageType.Normal &&
        !com.stoyko.smessenger.chat.AutoDelete.isMarker(m.message) && !com.stoyko.smessenger.chat.ScreenGuard.isMarker(m.message)

    /** false - новый текст слишком длинный. */
    fun edit(m: Message, newText: String, raw: Boolean = false): Boolean {
        val M = com.stoyko.smessenger.chat.Markup
        if (newText.toByteArray().size > M.EDIT_LONG_MAX_BYTES) return false
        // raw - новый текст уже со своим заголовком (живое сообщение с новыми параметрами).
        val stored = if (raw) M.editedRaw(newText) else M.edited(m.message, newText)
        val key = M.key(m.message)
        // Реакции и закреп остаются на изменённом сообщении.
        runCatching { extras.moveKey(publicKey.string(), key, M.key(stored)) }
        val single = M.edit(key, stored)
        if (single.toByteArray().size <= 1300 && m.message.length <= 1372) {
            chatManager.editMessage(publicKey, m.id, stored, single)
        } else {
            // Длинное сообщение - изменение уходит несколькими частями, у друга собирается в одно.
            val parts = M.editParts(key, m.message, stored)
            chatManager.editMessage(publicKey, m.id, stored, parts.first())
            parts.drop(1).forEach { chatManager.sendMessage(publicKey, it) }
        }
        return true
    }

    fun deleteForAll(m: Message) = scope.launch {
        // Закреплённое сообщение удалили у всех - сразу открепляем (у себя и у собеседника:
        // новые версии открепляют сами по удалению, старым шлём «открепить»).
        val pinKey = com.stoyko.smessenger.chat.Markup.key(m.message)
        if (extras.isPinned(publicKey.string(), pinKey)) {
            extras.unpin(publicKey.string(), pinKey)
            chatManager.sendMessage(publicKey, com.stoyko.smessenger.chat.Markup.unpinOne(pinKey))
        }
        if (m.type == MessageType.FileTransfer) fileTransferManager.delete(m.correlationId)
        chatManager.deleteForAll(publicKey, m.id, com.stoyko.smessenger.chat.Markup.delete(com.stoyko.smessenger.chat.Markup.key(m.message)))
    }

    fun delete(msg: Message) = scope.launch {
        if (msg.type == MessageType.FileTransfer) {
            fileTransferManager.delete(msg.correlationId)
        }
        chatManager.deleteMessage(msg.id)
    }

    fun exportFt(id: Int, dest: Uri) = scope.launch {
        fileTransferManager.get(id).take(1).collect { ft ->
            if (ft == null) return@collect
            launch(Dispatchers.IO) {
                try {
                    // content:// и file:// - любой источник (раньше content:// падал с ошибкой экспорта).
                    (resolver.openInputStream(ft.destination.toUri()) ?: FileInputStream(File(ft.destination.toUri().path.orEmpty()))).use { ins ->
                        resolver.openOutputStream(dest).use { os ->
                            ins.copyTo(os!!)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.export_file_success, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, e.toString())
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.export_file_failure, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun backupHistory(publicKey: String, locationSave: Uri) = scope.launch {
        val backupContent = exportManager.generateExportMessagesJString(publicKey)
        launch(Dispatchers.IO) {
            try {
                resolver.openOutputStream(locationSave).use { os ->
                    backupContent.byteInputStream().copyTo(os!!)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        R.string.export_history_success,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, e.toString())
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_history_failure, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun setDraft(draft: String) = contactManager.setDraft(publicKey, draft)
    fun clearDraft() = setDraft("")

    fun onEndCall() {
        callManager.endCall(publicKey)
        notificationHelper.dismissCallNotification(publicKey)
    }
}
