// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.Kinetics

/**
 * Подарки: 3000 живых подарков трёх редкостей ([GiftCatalog]).
 *
 * Как работает:
 *  - вы выбираете подарок и пишете подпись - в чате у собеседника появляется карточка:
 *    подарок с бесконечной анимацией, ваша подпись и кнопки «Принять» / «Отклонить»;
 *  - принятый подарок попадает в профиль получателя (раздел «Подарки», его видят друзья);
 *  - отправитель видит в своей карточке, принят подарок или отклонён.
 * Всё ходит служебными сообщениями со сквозным шифрованием.
 */
object Gifts {
    private const val PREFIX = "⁣🎁G:"
    private const val STATUS = "⁣🎁S:"
    private const val PREFS = "gifts"
    private const val MAX_STORED = 120

    /**
     * Принятый подарок. [sign] - подпись дарителя (подписанный подарок: имя/фраза, которую
     * он поставил при отправке; подлинность подтверждается ключом [from] - подарок пришёл
     * по сквозному шифрованию именно с этого ключа). [comment] - мой собственный комментарий
     * к подарку (виден на витрине).
     */
    data class Gift(
        val id: Int,
        val emoji: String,
        val from: String,
        val fromName: String,
        val note: String,
        val at: Long,
        val sign: String = "",
        val comment: String = "",
    )

    /** Подарок из сообщения в чате. */
    class Offer(val uid: String, val gift: GiftCatalog.Gift, val note: String, val sign: String = "")

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun nameOf(emoji: String): String = GiftCatalog.byEmoji(emoji)?.name() ?: emoji

    fun isGift(text: String) = text.startsWith(PREFIX)
    fun isStatus(text: String) = text.startsWith(STATUS)

    // ---------------- хранение ----------------

