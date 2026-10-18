// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stories

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FileTransferManager

/**
 * Истории: до 3 своих историй (фото или видео до 15 секунд), через 24 часа
 * они пропадают. Своя история рассылается всем контактам файлом (со сквозным шифрованием);
 * кто не в сети - получит при подключении (если 24 часа ещё не прошли).
 */
@Singleton
class StoryStore @Inject constructor(
    private val context: Context,
    private val fileTransferManager: FileTransferManager,
    private val contactRepository: ContactRepository,
    private val tox: com.stoyko.smessenger.domain.tox.Tox,
) {
    private val prefs = context.getSharedPreferences("stories", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "stories")
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes
    private var job: Job? = null

    init {
        // Входящие истории не показываются строками в чате и собираются здесь.
        // Цепочка: фото профиля (ProfilePhotos) тоже ставят свои обработчики - не затираем их.
        val prevHidden = fileTransferManager.hiddenIncoming
        fileTransferManager.hiddenIncoming = { pk, name -> StoryMeta.isStory(name) || prevHidden?.invoke(pk, name) == true }
        val prevDone = fileTransferManager.onIncomingComplete
        fileTransferManager.onIncomingComplete = { ft ->
            if (StoryMeta.isStory(ft.fileName)) {
                scope.launch { receive(ft.publicKey, ft.fileName, ft.destination, ft.id) }
            } else {
                prevDone?.invoke(ft)
            }
        }
    }

    @Synchronized
    fun all(): List<Story> = try {
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val sent = o.optJSONArray("s")
            Story(
                o.getString("id"), o.getString("o"), o.getString("p"), o.optBoolean("v"),
                o.getLong("c"), o.optLong("d"), o.optBoolean("w"),
                if (sent == null) emptyList() else (0 until sent.length()).map { i -> sent.getInt(i) },
            ).takeIf { File(it.path).exists() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(list: List<Story>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("o", s.owner).put("p", s.path).put("v", s.video)
                    .put("c", s.createdAt).put("d", s.durationMs).put("w", s.viewed).put("s", JSONArray(s.sentIds)),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        _changes.value = _changes.value + 1
    }

    fun mine(): List<Story> = all().filter { it.owner == ME }.sortedBy { it.createdAt }

    /** Истории контактов: ключ -> истории (по времени). */
    fun others(): Map<String, List<Story>> = all().filter { it.owner != ME }.groupBy { it.owner }
        .mapValues { (_, v) -> v.sortedBy { it.createdAt } }

    fun hasUnviewed(owner: String) = all().any { it.owner == owner && !it.viewed }

    fun markViewed(id: String) {
        val list = all()
        if (list.none { it.id == id && !it.viewed }) return
        save(list.map { if (it.id == id) it.copy(viewed = true) else it })
    }

    /**
     * Опубликовать свою историю (файл уже в папке приложения). Возвращает false, если уже есть 3 истории.
     */
    fun publish(
        source: File,
        video: Boolean,
        durationMs: Long,
        caption: String = "",
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): Boolean {
        if (mine().size >= StoryMeta.MAX_MINE) return false
        val now = System.currentTimeMillis()
        val ext = if (video) "mp4" else source.extension.ifEmpty { "jpg" }
        val name = "story_${now}_d$durationMs.$ext"
        val target = File(File(dir, ME).apply { mkdirs() }, name)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        val story = Story(name, ME, target.absolutePath, video, now, durationMs, viewed = true)
        StoryMeta.setCaption(context, name, caption.trim())
        save(all() + story)
        if (progress == null) {
            scope.launch { sendToAll(story) }
        } else {
            // Ждём, пока история встанет в очередь каждому контакту, - для полоски загрузки.
            kotlinx.coroutines.runBlocking { sendToAll(story, progress) }
        }
        return true
    }

    private fun sendCaption(pk: String, story: Story) {
        val c = StoryMeta.caption(context, story.id)
        if (c.isNotBlank()) runCatching { tox.sendMessage(PublicKey(pk), StoryMeta.captionMessage(story.id, c), com.stoyko.smessenger.core.vo.MessageType.Normal) }
    }

    private suspend fun sendToAll(story: Story, progress: ((Int, Int) -> Unit)? = null) {
        val uri: Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", File(story.path))
        val ids = ArrayList<Int>()
        val contacts = contactRepository.getAll().first()
        progress?.invoke(0, contacts.size)
        for ((i, c) in contacts.withIndex()) {
            sendCaption(c.publicKey, story)
            progress?.invoke(i + 1, contacts.size)
            try {
                val id = fileTransferManager.create(PublicKey(c.publicKey), uri, showInChat = false)
                if (id >= 0) {
                    ids.add(id)
                    markSent(story.id, c.publicKey)
                }
            } catch (e: Exception) {
                Log.w(TAG, "send story to ${c.publicKey.take(8)}: $e")
            }
        }
        val list = all()
        save(list.map { if (it.id == story.id) it.copy(sentIds = ids) else it })
    }

    // Кому какая история уже ушла - чтобы досылать тем, кто добавился в контакты позже.
    private val sentPrefs by lazy { context.getSharedPreferences("story_sent", android.content.Context.MODE_PRIVATE) }

    private fun markSent(storyId: String, pk: String) {
        val set = sentPrefs.getStringSet(storyId, emptySet()).orEmpty().toMutableSet()
        set.add(pk)
        sentPrefs.edit().putStringSet(storyId, set).apply()
    }

    /**
     * Контакт подключился: досылаем ему свои живые истории, которых у него ещё нет
     * (например, он добавился в контакты после публикации). Истории видят только контакты.
     */
    fun sendMissing(pk: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            for (story in mine()) {
                if (now - story.createdAt > StoryMeta.TTL_MS - 60_000L) continue
                if (sentPrefs.getStringSet(story.id, emptySet()).orEmpty().contains(pk)) continue
                try {
                    val uri: Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", File(story.path))
                    sendCaption(pk, story)
                    if (fileTransferManager.create(PublicKey(pk), uri, showInChat = false) >= 0) markSent(story.id, pk)
                } catch (e: Exception) {
                    Log.w(TAG, "send missing story: $e")
                }
            }
        }
    }

    fun delete(id: String) {
        val list = all()
        val s = list.firstOrNull { it.id == id } ?: return
        cancelPending(s)
        File(s.path).delete()
        save(list.filterNot { it.id == id })
    }

    /** Не успевшие уйти копии больше не досылаем. */
    private fun cancelPending(s: Story) {
        if (s.owner != ME) return
        s.sentIds.forEach { runCatching { fileTransferManager.markCancelledByUser(it) } }
    }

    private suspend fun receive(owner: String, name: String, destination: String, ftId: Int) {
        try {
            val src = destination.toUri().path?.let { File(it) } ?: return
            val created = StoryMeta.createdAt(name) ?: System.currentTimeMillis()
            val now = System.currentTimeMillis()
            // Пришла слишком поздно (отправитель был долго не в сети) - не показываем.
            if (now - created > StoryMeta.TTL_MS + 10 * 60_000L || !src.exists()) {
                src.delete()
            } else {
                val target = File(File(dir, "in/${owner.take(16)}").apply { mkdirs() }, name)
                src.copyTo(target, overwrite = true)
                src.delete()
                val list = all().filterNot { it.owner == owner && it.id == name }
                // Время жизни считаем от получения, но не дольше суток от создания + запас.
                val born = minOf(now, maxOf(created, now - StoryMeta.TTL_MS + 60_000L))
                save(
                    list + Story(
                        name, owner, target.absolutePath, StoryMeta.isVideoName(name), born,
                        com.stoyko.smessenger.ui.chat.VoiceMeta.durationMs(name) ?: 0L, viewed = false,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "receive story", e)
        } finally {
            // Служебная запись о передаче больше не нужна.
            runCatching { fileTransferManager.delete(ftId) }
        }
    }

    /** Удалить истёкшие (раз в минуту, пока работает фоновый сервис). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                purge()
                // Раз в 10 минут: проверка истёкших историй почти не будит телефон.
                delay(600_000)
            }
        }
    }

    fun purge() {
        val now = System.currentTimeMillis()
        val list = all()
        val (dead, alive) = list.partition { now - it.createdAt > StoryMeta.TTL_MS }
        if (dead.isEmpty()) return
        dead.forEach {
            cancelPending(it)
            File(it.path).delete()
        }
        save(alive)
    }

    companion object {
        const val ME = StoryMeta.ME
        private const val TAG = "Stories"
    }
}
