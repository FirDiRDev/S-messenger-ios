// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.squareup.picasso.Picasso
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

private const val TAG = "Stickers"
private const val STICKER_MAX_PX = 512

/** Эмодзи-стикеры: отправляются как одно эмодзи и показываются крупно, без пузыря. */
private val EMOJI_STICKERS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "☺️", "😊", "😇",
    "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛",
    "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏", "😒",
    "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢",
    "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰",
    "😥", "😓", "🫣", "🤗", "🫡", "🤔", "🫢", "🤭", "🤫", "🤥", "😶", "🫠",
    "😐", "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤",
    "😪", "😵", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠",
    "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️", "👽", "👾", "🤖",
    "🎃", "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿", "😾", "👋", "🤚",
    "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
    "👈", "👉", "👆", "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
    "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾",
    "🧠", "👀", "👁", "👅", "👄", "💋", "🫦", "❤️", "🧡", "💛", "💚", "💙",
    "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘",
    "💝", "💟", "❤️‍🔥", "❤️‍🩹", "💯", "💢", "💥", "💫", "💦", "💨", "🕳", "💬",
    "💭", "💤", "✨", "🌟", "⭐", "⚡", "🔥", "🌈", "☀️", "🌤", "⛅", "🌧",
    "⛈", "❄️", "☃️", "🌊", "🌙", "🌍", "🪐", "🐶", "🐱", "🐭", "🐹", "🐰",
    "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉",
    "🙊", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴",
    "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐢", "🐍", "🦖", "🐙", "🦑", "🦀",
    "🐠", "🐬", "🐳", "🦈", "🐊", "🐘", "🦒", "🐈", "🐕", "🌵", "🌲", "🌴",
    "🍀", "🍁", "🌹", "🌷", "🌸", "🌻", "💐", "🍏", "🍎", "🍐", "🍊", "🍋",
    "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑",
    "🥦", "🌶", "🌽", "🥕", "🥔", "🥐", "🍞", "🧀", "🥚", "🍳", "🥓", "🍗",
    "🍖", "🌭", "🍔", "🍟", "🍕", "🥪", "🌮", "🍝", "🍜", "🍣", "🍱", "🥟",
    "🍦", "🍰", "🎂", "🧁", "🍫", "🍬", "🍭", "🍩", "🍪", "☕", "🍵", "🧃",
    "🥤", "🍺", "🍻", "🥂", "🍷", "🍸", "🍹", "🍾", "⚽", "🏀", "🏈", "⚾",
    "🎾", "🏐", "🎱", "🏓", "🥊", "🎮", "🕹", "🎲", "🎯", "🎳", "🎸", "🎹",
    "🥁", "🎤", "🎧", "🎬", "🎨", "🏆", "🥇", "🎉", "🎊", "🎈", "🎁", "🎀",
    "🪅", "🚗", "🚕", "🏎", "🚓", "🚑", "🚒", "🚲", "🛵", "🏍", "✈️", "🚀",
    "🛸", "🚁", "⛵", "🚢", "🏠", "🏰", "🗽", "🗼", "🎡", "🏖", "🏔", "🌋",
    "📱", "💻", "⌨️", "🖥", "🖨", "🖱", "💾", "📷", "📸", "📹", "🎥", "📞",
    "☎️", "📺", "📻", "⏰", "⌛", "💡", "🔦", "🕯", "💸", "💵", "💰", "💳",
    "💎", "🔧", "🔨", "🛠", "🔑", "🔒", "🔓", "🧲", "💊", "💉", "🧬", "📌",
    "📎", "✂️", "📝", "📚", "📖", "🔍", "📢", "🔔", "🎵", "🎶", "✅", "❌",
    "❓", "❗", "⚠️", "🚫", "♻️", "🆗", "🆒", "🆕", "🔝", "💤", "🇷🇺", "🏳️",
    "🏴", "🚩",
)