    /** Принятые мной подарки (новые - первыми). */
    fun received(ctx: Context): List<Gift> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("in", "[]"))
        val all = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val e = o.optString("e")
            val id = o.optInt("g", GiftCatalog.byEmoji(e)?.id ?: 0)
            Gift(id, e, o.optString("f"), o.optString("n"), o.optString("m"), o.optLong("t"), o.optString("s"), o.optString("c"))
        }
        // Все подарки - и картинки, и эмодзи, и нарисованные (прежние снова показываются).
        GiftCatalog.init(ctx)
        all
    }.getOrDefault(emptyList())

    /** Подарок самому себе (куплен за монеты) - сразу в «Мои подарки». */
    fun giveSelf(ctx: Context, gift: GiftCatalog.Gift, note: String) {
        store(ctx, Gift(gift.id, gift.emoji, "", ctx.getString(R.string.gift_from_me), note, System.currentTimeMillis()))
        changed()
    }

    private fun store(ctx: Context, g: Gift) {
        saveAll(ctx, (listOf(g) + received(ctx)).take(MAX_STORED))
    }

    private fun saveAll(ctx: Context, list: List<Gift>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("g", it.id).put("e", it.emoji).put("f", it.from).put("n", it.fromName).put("m", it.note).put("t", it.at)
                    .apply {
                        if (it.sign.isNotBlank()) put("s", it.sign)
                        if (it.comment.isNotBlank()) put("c", it.comment)
                    },
            )
        }
        prefs(ctx).edit().putString("in", arr.toString()).apply()
    }

    // ---------------- витрина профиля ----------------
    // Все принятые подарки лежат в галерее «Мои подарки»; в профиле (и у друзей) видны только те,
    // что вы сами поставили на витрину, - не больше 12.

    const val SHOWCASE_MAX = 12

    private fun keyOf(g: Gift) = g.at.toString()

    private fun showcaseKeys(ctx: Context): List<String> =
        prefs(ctx).getString("show", "").orEmpty().split(',').filter { it.isNotBlank() }

    /** Подарки на витрине - в том порядке, в каком вы их поставили. */
    fun showcase(ctx: Context): List<Gift> {
        val byKey = received(ctx).associateBy { keyOf(it) }
        return showcaseKeys(ctx).mapNotNull { byKey[it] }.take(SHOWCASE_MAX)
    }

    fun inShowcase(ctx: Context, g: Gift): Boolean = keyOf(g) in showcaseKeys(ctx)

    /** Поставить на витрину / убрать. false - витрина уже заполнена (12 подарков). */
    fun toggleShowcase(ctx: Context, g: Gift): Boolean {
        val keys = showcaseKeys(ctx).filter { k -> received(ctx).any { keyOf(it) == k } }.toMutableList()
        val k = keyOf(g)
        if (k in keys) {
            keys.remove(k)
        } else {
            if (keys.size >= SHOWCASE_MAX) return false
            keys.add(k)
        }
        prefs(ctx).edit().putString("show", keys.joinToString(",")).apply()
        changed()
        return true
    }

    /**
     * Подарки витрины - для карточки профиля (их видят друзья): «G:номер|эмодзи|от кого,…».
     * Эмодзи передаётся вместе с номером: так у друзей подарок выглядит как настоящий,
     * а старые версии (3.3.0) находят по эмодзи хотя бы сам подарок.
     */
    fun recentEmojis(ctx: Context, n: Int = SHOWCASE_MAX): String {
        val sb = StringBuilder("G:")
        // Друзья видят только витрину (до 12 подарков, которые вы выбрали сами).
        for (it in showcase(ctx).take(maxOf(n, SHOWCASE_MAX))) {
            if (it.id <= 0 && it.emoji.isBlank()) continue
            val part = it.id.toString() + "|" + it.emoji.replace(Regex("[,|]"), "") + "|" +
                it.fromName.take(12).replace(Regex("[,|~\\n]"), " ").trim()
            // Не больше, чем примет карточка профиля - иначе последний подарок обрезался бы.
            if (sb.length + part.length + 1 > com.stoyko.smessenger.profile.ExtProfile.GIFTS_MAX) break
            if (sb.length > 2) sb.append(',')
            sb.append(part)
        }
        return sb.toString()
    }

    /** Решение по подарку: у получателя - его ответ, у отправителя - ответ друга. "ok" / "no" / null. */
    fun state(ctx: Context, uid: String): String? = prefs(ctx).getString("s_$uid", null)
    private fun setState(ctx: Context, uid: String, v: String) = prefs(ctx).edit().putString("s_$uid", v).apply()

    // ---------------- подписанные подарки ----------------

    const val SIGN_MAX = 40
    const val COMMENT_MAX = 120

    /** Подпись, которую я ставлю на подарки (пусто - подарки без подписи). */
    fun mySign(ctx: Context): String = if (prefs(ctx).getBoolean("sign_on", false)) prefs(ctx).getString("sign_text", "").orEmpty() else ""

    private fun saveSign(ctx: Context, on: Boolean, text: String) =
        prefs(ctx).edit().putBoolean("sign_on", on).putString("sign_text", text.take(SIGN_MAX)).apply()

    /** Короткий отпечаток ключа дарителя: «A1B2…C3D4» - сверить подлинность подписи. */
    fun keyPrint(pk: String): String = if (pk.length < 12) pk.uppercase() else (pk.take(4) + "…" + pk.takeLast(4)).uppercase()

    /** Строка подписи для показа: «✍ Подпись · ✓ A1B2…C3D4». */
    fun signLine(ctx: Context, sign: String, fromPk: String): String =
        "✍ " + sign + if (fromPk.isNotBlank()) "  ·  " + ctx.getString(R.string.gift_sign_verified, keyPrint(fromPk)) else ""

    /** Мой комментарий к принятому подарку (виден на витрине). */
    fun setComment(ctx: Context, g: Gift, text: String) {
        val list = received(ctx).map { if (it.at == g.at && it.emoji == g.emoji) it.copy(comment = text.trim().take(COMMENT_MAX)) else it }
        saveAll(ctx, list)
        changed()
    }

    /** Подарок из моего списка (а не с чужой витрины) - его можно прокомментировать. */
    private fun mine(ctx: Context, g: Gift): Gift? = received(ctx).firstOrNull { it.at == g.at && it.emoji == g.emoji }

    // ---------------- сообщения ----------------

    /** Текст сообщения-подарка (его же сохраняем у себя в чате). */
    fun message(gift: GiftCatalog.Gift, note: String, sign: String = ""): String =
        PREFIX + JSONObject()
            .put("g", gift.id)
            .put("e", gift.emoji)
            .put("m", note.take(200))
            .put("u", System.currentTimeMillis().toString(36) + (1000..9999).random())
            .apply { if (sign.isNotBlank()) put("s", sign.take(SIGN_MAX)) }
            .toString()

    fun parse(text: String): Offer? = runCatching {
        val o = JSONObject(text.removePrefix(PREFIX))
        // Номер + эмодзи: подарок выглядит так же, как когда его дарили (даже если каталог менялся).
        val g = GiftCatalog.resolve(o.optInt("g"), o.optString("e")) ?: GiftCatalog.all.lastOrNull() ?: return null
        Offer(o.optString("u").ifBlank { o.optString("t") }, g, o.optString("m").take(200), o.optString("s").take(SIGN_MAX))
    }.getOrNull()

    /** Отправить подарок. Возвращает текст для своего чата или null (друг не в сети). */
    fun send(tox: Tox, to: String, gift: GiftCatalog.Gift, note: String, sign: String = ""): String? = runCatching {
        val text = message(gift, note, sign)
        tox.sendMessage(PublicKey(to), text, MessageType.Normal)
        text
    }.getOrNull()

    /** Принять или отклонить подарок из чата. */
    fun respond(ctx: Context, tox: Tox, fromPk: String, fromName: String, text: String, accept: Boolean) {
        val offer = parse(text) ?: return
        setState(ctx, offer.uid, if (accept) "ok" else "no")
        if (accept) {
            store(ctx, Gift(offer.gift.id, offer.gift.emoji, fromPk, fromName, offer.note, System.currentTimeMillis(), offer.sign))
        }
        runCatching {
            tox.sendMessage(PublicKey(fromPk), STATUS + JSONObject().put("u", offer.uid).put("s", if (accept) "ok" else "no"), MessageType.Normal)
        }
        changed()
    }

    /** Ответ друга на мой подарок. */
    fun onStatus(ctx: Context, text: String) {
        runCatching {
            val o = JSONObject(text.removePrefix(STATUS))
            setState(ctx, o.getString("u"), if (o.optString("s") == "ok") "ok" else "no")
        }
        changed()
    }

    /** Текст для уведомлений. */
    fun preview(ctx: Context, text: String): String? {
        GiftCatalog.init(ctx)
        val o = parse(text) ?: return null
        return ctx.getString(R.string.gift_notification, o.gift.emoji + " " + o.gift.name()) +
            if (o.note.isNotBlank()) "\n«${o.note}»" else ""
    }

    // ---------------- карточка в чате ----------------

    /**
     * Подарок в чате: анимация без конца, название и редкость, подпись отправителя и
     * (у получателя) кнопки «Принять» / «Отклонить».
     */
    fun chatCard(ctx: Context, text: String, received: Boolean, onAnswer: (Boolean) -> Unit): View {
        GiftCatalog.init(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val offer = parse(text)
        val gift = offer?.gift ?: GiftCatalog.all.lastOrNull() ?: return View(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blend(gift.colors[1], 0xFF1E1B4B.toInt()), 0xFF1E1B4B.toInt()),
            ).apply { cornerRadius = 22 * d }
            elevation = 3 * d
            layoutParams = FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(
            TextView(ctx).apply {
                this.text = ctx.getString(if (received) R.string.gift_card_in else R.string.gift_card_out)
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
                paint.isFakeBoldText = true
            },
        )
        val art = GiftArt(ctx, gift)
        root.addView(art, LinearLayout.LayoutParams(dp(132), dp(132)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        root.addView(
            TextView(ctx).apply {
                this.text = gift.name()
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            },
        )
        root.addView(rarityBadge(ctx, gift.rarity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { topMargin = dp(6) })
        if (!offer?.note.isNullOrBlank()) {
            root.addView(
                TextView(ctx).apply {
                    this.text = "«" + offer!!.note + "»"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(0xF0FFFFFF.toInt())
                    setPadding(dp(4), dp(10), dp(4), 0)
                },
            )
        }
        if (!offer?.sign.isNullOrBlank()) {
            root.addView(
                TextView(ctx).apply {
                    // Подписанный подарок: подпись дарителя; у получателя - с отпечатком ключа отправителя.
                    this.text = "✍ " + offer!!.sign
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC))
                    setTextColor(0xFFFDE68A.toInt())
                    setPadding(dp(4), dp(6), dp(4), 0)
                },
            )
        }
        val st = offer?.let { state(ctx, it.uid) }
        if (received && st == null && offer != null && Kinetics.enabled) {
            // Неоткрытый подарок мягко «подпрыгивает» (без перекоса и наклона) - зовёт нажать «Принять».
            val lift = -6 * d
            art.post {
                art.animate().scaleX(1.06f).scaleY(1.06f).translationY(lift).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                        art.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(420)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                    }.start()
            }
        }
        if (received && st == null && offer != null) {
            val row = Btn.row(
                ctx,
                listOf(
                    Btn.tonal(ctx, ctx.getString(R.string.card_decline), Color.WHITE) { onAnswer(false) },
                    Btn.primary(ctx, ctx.getString(R.string.gift_accept), intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) { onAnswer(true) },
                ),
                height = 42,
            )
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        } else {
            root.addView(
                TextView(ctx).apply {
                    this.text = ctx.getString(
                        when {
                            st == "ok" -> if (received) R.string.gift_accepted_me else R.string.gift_accepted_peer
                            st == "no" -> if (received) R.string.gift_declined_me else R.string.gift_declined_peer
                            else -> R.string.gift_waiting
                        },
                    )
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(if (st == "ok") 0xFF86EFAC.toInt() else 0xCCFFFFFF.toInt())
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }
        return root
    }

    internal fun blend(a: Int, b: Int): Int {
        fun ch(c: Int, s: Int) = c shr s and 0xFF
        return (0xFF shl 24) or
            (((ch(a, 16) + ch(b, 16)) / 2) shl 16) or
            (((ch(a, 8) + ch(b, 8)) / 2) shl 8) or
            ((ch(a, 0) + ch(b, 0)) / 2)
    }

    internal fun rarityBadge(ctx: Context, r: GiftCatalog.Rarity): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = when (r) {
                GiftCatalog.Rarity.COMMON -> "●  "
                GiftCatalog.Rarity.RARE -> "◆  "
                GiftCatalog.Rarity.LEGENDARY -> "★  "
            } + GiftCatalog.rarityName(r)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 11 * d
                setColor(r.color)
            }
        }
    }

    // ---------------- выбор подарка ----------------

    /** Выбор подарка: вкладки по редкости, поиск, предпросмотр с анимацией и подпись. */
    /**
     * Выбор подарка. Подарки покупаются за монеты 🪙 (зарабатываются в «Змейке»):
     * обычные 5-10, редкие 10-20, легендарные 20-30. [self] - покупка себе.
     */
    fun pick(ctx: Context, toName: String, self: Boolean = false, onSend: (gift: GiftCatalog.Gift, note: String) -> Unit) {
        GiftCatalog.init(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(14), dp(12), dp(14), dp(6))
        }
        root.addView(
            TextView(ctx).apply {
                text = if (self) ctx.getString(R.string.gift_shop_self_title) else ctx.getString(R.string.gift_title, toName)
                textSize = 18f
                setTextColor(cText)
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
            },
        )
        // Кошелёк: сколько монет и где их взять.
        val wallet = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFD97706.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { com.stoyko.smessenger.games.SnakeGame.show(ctx) }
        }
        fun refreshWallet() {
            wallet.text = ctx.getString(R.string.coins_wallet, Coins.balance(ctx))
        }
        refreshWallet()
        // Предпросмотр выбранного подарка.
        val preview = FrameLayout(ctx)
        val previewName = TextView(ctx).apply {
            textSize = 15f
            setTextColor(cText)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        val previewBadge = FrameLayout(ctx)
        // Невысокий экран (например, 720×1480): предпросмотр меньше и сбоку от названия -
        // сетке подарков остаётся место.
        val compact = ctx.resources.displayMetrics.heightPixels / d < 780
        // Предпросмотр всегда сбоку от названия - сетке подарков больше места.
        val pv = if (compact) 68 else 84
        run {
            previewName.gravity = Gravity.START
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(preview, LinearLayout.LayoutParams(dp(pv), dp(pv)))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(previewName)
                addView(previewBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { topMargin = dp(4) })
                // Кошелёк - под названием, а не отдельной строкой.
                wallet.gravity = Gravity.START
                addView(wallet)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // Каталог не прочитался (повреждён APK) - не падаем, просто не открываем выбор.
        var chosen: GiftCatalog.Gift = GiftCatalog.of(GiftCatalog.Rarity.COMMON).firstOrNull() ?: return
        var sendLabel: () -> Unit = {}
        fun showPreview() {
            preview.removeAllViews()
            preview.addView(GiftArt(ctx, chosen), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            previewName.text = chosen.name()
            previewBadge.removeAllViews()
            previewBadge.addView(rarityBadge(ctx, chosen.rarity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)))
            preview.scaleX = 0.8f
            preview.scaleY = 0.8f
            preview.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
            sendLabel()
        }

        // Вкладки по редкости.
        var rarity = GiftCatalog.Rarity.COMMON
        var query = ""
        // Тематическая категория (null - все): любовь, магия, сокровища, существа, космос…
        var cat: GiftCatalog.Cat? = null
        var items: List<GiftCatalog.Gift> = GiftCatalog.of(rarity)
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(0x1A5B4BDB)
            }
        }
        val tabViews = ArrayList<TextView>()
        val grid = GridView(ctx).apply {
            // Планшет / альбомная ориентация - больше колонок, ячейки привычного размера.
            numColumns = com.stoyko.smessenger.ui.Adaptive.columns(ctx, max = 6)
            verticalSpacing = dp(8)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val g = items[position]
                val old = convertView as? LinearLayout
                if (old != null && (old.tag as? Int) == g.id) {
                    markCell(old, g.id == chosen.id, d)
                    return old
                }
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    tag = g.id
                    setPadding(dp(3), dp(3), dp(3), dp(4))
                }
                // В сетке анимирован фон-эффект, а сам эмодзи оживает в предпросмотре и в чате.
                cell.addView(GiftArt(ctx, g, playEmoji = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))
                cell.addView(
                    TextView(ctx).apply {
                        text = g.shortName()
                        textSize = 10f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setTextColor(cSub)
                        setPadding(0, dp(3), 0, 0)
                    },
                )
                // Цена в монетах.
                cell.addView(
                    TextView(ctx).apply {
                        text = "🪙 " + Coins.price(g)
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(0xFFD97706.toInt())
                        setTypeface(Typeface.DEFAULT_BOLD)
                    },
                )
                markCell(cell, g.id == chosen.id, d)
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, view, position, _ ->
            chosen = items[position]
            view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(90).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
            adapter.notifyDataSetChanged()
            showPreview()
        }
        val empty = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(cSub)
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = View.GONE
        }
        fun ofKind(r: GiftCatalog.Rarity) = GiftCatalog.of(r).let { l ->
            val c = cat
            l.filter { (c == null || GiftCatalog.catOf(it) == c) }
        }
        var markTabsRef: (() -> Unit)? = null
        fun refill() {
            // Вид выбран, а в этой редкости таких нет - переключаемся на редкость, где они есть.
            if (cat != null && ofKind(rarity).isEmpty()) {
                GiftCatalog.Rarity.values().firstOrNull { ofKind(it).isNotEmpty() }?.let { rarity = it }
            }
            val all = ofKind(rarity)
            items = if (query.isBlank()) all else all.filter { it.name().contains(query, ignoreCase = true) || it.emoji == query }
            // Новый список - новые ячейки (раньше старые ячейки иногда оставались от прежнего вида).
            grid.adapter = adapter
            adapter.notifyDataSetChanged()
            grid.setSelection(0)
            empty.text = ctx.getString(R.string.gift_none_found)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            markTabsRef?.invoke()
        }
        fun markTabs() {
            tabViews.forEach { tv ->
                val on = tv.tag == rarity
                val r = tv.tag as GiftCatalog.Rarity
                // Число - сколько подарков выбранного вида в этой редкости.
                tv.text = GiftCatalog.rarityName(r) + " · " + ofKind(r).size
                tv.setTextColor(if (on) Color.WHITE else r.color)
                tv.background = if (on) GradientDrawable().apply { cornerRadius = 17 * d; setColor(r.color) } else null
            }
        }
        markTabsRef = { markTabs() }
        for (r in GiftCatalog.Rarity.values()) {
            val tv = TextView(ctx).apply {
                tag = r
                text = GiftCatalog.rarityName(r) + " · " + GiftCatalog.of(r).size
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setTypeface(Typeface.DEFAULT_BOLD)
                setOnClickListener {
                    rarity = r
                    markTabs()
                    refill()
                }
            }
            Kinetics.squish(tv, 0.92f)
            tabViews += tv
            tabs.addView(tv, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        markTabs()
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        // Категории - лентой «пилюль» с иконкой и числом; лента прокручивается пальцем.
        val catRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(6), 0)
        }
        val catViews = ArrayList<TextView>()
        fun catCount(c: GiftCatalog.Cat?): Int = GiftCatalog.Rarity.values().sumOf { r ->
            GiftCatalog.of(r).count { (c == null || GiftCatalog.catOf(it) == c) }
        }
        fun markCats() = catViews.forEach { tv ->
            val c = tv.tag as? GiftCatalog.Cat
            val on = c == cat
            val n = catCount(c)
            tv.text = (c?.emoji ?: "✨") + " " + (c?.title() ?: ctx.getString(R.string.gift_kind_all)) + " · " + n
            tv.visibility = if (n == 0 && c != null && !on) View.GONE else View.VISIBLE
            tv.setTextColor(if (on) Color.WHITE else cText)
            tv.background = GradientDrawable().apply {
                cornerRadius = 15 * d
                if (on) {
                    colors = intArrayOf(0xFF7C6CF6.toInt(), 0xFFDB2777.toInt())
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                } else {
                    setColor(0x145B4BDB)
                    setStroke((1 * d).toInt(), 0x335B4BDB)
                }
            }
        }
        (listOf<GiftCatalog.Cat?>(null) + GiftCatalog.Cat.values().toList()).forEachIndexed { i, c ->
            val tv = TextView(ctx).apply {
                tag = c
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { v ->
                    cat = c
                    markCats()
                    refill()
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                    }.start()
                }
            }
            catViews += tv
            catRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { if (i > 0) marginStart = dp(6) })
        }
        markCats()
        root.addView(
            android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(catRow)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        val search = EditText(ctx).apply {
            setHint(R.string.gift_search)
            setSingleLine()
            textSize = 14f
            setTextColor(cText)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    refill()
                }
            })
        }
        // Поиск и «🎲 Случайный» - в одну строку.
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dice = TextView(ctx).apply {
            text = "🎲"
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = ctx.getString(R.string.gift_random)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setOnClickListener {
                if (items.isEmpty()) return@setOnClickListener
                val pos = items.indices.random()
                chosen = items[pos]
                adapter.notifyDataSetChanged()
                grid.smoothScrollToPositionFromTop(pos, dp(40), 300)
                showPreview()
                animate().rotationBy(360f).setDuration(420).start()
            }
        }
        Kinetics.squish(dice, 0.85f)
        searchRow.addView(dice, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginStart = dp(8) })
        // Строка поиска уезжает вверх, когда листаешь подарки вниз, и возвращается при
        // прокрутке вверх - сетке остаётся больше места.
        val searchBox = FrameLayout(ctx).apply { clipChildren = true }
        searchBox.addView(searchRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { topMargin = dp(8) })
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var searchShown = true
        var searchAnim: android.animation.ValueAnimator? = null
        fun showSearch(show: Boolean) {
            if (show == searchShown) return
            // Пока ищешь (есть текст или фокус) - строка не прячется.
            if (!show && (search.hasFocus() || query.isNotEmpty())) return
            searchShown = show
            val full = dp(48)
            val from = searchBox.height.takeIf { it > 0 || !show } ?: 0
            val to = if (show) full else 0
            searchAnim?.cancel()
            searchAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    val h = it.animatedValue as Int
                    searchBox.layoutParams = searchBox.layoutParams.apply { height = h }
                    searchRow.alpha = h / full.toFloat()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (show) searchBox.layoutParams = searchBox.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    }
                })
                start()
            }
        }
        var lastOffset = 0
        grid.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) = Unit
            override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                val c0 = v.getChildAt(0) ?: return
                val rowH = c0.height + grid.verticalSpacing
                val off = (first / grid.numColumns.coerceAtLeast(1)) * rowH - c0.top
                val dy = off - lastOffset
                lastOffset = off
                when {
                    off <= dp(4) -> showSearch(true)
                    dy > dp(6) -> showSearch(false)
                    dy < -dp(6) -> showSearch(true)
                }
            }
        })
        // Сетка выше на больших экранах - больше подарков видно сразу.
        // Сетка забирает всё свободное место окна (вес 1) - кнопка «Подарить/Купить» внизу
        // всегда видна и не уезжает под кнопки телефона.
        root.addView(FrameLayout(ctx).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        val note = EditText(ctx).apply {
            setHint(R.string.gift_note_hint)
            maxLines = 3
            textSize = 14f
            setTextColor(cText)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        // Записка и подпись свёрнуты в одну строку - не отнимают место у сетки подарков.
        val extras = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        // Кнопка «✍» рядом с «Подарить» раскрывает записку и подпись.
        val extrasToggle = TextView(ctx).apply {
            text = "✍"
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = ctx.getString(if (self) R.string.gift_extras_note else R.string.gift_extras_toggle)
            setTextColor(0xFF7B6CF6.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setOnClickListener {
                val open = extras.visibility != View.VISIBLE
                extras.visibility = if (open) View.VISIBLE else View.GONE
                (background as? GradientDrawable)?.setColor(if (open) 0x335B4BDB else ContextCompat.getColor(ctx, R.color.dialogItem))
                if (open) {
                    extras.alpha = 0f
                    extras.animate().alpha(1f).setDuration(200).start()
                    showSearch(false)
                }
            }
        }
        Kinetics.squish(extrasToggle, 0.88f)
        root.addView(extras)
        extras.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        // Подписанный подарок: своя подпись (имя или фраза) - получатель увидит её с отметкой подлинности.
        val signOn = android.widget.CheckBox(ctx).apply {
            setText(R.string.gift_sign_toggle)
            setTextColor(cText)
            isChecked = prefs(ctx).getBoolean("sign_on", false)
            visibility = if (self) View.GONE else View.VISIBLE
        }
        val signText = EditText(ctx).apply {
            setHint(R.string.gift_sign_hint)
            setText(prefs(ctx).getString("sign_text", "").orEmpty())
            setSingleLine()
            textSize = 14f
            setTextColor(cText)
            filters = arrayOf(android.text.InputFilter.LengthFilter(SIGN_MAX))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(ContextCompat.getColor(ctx, R.color.dialogItem))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = if (!self && signOn.isChecked) View.VISIBLE else View.GONE
        }
        signOn.setOnCheckedChangeListener { _, on -> signText.visibility = if (on) View.VISIBLE else View.GONE }
        extras.addView(signOn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        extras.addView(signText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(extrasToggle, LinearLayout.LayoutParams(dp(44), dp(44)))
        val sendBtn =
            Btn.primary(ctx, ctx.getString(if (self) R.string.gift_buy_self else R.string.gift_send), intArrayOf(0xFFFF6B9D.toInt(), 0xFF7B6CF6.toInt())) {
                val price = Coins.price(chosen)
                if (!Coins.spend(ctx, price)) {
                    // Не хватает монет - предлагаем сыграть.
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.coins_not_enough_title)
                        .setMessage(ctx.getString(R.string.coins_not_enough, price, Coins.balance(ctx)))
                        .setPositiveButton(R.string.snake_play) { _, _ -> com.stoyko.smessenger.games.SnakeGame.show(ctx) }
                        .setNegativeButton(R.string.close, null)
                        .show()
                    return@primary
                }
                refreshWallet()
                sheet.dismiss()
                if (!self) saveSign(ctx, signOn.isChecked && signText.text.isNotBlank(), signText.text.toString().trim())
                onSend(chosen, note.text.toString().trim())
            }
        sendBtn.textSize = 15f
        bottomRow.addView(sendBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        root.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        // На кнопке сразу видна цена выбранного подарка.
        sendLabel = {
            sendBtn.text = ctx.getString(if (self) R.string.gift_buy_self else R.string.gift_send) + "  ·  🪙 " + Coins.price(chosen)
        }
        showPreview()
        // Окно фиксированной высоты (88% экрана), кнопка - над панелью кнопок телефона.
        val sheetH = (ctx.resources.displayMetrics.heightPixels * (if (compact) 0.93f else 0.88f)).toInt()
        sheet.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetH))
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        // Отступ снизу - ровно на ту часть окна, что реально закрыта панелью кнопок или
        // клавиатурой. Раньше отступ добавлялся дважды (окно уже стояло над панелью) - под
        // кнопкой «Подарить» оставалась пустая полоса.
        val basePad = root.paddingBottom
        var navBottom = 0
        fun fitBottom() {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val screenH = root.rootView.height.takeIf { it > 0 } ?: return
            val visibleBottom = screenH - navBottom
            val overlap = (loc[1] + root.height - visibleBottom).coerceIn(0, navBottom)
            val want = basePad + overlap
            if (root.paddingBottom != want) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, want)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.post { fitBottom() }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { fitBottom() } }
        sheet.behavior.isDraggable = false
        sheet.behavior.skipCollapsed = true
        sheet.behavior.peekHeight = sheetH
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
    }

    private fun markCell(cell: View, on: Boolean, d: Float) {
        cell.background = if (on) {
            GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x225B4BDB)
                setStroke((2 * d).toInt(), 0xFF7B6CF6.toInt())
            }
        } else {
            null
        }
    }

    /** Праздничная анимация по эмодзи (старый вызов): ищет подарок и показывает полную анимацию. */
    fun celebrate(anchor: View, emoji: String) {
        val g = GiftCatalog.byEmoji(emoji)
        if (g != null) {
            celebrate(anchor, g)
        } else {
            val root = anchor.rootView as? ViewGroup ?: return
            QuickReaction.play(root, root.width / 2f, root.height / 2.4f, emoji)
            Kinetics.burst(anchor)
        }
    }

    /**
     * Подарок принят: экран мягко темнеет, за подарком вращаются золотые лучи и светится ореол,
     * сам подарок «выпрыгивает» с пружинкой и покачивается, вокруг взрывается конфетти,
     * снизу появляется «Подарок ваш!». Через 2,5 с (или по касанию) всё плавно исчезает.
     */
    fun celebrate(anchor: View, gift: GiftCatalog.Gift) {
        val root = anchor.rootView as? FrameLayout ?: return
        val ctx = root.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            elevation = 40 * d
        }
        val rays = CelebrateFx(ctx, gift.colors[0])
        overlay.addView(rays, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val art = GiftArt(ctx, gift)
        overlay.addView(art, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER).apply { bottomMargin = dp(60) })
        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.gift_yours, gift.name())
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setShadowLayer(10 * d, 0f, 2 * d, 0xAA000000.toInt())
        }
        overlay.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(170) })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        runCatching { anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
        // «Дзынь»: колокольчик, у редких и легендарных - с россыпью звонких искр.
        GiftSound.play(ctx, gift.rarity)

        var closed = false
        fun close() {
            if (closed) return
            closed = true
            overlay.animate().alpha(0f).setDuration(320).withEndAction { runCatching { root.removeView(overlay) } }.start()
        }
        overlay.setOnClickListener { close() }
        // Тёмный фон проявляется.
        android.animation.ValueAnimator.ofInt(0, 0x99).apply {
            duration = 300
            addUpdateListener { overlay.setBackgroundColor((it.animatedValue as Int) shl 24) }
            start()
        }
        // Подарок ровно «выплывает» с лёгкой пружинкой и потом плавно парит - без наклонов и рывков.
        art.scaleX = 0.3f
        art.scaleY = 0.3f
        art.alpha = 0f
        art.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(620)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).withEndAction {
                if (!closed) {
                    art.animate().translationY(-8 * d).setDuration(700)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction {
                            if (!closed) {
                                art.animate().translationY(0f).setDuration(700)
                                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
                            }
                        }.start()
                }
            }.start()
        title.alpha = 0f
        title.translationY = 30 * d
        title.animate().alpha(1f).translationY(0f).setStartDelay(380).setDuration(420).start()
        rays.start()
        overlay.postDelayed({ close() }, 2600)
    }

    /** Подарок отправлен: он улетает вверх и тает - в профиль друга он попадёт, только когда тот примет. */
    fun sendAway(anchor: View, gift: GiftCatalog.Gift) {
        val root = anchor.rootView as? FrameLayout ?: return
        val ctx = root.context
        val d = ctx.resources.displayMetrics.density
        val size = (110 * d).toInt()
        val art = GiftArt(ctx, gift)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        art.x = loc[0] + anchor.width / 2f - size / 2f
        art.y = loc[1] + anchor.height / 2f - size / 2f
        art.elevation = 40 * d
        root.addView(art, FrameLayout.LayoutParams(size, size))
        art.scaleX = 0.4f
        art.scaleY = 0.4f
        art.animate().scaleX(1.1f).scaleY(1.1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).withEndAction {
                art.animate().translationYBy(-root.height * 0.45f).scaleX(0.3f).scaleY(0.3f).alpha(0f).rotation(20f)
                    .setDuration(700).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction { runCatching { root.removeView(art) } }.start()
            }.start()
        Kinetics.burst(anchor)
    }

    /**
     * Раздел «Подарки» в профиле: сетка живых подарков; нажатие - кто подарил и подпись.
     * [line] - строка из карточки друга: «g:номера» (новый формат) или эмодзи (старый).
     */
    fun fillSection(box: LinearLayout, gifts: List<Gift>, line: String = "", limit: Int = 24) {
        val ctx = box.context
        GiftCatalog.init(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        box.removeAllViews()
        // (подарок из каталога, полная запись - если она есть, имя дарителя)
        val raw: List<Triple<GiftCatalog.Gift, Gift?, String>> = if (gifts.isNotEmpty()) {
            gifts.mapNotNull { g -> GiftCatalog.resolve(g.id, g.emoji)?.let { Triple(it, g, g.fromName) } }
        } else if (line.startsWith("G:")) {
            // Новый формат: «номер|эмодзи|от кого» - друзья видят подарок в его настоящем виде.
            line.removePrefix("G:").split(',').mapNotNull { part ->
                val f = part.split('|')
                val id = f.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                GiftCatalog.resolve(id, f.getOrNull(1))?.let { Triple(it, null, f.getOrNull(2)?.trim().orEmpty()) }
            }
        } else if (line.startsWith("g:")) {
            line.removePrefix("g:").split(',').mapNotNull { part ->
                val id = part.substringBefore('~').trim().toIntOrNull() ?: return@mapNotNull null
                GiftCatalog.byId(id)?.let { Triple(it, null, part.substringAfter('~', "").trim()) }
            }
        } else {
            val out = ArrayList<Triple<GiftCatalog.Gift, Gift?, String>>()
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(line)
            var start = it.first()
            var end = it.next()
            while (end != java.text.BreakIterator.DONE) {
                line.substring(start, end).takeIf { e -> e.isNotBlank() }?.let { e -> GiftCatalog.byEmoji(e)?.let { g -> out += Triple(g, null, "") } }
                start = end
                end = it.next()
            }
            out
        }
        // Все подарки: картинки, эмодзи и нарисованные.
        val items = raw
        if (items.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val legendary = items.count { it.first.rarity == GiftCatalog.Rarity.LEGENDARY }
        val rare = items.count { it.first.rarity == GiftCatalog.Rarity.RARE }
        box.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.gifts_title, items.size) +
                    (if (legendary > 0) "   ★ $legendary" else "") + (if (rare > 0) "   ◆ $rare" else "")
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.settingsAccent))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(20), dp(14), dp(20), dp(6))
            },
        )
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        items.take(limit).forEachIndexed { i, (g, gift, from) ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(GiftArt(ctx, g, playEmoji = i < 8), LinearLayout.LayoutParams(dp(70), dp(70)))
                // От кого подарок - прямо под ним.
                addView(TextView(ctx).apply {
                    text = if (from.isNotBlank()) ctx.getString(R.string.gift_from_short, from) else g.shortName()
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(cSub)
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener {
                    Kinetics.burst(this)
                    showGift(ctx, g, gift, from)
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(6))
            })
            Kinetics.springIn(cell, i * 40L)
        }
        box.addView(grid)
        // Больше 24 - кнопка «Все подарки», а не молча обрезанный список.
        if (items.size > limit) {
            box.addView(
                Btn.tonal(ctx, ctx.getString(R.string.gifts_all, items.size)) { showAll(ctx, items) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    bottomMargin = dp(8)
                },
            )
        }
    }

    /** Подарок крупно: живая анимация, название, редкость, от кого, когда и подпись. */
    internal fun showGift(
        ctx: Context,
        g: GiftCatalog.Gift,
        gift: Gift?,
        from: String,
        extra: Pair<String, () -> Unit>? = null,
    ) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blend(g.colors[1], 0xFF1E1B4B.toInt()), 0xFF1E1B4B.toInt()),
            ).apply {
                cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f)
            }
        }
        val art = GiftArt(ctx, g)
        root.addView(art, LinearLayout.LayoutParams(dp(180), dp(180)))
        root.addView(
            TextView(ctx).apply {
                text = g.name()
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, dp(12), 0, 0)
            },
        )
        root.addView(rarityBadge(ctx, g.rarity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)).apply { topMargin = dp(8) })
        val who = gift?.fromName?.ifBlank { null } ?: from.ifBlank { null }
        val lines = buildString {
            if (who != null) append(ctx.getString(R.string.gift_from, who))
            if (gift != null) {
                if (isNotEmpty()) append("  ·  ")
                append(android.text.format.DateUtils.getRelativeTimeSpanString(gift.at))
            }
        }
        if (lines.isNotBlank()) {
            root.addView(
                TextView(ctx).apply {
                    text = lines
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(0xDDFFFFFF.toInt())
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
        if (gift != null && gift.note.isNotBlank()) {
            root.addView(
                TextView(ctx).apply {
                    text = "«" + gift.note + "»"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(12), dp(8), 0)
                },
            )
        }
        if (gift != null && gift.sign.isNotBlank()) {
            root.addView(
                TextView(ctx).apply {
                    text = signLine(ctx, gift.sign, gift.from)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC))
                    setTextColor(0xFFFDE68A.toInt())
                    setPadding(dp(8), dp(10), dp(8), 0)
                },
            )
        }
        // Мой комментарий: у своих подарков - можно написать/изменить, у чужих - только показать.
        val own = gift?.let { mine(ctx, it) }
        val comment = (own ?: gift)?.comment.orEmpty()
        if (own != null || comment.isNotBlank()) {
            val cv = TextView(ctx).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xE6FFFFFF.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0x22FFFFFF)
                }
            }
            fun showComment(c: String) {
                cv.text = when {
                    c.isNotBlank() -> "💬 " + c
                    else -> ctx.getString(R.string.gift_comment_add)
                }
            }
            showComment(comment)
            if (own != null) {
                cv.setOnClickListener {
                    val input = EditText(ctx).apply {
                        setText(mine(ctx, own)?.comment.orEmpty())
                        setHint(R.string.gift_comment_hint)
                        filters = arrayOf(android.text.InputFilter.LengthFilter(COMMENT_MAX))
                    }
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.gift_comment_title)
                        .setView(FrameLayout(ctx).apply {
                            setPadding(dp(20), dp(6), dp(20), 0)
                            addView(input)
                        })
                        .setPositiveButton(R.string.save) { _, _ ->
                            setComment(ctx, own, input.text.toString())
                            showComment(input.text.toString().trim())
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            root.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        extra?.let { (label, action) ->
            root.addView(
                Btn.key3d(ctx, label) {
                    runCatching { sheet.dismiss() }
                    action()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) },
            )
        }
        root.addView(
            Btn.key3d(ctx, ctx.getString(R.string.close)) { runCatching { sheet.dismiss() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(if (extra != null) 10 else 18) },
        )
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
        if (Kinetics.enabled) {
            art.scaleX = 0.4f
            art.scaleY = 0.4f
            art.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
        }
    }

    /** Все подарки профиля - сеткой в окне снизу (лёгкие: без живых эмодзи). */
    private fun showAll(ctx: Context, items: List<Triple<GiftCatalog.Gift, Gift?, String>>) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = BottomSheetDialog(ctx)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val grid = GridView(ctx).apply {
            numColumns = com.stoyko.smessenger.ui.Adaptive.columns(ctx, max = 6)
            verticalSpacing = dp(8)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            clipToPadding = false
            setBackgroundResource(R.drawable.bg_sheet)
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val (g, _, from) = items[position]
                val old = convertView as? LinearLayout
                if (old != null && old.tag == position) return old
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    tag = position
                    addView(GiftArt(ctx, g, playEmoji = false), LinearLayout.LayoutParams(dp(70), dp(70)))
                    addView(
                        TextView(ctx).apply {
                            text = if (from.isNotBlank()) ctx.getString(R.string.gift_from_short, from) else g.shortName()
                            textSize = 10f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            setTextColor(cSub)
                            setPadding(0, dp(3), 0, 0)
                        },
                    )
                }
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val (g, gift, from) = items[position]
            showGift(ctx, g, gift, from)
        }
        sheet.setContentView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.75f).toInt()))
        (grid.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
    }
}

