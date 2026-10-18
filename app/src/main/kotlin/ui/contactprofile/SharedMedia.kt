// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.contactprofile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.URLConnection
import java.util.Date
import com.squareup.picasso.Picasso
import com.stoyko.smessenger.R
import com.stoyko.smessenger.media.VideoNoteMeta
import com.stoyko.smessenger.media.VideoThumbs
import com.stoyko.smessenger.ui.chat.MediaViewer
import com.stoyko.smessenger.ui.chat.VoiceMeta
import com.stoyko.smessenger.ui.chat.VoicePlayer

/** Файл из переписки для раздела «Медиа/Файлы/Голосовые». */
class SharedFile(val name: String, val size: Long, val uri: Uri, val raw: String, val time: Long, val outgoing: Boolean)

/** Ссылка из переписки. */
class SharedLink(val url: String, val time: Long)

class SharedData(
    val media: List<SharedFile>,
    val files: List<SharedFile>,
    val voice: List<SharedFile>,
    val links: List<SharedLink>,
) {
    companion object {
        fun isImage(name: String) = URLConnection.guessContentTypeFromName(name)?.startsWith("image/") == true
        fun isVideo(name: String) = URLConnection.guessContentTypeFromName(name)?.startsWith("video/") == true ||
            name.endsWith(".mp4", true) || name.endsWith(".webm", true) || name.endsWith(".mkv", true)

        fun split(files: List<SharedFile>, texts: List<Pair<String, Long>>): SharedData {
            val voice = files.filter { VoiceMeta.isVoice(it.name) || VideoNoteMeta.isVideoNote(it.name) }
            val rest = files - voice.toSet()
            val media = rest.filter { isImage(it.name) || isVideo(it.name) }
            val other = rest - media.toSet()
            val links = texts.flatMap { (t, time) ->
                val m = Patterns.WEB_URL.matcher(t)
                val found = ArrayList<SharedLink>()
                while (m.find()) {
                    val u = m.group()
                    if (u.contains("://") || u.startsWith("www.")) found += SharedLink(u, time)
                }
                found
            }
            return SharedData(media.sortedByDescending { it.time }, other.sortedByDescending { it.time }, voice.sortedByDescending { it.time }, links.sortedByDescending { it.time })
        }
    }
}

/**
 * Раздел «Общие медиа»: вкладки Медиа / Файлы / Голосовые / Ссылки.
 * Медиа - сетка превью 3 в ряд, остальное - списком.
 */
