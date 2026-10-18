// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactlist

import kotlinx.coroutines.flow.filterNotNull

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import java.io.FileNotFoundException
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.tox.ToxStarter
import com.stoyko.smessenger.ui.NotificationHelper
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.ChatManager
import com.stoyko.smessenger.domain.feature.ContactManager
import com.stoyko.smessenger.domain.feature.FileTransferManager
import com.stoyko.smessenger.domain.feature.FriendRequestManager
import com.stoyko.smessenger.domain.feature.UserManager
import com.stoyko.smessenger.domain.tox.ProxyType
import com.stoyko.smessenger.domain.tox.SaveOptions
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxSaveStatus
import com.stoyko.smessenger.domain.tox.testToxSave

class ContactListViewModel @Inject constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val resolver: ContentResolver,
    private val callManager: CallManager,
    private val chatManager: ChatManager,
    private val contactManager: ContactManager,
    private val fileTransferManager: FileTransferManager,
    private val friendRequestManager: FriendRequestManager,
    private val notificationHelper: NotificationHelper,
    private val tox: Tox,
    private val toxStarter: ToxStarter,
    private val settings: Settings,
    userManager: UserManager,
    private val groupRepository: com.stoyko.smessenger.core.repository.GroupRepository,
    private val groupManager: com.stoyko.smessenger.groups.GroupManager,
    val stories: com.stoyko.smessenger.stories.StoryStore,
    private val database: com.stoyko.smessenger.core.db.Database,
) : ViewModel() {
    val storyChanges: LiveData<Int> by lazy { stories.changes.asLiveData() }
    fun leaveGroup(chatId: String) = groupManager.leave(chatId)
    val groups: LiveData<List<com.stoyko.smessenger.core.vo.GroupChat>> = groupRepository.getAll().asLiveData()

    /** Лента новостей: посты всех каналов. */
    val feed: LiveData<List<com.stoyko.smessenger.core.vo.GroupMessage>> by lazy {
        groupRepository.feed("").asLiveData()
    }
    suspend fun createGroup(name: String, isChannel: Boolean) = groupManager.create(name, isChannel)
    suspend fun joinGroup(id: String) = groupManager.join(id)

    val publicKey by lazy { tox.publicKey }
    val toxIdString: String get() = tox.toxId.string()

    val user: LiveData<User> by lazy { userManager.get(publicKey).filterNotNull().asLiveData() }
    val contacts: LiveData<List<Contact>> = contactManager.getAll().asLiveData()
    val friendRequests: LiveData<List<FriendRequest>> = friendRequestManager.getAll().asLiveData()

    fun isToxRunning() = tox.started
    fun tryLoadTox(password: String?): ToxSaveStatus = toxStarter.tryLoadTox(password)
    fun tryLoadToxAsync(password: String?, done: (ToxSaveStatus) -> Unit) = toxStarter.tryLoadToxAsync(password, done)
    fun quitTox() = toxStarter.stopTox()

    fun quittingNeedsConfirmation(): Boolean = settings.confirmQuitting

    fun acceptFriendRequest(friendRequest: FriendRequest) = friendRequestManager.accept(friendRequest)
    fun rejectFriendRequest(friendRequest: FriendRequest) = friendRequestManager.reject(friendRequest)
    fun clearHistory(publicKey: PublicKey) = chatManager.clearHistory(publicKey)

    fun deleteContact(publicKey: PublicKey) {
        callManager.endCall(publicKey)
        notificationHelper.dismissNotifications(publicKey)
        notificationHelper.dismissCallNotification(publicKey)
        contactManager.delete(publicKey)
        chatManager.clearHistory(publicKey)
        scope.launch {
            fileTransferManager.deleteAll(publicKey)
        }
    }

    /** Мой собственный ID (своя ссылка на профиль - не себе же заявку). */
    fun isSelf(pk: PublicKey): Boolean = runCatching { tox.publicKey.string().equals(pk.string(), true) }.getOrDefault(false)

    /** Заявка в друзья по ссылке на профиль - без экрана добавления. */
    fun autoAddContact(fullId: String, hello: String) = scope.launch {
        runCatching {
            contactManager.add(com.stoyko.smessenger.domain.tox.ToxID(fullId), hello).join()
            com.stoyko.smessenger.profile.ContactIds.saveAdded(context, fullId)
        }
    }

    fun contactAdded(toxId: PublicKey): Boolean = runBlocking {
        return@runBlocking contactManager.get(toxId).firstOrNull() != null
    }

    /**
     * Полный профиль (.smsave): ключи, переписка, подарки, монеты, покупки, игры, сеть, VPN,
     * настройки. После записи файл перечитывается целиком и каждый файл сверяется с описью
     * (размер и SHA-256), ключи проверяются пробным открытием. [done] получает итог (в главном
     * потоке); без него итог показывается всплывающим сообщением.
     */
    fun saveToxBackupTo(
        uri: Uri,
        withMedia: Boolean = false,
        progress: com.stoyko.smessenger.profile.FullBackup.Progress? = null,
        done: ((CharSequence) -> Unit)? = null,
    ) = scope.launch(Dispatchers.IO) {
        val message: CharSequence = try {
            if (progress == null) {
                withContext(Dispatchers.Main) { Toast.makeText(context, R.string.backup_full_working, Toast.LENGTH_SHORT).show() }
            }
            // «wt» - с обрезкой: при перезаписи файла побольше в конце не остаётся старый хвост.
            val pfd = runCatching { resolver.openFileDescriptor(uri, "wt") }.getOrNull()
                ?: resolver.openFileDescriptor(uri, "w")
                ?: throw FileNotFoundException(uri.toString())
            val result = pfd.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { out ->
                    com.stoyko.smessenger.profile.FullBackup.export(context, out, tox.getSaveData(), withMedia, database, progress)
                }
            }
            val check = resolver.openInputStream(uri)?.use { ios ->
                com.stoyko.smessenger.profile.FullBackup.verify(context, ios, result.archiveBytes, progress)
            } ?: throw FileNotFoundException(uri.toString())
            val saveData = check.toxSave
            if (check.error != null || saveData == null) {
                context.getString(R.string.smsave_export_bad, check.error ?: context.getString(R.string.smsave_err_no_keys))
            } else {
                val save = SaveOptions(saveData, true, ProxyType.None, "", 0)
                when (val status = testToxSave(save, tox.password)) {
                    ToxSaveStatus.Ok -> {
                        val size = android.text.format.Formatter.formatShortFileSize(context, result.archiveBytes)
                        val text = context.getString(R.string.smsave_export_done, result.files, size)
                        if (result.skipped.isEmpty()) text else text + "\n" + context.getString(R.string.smsave_export_skipped, result.skipped.size)
                    }
                    else -> context.getString(R.string.tox_save_export_failure, status.name)
                }
            }
        } catch (e: FileNotFoundException) {
            context.getString(R.string.tox_save_export_failure, context.getString(R.string.file_not_found))
        } catch (e: Exception) {
            context.getString(R.string.tox_save_export_failure, e.message ?: e.toString())
        }
        withContext(Dispatchers.Main) {
            if (done != null) done(message) else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun onShareText(what: String, to: Contact) = chatManager.sendMessage(PublicKey(to.publicKey), what)

    /** «Поделиться» в несколько чатов сразу: файлы и подпись каждому выбранному. */
    fun shareFilesTo(files: List<android.net.Uri>, caption: String, to: List<Contact>) = scope.launch {
        for (c in to) {
            val pk = PublicKey(c.publicKey)
            files.forEachIndexed { i, f ->
                // Подпись - вместе с первым файлом (показывается под ним).
                if (i == 0 && caption.isNotBlank()) {
                    val info = runCatching {
                        resolver.query(f, null, null, null, null)?.use { cur ->
                            if (!cur.moveToFirst()) return@use null
                            val size = cur.getLong(cur.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
                            val name = cur.getString(cur.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) ?: "file"
                            name to size
                        }
                    }.getOrNull()
                    if (info != null) {
                        com.stoyko.smessenger.chat.Captions.put(context, c.publicKey, true, info.first, info.second, caption)
                        chatManager.sendMessage(pk, com.stoyko.smessenger.chat.Captions.message(info.first, info.second, caption))
                    } else {
                        chatManager.sendMessage(pk, caption)
                    }
                }
                fileTransferManager.create(pk, f)
            }
            if (files.isEmpty() && caption.isNotBlank()) chatManager.sendMessage(pk, caption)
        }
    }

    /** Карточка контакта нескольким получателям (и комментарий, если написан). */
    fun sendCard(targets: List<String>, card: String, comment: String) = scope.launch {
        for (t in targets.take(100)) {
            val pk = PublicKey(t)
            chatManager.sendMessage(pk, card)
            if (comment.isNotBlank()) chatManager.sendMessage(pk, comment)
        }
    }
}
