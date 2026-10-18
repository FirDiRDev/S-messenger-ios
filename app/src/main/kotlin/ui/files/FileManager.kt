// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.files

import com.stoyko.smessenger.ui.safeDismiss
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.fitSystemBars
import java.io.File
import java.util.Date

/**
 * Файловый менеджер:
 *  - хранилища с заполненностью (память телефона, SD-карта, «Загрузки», «Камера»,
 *    «Документы», файлы мессенджера);
 *  - путь «хлебными крошками», сортировка (имя/дата/размер/тип), скрытые файлы, поиск по
 *    папке и вложенным;
 *  - выбор нескольких файлов: копировать, переместить, переименовать, удалить, поделиться,
 *    свойства, новая папка;
 *  - архиватор ([ArchiverUi], [Archive]): ZIP с паролем и степенью сжатия, просмотр архива
 *    как папки (ZIP/JAR/APK/CBZ, TAR, TAR.GZ), извлечение, добавление, удаление, проверка;
 *  - превью фото и видео, открыть любым приложением;
 *  - анализ памяти (что сколько занимает, самые крупные файлы), недавние файлы,
 *    избранные папки, очистка (кэш мессенджера, копии отправленного, старые APK, миниатюры),
 *    копирование пути.
 * Для доступа ко всей памяти на Android 11+ нужно разрешение «Доступ ко всем файлам».
 */
