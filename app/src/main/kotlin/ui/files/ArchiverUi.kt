// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.files

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.stoyko.smessenger.ui.safeDismiss
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Окна архиватора файлового менеджера (логика - в [Archive]):
 *  - создание ZIP: имя, степень сжатия, пароль, «удалить исходные»; добавление в существующий архив;
 *  - просмотр архива как папки ([ArchiveBrowser]): размеры, сжатие, даты, вложенные папки;
 *  - «Извлечь сюда» / «Извлечь в папку…» / «Извлечь в…», выбранное или всё, вопрос о замене;
 *  - добавление, удаление, переименование внутри ZIP; проверка целостности; информация;
 *  - окно прогресса с процентами и отменой; всё тяжёлое - в фоновом потоке.
 */
@SuppressLint("SetTextI18n")
object ArchiverUi {
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun fmt(ctx: Context, n: Long) = Formatter.formatShortFileSize(ctx, n.coerceAtLeast(0))

    // ---------------- прогресс ----------------

    /**
     * Окно прогресса: имя текущего файла, полоска, проценты, «Отмена».
     * Показывается с задержкой - быстрые операции проходят без мелькания.
     */
    internal class Task(ctx: Context, title: String) : ArcProgress {
        @Volatile
        override var cancelled = false
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        private val nameView = TextView(ctx).apply {
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
        }
        private val pct = TextView(ctx).apply {
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
        }
        private val fmtCtx = ctx
        private val dialog: AlertDialog
        private val showLater = Runnable { runCatching { dialog.show() } }

        @Volatile
        private var last = 0L

