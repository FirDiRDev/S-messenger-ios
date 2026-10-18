// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.stoyko.smessenger.ui.safeDismiss
import kotlin.math.abs

private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)

/**
 * Обучение «Как вести канал» - показывается один раз сразу после создания канала и в любой момент
 * из «Информации» канала (плитка «🎓 Обучение»). Страницы листаются кнопками и свайпом.
 * Особое внимание - бизнесу: товары, заказы, бизнес-информация, статистика, советы.
 */
object ChannelTutorial {
    private class Page(val emoji: String, val title: String, val body: String, val action: String? = null)

    private fun pages(): List<Page> = listOf(
        Page(
            "📣",
            t("Ваш канал создан!", "Your channel is ready!"),
            t(
                "Канал - лента ваших постов. Подписчики читают, ставят реакции и (если включите) комментируют. " +
                    "Посты идут напрямую между телефонами, без серверов, со сквозным шифрованием.\n\n" +
                    "За минуту покажем всё, что нужно, чтобы вести канал - в том числе для бизнеса.",
                "A channel is a feed of your posts. Subscribers read, react and (if you allow it) comment. " +
                    "Posts travel directly between phones, with no servers and end-to-end encryption.\n\n" +
                    "In a minute we'll show everything you need to run a channel - including for business.",
            ),
        ),
        Page(
            "✍️",
            t("Публикация постов", "Publishing posts"),
            t(
                "• Напишите текст и нажмите «Отправить» - пост уйдёт всем подписчикам.\n" +
                    "• Удержите «Отправить» - откроется конструктор поста: товар, кнопка-ссылка, шаблоны, без звука и «Отложить» на нужное время.\n" +
                    "• Фото, видео и файлы - кнопкой «скрепка», как в чате.",
                "• Type and tap “Send” - the post goes to all subscribers.\n" +
                    "• Hold “Send” to open the post builder: product, link button, templates, silent and “Schedule”.\n" +
                    "• Photos, videos and files - via the paperclip, as in a chat.",
            ),
        ),
        Page(
            "🛍",
            t("Товары и услуги", "Products and services"),
            t(
                "Меню ⋮ → «➕ Карточка товара» (или удержите «Отправить» → «Товар или услуга»): название, цена, старая цена (покупатель увидит скидку), " +
                    "промокод (копируется одним нажатием), наличие («В наличии», «Под заказ», «Нет в наличии») и кнопка «🛒 Заказать».\n\n" +
                    "«Заказать» сразу открывает чат с вами и вставляет текст заказа. Подписчики видят все товары в «🛍 Каталоге».",
                "Menu ⋮ → “➕ Product card” (or hold “Send” → “Product or service”): title, price, old price (buyers see the discount), " +
                    "promo code (copied with one tap), availability (“In stock”, “Pre-order”, “Sold out”) and an “🛒 Order” button.\n\n" +
                    "“Order” opens a chat with you with the order text ready. Subscribers see all products in the “🛍 Catalog”.",
            ),
        ),
        Page(
            "💼",
            t("Бизнес-информация", "Business info"),
            t(
                "Сайт, телефон, адрес, часы работы и «Контакт для заказов» - видны всем подписчикам в «Информации» канала. " +
                    "Без контакта для заказов кнопка «Заказать» предложит позвонить по телефону.",
                "Website, phone, address, opening hours and “Orders contact” - visible to every subscriber in the channel's “Info”. " +
                    "Without an orders contact, “Order” offers to call the phone number.",
            ),
            t("Заполнить сейчас", "Fill in now"),
        ),
        Page(
            "📈",
            t("Рост и статистика", "Growth and stats"),
            t(
                "• Приглашайте: «Информация» → «Пригласить», «Поделиться ID» или QR-код.\n" +
                    "• Под каждым постом - просмотры 👁.\n" +
                    "• «📊 Статистика»: подписчики, охват, просмотры, реакции и лучшие посты.\n" +
                    "• «🛍 Каталог»: все товары канала одним списком - нажатие ведёт к посту.",
                "• Invite: “Info” → “Invite”, “Share ID” or the QR code.\n" +
                    "• Every post shows views 👁.\n" +
                    "• “📊 Stats”: subscribers, reach, views, reactions and top posts.\n" +
                    "• “🛍 Catalog”: all products of the channel in one list - tap to jump to the post.",
            ),
        ),
        Page(
            "⚙️",
            t("Управление каналом", "Managing the channel"),
            t(
                "В «⚙️ Настройках» - плитки: подписи авторов, комментарии, медленный режим, медиа только от администраторов, правила.\n" +
                    "Удержите пост - «Закрепить для всех». Нажмите на подписчика - назначьте администратором, заглушите или удалите.",
                "“⚙️ Settings” has tiles: author signatures, comments, slow mode, admin-only media, rules.\n" +
                    "Hold a post to “Pin for everyone”. Tap a subscriber to make them admin, mute or remove them.",
            ),
        ),
        Page(
            "💡",
            t("Советы для бизнеса", "Business tips"),
            t(
                "• Публикуйте регулярно, примерно в одно время.\n" +
                    "• Закрепите пост с прайсом или главным предложением.\n" +
                    "• Мелкие новости - тихими постами (без уведомления).\n" +
                    "• Акции - со старой ценой и промокодом: скидка видна сразу.\n" +
                    "• Отвечайте на заказы быстро - кнопка ведёт прямо в чат с вами.\n\n" +
                    "Обучение всегда можно открыть снова: «Информация» → «🎓 Обучение».",
                "• Post regularly, at about the same time.\n" +
                    "• Pin a post with your price list or main offer.\n" +
                    "• Small news - as silent posts (no notification).\n" +
                    "• Promotions - with the old price and a promo code: the discount is obvious.\n" +
                    "• Answer orders quickly - the button leads straight to a chat with you.\n\n" +
                    "You can open this guide again any time: “Info” → “🎓 Guide”.",
            ),
        ),
    )

