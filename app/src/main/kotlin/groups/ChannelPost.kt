// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.groups

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Посты канала для бизнеса: служебный заголовок в начале текста поста (невидим в ленте,
 * уведомлениях и цитатах). В нём:
 *  - s: тихий пост (без звука и уведомления у подписчиков);
 *  - p: карточка товара/услуги - название, цена, валюта, описание, кнопка «Заказать»;
 *  - b: кнопка-ссылка под постом (текст + адрес: сайт, магазин, запись);
 *  - k: кнопки внутри сообщения - ряды цветных кнопок (см. [com.stoyko.smessenger.chat.MsgButtons]);
 *  - q: опрос (см. [com.stoyko.smessenger.chat.Polls]);
 *  - w: приветствие для новых участников группы (видно только им, см. [com.stoyko.smessenger.groups.Welcome]).
 * Формат: ⁣🏷{json}⁣текст поста.
 */
object ChannelPost {
    private const val INV = "⁣"
    private const val HEAD = "$INV🏷"

    /**
     * Товар/услуга. [oldPrice] - цена до скидки (зачёркнута, рядом «-N%»), [promo] - промокод
     * (копируется нажатием), [stock] - наличие: "in" в наличии, "pre" под заказ, "out" нет.
     */
    data class Product(
        val title: String,
        val price: String,
        val currency: String,
        val desc: String,
        val oldPrice: String = "",
        val promo: String = "",
        val stock: String = "",
    ) {
        /** Скидка в процентах (по ценам-числам) или 0. */
        val discount: Int
            get() {
                fun num(v: String) = v.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').toDoubleOrNull()
                val p = num(price) ?: return 0
                val o = num(oldPrice) ?: return 0
                if (o <= p || o <= 0) return 0
                return ((1 - p / o) * 100).toInt().coerceIn(1, 99)
            }
    }
    data class Button(val label: String, val url: String)
    data class Meta(
        val silent: Boolean = false,
        val product: Product? = null,
        val button: Button? = null,
        val buttons: List<List<com.stoyko.smessenger.chat.MsgButtons.B>> = emptyList(),
        val welcome: Boolean = false,
        val poll: com.stoyko.smessenger.chat.Polls.Poll? = null,
        /** HTML-пузырь: сжатый HTML (см. HtmlBubbles). */
        val html: String? = null,
        /** Живое сообщение из встроенной галереи (id шаблона). */
        val tpl: String? = null,
        /** Параметры живого сообщения (JSON: текст, числа, ссылки, картинки). */
        val tplParams: String? = null,
    ) {
        val isEmpty get() = !silent && product == null && button == null && buttons.isEmpty() && !welcome && poll == null && html == null && tpl == null
    }

    fun wrap(meta: Meta, text: String): String {
        if (meta.isEmpty) return text
        val o = JSONObject()
        if (meta.silent) o.put("s", 1)
        meta.product?.let {
            o.put(
                "p",
                JSONObject().put("t", it.title.take(80)).put("pr", it.price.take(24)).put("c", it.currency.take(6)).put("d", it.desc.take(300))
                    .apply {
                        if (it.oldPrice.isNotBlank()) put("op", it.oldPrice.take(24))
                        if (it.promo.isNotBlank()) put("pc", it.promo.take(32))
                        if (it.stock.isNotBlank()) put("av", it.stock.take(4))
                    },
            )
        }
        meta.button?.let { o.put("b", JSONObject().put("l", it.label.take(32)).put("u", it.url.take(300))) }
        if (meta.buttons.isNotEmpty()) o.put("k", com.stoyko.smessenger.chat.MsgButtons.toJson(meta.buttons))
        if (meta.welcome) o.put("w", 1)
        meta.poll?.let { o.put("q", com.stoyko.smessenger.chat.Polls.toJson(it)) }
        meta.html?.let { o.put("h", it) }
        meta.tpl?.let { o.put("ht", it.take(32)) }
        meta.tplParams?.let { o.put("hp", it) }
        // В JSON не должно быть невидимого разделителя (он закрывает заголовок).
        return HEAD + o.toString().replace(INV, "") + INV + text
    }

