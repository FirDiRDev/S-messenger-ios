// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ai

import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.GlassFrameLayout

/** Круглый значок S-AI (градиент и «искра»). */
class AiAvatar(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        fill.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF7B6CF6.toInt(), 0xFF00C2A8.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(width / 2f, height / 2f, r, fill)
        text.textSize = r * 0.8f
        canvas.drawText("✦", width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
    }
}

/** Строка S-AI в списке чатов: всегда «в сети», как закреплённый контакт. */
class AiRow(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val preview = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(8))
        val bg = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) setBackgroundResource(bg.resourceId)
        val avatarBox = android.widget.FrameLayout(context)
        avatarBox.addView(AiAvatar(context), android.widget.FrameLayout.LayoutParams(dp(38), dp(38)))
        // Зелёная точка «в сети».
        avatarBox.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF22C55E.toInt())
                    setStroke(dp(2), Color.WHITE)
                }
            },
            android.widget.FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END),
        )
        addView(avatarBox, LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        val title = TextView(context).apply {
            text = "S-AI ✔"
            textSize = 15f
            paint.isFakeBoldText = true
            @Suppress("DEPRECATION")
            setTextColor(resources.getColor(R.color.textPrimary))
        }
        preview.textSize = 13f
        preview.maxLines = 1
        preview.ellipsize = android.text.TextUtils.TruncateAt.END
        @Suppress("DEPRECATION")
        preview.setTextColor(resources.getColor(R.color.textSecondary))
        texts.addView(title)
        texts.addView(preview)
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        refresh()
    }

    fun refresh() {
        val last = AiStore(context).load().lastOrNull()
        preview.text = last?.content?.replace('\n', ' ')?.take(80) ?: context.getString(R.string.ai_subtitle)
    }
}

/**
 * Чат с S-AI на весь экран: пузыри, «S-AI печатает…», копирование по долгому нажатию,
 * очистка и настройки (адрес API, ключ, модель).
 */
