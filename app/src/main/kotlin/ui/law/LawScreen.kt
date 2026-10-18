// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.law

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import kotlin.math.abs

/**
 * Уголовный кодекс РФ без интернета - актуальная редакция (с изменениями 2026 года) из
 * официального сервиса actual.pravo.gov.ru. Официальные тексты законов не охраняются авторским
 * правом (ст. 1259 ГК РФ), поэтому кодекс встроен в приложение целиком.
 *
 * Навигация: части → разделы → главы → статьи; статья открывается читалкой с крупным текстом:
 * части статьи выделены, санкции («наказывается…») - цветной полосой, сведения о редакциях -
 * мелко (можно скрыть). Поиск по номеру («105», «159.1», «ст 228») и по тексту, закладки,
 * недавние, переход к соседним статьям свайпом, размер шрифта, копировать и поделиться.
 */
object LawScreen {
    class Article(val n: String, val title: String, val paras: List<String>, val chapter: Chapter)
    class Chapter(val n: String, val title: String, val section: Section) {
        val articles = ArrayList<Article>()
    }
    class Section(val n: String, val title: String) {
        val chapters = ArrayList<Chapter>()
        val general: Boolean get() = n in setOf("I", "II", "III", "IV", "V", "VI")
    }
    class Code(val name: String, val edition: String, val source: String, val sections: List<Section>) {
        val articles: List<Article> = sections.flatMap { s -> s.chapters.flatMap { it.articles } }
        private val byNumber = articles.associateBy { it.n }
        fun article(n: String): Article? = byNumber[n]
    }

    @Volatile
    private var code: Code? = null
    private val main = Handler(Looper.getMainLooper())

