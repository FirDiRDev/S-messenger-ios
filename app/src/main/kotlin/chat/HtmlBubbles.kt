// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.groups.ChannelPost
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * HTML-пузыри: сообщение превращается в маленькое приложение, виджет или мини-сайт прямо в
 * чате - кнопки, карточки товаров, живые графики.
 *
 * Формат: HTML сжимается (deflate) и кладётся в заголовок сообщения (ChannelPost, ключ "h");
 * текст сообщения - подпись (её же видят старые версии). Размер ограничен одним сообщением Tox.
 *
 * Безопасность: страница рисуется в WebView без сети (blockNetworkLoads + CSP default-src 'none'
 * + перехват всех запросов), без доступа к файлам; переходы по ссылкам - только после
 * подтверждения. Скрипт может «ответить в чат» или скопировать текст только сразу после
 * нажатия человека на пузырь (не сам по себе) и не чаще раза в секунду.
 *
 * В приложении встроен «набор» (стили и функции), поэтому сами сообщения маленькие:
 *  - кнопки: `<button class=b data-reply="Да">Да</button>` (data-reply / data-copy / data-url),
 *    цвета `b g` (зелёная), `b r` (красная), `b o` (оранжевая), `b s` (стекло); ряд - `<div class=row>`;
 *  - карточка: `.card`, картинка-эмодзи `.pic`, цена `.pr`, старая цена `.old`, метка `.tag`;
 *  - график: `<canvas id=c></canvas><script>chart('c',[3,5,4,8],['Пн','Вт','Ср','Чт'],{type:'line'})</script>`,
 *    типы line / bar, анимация появления, касание показывает значение, {live:1} - график «дышит»
 *    и плавно дорисовывает новые значения (случайное блуждание вокруг последних).
 */
object HtmlBubbles {
    /** Предел заголовка сообщения в байтах (сообщение Tox - 1372 байта вместе с подписью). */
    const val MAX_PACKED = 1150

    // ------------------------------------------------------------------ формат

    fun pack(html: String): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(html.toByteArray(Charsets.UTF_8))
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun unpack(packed: String): String? = runCatching {
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        val inf = Inflater(true)
        inf.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        // Защита от «бомбы»: распакованный HTML не больше 64 КБ.
        while (!inf.finished() && out.size() < 65_536) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        out.toString("UTF-8")
    }.getOrNull()

    /** Содержимое пузыря: HTML и параметры (у живых сообщений), шаблон - если это живое сообщение. */
    class Content(val html: String, val params: org.json.JSONObject?, val tpl: Tpl?, val tplId: String?)

    private fun metaOf(raw: String) = ChannelPost.meta(if (raw.startsWith("⁣✎")) raw.substring(2) else raw)

    /** Живое сообщение из галереи («/»)? */
    fun isTemplate(raw: String): Boolean = runCatching { metaOf(raw)?.tpl != null }.getOrDefault(false)

    fun content(raw: String, ctx: Context?): Content? {
        val m = metaOf(raw) ?: return null
        m.tpl?.let { id ->
            val params = m.tplParams?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            return Content(template(ctx, id), params, TEMPLATES.firstOrNull { it.id == id }, id)
        }
        val packed = m.html ?: return null
        val html = cache[packed] ?: unpack(packed)?.also { cache[packed] = it } ?: return null
        return Content(html, null, null, null)
    }

    /** HTML пузыря из сообщения (или null): свой HTML или встроенный шаблон из галереи. */
    fun of(raw: String, ctx: Context? = null): String? = content(raw, ctx)?.html

    // ------------------------------------------------------------------ галерея живых сообщений

    /** Поле редактора живого сообщения: ключ параметра, подпись, значение по умолчанию, подсказка. */
    class Field(val key: String, private val ru: String, private val en: String, val def: String, val multi: Boolean = false, private val hRu: String = "", private val hEn: String = "") {
        val label get() = t(ru, en)
        val hint get() = t(hRu, hEn)
    }

    /** Встроенное живое сообщение: id (файл assets/bubbles/<id>.html), эмодзи, название, описание, поля. */
    class Tpl(val id: String, val emoji: String, private val ru: String, private val en: String, private val dRu: String, private val dEn: String, val fields: List<Field> = emptyList()) {
        val title get() = t(ru, en)
        val desc get() = t(dRu, dEn)
    }

    private fun f(key: String, ru: String, en: String, def: String, multi: Boolean = false, hRu: String = "", hEn: String = "") = Field(key, ru, en, def, multi, hRu, hEn)

    private val IMG_HINT_RU = "Ссылка https://… на картинку (необязательно). Картинка загружается из интернета при показе."
    private val IMG_HINT_EN = "An https://… image link (optional). The image is loaded from the internet when shown."

