// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/** Запись «Избранного»: текст и/или файл (копия хранится в папке приложения). */
data class SavedItem(
    val id: Long,
    val text: String,
    val file: String?,
    val fileName: String?,
    val at: Long,
    val pinned: Boolean = false,
)

/**
 * «Избранное»: свои заметки и пересланные сюда сообщения и файлы.
 * Хранится только на этом устройстве.
 */
object SavedMessages {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("saved_messages", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "saved").apply { mkdirs() }

    fun all(ctx: Context): List<SavedItem> = try {
        val arr = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedItem(
                o.getLong("id"), o.optString("t"), o.optString("f").ifBlank { null },
                o.optString("n").ifBlank { null }, o.optLong("at"), o.optBoolean("p", false),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<SavedItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.text).put("f", it.file.orEmpty()).put("n", it.fileName.orEmpty()).put("at", it.at).put("p", it.pinned))
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun addText(ctx: Context, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        save(ctx, all(ctx) + SavedItem(now, text, null, null, now))
    }

    /** Сохранить копию файла (из чата) - она останется, даже если файл в чате удалят. */
    fun addFile(ctx: Context, source: File, name: String) {
        val now = System.currentTimeMillis()
        val out = File(dir(ctx), "${now}_$name")
        runCatching { source.copyTo(out, overwrite = true) }.onFailure { return }
        save(ctx, all(ctx) + SavedItem(now, "", out.absolutePath, name, now))
    }

