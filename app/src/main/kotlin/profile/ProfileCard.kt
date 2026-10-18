// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.Tox

/** Расширенный профиль: то, чего нет в самом протоколе (имя и статус там есть). */
data class ExtProfile(
    val username: String = "",
    val bio: String = "",
    val city: String = "",
    val birthday: String = "",
    val link: String = "",
    // Имя и статус дублируются: так собеседник гарантированно получит их, даже если
    // событие смены имени ядра было пропущено (контакт «Неизвестный»).
    val name: String = "",
    val status: String = "",
    // Полный ID отправителя (76 символов) - чтобы друзья могли поделиться им с другими.
    val id: String = "",
    /** Эмодзи-статус: живой эмодзи рядом с именем в профиле и чате. */
    val emojiStatus: String = "",
    /** Последние полученные подарки (эмодзи) - видны друзьям в профиле. */
    val gifts: String = "",
    /** Монеты и рекорды в играх (видят друзья); -1 - человек скрыл. */
    val coins: Int = -1,
    /**
     * Рекорды: «s:монеты:уровень» (Змейка), «c:победы» (Шахматы), «d:», «m:», «r:», дальше (4.7.0) -
     * «gp:» (все счётчики игр) и «a:» (витрина достижений), см. GameStats.line. Пусто - скрыто.
     */
    val games: String = "",
) {
    val isEmpty: Boolean get() = username.isBlank() && bio.isBlank() && city.isBlank() && birthday.isBlank() && link.isBlank()

    fun toJson(): String = JSONObject()
        .put("u", username).put("b", bio).put("c", city).put("d", birthday).put("l", link)
        .put("n", name).put("s", status)
        .apply { if (id.isNotEmpty()) put("i", id) }
        .apply { if (emojiStatus.isNotEmpty()) put("es", emojiStatus) }
        .apply { if (gifts.isNotEmpty()) put("g", gifts) }
        .apply { if (coins >= 0) put("co", coins) }
        .apply { if (games.isNotEmpty()) put("gm", games) }
        .toString()

    companion object {
        fun fromJson(s: String?): ExtProfile? = try {
            if (s.isNullOrBlank()) {
                null
            } else {
                val o = JSONObject(s)
                ExtProfile(
                    o.optString("u").take(32), o.optString("b").take(MAX_BIO), o.optString("c").take(64),
                    o.optString("d").take(32), o.optString("l").take(200), o.optString("n").take(128), o.optString("s").take(1007),
                    o.optString("i").take(ContactIds.LENGTH),
                    o.optString("es").take(16),
                    o.optString("g").take(GIFTS_MAX),
                    o.optInt("co", -1).coerceIn(-1, 100_000_000),
                    // Раньше строка обрезалась до 80 знаков - теперь в ней вся статистика игр и
                    // витрина (прежние поля - в начале, их по-прежнему видят и старые версии).
                    o.optString("gm").take(GAMES_MAX),
                )
            }
        } catch (e: Exception) {
            null
        }

        const val MAX_BIO = 300

        /** Строка рекордов и статистики игр в карточке. */
        const val GAMES_MAX = 320

        /** Строка подарков в карточке (номер, эмодзи и от кого - до 10 подарков). */
        const val GIFTS_MAX = 400
    }
}

/**
 * Карточка профиля: при каждом подключении собеседника ему уходит служебное сообщение с именем,
 * статусом и расширенным профилем (со сквозным шифрованием, в чате не показывается).
 * Входящая карточка: если контакта ещё нет в базе (запрос только что принят) - он создаётся,
 * пустое имя заполняется. Так контакт больше не остаётся «Неизвестным пользователем».
 */