/** Фон праздника: вращающиеся лучи, ореол и конфетти, разлетающееся из центра. */
private class CelebrateFx(ctx: Context, private val tint: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val ray = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bit = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t0 = 0L
    private val colors = intArrayOf(0xFFFDE047.toInt(), 0xFFF472B6.toInt(), 0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFB923C.toInt(), 0xFFC084FC.toInt())
    private val rnd = java.util.Random()

    // Конфетти: угол, скорость, вращение, цвет, форма.
    private val bits = Array(70) {
        floatArrayOf(
            (rnd.nextFloat() * Math.PI * 2).toFloat(),
            (380 + rnd.nextFloat() * 620),
            rnd.nextFloat() * 720 - 360,
            (it % 6).toFloat(),
            (it % 3).toFloat(),
        )
    }

    fun start() {
        t0 = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val t = (android.os.SystemClock.uptimeMillis() - t0) / 1000f
        val cx = width / 2f
        val cy = height / 2f - 30 * d
        val appear = (t / 0.4f).coerceIn(0f, 1f)
        // Ореол цвета подарка.
        val gr = 170 * d * (0.9f + 0.1f * kotlin.math.sin(t * 3f))
        glow.shader = android.graphics.RadialGradient(cx, cy, gr, (tint and 0x00FFFFFF) or 0xCC000000.toInt(), tint and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, glow)
        // Лучи.
        ray.color = 0xFFFFE08A.toInt()
        ray.alpha = (90 * appear).toInt()
        val len = maxOf(width, height) * 0.7f
        c.save()
        c.rotate(t * 25f, cx, cy)
        for (i in 0 until 12) {
            c.rotate(30f, cx, cy)
            val p = android.graphics.Path()
            p.moveTo(cx, cy)
            p.lineTo(cx - len * 0.09f, cy - len)
            p.lineTo(cx + len * 0.09f, cy - len)
            p.close()
            c.drawPath(p, ray)
        }
        c.restore()
        // Конфетти: вылетает из центра и падает с силой тяжести.
        if (t < 2.6f) {
            for (b in bits) {
                val v = b[1] * d / 3f
                val x = cx + kotlin.math.cos(b[0]) * v * t
                val y = cy + kotlin.math.sin(b[0]) * v * t + 420 * d * t * t / 2f
                bit.color = colors[b[3].toInt()]
                bit.alpha = ((1f - t / 2.6f) * 255).toInt().coerceIn(0, 255)
                c.save()
                c.rotate(b[2] * t, x, y)
                when (b[4].toInt()) {
                    0 -> c.drawRect(x - 4 * d, y - 2 * d, x + 4 * d, y + 2 * d, bit)
                    1 -> c.drawCircle(x, y, 3 * d, bit)
                    else -> c.drawRect(x - 2 * d, y - 5 * d, x + 2 * d, y + 5 * d, bit)
                }
                c.restore()
            }
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