@SuppressLint("SetTextI18n")
class FileManager private constructor(private val context: Context) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val dialog = Dialog(context, R.style.AppTheme_FullDialog)
    private val prefs = context.getSharedPreferences("file_manager", Context.MODE_PRIVATE)
    private val cText = ContextCompat.getColor(context, R.color.textPrimary)
    private val cSub = ContextCompat.getColor(context, R.color.textSecondary)
    private val cCard = ContextCompat.getColor(context, R.color.colorSurface)
    private val cBg = ContextCompat.getColor(context, R.color.colorBackground)

    private var dir: File? = null
    private var items: List<File> = emptyList()
    private val selected = LinkedHashSet<File>()
    private var query = ""
    private var searchResults: List<File>? = null
    private var clipboard: List<File> = emptyList()
    private var clipboardMove = false

    /** Виртуальный список (недавние, крупные файлы, APK) - не папка на диске. */
    private var virtualTitle: String? = null

    private var favorites: Set<String>
        get() = prefs.getStringSet("favs", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("favs", v).apply()

    private var sort: Int
        get() = prefs.getInt("sort", 0)
        set(v) = prefs.edit().putInt("sort", v).apply()
    private var desc: Boolean
        get() = prefs.getBoolean("desc", false)
        set(v) = prefs.edit().putBoolean("desc", v).apply()
    private var showHidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) = prefs.edit().putBoolean("hidden", v).apply()

    private val title = TextView(context)
    private val crumbs = LinearLayout(context)
    private val crumbsScroll = HorizontalScrollView(context)
    private val list = ListView(context)
    private val rootsBox = LinearLayout(context)
    private val rootsScroll = ScrollView(context)
    private val empty = TextView(context)
    private val actionBar = LinearLayout(context)
    private val pasteBar = LinearLayout(context)
    private val searchField = EditText(context)

    // ---------------- окно ----------------

    private fun build() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt()))
            setPadding(dp(4), dp(6), dp(8), dp(10))
            elevation = 6 * d
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun icon(res: Int, click: (View) -> Unit) = ImageButton(context).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { click(it) }
            Kinetics.squish(this, 0.85f)
        }
        bar.addView(icon(R.drawable.ic_back) { goUp() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        title.textSize = 19f
        title.setTextColor(Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bar.addView(icon(R.drawable.ic_search) { toggleSearch() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(icon(R.drawable.ic_more_vert) { menu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(bar)
        // Поиск по папке и вложенным.
        searchField.apply {
            setHint(R.string.fm_search_hint)
            setSingleLine()
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xAAFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(0x33FFFFFF)
            }
            setPadding(dp(14), dp(9), dp(14), dp(9))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    runSearch()
                }
            })
        }
        header.addView(searchField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(4), 0, dp(4))
        })
        crumbs.orientation = LinearLayout.HORIZONTAL
        crumbs.setPadding(dp(8), dp(4), dp(8), 0)
        crumbsScroll.isHorizontalScrollBarEnabled = false
        crumbsScroll.addView(crumbs)
        header.addView(crumbsScroll)
        root.addView(header)

        val content = FrameLayout(context)
        rootsBox.orientation = LinearLayout.VERTICAL
        rootsBox.setPadding(dp(14), dp(12), dp(14), dp(20))
        rootsScroll.addView(rootsBox)
        content.addView(rootsScroll, FrameLayout.LayoutParams(-1, -1))
        list.divider = null
        list.isFastScrollEnabled = true
        list.adapter = adapter
        list.setOnItemClickListener { _, view, position, _ ->
            val f = shown().getOrNull(position) ?: return@setOnItemClickListener
            if (selected.isNotEmpty()) toggleSelect(f) else open(f, view)
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            val f = shown().getOrNull(position) ?: return@setOnItemLongClickListener false
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            toggleSelect(f)
            true
        }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.setText(R.string.fm_empty)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.setTextColor(cSub)
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // Панель «Вставить сюда» (копирование/перемещение).
        pasteBar.orientation = LinearLayout.HORIZONTAL
        pasteBar.gravity = Gravity.CENTER_VERTICAL
        pasteBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        pasteBar.setBackgroundColor(cCard)
        pasteBar.elevation = 10 * d
        pasteBar.visibility = View.GONE
        root.addView(pasteBar)
        // Панель действий над выбранными.
        actionBar.orientation = LinearLayout.HORIZONTAL
        actionBar.gravity = Gravity.CENTER_VERTICAL
        actionBar.setPadding(dp(8), dp(8), dp(8), dp(8))
        actionBar.setBackgroundColor(cCard)
        actionBar.elevation = 10 * d
        actionBar.visibility = View.GONE
        root.addView(actionBar)

        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                when {
                    selected.isNotEmpty() -> {
                        selected.clear()
                        refreshSelection()
                        true
                    }
                    searchField.visibility == View.VISIBLE -> {
                        toggleSearch()
                        true
                    }
                    dir != null || virtualTitle != null -> {
                        goUp()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    // ---------------- хранилища ----------------

    private class Root(val emoji: String, val title: String, val file: File, val color: Int, val volume: Boolean)

    private fun roots(): List<Root> {
        val out = ArrayList<Root>()
        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory()
        out += Root("📱", context.getString(R.string.fm_internal), ext, 0xFF5B4BDB.toInt(), true)
        // SD-карта: второй том (путь до /Android/...).
        context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
            val p = f.absolutePath.substringBefore("/Android/")
            out += Root("💾", context.getString(R.string.fm_sd), File(p), 0xFFF59E0B.toInt(), true)
        }
        @Suppress("DEPRECATION")
        fun pub(type: String) = Environment.getExternalStoragePublicDirectory(type)
        out += Root("⬇️", context.getString(R.string.fm_downloads), pub(Environment.DIRECTORY_DOWNLOADS), 0xFF0EA5E9.toInt(), false)
        out += Root("📷", context.getString(R.string.fm_camera), pub(Environment.DIRECTORY_DCIM), 0xFFEC4899.toInt(), false)
        out += Root("🖼", context.getString(R.string.fm_pictures), pub(Environment.DIRECTORY_PICTURES), 0xFF8B5CF6.toInt(), false)
        out += Root("🎬", context.getString(R.string.fm_movies), pub(Environment.DIRECTORY_MOVIES), 0xFFEF4444.toInt(), false)
        out += Root("🎵", context.getString(R.string.fm_music), pub(Environment.DIRECTORY_MUSIC), 0xFF10B981.toInt(), false)
        out += Root("📄", context.getString(R.string.fm_documents), pub(Environment.DIRECTORY_DOCUMENTS), 0xFF64748B.toInt(), false)
        // Файлы самого мессенджера - доступны всегда, без разрешений.
        out += Root("💬", context.getString(R.string.fm_app_files), context.filesDir, 0xFF7B6CF6.toInt(), false)
        return out
    }

    private fun hasFullAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun askAccess() {
        val a = Kinetics.activityOf(context) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                a.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}")),
                )
            }.onFailure { runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                a,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                7412,
            )
        }
    }

    private fun showRoots() {
        dir = null
        virtualTitle = null
        searchResults = null
        selected.clear()
        title.setText(R.string.fm_title)
        crumbs.removeAllViews()
        crumbsScroll.visibility = View.GONE
        list.visibility = View.GONE
        empty.visibility = View.GONE
        rootsScroll.visibility = View.VISIBLE
        rootsBox.removeAllViews()
        val cards = ArrayList<View>()
        if (!hasFullAccess()) {
            val warn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(0x1FF59E0B)
                    setStroke(dp(1), 0x66F59E0B)
                }
            }
            warn.addView(TextView(context).apply {
                setText(R.string.fm_access_text)
                textSize = 13f
                setTextColor(cText)
            })
            warn.addView(
                Btn.primary(context, context.getString(R.string.fm_access_button), intArrayOf(0xFFF59E0B.toInt(), 0xFFD97706.toInt())) { askAccess() },
                LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) },
            )
            rootsBox.addView(warn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            cards += warn
        }
        // Инструменты: анализ памяти, недавние, очистка.
        val tools = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("📊", R.string.fm_tool_analyze) { analyze() },
            Triple("🕘", R.string.fm_tool_recent) { showRecent() },
            Triple("🧹", R.string.fm_tool_clean) { cleanup() },
            Triple("🗜", R.string.arc_title) { archiverHome() },
        ).forEachIndexed { i, (e, label, action) ->
            tools.addView(
                Btn.tile(context, e, context.getString(label)) { action() },
                LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(8) },
            )
        }
        rootsBox.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        cards += tools
        // Избранные папки.
        val favs = favorites.map { File(it) }.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        if (favs.isNotEmpty()) {
            rootsBox.addView(sectionLabel(R.string.fm_favorites))
            for (f in favs) {
                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(8), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * d
                        setColor(cCard)
                    }
                    isClickable = true
                    setOnClickListener { openDir(f) }
                    setOnLongClickListener {
                        toggleFavorite(f)
                        showRoots()
                        true
                    }
                    Kinetics.squish(this, 0.97f)
                }
                chip.addView(TextView(context).apply { text = "⭐"; textSize = 18f })
                chip.addView(TextView(context).apply {
                    text = f.name.ifBlank { "/" } + "\n" + f.absolutePath
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(cText)
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                rootsBox.addView(chip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                cards += chip
            }
            rootsBox.addView(sectionLabel(R.string.fm_storages))
        }
        for (r in roots()) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(cCard)
                }
                elevation = 1 * d
                isClickable = true
                setOnClickListener { openDir(r.file) }
                Kinetics.squish(this, 0.97f)
            }
            card.addView(TextView(context).apply {
                text = r.emoji
                textSize = 22f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor((r.color and 0x00FFFFFF) or 0x26000000)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            texts.addView(TextView(context).apply {
                text = r.title
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(cText)
            })
            if (r.volume) {
                val st = runCatching { StatFs(r.file.absolutePath) }.getOrNull()
                if (st != null) {
                    val total = st.totalBytes
                    val free = st.availableBytes
                    val used = (total - free).coerceAtLeast(0)
                    texts.addView(TextView(context).apply {
                        text = context.getString(
                            R.string.fm_usage,
                            Formatter.formatShortFileSize(context, used),
                            Formatter.formatShortFileSize(context, total),
                            Formatter.formatShortFileSize(context, free),
                        )
                        textSize = 12f
                        setTextColor(cSub)
                        setPadding(0, dp(2), 0, dp(6))
                    })
                    // Полоска заполненности.
                    val track = FrameLayout(context).apply {
                        background = GradientDrawable().apply { cornerRadius = 3 * d; setColor(0x22888888) }
                    }
                    val fill = View(context).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(r.color, 0xFFEC4899.toInt()))
                            .apply { cornerRadius = 3 * d }
                        pivotX = 0f
                        scaleX = 0f
                    }
                    track.addView(fill, FrameLayout.LayoutParams(-1, -1))
                    texts.addView(track, LinearLayout.LayoutParams(-1, dp(6)))
                    val frac = if (total > 0) used.toFloat() / total else 0f
                    fill.animate().scaleX(frac).setDuration(700).setStartDelay(200).start()
                }
            } else {
                texts.addView(TextView(context).apply {
                    text = r.file.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath).ifBlank { "/" }
                    textSize = 12f
                    maxLines = 1
                    setTextColor(cSub)
                })
            }
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(context).apply {
                text = "›"
                textSize = 22f
                setTextColor(cSub)
            })
            rootsBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            cards += card
        }
        Kinetics.stagger(cards, step = 40)
        refreshSelection()
    }

    // ---------------- инструменты: избранное, путь, анализ, недавние, очистка ----------------

    private fun sectionLabel(res: Int) = TextView(context).apply {
        setText(res)
        textSize = 13f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(cSub)
        setPadding(dp(4), dp(6), 0, dp(8))
    }

    private fun toggleFavorite(f: File) {
        val cur = favorites.toMutableSet()
        val added = cur.add(f.absolutePath)
        if (!added) cur.remove(f.absolutePath)
        favorites = cur
        Toast.makeText(context, if (added) R.string.fm_fav_added else R.string.fm_fav_removed, Toast.LENGTH_SHORT).show()
    }

    private fun copyPath(f: File) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
        Toast.makeText(context, R.string.fm_path_copied, Toast.LENGTH_SHORT).show()
    }

    /** Показать список файлов, собранных из разных папок (недавние, крупные, APK). */
    private fun showVirtual(titleText: String, files: List<File>) {
        dir = null
        virtualTitle = titleText
        searchResults = null
        selected.clear()
        if (searchField.visibility == View.VISIBLE) {
            searchField.setText("")
            searchField.visibility = View.GONE
        }
        title.text = titleText
        crumbs.removeAllViews()
        crumbsScroll.visibility = View.GONE
        rootsScroll.visibility = View.GONE
        list.visibility = View.VISIBLE
        items = files
        adapter.notifyDataSetChanged()
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        refreshSelection()
        if (Kinetics.enabled) {
            list.alpha = 0f
            list.animate().alpha(1f).setDuration(220).start()
        }
    }

    private class Scan(
        val sizes: LongArray,
        val counts: IntArray,
        val largest: List<File>,
        val recent: List<File>,
        val files: Int,
        val total: Long,
    )

    private fun category(f: File): Int = when (f.extension.lowercase()) {
        in IMAGES -> 0
        in VIDEOS -> 1
        in AUDIO -> 2
        "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "fb2" -> 3
        "zip", "rar", "7z", "tar", "gz", "tgz", "cbz", "bz2", "xz" -> 4
        "apk", "apks", "xapk" -> 5
        else -> 6
    }

    /**
     * Один проход по памяти телефона (до 200 000 файлов): размер по типам, 60 самых крупных
     * и 300 самых свежих (за 7 дней) - в куче фиксированного размера, без сортировки всего списка.
     */
    private fun scan(): Scan {
        val sizes = LongArray(CATS.size)
        val counts = IntArray(CATS.size)
        val big = java.util.PriorityQueue<File>(61, compareBy { it.length() })
        val fresh = java.util.PriorityQueue<File>(301, compareBy { it.lastModified() })
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
        @Suppress("DEPRECATION")
        val rootDirs = buildList {
            add(Environment.getExternalStorageDirectory())
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { add(File(it.absolutePath.substringBefore("/Android/"))) }
        }
        val stack = ArrayDeque<File>()
        rootDirs.forEach { stack.add(it) }
        var n = 0
        var total = 0L
        while (stack.isNotEmpty() && n < 200_000) {
            val dirF = stack.removeLast()
            val children = dirF.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    // Служебные папки других приложений недоступны и только тормозят обход.
                    if (c.name == "data" && dirF.name == "Android") continue
                    if (c.name == "obb" && dirF.name == "Android") continue
                    stack.add(c)
                    continue
                }
                n++
                val len = c.length()
                total += len
                val cat = category(c)
                sizes[cat] += len
                counts[cat]++
                big.add(c)
                if (big.size > 60) big.poll()
                if (!c.name.startsWith(".") && !c.absolutePath.contains("/.")) {
                    val m = c.lastModified()
                    if (m >= weekAgo) {
                        fresh.add(c)
                        if (fresh.size > 300) fresh.poll()
                    }
                }
            }
        }
        return Scan(
            sizes, counts,
            big.toList().sortedByDescending { it.length() },
            fresh.toList().sortedByDescending { it.lastModified() },
            n, total,
        )
    }

    private fun scanThen(done: (Scan) -> Unit) {
        val toast = Toast.makeText(context, R.string.fm_scanning, Toast.LENGTH_SHORT)
        toast.show()
        Thread {
            val r = runCatching { scan() }.getOrNull()
            main.post {
                if (!dialog.isShowing) return@post
                if (r == null) Toast.makeText(context, R.string.fm_failed, Toast.LENGTH_SHORT).show() else done(r)
            }
        }.start()
    }

    private fun showRecent() = scanThen { s -> showVirtual(context.getString(R.string.fm_recent_title), s.recent) }

    /** Что занимает место: полоски по типам файлов и список самых крупных. */
    private fun analyze() = scanThen { s ->
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        box.addView(TextView(context).apply {
            text = context.getString(R.string.fm_analyze_total, s.files, Formatter.formatShortFileSize(context, s.total))
            textSize = 13f
            setTextColor(cSub)
            setPadding(0, 0, 0, dp(10))
        })
        val max = (s.sizes.maxOrNull() ?: 0L).coerceAtLeast(1L)
        CATS.indices.sortedByDescending { s.sizes[it] }.forEach { i ->
            if (s.counts[i] == 0) return@forEach
            val (emoji, label, color) = CATS[i]
            box.addView(TextView(context).apply {
                text = "$emoji  " + context.getString(label) + " · " + s.counts[i] + " · " + Formatter.formatShortFileSize(context, s.sizes[i])
                textSize = 14f
                setTextColor(cText)
            })
            val track = FrameLayout(context).apply {
                background = GradientDrawable().apply { cornerRadius = 4 * d; setColor(0x22888888) }
            }
            val fill = View(context).apply {
                background = GradientDrawable().apply { cornerRadius = 4 * d; setColor(color) }
                pivotX = 0f
                scaleX = 0f
            }
            track.addView(fill, FrameLayout.LayoutParams(-1, -1))
            box.addView(track, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4); bottomMargin = dp(12) })
            fill.animate().scaleX(s.sizes[i].toFloat() / max).setDuration(600).setStartDelay(120).start()
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.fm_analyze_title)
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton(R.string.fm_largest) { _, _ -> showVirtual(context.getString(R.string.fm_largest), s.largest) }
            .setNeutralButton(R.string.fm_tool_clean) { _, _ -> cleanup() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Очистка: кэш мессенджера, копии отправленного (старше суток), установочные APK в «Загрузках»,
     * системные миниатюры галереи (.thumbnails - телефон создаст их заново).
     */
    private fun cleanup() {
        Thread {
            val app = context.applicationContext
            val cache = com.stoyko.smessenger.settings.StorageCleaner.cacheSize(app)
            val sent = com.stoyko.smessenger.settings.StorageCleaner.size(com.stoyko.smessenger.settings.StorageCleaner.sentCopies(app))
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val apks = dl.listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }.orEmpty()
            val apkSize = apks.sumOf { it.length() }
            @Suppress("DEPRECATION")
            val thumbs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ".thumbnails")
            val thumbSize = if (thumbs.canRead()) com.stoyko.smessenger.settings.StorageCleaner.size(thumbs) else 0L
            main.post {
                if (!dialog.isShowing) return@post
                val fmt = { b: Long -> Formatter.formatShortFileSize(context, b) }
                val opts = ArrayList<Pair<String, () -> Long>>()
                if (cache > 0) opts += context.getString(R.string.fm_clean_cache, fmt(cache)) to {
                    com.stoyko.smessenger.settings.StorageCleaner.clearCache(app)
                    cache
                }
                if (sent > 0) opts += context.getString(R.string.fm_clean_sent, fmt(sent)) to {
                    com.stoyko.smessenger.settings.StorageCleaner.clearSentCopiesNow(app)
                }
                if (apks.isNotEmpty()) opts += context.getString(R.string.fm_clean_apk, apks.size, fmt(apkSize)) to {
                    apks.sumOf { f -> f.length().let { len -> if (f.delete()) len else 0L } }
                }
                if (thumbSize > 0) opts += context.getString(R.string.fm_clean_thumbs, fmt(thumbSize)) to {
                    thumbs.listFiles()?.forEach { it.deleteRecursively() }
                    thumbSize
                }
                if (opts.isEmpty()) {
                    Toast.makeText(context, R.string.fm_clean_nothing, Toast.LENGTH_LONG).show()
                    return@post
                }
                // По умолчанию отмечено безопасное: кэш и миниатюры; APK и копии - по желанию.
                val safe = setOf(context.getString(R.string.fm_clean_cache, fmt(cache)), context.getString(R.string.fm_clean_thumbs, fmt(thumbSize)))
                val checked = BooleanArray(opts.size) { i -> opts[i].first in safe }
                val b = AlertDialog.Builder(context)
                    .setTitle(R.string.fm_clean_title)
                    .setMultiChoiceItems(opts.map { it.first as CharSequence }.toTypedArray(), checked) { _, w, on -> checked[w] = on }
                    .setPositiveButton(R.string.fm_clean_run) { _, _ ->
                        val chosen = opts.filterIndexed { i, _ -> checked[i] }
                        if (chosen.isEmpty()) return@setPositiveButton
                        var freed = 0L
                        background(R.string.fm_deleting, { chosen.forEach { freed += runCatching { it.second() }.getOrDefault(0L) } }) {
                            Toast.makeText(context, context.getString(R.string.fm_clean_done, fmt(freed)), Toast.LENGTH_LONG).show()
                            if (virtualTitle != null || dir != null) reload()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                if (apks.isNotEmpty()) b.setNeutralButton(R.string.fm_clean_show_apk) { _, _ -> showVirtual("APK", apks) }
                b.show()
            }
        }.start()
    }

    // ---------------- папка ----------------

    private fun openDir(f: File, forward: Boolean = true) {
        if (!f.exists() || !f.canRead()) {
            Toast.makeText(context, if (hasFullAccess()) R.string.fm_no_access else R.string.fm_need_access, Toast.LENGTH_LONG).show()
            if (!hasFullAccess()) askAccess()
            return
        }
        dir = f
        virtualTitle = null
        searchResults = null
        if (searchField.visibility == View.VISIBLE) searchField.setText("")
        selected.clear()
        rootsScroll.visibility = View.GONE
        list.visibility = View.VISIBLE
        crumbsScroll.visibility = View.VISIBLE
        title.text = f.name.ifBlank { "/" }
        reload()
        buildCrumbs()
        // Папка въезжает сбоку.
        if (Kinetics.enabled) {
            list.translationX = (if (forward) 40 else -40) * d
            list.alpha = 0f
            list.animate().translationX(0f).alpha(1f).setDuration(220).start()
        }
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        val chain = ArrayList<File>()
        var c: File? = dir
        while (c != null) {
            chain.add(0, c)
            c = c.parentFile
        }
        val ext = Environment.getExternalStorageDirectory().absolutePath
        val start = chain.indexOfFirst { it.absolutePath == ext }.takeIf { it >= 0 } ?: 0
        chain.drop(start).forEachIndexed { i, f ->
            if (i > 0) crumbs.addView(TextView(context).apply { text = " › "; setTextColor(0xAAFFFFFF.toInt()); textSize = 13f })
            crumbs.addView(TextView(context).apply {
                text = if (f.absolutePath == ext) context.getString(R.string.fm_internal) else f.name.ifBlank { "/" }
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(if (f == dir) 0x40FFFFFF else 0x1AFFFFFF)
                }
                setOnClickListener { openDir(f, forward = false) }
            })
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun goUp() {
        if (virtualTitle != null) {
            showRoots()
            return
        }
        val cur = dir ?: run {
            dialog.safeDismiss()
            return
        }
        val isRoot = roots().any { it.file.absolutePath == cur.absolutePath }
        val parent = cur.parentFile
        if (isRoot || parent == null || !parent.canRead()) showRoots() else openDir(parent, forward = false)
    }

    private fun reload() {
        if (virtualTitle != null) {
            // Виртуальный список: убираем удалённые/перемещённые.
            items = items.filter { it.exists() }
            adapter.notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            refreshSelection()
            return
        }
        val f = dir ?: return
        val raw = f.listFiles()?.toList().orEmpty().filter { showHidden || !it.name.startsWith(".") }
        val cmp: Comparator<File> = when (sort) {
            1 -> compareBy { it.lastModified() }
            2 -> compareBy { if (it.isDirectory) -1L else it.length() }
            3 -> compareBy { it.extension.lowercase() }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val sorted = raw.sortedWith(cmp).let { if (desc) it.reversed() else it }
        // Папки - всегда сверху.
        items = sorted.filter { it.isDirectory } + sorted.filter { !it.isDirectory }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown().isEmpty()) View.VISIBLE else View.GONE
        refreshSelection()
    }

    private fun shown(): List<File> = searchResults ?: items

    private fun toggleSearch() {
        if (dir == null) {
            Toast.makeText(context, R.string.fm_pick_folder_first, Toast.LENGTH_SHORT).show()
            return
        }
        val show = searchField.visibility != View.VISIBLE
        searchField.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            searchField.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(searchField, 0)
        } else {
            searchField.setText("")
            searchResults = null
            reload()
        }
    }

    private var searchToken = 0

    /** Поиск в папке и вложенных (до 1500 совпадений), в фоне. */
    private fun runSearch() {
        val base = dir ?: return
        val q = query
        val token = ++searchToken
        if (q.length < 2) {
            searchResults = null
            reload()
            return
        }
        Thread {
            val out = ArrayList<File>()
            val stack = ArrayDeque<File>()
            stack.add(base)
            var visited = 0
            while (stack.isNotEmpty() && out.size < 1500 && visited < 40_000 && token == searchToken) {
                val f = stack.removeLast()
                visited++
                f.listFiles()?.forEach { c ->
                    if (!showHidden && c.name.startsWith(".")) return@forEach
                    if (c.name.contains(q, ignoreCase = true)) out += c
                    if (c.isDirectory) stack.add(c)
                }
            }
            main.post {
                if (token != searchToken) return@post
                searchResults = out
                adapter.notifyDataSetChanged()
                empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // ---------------- список ----------------

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown().size
        override fun getItem(position: Int) = shown()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = shown()[position]
            val row = (convertView as? LinearLayout)?.takeIf { it.tag is Holder } ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                val iconBox = FrameLayout(context)
                iconBox.addView(TextView(context).apply { gravity = Gravity.CENTER; textSize = 20f; tag = "emoji" }, FrameLayout.LayoutParams(-1, -1))
                iconBox.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "thumb" }, FrameLayout.LayoutParams(-1, -1))
                iconBox.clipToOutline = true
                iconBox.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                addView(iconBox, LinearLayout.LayoutParams(dp(46), dp(46)))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                texts.addView(TextView(context).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTextColor(cText) })
                texts.addView(TextView(context).apply { textSize = 12f; maxLines = 1; setTextColor(cSub) })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { textSize = 18f; gravity = Gravity.CENTER; tag = "check" }, LinearLayout.LayoutParams(dp(30), dp(30)))
                // Ссылки на части строки - не через теги (загрузчик превью сам ставит тег картинке).
                tag = Holder(
                    iconBox,
                    iconBox.getChildAt(0) as TextView,
                    iconBox.getChildAt(1) as ImageView,
                    texts.getChildAt(0) as TextView,
                    texts.getChildAt(1) as TextView,
                    getChildAt(2) as TextView,
                )
            }
            val h = row.tag as Holder
            val iconBox = h.iconBox
            val (emoji, color) = typeOf(f)
            iconBox.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor((color and 0x00FFFFFF) or 0x26000000)
            }
            val emojiView = h.emoji
            val thumb = h.thumb
            emojiView.text = emoji
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
            val ext = f.extension.lowercase()
            if (f.isFile && (ext in IMAGES || ext in VIDEOS)) {
                thumb.visibility = View.VISIBLE
                if (ext in IMAGES) {
                    com.squareup.picasso.Picasso.get().load(f).resize(dp(46), dp(46)).centerCrop().into(thumb)
                } else {
                    com.stoyko.smessenger.ui.chat.FileTypes.loadThumb(context, Uri.fromFile(f).toString(), thumb, dp(46))
                }
            }
            h.name.text = f.name
            h.info.text = describe(f)
            val check = h.check
            val on = f in selected
            check.text = if (selected.isEmpty()) "" else if (on) "✓" else "○"
            check.setTextColor(if (on) Color.WHITE else cSub)
            check.background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF5B4BDB.toInt()) } else null
            row.setBackgroundColor(if (on) 0x1A5B4BDB else Color.TRANSPARENT)
            return row
        }
    }

    private class Holder(
        val iconBox: FrameLayout,
        val emoji: TextView,
        val thumb: ImageView,
        val name: TextView,
        val info: TextView,
        val check: TextView,
    )

    private fun describe(f: File): String {
        val date = DateFormat.getDateFormat(context).format(Date(f.lastModified())) + " " +
            DateFormat.getTimeFormat(context).format(Date(f.lastModified()))
        return if (f.isDirectory) {
            val n = f.list()?.size ?: 0
            context.resources.getQuantityString(R.plurals.fm_items, n, n) + " · " + date
        } else {
            Formatter.formatShortFileSize(context, f.length()) + " · " + date +
                (if (virtualTitle != null) " · " + (f.parentFile?.name ?: "") else "")
        }
    }

    private fun typeOf(f: File): Pair<String, Int> {
        if (f.isDirectory) return "📁" to 0xFFF59E0B.toInt()
        return when (f.extension.lowercase()) {
            in IMAGES -> "🖼" to 0xFF8B5CF6.toInt()
            in VIDEOS -> "🎬" to 0xFFEF4444.toInt()
            in AUDIO -> "🎵" to 0xFF10B981.toInt()
            "pdf" -> "📕" to 0xFFDC2626.toInt()
            "doc", "docx", "odt", "rtf", "txt", "md" -> "📄" to 0xFF2563EB.toInt()
            "xls", "xlsx", "ods", "csv" -> "📊" to 0xFF16A34A.toInt()
            "ppt", "pptx", "odp" -> "📽" to 0xFFEA580C.toInt()
            "zip", "rar", "7z", "tar", "gz", "tgz", "cbz", "jar", "bz2", "xz" -> "🗜" to 0xFF64748B.toInt()
            "apk" -> "🤖" to 0xFF22C55E.toInt()
            else -> "📎" to 0xFF64748B.toInt()
        }
    }

    // ---------------- выбор и действия ----------------

    private fun toggleSelect(f: File) {
        if (!selected.remove(f)) selected += f
        refreshSelection()
    }

    private fun refreshSelection() {
        adapter.notifyDataSetChanged()
        actionBar.removeAllViews()
        if (selected.isEmpty()) {
            actionBar.visibility = View.GONE
        } else {
            actionBar.visibility = View.VISIBLE
            actionBar.addView(TextView(context).apply {
                text = selected.size.toString()
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(cText)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(44)))
            val acts = listOf(
                "📋" to R.string.fm_copy, "✂️" to R.string.fm_move, "📤" to R.string.fm_share,
                "🗜" to R.string.fm_zip, "🗑" to R.string.fm_delete, "⋯" to R.string.fm_more,
            )
            for ((e, label) in acts) {
                actionBar.addView(
                    Btn.tile(context, e, context.getString(label)) { selectionAction(label) },
                    LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(4) },
                )
            }
            if (actionBar.tag == null) {
                actionBar.tag = true
                actionBar.translationY = dp(80).toFloat()
                actionBar.animate().translationY(0f).setDuration(240).start()
            }
        }
        if (selected.isEmpty()) actionBar.tag = null
        // Панель вставки.
        pasteBar.removeAllViews()
        if (clipboard.isNotEmpty() && dir != null) {
            pasteBar.visibility = View.VISIBLE
            pasteBar.addView(TextView(context).apply {
                text = context.getString(if (clipboardMove) R.string.fm_paste_move else R.string.fm_paste_copy, clipboard.size)
                textSize = 13f
                setTextColor(cText)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            pasteBar.addView(Btn.tonal(context, context.getString(android.R.string.cancel)) {
                clipboard = emptyList()
                refreshSelection()
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
            pasteBar.addView(Btn.primary(context, context.getString(R.string.fm_paste)) { paste() }, LinearLayout.LayoutParams(-2, dp(42)))
        } else {
            pasteBar.visibility = View.GONE
        }
    }

    private fun selectionAction(label: Int) {
        val files = selected.toList()
        when (label) {
            R.string.fm_copy, R.string.fm_move -> {
                clipboard = files
                clipboardMove = label == R.string.fm_move
                selected.clear()
                refreshSelection()
                Toast.makeText(context, R.string.fm_choose_target, Toast.LENGTH_SHORT).show()
            }
            R.string.fm_share -> share(files)
            R.string.fm_zip -> archive(files)
            R.string.fm_delete -> delete(files)
            R.string.fm_more -> more(files)
        }
    }

    private fun more(files: List<File>) {
        val opts = ArrayList<Pair<Int, () -> Unit>>()
        if (files.size == 1) {
            val f = files[0]
            opts += R.string.fm_rename to { rename(f) }
            opts += R.string.fm_properties to { properties(f) }
            if (f.isFile) opts += R.string.fm_open_with to { openWith(f) }
            if (f.isFile && Archive.isArchiveName(f.name)) {
                opts += R.string.arc_open to { ArchiverUi.browse(context, f) { afterArchive() } }
                opts += R.string.arc_extract_here to { ArchiverUi.extractFile(context, f, false) { afterArchive() } }
                opts += R.string.arc_extract_to_folder to { ArchiverUi.extractFile(context, f, true) { afterArchive() } }
                opts += R.string.arc_test to { ArchiverUi.testFile(context, f) }
                opts += R.string.arc_info to { ArchiverUi.infoFile(context, f) }
            }
            opts += R.string.fm_copy_path to { copyPath(f) }
            if (f.isDirectory) {
                opts += (if (f.absolutePath in favorites) R.string.fm_fav_remove else R.string.fm_fav_add) to {
                    toggleFavorite(f)
                    selected.clear()
                    refreshSelection()
                }
            }
            if (virtualTitle != null && f.parentFile != null) opts += R.string.fm_show_in_folder to { openDir(f.parentFile!!) }
        }
        opts += R.string.arc_add_to_archive to { archive(files) }
        opts += R.string.fm_select_all to {
            selected.addAll(shown())
            refreshSelection()
        }
        // «Извлечь в папку» - с именем папки в подписи, как в WinRAR.
        val labels: List<CharSequence> = opts.map { (res, _) ->
            if (res == R.string.arc_extract_to_folder) {
                context.getString(R.string.arc_extract_folder, ArchiverUi.folderName(files[0]))
            } else {
                context.getString(res)
            }
        }
        AlertDialog.Builder(context)
            .setItems(labels.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    private fun menu(anchor: View) {
        val p = PopupMenu(context, anchor)
        if (dir != null) p.menu.add(0, 1, 0, R.string.fm_new_folder)
        p.menu.add(0, 2, 1, R.string.fm_sort)
        p.menu.add(0, 3, 2, if (showHidden) R.string.fm_hide_hidden else R.string.fm_show_hidden)
        p.menu.add(0, 4, 3, R.string.fm_storages)
        if (!hasFullAccess()) p.menu.add(0, 5, 4, R.string.fm_access_button)
        dir?.let { cur ->
            p.menu.add(0, 6, 5, if (cur.absolutePath in favorites) R.string.fm_fav_remove else R.string.fm_fav_add)
            p.menu.add(0, 7, 6, R.string.fm_copy_path)
        }
        p.menu.add(0, 8, 7, R.string.fm_tool_analyze)
        p.menu.add(0, 9, 8, R.string.fm_tool_clean)
        p.menu.add(0, 10, 9, R.string.arc_title)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newFolder()
                2 -> chooseSort()
                3 -> {
                    showHidden = !showHidden
                    reload()
                }
                4 -> showRoots()
                5 -> askAccess()
                6 -> dir?.let { toggleFavorite(it) }
                7 -> dir?.let { copyPath(it) }
                8 -> analyze()
                9 -> cleanup()
                10 -> archiverHome()
            }
            true
        }
        p.show()
    }

    private fun chooseSort() {
        val names = arrayOf<CharSequence>(
            context.getString(R.string.fm_sort_name), context.getString(R.string.fm_sort_date),
            context.getString(R.string.fm_sort_size), context.getString(R.string.fm_sort_type),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.fm_sort)
            .setSingleChoiceItems(names, sort) { dlg, w ->
                if (sort == w) desc = !desc else {
                    sort = w
                    desc = w == 1 || w == 2
                }
                dlg.dismiss()
                reload()
            }
            .show()
    }

    private fun ask(title: Int, initial: String, done: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(initial)
            setSingleLine()
            setSelection(initial.substringBeforeLast('.').length.coerceAtMost(initial.length))
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(FrameLayout(context).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(field) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = field.text.toString().trim()
                if (v.isNotEmpty() && '/' !in v) done(v)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        val base = dir ?: return
        ask(R.string.fm_new_folder, "") { name ->
            if (!File(base, name).mkdirs()) Toast.makeText(context, R.string.fm_failed, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun rename(f: File) {
        ask(R.string.fm_rename, f.name) { name ->
            val target = File(f.parentFile, name)
            if (target.exists() || !f.renameTo(target)) Toast.makeText(context, R.string.fm_failed, Toast.LENGTH_SHORT).show()
            selected.clear()
            reload()
        }
    }

    private fun delete(files: List<File>) {
        AlertDialog.Builder(context)
            .setTitle(R.string.fm_delete)
            .setMessage(context.getString(R.string.fm_delete_confirm, files.size))
            .setPositiveButton(R.string.fm_delete) { _, _ ->
                background(R.string.fm_deleting, { files.forEach { it.deleteRecursively() } }) {
                    selected.clear()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uniqueTarget(dirTo: File, name: String): File {
        var t = File(dirTo, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (t.exists()) t = File(dirTo, "$base ($i)$ext").also { i++ }
        return t
    }

    private fun paste() {
        val to = dir ?: return
        val files = clipboard
        val move = clipboardMove
        clipboard = emptyList()
        background(if (move) R.string.fm_moving else R.string.fm_copying, {
            for (f in files) {
                if (to.absolutePath.startsWith(f.absolutePath + "/") || to == f) continue
                val target = uniqueTarget(to, f.name)
                if (move && f.renameTo(target)) continue
                f.copyRecursively(target, overwrite = false)
                if (move) f.deleteRecursively()
            }
        }) { reload() }
    }

    // ---------------- архиватор ----------------

    /** «В ZIP» / «Добавить в архив»: окно параметров архиватора (имя, сжатие, пароль). */
    private fun archive(files: List<File>) {
        val to = dir ?: files.firstOrNull()?.parentFile ?: return
        ArchiverUi.create(context, files, to) {
            selected.clear()
            reload()
        }
    }

    /** После действий архиватора: обновить список (появились или изменились файлы). */
    private fun afterArchive() {
        if (!dialog.isShowing) return
        selected.clear()
        if (dir != null || virtualTitle != null) reload() else refreshSelection()
    }

    /** Окно «Архиватор»: поиск архивов, создание архива, справка. */
    private fun archiverHome() {
        ArchiverUi.home(context, dir, onFind = { findArchives() }) { afterArchive() }
    }

    /** Все архивы в памяти телефона (до 2000, свежие сверху) - списком, как «Недавние». */
    private fun findArchives() {
        Toast.makeText(context, R.string.fm_scanning, Toast.LENGTH_SHORT).show()
        Thread {
            val out = ArrayList<File>()
            runCatching {
                @Suppress("DEPRECATION")
                val rootDirs = buildList {
                    add(Environment.getExternalStorageDirectory())
                    context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { add(File(it.absolutePath.substringBefore("/Android/"))) }
                    add(context.filesDir)
                }
                val stack = ArrayDeque<File>()
                rootDirs.forEach { stack.add(it) }
                var visited = 0
                while (stack.isNotEmpty() && out.size < 2000 && visited < 60_000) {
                    val cur = stack.removeLast()
                    visited++
                    val children = cur.listFiles() ?: continue
                    for (c in children) {
                        if (c.name.startsWith(".")) continue
                        if (c.isDirectory) {
                            if ((c.name == "data" || c.name == "obb") && cur.name == "Android") continue
                            stack.add(c)
                        } else if (Archive.isArchiveName(c.name) && !Archive.isContainerName(c.name)) {
                            out += c
                        }
                    }
                }
            }
            val sorted = out.sortedByDescending { it.lastModified() }
            main.post {
                if (!dialog.isShowing) return@post
                showVirtual(context.getString(R.string.arc_found_title, sorted.size), sorted)
            }
        }.start()
    }

    private fun properties(f: File) {
        val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
        val msg = buildString {
            append(context.getString(R.string.fm_prop_path, f.absolutePath)).append("\n")
            append(context.getString(R.string.fm_prop_size, Formatter.formatFileSize(context, size))).append("\n")
            append(context.getString(R.string.fm_prop_modified, DateFormat.format("dd.MM.yyyy HH:mm", f.lastModified()))).append("\n")
            if (f.isFile) append(context.getString(R.string.fm_prop_type, mime(f)))
        }
        AlertDialog.Builder(context).setTitle(f.name).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    /** Долгая операция в фоне с плашкой прогресса снизу. */
    private fun background(title: Int, work: () -> Unit, done: () -> Unit) {
        val bar = com.stoyko.smessenger.ui.UploadBar.show(context, context.getString(title))
        bar?.set(0.15f)
        Thread {
            val r = runCatching { work() }
            main.post {
                if (r.isSuccess) {
                    if (bar != null) bar.done(context.getString(R.string.fm_done)) else Toast.makeText(context, R.string.fm_done, Toast.LENGTH_SHORT).show()
                } else {
                    val m = context.getString(R.string.fm_failed) + ": " + (r.exceptionOrNull()?.message ?: "")
                    if (bar != null) bar.fail(m) else Toast.makeText(context, m, Toast.LENGTH_LONG).show()
                }
                done()
            }
        }.start()
    }

    // ---------------- открыть / поделиться ----------------

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun uriOf(f: File): Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", f)

    private fun open(f: File, view: View) {
        if (f.isDirectory) {
            openDir(f)
            return
        }
        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
        val ext = f.extension.lowercase()
        // Фото и видео - во встроенном просмотрщике (листается свайпом по всей папке).
        if (ext in IMAGES || ext in VIDEOS) {
            val media = shown().filter { it.isFile && it.extension.lowercase().let { e -> e in IMAGES || e in VIDEOS } }
            val idx = media.indexOf(f).coerceAtLeast(0)
            runCatching {
                com.stoyko.smessenger.ui.chat.MediaViewer.showList(context, media.map { Uri.fromFile(it) to it.name }, idx)
                return
            }
        }
        // Архивы открываются во встроенном архиваторе «как папка»; APK, JAR, книги и документы -
        // на выбор: архиватор или обычное приложение (установка, чтение).
        if (Archive.isArchiveName(f.name)) {
            if (Archive.isContainerName(f.name)) {
                AlertDialog.Builder(context)
                    .setTitle(f.name)
                    .setItems(arrayOf<CharSequence>(context.getString(R.string.fm_open_with), context.getString(R.string.arc_open))) { _, w ->
                        if (w == 0) openWith(f) else ArchiverUi.browse(context, f) { afterArchive() }
                    }
                    .show()
            } else {
                ArchiverUi.browse(context, f) { afterArchive() }
            }
            return
        }
        openWith(f)
    }

    private fun openWith(f: File) {
        runCatching {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(i, f.name))
        }.onFailure { Toast.makeText(context, R.string.fm_no_app, Toast.LENGTH_LONG).show() }
    }

    private fun share(files: List<File>) {
        val uris = ArrayList(files.filter { it.isFile }.mapNotNull { runCatching { uriOf(it) }.getOrNull() })
        if (uris.isEmpty()) {
            Toast.makeText(context, R.string.fm_share_files_only, Toast.LENGTH_SHORT).show()
            return
        }
        // Можно выбрать и S-messenger - файлы уйдут в чат.
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mime(files.first { it.isFile })).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(i, context.getString(R.string.fm_share))) }
        selected.clear()
        refreshSelection()
    }

    companion object {
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEOS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
        private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        /** Типы файлов для анализа памяти: эмодзи, подпись, цвет полоски. */
        private val CATS = listOf(
            Triple("🖼", R.string.fm_cat_images, 0xFF8B5CF6.toInt()),
            Triple("🎬", R.string.fm_cat_videos, 0xFFEF4444.toInt()),
            Triple("🎵", R.string.fm_cat_audio, 0xFF10B981.toInt()),
            Triple("📄", R.string.fm_cat_docs, 0xFF2563EB.toInt()),
            Triple("🗜", R.string.fm_cat_archives, 0xFF64748B.toInt()),
            Triple("🤖", R.string.fm_cat_apk, 0xFF22C55E.toInt()),
            Triple("📎", R.string.fm_cat_other, 0xFF94A3B8.toInt()),
        )

        fun show(context: Context) {
            val fm = FileManager(context)
            fm.build()
            fm.dialog.show()
            fm.showRoots()
        }
    }
}