@Singleton
class ProfileCard @Inject constructor(
    private val context: Context,
    private val tox: Tox,
    private val contactRepository: ContactRepository,
    private val photos: ProfilePhotos,
    private val video: ProfileVideo,
) {
    private val prefs = context.getSharedPreferences("profile_card", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    var mine: ExtProfile
        get() = ExtProfile.fromJson(prefs.getString("mine", null)) ?: ExtProfile()
        set(v) {
            prefs.edit().putString("mine", v.toJson()).apply()
            _changes.value = _changes.value + 1
        }

    fun of(pk: String): ExtProfile? = ExtProfile.fromJson(prefs.getString("peer_$pk", null))

    /** Отправить свою карточку (собеседник должен быть в сети). */
    fun sendTo(pk: PublicKey) {
        // Друг в сети: досылаем переводы монет и сверяем ходы партий.
        runCatching { com.stoyko.smessenger.games.GameNet.onPeerOnline(context, pk.string()) }
        try {
            val selfId = runCatching { tox.toxId.string() }.getOrDefault("")
            val card = mine.copy(
                name = tox.getName(),
                status = tox.getStatusMessage(),
                id = selfId,
                gifts = com.stoyko.smessenger.chat.Gifts.recentEmojis(context),
                // Монеты и рекорды - если человек их не скрыл (настройка в «Играх»).
                coins = if (com.stoyko.smessenger.chat.Coins.shareStats(context)) com.stoyko.smessenger.chat.Coins.balance(context) else -1,
                games = if (com.stoyko.smessenger.chat.Coins.shareStats(context)) com.stoyko.smessenger.chat.Coins.statsLine(context) else "",
            )
            var json = card.toJson()
            // Одно служебное сообщение не должно делиться на части (лимит ядра - 1372 байта).
            if ((PREFIX + json).toByteArray().size > 1300) json = card.copy(status = card.status.take(200), bio = card.bio.take(150)).toJson()
            // Всё ещё длинно - статистика игр уходит в прежнем коротком виде (без счётчиков и витрины).
            if ((PREFIX + json).toByteArray().size > 1300) json = card.copy(status = card.status.take(200), bio = card.bio.take(150), games = card.games.substringBefore(";gp:")).toJson()
            tox.sendMessage(pk, PREFIX + json, MessageType.Normal)
            // Список фото профиля (недостающие друг попросит сам).
            photos.announce(pk)
            video.announce(pk)
        } catch (e: Exception) {
            Log.w(TAG, "send card: $e")
        }
    }

    /** true - это была карточка профиля (в чат не писать). Вызывается в потоке событий ядра. */
    fun onIncoming(pk: String, text: String): Boolean {
        if (photos.onIncoming(pk, text)) return true
        if (video.onIncoming(pk, text)) return true
        if (!text.startsWith(PREFIX)) return false
        val card = ExtProfile.fromJson(text.removePrefix(PREFIX)) ?: return true
        try {
            if (!contactRepository.exists(pk)) {
                contactRepository.add(Contact(pk, name = card.name, statusMessage = card.status.ifBlank { "..." }))
            } else {
                if (card.name.isNotBlank() && !ContactAlias.has(context, pk)) contactRepository.setName(pk, card.name)
                if (card.status.isNotBlank()) contactRepository.setStatusMessage(pk, card.status)
            }
        } catch (e: Exception) {
            Log.w(TAG, "apply card: $e")
        }
        if (card.id.isNotEmpty()) ContactIds.save(context, pk, card.id)
        prefs.edit().putString("peer_$pk", card.toJson()).apply()
        _changes.value = _changes.value + 1
        return true
    }

    fun forget(pk: String) {
        prefs.edit().remove("peer_$pk").apply()
        ContactIds.forget(context, pk)
    }

    /** Разослать карточку всем, кто в сети (например, после смены nospam - у всех будет новый ID). */
    suspend fun sendToOnline() {
        val contacts = runCatching { contactRepository.getAll().first() }.getOrDefault(emptyList())
        for (c in contacts) {
            if (c.connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None) sendTo(PublicKey(c.publicKey))
        }
    }

    companion object {
        private const val TAG = "ProfileCard"

        /** ⁣ (U+2063) + метка - как у остальных служебных сообщений (см. Markup). */
        const val PREFIX = "\u2063👤P:"

        fun isCard(text: String) = text.startsWith(PREFIX)

        /** Профиль собеседника без внедрения зависимостей (для экранов). */
        fun peer(context: Context, pk: String): ExtProfile? = ExtProfile.fromJson(
            context.getSharedPreferences("profile_card", Context.MODE_PRIVATE).getString("peer_$pk", null),
        )
    }
}