    /** (заголовок или null, текст без заголовка). */
    fun parse(raw: String): Pair<Meta?, String> {
        if (!raw.startsWith(HEAD)) return null to raw
        val end = raw.indexOf(INV, HEAD.length)
        if (end < 0) return null to raw
        val json = raw.substring(HEAD.length, end)
        val rest = raw.substring(end + INV.length)
        val meta = runCatching {
            val o = JSONObject(json)
            Meta(
                silent = o.optInt("s", 0) == 1,
                product = o.optJSONObject("p")?.let { p ->
                    Product(
                        p.optString("t"), p.optString("pr"), p.optString("c"), p.optString("d"),
                        p.optString("op"), p.optString("pc"), p.optString("av"),
                    ).takeIf { it.title.isNotBlank() }
                },
                button = o.optJSONObject("b")?.let { b ->
                    Button(b.optString("l"), b.optString("u")).takeIf { it.label.isNotBlank() && it.url.isNotBlank() }
                },
                buttons = com.stoyko.smessenger.chat.MsgButtons.fromJson(o.optJSONArray("k")),
                welcome = o.optInt("w", 0) == 1,
                poll = com.stoyko.smessenger.chat.Polls.fromJson(o.optJSONObject("q")),
                html = o.optString("h").takeIf { it.isNotEmpty() },
                tpl = o.optString("ht").takeIf { it.isNotEmpty() },
                tplParams = o.optString("hp").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
        return meta to rest
    }

    fun strip(raw: String): String = if (raw.startsWith(HEAD)) parse(raw).second else raw
    fun meta(raw: String): Meta? = if (raw.startsWith(HEAD)) parse(raw).first else null

    /** Ссылка для кнопки: без схемы - https://, телефон - tel:. */
    fun normalizeUrl(u: String): String {
        val t = u.trim()
        return when {
            t.isEmpty() -> t
            t.matches(Regex("^\\+?[0-9 ()-]{6,}$")) -> "tel:" + t.filter { it.isDigit() || it == '+' }
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t) -> t
            else -> "https://$t"
        }
    }
}

/**
 * Просмотры постов канала («👁 N»,): каждый подписчик, увидев пост, один раз
 * сообщает об этом всем (пакетом группы), и у всех считается число разных зрителей.
 * Хранится по каналу: uid поста → ключи зрителей.
 */
object ChannelStats {
    private const val MAX_VIEWERS = 5000
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("channel_views", Context.MODE_PRIVATE)

    /** Посты, которые я посмотрел, но о которых ещё не сообщил: chatId → uid. */
    private val outbox = HashMap<String, LinkedHashSet<String>>()

    private fun viewers(ctx: Context, chatId: String, uid: String): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet("$chatId|$uid", emptySet()) ?: emptySet())

    fun views(ctx: Context, chatId: String, uid: String): Int =
        if (uid.isBlank()) 0 else prefs(ctx).getStringSet("$chatId|$uid", emptySet())?.size ?: 0

    /** Пост показан на экране: учесть у себя и поставить в очередь на отправку. */
    fun seen(ctx: Context, chatId: String, uid: String, selfKey: String) {
        if (uid.isBlank() || selfKey.isBlank()) return
        val sent = prefs(ctx).getBoolean("me|$chatId|$uid", false)
        if (sent) return
        add(ctx, chatId, uid, selfKey)
        prefs(ctx).edit().putBoolean("me|$chatId|$uid", true).apply()
        synchronized(outbox) { outbox.getOrPut(chatId) { LinkedHashSet() }.add(uid) }
    }

    /** Забрать очередь: chatId → список uid (для пакета). */
    fun drain(): Map<String, List<String>> = synchronized(outbox) {
        val out = outbox.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() }
        outbox.clear()
        out
    }

    private fun add(ctx: Context, chatId: String, uid: String, viewer: String): Boolean {
        val set = viewers(ctx, chatId, uid)
        if (set.size >= MAX_VIEWERS || !set.add(viewer.take(16))) return false
        prefs(ctx).edit().putStringSet("$chatId|$uid", set).apply()
        return true
    }

    /** Пакет от подписчика: JSON-массив uid постов, которые он посмотрел. */
    fun onRemote(ctx: Context, chatId: String, viewer: String, json: String) {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return
        var any = false
        for (i in 0 until minOf(a.length(), 200)) {
            val uid = a.optString(i)
            if (uid.matches(Regex("[0-9a-f]{16}")) && add(ctx, chatId, uid, viewer)) any = true
        }
        if (any) changed()
    }

    fun toJson(uids: List<String>): String = JSONArray(uids.take(100)).toString()

    /** Короткая запись числа: 950, 1,2K, 15K, 1,1M. */
    fun short(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 10_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1000f)
        n < 1_000_000 -> "${n / 1000}K"
        else -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f)
    }
}