    val TEMPLATES = listOf(
        Tpl("launch", "🎉", "Праздник релиза", "Release party", "Конфетти: пушки, дождь и салют", "Confetti: cannons, rain and fireworks", listOf(
            f("head", "Заголовок", "Header", "Release party"),
            f("title", "Крупный текст", "Big text", "v2.0 вышла!"),
            f("sub", "Подпись", "Caption", "42 PR · 9 авторов · 0 регрессий"),
        )),
        Tpl("easing", "〰️", "Галерея анимаций", "Easing gallery", "14 кривых движения - гонка и графики", "14 motion curves - race and plots"),
        Tpl("music", "🎵", "Музыкальный плеер", "Music player", "Живой эквалайзер и синтезатор", "Live equalizer and a synth", listOf(
            f("tracks", "Треки", "Tracks", "Ночной город|S-wave|96\nНеон|Синти-поп|112\nРассвет|Эмбиент|80", true, "Строка = трек: Название|Исполнитель|темп 60-180 (до 6 треков)", "One track per line: Title|Artist|tempo 60-180 (up to 6)"),
        )),
        Tpl("pipeline", "🚀", "Деплой по шагам", "Deploy pipeline", "Сборка → тесты → выкатка с прогрессом", "Build → tests → rollout with progress", listOf(
            f("head", "Заголовок", "Header", "Деплой"),
            f("steps", "Шаги", "Steps", "Сборка|gradle assembleRelease|1.6\nТесты|412 тестов|2.2\nПодпись|apksigner v2+v3|0.9\nЗагрузка|S-messenger.apk · 27 МБ|1.8\nВыкатка|10% → 50% → 100%|2", true, "Строка = шаг: Название|описание|секунды (до 8 шагов)", "One step per line: Name|description|seconds (up to 8)"),
        )),
        Tpl("streaming", "✦", "Ответ, который печатается", "Streaming reply", "Текст появляется слово за словом, как у ИИ", "Text appears word by word, like an AI", listOf(
            f("name", "Кто отвечает", "Who answers", "S-AI"),
            f("q", "Вопрос", "Question", "Как работает S-messenger без серверов?", true),
            f("a", "Ответ", "Answer", "Каждый телефон - сам себе сервер. Сообщения идут **напрямую** от устройства к устройству по протоколу `Tox`, зашифрованные ключом, который есть только у вас и у собеседника.", true, "**жирный**, `код`, новая строка - абзац", "**bold**, `code`, a new line - a paragraph"),
        )),
        Tpl("clock", "🕰", "Живые часы", "Live clock", "Стрелки с «пружинкой» и часовые пояса", "Springy hands and time zones", listOf(
            f("zones", "Часовые пояса", "Time zones", "Лондон|0\nМосква|3\nТокио|9", true, "Строка = город: Название|смещение от UTC (до 4)", "One city per line: Name|UTC offset (up to 4)"),
        )),
        Tpl("linkpreview", "🔗", "Превью ссылки", "Link preview", "Мерцающая заготовка, потом карточка", "Shimmer skeleton, then the card", listOf(
            f("url", "Ссылка", "Link", "https://smessenger.app/blog/p2p", false, "Нажатие на карточку откроет её (после подтверждения)", "Tapping the card opens it (after confirmation)"),
            f("site", "Сайт", "Site", "S-messenger · Блог"),
            f("title", "Заголовок", "Title", "Мессенджер без серверов: как это работает"),
            f("desc", "Описание", "Description", "Прямые зашифрованные соединения между телефонами, узлы сети, обход блокировок - простыми словами.", true),
            f("img", "Картинка", "Image", "", false, IMG_HINT_RU, IMG_HINT_EN),
        )),
        Tpl("reactions", "❤️", "Живые реакции", "Live reactions", "Эмодзи всплывают, как в эфире", "Emoji float up like a live stream", listOf(
            f("title", "Заголовок", "Title", "Запуск S-messenger 5.0"),
            f("sub", "Подпись", "Caption", "Реакции зрителей в реальном времени"),
            f("emoji", "Эмодзи через пробел", "Emoji, space-separated", "❤️ 🔥 😂 👏 😮 🎉", false, "До 6 штук", "Up to 6"),
        )),
        Tpl("activity", "🏃", "Кольца активности", "Activity rings", "Кольца заполняются, цифры бегут", "Rings fill up, numbers count", listOf(
            f("head", "Заголовок", "Header", "🏃 Активность сегодня"),
            f("rings", "Кольца", "Rings", "Движение|540|700|ккал\nУпражнения|38|30|мин\nРазминка|9|12|ч", true, "Строка = кольцо: Название|значение|цель|единица (до 3)", "One ring per line: Name|value|goal|unit (up to 3)"),
        )),
        Tpl("slots", "🎰", "Слот-машина", "Slot machine", "Барабаны с инерцией и джекпот", "Reels with inertia and a jackpot", listOf(
            f("head", "Заголовок", "Header", "🎰 Слоты"),
            f("symbols", "Символы через пробел", "Symbols, space-separated", "🍒 🍋 🔔 💎 7️⃣ 🍀 ⭐", false, "От 3 до 9 эмодзи", "3 to 9 emoji"),
        )),
        Tpl("counter", "🔢", "Счётчик", "Counter", "Цифры прокручиваются, как на табло", "Digits roll like a scoreboard", listOf(
            f("head", "Заголовок", "Header", "Счётчик"),
            f("start", "Начальное число", "Start value", "0"),
        )),
        Tpl("tictactoe", "❌", "Крестики-нолики", "Tic-tac-toe", "Сыграйте с ботом прямо в сообщении", "Play a bot right in the message"),
        Tpl("poll", "📊", "Опрос", "Poll", "Полосы растут, проценты считаются", "Bars grow, percentages count", listOf(
            f("q", "Вопрос", "Question", "Какую функцию сделать следующей?", true),
            f("options", "Варианты", "Options", "🎥 Видеокружки|41\n🗂 Папки чатов|27\n🤖 Боты|19\n🌍 Переводчик|13", true, "Строка = вариант: Текст|сколько голосов уже есть (до 8)", "One option per line: Text|existing votes (up to 8)"),
        )),
        Tpl("boardingpass", "✈️", "Посадочный талон", "Boarding pass", "Переворачивается - на обороте штрихкод", "Flips over to a barcode", listOf(
            f("airline", "Авиакомпания", "Airline", "✈ S-AIR · Посадочный"),
            f("date", "Дата и время", "Date and time", "11 окт · 21:40"),
            f("from", "Откуда (код)", "From (code)", "SVO"),
            f("fromCity", "Откуда (город)", "From (city)", "Москва"),
            f("to", "Куда (код)", "To (code)", "DXB"),
            f("toCity", "Куда (город)", "To (city)", "Дубай"),
            f("flight", "Рейс", "Flight", "SA 214"),
            f("gate", "Выход", "Gate", "B 17"),
            f("seat", "Место", "Seat", "12A"),
            f("boarding", "Посадка", "Boarding", "21:05"),
            f("name", "Пассажир", "Passenger", "ИВАНОВ ИВАН"),
        )),
        Tpl("product", "👟", "Карточка товара", "Product card", "Цвета, размеры, полёт в корзину", "Colours, sizes, fly-to-cart", listOf(
            f("name", "Название", "Name", "Кроссовки Air Run"),
            f("desc", "Описание", "Description", "Лёгкие, дышащие, для бега и города", true),
            f("price", "Цена", "Price", "2 990 ₽"),
            f("old", "Старая цена", "Old price", "4 290 ₽", false, "Пусто - без старой цены", "Empty - no old price"),
            f("badge", "Метка", "Badge", "−30%", false, "Пусто - без метки", "Empty - no badge"),
            f("rating", "Рейтинг", "Rating", "★ 4.9 · 1.2k"),
            f("sizes", "Размеры через пробел", "Sizes, space-separated", "40 41 42 43 44"),
            f("emoji", "Эмодзи-картинка", "Emoji picture", "👟"),
            f("img", "Фото товара", "Product photo", "", false, IMG_HINT_RU, IMG_HINT_EN),
            f("link", "Ссылка «Купить»", "«Buy» link", "", false, "https://… - кнопка откроет её; пусто - «В корзину»", "https://… - the button opens it; empty - «Add to cart»"),
        )),
        Tpl("revenue", "📈", "Выручка", "Revenue", "Столбцы и цифры анимируются", "Animated bars and numbers", listOf(
            f("head", "Заголовок", "Header", "Выручка"),
            f("values", "Значения", "Values", "42000 58000 51000 73000 69000 94000 88000", false, "Числа через пробел (до 12) - высота столбцов", "Numbers separated by spaces (up to 12) - bar heights"),
            f("labels", "Подписи через запятую", "Labels, comma-separated", "пн, вт, ср, чт, пт, сб, вс"),
            f("unit", "Единица", "Unit", "₽"),
            f("growth", "Рост, %", "Growth, %", "18"),
            f("orders", "Заказы", "Orders", "312"),
            f("conv", "Конверсия, %", "Conversion, %", "3.4"),
        )),
        Tpl("faq", "❓", "Вопросы и ответы", "FAQ", "Плавно раскрывающиеся ответы", "Smoothly expanding answers", listOf(
            f("head", "Заголовок", "Header", "❓ Частые вопросы"),
            f("items", "Вопросы", "Questions", "Нужен ли номер телефона?|Нет. Ваш адрес - это ключ из 76 символов (Tox ID).\nГде хранятся сообщения?|Только на ваших устройствах.", true, "Строка = Вопрос|Ответ (до 8)", "One per line: Question|Answer (up to 8)"),
        )),
    )

    private val tplCache = HashMap<String, String>()