    private fun load(ctx: Context): Code {
        code?.let { return it }
        synchronized(this) {
            code?.let { return it }
            // Кодекс лежит в самом приложении (assets/ukrf.json) - интернет не нужен.
            val json = ctx.assets.open("ukrf.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(json)
            val sections = ArrayList<Section>()
            val sa = o.getJSONArray("sections")
            for (i in 0 until sa.length()) {
                val so = sa.getJSONObject(i)
                val sec = Section(so.optString("n"), so.optString("t"))
                val ca = so.getJSONArray("ch")
                for (j in 0 until ca.length()) {
                    val co = ca.getJSONObject(j)
                    val ch = Chapter(co.optString("n"), co.optString("t"), sec)
                    val aa = co.getJSONArray("a")
                    for (k in 0 until aa.length()) {
                        val ao = aa.getJSONObject(k)
                        val pa = ao.getJSONArray("p")
                        ch.articles += Article(ao.optString("n"), ao.optString("t"), List(pa.length()) { pa.getString(it) }, ch)
                    }
                    sec.chapters += ch
                }
                sections += sec
            }
            return Code(o.optString("name"), o.optString("edition"), o.optString("source"), sections).also { code = it }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("law_ukrf", Context.MODE_PRIVATE)

    fun show(ctx: Context) {
        val loaded = code
        if (loaded != null) {
            openSafely(ctx, loaded)
            return
        }
        Toast.makeText(ctx, R.string.law_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val r = runCatching { load(ctx.applicationContext) }
            main.post {
                r.onSuccess { openSafely(ctx, it) }.onFailure { fail(ctx, it) }
            }
        }.start()
    }

    /** Открыть окно; любая ошибка - понятное сообщение и запись в журнал (раньше окно молча не открывалось). */
    private fun openSafely(ctx: Context, c: Code) {
        runCatching { Screen(ctx, c).open() }.onFailure { fail(ctx, it) }
    }

    private fun fail(ctx: Context, e: Throwable) {
        com.stoyko.smessenger.domain.Diagnostics.record("УК РФ: открытие", e, fatal = false)
        runCatching {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.law_load_failed)
                .setMessage((e.message ?: e.javaClass.simpleName).take(400))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Окно кодекса: стек экранов внутри одного полноэкранного окна. */
    private class Screen(private val ctx: Context, private val code: Code) {
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        private val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        private val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        private val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        private val cCard = ContextCompat.getColor(ctx, R.color.colorSurface)
        private val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        private val accent = 0xFF5B4BDB.toInt()
        private val title = TextView(ctx)
        private val subtitle = TextView(ctx)
        private val content = FrameLayout(ctx)
        private val searchField = EditText(ctx)
        private val stack = ArrayList<() -> Unit>()

        private var fontSize: Float
            get() = prefs(ctx).getFloat("font", 16f)
            set(v) = prefs(ctx).edit().putFloat("font", v).apply()
        private var showEditions: Boolean
            get() = prefs(ctx).getBoolean("editions", false)
            set(v) = prefs(ctx).edit().putBoolean("editions", v).apply()

        private fun bookmarks(): List<String> = prefs(ctx).getString("bm", "").orEmpty().split(',').filter { it.isNotBlank() }
        private fun recent(): List<String> = prefs(ctx).getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
        private fun toggleBookmark(n: String): Boolean {
            val l = bookmarks().toMutableList()
            val added = if (n in l) {
                l.remove(n)
                false
            } else {
                l.add(0, n)
                true
            }
            prefs(ctx).edit().putString("bm", l.joinToString(",")).apply()
            return added
        }
        private fun addRecent(n: String) {
            val l = (listOf(n) + recent().filter { it != n }).take(20)
            prefs(ctx).edit().putString("recent", l.joinToString(",")).apply()
        }

        fun open() {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cBg)
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B2C3B.toInt(), 0xFF3B1B5B.toInt()))
                setPadding(dp(4), dp(6), dp(8), dp(10))
                elevation = 6 * d
            }
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun icon(res: Int, click: () -> Unit) = ImageButton(ctx).apply {
                setImageResource(res)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { click() }
                Kinetics.squish(this, 0.85f)
            }
            bar.addView(icon(R.drawable.ic_back) { back() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            title.textSize = 18f
            title.setTextColor(Color.WHITE)
            title.setTypeface(Typeface.DEFAULT_BOLD)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            subtitle.textSize = 12f
            subtitle.setTextColor(0xCCFFFFFF.toInt())
            subtitle.maxLines = 1
            subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
            titles.addView(title)
            titles.addView(subtitle)
            bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
            bar.addView(icon(R.drawable.ic_bookmark) { push { showBookmarks() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
            header.addView(bar)
            // Поиск: номер статьи или слова.
            searchField.apply {
                setHint(R.string.law_search_hint)
                setSingleLine()
                textSize = 15f
                setTextColor(Color.WHITE)
                setHintTextColor(0xAAFFFFFF.toInt())
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0x33FFFFFF)
                }
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnEditorActionListener { _, _, _ ->
                    runSearch(text.toString())
                    true
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val q = s?.toString().orEmpty()
                        // Номер статьи - результат сразу, слова - с 3 букв.
                        if (q.trim().length >= 3 || q.trim().matches(Regex("\\d+(\\.\\d+)?"))) runSearch(q, live = true)
                    }
                })
            }
            header.addView(searchField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(6), 0, 0)
            })
            root.addView(header)
            root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.fitSystemBars()
            dialog.setContentView(root)
            dialog.window?.setWindowAnimations(R.style.DialogAnimation)
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    back()
                    true
                } else {
                    false
                }
            }
            dialog.show()
            push { showHome() }
        }

        private fun push(screen: () -> Unit) {
            stack += screen
            screen()
        }

        private fun back() {
            if (stack.size <= 1) {
                dialog.safeDismiss()
                return
            }
            stack.removeAt(stack.lastIndex)
            stack[stack.lastIndex].invoke()
        }

        private fun setPage(v: View, forward: Boolean = true) {
            content.removeAllViews()
            content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (Kinetics.enabled) {
                v.alpha = 0f
                v.translationX = (if (forward) 28 else -28) * d
                v.animate().alpha(1f).translationX(0f).setDuration(200).start()
            }
        }

        private fun list(): Pair<ScrollView, LinearLayout> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(24))
            }
            return ScrollView(ctx).apply { addView(box) } to box
        }

        private fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(cSub)
            setPadding(dp(4), dp(12), 0, dp(6))
        }

        private fun card(badge: String, main: String, sub: String?, color: Int, click: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(cCard)
            }
            isClickable = true
            setOnClickListener { click() }
            Kinetics.squish(this, 0.97f)
            addView(
                TextView(ctx).apply {
                    text = badge
                    textSize = if (badge.length > 4) 11f else 14f
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(color)
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor((color and 0x00FFFFFF) or 0x22000000)
                    }
                },
                LinearLayout.LayoutParams(dp(54), dp(42)),
            )
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(TextView(ctx).apply {
                text = main
                textSize = 15f
                setTextColor(cText)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!sub.isNullOrBlank()) {
                texts.addView(TextView(ctx).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(cSub)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun addCard(box: LinearLayout, v: View) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        private fun edition(): String {
            val e = code.edition.split('-')
            val date = if (e.size == 3) "${e[2]}.${e[1]}.${e[0]}" else code.edition
            return ctx.getString(R.string.law_edition, date)
        }

        // ---------- главная: части и разделы ----------

        private fun showHome() {
            title.setText(R.string.law_title)
            subtitle.text = edition() + " · " + ctx.getString(R.string.law_articles_count, code.articles.size)
            val (scroll, box) = list()
            // Быстрые действия: недавние и закладки.
            val rec = recent().mapNotNull { code.article(it) }.take(5)
            if (rec.isNotEmpty()) {
                box.addView(label("🕘 " + ctx.getString(R.string.law_recent)))
                rec.forEach { a -> addCard(box, articleCard(a)) }
            }
            box.addView(label("📘 " + ctx.getString(R.string.law_part_general)))
            code.sections.filter { it.general }.forEach { s -> addCard(box, sectionCard(s)) }
            box.addView(label("📕 " + ctx.getString(R.string.law_part_special)))
            code.sections.filter { !it.general }.forEach { s -> addCard(box, sectionCard(s)) }
            box.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.law_source, code.source)
                textSize = 11f
                setTextColor(cSub)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), 0)
            })
            setPage(scroll)
        }

        private fun sectionCard(s: Section): View {
            val count = s.chapters.sumOf { it.articles.size }
            return card(s.n, s.title, ctx.getString(R.string.law_section_sub, s.chapters.size, count), if (s.general) 0xFF2563EB.toInt() else 0xFFB91C1C.toInt()) {
                push { showSection(s) }
            }
        }

        private fun showSection(s: Section) {
            title.text = ctx.getString(R.string.law_section, s.n)
            subtitle.text = s.title
            val (scroll, box) = list()
            s.chapters.forEach { ch ->
                val first = ch.articles.firstOrNull()?.n
                val last = ch.articles.lastOrNull()?.n
                addCard(
                    box,
                    card(ctx.getString(R.string.law_chapter_short, ch.n), ch.title, if (first != null) ctx.getString(R.string.law_articles_range, first, last) else null, accent) {
                        push { showChapter(ch) }
                    },
                )
            }
            setPage(scroll)
        }

        private fun articleCard(a: Article, snippet: CharSequence? = null): View {
            val v = card(a.n, a.title, null, if (a.n in bookmarks()) 0xFFF59E0B.toInt() else accent) { push { showArticle(a) } }
            if (snippet != null) {
                (v.getChildAt(1) as LinearLayout).addView(TextView(ctx).apply {
                    text = snippet
                    textSize = 12f
                    setTextColor(cSub)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            return v
        }

        private fun showChapter(ch: Chapter) {
            title.text = ctx.getString(R.string.law_chapter, ch.n)
            subtitle.text = ch.title
            val (scroll, box) = list()
            ch.articles.forEach { a -> addCard(box, articleCard(a)) }
            setPage(scroll)
        }

        // ---------- статья ----------

        private fun styled(a: Article): CharSequence {
            val sb = SpannableStringBuilder()
            for (p in a.paras) {
                val isNote = p.startsWith("(") && (p.contains("редакц") || p.contains("Дополнение") || p.contains("Утратил") || p.contains("Конституци") || p.contains("Федеральн"))
                if (isNote && !showEditions) continue
                val start = sb.length
                sb.append(p).append("\n\n")
                val end = start + p.length
                when {
                    isNote -> {
                        sb.setSpan(RelativeSizeSpan(0.78f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(cSub), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    p.startsWith("наказыва") -> {
                        // Санкция - цветом и отступом с полосой.
                        sb.setSpan(ForegroundColorSpan(0xFFDC2626.toInt()), start, minOf(end, start + p.indexOf(' ').coerceAtLeast(0)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(SanctionSpan(0xFFDC2626.toInt(), dp(3), dp(12)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    p.startsWith("Примечани") -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    else -> {
                        val m = Regex("^(\\d+(\\.\\d+)?\\.|[а-я](\\.\\d+)?\\))").find(p)
                        if (m != null) {
                            sb.setSpan(StyleSpan(Typeface.BOLD), start, start + m.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(ForegroundColorSpan(accent), start, start + m.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (p.matches(Regex("^[а-я](\\.\\d+)?\\).*"))) sb.setSpan(LeadingMarginSpan.Standard(dp(14)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return sb
        }

        private fun showArticle(a: Article) {
            addRecent(a.n)
            title.text = ctx.getString(R.string.law_article, a.n)
            subtitle.text = ctx.getString(R.string.law_chapter, a.chapter.n) + " · " + a.chapter.title
            val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(24))
            }
            box.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.law_article, a.n) + ". " + a.title
                textSize = fontSize + 3
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(cText)
                setPadding(0, 0, 0, dp(12))
            })
            val body = TextView(ctx).apply {
                text = styled(a)
                textSize = fontSize
                setTextColor(cText)
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
            }
            box.addView(body)
            val scroll = ScrollView(ctx).apply { addView(box) }
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Нижняя панель: назад/вперёд по статьям, закладка, шрифт, редакции, поделиться.
            val idx = code.articles.indexOf(a)
            val prev = code.articles.getOrNull(idx - 1)
            val next = code.articles.getOrNull(idx + 1)
            fun tool(text: String, enabled: Boolean = true, click: () -> Unit) = TextView(ctx).apply {
                this.text = text
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (enabled) accent else cSub)
                alpha = if (enabled) 1f else 0.4f
                isEnabled = enabled
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0x145B4BDB)
                }
                setOnClickListener { click() }
                Kinetics.squish(this, 0.9f)
            }
            val bm = tool(if (a.n in bookmarks()) "★" else "☆") {}
            bm.setOnClickListener {
                val added = toggleBookmark(a.n)
                bm.text = if (added) "★" else "☆"
                Toast.makeText(ctx, if (added) R.string.law_bookmarked else R.string.law_unbookmarked, Toast.LENGTH_SHORT).show()
            }
            val tools = listOf(
                tool("‹", prev != null) { prev?.let { replaceArticle(it, forward = false) } },
                bm,
                tool("A−") { fontSize = (fontSize - 1f).coerceAtLeast(12f); body.textSize = fontSize },
                tool("A+") { fontSize = (fontSize + 1f).coerceAtMost(26f); body.textSize = fontSize },
                tool(if (showEditions) "📝" else "📄") {
                    showEditions = !showEditions
                    body.text = styled(a)
                    Toast.makeText(ctx, if (showEditions) R.string.law_editions_on else R.string.law_editions_off, Toast.LENGTH_SHORT).show()
                },
                tool("⇪") { share(a) },
                tool("›", next != null) { next?.let { replaceArticle(it, forward = true) } },
            )
            root.addView(
                com.stoyko.smessenger.ui.Btn.row(ctx, tools, height = 44, gap = 6).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(cCard)
                    elevation = 8 * d
                },
            )
            // Свайп влево/вправо - соседняя статья.
            val gd = GestureDetector(
                ctx,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                        if (abs(vx) < abs(vy) * 1.5f || abs(vx) < 900) return false
                        val target = if (vx < 0) next else prev
                        target?.let { replaceArticle(it, forward = vx < 0) }
                        return target != null
                    }
                },
            )
            scroll.setOnTouchListener { _, e ->
                gd.onTouchEvent(e)
                false
            }
            setPage(root)
        }

        /** Соседняя статья - на месте текущей (кнопка «Назад» вернёт в список, а не по всем статьям). */
        private fun replaceArticle(a: Article, forward: Boolean) {
            if (stack.isNotEmpty()) stack[stack.lastIndex] = { showArticle(a) }
            showArticle(a)
            content.getChildAt(0)?.let { v ->
                if (Kinetics.enabled) {
                    v.translationX = (if (forward) 60 else -60) * d
                    v.animate().translationX(0f).setDuration(220).start()
                }
            }
        }

        private fun share(a: Article) {
            val text = ctx.getString(R.string.law_article, a.n) + ". " + a.title + "\n\n" +
                a.paras.filter { !(it.startsWith("(") && it.contains("редакц")) }.joinToString("\n") +
                "\n\n" + code.name + " (" + edition() + ")"
            val items = arrayOf<CharSequence>(ctx.getString(R.string.law_copy), ctx.getString(R.string.law_share))
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setItems(items) { _, w ->
                    if (w == 0) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("law", text))
                        Toast.makeText(ctx, R.string.law_copied, Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                .show()
        }

        // ---------- закладки ----------

        private fun showBookmarks() {
            title.setText(R.string.law_bookmarks)
            subtitle.text = edition()
            val (scroll, box) = list()
            val l = bookmarks().mapNotNull { code.article(it) }
            if (l.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    setText(R.string.law_bookmarks_empty)
                    textSize = 15f
                    setTextColor(cSub)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(40), dp(16), 0)
                })
            }
            l.forEach { a -> addCard(box, articleCard(a)) }
            setPage(scroll)
        }

        // ---------- поиск ----------

        private var searchToken = 0

        private fun runSearch(raw: String, live: Boolean = false) {
            val q = raw.trim()
            if (q.isEmpty()) return
            // «ст 228», «статья 159.1», «228.1» - сразу статья.
            val num = Regex("^(ст\\.?|статья)?\\s*(\\d+(\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(q)?.groupValues?.get(2)
            val exact = num?.let { code.article(it) }
            if (exact != null && !live) {
                hideKeyboard()
                push { showArticle(exact) }
                return
            }
            val token = ++searchToken
            Thread {
                val words = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
                val res = ArrayList<Pair<Article, CharSequence?>>()
                if (exact != null) res += exact to null
                for (a in code.articles) {
                    if (token != searchToken) return@Thread
                    if (a === exact) continue
                    val titleHit = words.all { a.title.lowercase().contains(it) }
                    val textHit = !titleHit && words.isNotEmpty() && a.paras.any { p -> words.all { p.lowercase().contains(it) } }
                    if (titleHit || textHit) {
                        val p = if (textHit) a.paras.first { p -> words.all { p.lowercase().contains(it) } } else null
                        res += a to p?.let { highlight(it, words) }
                        if (res.size >= 200) break
                    }
                }
                main.post {
                    if (token != searchToken || !dialog.isShowing) return@post
                    val screen = { showResults(q, res) }
                    // Живой поиск заменяет прежние результаты, а не копит их в «Назад».
                    if (stack.isNotEmpty() && stack[stack.lastIndex] is ResultsScreen) stack[stack.lastIndex] = ResultsScreen(screen) else stack += ResultsScreen(screen)
                    screen()
                }
            }.start()
        }

        private class ResultsScreen(val f: () -> Unit) : () -> Unit {
            override fun invoke() = f()
        }

        private fun highlight(p: String, words: List<String>): CharSequence {
            val low = p.lowercase()
            val first = words.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val from = (first - 60).coerceAtLeast(0)
            val cut = (if (from > 0) "…" else "") + p.substring(from, minOf(p.length, from + 220))
            val sb = SpannableStringBuilder(cut)
            val lc = cut.lowercase()
            for (w in words) {
                var i = lc.indexOf(w)
                while (i >= 0) {
                    sb.setSpan(BackgroundColorSpan(0x66FFD54F), i, i + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = lc.indexOf(w, i + w.length)
                }
            }
            return sb
        }

        private fun showResults(q: String, res: List<Pair<Article, CharSequence?>>) {
            title.text = ctx.getString(R.string.law_search_title)
            subtitle.text = ctx.getString(R.string.law_found, res.size, q)
            val (scroll, box) = list()
            if (res.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    setText(R.string.law_nothing)
                    textSize = 15f
                    setTextColor(cSub)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(40), dp(16), 0)
                })
            }
            res.forEach { (a, snip) -> addCard(box, articleCard(a, snip)) }
            setPage(scroll)
        }

        private fun hideKeyboard() {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
            searchField.clearFocus()
        }
    }

    /** Полоса слева у санкции («наказывается…»). */
    private class SanctionSpan(private val color: Int, private val stripe: Int, private val gap: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = stripe + gap
        override fun drawLeadingMargin(
            c: android.graphics.Canvas, p: android.graphics.Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence?, start: Int, end: Int, first: Boolean, layout: android.text.Layout?,
        ) {
            val style = p.style
            val col = p.color
            p.style = android.graphics.Paint.Style.FILL
            p.color = (color and 0x00FFFFFF) or 0xAA000000.toInt()
            c.drawRect(x.toFloat(), top.toFloat(), (x + dir * stripe).toFloat(), bottom.toFloat(), p)
            p.style = style
            p.color = col
        }
    }
}
