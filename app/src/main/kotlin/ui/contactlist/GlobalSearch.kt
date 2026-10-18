// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactlist

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.ui.chat.FileTypes
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Общий поиск по всему мессенджеру - как в больших мессенджерах:
 *  - по тексту сообщений во всех личных чатах, группах и каналах;
 *  - по ссылкам (вкладка «Ссылки»);
 *  - по названиям чатов, групп и каналов;
 *  - по файлам: имя и расширение (TXT, PNG, JPG, APK, ISO, RAR, ZIP, JS, CSS, DLL, EXE, MP3, MP4…).
 * Нажатие - открыть чат; удержание - меню: в каком чате, перейти, открыть файл, копировать.
 */
object GlobalSearch {
    private enum class Kind { CHAT, MESSAGE, LINK, FILE }

    private class Hit(
        val kind: Kind,
        val title: String,
        val text: String,
        val time: Long,
        /** Личный чат: ключ собеседника; группа/канал: id группы. */
        val contactPk: String? = null,
        val groupId: String? = null,
        val fileName: String = "",
        val filePath: String = "",
    )

    private val EXTS = listOf("txt", "pdf", "doc", "png", "jpg", "gif", "webp", "apk", "iso", "rar", "zip", "7z", "js", "css", "html", "json", "dll", "exe", "mp3", "m4a", "mp4", "mkv")
    private val LINK = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "global-search").apply { isDaemon = true } }

    fun show(ctx: Context, openChat: (String) -> Unit, openGroup: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val cAccent = ContextCompat.getColor(ctx, R.color.settingsAccent)
        val main = Handler(Looper.getMainLooper())
        val component = (ctx.applicationContext as App).component

        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        // ---- строка поиска ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(cText)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        val field = EditText(ctx).apply {
            setHint(R.string.gsearch_hint)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor((cText and 0x00FFFFFF) or 0x12000000)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        // ---- фильтры: что искать и расширения файлов ----
        var kindFilter: Kind? = null
        var extFilter: String? = null
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        val chipViews = ArrayList<Pair<TextView, () -> Boolean>>()
        fun styleChips() {
            chipViews.forEach { (v, on) ->
                val sel = on()
                v.setTextColor(if (sel) Color.WHITE else cText)
                v.background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(if (sel) cAccent else (cText and 0x00FFFFFF) or 0x14000000)
                }
            }
        }
        lateinit var runSearch: () -> Unit
        fun chip(label: String, isOn: () -> Boolean, click: () -> Unit) {
            val v = TextView(ctx).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener {
                    click()
                    styleChips()
                    runSearch()
                }
            }
            chips.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            chipViews += v to isOn
        }
        chip(ctx.getString(R.string.gsearch_all), { kindFilter == null && extFilter == null }) { kindFilter = null; extFilter = null }
        chip("💬 " + ctx.getString(R.string.gsearch_messages), { kindFilter == Kind.MESSAGE }) { kindFilter = Kind.MESSAGE; extFilter = null }
        chip("🔗 " + ctx.getString(R.string.gsearch_links), { kindFilter == Kind.LINK }) { kindFilter = Kind.LINK; extFilter = null }
        chip("📎 " + ctx.getString(R.string.gsearch_files), { kindFilter == Kind.FILE && extFilter == null }) { kindFilter = Kind.FILE; extFilter = null }
        chip("👥 " + ctx.getString(R.string.gsearch_chats), { kindFilter == Kind.CHAT }) { kindFilter = Kind.CHAT; extFilter = null }
        for (e in EXTS) {
            chip(e.uppercase(), { extFilter == e }) {
                if (extFilter == e) {
                    extFilter = null
                    kindFilter = null
                } else {
                    extFilter = e
                    kindFilter = Kind.FILE
                }
            }
        }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        styleChips()

        val status = TextView(ctx).apply {
            textSize = 13f
            setTextColor(cSub)
            setPadding(dp(18), dp(4), dp(18), dp(6))
        }
        root.addView(status)

        // ---- результаты ----
        val hits = ArrayList<Hit>()
        var query = ""
        val df = DateFormat.getDateInstance(DateFormat.SHORT)
        val adapter = object : BaseAdapter() {
            override fun getCount() = hits.size
            override fun getItem(position: Int) = hits[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val h = hits[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(TextView(ctx).apply {
                        tag = "icon"
                        gravity = Gravity.CENTER
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(TextView(ctx).apply {
                            tag = "title"
                            textSize = 15f
                            setTextColor(cText)
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(ctx).apply {
                            tag = "text"
                            textSize = 13.5f
                            setTextColor(cSub)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(ctx).apply {
                        tag = "time"
                        textSize = 11f
                        setTextColor(cSub)
                        setPadding(dp(8), 0, 0, 0)
                    })
                }
                val icon = row.findViewWithTag<TextView>("icon")
                when (h.kind) {
                    Kind.FILE -> FileTypes.applyBadge(icon, h.fileName)
                    else -> {
                        icon.text = when (h.kind) {
                            Kind.CHAT -> if (h.groupId != null) "👥" else "👤"
                            Kind.LINK -> "🔗"
                            else -> "💬"
                        }
                        icon.textSize = 18f
                        icon.setTextColor(cText)
                        icon.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor((cAccent and 0x00FFFFFF) or 0x22000000)
                        }
                    }
                }
                row.findViewWithTag<TextView>("title").text = h.title
                row.findViewWithTag<TextView>("text").text = highlight(if (h.kind == Kind.FILE) h.fileName else h.text, query, cAccent)
                row.findViewWithTag<TextView>("time").text = if (h.time in 1..System.currentTimeMillis() + 86_400_000L) df.format(Date(h.time)) else ""
                return row
            }
        }
        val list = ListView(ctx).apply {
            this.adapter = adapter
            divider = null
            isFastScrollEnabled = true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun open(h: Hit) {
            dialog.safeDismiss()
            when {
                h.contactPk != null -> openChat(h.contactPk)
                h.groupId != null -> openGroup(h.groupId)
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> hits.getOrNull(pos)?.let { open(it) } }
        // Удержание: где это лежит и что сделать.
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val h = hits.getOrNull(pos) ?: return@setOnItemLongClickListener true
            val actions = ArrayList<Pair<String, () -> Unit>>()
            fun act(label: String, f: () -> Unit) {
                actions.add(Pair(label, f))
            }
            act(ctx.getString(R.string.gsearch_go_chat)) { open(h) }
            // Скрытый чат (открыт по PIN) - можно вернуть в обычный список.
            if (h.kind == Kind.CHAT && h.contactPk != null && com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, h.contactPk)) {
                act(ctx.getString(R.string.hide_chat_unhide)) {
                    com.stoyko.smessenger.chat.HiddenChats.unhide(ctx, h.contactPk)
                    android.widget.Toast.makeText(ctx, R.string.hide_chat_unhidden, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            if (h.kind == Kind.FILE && h.filePath.isNotBlank()) {
                act(ctx.getString(R.string.gsearch_open_file)) { runCatching { FileTypes.open(ctx, h.filePath, h.fileName) } }
                act(ctx.getString(R.string.gsearch_save_file)) {
                    runCatching { FileTypes.saveToPublic(ctx, FileTypes.shareUri(ctx, h.filePath), h.fileName) }
                }
            }
            val copyText = if (h.kind == Kind.FILE) h.fileName else h.text
            act(ctx.getString(R.string.gsearch_copy)) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("text", copyText))
                android.widget.Toast.makeText(ctx, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            if (h.kind == Kind.LINK) {
                LINK.find(h.text)?.value?.let { url ->
                    act(ctx.getString(R.string.gsearch_open_link)) {
                        runCatching { com.stoyko.smessenger.browser.Browser.open(ctx, if (url.startsWith("www.")) "https://$url" else url) }
                    }
                }
            }
            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.gsearch_in_chat, h.title))
                .setMessage(if (h.kind == Kind.FILE) h.fileName else h.text.take(300))
                .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
                .show()
            true
        }

        // ---- сам поиск (в фоне, с паузой 250 мс после ввода) ----
        var token = 0
        runSearch = {
            val q = field.text.toString().trim()
            query = q
            val my = ++token
            val kind = kindFilter
            val ext = extFilter
            if (q.length < 2 && ext == null && kind != Kind.FILE && kind != Kind.LINK) {
                hits.clear()
                adapter.notifyDataSetChanged()
                status.setText(R.string.gsearch_tip)
            } else {
                status.setText(R.string.gsearch_searching)
                main.postDelayed({
                    if (my != token) return@postDelayed
                    pool.execute {
                        val found = runCatching { collect(component, ctx, q, kind, ext) }.getOrDefault(emptyList())
                        main.post {
                            if (my != token) return@post
                            hits.clear()
                            hits.addAll(found)
                            adapter.notifyDataSetChanged()
                            status.text = if (found.isEmpty()) ctx.getString(R.string.gsearch_nothing) else ctx.getString(R.string.gsearch_found, found.size)
                        }
                    }
                }, 250)
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runSearch()
        })
        status.setText(R.string.gsearch_tip)

        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.show()
        field.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Найденные совпадения (поток поиска). */
    private fun collect(c: com.stoyko.smessenger.di.AppComponent, ctx: Context, q: String, kind: Kind?, ext: String?): List<Hit> {
        val out = ArrayList<Hit>()
        val contacts = runCatching { runBlocking { c.contactRepository().getAll().first() } }.getOrDefault(emptyList())
        val names = contacts.associate { it.publicKey to it.name.ifBlank { ctx.getString(R.string.contact_default_name) } }
        val groups = runCatching { runBlocking { c.groupRepository().getAll().first() } }.getOrDefault(emptyList())
        val groupNames = groups.associate { it.chatId to it.name }
        val hidden: Set<String> = runCatching { com.stoyko.smessenger.chat.HiddenChats.ids(ctx) }.getOrDefault(emptySet())
        // PIN скрытого чата: введённый PIN открывает его в результатах (как раньше в поиске чатов).
        val unlocked = runCatching { com.stoyko.smessenger.chat.HiddenChats.unlock(ctx, q.trim()).toSet() }.getOrDefault(emptySet())
        unlocked.forEach { pk ->
            out += Hit(Kind.CHAT, "🔓 " + (names[pk] ?: ctx.getString(R.string.contact_default_name)), ctx.getString(R.string.gsearch_kind_hidden), Long.MAX_VALUE, contactPk = pk)
        }
        fun extOk(name: String) = ext == null || FileTypes.ext(name) == ext || (ext == "jpg" && FileTypes.ext(name) == "jpeg")

        // Чаты, группы и каналы по названию.
        if ((kind == null || kind == Kind.CHAT) && q.length >= 2) {
            contacts.filter { it.publicKey !in hidden && (names[it.publicKey] ?: "").contains(q, true) }.forEach {
                out += Hit(Kind.CHAT, names[it.publicKey] ?: "", ctx.getString(R.string.gsearch_kind_contact), it.lastMessage, contactPk = it.publicKey)
            }
            groups.filter { it.name.contains(q, true) }.forEach {
                out += Hit(Kind.CHAT, it.name, ctx.getString(if (it.isChannel) R.string.gsearch_kind_channel else R.string.gsearch_kind_group), it.lastMessage, groupId = it.chatId)
            }
        }
        // Файлы: личные чаты и группы (по имени и/или расширению).
        if (kind == null || kind == Kind.FILE) {
            val needle = if (q.isNotEmpty()) q else if (ext != null) ".$ext" else ""
            c.fileTransferRepository().search(needle).filter { it.publicKey !in hidden && extOk(it.fileName) && (q.isEmpty() || it.fileName.contains(q, true)) }.forEach {
                out += Hit(Kind.FILE, names[it.publicKey] ?: "", it.fileName, 0L, contactPk = it.publicKey, fileName = it.fileName, filePath = it.destination)
            }
            c.groupRepository().search(needle).filter { it.fileName.isNotBlank() && extOk(it.fileName) && (q.isEmpty() || it.fileName.contains(q, true)) }.forEach {
                out += Hit(Kind.FILE, groupNames[it.chatId] ?: "", it.fileName, it.timestamp, groupId = it.chatId, fileName = it.fileName, filePath = it.filePath)
            }
        }
        // Сообщения и ссылки.
        if (kind == null || kind == Kind.MESSAGE || kind == Kind.LINK) {
            val needle = if (kind == Kind.LINK && q.isEmpty()) "http" else q
            if (needle.length >= 2) {
                c.messageRepository().search(needle).forEach { m ->
                    if (m.publicKey !in hidden && m.type == MessageType.Normal && !m.message.startsWith("\u2063")) {
                        val isLink = LINK.containsMatchIn(m.message)
                        if (kind == Kind.LINK && !isLink) return@forEach
                        out += Hit(if (isLink) Kind.LINK else Kind.MESSAGE, names[m.publicKey] ?: "", m.message, m.timestamp, contactPk = m.publicKey)
                    }
                }
                c.groupRepository().search(needle).forEach { g ->
                    if (g.fileName.isBlank() && !g.message.startsWith("\u2063")) {
                        val isLink = LINK.containsMatchIn(g.message)
                        if (kind == Kind.LINK && !isLink) return@forEach
                        val who = if (g.senderName.isNotBlank()) g.senderName + ": " else ""
                        out += Hit(if (isLink) Kind.LINK else Kind.MESSAGE, groupNames[g.chatId] ?: "", who + g.message, g.timestamp, groupId = g.chatId)
                    }
                }
            }
        }
        return out.sortedByDescending { it.time }.take(500)
    }

    /** Найденное слово - жирным и с подсветкой. */
    private fun highlight(text: String, q: String, color: Int): CharSequence {
        if (q.isBlank()) return text
        val i = text.indexOf(q, ignoreCase = true)
        if (i < 0) return text
        // Длинный текст - начинаем чуть до совпадения, чтобы его было видно.
        val from = (i - 40).coerceAtLeast(0)
        val shown = (if (from > 0) "…" else "") + text.substring(from)
        val j = shown.indexOf(q, ignoreCase = true)
        return SpannableStringBuilder(shown).apply {
            if (j >= 0) {
                setSpan(StyleSpan(Typeface.BOLD), j, j + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan((color and 0x00FFFFFF) or 0x40000000), j, j + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