    /** Любой файл/фото/видео/музыка по адресу (из чата, из «Поделиться», из проводника). */
    fun addUri(ctx: Context, uri: android.net.Uri, nameHint: String? = null): Boolean {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return false)
            // Исчезнувший или пустой файл не сохраняем - раньше в «Избранном» появлялась пустая запись.
            if (!f.isFile || f.length() <= 0L) return false
            addFile(ctx, f, nameHint ?: f.name)
            return true
        }
        val name = nameHint ?: runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "file_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val out = File(dir(ctx), "${now}_${name.replace('/', '_')}")
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } != null
        }.getOrDefault(false) && out.length() > 0L
        if (!ok) {
            out.delete()
            return false
        }
        save(ctx, all(ctx) + SavedItem(now, "", out.absolutePath, name, now))
        return true
    }

    /** Закрепить/открепить запись в «Избранном» (сколько угодно). */
    fun togglePin(ctx: Context, id: Long) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    }

    fun filesSize(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Удалить только файлы (освободить память), заметки остаются. */
    fun clearFiles(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        save(ctx, all(ctx).filter { it.file == null })
    }

    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        save(ctx, emptyList())
    }

    fun delete(ctx: Context, id: Long) {
        val list = all(ctx)
        list.firstOrNull { it.id == id }?.file?.let { File(it).delete() }
        save(ctx, list.filterNot { it.id == id })
    }

    /** Экран «Избранное». */
    private var listRefresher: (() -> Unit)? = null

    private fun mediaIcon(name: String?): String {
        val n = name.orEmpty().lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") -> "🖼"
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov") -> "🎬"
            n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".opus") -> "🎵"
            else -> "📎"
        }
    }

    private fun share(ctx: Context, item: SavedItem) {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        if (item.file != null) {
            val uri = runCatching { com.stoyko.smessenger.ui.chat.AttachLauncher.providerUri(ctx, File(item.file)) }.getOrNull() ?: return
            i.type = ctx.contentResolver.getType(uri) ?: "*/*"
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            i.type = "text/plain"
            i.putExtra(android.content.Intent.EXTRA_TEXT, item.text)
        }
        runCatching { ctx.startActivity(android.content.Intent.createChooser(i, null)) }
    }

    fun show(ctx: Context, onClosed: () -> Unit = {}) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        // Цвета приложения (светлая/тёмная тема), а не тёмная системная тема - раньше всё было
        // почти чёрным: тёмный фон, тёмно-фиолетовые карточки, плохо видно текст и даты.
        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val cBg = androidx.core.content.ContextCompat.getColor(ctx, R.color.colorBackground)
        val cText = androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary)
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val cCard = if (night) 0xFF2C2950.toInt() else 0xFFFFFFFF.toInt()
        val cCardStroke = if (night) 0xFF423C7A.toInt() else 0xFFDCD6FF.toInt()
        val cPinned = if (night) 0xFF3A3170.toInt() else 0xFFF0EDFF.toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF5B4BDB.toInt())
            setPadding(px(4), px(8), px(12), px(8))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(px(44), px(44)),
        )
        bar.addView(
            TextView(ctx).apply {
                text = "⭐ " + ctx.getString(R.string.saved_messages)
                setTextColor(Color.WHITE)
                textSize = 18f
                paint.isFakeBoldText = true
            },
        )
        val items = ArrayList(all(ctx))
        lateinit var adapterRef: BaseAdapter
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(
            TextView(ctx).apply {
                text = "⋮"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(px(14), 0, px(6), 0)
                setOnClickListener { v ->
                    val pm = android.widget.PopupMenu(ctx, v)
                    pm.menu.add(0, 1, 0, ctx.getString(R.string.saved_clear_files, android.text.format.Formatter.formatShortFileSize(ctx, filesSize(ctx))))
                    pm.menu.add(0, 2, 1, R.string.saved_clear_all)
                    pm.setOnMenuItemClickListener { item ->
                        androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setMessage(if (item.itemId == 1) R.string.saved_clear_files_confirm else R.string.saved_clear_all_confirm)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                if (item.itemId == 1) clearFiles(ctx) else clearAll(ctx)
                                items.clear()
                                items.addAll(all(ctx))
                                adapterRef.notifyDataSetChanged()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                    pm.show()
                }
            },
        )
        root.addView(bar)
        // Полоска закреплённых: нажатие - перейти к следующему закреплённому.
        val pinBar = TextView(ctx).apply {
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settingsAccent))
            setBackgroundColor(if (night) 0x33A89DFF else 0x1A5B4BDB)
            setPadding(px(16), px(10), px(16), px(10))
            visibility = View.GONE
        }
        root.addView(pinBar)
        var pinCursor = 0
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val list = ListView(ctx).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = items[p].id
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val it = items[p]
                val row = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(14), px(10), px(14), px(8))
                    layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(
                        android.widget.ImageView(ctx).apply {
                            tag = "thumb"
                            adjustViewBounds = true
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(180)).apply { bottomMargin = px(6) },
                    )
                    addView(TextView(ctx).apply { tag = "body"; textSize = 15f })
                    addView(TextView(ctx).apply { tag = "date"; textSize = 12f; gravity = Gravity.END })
                }
                row.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(if (it.pinned) cPinned else cCard)
                    setStroke(px(1), cCardStroke)
                }
                val thumb = row.findViewWithTag<android.widget.ImageView>("thumb")
                val body = row.findViewWithTag<TextView>("body")
                val date = row.findViewWithTag<TextView>("date")
                body.setTextColor(cText)
                date.setTextColor(cSub)
                val n = it.fileName.orEmpty().lowercase()
                val isImage = it.file != null && listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp").any { e -> n.endsWith(e) }
                if (isImage) {
                    thumb.visibility = View.VISIBLE
                    com.squareup.picasso.Picasso.get().load(File(it.file!!)).resize(px(360), px(180)).centerCrop().onlyScaleDown().into(thumb)
                } else {
                    com.squareup.picasso.Picasso.get().cancelRequest(thumb)
                    thumb.setImageDrawable(null)
                    thumb.visibility = View.GONE
                }
                body.text = (if (it.pinned) "📌 " else "") + (if (it.file != null) mediaIcon(it.fileName) + " ${it.fileName}" else it.text)
                date.text = fmt.format(Date(it.at))
                return row
            }
        }
        list.adapter = adapter
        adapterRef = adapter
        fun refreshPins() {
            val pinned = items.filter { it.pinned }
            pinBar.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
            pinBar.text = "📌 " + ctx.getString(R.string.pins_count, pinned.size) + " · " +
                pinned.lastOrNull()?.let { p -> p.fileName ?: p.text }.orEmpty().take(60)
        }
        refreshPins()
        pinBar.setOnClickListener {
            val idx = items.indices.filter { items[it].pinned }
            if (idx.isEmpty()) return@setOnClickListener
            pinCursor = (pinCursor + 1) % idx.size
            list.setSelection(idx[idx.size - 1 - pinCursor])
        }
        list.setPadding(px(10), px(6), px(10), px(6))
        list.clipToPadding = false
        list.dividerHeight = px(6)
        list.setOnItemClickListener { _, _, p, _ ->
            val it = items[p]
            if (it.file != null) {
                com.stoyko.smessenger.ui.chat.FileTypes.open(ctx, android.net.Uri.fromFile(File(it.file)).toString(), it.fileName ?: "file")
            } else {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("note", it.text))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val it = items[p]
            val actions = arrayOf<CharSequence>(
                ctx.getString(if (it.pinned) R.string.unpin else R.string.pin),
                ctx.getString(R.string.share),
                ctx.getString(R.string.delete),
                ctx.getString(R.string.saved_save_to_phone),
            )
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> {
                            togglePin(ctx, it.id)
                            items.clear()
                            items.addAll(all(ctx))
                            adapter.notifyDataSetChanged()
                            refreshPins()
                        }
                        1 -> share(ctx, it)
                        2 -> androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setMessage(R.string.saved_delete)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                delete(ctx, it.id)
                                items.removeAll { x -> x.id == it.id }
                                adapter.notifyDataSetChanged()
                                refreshPins()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        3 -> if (it.file != null) {
                            runCatching {
                                com.stoyko.smessenger.ui.chat.FileTypes.saveToPublic(
                                    ctx,
                                    com.stoyko.smessenger.ui.chat.FileTypes.shareUri(ctx, android.net.Uri.fromFile(File(it.file)).toString()),
                                    it.fileName ?: File(it.file).name,
                                )
                            }.onFailure { Toast.makeText(ctx, R.string.file_no_access, Toast.LENGTH_SHORT).show() }
                        } else {
                            Toast.makeText(ctx, R.string.saved_text_only, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
            true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(6), px(6), px(10))
        }
        // Скрепка: добавить в Избранное фото, видео, музыку и любые файлы с телефона.
        val registry = (ctx as? androidx.activity.ComponentActivity)?.activityResultRegistry
        val picker = registry?.register(
            "saved_pick_${System.nanoTime()}",
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNullOrEmpty()) return@register
            Toast.makeText(ctx, R.string.preparing_file, Toast.LENGTH_SHORT).show()
            Thread {
                var ok = 0
                uris.forEach { if (addUri(ctx, it)) ok++ }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    listRefresher?.invoke()
                    Toast.makeText(ctx, if (ok > 0) R.string.saved_added else R.string.file_no_access, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        inputRow.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_attach_file)
                setColorFilter(cSub)
                background = null
                contentDescription = ctx.getString(R.string.attach)
                setOnClickListener { picker?.launch(arrayOf("*/*")) }
            },
            LinearLayout.LayoutParams(px(48), px(48)),
        )
        val input = EditText(ctx).apply {
            setHint(R.string.saved_hint)
            setTextColor(cText)
            setHintTextColor(cSub)
            maxLines = 5
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.colorPrimary))
                background = null
                setOnClickListener {
                    val t = input.text.toString().trim()
                    if (t.isEmpty()) return@setOnClickListener
                    addText(ctx, t)
                    input.text.clear()
                    items.clear()
                    items.addAll(all(ctx))
                    adapter.notifyDataSetChanged()
                }
            },
            LinearLayout.LayoutParams(px(48), px(48)),
        )
        root.addView(inputRow)
        root.fitSystemBars()
        listRefresher = {
            items.clear()
            items.addAll(all(ctx))
            adapter.notifyDataSetChanged()
            refreshPins()
        }
        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener {
            picker?.unregister()
            listRefresher = null
            onClosed()
        }
        dialog.show()
    }
}

/** Строка «⭐ Избранное» в списке чатов (всегда сверху, как S-AI). */
class SavedRow(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val preview = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(8))
        val bg = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) setBackgroundResource(bg.resourceId)
        // Живая аватарка: звезда покачивается, по ней бежит блик, вокруг искры.
        val avatar = com.stoyko.smessenger.ui.SavedAvatarView(context)
        addView(avatar, LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        val title = TextView(context).apply {
            setText(R.string.saved_messages)
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
        val last = SavedMessages.all(context).lastOrNull()
        preview.text = when {
            last == null -> context.getString(R.string.saved_empty_preview)
            last.file != null -> "📎 " + last.fileName
            else -> last.text
        }
    }
}