class SharedMediaView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val tabs = LinearLayout(context)
    private val body = LinearLayout(context)
    private var data = SharedData(emptyList(), emptyList(), emptyList(), emptyList())
    private var tab = 0

    @Suppress("DEPRECATION")
    private val textColor = resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val secondary = resources.getColor(R.color.textSecondary)

    init {
        orientation = VERTICAL
        tabs.orientation = HORIZONTAL
        tabs.setPadding(dp(8), dp(8), dp(8), dp(4))
        addView(tabs)
        body.orientation = VERTICAL
        addView(body)
    }

    // Листание вкладок свайпом влево/вправо (Медиа -> Файлы -> Голосовые -> Ссылки).
    private var swipeX = 0f
    private var swipeY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeX = e.x
                swipeY = e.y
                swiping = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - swipeX
                val dy = e.y - swipeY
                if (!swiping && kotlin.math.abs(dx) > dp(28) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return swiping
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(e)
        val dx = e.x - swipeX
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> body.translationX = dx * 0.5f
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                swiping = false
                val target = when {
                    dx < -dp(60) && tab < 3 -> tab + 1
                    dx > dp(60) && tab > 0 -> tab - 1
                    else -> tab
                }
                if (target == tab) {
                    body.animate().translationX(0f).setDuration(150).start()
                } else {
                    val w = width.toFloat()
                    val dir = if (target > tab) -1f else 1f
                    tab = target
                    renderTabs()
                    body.animate().translationX(dir * w * 0.6f).alpha(0f).setDuration(120).withEndAction {
                        renderBody()
                        body.translationX = -dir * w * 0.4f
                        body.animate().translationX(0f).alpha(1f).setDuration(200).start()
                    }.start()
                }
            }
        }
        return true
    }

    fun bind(d: SharedData) {
        data = d
        renderTabs()
        renderBody()
    }

    private fun titles() = listOf(
        context.getString(R.string.shared_media) to data.media.size,
        context.getString(R.string.shared_files) to data.files.size,
        context.getString(R.string.shared_voice) to data.voice.size,
        context.getString(R.string.shared_links) to data.links.size,
    )

    private fun renderTabs() {
        tabs.removeAllViews()
        titles().forEachIndexed { i, (t, n) ->
            val selected = i == tab
            tabs.addView(
                TextView(context).apply {
                    text = if (n > 0) "$t $n" else t
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    setTextColor(if (selected) Color.WHITE else secondary)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        if (selected) {
                            colors = intArrayOf(0xFF7B6CF6.toInt(), 0xFF4B3CCB.toInt())
                            orientation = GradientDrawable.Orientation.LEFT_RIGHT
                        } else {
                            setColor(0x00000000)
                        }
                    }
                    setOnClickListener {
                        if (tab != i) {
                            tab = i
                            renderTabs()
                            body.animate().alpha(0f).setDuration(90).withEndAction {
                                renderBody()
                                body.animate().alpha(1f).setDuration(160).start()
                            }.start()
                        }
                    }
                },
                LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
            )
        }
    }

    private fun renderBody() {
        body.removeAllViews()
        when (tab) {
            0 -> grid(data.media)
            1 -> fileList(data.files, "📄")
            2 -> fileList(data.voice, "🎤")
            else -> linkList()
        }
    }

    private fun empty() {
        body.addView(
            TextView(context).apply {
                setText(R.string.shared_empty)
                setTextColor(secondary)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            },
        )
    }

    private fun grid(items: List<SharedFile>) {
        if (items.isEmpty()) return empty()
        val cell = (resources.displayMetrics.widthPixels - dp(40)) / 3
        items.take(90).chunked(3).forEach { rowItems ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            rowItems.forEach { f ->
                val box = FrameLayout(context)
                val img = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x22888888)
                }
                box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (SharedData.isVideo(f.name)) {
                    box.addView(
                        TextView(context).apply {
                            text = "▶"
                            setTextColor(Color.WHITE)
                            textSize = 20f
                            setShadowLayer(6f, 0f, 0f, Color.BLACK)
                        },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                    )
                }
                if (SharedData.isImage(f.name)) {
                    Picasso.get().load(f.raw).resize(cell, cell).centerCrop().into(img)
                } else {
                    VideoThumbs.load(context, "shared:" + f.raw, Uri.parse(f.raw), cell) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
                }
                box.setOnClickListener { open(f) }
                row.addView(box, LayoutParams(cell, cell).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }
            body.addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    private fun fileList(items: List<SharedFile>, icon: String) {
        if (items.isEmpty()) return empty()
        items.take(200).forEach { f ->
            body.addView(
                row(
                    icon = if (VideoNoteMeta.isVideoNote(f.name)) "⭕" else icon,
                    title = if (VoiceMeta.isVoice(f.name) || VideoNoteMeta.isVideoNote(f.name)) {
                        context.getString(if (VoiceMeta.isVoice(f.name)) R.string.voice_message else R.string.video_message)
                    } else {
                        f.name
                    },
                    sub = Formatter.formatShortFileSize(context, f.size) + " · " + date(f.time),
                ) { open(f) },
            )
        }
    }

    private fun linkList() {
        if (data.links.isEmpty()) return empty()
        data.links.take(200).forEach { l ->
            body.addView(
                row("🔗", l.url, date(l.time)) {
                    val u = if (l.url.contains("://")) l.url else "https://" + l.url
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                },
            )
        }
    }

    private fun date(t: Long) = if (t <= 0) "" else DateFormat.getMediumDateFormat(context).format(Date(t))

    private fun row(icon: String, title: String, sub: String, click: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(
            TextView(context).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x1F5B4BDB)
                }
            },
            LayoutParams(dp(42), dp(42)),
        )
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(context).apply { text = title; textSize = 15f; maxLines = 1; setTextColor(textColor) })
            addView(TextView(context).apply { text = sub; textSize = 12f; maxLines = 1; setTextColor(secondary) })
        }
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        isClickable = true
        setOnClickListener { click() }
    }

    private fun open(f: SharedFile) {
        when {
            SharedData.isImage(f.name) -> {
                // Все фото собеседника - листаются свайпом влево/вправо.
                val photos = data.media.filter { SharedData.isImage(it.name) }
                MediaViewer.showList(context, photos.map { it.uri to it.name }, photos.indexOf(f).coerceAtLeast(0))
            }
            VoiceMeta.isVoice(f.name) -> VoicePlayer.toggle(context, f.uri.toString(), f.uri, context.getString(R.string.voice_message))
            VideoNoteMeta.isVideoNote(f.name) ->
                VoicePlayer.toggle(context, f.uri.toString(), f.uri, context.getString(R.string.video_message), video = true)
            com.stoyko.smessenger.ui.chat.FileTypes.isVideo(f.name) ->
                com.stoyko.smessenger.ui.chat.VideoPlayerDialog(context, f.uri, f.name).show()
            com.stoyko.smessenger.ui.chat.FileTypes.isApk(f.name) ->
                com.stoyko.smessenger.ui.chat.FileTypes.install(context, f.uri, f.name)
            else -> try {
                val type = URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        null,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.mimetype_handler_not_found, f.name), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** Адрес, который можно открыть другим приложениям (файлы приложения - через FileProvider). */
        fun providerUri(context: Context, destination: String): Uri? {
            val u = Uri.parse(destination)
            return if (u.scheme == "file") {
                val f = File(u.path ?: return null)
                if (!f.exists()) return null
                runCatching { com.stoyko.smessenger.ui.chat.AttachLauncher.providerUri(context, f) }.getOrNull()
            } else {
                u
            }
        }
    }
}