class AiChatDialog(private val context: Context, private val onClosed: () -> Unit = {}) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val config = AiConfig(context)
    private val store = AiStore(context)
    private var items: MutableList<AiMessage> = store.load().toMutableList()
    // Не «Fullscreen»: у полноэкранного окна система не сдвигает содержимое над клавиатурой,
    // и поле ввода пряталось под ней. Отступы под клавиатуру и системные панели - вручную (insets).
    private val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(context, android.R.style.Theme_Black_NoTitleBar))
    private val list = ListView(context)
    private val status = TextView(context)
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var inputRow: LinearLayout? = null
    private var dots = 0

    /** Прикреплённое фото (ещё не отправлено). */
    private var pendingImage: String? = null
    private var preview: LinearLayout? = null
    private val thumbs = HashMap<String, android.graphics.Bitmap>()

    private fun thumb(path: String): android.graphics.Bitmap? = thumbs[path] ?: runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        android.graphics.BitmapFactory.decodeFile(path, o)
    }.getOrNull()?.also { thumbs[path] = it }

    /** Фото из галереи: уменьшаем до 1280 px и сохраняем JPEG - так запрос быстрее и дешевле. */
    private fun attachImage(uri: android.net.Uri) {
        Thread {
            val path = runCatching {
                val cr = context.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
                val src = cr.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching null
                val k = 1280f / maxOf(src.width, src.height)
                val bmp = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
                val dir = java.io.File(context.filesDir, "s_ai_img").apply { mkdirs() }
                val f = java.io.File(dir, "p" + System.currentTimeMillis() + ".jpg")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                f.absolutePath
            }.getOrNull()
            main.post {
                if (path == null) {
                    Toast.makeText(context, R.string.ai_photo_failed, Toast.LENGTH_SHORT).show()
                } else {
                    pendingImage = path
                    showPreview()
                }
            }
        }.start()
    }

    private fun showPreview() {
        val box = preview ?: return
        box.removeAllViews()
        val path = pendingImage
        box.visibility = if (path == null) View.GONE else View.VISIBLE
        if (path == null) return
        box.addView(android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageBitmap(thumb(path))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
            }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        box.addView(TextView(context).apply {
            setText(R.string.ai_photo_attached)
            setTextColor(textColor)
            textSize = 13f
            setPadding(dp(10), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(iconButton(R.drawable.ic_close) {
            pendingImage = null
            showPreview()
        }.apply { setColorFilter(textColor) }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    @Suppress("DEPRECATION")
    private val bg = context.resources.getColor(R.color.colorBackground)

    @Suppress("DEPRECATION")
    private val surface = context.resources.getColor(R.color.colorSurface)

    @Suppress("DEPRECATION")
    private val textColor = context.resources.getColor(R.color.textPrimary)

    private val typing = object : Runnable {
        override fun run() {
            if (!busy) return
            dots = (dots + 1) % 4
            status.text = context.getString(R.string.ai_typing) + ".".repeat(dots)
            main.postDelayed(this, 400)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val m = items[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                addView(
                    TextView(context).apply {
                        textSize = 15f
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        setTextIsSelectable(false)
                        autoLinkMask = Linkify.WEB_URLS
                        linksClickable = true
                    },
                )
            }
            val tv = row.getChildAt(0) as TextView
            val mine = m.role == "user"
            (tv.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = if (mine) Gravity.END else Gravity.START
                if (mine) leftMargin = dp(48) else rightMargin = dp(48)
                if (mine) rightMargin = 0 else leftMargin = 0
            }
            tv.background = GradientDrawable().apply {
                val r = dp(18).toFloat()
                val s = dp(4).toFloat()
                cornerRadii = if (mine) floatArrayOf(r, r, s, s, r, r, r, r) else floatArrayOf(s, s, r, r, r, r, r, r)
                if (mine) {
                    colors = intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt())
                    orientation = GradientDrawable.Orientation.TL_BR
                } else {
                    setColor(if (m.error) 0xFFE5484D.toInt() else surface)
                }
            }
            tv.setTextColor(if (mine || m.error) Color.WHITE else textColor)
            // Фото в моём сообщении - миниатюра над текстом.
            val img = m.image?.let { thumb(it) }
            if (img != null) {
                val w = dp(180)
                val h = (w * img.height / img.width.coerceAtLeast(1)).coerceAtMost(dp(240))
                val dr = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(context.resources, img).apply { cornerRadius = dp(12).toFloat() }
                dr.setBounds(0, 0, w, h)
                tv.setCompoundDrawables(null, dr, null, null)
                tv.compoundDrawablePadding = dp(6)
            } else {
                tv.setCompoundDrawables(null, null, null, null)
            }
            tv.text = when {
                mine -> m.content
                m.content.isBlank() -> "…"
                else -> format(m.content)
            }
            tv.movementMethod = LinkMovementMethod.getInstance()
            return row
        }
    }

    fun show() {
        AiBackground.cancel(context.applicationContext)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        // Шапка - «стекло» с градиентом бренда.
        val header = GlassFrameLayout(context).apply {
            tintColor = 0xE05B4BDB.toInt()
            fallbackColor = 0xFF5B4BDB.toInt()
            edgeTop = false
            edgeBottom = true
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        bar.addView(iconButton(R.drawable.ic_back) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(AiAvatar(context), LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(4) })
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titles.addView(
            TextView(context).apply {
                text = "S-AI"
                textSize = 18f
                paint.isFakeBoldText = true
                setTextColor(Color.WHITE)
            },
        )
        status.textSize = 13f
        status.setTextColor(0xCCFFFFFF.toInt())
        status.setText(R.string.ai_online)
        titles.addView(status)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(R.drawable.ic_vpn) { runCatching { com.stoyko.smessenger.vpn.VpnScreen(context).show() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconButton(R.drawable.ic_settings) { showSettings() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconButton(R.drawable.ic_close) { confirmClear() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(bar)
        root.addView(header)
        // Подсказка для пользователей из РФ: нейросети доступны только через VPN.
        val hintPrefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (!hintPrefs.getBoolean("ai_vpn_hint_hidden", false)) {
            val banner = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(6), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0x22F59E0B)
                    setStroke(dp(1), 0x66F59E0B)
                }
            }
            banner.addView(TextView(context).apply {
                setText(R.string.ai_vpn_hint)
                textSize = 13.5f
                setTextColor(0xFFB45309.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            banner.addView(android.widget.Button(context).apply {
                setText(R.string.ai_vpn_open)
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(0xFFF59E0B.toInt())
                }
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { runCatching { com.stoyko.smessenger.vpn.VpnScreen(context).show() } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            banner.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFFB45309.toInt())
                background = null
                contentDescription = context.getString(R.string.close)
                setOnClickListener {
                    hintPrefs.edit().putBoolean("ai_vpn_hint_hidden", true).apply()
                    (banner.parent as? ViewGroup)?.removeView(banner)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(8), dp(10), 0)
            })
        }

        list.adapter = adapter
        list.divider = null
        list.transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        list.isStackFromBottom = true
        list.clipToPadding = false
        list.setPadding(0, dp(8), 0, dp(8))
        header.blurSource = list
        list.setOnItemLongClickListener { _, _, position, _ ->
            val m = items.getOrNull(position) ?: return@setOnItemLongClickListener false
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("S-AI", m.content))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setBackgroundColor(surface)
        }
        this.inputRow = inputRow
        val input = EditText(context).apply {
            setHint(R.string.ai_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(bg)
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        // 📷 Фото: S-AI распознаёт текст на снимке и что на нём изображено.
        val attach = iconButton(R.drawable.ic_image) {
            val act = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<com.stoyko.smessenger.MainActivity>().firstOrNull()
            if (act == null) {
                Toast.makeText(context, R.string.ai_photo_failed, Toast.LENGTH_SHORT).show()
            } else {
                act.pickImage { uri -> attachImage(uri) }
            }
        }
        attach.setColorFilter(0xFF5B4BDB.toInt())
        attach.contentDescription = context.getString(R.string.ai_photo_attach)
        inputRow.addView(attach, LinearLayout.LayoutParams(dp(44), dp(48)))
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Недописанный вопрос не теряется: сохраняется при сворачивании и возвращается при открытии.
        input.setText(com.stoyko.smessenger.ui.BackgroundSaver.draft(context, "s_ai"))
        com.stoyko.smessenger.ui.BackgroundSaver.register(this) {
            com.stoyko.smessenger.ui.BackgroundSaver.saveDraft(context, "s_ai", input.text.toString())
            items.removeAll { it.role == "assistant" && it.content.isBlank() && !busy }
            store.save(items)
        }
        val send = iconButton(R.drawable.ic_send) {
            val t = input.text.toString().trim().ifEmpty { if (pendingImage != null) context.getString(R.string.ai_photo_default) else "" }
            if (t.isEmpty()) return@iconButton
            if (busy) {
                Toast.makeText(context, R.string.ai_wait, Toast.LENGTH_SHORT).show()
                return@iconButton
            }
            input.text.clear()
            com.stoyko.smessenger.ui.BackgroundSaver.saveDraft(context, "s_ai", "")
            send(t)
        }
        send.setColorFilter(0xFF5B4BDB.toInt())
        inputRow.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        val previewBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), 0)
            setBackgroundColor(surface)
            visibility = View.GONE
        }
        preview = previewBox
        root.addView(previewBox)
        root.addView(inputRow)

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.statusBarColor = 0xFF5B4BDB.toInt()
        }
        // Шапка - под строкой состояния, поле ввода - над клавиатурой и кнопками навигации.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            header.setPadding(bars.left, bars.top, bars.right, 0)
            inputRow.setPadding(dp(10) + bars.left, dp(6), dp(6) + bars.right, dp(6) + maxOf(bars.bottom, ime.bottom))
            if (ime.bottom > 0) list.post { list.setSelection(items.size - 1) }
            insets
        }
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnDismissListener {
            closed = true
            // Запрос НЕ отменяем: ответ допишется в фоне, сохранится в переписке S-AI и придёт
            // уведомлением - ждать в открытом чате не нужно.
            if (busy) android.widget.Toast.makeText(context, R.string.ai_bg_working, android.widget.Toast.LENGTH_SHORT).show()
            // Недописанный пузырь не храним - готовый ответ добавится, когда придёт.
            items.removeAll { it.role == "assistant" && it.content.isBlank() }
            store.save(items)
            com.stoyko.smessenger.ui.BackgroundSaver.unregister(this)
            com.stoyko.smessenger.ui.BackgroundSaver.saveDraft(context, "s_ai", input.text.toString())
            onClosed()
        }
        dialog.show()
        if (items.isEmpty()) {
            items.add(AiMessage("assistant", context.getString(R.string.ai_greeting), System.currentTimeMillis()))
            store.save(items)
            adapter.notifyDataSetChanged()
        }
        if (!config.configured) main.postDelayed({ showSettings() }, 300)
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        setOnClickListener {
            it.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(200).start()
            }.start()
            onClick()
        }
    }

    /** Окно уже закрыто (ответ, пришедший позже, идёт в уведомление). */
    @Volatile
    private var closed = false

    private fun send(text: String) {
        if (!config.configured) {
            showSettings()
            return
        }
        items.add(AiMessage("user", text, System.currentTimeMillis(), image = pendingImage))
        pendingImage = null
        showPreview()
        store.save(items)
        val history = items.toList()
        // Пустой пузырь ответа - заполняется по мере того, как приходит текст.
        items.add(AiMessage("assistant", "", System.currentTimeMillis()))
        val slot = items.size - 1
        adapter.notifyDataSetChanged()
        list.setSelection(items.size - 1)
        busy = true
        main.post(typing)
        AiClient.ask(config, history, partial = { part ->
            if (slot < items.size) {
                items[slot] = items[slot].copy(content = part)
                adapter.notifyDataSetChanged()
                list.setSelection(items.size - 1)
            }
        }) { result ->
            busy = false
            if (closed) {
                // Чат закрыт: сохраняем ответ в переписку и показываем уведомление.
                AiBackground.deliver(context.applicationContext, result)
                return@ask
            }
            status.setText(R.string.ai_online)
            val answer = result.getOrNull()
            val msg = if (answer != null) {
                AiMessage("assistant", answer, System.currentTimeMillis())
            } else {
                AiMessage(
                    "assistant",
                    context.getString(R.string.ai_error, result.exceptionOrNull()?.message ?: "?"),
                    System.currentTimeMillis(),
                    error = true,
                )
            }
            if (slot < items.size) items[slot] = msg else items.add(msg)
            store.save(items)
            adapter.notifyDataSetChanged()
            list.setSelection(items.size - 1)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setMessage(R.string.ai_clear_confirm)
            .setPositiveButton(R.string.clear) { _, _ ->
                store.clear()
                items.clear()
                items.add(AiMessage("assistant", context.getString(R.string.ai_greeting), System.currentTimeMillis()))
                store.save(items)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Настройки S-AI: адрес API, ключ, модель, инструкция. */
    fun showSettings() = AiSettings.show(context, config)

    /** Немного разметки: **жирный**, `код`, ```блоки```. */
    private fun format(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        val t = text
        while (i < t.length) {
            when {
                t.startsWith("```", i) -> {
                    val end = t.indexOf("```", i + 3).let { if (it < 0) t.length else it }
                    val code = t.substring(i + 3, end).substringAfter('\n', t.substring(i + 3, end))
                    val s = sb.length
                    sb.append(code.trimEnd())
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0x22888888), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = (end + 3).coerceAtMost(t.length)
                }
                t.startsWith("**", i) -> {
                    val end = t.indexOf("**", i + 2)
                    if (end < 0) {
                        sb.append(t.substring(i))
                        i = t.length
                    } else {
                        val s = sb.length
                        sb.append(t.substring(i + 2, end))
                        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    }
                }
                t[i] == '`' -> {
                    val end = t.indexOf('`', i + 1)
                    if (end < 0) {
                        sb.append(t[i])
                        i++
                    } else {
                        val s = sb.length
                        sb.append(t.substring(i + 1, end))
                        sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    }
                }
                t.startsWith("### ", i) || t.startsWith("## ", i) || t.startsWith("# ", i) -> {
                    val skip = t.indexOf(' ', i) + 1
                    val end = t.indexOf('\n', skip).let { if (it < 0) t.length else it }
                    val s = sb.length
                    sb.append(t.substring(skip, end))
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end
                }
                else -> {
                    sb.append(t[i])
                    i++
                }
            }
        }
        return sb
    }
}

/** Окно настроек S-AI: выбор «мозга» (сервиса), ключ, модель, пожелания. */
object AiSettings {
    fun show(context: Context, config: AiConfig = AiConfig(context)) {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val providers = AiProviders.all
        var selected = providers.indexOfFirst { it.id == config.providerId }.coerceAtLeast(0)

        fun field(hint: Int, secret: Boolean = false) = EditText(context).apply {
            setHint(hint)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            setSingleLine()
        }
        val brain = android.widget.Spinner(context)
        brain.adapter = android.widget.ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.title },
        )
        val note = TextView(context).apply {
            textSize = 13f
            setPadding(px(4), px(2), 0, px(6))
        }
        val url = field(R.string.ai_base_url)
        val key = field(R.string.ai_api_key, secret = true)
        val model = field(R.string.ai_model)
        val getKey = android.widget.Button(context).apply { setText(R.string.ai_get_key) }
        val models = android.widget.Button(context).apply { setText(R.string.ai_models) }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(getKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(models, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val fallback = android.widget.CheckBox(context).apply {
            setText(R.string.ai_fallback)
            isChecked = config.autoFallback
        }
        val system = EditText(context).apply {
            setHint(R.string.ai_system_prompt)
            setText(config.extra)
            maxLines = 5
        }
        val hint = TextView(context).apply {
            setText(R.string.ai_settings_hint)
            textSize = 13f
            setPadding(0, px(8), 0, 0)
        }
        // Введённое, но ещё не сохранённое - по каждому сервису, чтобы переключение не стирало ключи.
        val keys = HashMap<String, String>()
        val modelsTyped = HashMap<String, String>()
        var customUrl = if (config.providerId == "custom") config.baseUrl else ""

        fun remember() {
            val p = providers[selected]
            keys[p.id] = key.text.toString()
            modelsTyped[p.id] = model.text.toString()
            if (p.id == "custom") customUrl = url.text.toString()
        }

        fun showProvider() {
            val p = providers[selected]
            note.text = p.note
            url.setText(if (p.id == "custom") customUrl else p.baseUrl)
            url.isEnabled = p.id == "custom"
            key.setText(keys[p.id] ?: config.keyFor(p.id))
            key.visibility = if (p.id == "vireonix") View.GONE else View.VISIBLE
            model.setText(modelsTyped[p.id] ?: config.modelFor(p.id))
            getKey.visibility = if (p.keyUrl.isBlank()) View.GONE else View.VISIBLE
        }
        brain.setSelection(selected)
        showProvider()
        brain.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == selected) return
                remember()
                selected = position
                showProvider()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        getKey.setOnClickListener {
            val p = providers[selected]
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.keyUrl)))
            }
        }
        models.setOnClickListener {
            models.isEnabled = false
            AiClient.listModels(url.text.toString(), key.text.toString()) { r ->
                models.isEnabled = true
                val list = r.getOrNull()
                if (list.isNullOrEmpty()) {
                    Toast.makeText(context, context.getString(R.string.ai_models_failed, r.exceptionOrNull()?.message ?: "-"), Toast.LENGTH_LONG).show()
                    return@listModels
                }
                // Бесплатные модели - первыми.
                val sorted = list.sortedBy { if (it.contains(":free")) 0 else 1 }
                AlertDialog.Builder(context)
                    .setTitle(R.string.ai_model)
                    .setItems(sorted.toTypedArray<CharSequence>()) { _, which -> model.setText(sorted[which]) }
                    .show()
            }
        }
        val title = TextView(context).apply {
            setText(R.string.ai_brain)
            textSize = 13f
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            listOf(title, brain, note, url, key, model, buttons, fallback, system, hint).forEach { addView(it) }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.ai_settings)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                remember()
                val p = providers[selected]
                config.providerId = p.id
                for ((id, k) in keys) config.setKeyFor(id, k)
                for ((id, m) in modelsTyped) config.setModelFor(id, m)
                if (p.id == "custom") config.baseUrl = customUrl
                config.autoFallback = fallback.isChecked
                config.extra = system.text.toString()
                if (p.needsKey && config.apiKey.isBlank()) {
                    Toast.makeText(context, R.string.ai_need_key, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.ai_reset) { _, _ ->
                config.reset()
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * Ответ S-AI, пришедший после закрытия чата: дописывается в сохранённую переписку и
 * показывается уведомлением «S-AI ответил» (нажатие открывает приложение и чат S-AI).
 */
object AiBackground {
    const val ACTION_OPEN = "com.stoyko.smessenger.OPEN_SAI"
    private const val CHANNEL = "s_ai_replies"
    private const val NOTIFY_ID = 7420

    fun deliver(app: Context, result: Result<String>) {
        val answer = result.getOrNull()
        val msg = if (answer != null) {
            AiMessage("assistant", answer, System.currentTimeMillis())
        } else {
            AiMessage("assistant", app.getString(R.string.ai_error, result.exceptionOrNull()?.message ?: "?"), System.currentTimeMillis(), error = true)
        }
        val store = AiStore(app)
        store.save(store.load().filterNot { it.role == "assistant" && it.content.isBlank() } + msg)
        notify(app, msg)
    }

    private fun notify(app: Context, msg: AiMessage) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(CHANNEL, app.getString(R.string.ai_notify_channel), android.app.NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = android.content.Intent(app, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(
                app, NOTIFY_ID, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val text = msg.content.replace(Regex("[*_`#>]"), "").trim()
            val n = androidx.core.app.NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFF7B6CF6.toInt())
                .setContentTitle(app.getString(if (msg.error) R.string.ai_notify_error else R.string.ai_notify_title))
                .setContentText(text.take(120))
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }

    fun cancel(app: Context) {
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(NOTIFY_ID) }
    }
}