    private const val PREFS = "channel_tutorial"

    /** Канал только что создан - обучение покажется при первом открытии канала. */
    fun markPending(ctx: Context, chatId: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending_$chatId", true).apply()

    /** true - обучение ждёт показа (и флаг снят). */
    fun consumePending(ctx: Context, chatId: String): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("pending_$chatId", false)) return false
        p.edit().remove("pending_$chatId").putBoolean(chatId, true).apply()
        return true
    }

    /** Показать один раз для канала [chatId] (сразу после создания). */
    fun showOnce(ctx: Context, chatId: String, onBusiness: (() -> Unit)? = null) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(chatId, false)) return
        p.edit().putBoolean(chatId, true).apply()
        show(ctx, onBusiness)
    }

    fun show(ctx: Context, onBusiness: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val list = pages()
        var index = 0
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xB3000000.toInt()) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A2460.toInt(), 0xFF1B1840.toInt())).apply {
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val emoji = TextView(ctx).apply {
            textSize = 54f
            gravity = Gravity.CENTER
        }
        val title = TextView(ctx).apply {
            textSize = 21f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
        }
        val body = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xE6FFFFFF.toInt())
            setLineSpacing(0f, 1.18f)
        }
        val bodyScroll = ScrollView(ctx).apply { addView(body) }
        val action = com.stoyko.smessenger.ui.Btn.primary(ctx, "", intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) {}
        val dots = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val back = com.stoyko.smessenger.ui.Btn.tonal(ctx, t("Назад", "Back")) {}
        val next = com.stoyko.smessenger.ui.Btn.primary(ctx, t("Далее", "Next")) {}
        card.addView(emoji)
        card.addView(title)
        card.addView(bodyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        card.addView(dots)
        card.addView(com.stoyko.smessenger.ui.Btn.row(ctx, listOf(back, next), height = 48))
        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.CENTER).apply {
                setMargins(dp(18), 0, dp(18), 0)
            },
        )

        fun render(dir: Int) {
            val p = list[index]
            emoji.text = p.emoji
            title.text = p.title
            body.text = p.body
            bodyScroll.scrollTo(0, 0)
            action.visibility = if (p.action != null && onBusiness != null) View.VISIBLE else View.GONE
            action.text = p.action.orEmpty()
            back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            next.text = if (index == list.lastIndex) t("Готово", "Done") else t("Далее", "Next")
            dots.removeAllViews()
            for (i in list.indices) {
                dots.addView(
                    View(ctx).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setColor(if (i == index) 0xFF7B6CF6.toInt() else 0x55FFFFFF)
                        }
                    },
                    LinearLayout.LayoutParams(if (i == index) dp(20) else dp(8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) },
                )
            }
            if (dir != 0 && com.stoyko.smessenger.ui.Kinetics.enabled) {
                for (v in listOf(title, bodyScroll)) {
                    v.alpha = 0f
                    v.translationX = dp(40) * dir.toFloat()
                    v.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            emoji.scaleX = 0.4f
            emoji.scaleY = 0.4f
            emoji.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
        }

        fun go(delta: Int) {
            val n = index + delta
            if (n < 0) return
            if (n > list.lastIndex) {
                dialog.safeDismiss()
                return
            }
            index = n
            render(delta)
        }
        back.setOnClickListener { go(-1) }
        next.setOnClickListener { go(1) }
        action.setOnClickListener {
            dialog.safeDismiss()
            onBusiness?.invoke()
        }
        // Свайп влево/вправо листает страницы.
        val gd = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                    go(if (vx < 0) 1 else -1)
                    return true
                }
            },
        )
        card.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        bodyScroll.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            false
        }
        dialog.setContentView(root)
        render(0)
        dialog.show()
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
    }
}