        init {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ArchiverUi.dp(ctx, 22), ArchiverUi.dp(ctx, 8), ArchiverUi.dp(ctx, 22), 0)
                addView(nameView)
                addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArchiverUi.dp(ctx, 10) })
                addView(pct, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ArchiverUi.dp(ctx, 6) })
            }
            dialog = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
                .create()
            mainHandler.postDelayed(showLater, 350)
        }

        override fun update(done: Long, total: Long, name: String) {
            val now = SystemClock.uptimeMillis()
            if (now - last < 100) return
            last = now
            mainHandler.post {
                nameView.text = name
                if (total > 0) {
                    bar.isIndeterminate = false
                    bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                    pct.text = "${(done * 100 / total).coerceIn(0, 100)}% · ${ArchiverUi.fmt(fmtCtx, done)} / ${ArchiverUi.fmt(fmtCtx, total)}"
                } else {
                    pct.text = ArchiverUi.fmt(fmtCtx, done)
                }
            }
        }

        fun finish() {
            mainHandler.removeCallbacks(showLater)
            dialog.safeDismiss()
        }
    }

    /** Выполнить [work] в фоне с окном прогресса; результат - в главном потоке. */
    internal fun <T> runTask(ctx: Context, title: Int, work: (Task) -> T, failed: ((Throwable) -> Unit)? = null, done: (T) -> Unit) {
        val task = Task(ctx, ctx.getString(title))
        Thread {
            val r = try {
                Result.success(work(task))
            } catch (t: Throwable) {
                Result.failure<T>(t)
            }
            mainHandler.post {
                task.finish()
                r.onSuccess { done(it) }
                r.onFailure { t -> if (failed != null) failed(t) else showError(ctx, t) }
            }
        }.start()
    }

    // ---------------- ошибки, пароль, замена ----------------

    fun errorText(ctx: Context, t: Throwable): String = when (t) {
        is WrongPasswordException -> ctx.getString(R.string.arc_err_password)
        is CrcException -> ctx.getString(R.string.arc_err_crc, t.name)
        is UnsupportedArcException -> when (t.kind) {
            "RAR" -> ctx.getString(R.string.arc_err_rar)
            "7z" -> ctx.getString(R.string.arc_err_7z)
            else -> ctx.getString(R.string.arc_err_unsupported, t.kind)
        }
        is ZipBombException -> ctx.getString(R.string.arc_err_bomb)
        is NoSpaceException -> ctx.getString(R.string.arc_err_space, fmt(ctx, t.need), fmt(ctx, t.free))
        is ArcCancelledException -> ctx.getString(R.string.arc_cancelled)
        is BadArchiveException, is java.io.EOFException -> ctx.getString(R.string.arc_err_bad)
        is OutOfMemoryError -> ctx.getString(R.string.arc_err_bad)
        else -> ctx.getString(R.string.fm_failed) + ": " + (t.message ?: t.javaClass.simpleName)
    }

    fun showError(ctx: Context, t: Throwable) {
        if (t is ArcCancelledException) {
            Toast.makeText(ctx, R.string.arc_cancelled, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.arc_error_title)
                .setMessage(errorText(ctx, t))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Ошибка открытия: для RAR/7z - понятное объяснение и «Открыть с помощью». */
    private fun openFailed(ctx: Context, file: File, t: Throwable) {
        if (t is ArcCancelledException) return
        if (t is UnsupportedArcException || t is BadArchiveException) {
            AlertDialog.Builder(ctx)
                .setTitle(file.name)
                .setMessage(errorText(ctx, t))
                .setPositiveButton(R.string.fm_open_with) { _, _ -> openWith(ctx, file) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            showError(ctx, t)
        }
    }

    private fun passwordField(ctx: Context, initial: String?): Pair<EditText, CheckBox> {
        val field = EditText(ctx).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.arc_password_hint)
            if (initial != null) setText(initial)
        }
        val show = CheckBox(ctx).apply {
            setText(R.string.arc_password_show)
            setOnCheckedChangeListener { _, on ->
                field.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                field.setSelection(field.text.length)
            }
        }
        return field to show
    }

    /** Спросить пароль к архиву. */
    fun askPassword(ctx: Context, name: String, wrong: Boolean, done: (String) -> Unit) {
        val (field, show) = passwordField(ctx, null)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 6), dp(ctx, 20), 0)
            addView(field)
            addView(show)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.arc_password_title)
            .setMessage(if (wrong) ctx.getString(R.string.arc_err_password) else ctx.getString(R.string.arc_password_for, name))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pw = field.text.toString()
                if (pw.isNotEmpty()) done(pw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        field.requestFocus()
    }

    /** Спросить из фонового потока «файл уже есть - что делать?» и дождаться ответа. */
    private fun asker(ctx: Context, task: Task): (File) -> Overwrite = { f ->
        val latch = CountDownLatch(1)
        val ans = AtomicReference(Overwrite.CANCEL)
        mainHandler.post {
            val labels = arrayOf<CharSequence>(
                ctx.getString(R.string.arc_replace), ctx.getString(R.string.arc_skip),
                ctx.getString(R.string.arc_replace_all), ctx.getString(R.string.arc_skip_all),
            )
            val values = arrayOf(Overwrite.REPLACE, Overwrite.SKIP, Overwrite.REPLACE_ALL, Overwrite.SKIP_ALL)
            runCatching {
                AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.arc_exists, f.name))
                    .setItems(labels) { _, w -> ans.set(values[w]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { latch.countDown() }
                    .show()
            }.onFailure { latch.countDown() }
        }
        while (!latch.await(300, TimeUnit.MILLISECONDS)) {
            if (task.cancelled) break
        }
        ans.get()
    }

    // ---------------- открыть файл другим приложением ----------------

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    internal fun openWith(ctx: Context, f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, f.name))
        }.onFailure { Toast.makeText(ctx, R.string.fm_no_app, Toast.LENGTH_LONG).show() }
    }

    // ---------------- параметры архива ----------------

    private class Options(
        val view: View,
        val name: EditText?,
        val levels: RadioGroup,
        val levelIds: IntArray,
        val password: EditText,
        val deleteSources: CheckBox?,
    ) {
        fun level(): ArcLevel {
            val i = levelIds.indexOf(levels.checkedRadioButtonId).coerceAtLeast(0)
            return ArcLevel.values()[i]
        }

        fun pw(): String? = password.text.toString().takeIf { it.isNotEmpty() }
    }

    private fun optionsView(ctx: Context, name: String?, password: String?, withDelete: Boolean): Options {
        val prefs = ctx.getSharedPreferences("file_manager", Context.MODE_PRIVATE)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        fun label(res: Int) = TextView(ctx).apply {
            setText(res)
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(cSub)
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 2))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
        }
        var nameField: EditText? = null
        if (name != null) {
            box.addView(label(R.string.arc_name))
            nameField = EditText(ctx).apply {
                setSingleLine()
                setText(name)
                setSelection(name.substringBeforeLast('.').length.coerceAtMost(name.length))
            }
            box.addView(nameField)
        }
        box.addView(label(R.string.arc_level))
        val group = RadioGroup(ctx)
        val names = intArrayOf(R.string.arc_level_store, R.string.arc_level_fast, R.string.arc_level_normal, R.string.arc_level_max)
        val ids = IntArray(names.size)
        names.forEachIndexed { i, res ->
            val rb = RadioButton(ctx).apply {
                id = View.generateViewId()
                setText(res)
            }
            ids[i] = rb.id
            group.addView(rb)
        }
        group.check(ids[prefs.getInt("arc_level", ArcLevel.NORMAL.ordinal).coerceIn(0, ids.size - 1)])
        group.setOnCheckedChangeListener { _, id ->
            val i = ids.indexOf(id)
            if (i >= 0) prefs.edit().putInt("arc_level", i).apply()
        }
        box.addView(group)
        box.addView(label(R.string.arc_password_optional))
        val (pwField, show) = passwordField(ctx, password)
        box.addView(pwField)
        box.addView(show)
        box.addView(TextView(ctx).apply {
            setText(R.string.arc_password_note)
            textSize = 12f
            setTextColor(cSub)
        })
        var del: CheckBox? = null
        if (withDelete) {
            del = CheckBox(ctx).apply { setText(R.string.arc_delete_sources) }
            box.addView(del, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 8) })
        }
        return Options(ScrollView(ctx).apply { addView(box) }, nameField, group, ids, pwField, del)
    }

    /** Параметры для «Добавить файлы» в открытый архив: вид окна и (сжатие, пароль). */
    internal fun addOptions(ctx: Context, password: String?): Pair<View, () -> Pair<ArcLevel, String?>> {
        val o = optionsView(ctx, null, password, withDelete = false)
        return o.view to { o.level() to o.pw() }
    }

    // ---------------- создание ----------------

    /** «Добавить в архив»: окно параметров, затем создание ZIP в папке [dir] (или добавление в существующий). */
    fun create(ctx: Context, files: List<File>, dir: File, onChanged: () -> Unit) {
        if (files.isEmpty()) return
        val suggested = (if (files.size == 1) files[0].nameWithoutExtension.ifBlank { files[0].name } else dir.name.ifBlank { "archive" }) + ".zip"
        val o = optionsView(ctx, suggested, null, withDelete = true)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.arc_create_title)
            .setView(o.view)
            .setPositiveButton(R.string.arc_create) { _, _ ->
                var name = o.name?.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || '/' in name) {
                    Toast.makeText(ctx, R.string.arc_bad_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!name.contains('.')) name += ".zip"
                val target = File(dir, name)
                val level = o.level()
                val pw = o.pw()
                val deleteAfter = o.deleteSources?.isChecked == true
                val after = {
                    if (deleteAfter) files.forEach { if (it != target) it.deleteRecursively() }
                    Toast.makeText(ctx, ctx.getString(R.string.arc_created, target.name), Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                if (target.exists()) {
                    AlertDialog.Builder(ctx)
                        .setTitle(ctx.getString(R.string.arc_exists, target.name))
                        .setItems(arrayOf<CharSequence>(ctx.getString(R.string.arc_add_into), ctx.getString(R.string.arc_replace))) { _, w ->
                            if (w == 0) {
                                runTask(ctx, R.string.arc_adding, { t ->
                                    val arc = Archive.open(target, t)
                                    Archive.rewrite(arc, { false }, { null }, Archive.expand(files, "", target), level, pw, t)
                                }) { after() }
                            } else {
                                runTask(ctx, R.string.arc_creating, { t -> Archive.create(target, files, level, pw, t) }) { after() }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    runTask(ctx, R.string.arc_creating, { t -> Archive.create(target, files, level, pw, t) }) { after() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- распаковка, проверка, информация ----------------

    /** Имя папки для «Извлечь в папку»: без .zip / .tar.gz. */
    fun folderName(f: File): String {
        val n = f.name
        val lower = n.lowercase()
        return when {
            lower.endsWith(".tar.gz") -> n.dropLast(7)
            else -> n.substringBeforeLast('.', n)
        }.ifBlank { "archive" }
    }

    private fun withArchive(ctx: Context, file: File, then: (Archive) -> Unit) {
        runTask(ctx, R.string.arc_opening, { t -> Archive.open(file, t) }, failed = { e -> openFailed(ctx, file, e) }) { then(it) }
    }

    /**
     * Распаковать [list] в [dest] (с вопросом пароля и замены).
     * [done] получает пароль, который подошёл (чтобы не спрашивать снова).
     */
    internal fun extract(
        ctx: Context,
        arc: Archive,
        list: List<ArcEntry>,
        dest: File,
        strip: String,
        password: String?,
        done: (String?) -> Unit,
    ) {
        val needPw = list.any { it.encrypted && !it.isDir }
        if (needPw && password == null) {
            askPassword(ctx, arc.file.name, false) { extract(ctx, arc, list, dest, strip, it, done) }
            return
        }
        runTask(ctx, R.string.arc_extracting, { t ->
            if (needPw) arc.checkPassword(list, password)
            Archive.extract(arc, list, dest, strip, password, asker(ctx, t), t)
        }, failed = { e ->
            if (e is WrongPasswordException) {
                askPassword(ctx, arc.file.name, true) { extract(ctx, arc, list, dest, strip, it, done) }
            } else {
                showError(ctx, e)
                done(password)
            }
        }) { r ->
            var msg = ctx.getString(R.string.arc_extracted, r.extracted, dest.absolutePath)
            if (r.skipped > 0) msg += "\n" + ctx.getString(R.string.arc_skipped, r.skipped)
            if (r.blocked.isNotEmpty()) {
                msg += "\n\n" + ctx.getString(R.string.arc_blocked, r.blocked.size) + "\n" + r.blocked.take(10).joinToString("\n")
                AlertDialog.Builder(ctx).setTitle(R.string.fm_done).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
            } else {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
            done(password)
        }
    }

    /** «Извлечь сюда» ([toFolder] = false) или «Извлечь в папку <имя>» прямо из файлового менеджера. */
    fun extractFile(ctx: Context, file: File, toFolder: Boolean, onChanged: () -> Unit) {
        val parent = file.parentFile ?: return
        withArchive(ctx, file) { arc ->
            val dest = if (toFolder) File(parent, folderName(file)) else parent
            extract(ctx, arc, arc.entries, dest, "", null) { onChanged() }
        }
    }

    /** «Извлечь в…»: путь назначения вводится вручную (по умолчанию - папка архива). */
    internal fun askDestination(ctx: Context, initial: File, done: (File) -> Unit) {
        val field = EditText(ctx).apply {
            setSingleLine()
            setText(initial.absolutePath)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.arc_extract_to)
            .setView(FrameLayout(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 6), dp(ctx, 20), 0); addView(field) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = field.text.toString().trim()
                if (p.startsWith("/")) done(File(p)) else Toast.makeText(ctx, R.string.arc_bad_path, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun testFile(ctx: Context, file: File) = withArchive(ctx, file) { test(ctx, it, null) {} }

    /** Проверить архив целиком и показать отчёт. */
    internal fun test(ctx: Context, arc: Archive, password: String?, done: (String?) -> Unit) {
        if (arc.encrypted && password == null) {
            askPassword(ctx, arc.file.name, false) { test(ctx, arc, it, done) }
            return
        }
        runTask(ctx, R.string.arc_testing, { t ->
            if (password != null) arc.checkPassword(arc.entries, password)
            Archive.test(arc, password, t)
        }, failed = { e ->
            if (e is WrongPasswordException) askPassword(ctx, arc.file.name, true) { test(ctx, arc, it, done) } else showError(ctx, e)
        }) { r ->
            val msg = if (r.errors.isEmpty()) {
                ctx.getString(R.string.arc_test_ok, r.tested)
            } else {
                ctx.getString(R.string.arc_test_errors, r.errors.size, r.tested) + "\n\n" +
                    r.errors.take(30).joinToString("\n\n") { (path, t) -> "• $path\n" + errorText(ctx, t) } +
                    (if (r.errors.size > 30) "\n…" else "")
            }
            AlertDialog.Builder(ctx)
                .setTitle(if (r.errors.isEmpty()) R.string.arc_test_ok_title else R.string.arc_test_bad_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            done(password)
        }
    }

    fun infoFile(ctx: Context, file: File) = withArchive(ctx, file) { info(ctx, it) }

    /** Сведения об архиве: формат, число файлов, размеры, степень сжатия, шифрование. */
    internal fun info(ctx: Context, arc: Archive) {
        val total = arc.totalSize
        val packed = arc.packedSize
        val ratio = if (total > 0) (packed * 100 / total).toInt() else 100
        val fmtName = when (arc.format) {
            ArcFormat.ZIP -> "ZIP"
            ArcFormat.TAR -> "TAR"
            ArcFormat.TAR_GZ -> "TAR.GZ"
            ArcFormat.GZ -> "GZIP"
        }
        val methods = arc.entries.filter { !it.isDir }.map { it.methodName }.distinct().joinToString(", ")
        val msg = buildString {
            append(ctx.getString(R.string.arc_info_format, fmtName + if (methods.isNotEmpty() && arc.format == ArcFormat.ZIP) " ($methods)" else "")).append('\n')
            append(ctx.getString(R.string.arc_info_files, arc.fileCount, arc.entries.count { it.isDir })).append('\n')
            append(ctx.getString(R.string.arc_info_size, Formatter.formatFileSize(ctx, total))).append('\n')
            append(ctx.getString(R.string.arc_info_packed, Formatter.formatFileSize(ctx, packed))).append('\n')
            append(ctx.getString(R.string.arc_info_ratio, ratio)).append('\n')
            append(ctx.getString(R.string.arc_info_file_size, Formatter.formatFileSize(ctx, arc.file.length()))).append('\n')
            append(ctx.getString(if (arc.encrypted) R.string.arc_info_encrypted_yes else R.string.arc_info_encrypted_no)).append('\n')
            append(ctx.getString(R.string.arc_info_modified, DateFormat.format("dd.MM.yyyy HH:mm", arc.file.lastModified()))).append('\n')
            append(ctx.getString(R.string.fm_prop_path, arc.file.absolutePath))
            if (!arc.writable) append("\n\n").append(ctx.getString(R.string.arc_read_only))
            val c = arc.comment
            if (c.isNotBlank()) append("\n\n").append(ctx.getString(R.string.arc_info_comment, c.take(2000)))
        }
        AlertDialog.Builder(ctx).setTitle(arc.file.name).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    /** Открыть архив «как папку». */
    fun browse(ctx: Context, file: File, onChanged: () -> Unit) = ArchiveBrowser(ctx, file, onChanged).show()

    /** Главное окно «Архиватор» из инструментов файлового менеджера. */
    fun home(ctx: Context, startDir: File?, onFind: () -> Unit, onChanged: () -> Unit) {
        @Suppress("DEPRECATION")
        val start = startDir ?: Environment.getExternalStorageDirectory()
        val items = arrayOf<CharSequence>(
            ctx.getString(R.string.arc_home_find),
            ctx.getString(R.string.arc_home_create),
            ctx.getString(R.string.arc_home_about),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.arc_title)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> onFind()
                    1 -> pickFiles(ctx, start) { files -> create(ctx, files, files[0].parentFile ?: start, onChanged) }
                    else -> AlertDialog.Builder(ctx)
                        .setTitle(R.string.arc_title)
                        .setMessage(R.string.arc_about)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- выбор файлов ----------------

    /**
     * Простой выбор файлов и папок: нажатие на папку - открыть, на файл - отметить,
     * удержание папки - отметить её целиком.
     */
    fun pickFiles(ctx: Context, start: File, done: (List<File>) -> Unit) {
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        var cur = start
        val chosen = LinkedHashSet<File>()
        var rows: List<File?> = emptyList() // null - «наверх»
        val path = TextView(ctx).apply {
            textSize = 12f
            setTextColor(cSub)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
        }
        val listView = ListView(ctx)
        var dialogRef: AlertDialog? = null
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any? = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                    textSize = 15f
                    setTextColor(cText)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
                }
                val f = rows[position]
                tv.text = if (f == null) {
                    "⬆  .."
                } else {
                    (if (f in chosen) "☑ " else "☐ ") + (if (f.isDirectory) "📁 " else "📄 ") + f.name
                }
                return tv
            }
        }
        fun refresh() {
            val all = cur.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val parent = cur.parentFile
            rows = (if (parent != null && parent.canRead()) listOf<File?>(null) else emptyList()) + all
            path.text = cur.absolutePath
            adapter.notifyDataSetChanged()
            dialogRef?.getButton(DialogInterface.BUTTON_POSITIVE)?.text = ctx.getString(R.string.arc_pick_add, chosen.size)
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val f = rows.getOrNull(pos)
            when {
                f == null -> cur.parentFile?.let { cur = it }
                f.isDirectory -> cur = f
                !chosen.remove(f) -> chosen += f
            }
            refresh()
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val f = rows.getOrNull(pos) ?: return@setOnItemLongClickListener false
            if (!chosen.remove(f)) chosen += f
            refresh()
            true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                setText(R.string.arc_pick_hint)
                textSize = 12f
                setTextColor(cSub)
                setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0)
            })
            addView(path)
            addView(listView, LinearLayout.LayoutParams(-1, dp(ctx, 360)))
        }
        dialogRef = AlertDialog.Builder(ctx)
            .setTitle(R.string.arc_pick_title)
            .setView(box)
            .setPositiveButton(ctx.getString(R.string.arc_pick_add, 0)) { _, _ ->
                if (chosen.isEmpty()) {
                    Toast.makeText(ctx, R.string.arc_pick_nothing, Toast.LENGTH_SHORT).show()
                } else {
                    done(chosen.toList())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        refresh()
    }
}

/**
 * Архив «как папка»: путь внутри архива, вложенные папки, размеры и сжатие, выбор нескольких
 * записей, извлечение, добавление, удаление, переименование, проверка и сведения.
 */
@SuppressLint("SetTextI18n")
private class ArchiveBrowser(private val ctx: Context, private val file: File, private val onChanged: () -> Unit) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
    private val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
    private val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
    private val cCard = ContextCompat.getColor(ctx, R.color.colorSurface)
    private val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)

    private var arc: Archive? = null
    private var prefix = ""
    private var nodes: List<ArcNode> = emptyList()
    private val selected = LinkedHashSet<String>()

    /** Пароль, который уже подошёл, - чтобы не спрашивать на каждое действие. */
    private var password: String? = null

    private val title = TextView(ctx)
    private val subtitle = TextView(ctx)
    private val crumbs = LinearLayout(ctx)
    private val crumbsScroll = HorizontalScrollView(ctx)
    private val list = ListView(ctx)
    private val empty = TextView(ctx)
    private val bottom = LinearLayout(ctx)

    fun show() {
        build()
        dialog.show()
        load()
    }

    private fun build() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF64748B.toInt(), 0xFF334155.toInt()))
            setPadding(dp(4), dp(6), dp(8), dp(10))
            elevation = 6 * d
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun icon(res: Int, click: (View) -> Unit) = ImageButton(ctx).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { click(it) }
            Kinetics.squish(this, 0.85f)
        }
        bar.addView(icon(R.drawable.ic_back) { goUp() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title.textSize = 18f
        title.setTextColor(Color.WHITE)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        title.text = "🗜 " + file.name
        subtitle.textSize = 12f
        subtitle.setTextColor(0xCCFFFFFF.toInt())
        subtitle.maxLines = 1
        titles.addView(title)
        titles.addView(subtitle)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bar.addView(icon(R.drawable.ic_more_vert) { menu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(bar)
        crumbs.orientation = LinearLayout.HORIZONTAL
        crumbs.setPadding(dp(8), dp(4), dp(8), 0)
        crumbsScroll.isHorizontalScrollBarEnabled = false
        crumbsScroll.addView(crumbs)
        header.addView(crumbsScroll)
        root.addView(header)

        val content = FrameLayout(ctx)
        list.divider = null
        list.isFastScrollEnabled = true
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val n = nodes.getOrNull(position) ?: return@setOnItemClickListener
            when {
                selected.isNotEmpty() -> toggle(n)
                n.isDir -> openPrefix(n.path, forward = true)
                else -> view(n)
            }
        }
        list.setOnItemLongClickListener { _, v, position, _ ->
            val n = nodes.getOrNull(position) ?: return@setOnItemLongClickListener false
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            toggle(n)
            true
        }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.setText(R.string.arc_empty)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.setTextColor(cSub)
        empty.visibility = View.GONE
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8))
        bottom.setBackgroundColor(cCard)
        bottom.elevation = 10 * d
        root.addView(bottom)

        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                goUp()
                true
            } else {
                keyCode == android.view.KeyEvent.KEYCODE_BACK
            }
        }
    }

    // ---------------- загрузка и навигация ----------------

    private fun load() {
        ArchiverUi.runTask(ctx, R.string.arc_opening, { t -> Archive.open(file, t) }, failed = { e ->
            dialog.safeDismiss()
            if (e is UnsupportedArcException || e is BadArchiveException) {
                AlertDialog.Builder(ctx)
                    .setTitle(file.name)
                    .setMessage(ArchiverUi.errorText(ctx, e))
                    .setPositiveButton(R.string.fm_open_with) { _, _ -> ArchiverUi.openWith(ctx, file) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                ArchiverUi.showError(ctx, e)
            }
        }) { a ->
            arc = a
            val total = a.totalSize
            val ratio = if (total > 0) (a.packedSize * 100 / total).toInt() else 100
            subtitle.text = ctx.getString(R.string.arc_subtitle, a.fileCount, Formatter.formatShortFileSize(ctx, total), ratio) +
                (if (a.encrypted) " · 🔒" else "")
            if (prefix.isNotEmpty() && !a.exists(prefix)) prefix = ""
            openPrefix(prefix, forward = true)
        }
    }

    private fun openPrefix(p: String, forward: Boolean) {
        val a = arc ?: return
        prefix = p
        selected.clear()
        nodes = a.children(p)
        adapter.notifyDataSetChanged()
        empty.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
        buildCrumbs()
        refreshBar()
        if (Kinetics.enabled) {
            list.translationX = (if (forward) 40 else -40) * d
            list.alpha = 0f
            list.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        val parts = if (prefix.isEmpty()) emptyList() else prefix.split('/')
        fun chip(text: String, target: String, current: Boolean) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(if (current) 0x40FFFFFF else 0x1AFFFFFF)
            }
            setOnClickListener { openPrefix(target, forward = false) }
        }
        crumbs.addView(chip("🗜 " + file.name, "", parts.isEmpty()))
        parts.forEachIndexed { i, part ->
            crumbs.addView(TextView(ctx).apply { text = " › "; setTextColor(0xAAFFFFFF.toInt()); textSize = 13f })
            crumbs.addView(chip(part, parts.take(i + 1).joinToString("/"), i == parts.size - 1))
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun goUp() {
        when {
            selected.isNotEmpty() -> {
                selected.clear()
                adapter.notifyDataSetChanged()
                refreshBar()
            }
            prefix.isNotEmpty() -> openPrefix(prefix.substringBeforeLast('/', ""), forward = false)
            else -> dialog.safeDismiss()
        }
    }

    // ---------------- список ----------------

    private class Holder(val icon: TextView, val name: TextView, val info: TextView, val check: TextView)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = nodes.size
        override fun getItem(position: Int) = nodes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val n = nodes[position]
            val row = (convertView as? LinearLayout)?.takeIf { it.tag is Holder } ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                val icon = TextView(ctx).apply { gravity = Gravity.CENTER; textSize = 20f }
                addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                val name = TextView(ctx).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTextColor(cText) }
                val info = TextView(ctx).apply { textSize = 12f; maxLines = 1; setTextColor(cSub) }
                texts.addView(name)
                texts.addView(info)
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                val check = TextView(ctx).apply { textSize = 18f; gravity = Gravity.CENTER }
                addView(check, LinearLayout.LayoutParams(dp(30), dp(30)))
                tag = Holder(icon, name, info, check)
            }
            val h = row.tag as Holder
            val (emoji, color) = if (n.isDir) "📁" to 0xFFF59E0B.toInt() else iconOf(n.name)
            h.icon.text = emoji
            h.icon.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor((color and 0x00FFFFFF) or 0x26000000)
            }
            h.name.text = (if (n.encrypted) "🔒 " else "") + n.name
            h.info.text = describe(n)
            val on = n.path in selected
            h.check.text = if (selected.isEmpty()) "" else if (on) "✓" else "○"
            h.check.setTextColor(if (on) Color.WHITE else cSub)
            h.check.background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF5B4BDB.toInt()) } else null
            row.setBackgroundColor(if (on) 0x1A5B4BDB else Color.TRANSPARENT)
            return row
        }
    }

    private fun iconOf(name: String): Pair<String, Int> = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "🖼" to 0xFF8B5CF6.toInt()
        "mp4", "mkv", "webm", "3gp", "mov", "avi" -> "🎬" to 0xFFEF4444.toInt()
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "🎵" to 0xFF10B981.toInt()
        "pdf" -> "📕" to 0xFFDC2626.toInt()
        "doc", "docx", "odt", "rtf", "txt", "md", "xml", "json", "html", "kt", "java" -> "📄" to 0xFF2563EB.toInt()
        "zip", "rar", "7z", "tar", "gz", "tgz", "jar" -> "🗜" to 0xFF64748B.toInt()
        "apk" -> "🤖" to 0xFF22C55E.toInt()
        else -> "📎" to 0xFF64748B.toInt()
    }

    private fun describe(n: ArcNode): String {
        val size = Formatter.formatShortFileSize(ctx, n.size.coerceAtLeast(0))
        val packed = Formatter.formatShortFileSize(ctx, n.csize.coerceAtLeast(0))
        val ratio = if (n.size > 0) (n.csize * 100 / n.size).toInt() else 100
        val date = if (n.time > 0) {
            " · " + DateFormat.getDateFormat(ctx).format(Date(n.time)) + " " + DateFormat.getTimeFormat(ctx).format(Date(n.time))
        } else {
            ""
        }
        return if (n.isDir) {
            ctx.getString(R.string.arc_dir_info, n.files, size) + date
        } else if (n.size < 0) {
            packed + date
        } else {
            ctx.getString(R.string.arc_file_info, size, packed, ratio) + date
        }
    }

    // ---------------- выбор и нижняя панель ----------------

    private fun toggle(n: ArcNode) {
        if (!selected.remove(n.path)) selected += n.path
        adapter.notifyDataSetChanged()
        refreshBar()
    }

    private fun refreshBar() {
        val a = arc
        bottom.removeAllViews()
        if (a == null) return
        val acts = ArrayList<Triple<String, Int, () -> Unit>>()
        if (selected.isEmpty()) {
            acts += Triple("📤", R.string.arc_extract) { extractMenu(all = true) }
            if (a.writable) acts += Triple("➕", R.string.arc_add) { addFiles() }
            acts += Triple("🩺", R.string.arc_test) { ArchiverUi.test(ctx, a, password) { password = it } }
            acts += Triple("ℹ️", R.string.arc_info) { ArchiverUi.info(ctx, a) }
        } else {
            bottom.addView(TextView(ctx).apply {
                text = selected.size.toString()
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(cText)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(44)))
            acts += Triple("📤", R.string.arc_extract) { extractMenu(all = false) }
            val single = selected.singleOrNull()?.let { p -> nodes.firstOrNull { it.path == p } }
            if (single != null && !single.isDir) acts += Triple("👁", R.string.arc_view) { view(single) }
            if (single != null && a.writable) acts += Triple("✏️", R.string.fm_rename) { rename(single) }
            if (a.writable) acts += Triple("🗑", R.string.fm_delete) { delete() }
            acts += Triple("☑", R.string.fm_select_all) {
                selected.addAll(nodes.map { it.path })
                adapter.notifyDataSetChanged()
                refreshBar()
            }
        }
        for ((e, label, action) in acts) {
            bottom.addView(
                Btn.tile(ctx, e, ctx.getString(label)) { action() },
                LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(4) },
            )
        }
    }

    private fun menu(anchor: View) {
        val a = arc ?: return
        val p = PopupMenu(ctx, anchor)
        p.menu.add(0, 1, 0, R.string.arc_extract_all)
        if (a.writable) p.menu.add(0, 2, 1, R.string.arc_add)
        p.menu.add(0, 3, 2, R.string.arc_test)
        p.menu.add(0, 4, 3, R.string.arc_info)
        p.menu.add(0, 5, 4, R.string.fm_select_all)
        p.menu.add(0, 6, 5, R.string.fm_open_with)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> extractMenu(all = true)
                2 -> addFiles()
                3 -> ArchiverUi.test(ctx, a, password) { pw -> password = pw }
                4 -> ArchiverUi.info(ctx, a)
                5 -> {
                    selected.addAll(nodes.map { n -> n.path })
                    adapter.notifyDataSetChanged()
                    refreshBar()
                }
                6 -> ArchiverUi.openWith(ctx, file)
            }
            true
        }
        p.show()
    }

    // ---------------- действия ----------------

    /** Выбранные записи (с содержимым выбранных папок). */
    private fun selectedEntries(a: Archive): List<ArcEntry> {
        val out = LinkedHashSet<ArcEntry>()
        for (p in selected) out.addAll(a.under(p))
        return out.toList()
    }

    private fun extractMenu(all: Boolean) {
        val a = arc ?: return
        val entries = if (all) a.entries else selectedEntries(a)
        if (entries.isEmpty()) return
        val strip = if (all || prefix.isEmpty()) "" else "$prefix/"
        val parent = file.parentFile ?: return
        val folder = ArchiverUi.folderName(file)
        val items = arrayOf<CharSequence>(
            ctx.getString(R.string.arc_extract_here),
            ctx.getString(R.string.arc_extract_folder, folder),
            ctx.getString(R.string.arc_extract_to),
        )
        val go = { dest: File ->
            ArchiverUi.extract(ctx, a, entries, dest, strip, password) { pw ->
                password = pw
                selected.clear()
                adapter.notifyDataSetChanged()
                refreshBar()
                onChanged()
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (all) ctx.getString(R.string.arc_extract_all) else ctx.getString(R.string.arc_extract_selected, selected.size))
            .setItems(items) { _, w ->
                when (w) {
                    0 -> go(parent)
                    1 -> go(File(parent, folder))
                    else -> ArchiverUi.askDestination(ctx, parent) { go(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Открыть файл из архива: распаковать во временную папку и показать. */
    private fun view(n: ArcNode) {
        val a = arc ?: return
        val e = n.entry ?: return
        val base = File(ctx.cacheDir, "arc_view")
        // Старые временные копии (старше суток) убираем.
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        base.listFiles()?.forEach { if (it.lastModified() < dayAgo) it.deleteRecursively() }
        val dest = File(base, System.nanoTime().toString())
        val strip = e.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        ArchiverUi.extract(ctx, a, listOf(e), dest, strip, password) { pw ->
            password = pw
            val f = File(dest, e.name)
            if (!f.isFile) return@extract
            val ext = f.extension.lowercase()
            when {
                Archive.isArchiveName(f.name) && !Archive.isContainerName(f.name) -> ArchiveBrowser(ctx, f) {}.show()
                ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "mp4", "mkv", "webm", "3gp", "mov") ->
                    runCatching { com.stoyko.smessenger.ui.chat.MediaViewer.showList(ctx, listOf(Uri.fromFile(f) to f.name), 0) }
                        .onFailure { ArchiverUi.openWith(ctx, f) }
                else -> ArchiverUi.openWith(ctx, f)
            }
        }
    }

    private fun afterChange() {
        selected.clear()
        load()
        onChanged()
    }

    private fun delete() {
        val a = arc ?: return
        val paths = selected.toList()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.fm_delete)
            .setMessage(ctx.getString(R.string.arc_delete_confirm, paths.size))
            .setPositiveButton(R.string.fm_delete) { _, _ ->
                ArchiverUi.runTask(ctx, R.string.arc_deleting, { t ->
                    Archive.rewrite(a, { e -> paths.any { p -> e.path == p || e.path.startsWith("$p/") } }, { null }, emptyList(), ArcLevel.NORMAL, null, t)
                }) { afterChange() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rename(n: ArcNode) {
        val a = arc ?: return
        val field = EditText(ctx).apply {
            setSingleLine()
            setText(n.name)
            setSelection(n.name.substringBeforeLast('.').length.coerceAtMost(n.name.length))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.fm_rename)
            .setView(FrameLayout(ctx).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(field) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") {
                    Toast.makeText(ctx, R.string.arc_bad_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name == n.name) return@setPositiveButton
                val to = if (prefix.isEmpty()) name else "$prefix/$name"
                if (a.exists(to)) {
                    Toast.makeText(ctx, ctx.getString(R.string.arc_exists, name), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val from = n.path
                ArchiverUi.runTask(ctx, R.string.arc_renaming, { t ->
                    Archive.rewrite(a, { false }, { e ->
                        when {
                            e.path == from -> to
                            e.path.startsWith("$from/") -> to + e.path.substring(from.length)
                            else -> null
                        }
                    }, emptyList(), ArcLevel.NORMAL, null, t)
                }) { afterChange() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Добавить файлы в текущую папку архива. */
    private fun addFiles() {
        val a = arc ?: return
        val start = file.parentFile ?: return
        ArchiverUi.pickFiles(ctx, start) { files ->
            val o = ArchiverUi.addOptions(ctx, password)
            AlertDialog.Builder(ctx)
                .setTitle(R.string.arc_add)
                .setView(o.first)
                .setPositiveButton(R.string.arc_add) { _, _ ->
                    val (level, pw) = o.second()
                    ArchiverUi.runTask(ctx, R.string.arc_adding, { t ->
                        Archive.rewrite(a, { false }, { null }, Archive.expand(files, prefix, file), level, pw, t)
                    }) {
                        if (pw != null) password = pw
                        afterChange()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
