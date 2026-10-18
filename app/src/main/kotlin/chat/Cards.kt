// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Карточки в чате:
 *  - «Контакт»: вы делитесь контактом, у собеседника в чате - карточка с именем и ID и
 *    две кнопки: «Добавить» и «Отклонить»;
 *  - «Приглашение в группу/канал»: раньше приглашения принимались молча, теперь в чате с
 *    пригласившим появляется карточка, и вы сами решаете - вступить или нет.
 */
object Cards {
    private const val CONTACT = "⁣👤C:"
    private const val INVITE = "⁣👥I:"
    /** Приглашение с описанием: тип (группа/канал), название, описание, ID группы. */
    private const val META = "⁣👥M:"
    /** Маленькая аватарка группы/канала к приглашению (отдельным служебным сообщением). */
    private const val AVATAR = "⁣👥A:"

    fun isCard(text: String) = text.startsWith(CONTACT) || text.startsWith(INVITE) || text.startsWith(META)
    fun isMeta(text: String) = text.startsWith(META)
    fun isInviteAvatar(text: String) = text.startsWith(AVATAR)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    /** Перерисовать карточки в открытых чатах (например, ответ на перевод монет). */
    fun notifyChanged() = changed()
    fun isContact(text: String) = text.startsWith(CONTACT)
    fun isInvite(text: String) = text.startsWith(INVITE)

    // ---------- Контакт ----------

    class ContactCard(val id: String, val name: String)

    fun contactMessage(fullId: String, name: String): String =
        CONTACT + JSONObject().put("i", fullId).put("n", name.take(128)).toString()

    fun parseContact(text: String): ContactCard? = runCatching {
        val o = JSONObject(text.removePrefix(CONTACT))
        val id = o.getString("i").trim()
        if (id.length != 76 || !id.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) null else ContactCard(id.uppercase(), o.optString("n"))
    }.getOrNull()

    // ---------- Приглашение в группу ----------

    class Invite(val id: String, val pk: String, val data: ByteArray, val name: String)

    private fun invites(ctx: Context) = ctx.applicationContext.getSharedPreferences("group_invites", Context.MODE_PRIVATE)

    /** Сохранить приглашение (данные приглашения нужны, чтобы вступить позже). Возвращает текст карточки. */
    fun saveInvite(ctx: Context, pk: String, data: ByteArray, name: String): String {
        val id = System.currentTimeMillis().toString(36) + (100..999).random()
        invites(ctx).edit().putString(
            id,
            JSONObject().put("pk", pk).put("d", Base64.encodeToString(data, Base64.NO_WRAP)).put("n", name).toString(),
        ).apply()
        return INVITE + id
    }

    fun invite(ctx: Context, text: String): Invite? = runCatching {
        val id = text.removePrefix(INVITE)
        val o = JSONObject(invites(ctx).getString(id, null) ?: return null)
        Invite(id, o.getString("pk"), Base64.decode(o.getString("d"), Base64.NO_WRAP), o.optString("n"))
    }.getOrNull()

    // ---------- Приглашение с описанием (новый формат) ----------

    class Meta(val chatId: String, val channel: Boolean, val name: String, val desc: String)

    /** Сообщения-приглашение для друга: карточка и (если есть) аватарка. */
    fun metaMessages(chatId: String, channel: Boolean, name: String, desc: String, avatarJpeg: ByteArray?): List<String> {
        var d = desc
        fun build() = META + JSONObject().put("c", chatId).put("k", if (channel) "c" else "g").put("n", name.take(64)).put("d", d).toString()
        // Одно служебное сообщение - до ~1300 байт: длинное описание укорачиваем.
        while (build().toByteArray().size > 1300 && d.isNotEmpty()) d = d.dropLast(maxOf(1, d.length / 8))
        val out = arrayListOf(build())
        avatarJpeg?.let { out += AVATAR + chatId + ":" + Base64.encodeToString(it, Base64.NO_WRAP) }
        return out
    }

    fun parseMeta(text: String): Meta? = runCatching {
        val o = JSONObject(text.removePrefix(META))
        val id = o.getString("c")
        if (id.length != 64) null else Meta(id.uppercase(), o.optString("k") == "c", o.optString("n"), o.optString("d"))
    }.getOrNull()