/** Имя файла стикера: такие файлы показываются в чате без пузыря. */
fun isStickerName(name: String) = (name.startsWith("sticker_") &&
    (name.endsWith(".png") || name.endsWith(".webp"))) || com.stoyko.smessenger.chat.KbGif.isKbGif(name)

/** Свой набор стикеров (импортированный пользователем): папка stickers/packs/<id>/. */
data class UserPack(val id: String, val dir: File, val title: String) {
    fun stickers(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()
}

/** Свои стикеры: «Мои стикеры» (корень папки) и импортированные наборы. */
object StickerStore {
    /** Сколько стикеров максимум берём из одного архива/выбора (защита от огромных архивов). */
    private const val MAX_IMPORT = 200
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private val IMAGE_EXT = listOf(".png", ".webp", ".jpg", ".jpeg", ".gif", ".bmp")

    fun dir(ctx: Context) = File(ctx.filesDir, "stickers").apply { mkdirs() }
    private fun packsDir(ctx: Context) = File(dir(ctx), "packs").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun packs(ctx: Context): List<UserPack> =
        packsDir(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.map { d ->
            val title = runCatching { File(d, "title.txt").readText().trim() }.getOrNull().orEmpty().ifBlank { d.name }
            UserPack(d.name, d, title)
        } ?: emptyList()

    fun createPack(ctx: Context, title: String): UserPack {
        val id = "p${System.currentTimeMillis()}"
        val d = File(packsDir(ctx), id).apply { mkdirs() }
        File(d, "title.txt").writeText(title.trim().take(40))
        return UserPack(id, d, title.trim().take(40))
    }

    fun renamePack(pack: UserPack, title: String) {
        runCatching { File(pack.dir, "title.txt").writeText(title.trim().take(40)) }
    }

    fun deletePack(pack: UserPack) {
        pack.dir.deleteRecursively()
    }

    /** Байты картинки -> PNG до 512 px (с прозрачностью). Анимация (gif/webp) - первый кадр. */
    private fun saveScaled(bytesOrNull: ByteArray?, uri: Uri?, ctx: Context, target: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytesOrNull != null) {
            BitmapFactory.decodeByteArray(bytesOrNull, 0, bytesOrNull.size, bounds)
        } else if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= STICKER_MAX_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap? = if (bytesOrNull != null) {
            BitmapFactory.decodeByteArray(bytesOrNull, 0, bytesOrNull.size, opts)
        } else {
            ctx.contentResolver.openInputStream(uri!!)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        val src = decoded ?: return null
        val k = STICKER_MAX_PX.toFloat() / max(src.width, src.height)
        val bmp = if (k < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
        } else {
            src
        }
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return target
    }

    private var seq = 0
    private fun newFile(dir: File): File = synchronized(this) {
        seq = (seq + 1) % 1000
        File(dir, "stk_${System.currentTimeMillis()}_${"%03d".format(seq)}.png")
    }

    /** Картинка -> стикер в «Мои стикеры» (или в набор). */
    fun add(ctx: Context, uri: Uri, pack: UserPack? = null): File? = try {
        saveScaled(null, uri, ctx, newFile(pack?.dir ?: dir(ctx)))
    } catch (e: Throwable) {
        Log.e(TAG, "add sticker", e)
        null
    }

    /**
     * Импорт: картинки (png, webp, jpg, gif) и/или ZIP-архивы с ними - например, набор,
     * скачанный с сайта стикеров. Возвращает число добавленных стикеров.
     */
    fun importInto(ctx: Context, pack: UserPack, uris: List<Uri>, progress: (Int) -> Unit = {}): Int {
        var added = 0
        for (uri in uris) {
            if (added >= MAX_IMPORT) break
            val name = queryName(ctx, uri).lowercase()
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            try {
                if (name.endsWith(".zip") || mime == "application/zip" || mime == "application/x-zip-compressed") {
                    ctx.contentResolver.openInputStream(uri)?.use { raw ->
                        java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                            while (added < MAX_IMPORT) {
                                val e = zip.nextEntry ?: break
                                val n = e.name.lowercase()
                                if (e.isDirectory || n.contains("__macosx") || n.substringAfterLast('/').startsWith(".")) continue
                                if (IMAGE_EXT.none { n.endsWith(it) }) continue
                                val bytes = readLimited(zip) ?: continue
                                if (saveScaled(bytes, null, ctx, newFile(pack.dir)) != null) {
                                    added++
                                    progress(added)
                                }
                            }
                        }
                    }
                } else if (saveScaled(null, uri, ctx, newFile(pack.dir)) != null) {
                    added++
                    progress(added)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "import $name: $e")
            }
        }
        return added
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun queryName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Название набора по имени файла (pitahaya_pack.zip -> «pitahaya pack»). */
    fun suggestTitle(ctx: Context, uris: List<Uri>): String {
        val n = uris.firstOrNull()?.let { queryName(ctx, it) }.orEmpty()
        return n.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().take(40)
    }

    /** Добавить полученный стикер в свои. */
    fun addFile(ctx: Context, file: File): File? = try {
        val out = newFile(dir(ctx))
        file.copyTo(out, overwrite = true)
        out
    } catch (e: Exception) {
        null
    }

    /** Копия стикера для отправки (имя sticker_* - получатель покажет его как стикер). */
    fun prepareToSend(ctx: Context, file: File): Uri {
        val d = File(ctx.filesDir, "outgoing/stickers").apply { mkdirs() }
        val out = File(d, "sticker_${System.currentTimeMillis()}.png")
        file.copyTo(out, overwrite = true)
        return AttachLauncher.providerUri(ctx, out)
    }
}

/**
 * Панель стикеров (кнопка со смайликом): вкладки «Эмодзи», «Мои стикеры», свои наборы
 * (импорт картинок или ZIP) и встроенные наборы. Создаётся как поле фрагмента.
 */
class StickerLauncher(
    private val fragment: Fragment,
    private val onEmoji: (String) -> Unit,
    private val onSticker: (Uri) -> Unit,
) {
    private var sheet: BottomSheetDialog? = null
    /** Обновить панель; packId - открыть этот набор. */
    private var refresh: ((packId: String?) -> Unit)? = null

    /** Куда импортировать выбранные файлы: null - новый набор (спросим название). */
    private var importTarget: UserPack? = null

    private val pick = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { StickerStore.add(ctx, uri) }
            Toast.makeText(ctx, if (f != null) R.string.sticker_added else R.string.sticker_failed, Toast.LENGTH_SHORT).show()
            refresh?.invoke(null)
        }
    }

