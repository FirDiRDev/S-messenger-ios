// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.groups

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.GroupMessage
import com.stoyko.smessenger.groups.ChannelPost
import com.stoyko.smessenger.groups.ChannelStats
import com.stoyko.smessenger.groups.GroupReactions
import com.stoyko.smessenger.groups.GroupSettings

private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)

/**
 * Инструменты канала для бизнеса (своим кодом):
 *  - конструктор поста: обычный / товар или услуга с ценой и кнопкой «Заказать» / пост с кнопкой-ссылкой,
 *    «без звука», отложенная публикация;
 *  - оформление постов в ленте: карточка товара, кнопка, подпись автора, просмотры «👁 N»;
 *  - бизнес-информация канала (сайт, телефон, адрес, часы, контакт для заказов) и её редактор;
 *  - статистика канала для администраторов (подписчики, публикации, просмотры, охват, реакции, топ постов).
 */
object ChannelTools {
    const val EXTRAS = "post_extras"
    private const val ACCENT = 0xFF5B4BDB.toInt()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Есть ли человек в контактах (проверка в базе - не в главном потоке). Задаёт экран канала. */
    @Volatile
    var contactCheck: (String) -> Boolean = { false }

    /** Открыть чат с контактом или запрос в контакты (с текстом), смотря есть ли он уже. */
    private fun openContact(fragment: Fragment, id: String, message: String?, onKnown: () -> Unit = {}) {
        val pk = id.take(64)
        Thread {
            val known = runCatching { contactCheck(pk) }.getOrDefault(false)
            main.post {
                if (!fragment.isAdded) return@post
                runCatching {
                    if (known) {
                        onKnown()
                        fragment.findNavController().navigate(R.id.chatFragment, bundleOf(com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY to pk))
                    } else {
                        val args = bundleOf("toxId" to id)
                        if (message != null) args.putString("message", message)
                        fragment.findNavController().navigate(R.id.addContactFragment, args)
                    }
                }
            }
        }.start()
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines: Int = 1) =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = if (lines > 1) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
            if (lines > 1) maxLines = lines else setSingleLine()
        }

    private fun label(ctx: Context, text: String, top: Int = 12) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, top), 0, dp(ctx, 2))
        alpha = 0.75f
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), 0)
    }

    private fun scroll(ctx: Context, v: View) = ScrollView(ctx).apply { addView(v) }

    // ---------------- конструктор поста ----------------

    /**
     * Удержание «Отправить» в канале (у администраторов): параметры поста.
     * [publish] - готовый текст поста (с заголовком); [schedule] - отложить на время.
     */
    fun composer(ctx: Context, chatId: String, text: String, publish: (String) -> Unit, schedule: (String) -> Unit, startProduct: Boolean = false) {
        val st = GroupSettings.get(ctx, chatId)
        val box = column(ctx)
        // Текст поста можно поправить прямо здесь или взять готовый шаблон.
        val body = field(ctx, t("Текст поста", "Post text"), text, lines = 4).apply { maxLines = 10 }
        box.addView(body)
        box.addView(
            com.stoyko.smessenger.ui.Btn.tonal(ctx, t("📋 Шаблоны постов", "📋 Post templates")) {
                val tpl = templates()
                AlertDialog.Builder(ctx)
                    .setTitle(t("Шаблон поста", "Post template"))
                    .setItems(tpl.map { it.first as CharSequence }.toTypedArray()) { _, w ->
                        body.setText(tpl[w].second)
                        body.setSelection(body.text.length)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42)).apply { topMargin = dp(ctx, 6) },
        )
        box.addView(
            com.stoyko.smessenger.ui.Btn.tonal(ctx, t("✨ Редактор оформления", "✨ Formatting editor")) {
                com.stoyko.smessenger.chat.RichEditor.show(ctx, body.text.toString(), t("Готово", "Done")) { out ->
                    body.setText(out)
                    body.setSelection(body.text.length)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42)).apply { topMargin = dp(ctx, 6) },
        )
        com.stoyko.smessenger.chat.SelectionMenu.install(body)
        box.addView(label(ctx, t("Тип поста", "Post type")))
        val types = RadioGroup(ctx)
        val plain = RadioButton(ctx).apply {
            id = View.generateViewId()
            this.text = t("📝 Обычный", "📝 Regular")
        }
        val product = RadioButton(ctx).apply {
            id = View.generateViewId()
            this.text = t("🛍 Товар или услуга (цена и «Заказать»)", "🛍 Product or service (price and “Order”)")
        }
        val link = RadioButton(ctx).apply {
            id = View.generateViewId()
            this.text = t("🔗 С кнопкой-ссылкой (сайт, запись, магазин)", "🔗 With a link button (site, booking, shop)")
        }
        val withButtons = RadioButton(ctx).apply {
            id = View.generateViewId()
            this.text = t("🔘 С кнопками в посте (до 6, цветные)", "🔘 With buttons in the post (up to 6, coloured)")
        }
        types.addView(plain)
        types.addView(product)
        types.addView(link)
        types.addView(withButtons)
        val withPoll = RadioButton(ctx).apply {
            id = View.generateViewId()
            this.text = t("📊 Опрос (медиа, ограничения, график)", "📊 Poll (media, limits, chart)")
        }
        types.addView(withPoll)
        types.check(if (startProduct) product.id else plain.id)
        box.addView(types)

        val productBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val pTitle = field(ctx, t("Название", "Title"))
        val pPrice = field(ctx, t("Цена (например, 1 500)", "Price (e.g. 1,500)"), type = InputType.TYPE_CLASS_TEXT)
        val pCur = field(ctx, t("Валюта", "Currency"), st.currency, InputType.TYPE_CLASS_TEXT)
        val pDesc = field(ctx, t("Короткое описание (необязательно)", "Short description (optional)"), lines = 3)
        productBox.addView(pTitle)
        productBox.addView(
            LinearLayout(ctx).apply {
                addView(pPrice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pCur, LinearLayout.LayoutParams(dp(ctx, 72), ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
        productBox.addView(pDesc)
        val pOld = field(ctx, t("Старая цена - покажем скидку (необязательно)", "Old price - shows the discount (optional)"))
        val pPromo = field(ctx, t("Промокод (необязательно)", "Promo code (optional)"))
        productBox.addView(pOld)
        productBox.addView(pPromo)
        productBox.addView(label(ctx, t("Наличие", "Availability"), top = 6))
        val stockGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val stockIds = listOf("" to t("—", "—"), "in" to t("В наличии", "In stock"), "pre" to t("Под заказ", "Pre-order"), "out" to t("Нет", "Sold out"))
            .map { (k, l) ->
                val rb = RadioButton(ctx).apply {
                    id = View.generateViewId()
                    this.text = l
                    textSize = 12f
                }
                stockGroup.addView(rb)
                rb.id to k
            }
        stockGroup.check(stockIds[0].first)
        productBox.addView(stockGroup)
        if (st.contactId.isBlank()) {
            productBox.addView(
                TextView(ctx).apply {
                    this.text = t(
                        "Совет: укажите «Контакт для заказов» в бизнес-информации канала - тогда «Заказать» сразу откроет связь с вами.",
                        "Tip: set the “Orders contact” in the channel's business info - then “Order” opens a chat with you right away.",
                    )
                    textSize = 12f
                    alpha = 0.7f
                },
            )
        }
        box.addView(productBox)

        val linkBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val lLabel = field(ctx, t("Текст кнопки (например, «Записаться»)", "Button text (e.g. “Book now”)"))
        val lUrl = field(ctx, t("Ссылка или телефон", "Link or phone"), st.site, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        linkBox.addView(lLabel)
        linkBox.addView(lUrl)
        box.addView(linkBox)

        types.setOnCheckedChangeListener { _, id ->
            productBox.visibility = if (id == product.id) View.VISIBLE else View.GONE
            linkBox.visibility = if (id == link.id) View.VISIBLE else View.GONE
        }
        if (startProduct) productBox.visibility = View.VISIBLE

        val silent = CheckBox(ctx).apply {
            this.text = t("🔕 Без звука - подписчики не получат уведомление", "🔕 Silent - subscribers get no notification")
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        box.addView(silent)

        fun build(): String? {
            val meta = when (types.checkedRadioButtonId) {
                product.id -> {
                    if (pTitle.text.isBlank()) {
                        pTitle.error = t("Укажите название", "Enter a title")
                        return null
                    }
                    ChannelPost.Meta(
                        silent = silent.isChecked,
                        product = ChannelPost.Product(
                            pTitle.text.toString().trim(),
                            pPrice.text.toString().trim(),
                            pCur.text.toString().trim().ifEmpty { st.currency },
                            pDesc.text.toString().trim(),
                            pOld.text.toString().trim(),
                            pPromo.text.toString().trim(),
                            stockIds.firstOrNull { it.first == stockGroup.checkedRadioButtonId }?.second.orEmpty(),
                        ),
                    )
                }
                link.id -> {
                    if (lLabel.text.isBlank() || lUrl.text.isBlank()) {
                        (if (lLabel.text.isBlank()) lLabel else lUrl).error = t("Заполните поле", "Fill this in")
                        return null
                    }
                    ChannelPost.Meta(
                        silent = silent.isChecked,
                        button = ChannelPost.Button(lLabel.text.toString().trim(), ChannelPost.normalizeUrl(lUrl.text.toString())),
                    )
                }
                else -> ChannelPost.Meta(silent = silent.isChecked)
            }
            val postText = body.text.toString().trim()
            if (postText.isEmpty() && meta.product == null) {
                body.error = t("Напишите текст поста", "Write the post text")
                return null
            }
            return ChannelPost.wrap(meta, postText)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t("Новый пост", "New post"))
            .setView(scroll(ctx, box))
            .setPositiveButton(t("Опубликовать", "Publish"), null)
            .setNeutralButton(t("Отложить…", "Schedule…"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // «С кнопками»: следующий шаг - редактор кнопок (ответ, ссылка, копирование, игра, каталог).
        fun buttonsStep(then: (String) -> Unit): Boolean {
            if (types.checkedRadioButtonId == withPoll.id) {
                dialog.dismiss()
                com.stoyko.smessenger.chat.Polls.editor(ctx, body.text.toString().trim()) { then(it) }
                return true
            }
            if (types.checkedRadioButtonId != withButtons.id) return false
            dialog.dismiss()
            com.stoyko.smessenger.chat.MsgButtons.editor(
                ctx, body.text.toString().trim(), games = true, shop = true,
                base = ChannelPost.Meta(silent = silent.isChecked),
            ) { then(it) }
            return true
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (buttonsStep(publish)) return@setOnClickListener
            build()?.let {
                dialog.dismiss()
                publish(it)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (buttonsStep(schedule)) return@setOnClickListener
            build()?.let {
                dialog.dismiss()
                schedule(it)
            }
        }
    }

    // ---------------- пост в ленте ----------------

    /**
     * Оформление поста канала: карточка товара, кнопка-ссылка, подпись автора. Ставится в пузырь
     * сразу после текста; у обычных сообщений и в группах - убирается.
     */
    fun bindExtras(bubble: LinearLayout, anchor: View, m: GroupMessage, chatId: String, isChannel: Boolean, fragment: Fragment) {
        val ctx = bubble.context
        bubble.findViewWithTag<View>(EXTRAS)?.let { bubble.removeView(it) }
        if (m.isFile()) return
        val meta = ChannelPost.meta(m.message)
        val st = GroupSettings.get(ctx, chatId)
        val signature = isChannel && st.signatures && m.senderName.isNotBlank()
        if (meta == null && !signature) return
        val box = LinearLayout(ctx).apply {
            tag = EXTRAS
            orientation = LinearLayout.VERTICAL
        }
        meta?.product?.let { p -> box.addView(productCard(ctx, p) { order(fragment, chatId, p) }) }
        meta?.button?.let { b ->
            box.addView(
                actionButton(ctx, "🔗 " + b.label) { open(ctx, b.url) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 8)
                },
            )
        }
        if (signature) {
            box.addView(
                TextView(ctx).apply {
                    text = "✍ " + m.senderName
                    textSize = 12f
                    setTextColor(0xB3FFFFFF.toInt())
                    setPadding(0, dp(ctx, 6), 0, 0)
                },
            )
        }
        val i = bubble.indexOfChild(anchor)
        bubble.addView(box, if (i >= 0) i + 1 else bubble.childCount)
    }

    private fun actionButton(ctx: Context, text: String, click: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            setColor(0x33FFFFFF)
            setStroke(dp(ctx, 1), 0x55FFFFFF)
        }
        setOnClickListener {
            animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            click()
        }
    }

    private fun productCard(ctx: Context, p: ChannelPost.Product, order: () -> Unit): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x26FFFFFF)
            }
            minimumWidth = dp(ctx, 220)
        }
        card.addView(
            TextView(ctx).apply {
                text = "🛍 " + p.title
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        if (p.price.isNotBlank()) {
            val priceRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4), 0, 0)
            }
            priceRow.addView(
                TextView(ctx).apply {
                    text = p.price + " " + p.currency
                    textSize = 20f
                    setTextColor(0xFFFFE08A.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                },
            )
            if (p.oldPrice.isNotBlank()) {
                priceRow.addView(
                    TextView(ctx).apply {
                        text = p.oldPrice + " " + p.currency
                        textSize = 14f
                        setTextColor(0x99FFFFFF.toInt())
                        paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        setPadding(dp(ctx, 10), 0, 0, 0)
                    },
                )
            }
            if (p.discount > 0) {
                priceRow.addView(
                    TextView(ctx).apply {
                        text = "-${p.discount}%"
                        textSize = 12f
                        setTextColor(0xFFFFFFFF.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(ctx, 7), dp(ctx, 2), dp(ctx, 7), dp(ctx, 2))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 8).toFloat()
                            setColor(0xFFE5484D.toInt())
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) },
                )
            }
            card.addView(priceRow)
        }
        stockLabel(p.stock)?.let { (label, color) ->
            card.addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    setTextColor(color)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(ctx, 4), 0, 0)
                },
            )
        }
        if (p.desc.isNotBlank()) {
            card.addView(
                TextView(ctx).apply {
                    text = p.desc
                    textSize = 14f
                    setTextColor(0xE6FFFFFF.toInt())
                    setPadding(0, dp(ctx, 4), 0, 0)
                },
            )
        }
        if (p.promo.isNotBlank()) {
            card.addView(
                actionButton(ctx, t("🏷 Промокод: ", "🏷 Promo code: ") + p.promo + "  ⧉") {
                    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("promo", p.promo))
                    Toast.makeText(ctx, t("Промокод скопирован", "Promo code copied"), Toast.LENGTH_SHORT).show()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 8)
                },
            )
        }
        card.addView(
            actionButton(ctx, if (p.stock == "out") t("🔔 Узнать о поступлении", "🔔 Ask when available") else t("🛒 Заказать", "🛒 Order"), order),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 10)
            },
        )
        return LinearLayout(ctx).apply {
            setPadding(0, dp(ctx, 8), 0, 0)
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun stockLabel(s: String): Pair<String, Int>? = when (s) {
        "in" -> t("✅ В наличии", "✅ In stock") to 0xFF4ADE80.toInt()
        "pre" -> t("🕓 Под заказ", "🕓 Pre-order") to 0xFFFCD34D.toInt()
        "out" -> t("⛔ Нет в наличии", "⛔ Sold out") to 0xFFFCA5A5.toInt()
        else -> null
    }

    /** Готовые тексты постов для бизнеса. */
    private fun templates(): List<Pair<String, String>> = listOf(
        t("🔥 Акция", "🔥 Sale") to t(
            "🔥 АКЦИЯ!\n\nТолько до [дата] - скидка [N]% на [товар/услугу].\nУспейте заказать - нажмите «Заказать» под постом.",
            "🔥 SALE!\n\nUntil [date] only - [N]% off [product/service].\nOrder now - tap “Order” under the post.",
        ),
        t("🆕 Новинка", "🆕 New arrival") to t(
            "🆕 Новинка: [название]\n\n[Чем хороша - 2-3 строки]\n\nЦена и заказ - в карточке ниже 👇",
            "🆕 New: [name]\n\n[Why it's great - 2-3 lines]\n\nPrice and ordering - in the card below 👇",
        ),
        t("🕘 Режим работы", "🕘 Opening hours") to t(
            "🕘 Режим работы\n\nПн-Пт: 10:00-20:00\nСб: 11:00-18:00\nВс: выходной\n\n📍 [адрес]  ☎️ [телефон]",
            "🕘 Opening hours\n\nMon-Fri: 10:00-20:00\nSat: 11:00-18:00\nSun: closed\n\n📍 [address]  ☎️ [phone]",
        ),
        t("🎁 Розыгрыш", "🎁 Giveaway") to t(
            "🎁 РОЗЫГРЫШ!\n\nРазыгрываем [приз].\nУсловия: поставьте реакцию на этот пост и пригласите друга в канал.\nИтоги - [дата] в этом канале.",
            "🎁 GIVEAWAY!\n\nWe're giving away [prize].\nTo enter: react to this post and invite a friend to the channel.\nResults - [date] in this channel.",
        ),
        t("💰 Прайс-лист", "💰 Price list") to t(
            "💰 Прайс-лист\n\n• [услуга] - [цена]\n• [услуга] - [цена]\n• [услуга] - [цена]\n\nЗапись и вопросы - кнопкой «Написать владельцу» в информации канала.",
            "💰 Price list\n\n• [service] - [price]\n• [service] - [price]\n• [service] - [price]\n\nBookings and questions - via “Message the owner” in the channel info.",
        ),
        t("📦 Доставка и оплата", "📦 Delivery and payment") to t(
            "📦 Доставка и оплата\n\n🚚 Доставка: [условия]\n💳 Оплата: [способы]\n↩️ Возврат: [условия]",
            "📦 Delivery and payment\n\n🚚 Delivery: [terms]\n💳 Payment: [methods]\n↩️ Returns: [terms]",
        ),
        t("⭐ Отзыв клиента", "⭐ Customer review") to t(
            "⭐⭐⭐⭐⭐\n\n«[текст отзыва]»\n- [имя клиента]\n\nСпасибо, что выбираете нас!",
            "⭐⭐⭐⭐⭐\n\n“[review text]”\n- [customer name]\n\nThank you for choosing us!",
        ),
    )

    // ---------------- каталог товаров ----------------

    /**
     * «🛍 Каталог»: витрина всех товаров и услуг канала - карточками (название, цена, старая цена
     * со скидкой, наличие, описание, промокод). Нажатие - перейти к посту, «🛒» - заказать.
     * Администратору - кнопка «➕ Новая карточка товара» (сразу публикуется в канал).
     */
    fun catalogDialog(
        ctx: Context,
        posts: List<GroupMessage>,
        jump: (GroupMessage) -> Unit,
        onAdd: (() -> Unit)? = null,
        onOrder: ((ChannelPost.Product) -> Unit)? = null,
    ) {
        val items = posts.mapNotNull { m -> ChannelPost.meta(m.message)?.product?.let { m to it } }.reversed()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = column(ctx)
        var dialog: AlertDialog? = null
        if (onAdd != null) {
            box.addView(
                com.stoyko.smessenger.ui.Btn.primary(ctx, t("➕ Новая карточка товара", "➕ New product card"), intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) {
                    dialog?.dismiss()
                    onAdd()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) },
            )
        }
        // Поиск по каталогу.
        val search = field(ctx, t("Поиск товара", "Search products"))
        if (items.size > 4) box.addView(search)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun fill(q: String) {
            list.removeAllViews()
            val shown = items.filter { (_, p) -> q.isBlank() || p.title.contains(q, true) || p.desc.contains(q, true) }
            if (shown.isEmpty()) {
                list.addView(TextView(ctx).apply {
                    text = if (items.isEmpty()) {
                        t(
                            "Товаров пока нет." + (if (onAdd != null) " Нажмите «➕ Новая карточка товара»." else ""),
                            "No products yet." + (if (onAdd != null) " Tap “➕ New product card”." else ""),
                        )
                    } else {
                        t("Ничего не найдено", "Nothing found")
                    }
                    textSize = 14f
                    alpha = 0.75f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
            }
            for ((m, p) in shown) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5B4BDB.toInt(), 0xFF3B2F9E.toInt())).apply {
                        cornerRadius = dp(16).toFloat()
                    }
                    isClickable = true
                    setOnClickListener {
                        dialog?.dismiss()
                        jump(m)
                    }
                    com.stoyko.smessenger.ui.Kinetics.squish(this, 0.97f)
                }
                card.addView(TextView(ctx).apply {
                    text = "🛍 " + p.title
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                if (p.price.isNotBlank()) {
                    card.addView(TextView(ctx).apply {
                        val sb = android.text.SpannableStringBuilder(p.price + " " + p.currency)
                        if (p.oldPrice.isNotBlank()) {
                            val st = sb.length + 2
                            sb.append("  ").append(p.oldPrice).append(' ').append(p.currency)
                            sb.setSpan(android.text.style.StrikethroughSpan(), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(android.text.style.RelativeSizeSpan(0.7f), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (p.discount > 0) sb.append("   -").append(p.discount.toString()).append("%")
                        text = sb
                        textSize = 19f
                        setTextColor(0xFFFFE08A.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                stockLabel(p.stock)?.let { (label, color) ->
                    card.addView(TextView(ctx).apply {
                        text = label
                        textSize = 12f
                        setTextColor(color)
                    })
                }
                if (p.desc.isNotBlank()) {
                    card.addView(TextView(ctx).apply {
                        text = p.desc
                        textSize = 13f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(0xE6FFFFFF.toInt())
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                if (p.promo.isNotBlank()) {
                    card.addView(TextView(ctx).apply {
                        text = t("🏷 Промокод: ", "🏷 Promo: ") + p.promo
                        textSize = 13f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                if (onOrder != null) {
                    card.addView(
                        actionButton(ctx, if (p.stock == "out") t("🔔 Узнать о поступлении", "🔔 Ask when available") else t("🛒 Заказать", "🛒 Order")) {
                            dialog?.dismiss()
                            onOrder(p)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
                    )
                }
                list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = fill(e?.toString().orEmpty())
        })
        fill("")
        dialog = AlertDialog.Builder(ctx)
            .setTitle(t("🛍 Каталог · ", "🛍 Catalog · ") + items.size)
            .setView(scroll(ctx, box))
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** «Заказать» из каталога (для фрагмента канала). */
    fun orderFromCatalog(fragment: Fragment, chatId: String, p: ChannelPost.Product) = order(fragment, chatId, p)

    private fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    /**
     * «Заказать»: связь с владельцем канала по контакту из бизнес-информации. Уже в контактах -
     * открывается чат (текст заказа вставлен в буфер обмена); нет - запрос в контакты с текстом заказа.
     */
    private fun order(fragment: Fragment, chatId: String, p: ChannelPost.Product) {
        val ctx = fragment.requireContext()
        val st = GroupSettings.get(ctx, chatId)
        val title = com.stoyko.smessenger.groups.GroupProfile.name(ctx, chatId).orEmpty()
        val text = t("Здравствуйте! Хочу заказать: ", "Hello! I'd like to order: ") + p.title +
            (if (p.price.isNotBlank()) " (" + p.price + " " + p.currency + ")" else "") +
            (if (title.isNotBlank()) t(" - из канала «$title»", " - from the channel “$title”") else "")
        val id = st.contactId.trim().uppercase()
        if (!Regex("[0-9A-F]{76}").matches(id)) {
            AlertDialog.Builder(ctx)
                .setTitle(t("🛒 Заказ", "🛒 Order"))
                .setMessage(
                    if (st.phone.isNotBlank()) {
                        t("Свяжитесь с продавцом по телефону: ", "Contact the seller by phone: ") + st.phone
                    } else {
                        t(
                            "Владелец канала пока не указал контакт для заказов. Напишите в комментариях к посту.",
                            "The channel owner hasn't set an orders contact yet. Leave a comment under the post.",
                        )
                    },
                )
                .apply { if (st.phone.isNotBlank()) setPositiveButton(t("Позвонить", "Call")) { _, _ -> open(ctx, ChannelPost.normalizeUrl(st.phone)) } }
                .setNegativeButton(android.R.string.ok, null)
                .show()
            return
        }
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clip?.setPrimaryClip(android.content.ClipData.newPlainText("order", text))
        openContact(fragment, id, text) {
            Toast.makeText(ctx, t("Текст заказа скопирован - вставьте его в чат", "Order text copied - paste it into the chat"), Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- бизнес-информация ----------------

    /** Блок «Бизнес-информация» в «Информации» канала (видят все подписчики). */
    fun businessBlock(ctx: Context, chatId: String, fragment: Fragment): View? {
        val st = GroupSettings.get(ctx, chatId)
        if (!st.hasBusiness) return null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 4))
        }
        box.addView(label(ctx, t("💼 О бизнесе", "💼 Business info"), 4))
        fun row(icon: String, value: String, click: (() -> Unit)?) {
            if (value.isBlank()) return
            box.addView(
                TextView(ctx).apply {
                    text = "$icon  $value"
                    textSize = 15f
                    setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
                    if (click != null) {
                        setTextColor(0xFF7C6CF0.toInt())
                        setOnClickListener { click() }
                    }
                },
            )
        }
        row("🌐", st.site) { open(ctx, ChannelPost.normalizeUrl(st.site)) }
        row("📞", st.phone) { open(ctx, ChannelPost.normalizeUrl(st.phone)) }
        row("📍", st.address) { open(ctx, "geo:0,0?q=" + Uri.encode(st.address)) }
        row("🕘", st.hours, null)
        if (Regex("[0-9A-Fa-f]{76}").matches(st.contactId.trim())) {
            box.addView(
                actionButton(ctx, t("✉️ Написать владельцу", "✉️ Message the owner")) {
                    openContact(fragment, st.contactId.trim().uppercase(), null)
                }.apply { (background as? GradientDrawable)?.setColor(ACCENT) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 8)
                },
            )
        }
        return box
    }

    /** Редактор бизнес-информации (администраторы). [save] - разослать подписчикам. */
    fun businessEditor(ctx: Context, chatId: String, myId: String, save: ((GroupSettings.Settings) -> GroupSettings.Settings) -> Boolean) {
        val st = GroupSettings.get(ctx, chatId)
        val box = column(ctx)
        val site = field(ctx, t("Сайт", "Website"), st.site, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val phone = field(ctx, t("Телефон", "Phone"), st.phone, InputType.TYPE_CLASS_PHONE)
        val address = field(ctx, t("Адрес", "Address"), st.address)
        val hours = field(ctx, t("Часы работы (например, Пн–Пт 9:00–19:00)", "Opening hours (e.g. Mon–Fri 9–7)"), st.hours)
        val currency = field(ctx, t("Валюта цен", "Price currency"), st.currency, InputType.TYPE_CLASS_TEXT)
        val contact = field(ctx, t("ID для заказов (ваш ID в мессенджере)", "Orders ID (your messenger ID)"), st.contactId, InputType.TYPE_CLASS_TEXT)
        box.addView(label(ctx, t("Контакты", "Contacts"), 4))
        box.addView(site)
        box.addView(phone)
        box.addView(address)
        box.addView(hours)
        box.addView(label(ctx, t("Продажи", "Sales")))
        box.addView(currency)
        box.addView(contact)
        box.addView(
            com.stoyko.smessenger.ui.Btn.tonal(ctx, t("Подставить мой ID", "Use my ID")) { contact.setText(myId) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 6)
                bottomMargin = dp(ctx, 6)
            },
        )
        AlertDialog.Builder(ctx)
            .setTitle(t("💼 Бизнес-информация", "💼 Business info"))
            .setView(scroll(ctx, box))
            .setPositiveButton(R.string.save) { _, _ ->
                val ok = save {
                    it.copy(
                        site = site.text.toString().trim(),
                        phone = phone.text.toString().trim(),
                        address = address.text.toString().trim(),
                        hours = hours.text.toString().trim(),
                        currency = currency.text.toString().trim().ifEmpty { "₽" },
                        contactId = contact.text.toString().trim().uppercase().filter { c -> c in '0'..'9' || c in 'A'..'F' },
                    )
                }
                Toast.makeText(ctx, if (ok) R.string.gs_saved else R.string.group_no_rights, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- статистика ----------------

    /** Статистика канала (администраторы): по данным, которые есть у вас на устройстве. */
    fun statsDialog(ctx: Context, chatId: String, posts: List<GroupMessage>, subscribers: Int) {
        val real = posts.filter { !com.stoyko.smessenger.chat.Markup.isAnyComment(it.message) && !com.stoyko.smessenger.chat.Markup.isGroupControl(it.message) && !com.stoyko.smessenger.chat.Polls.isVote(it.message) }
        val now = System.currentTimeMillis()
        val week = real.count { now - it.timestamp < 7 * 86_400_000L }
        val month = real.count { now - it.timestamp < 30 * 86_400_000L }
        val views = real.map { it to ChannelStats.views(ctx, chatId, it.msgUid) }
        val total = views.sumOf { it.second }
        val recent = views.filter { now - it.first.timestamp < 30 * 86_400_000L }
        val avg = if (recent.isEmpty()) 0 else recent.sumOf { it.second } / recent.size
        val reach = if (subscribers > 0) (avg * 100 / subscribers).coerceAtMost(100) else 0
        val reactions = real.sumOf { p -> if (p.msgUid.isBlank()) 0 else GroupReactions.counts(ctx, chatId, p.msgUid).sumOf { it.second } }
        val products = real.count { ChannelPost.meta(it.message)?.product != null }
        val box = column(ctx)
        fun tile(value: String, caption: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x1A5B4BDB)
            }
            addView(
                TextView(ctx).apply {
                    text = value
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ACCENT)
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(ctx).apply {
                    text = caption
                    textSize = 11f
                    gravity = Gravity.CENTER
                    alpha = 0.75f
                },
            )
        }
        fun rowOf(vararg tiles: View) = LinearLayout(ctx).apply {
            tiles.forEachIndexed { i, v ->
                addView(
                    v,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(ctx, 8)
                        topMargin = dp(ctx, 8)
                    },
                )
            }
        }
        box.addView(rowOf(tile(subscribers.toString(), t("подписчиков", "subscribers")), tile("$reach%", t("охват поста", "post reach"))))
        box.addView(rowOf(tile(week.toString(), t("постов за неделю", "posts this week")), tile(month.toString(), t("за месяц", "this month"))))
        box.addView(rowOf(tile(ChannelStats.short(total), t("просмотров всего", "total views")), tile(ChannelStats.short(avg), t("в среднем на пост", "avg per post"))))
        box.addView(rowOf(tile(ChannelStats.short(reactions), t("реакций", "reactions")), tile(products.toString(), t("товаров и услуг", "products"))))
        val top = views.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        if (top.isNotEmpty()) {
            box.addView(label(ctx, t("🏆 Самые просматриваемые", "🏆 Most viewed")))
            top.forEach { (p, n) ->
                box.addView(
                    TextView(ctx).apply {
                        text = "👁 " + ChannelStats.short(n) + "  ·  " + com.stoyko.smessenger.chat.Markup.snippet(p.message).ifEmpty { "📎 " + p.fileName }
                        textSize = 14f
                        maxLines = 2
                        setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
                    },
                )
            }
        }
        box.addView(
            TextView(ctx).apply {
                text = t(
                    "Просмотры считаются у подписчиков с этой версией приложения; охват - средние просмотры поста за 30 дней к числу подписчиков.",
                    "Views are counted from subscribers on this app version; reach is average post views over 30 days vs subscribers.",
                )
                textSize = 11f
                alpha = 0.6f
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 8))
            },
        )
        AlertDialog.Builder(ctx)
            .setTitle(t("📊 Статистика канала", "📊 Channel statistics"))
            .setView(scroll(ctx, box))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