    private fun avatars(ctx: Context) = ctx.applicationContext.getSharedPreferences("invite_avatars", Context.MODE_PRIVATE)

    fun onInviteAvatar(ctx: Context, text: String) {
        val body = text.removePrefix(AVATAR)
        val id = body.substringBefore(':').uppercase()
        val b64 = body.substringAfter(':', "")
        if (id.length == 64 && b64.isNotEmpty()) avatars(ctx).edit().putString(id, b64).apply()
        changed()
    }

    fun inviteAvatar(ctx: Context, chatId: String): ByteArray? =
        avatars(ctx).getString(chatId.uppercase(), null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    /** Когда пришла карточка-описание от друга (чтобы не показывать вторую, старую карточку). */
    fun markMetaFrom(ctx: Context, pk: String) = invites(ctx).edit().putLong("meta_$pk", System.currentTimeMillis()).apply()
    fun recentMetaFrom(ctx: Context, pk: String): Boolean =
        System.currentTimeMillis() - invites(ctx).getLong("meta_$pk", 0L) < 5 * 60_000L

    /** Данные приглашения ядра (если пригласивший в сети - вступление через них надёжнее). */
    fun saveInviteData(ctx: Context, pk: String, data: ByteArray) =
        invites(ctx).edit().putString("data_$pk", Base64.encodeToString(data, Base64.NO_WRAP))
            .putLong("data_t_$pk", System.currentTimeMillis()).apply()

    fun inviteData(ctx: Context, pk: String): ByteArray? {
        val t = invites(ctx).getLong("data_t_$pk", 0L)
        if (System.currentTimeMillis() - t > 24 * 3600_000L) return null
        return invites(ctx).getString("data_$pk", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    }

    /** Маленькая JPEG-аватарка для приглашения (влезает в одно служебное сообщение). */
    fun thumb(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        return runCatching {
            val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            var size = 48
            var q = 60
            var res: ByteArray? = null
            while (res == null && size >= 24) {
                val bmp = android.graphics.Bitmap.createScaledBitmap(src, size, size, true)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out)
                val b = out.toByteArray()
                if (b.size <= 900) res = b else if (q > 35) q -= 10 else size -= 8
            }
            res
        }.getOrNull()
    }

    // ---------- Решение по карточке ----------

    private fun states(ctx: Context) = ctx.applicationContext.getSharedPreferences("card_states", Context.MODE_PRIVATE)

    fun keyFor(text: String, timestamp: Long) = Markup.key(text) + ":" + timestamp

    fun clearState(ctx: Context, key: String) = states(ctx).edit().remove(key).apply()

    /** "ok" - принято, "no" - отклонено, null - ещё не решали. */
    fun state(ctx: Context, key: String): String? = states(ctx).getString(key, null)
    fun setState(ctx: Context, key: String, v: String) = states(ctx).edit().putString(key, v).apply()

    /** Текст для уведомлений и списка чатов. */
    fun preview(ctx: Context, text: String): String? = when {
        isContact(text) -> "👤 " + (parseContact(text)?.name?.ifBlank { null } ?: ctx.getString(com.stoyko.smessenger.R.string.card_contact))
        isInvite(text) -> "👥 " + ctx.getString(com.stoyko.smessenger.R.string.card_invite_title, invite(ctx, text)?.name.orEmpty())
        isMeta(text) -> parseMeta(text)?.let { m ->
            (if (m.channel) "📢 " else "👥 ") + ctx.getString(
                if (m.channel) com.stoyko.smessenger.R.string.card_invite_channel_title else com.stoyko.smessenger.R.string.card_invite_title,
                m.name,
            )
        }
        Gifts.isGift(text) -> Gifts.preview(ctx, text)
        CoinGift.isGift(text) -> CoinGift.preview(ctx, text)
        com.stoyko.smessenger.games.GameInvite.isCard(text) -> com.stoyko.smessenger.games.GameInvite.preview(ctx, text)
        else -> null
    }
}