    // Несколько картинок и/или ZIP-архивов сразу (из «Загрузок», файлового менеджера).
    private val pickFiles = fragment.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val target = importTarget
        importTarget = null
        if (target != null) runImport(target, uris) else askPackTitle(uris)
    }

    private fun launchImport(target: UserPack?) {
        importTarget = target
        runCatching { pickFiles.launch(arrayOf("image/*", "application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            .onFailure { Toast.makeText(fragment.requireContext(), R.string.sticker_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun askPackTitle(uris: List<Uri>) {
        val ctx = fragment.context ?: return
        val field = android.widget.EditText(ctx).apply {
            setSingleLine()
            setText(StickerStore.suggestTitle(ctx, uris))
            setSelection(text.length)
            setHint(R.string.sticker_pack_title_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val p = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.sticker_pack_new)
            .setView(box)
            .setPositiveButton(R.string.sticker_pack_import) { _, _ ->
                val title = field.text.toString().trim().ifBlank { ctx.getString(R.string.sticker_pack_default_title) }
                runImport(StickerStore.createPack(ctx.applicationContext, title), uris)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runImport(pack: UserPack, uris: List<Uri>) {
        val app = fragment.requireContext().applicationContext
        Toast.makeText(app, R.string.sticker_pack_importing, Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { StickerStore.importInto(app, pack, uris) }
            if (n == 0 && pack.stickers().isEmpty()) StickerStore.deletePack(pack)
            Toast.makeText(
                app,
                if (n > 0) app.getString(R.string.sticker_pack_imported, n, pack.title) else app.getString(R.string.sticker_pack_import_none),
                Toast.LENGTH_LONG,
            ).show()
            refresh?.invoke(if (n > 0) pack.id else null)
        }
    }

    private sealed class Tab {
        object Emoji : Tab()
        object Mine : Tab()
        data class User(val pack: UserPack) : Tab()
        data class BuiltIn(val index: Int) : Tab()
    }

    fun show() {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.sheet_stickers, null, false)
        val tabs = root.findViewById<TabLayout>(R.id.stickerTabs)
        val grid = root.findViewById<GridView>(R.id.stickerGrid)
        val empty = root.findViewById<TextView>(R.id.stickerEmpty)
        val size = (72 * ctx.resources.displayMetrics.density).toInt()
        val density = ctx.resources.displayMetrics.density
        // Выбрали эмодзи: вставляем, запоминаем в «Недавние» и закрываем панель.
        fun pickEmoji(e: String) {
            RecentEmoji.add(ctx, e)
            onEmoji(e)
            dialog.dismiss()
        }
        // Ряд «Недавние» над сеткой эмодзи.
        val recentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        val recentBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(ctx).apply {
                    setText(R.string.emoji_recent)
                    textSize = 13f
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary))
                    setPadding((14 * density).toInt(), (6 * density).toInt(), 0, 0)
                },
            )
            addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(recentRow)
            })
        }
        fun fillRecents() {
            recentRow.removeAllViews()
            val r = RecentEmoji.list(ctx)
            recentBox.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
            r.forEach { e ->
                recentRow.addView(
                    TextView(ctx).apply {
                        text = e
                        textSize = 28f
                        gravity = Gravity.CENTER
                        setOnClickListener { pickEmoji(e) }
                    },
                    LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt()),
                )
            }
        }
        (root as? LinearLayout)?.let { lin ->
            val gridBox = grid.parent as View
            lin.addView(recentBox, lin.indexOfChild(gridBox))
        }
        fillRecents()

        val emojiAdapter = object : BaseAdapter() {
            override fun getCount() = EMOJI_STICKERS.size
            override fun getItem(p: Int) = EMOJI_STICKERS[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(size, size)
                    gravity = Gravity.CENTER
                    textSize = 34f
                }
                tv.text = EMOJI_STICKERS[p]
                return tv
            }
        }

        // Сетка картинок: первая ячейка - действие («+» или «⚙»), дальше стикеры.
        var files: List<File> = emptyList()
        var firstIcon = R.drawable.ic_add
        val filesAdapter = object : BaseAdapter() {
            override fun getCount() = files.size + 1
            override fun getItem(p: Int): Any = if (p == 0) "+" else files[p - 1]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
                val iv = (convertView as? ImageView) ?: ImageView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(size, size)
                    val pad = size / 10
                    setPadding(pad, pad, pad, pad)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (p == 0) {
                    Picasso.get().cancelRequest(iv)
                    iv.setImageResource(firstIcon)
                    iv.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.colorPrimary))
                } else {
                    iv.clearColorFilter()
                    Picasso.get().load(files[p - 1]).resize(size, size).centerInside().into(iv)
                }
                return iv
            }
        }

        // Встроенные наборы (Noto Animated Emoji, CC BY 4.0): картинки грузятся по сети и кэшируются.
        var pack: List<String> = emptyList()
        val packAdapter = object : BaseAdapter() {
            override fun getCount() = pack.size
            override fun getItem(p: Int): Any = pack[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
                val iv = (convertView as? ImageView) ?: ImageView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(size, size)
                    val pad = size / 12
                    setPadding(pad, pad, pad, pad)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                iv.clearColorFilter()
                com.stoyko.smessenger.chat.AnimatedEmoji.loadImage(iv, pack[p], size)
                return iv
            }
        }

        var tabList: List<Tab> = emptyList()
        fun current(): Tab = tabList.getOrNull(tabs.selectedTabPosition) ?: Tab.Emoji

        // Эмодзи по разделам - отдельная панель поверх сетки стикеров.
        val emojiPanel = EmojiPanel(ctx) { e -> pickEmoji(e) }
        (grid.parent as? android.widget.FrameLayout)?.addView(
            emojiPanel,
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        fun showTab(t: Tab) {
            // «Недавние» теперь - первый раздел панели эмодзи.
            recentBox.visibility = View.GONE
            emojiPanel.visibility = if (t is Tab.Emoji) View.VISIBLE else View.GONE
            grid.visibility = if (t is Tab.Emoji) View.INVISIBLE else View.VISIBLE
            when (t) {
                Tab.Emoji -> {
                    grid.adapter = emojiAdapter
                    empty.visibility = View.GONE
                    emojiPanel.appear()
                }
                Tab.Mine -> {
                    files = StickerStore.list(ctx)
                    firstIcon = R.drawable.ic_add
                    grid.adapter = filesAdapter
                    filesAdapter.notifyDataSetChanged()
                    empty.setText(R.string.stickers_empty)
                    empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                }
                is Tab.User -> {
                    files = t.pack.stickers()
                    firstIcon = R.drawable.ic_settings
                    grid.adapter = filesAdapter
                    filesAdapter.notifyDataSetChanged()
                    empty.setText(R.string.sticker_pack_empty)
                    empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                }
                is Tab.BuiltIn -> {
                    pack = com.stoyko.smessenger.chat.StickerPacks.all[t.index].codes
                    grid.adapter = packAdapter
                    packAdapter.notifyDataSetChanged()
                    empty.visibility = View.GONE
                }
            }
        }

        val listener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(current())
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        }

        /** Перестроить вкладки (после импорта/удаления набора), стараясь остаться на нужной. */
        fun buildTabs(select: (Tab) -> Boolean) {
            tabs.removeOnTabSelectedListener(listener)
            tabs.removeAllTabs()
            val list = ArrayList<Tab>()
            list += Tab.Emoji
            list += Tab.Mine
            StickerStore.packs(ctx).forEach { list += Tab.User(it) }
            com.stoyko.smessenger.chat.StickerPacks.all.indices.forEach { list += Tab.BuiltIn(it) }
            tabList = list
            list.forEach { t ->
                val title: CharSequence = when (t) {
                    Tab.Emoji -> ctx.getString(R.string.stickers_emoji)
                    Tab.Mine -> ctx.getString(R.string.stickers_mine)
                    is Tab.User -> "⭐ " + t.pack.title
                    is Tab.BuiltIn -> com.stoyko.smessenger.chat.StickerPacks.all[t.index].title
                }
                tabs.addTab(tabs.newTab().setText(title), false)
            }
            val idx = list.indexOfFirst(select).coerceAtLeast(0)
            tabs.getTabAt(idx)?.select()
            tabs.addOnTabSelectedListener(listener)
            showTab(list[idx])
        }
        tabs.tabGravity = TabLayout.GRAVITY_CENTER
        tabs.tabMode = TabLayout.MODE_SCROLLABLE
        tabs.tabGravity = TabLayout.GRAVITY_START

        refresh = { packId ->
            val cur = current()
            fillRecents()
            buildTabs { t ->
                if (packId != null) {
                    t is Tab.User && t.pack.id == packId
                } else {
                    t == cur || (cur is Tab.User && t is Tab.User && t.pack.id == cur.pack.id)
                }
            }
        }
        buildTabs { it == Tab.Emoji }

        fun packMenu(p: UserPack) {
            val items = arrayOf<CharSequence>(
                ctx.getString(R.string.sticker_pack_add_more),
                ctx.getString(R.string.sticker_pack_rename),
                ctx.getString(R.string.sticker_pack_delete),
            )
            AlertDialog.Builder(ctx)
                .setTitle(p.title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> launchImport(p)
                        1 -> {
                            val field = android.widget.EditText(ctx).apply {
                                setSingleLine()
                                setText(p.title)
                                setSelection(text.length)
                                filters = arrayOf(android.text.InputFilter.LengthFilter(40))
                            }
                            val box = android.widget.FrameLayout(ctx).apply {
                                val pd = (20 * density).toInt()
                                setPadding(pd, pd / 2, pd, 0)
                                addView(field)
                            }
                            AlertDialog.Builder(ctx)
                                .setTitle(R.string.sticker_pack_rename)
                                .setView(box)
                                .setPositiveButton(R.string.save) { _, _ ->
                                    if (field.text.isNotBlank()) StickerStore.renamePack(p, field.text.toString())
                                    refresh?.invoke(p.id)
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                        else -> AlertDialog.Builder(ctx)
                            .setMessage(ctx.getString(R.string.sticker_pack_delete_confirm, p.title))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                StickerStore.deletePack(p)
                                buildTabs { it == Tab.Mine }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                .show()
        }

        grid.setOnItemClickListener { _, v, p, _ ->
            v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(90).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            when (val t = current()) {
                is Tab.BuiltIn -> {
                    // Стикер из встроенного набора: скачиваем и отправляем (у получателя он анимированный).
                    val code = pack.getOrNull(p) ?: return@setOnItemClickListener
                    dialog.dismiss()
                    val app = ctx.applicationContext
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        val uri = withContext(Dispatchers.IO) { com.stoyko.smessenger.chat.AnimatedEmoji.prepareSticker(app, code) }
                        if (uri != null) onSticker(uri) else Toast.makeText(app, R.string.sticker_download_failed, Toast.LENGTH_LONG).show()
                    }
                }
                Tab.Emoji -> pickEmoji(EMOJI_STICKERS[p])
                Tab.Mine -> if (p == 0) {
                    AlertDialog.Builder(ctx)
                        .setItems(
                            arrayOf<CharSequence>(
                                ctx.getString(R.string.sticker_add_from_gallery),
                                ctx.getString(R.string.sticker_pack_import_new),
                            ),
                        ) { _, which ->
                            if (which == 0) {
                                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } else {
                                AlertDialog.Builder(ctx)
                                    .setTitle(R.string.sticker_pack_import_new)
                                    .setMessage(R.string.sticker_pack_import_help)
                                    .setPositiveButton(R.string.sticker_pack_choose_files) { _, _ -> launchImport(null) }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            }
                        }
                        .show()
                } else {
                    dialog.dismiss()
                    onSticker(StickerStore.prepareToSend(ctx, files[p - 1]))
                }
                is Tab.User -> if (p == 0) {
                    packMenu(t.pack)
                } else {
                    dialog.dismiss()
                    onSticker(StickerStore.prepareToSend(ctx, files[p - 1]))
                }
            }
        }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            val t = current()
            if (t is Tab.BuiltIn) {
                // Удержание стикера из набора - крупный живой предпросмотр с его анимацией и «салютом».
                val code = pack.getOrNull(p) ?: return@setOnItemLongClickListener false
                previewSticker(ctx, code)
                return@setOnItemLongClickListener true
            }
            if ((t != Tab.Mine && t !is Tab.User) || p == 0) return@setOnItemLongClickListener false
            AlertDialog.Builder(ctx)
                .setMessage(R.string.sticker_delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    files.getOrNull(p - 1)?.delete()
                    showTab(t)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // Ручка сверху: нажатие или жест вниз - закрыть панель и вернуться к сообщению.
        // (Сама панель пальцем не тянется, чтобы прокрутка эмодзи её не закрывала.)
        root.findViewById<View>(R.id.stickerHandle)?.let { handle ->
            val bar = root.findViewById<View>(R.id.stickerHandleBar)
            var downY = 0f
            var dragging = false
            handle.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        dragging = false
                        bar?.animate()?.scaleX(1.4f)?.alpha(0.7f)?.setDuration(120)?.start()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = (e.rawY - downY).coerceAtLeast(0f)
                        if (dy > 8 * density) dragging = true
                        root.translationY = dy
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        bar?.animate()?.scaleX(1f)?.alpha(0.35f)?.setDuration(160)?.start()
                        val dy = root.translationY
                        if (!dragging || dy > root.height * 0.18f) {
                            root.animate().translationY(root.height.toFloat()).setDuration(180)
                                .withEndAction { runCatching { dialog.dismiss() } }.start()
                        } else {
                            root.animate().translationY(0f).setDuration(260)
                                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                        }
                    }
                }
                true
            }
        }

        root.fitSystemBars(top = false)
        dialog.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Прокрутка эмодзи вверх больше не закрывает панель: сетка прокручивается сама,
        // а панель не тянется пальцем (закрыть - касанием вне её или «Назад»).
        androidx.core.view.ViewCompat.setNestedScrollingEnabled(grid, true)
        dialog.behavior.isDraggable = false
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED }
        dialog.setOnDismissListener {
            sheet = null
            refresh = null
        }
        sheet = dialog
        dialog.show()
    }

    companion object {
        /** Нажали на полученный стикер - предложить сохранить в свои. */
        fun offerSave(ctx: Context, file: File) {
            AlertDialog.Builder(ctx)
                .setMessage(R.string.sticker_save_confirm)
                .setPositiveButton(R.string.save) { _, _ ->
                    val ok = StickerStore.addFile(ctx, file) != null
                    Toast.makeText(ctx, if (ok) R.string.sticker_added else R.string.sticker_failed, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}

/** Недавние эмодзи: сами собираются в отдельный ряд сверху панели (последние 24). */
/** Предпросмотр стикера: крупно, с настоящей анимацией и характерным движением; касание - «салют». */
private fun previewSticker(ctx: Context, code: String) {
    val density = ctx.resources.displayMetrics.density
    val size = (200 * density).toInt()
    val emoji = com.stoyko.smessenger.chat.EmojiMotion.fromCode(code)
    val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    val anim = ImageView(ctx).apply { visibility = View.GONE }
    val box = android.widget.FrameLayout(ctx).apply {
        addView(img, android.widget.FrameLayout.LayoutParams(size, size))
        addView(anim, android.widget.FrameLayout.LayoutParams(size, size))
    }
    val wrap = android.widget.FrameLayout(ctx).apply {
        val pd = (24 * density).toInt()
        setPadding(pd, pd, pd, pd)
        addView(box, android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
    }
    com.stoyko.smessenger.chat.AnimatedEmoji.loadImage(img, code, size)
    com.stoyko.smessenger.chat.AnimatedEmoji.play(anim, code, loop = -1) { img.visibility = View.INVISIBLE }
    val dialog = AlertDialog.Builder(ctx).setView(wrap).create()
    box.setOnClickListener { com.stoyko.smessenger.chat.EmojiMotion.burst(box, emoji) }
    dialog.setOnDismissListener { com.stoyko.smessenger.chat.AnimatedEmoji.stop(anim) }
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    dialog.show()
    com.stoyko.smessenger.chat.EmojiMotion.entrance(box, emoji)
}

object RecentEmoji {
    private const val PREFS = "emoji_recent"

    fun list(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("l", "").orEmpty()
            .split('\u0001').filter { it.isNotEmpty() }

    fun add(ctx: Context, e: String) {
        val l = (listOf(e) + list(ctx).filter { it != e }).take(24)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("l", l.joinToString("\u0001")).apply()
    }
}

/** Вставить эмодзи в поле ввода на место курсора (или заменить выделенное). */
fun insertEmoji(input: android.widget.EditText, emoji: String) {
    val text = input.text ?: return
    val start = input.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
    val end = input.selectionEnd.coerceAtLeast(0).coerceAtMost(text.length)
    text.replace(minOf(start, end), maxOf(start, end), emoji)
    input.setSelection((minOf(start, end) + emoji.length).coerceAtMost(input.text.length))
}