    private fun template(ctx: Context?, id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '_' }.take(32)
        tplCache[safe]?.let { return it }
        val html = runCatching { ctx?.assets?.open("bubbles/$safe.html")?.bufferedReader()?.use { it.readText() } }.getOrNull()
        if (html != null) {
            tplCache[safe] = html
            return html
        }
        // Шаблона нет в этой версии - подсказываем обновиться.
        return "<div class=card><div class=t>✨ " + esc(safe) + "</div><div class=m>" +
            t("Это живое сообщение появилось в новой версии S-messenger - обновите приложение, чтобы его увидеть.", "This live message needs a newer S-messenger - update the app to see it.") +
            "</div></div>"
    }

    /** Предел сообщения (байт): одно сообщение Tox - 1372 байта. */
    private const val MAX_MESSAGE_BYTES = 1300

    /**
     * Сообщение с живым шаблоном [id] (сам HTML не передаётся - он встроен в приложение) и
     * параметрами [params] (только изменённые поля). Подпись - заголовок сообщения.
     */
    fun templateMessage(id: String, params: org.json.JSONObject? = null): String {
        val tp = TEMPLATES.firstOrNull { it.id == id }
        val p = params?.takeIf { it.length() > 0 }
        val named = listOf("title", "head", "q", "name").firstNotNullOfOrNull { k -> p?.optString(k)?.takeIf { it.isNotBlank() } }
        val caption = "✨ " + (named ?: tp?.title ?: id).replace('\n', ' ').take(60)
        return ChannelPost.wrap(ChannelPost.Meta(tpl = id, tplParams = p?.toString()), caption)
    }

    /** Галерея: список живых сообщений; нажатие - просмотр вживую, «Изменить» и «Отправить». */
    fun gallery(ctx: Context, onDone: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(t("✨ Живые сообщения", "✨ Live messages"))
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNeutralButton(t("Как это работает", "How it works")) { _, _ -> showHelp(ctx) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        TEMPLATES.forEachIndexed { i, tp ->
            val row = templateRow(ctx, tp) {
                dialog.dismiss()
                preview(ctx, tp, null, onDone)
            }
            list.addView(row)
            // Строки появляются по очереди.
            row.alpha = 0f
            row.translationY = 10 * d
            row.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 22L).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        dialog.show()
    }

    /** Строка шаблона: значок, название, описание. */
    fun templateRow(ctx: Context, tp: Tpl, click: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (7 * d).toInt(), (8 * d).toInt(), (7 * d).toInt())
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            addView(emojiBadge(ctx, tp.emoji, 40), LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "/" + tp.id + "  ·  " + tp.title + if (tp.fields.isNotEmpty()) "  ✏️" else ""
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = tp.desc
                    textSize = 12.5f
                    alpha = 0.7f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { click() }
        }
    }

    private fun emojiBadge(ctx: Context, emoji: String, sizeDp: Int) = TextView(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        text = emoji
        textSize = sizeDp * 0.47f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = sizeDp * 0.3f * d
            colors = intArrayOf(0xFF6366F1.toInt(), 0xFF22D3EE.toInt())
            orientation = GradientDrawable.Orientation.TL_BR
        }
    }

    private fun bubbleBox(ctx: Context) = FrameLayout(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        background = GradientDrawable().apply {
            cornerRadius = 18 * d
            colors = intArrayOf(0xFF2A2650.toInt(), 0xFF1C1A33.toInt())
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke((1 * d).toInt().coerceAtLeast(1), 0x33FFFFFF)
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    /** Просмотр шаблона вживую перед отправкой: «Отправить», «✏️ Изменить», «Назад». */
    fun preview(ctx: Context, tp: Tpl, params: org.json.JSONObject?, onDone: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val box = bubbleBox(ctx)
        val web = webView(ctx, template(ctx, tp.id), null, (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt(), params = params)
        box.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (160 * d).toInt()))
        val wrap = FrameLayout(ctx).apply {
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), 0)
            addView(box)
        }
        val b = AlertDialog.Builder(ctx)
            .setTitle(tp.emoji + " " + tp.title)
            .setView(ScrollView(ctx).apply { addView(wrap) })
            .setPositiveButton(t("Отправить", "Send")) { _, _ -> onDone(templateMessage(tp.id, params)) }
            .setNegativeButton(t("Назад", "Back")) { _, _ -> gallery(ctx, onDone) }
        if (tp.fields.isNotEmpty()) {
            b.setNeutralButton(t("✏️ Изменить", "✏️ Edit")) { _, _ ->
                editTemplate(ctx, tp, params) { p -> onDone(templateMessage(tp.id, p)) }
            }
        }
        b.create().apply { setOnDismissListener { runCatching { web.destroy() } } }.show()
    }

    /** Изменить уже отправленное живое сообщение: [onDone] получает новый текст сообщения целиком. */
    fun editSent(ctx: Context, raw: String, onDone: (String) -> Unit) {
        val c = content(raw, ctx) ?: return
        val tp = c.tpl
        if (tp == null || tp.fields.isEmpty()) {
            Toast.makeText(ctx, t("В этом живом сообщении нечего менять", "This live message has nothing to edit"), Toast.LENGTH_SHORT).show()
            return
        }
        editTemplate(ctx, tp, c.params, sendLabel = t("Сохранить", "Save")) { p -> onDone(templateMessage(tp.id, p)) }
    }

    /**
     * Редактор живого сообщения: сверху - живой просмотр (обновляется, пока печатаете), ниже -
     * поля шаблона (текст, числа, ссылки, картинки) с подсказками и счётчик размера.
     * В сообщение попадают только поля, отличающиеся от образца.
     */
    fun editTemplate(ctx: Context, tp: Tpl, current: org.json.JSONObject?, sendLabel: String = t("Отправить", "Send"), onDone: (org.json.JSONObject?) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val box = bubbleBox(ctx)
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val size = TextView(ctx).apply {
            textSize = 12f
            alpha = 0.75f
            setPadding(0, dp(6), 0, dp(2))
        }
        col.addView(size)
        val edits = LinkedHashMap<Field, EditText>()
        for (fl in tp.fields) {
            col.addView(TextView(ctx).apply {
                text = fl.label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, 0)
            })
            val e = EditText(ctx).apply {
                setText(current?.optString(fl.key)?.takeIf { current.has(fl.key) } ?: fl.def)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    (if (fl.multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0) or
                    (if (fl.key == "img" || fl.key == "link" || fl.key == "url") InputType.TYPE_TEXT_VARIATION_URI else 0)
                if (fl.multi) {
                    minLines = 2
                    maxLines = 8
                }
            }
            col.addView(e)
            if (fl.hint.isNotEmpty()) {
                col.addView(TextView(ctx).apply {
                    text = fl.hint
                    textSize = 11.5f
                    alpha = 0.65f
                })
            }
            edits[fl] = e
        }
        fun params(): org.json.JSONObject? {
            val o = org.json.JSONObject()
            for ((fl, e) in edits) {
                val v = e.text.toString().trimEnd()
                if (v != fl.def) o.put(fl.key, v.take(600))
            }
            return o.takeIf { it.length() > 0 }
        }
        var web: WebView? = null
        fun refresh() {
            web?.let { runCatching { box.removeView(it); it.destroy() } }
            val w = webView(ctx, template(ctx, tp.id), null, (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt(), params = params())
            box.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (box.height.takeIf { it > 0 } ?: dp(160))))
            web = w
            val bytes = templateMessage(tp.id, params()).toByteArray().size
            size.text = t("Размер: ", "Size: ") + "$bytes / $MAX_MESSAGE_BYTES " + t("байт", "bytes") + if (bytes > MAX_MESSAGE_BYTES) t(" - слишком много, сократите текст", " - too much, shorten the text") else ""
            size.setTextColor(if (bytes > MAX_MESSAGE_BYTES) 0xFFFF6B6B.toInt() else 0xFFB8B4D8.toInt())
        }
        // Просмотр обновляется сам через полсекунды после последней правки.
        val again = Runnable { com.stoyko.smessenger.ui.Safe.run("tpl-preview") { refresh() } }
        for (e in edits.values) {
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(again)
                    main.postDelayed(again, 600)
                }
            })
        }
        refresh()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("✏️ " + tp.emoji + " " + tp.title)
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton(sendLabel, null)
            .setNeutralButton(t("Как это работает", "How it works"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showHelp(ctx) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = params()
            if (templateMessage(tp.id, p).toByteArray().size > MAX_MESSAGE_BYTES) {
                Toast.makeText(ctx, t("Слишком длинно - сократите текст", "Too long - shorten the text"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onDone(p)
        }
        dialog.setOnDismissListener {
            main.removeCallbacks(again)
            web?.let { runCatching { it.destroy() } }
        }
    }

    /** Справка «?»: как отправлять, менять и смотреть живые сообщения. */
    fun showHelp(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle(t("✨ Живые сообщения - как это работает", "✨ Live messages - how it works"))
            .setMessage(ctx.getString(com.stoyko.smessenger.R.string.live_help_text))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(t("Открыть галерею", "Open gallery"), null)
            .show()
            .also { dl ->
                dl.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                dl.findViewById<TextView>(android.R.id.message)?.textSize = 14f
            }
    }

    /**
     * Набор «/»: в пустом поле ввода пишем «/» - всплывает список живых сообщений (фильтр по
     * набранному). Нажатие - просмотр с кнопками «Отправить» и «✏️ Изменить».
     */
    fun attachSlash(input: EditText, send: (String) -> Unit) {
        val ctx = input.context
        val d = ctx.resources.displayMetrics.density
        var popup: android.widget.PopupWindow? = null
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            addView(list)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(0xF2201E36.toInt())
                setStroke((1 * d).toInt().coerceAtLeast(1), 0x33FFFFFF)
            }
            elevation = 10 * d
        }
        fun hide() {
            popup?.dismiss()
            popup = null
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                com.stoyko.smessenger.ui.Safe.run("slash") { onText(e) }
            }

            private fun onText(e: Editable?) {
                val txt = e?.toString().orEmpty()
                val m = Regex("^/([A-Za-zА-Яа-я0-9_]*)$").find(txt)
                if (m == null) {
                    hide()
                    return
                }
                val q = m.groupValues[1].lowercase()
                val found = TEMPLATES.filter { q.isEmpty() || it.id.startsWith(q) || it.title.lowercase().contains(q) }
                if (found.isEmpty()) {
                    hide()
                    return
                }
                list.removeAllViews()
                found.forEach { tp ->
                    list.addView(templateRow(ctx, tp) {
                        hide()
                        input.setText("")
                        preview(ctx, tp, null, send)
                    }.also { row ->
                        // Список на тёмной подложке - светлый текст.
                        ((row as? LinearLayout)?.getChildAt(1) as? LinearLayout)?.let { col ->
                            (col.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
                            (col.getChildAt(1) as? TextView)?.setTextColor(0xFFCBD5E1.toInt())
                        }
                    })
                }
                val h = minOf((found.size * 56 * d).toInt() + (8 * d).toInt(), (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt())
                val w = input.rootView.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
                val p = popup
                if (p == null) {
                    popup = android.widget.PopupWindow(scroll, w - (16 * d).toInt(), h, false).apply {
                        isOutsideTouchable = true
                        animationStyle = android.R.style.Animation_Dialog
                        inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
                        runCatching { showAtLocation(input.rootView, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, input.rootView.height - IntArray(2).also { input.getLocationInWindow(it) }[1] + (6 * d).toInt()) }
                    }
                } else {
                    runCatching { p.update(w - (16 * d).toInt(), h) }
                }
            }
        })
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = hide()
        })
    }

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
    }

    /** Сообщение: HTML в заголовке, [caption] - видимый текст. null - не помещается. */
    fun message(html: String, caption: String): String? {
        val packed = pack(html.trim())
        if (packed.length > MAX_PACKED) return null
        return ChannelPost.wrap(ChannelPost.Meta(html = packed), caption.ifBlank { "🧩" })
    }

    // ------------------------------------------------------------------ страница

    private const val CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data: https:; " +
        "style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; media-src data:; form-action 'none'; frame-src 'none'\">"

    private const val KIT_CSS = ":root{color-scheme:dark}*{box-sizing:border-box}" +
        "html,body{margin:0;background:transparent}" +
        "body{padding:10px;font:14px/1.35 Roboto,-apple-system,sans-serif;color:#F4F2FF;overflow:hidden;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}" +
        ".t{font-weight:700;font-size:16px;margin:0 0 6px}.m{opacity:.72;font-size:13px}" +
        ".b{display:block;width:100%;margin:6px 0 0;padding:10px 12px;border:0;border-radius:12px;" +
        "background:linear-gradient(135deg,#8B7CFF,#5B4CE0);color:#fff;font:600 14px Roboto,sans-serif;" +
        "box-shadow:0 2px 10px rgba(91,76,224,.35);transition:transform .14s cubic-bezier(.3,1.6,.5,1),filter .14s}" +
        ".b:active{transform:scale(.95);filter:brightness(1.15)}" +
        ".b.g{background:linear-gradient(135deg,#34D399,#059669);box-shadow:0 2px 10px rgba(5,150,105,.35)}" +
        ".b.r{background:linear-gradient(135deg,#FB7185,#E11D48);box-shadow:0 2px 10px rgba(225,29,72,.35)}" +
        ".b.o{background:linear-gradient(135deg,#FBBF24,#EA580C);box-shadow:0 2px 10px rgba(234,88,12,.35)}" +
        ".b.s{background:rgba(255,255,255,.12);box-shadow:none}" +
        ".row{display:flex;gap:6px}.row .b{flex:1}" +
        ".card{border-radius:16px;background:rgba(255,255,255,.07);padding:12px;animation:sbIn .5s cubic-bezier(.2,1.4,.4,1)}" +
        ".pic{font-size:46px;text-align:center;background:linear-gradient(135deg,#2a2650,#4b3a9a);border-radius:12px;padding:14px 0;margin-bottom:8px}" +
        ".pr{font-size:20px;font-weight:800;color:#A7F3D0}.old{text-decoration:line-through;opacity:.5;margin-left:6px;font-size:14px}" +
        ".tag{display:inline-block;padding:2px 8px;border-radius:8px;background:#F43F5E;color:#fff;font-size:12px;font-weight:700;margin-left:6px}" +
        "canvas{width:100%;height:170px;display:block}" +
        "@keyframes sbIn{from{transform:scale(.92);opacity:0}to{transform:none;opacity:1}}"

    // Набор функций: ответ/копирование/ссылка, кнопки по data-атрибутам, графики.
    private const val KIT_JS = "function reply(t){SB.reply(String(t))}function copy(t){SB.copy(String(t))}" +
        "function go(u){SB.open(String(u))}function toast(t){SB.toast(String(t))}" +
        "document.addEventListener('click',function(e){var b=e.target.closest('[data-reply],[data-copy],[data-url]');if(!b)return;" +
        "if(b.dataset.reply!=null)reply(b.dataset.reply);else if(b.dataset.copy!=null)copy(b.dataset.copy);else go(b.dataset.url)});" +
        "var SBL=SB.lite();" +
        // Параметры живого сообщения (их задаёт отправитель в редакторе): P.ключ.
        "var P=window.P||{};function pv(k,d){var v=P[k];return v==null||v===''?d:String(v)}" +
        "function pl(k,d){var v=P[k];if(v==null||String(v).trim()==='')return d;return String(v).split('\\n').map(function(s){return s.split('|').map(function(x){return x.trim()})}).filter(function(a){return a[0]!==''})}" +
        "function pn(k,d){var v=P[k];if(v==null||String(v).trim()==='')return d;var a=String(v).split(/[,;\\s]+/).map(function(x){return parseFloat(x.replace(',','.'))}).filter(function(x){return !isNaN(x)});return a.length?a:d}" +
        "function H(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;')}" +
        "function chart(id,v,l,o){o=o||{};var c=document.getElementById(id);if(!c)return;" +
        "if(!c.clientWidth){setTimeout(function(){chart(id,v,l,o)},120);return}var x=c.getContext('2d'),dpr=window.devicePixelRatio||1," +
        "W=c.clientWidth,H=c.clientHeight;c.width=W*dpr;c.height=H*dpr;x.scale(dpr,dpr);v=v.slice();l=(l||[]).slice();" +
        "var col=o.color||'#8B7CFF',bar=o.type=='bar',t0=performance.now(),sel=-1,P=22;" +
        "function mx(){return Math.max.apply(0,v)*1.15||1}" +
        "function px(i){return P+(W-2*P)*(bar?(i+.5)/v.length:(v.length>1?i/(v.length-1):.5))}" +
        "function py(y,k){return H-P-(H-2*P)*(y/mx())*k}" +
        "function draw(now){var k=SBL?1:Math.min(1,(now-t0)/900);k=1-Math.pow(1-k,3);x.clearRect(0,0,W,H);" +
        "x.strokeStyle='rgba(255,255,255,.08)';x.lineWidth=1;for(var g=0;g<4;g++){var gy=P+(H-2*P)*g/3;x.beginPath();x.moveTo(P,gy);x.lineTo(W-P,gy);x.stroke()}" +
        "x.fillStyle='rgba(255,255,255,.55)';x.font='11px sans-serif';x.textAlign='center';" +
        "for(var i=0;i<l.length&&i<v.length;i++)if(l.length<9||i%Math.ceil(l.length/8)==0)x.fillText(l[i],px(i),H-6);" +
        "var gr=x.createLinearGradient(0,P,0,H-P);gr.addColorStop(0,col);gr.addColorStop(1,col+'22');" +
        "if(bar){var bw=(W-2*P)/v.length*.62;for(var i=0;i<v.length;i++){var kk=Math.max(0,Math.min(1,k*1.6-i*.6/v.length));" +
        "x.fillStyle=i==sel?'#fff':gr;var y=py(v[i],kk),r=Math.min(6,bw/2);x.beginPath();x.moveTo(px(i)-bw/2,H-P);x.lineTo(px(i)-bw/2,y+r);" +
        "x.quadraticCurveTo(px(i)-bw/2,y,px(i)-bw/2+r,y);x.lineTo(px(i)+bw/2-r,y);x.quadraticCurveTo(px(i)+bw/2,y,px(i)+bw/2,y+r);x.lineTo(px(i)+bw/2,H-P);x.fill()}}" +
        "else{x.beginPath();for(var i=0;i<v.length;i++){var X=px(i),Y=py(v[i],k);if(!i)x.moveTo(X,Y);else{var X0=px(i-1),Y0=py(v[i-1],k);x.bezierCurveTo((X0+X)/2,Y0,(X0+X)/2,Y,X,Y)}}" +
        "x.lineWidth=2.5;x.strokeStyle=col;x.lineJoin='round';x.stroke();x.lineTo(px(v.length-1),H-P);x.lineTo(px(0),H-P);x.closePath();" +
        "var ga=x.createLinearGradient(0,P,0,H-P);ga.addColorStop(0,col+'66');ga.addColorStop(1,col+'00');x.fillStyle=ga;x.fill();" +
        "var lx=px(v.length-1),ly=py(v[v.length-1],k),pu=SBL||!o.live?0:(Math.sin(now/300)+1)/2;x.fillStyle=col+'44';x.beginPath();x.arc(lx,ly,5+4*pu,0,7);x.fill();" +
        "x.fillStyle='#fff';x.beginPath();x.arc(lx,ly,3.5,0,7);x.fill()}" +
        "if(sel>=0){var sx=px(sel),sy=py(v[sel],1),s=(o.prefix||'')+v[sel]+(o.suffix||'');x.font='bold 12px sans-serif';var tw=x.measureText(s).width+14;" +
        "x.fillStyle='rgba(20,18,40,.92)';x.fillRect(Math.min(W-tw,Math.max(0,sx-tw/2)),Math.max(0,sy-30),tw,22);x.fillStyle='#fff';x.textAlign='center';" +
        "x.fillText(s,Math.min(W-tw/2,Math.max(tw/2,sx)),Math.max(15,sy-15))}" +
        "if(!SBL&&(k<1||o.live))requestAnimationFrame(draw)}" +
        "c.addEventListener('touchstart',function(e){var r=c.getBoundingClientRect(),tx=e.touches[0].clientX-r.left,b=0,d=1e9;" +
        "for(var i=0;i<v.length;i++){var dd=Math.abs(px(i)-tx);if(dd<d){d=dd;b=i}}sel=b;if(SBL)draw(performance.now())},{passive:true});" +
        "if(o.live&&!SBL)setInterval(function(){var a=v[v.length-1],s=0;for(var i=0;i<v.length;i++)s+=v[i];var m=s/v.length;" +
        "v.push(Math.max(0,Math.round((a+(m-a)*.25+(Math.random()-.5)*m*.3)*10)/10));v.shift();l=[]},1600);" +
        "requestAnimationFrame(draw)}"

    private const val SIZE_JS = "<script>(function(){function h(){SB.height(Math.ceil(document.body.getBoundingClientRect().height))}" +
        "window.addEventListener('load',h);setTimeout(h,60);try{new ResizeObserver(h).observe(document.body)}catch(e){}})()</script>"

    fun page(html: String, params: org.json.JSONObject? = null): String {
        // Параметры - в теге script: «</» экранируем, чтобы текст не закрыл тег.
        val p = (params?.toString() ?: "{}").replace("</", "<\\/")
        return "<!doctype html><html><head><meta charset=utf-8>$CSP" +
            "<meta name=viewport content=\"width=device-width,initial-scale=1,maximum-scale=1\">" +
            "<style>$KIT_CSS</style><script>window.P=$p;</script><script>$KIT_JS</script></head><body>$html$SIZE_JS</body></html>"
    }

    // ------------------------------------------------------------------ WebView

    private val main = Handler(Looper.getMainLooper())

    /** Мост для скрипта страницы. Действия - только сразу после касания человека. */
    private class Bridge(val web: WebView, val host: MsgButtons.Host?, val onHeight: (Int) -> Unit) {
        @Volatile var lastTouch = 0L
        @Volatile var lastReply = 0L

        private fun gesture() = SystemClock.uptimeMillis() - lastTouch < 1500

        @JavascriptInterface
        fun reply(text: String) {
            val t = text.trim().take(300)
            if (t.isEmpty() || !gesture()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastReply < 1000) return
            lastReply = now
            main.post {
                val send = host?.send
                if (send != null) send(t) else Toast.makeText(web.context, t, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            if (!gesture()) return
            main.post {
                val cm = web.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
                cm.setPrimaryClip(ClipData.newPlainText("bubble", text.take(4000)))
                Toast.makeText(web.context, t("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun open(url: String) {
            if (!gesture()) return
            main.post { confirmOpen(web.context, url) }
        }

        @JavascriptInterface
        fun toast(text: String) {
            if (!gesture()) return
            main.post { Toast.makeText(web.context, text.take(200), Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun lite(): Boolean = com.stoyko.smessenger.ui.LiteMode.on

        @JavascriptInterface
        fun height(px: Int) {
            main.post { onHeight(px) }
        }
    }

    private fun confirmOpen(ctx: Context, url: String) {
        val u = ChannelPost.normalizeUrl(url).take(500)
        val scheme = Uri.parse(u).scheme?.lowercase()
        if (scheme !in setOf("http", "https", "tel", "mailto", "geo")) return
        AlertDialog.Builder(ctx)
            .setTitle(t("Открыть ссылку?", "Open link?"))
            .setMessage(u)
            .setPositiveButton(t("Открыть", "Open")) { _, _ ->
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Песочница: без сети, файлов и окон; ссылки - через подтверждение. */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun webView(ctx: Context, html: String, host: MsgButtons.Host?, maxHeightPx: Int, onHeight: ((Int) -> Unit)? = null, params: org.json.JSONObject? = null): WebView {
        val d = ctx.resources.displayMetrics.density
        val web = WebView(ctx)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            // Сеть - только для картинок по https (CSP и перехват ниже не пускают ничего другого).
            blockNetworkLoads = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.hasGesture()) confirmOpen(ctx, request.url.toString())
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val s = request.url.scheme?.lowercase()
                // Встроенные данные (data:) и картинки по https; всё остальное - пустой ответ.
                if (s == "data") return null
                if (s == "https" && request.method.equals("GET", true) && isImage(request)) return null
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
        }
        val bridge = Bridge(web, host) { px ->
            val h = (px * d).toInt().coerceIn((40 * d).toInt(), maxHeightPx)
            val lp = web.layoutParams
            if (lp != null && lp.height != h) {
                // Высота меняется плавно (пузырь «раскрывается»), а не скачком.
                heightAnims.remove(web)?.cancel()
                if (!web.isAttachedToWindow || lp.height <= 0 || com.stoyko.smessenger.ui.LiteMode.on) {
                    lp.height = h
                    web.layoutParams = lp
                } else {
                    heightAnims[web] = android.animation.ValueAnimator.ofInt(lp.height, h).apply {
                        duration = 260
                        interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                        addUpdateListener {
                            val l = web.layoutParams ?: return@addUpdateListener
                            l.height = it.animatedValue as Int
                            web.layoutParams = l
                        }
                        start()
                    }
                }
            }
            onHeight?.invoke(h)
        }
        web.addJavascriptInterface(bridge, "SB")
        web.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) bridge.lastTouch = SystemClock.uptimeMillis()
            false
        }
        // Ушёл с экрана (прокрутили) - анимации и таймеры страницы на паузе: не тратим батарею.
        web.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = runCatching { web.onResume() }.let { }
            override fun onViewDetachedFromWindow(v: View) = runCatching { web.onPause() }.let { }
        })
        // Долгое нажатие - меню сообщения (реакции, удалить у всех, изменить), без выделения текста.
        web.isLongClickable = true
        web.setOnLongClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            forwardLongPress(v)
            true
        }
        web.loadDataWithBaseURL("about:blank", page(html, params), "text/html", "utf-8", null)
        return web
    }

    private val heightAnims = java.util.WeakHashMap<WebView, android.animation.ValueAnimator>()

    private fun isImage(r: WebResourceRequest): Boolean {
        val accept = r.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
        val path = r.url.path.orEmpty().lowercase()
        return accept.startsWith("image/") || Regex("\\.(png|jpe?g|gif|webp|avif|bmp)$").containsMatchIn(path)
    }

    /** Долгое нажатие на пузырь - как на само сообщение (меню списка сообщений). */
    private fun forwardLongPress(v: View): Boolean {
        var child: View = v
        var p = v.parent
        while (p != null && p !is android.widget.AdapterView<*>) {
            if (p is View) child = p
            p = p.parent
        }
        val list = p as? android.widget.AdapterView<*> ?: return false
        val pos = runCatching { list.getPositionForView(child) }.getOrDefault(android.widget.AdapterView.INVALID_POSITION)
        if (pos == android.widget.AdapterView.INVALID_POSITION) return false
        return list.onItemLongClickListener?.onItemLongClick(list, child, pos, list.getItemIdAtPosition(pos)) ?: false
    }

    // ------------------------------------------------------------------ пузырь в чате

    private const val TAG_BOX = "html_bubble"

    /**
     * Чтобы чат не тормозил: в списке пузырь - лёгкая карточка (значок, название, «▶»). Живой
     * WebView создаётся, только когда нажали на карточку или пришло самое новое сообщение;
     * одновременно живых не больше [MAX_LIVE] - самый старый снова становится карточкой.
     */
    private const val MAX_LIVE = 2

    private class Slot(val key: String, val box: FrameLayout, var web: WebView? = null)

    private val slots = object : LinkedHashMap<String, Slot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>?): Boolean {
            if (size <= 24) return false
            eldest?.value?.let { sl ->
                (sl.box.parent as? ViewGroup)?.removeView(sl.box)
                sl.web?.let { runCatching { it.destroy() } }
            }
            return true
        }
    }

    /** Живые сейчас (порядок - от старого к новому). */
    private val live = ArrayList<String>()

    /** Высота содержимого по ключу - карточка сразу нужного размера, без прыжков списка. */
    private val heights = HashMap<String, Int>()

    /** Экран чата закрыт - освободить WebView (они держат ссылку на экран). */
    fun clear() {
        slots.values.forEach { sl ->
            (sl.box.parent as? ViewGroup)?.removeView(sl.box)
            sl.web?.let { runCatching { it.destroy() } }
        }
        slots.clear()
        live.clear()
    }

    /** Показать HTML-пузырь под [anchor] (текст сообщения - подпись). Без HTML - убрать. */
    fun bind(row: ViewGroup, anchor: View, raw: String, host: MsgButtons.Host?, autoPlay: Boolean = false) {
        val old = row.findViewWithTag<View>(TAG_BOX)
        val c = runCatching { content(raw, row.context) }.getOrNull()
        if (c == null) {
            old?.let { row.removeView(it) }
            return
        }
        val key = raw.hashCode().toString() + ":" + raw.length
        if (old != null && old.getTag(com.stoyko.smessenger.R.id.tag_card_sig) == key) return
        old?.let { row.removeView(it) }
        val ctx = row.context
        val d = ctx.resources.displayMetrics.density
        val w = minOf((300 * d).toInt(), (ctx.resources.displayMetrics.widthPixels * 0.82f).toInt())
        val slot = slots[key]?.takeIf { it.box.context === ctx } ?: Slot(key, bubbleBox(ctx)).also {
            slots[key] = it
            it.box.addView(placeholder(ctx, c, key) { activate(ctx, it, c, host) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val box = slot.box
        (box.parent as? ViewGroup)?.removeView(box)
        box.tag = TAG_BOX
        box.setTag(com.stoyko.smessenger.R.id.tag_card_sig, key)
        val lp = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = (anchor.layoutParams as? LinearLayout.LayoutParams)?.gravity ?: Gravity.START
            topMargin = (4 * d).toInt()
        }
        val i = row.indexOfChild(anchor)
        row.addView(box, if (i >= 0) i + 1 else row.childCount, lp)
        // Самое новое сообщение оживает само (когда список успокоится), остальные - по нажатию.
        if (autoPlay && slot.web == null && !com.stoyko.smessenger.ui.LiteMode.on) {
            box.postDelayed({
                if (box.isAttachedToWindow && slot.web == null) com.stoyko.smessenger.ui.Safe.run("html-live") { activate(ctx, slot, c, host) }
            }, 450)
        }
    }

    /** Лёгкая карточка вместо WebView: значок, название, подсказка «нажмите». */
    private fun placeholder(ctx: Context, c: Content, key: String, onTap: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tp = c.tpl
        val emoji = tp?.emoji ?: "🧩"
        val title = c.params?.let { p -> listOf("title", "head", "q", "name").firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } } }
            ?: tp?.title ?: t("Мини-приложение", "Mini app")
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = heights[key] ?: 0
            addView(emojiBadge(ctx, emoji, 46), LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = title.replace('\n', ' ')
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = (tp?.desc?.let { "$it · " } ?: "") + t("нажмите, чтобы оживить", "tap to play")
                    textSize = 12.5f
                    setTextColor(0xFFB8B4D8.toInt())
                    maxLines = 2
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "▶"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(0xFF8B7CFF.toInt(), 0xFF5B4CE0.toInt())
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            setOnClickListener { com.stoyko.smessenger.ui.Safe.run("html-tap") { onTap() } }
            setOnLongClickListener { forwardLongPress(it) }
        }
        return v
    }

    /** Оживить пузырь: WebView поверх карточки, карточка плавно уходит. */
    private fun activate(ctx: Context, slot: Slot, c: Content, host: MsgButtons.Host?) {
        if (slot.web != null) return
        val d = ctx.resources.displayMetrics.density
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val card = slot.box.getChildAt(0)
        val startH = heights[slot.key] ?: card?.height?.takeIf { it > 0 } ?: (96 * d).toInt()
        val web = webView(ctx, c.html, host, maxH, onHeight = { h -> heights[slot.key] = h }, params = c.params)
        slot.web = web
        web.alpha = 0f
        slot.box.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, startH))
        web.animate().alpha(1f).setDuration(220).setStartDelay(120).withEndAction {
            card?.visibility = View.GONE
        }.start()
        live.remove(slot.key)
        live += slot.key
        // Живых не больше двух: самый старый снова становится лёгкой карточкой.
        while (live.size > MAX_LIVE) {
            val k = live.removeAt(0)
            slots[k]?.let { deactivate(it) }
        }
    }

    private fun deactivate(slot: Slot) {
        val web = slot.web ?: return
        slot.web = null
        slot.box.removeView(web)
        runCatching { web.destroy() }
        slot.box.getChildAt(0)?.let {
            it.visibility = View.VISIBLE
            it.minimumHeight = heights[slot.key] ?: 0
        }
    }

    // ------------------------------------------------------------------ шаблоны

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun js(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c").replace("\n", " ") + "'"

    fun buttonsHtml(title: String, text: String, buttons: List<Pair<String, String>>): String {
        val colors = listOf("", " g", " o", " r", " s")
        val sb = StringBuilder("<div class=card>")
        if (title.isNotBlank()) sb.append("<div class=t>").append(esc(title)).append("</div>")
        if (text.isNotBlank()) sb.append("<div class=m>").append(esc(text)).append("</div>")
        buttons.forEachIndexed { i, (label, answer) ->
            sb.append("<button class=\"b").append(colors[i % colors.size]).append("\" data-reply=\"").append(esc(answer.ifBlank { label }))
                .append("\">").append(esc(label)).append("</button>")
        }
        return sb.append("</div>").toString()
    }

    fun productHtml(emoji: String, title: String, desc: String, price: String, oldPrice: String, badge: String): String {
        val sb = StringBuilder("<div class=card>")
        sb.append("<div class=pic>").append(esc(emoji.ifBlank { "🛍" })).append("</div>")
        sb.append("<div class=t>").append(esc(title))
        if (badge.isNotBlank()) sb.append("<span class=tag>").append(esc(badge)).append("</span>")
        sb.append("</div>")
        if (desc.isNotBlank()) sb.append("<div class=m>").append(esc(desc)).append("</div>")
        sb.append("<div style=\"margin-top:8px\"><span class=pr>").append(esc(price)).append("</span>")
        if (oldPrice.isNotBlank()) sb.append("<span class=old>").append(esc(oldPrice)).append("</span>")
        sb.append("</div><div class=row><button class=\"b g\" data-reply=\"").append(esc(t("Хочу купить: ", "I want to buy: ") + title))
            .append("\">").append(esc(t("Купить", "Buy"))).append("</button><button class=\"b s\" data-reply=\"")
            .append(esc(t("Расскажите подробнее про ", "Tell me more about ") + title)).append("\">")
            .append(esc(t("Подробнее", "Details"))).append("</button></div></div>")
        return sb.toString()
    }

    fun chartHtml(title: String, values: List<Double>, labels: List<String>, bar: Boolean, live: Boolean, suffix: String): String {
        val v = values.joinToString(",") { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
        val l = labels.joinToString(",") { js(it) }
        val o = StringBuilder("{type:'").append(if (bar) "bar" else "line").append("'")
        if (live) o.append(",live:1")
        if (suffix.isNotBlank()) o.append(",suffix:").append(js(suffix))
        o.append("}")
        val sb = StringBuilder("<div class=card>")
        if (title.isNotBlank()) sb.append("<div class=t>").append(esc(title)).append("</div>")
        sb.append("<canvas id=c></canvas><script>chart('c',[").append(v).append("],[").append(l).append("],").append(o).append(")</script></div>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ редактор

    /** Выбор вида пузыря и его поля; [onDone] получает готовое сообщение. */
    fun editor(ctx: Context, typed: String, onDone: (String) -> Unit) {
        val kinds = arrayOf(
            t("✨ Живые сообщения (галерея)", "✨ Live messages (gallery)"),
            t("🔘 Кнопки (быстрые ответы)", "🔘 Buttons (quick replies)"),
            t("🛍 Карточка товара", "🛍 Product card"),
            t("📈 Живой график", "📈 Live chart"),
            t("🧩 Свой HTML", "🧩 Custom HTML"),
        )
        AlertDialog.Builder(ctx)
            .setTitle(t("🧩 HTML-пузырь", "🧩 HTML bubble"))
            .setItems(kinds) { _, which ->
                when (which) {
                    0 -> gallery(ctx, onDone)
                    1 -> buttonsEditor(ctx, typed, onDone)
                    2 -> productEditor(ctx, typed, onDone)
                    3 -> chartEditor(ctx, typed, onDone)
                    else -> customEditor(ctx, typed, onDone)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun field(ctx: Context, hint: String, text: String = "", multi: Boolean = false, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
        this.hint = hint
        setText(text)
        inputType = type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
    }

    /** Общий диалог: поля + «Просмотр» + «Отправить». [build] - (html, подпись) или null с ошибкой. */
    private fun form(ctx: Context, title: String, fields: List<View>, onDone: (String) -> Unit, build: () -> Pair<String, String>?) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fields.forEach { box.addView(it) }
        val size = TextView(ctx).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        box.addView(size)
        val preview = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                colors = intArrayOf(0xFF2A2650.toInt(), 0xFF1C1A33.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            visibility = View.GONE
        }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        fun refreshSize() {
            val r = runCatching { build() }.getOrNull() ?: run {
                size.text = ""
                return
            }
            val n = pack(r.first).length
            size.text = t("Размер: ", "Size: ") + "$n / $MAX_PACKED" + if (n > MAX_PACKED) t(" - слишком большой", " - too big") else ""
            size.setTextColor(if (n > MAX_PACKED) 0xFFFF6B6B.toInt() else 0xFFB8B4D8.toInt())
        }
        fields.filterIsInstance<EditText>().forEach {
            it.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshSize()
            })
        }
        refreshSize()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(t("Отправить", "Send"), null)
            .setNeutralButton(t("Просмотр", "Preview"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val r = build() ?: return@setOnClickListener
            (preview.getChildAt(0) as? WebView)?.destroy()
            preview.removeAllViews()
            preview.addView(webView(ctx, r.first, null, (360 * d).toInt()), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (120 * d).toInt()))
            preview.visibility = View.VISIBLE
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r = build() ?: return@setOnClickListener
            val msg = message(r.first, r.second)
            if (msg == null) {
                Toast.makeText(ctx, t("Слишком большой пузырь - сократите", "The bubble is too big - shorten it"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onDone(msg)
        }
        dialog.setOnDismissListener { (preview.getChildAt(0) as? WebView)?.destroy() }
    }

    private fun buttonsEditor(ctx: Context, typed: String, onDone: (String) -> Unit) {
        val title = field(ctx, t("Заголовок", "Title"), typed)
        val text = field(ctx, t("Текст (необязательно)", "Text (optional)"), multi = true)
        val btns = field(
            ctx,
            t("Кнопки - по одной в строке: Надпись = ответ", "Buttons - one per line: Label = reply"),
            t("✅ Да = Да, иду\n❌ Нет = Не смогу\n🤔 Может быть", "✅ Yes = Yes, I'm in\n❌ No = Can't make it\n🤔 Maybe"),
            multi = true,
        )
        form(ctx, kindTitle(0), listOf(title, text, btns), onDone) {
            val list = btns.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8).map {
                val label = it.substringBefore("=").trim()
                label to it.substringAfter("=", label).trim()
            }
            if (list.isEmpty()) {
                btns.error = t("Добавьте кнопку", "Add a button")
                return@form null
            }
            buttonsHtml(title.text.toString().trim(), text.text.toString().trim(), list) to ("🔘 " + title.text.toString().trim().ifBlank { t("Выберите ответ", "Choose an answer") })
        }
    }

    private fun productEditor(ctx: Context, typed: String, onDone: (String) -> Unit) {
        val emoji = field(ctx, t("Картинка - эмодзи", "Picture - emoji"), "👟")
        val title = field(ctx, t("Название", "Name"), typed)
        val desc = field(ctx, t("Описание", "Description"), multi = true)
        val price = field(ctx, t("Цена, например 2 990 ₽", "Price, e.g. $29"))
        val old = field(ctx, t("Старая цена (необязательно)", "Old price (optional)"))
        val badge = field(ctx, t("Метка: −30%, Хит, Новинка (необязательно)", "Badge: −30%, Hit, New (optional)"))
        form(ctx, kindTitle(1), listOf(emoji, title, desc, price, old, badge), onDone) {
            val n = title.text.toString().trim()
            if (n.isEmpty()) {
                title.error = t("Напишите название", "Enter the name")
                return@form null
            }
            productHtml(emoji.text.toString().trim(), n, desc.text.toString().trim(), price.text.toString().trim(), old.text.toString().trim(), badge.text.toString().trim()) to
                ("🛍 $n " + price.text.toString().trim()).trim()
        }
    }

    private fun chartEditor(ctx: Context, typed: String, onDone: (String) -> Unit) {
        val title = field(ctx, t("Заголовок", "Title"), typed.ifBlank { t("Продажи за неделю", "Sales this week") })
        val values = field(ctx, t("Значения через запятую", "Values, comma-separated"), "12, 18, 15, 24, 21, 30, 34")
        val labels = field(ctx, t("Подписи через запятую (необязательно)", "Labels, comma-separated (optional)"), t("Пн, Вт, Ср, Чт, Пт, Сб, Вс", "Mon, Tue, Wed, Thu, Fri, Sat, Sun"))
        val suffix = field(ctx, t("Единицы: ₽, %, шт (необязательно)", "Units: $, %, pcs (optional)"))
        val bar = android.widget.CheckBox(ctx).apply { text = t("Столбцы (вместо линии)", "Bars (instead of a line)") }
        val live = android.widget.CheckBox(ctx).apply {
            text = t("Живой: график сам плавно обновляется", "Live: the chart keeps updating smoothly")
            isChecked = true
        }
        form(ctx, kindTitle(2), listOf(title, values, labels, suffix, bar, live), onDone) {
            val v = values.text.split(Regex("[,;\\s]+")).mapNotNull { it.replace(',', '.').toDoubleOrNull() }.take(40)
            if (v.size < 2) {
                values.error = t("Нужно хотя бы 2 числа", "At least 2 numbers needed")
                return@form null
            }
            val l = labels.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(40)
            chartHtml(title.text.toString().trim(), v, l, bar.isChecked, live.isChecked, suffix.text.toString().trim()) to
                ("📈 " + title.text.toString().trim().ifBlank { t("График", "Chart") })
        }
    }

    private fun customEditor(ctx: Context, typed: String, onDone: (String) -> Unit) {
        val caption = field(ctx, t("Подпись (видна в списке чатов)", "Caption (shown in the chat list)"), typed)
        val html = field(
            ctx,
            "HTML",
            "<div class=card><div class=t>Привет!</div><div class=row><button class=\"b g\" data-reply=\"👍\">👍</button><button class=\"b r\" data-reply=\"👎\">👎</button></div></div>",
            multi = true,
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            minLines = 6
        }
        val help = TextView(ctx).apply {
            textSize = 12f
            alpha = 0.7f
            text = t(
                "Готовые стили: .card .t .m .b (g r o s) .row .pic .pr .old .tag. Кнопки: data-reply / data-copy / data-url. " +
                    "График: chart('id',[1,2,3],['a','b','c'],{type:'line',live:1}). Без интернета: картинки только data:.",
                "Built-in styles: .card .t .m .b (g r o s) .row .pic .pr .old .tag. Buttons: data-reply / data-copy / data-url. " +
                    "Chart: chart('id',[1,2,3],['a','b','c'],{type:'line',live:1}). No internet: images only as data:.",
            )
        }
        form(ctx, kindTitle(3), listOf(caption, html, help), onDone) {
            val h = html.text.toString().trim()
            if (h.isEmpty()) {
                html.error = t("Пусто", "Empty")
                return@form null
            }
            h to ("🧩 " + caption.text.toString().trim().ifBlank { t("Мини-приложение", "Mini app") })
        }
    }

    private fun kindTitle(i: Int) = when (i) {
        0 -> t("🔘 Кнопки", "🔘 Buttons")
        1 -> t("🛍 Карточка товара", "🛍 Product card")
        2 -> t("📈 Живой график", "📈 Live chart")
        else -> t("🧩 Свой HTML", "🧩 Custom HTML")
    }

    private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
