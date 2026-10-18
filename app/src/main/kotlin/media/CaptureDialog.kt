// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import com.stoyko.smessenger.R

/**
 * Экран записи «кружка» или видео для истории.
 *
 * 1. Сначала спрашиваем, какой камерой снимать: фронтальной или основной.
 * 2. Идёт запись с предпросмотром (кружок или кадр 9:16), вокруг - полоса времени.
 * 3. Во время записи камеру можно переключить; «✕» - отменить, «➤» - отправить.
 */
class CaptureDialog(
    private val context: Context,
    private val story: Boolean,
    private val onResult: (File) -> Unit,
) {
    companion object {
        const val VIDEO_NOTE_MAX_MS = 60_000L
        const val STORY_MAX_MS = 60_000L
        private const val PREFS = "capture"

        fun lastFront(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("front", true)

        private fun saveFront(ctx: Context, front: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("front", front).apply()

        /** Имя файла «кружка»: vnote_<время>_d<мс>.mp4 (длительность видна получателю сразу). */
        fun videoNoteFile(ctx: Context): File =
            File(File(ctx.filesDir, "outgoing/vnote").apply { mkdirs() }, "rec_${System.currentTimeMillis()}.mp4")
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val root = FrameLayout(context)
    private var recorder: CameraXRecorder? = null
    private var sent = false
    private var file: File? = null
    private val maxMs = if (story) STORY_MAX_MS else VIDEO_NOTE_MAX_MS

    private val ring = RingView(context)
    private val timer = TextView(context)
    private val previewBox = FrameLayout(context)
    private val controls = LinearLayout(context)

    private val tick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val ms = r.elapsedMs
            timer.text = "● " + format(ms)
            ring.progress = (ms.toFloat() / maxMs).coerceIn(0f, 1f)
            if (ms >= maxMs) {
                finish(send = true)
                return
            }
            main.postDelayed(this, 100)
        }
    }

    fun show() {
        root.setBackgroundColor(0xF0080812.toInt())
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener { if (!sent) cancelRecording() }
        dialog.show()
        showCameraChoice()
    }

    private fun format(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun pill(text: String, highlighted: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(16), dp(22), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (highlighted) 0xFF5B4BDB.toInt() else 0x33FFFFFF)
            setStroke(dp(1), 0x40FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    /** Шаг 1: какой камерой снимать. */
    private fun showCameraChoice() {
        root.removeAllViews()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        box.addView(
            TextView(context).apply {
                setText(if (story) R.string.story_record_title else R.string.vnote_choose_camera)
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            },
        )
        val last = lastFront(context)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        box.addView(pill("🤳  " + context.getString(R.string.vnote_front), last) { startRecording(true) }, lp)
        box.addView(pill("📷  " + context.getString(R.string.vnote_back), !last) { startRecording(false) }, lp)
        box.addView(
            pill(context.getString(android.R.string.cancel), false) { dialog.dismiss() }.apply {
                background = null
                setTextColor(0xB3FFFFFF.toInt())
            },
            lp,
        )
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.alpha = 0f
        box.translationY = dp(30).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** Шаг 2: запись. */
    private fun startRecording(front: Boolean) {
        saveFront(context, front)
        root.removeAllViews()
        val metrics = context.resources.displayMetrics
        val size: Int
        val boxW: Int
        val boxH: Int
        if (story) {
            boxW = (metrics.widthPixels * 0.86f).toInt()
            boxH = (boxW * 16f / 9f).toInt().coerceAtMost((metrics.heightPixels * 0.72f).toInt())
            size = boxW
        } else {
            size = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.78f).toInt()
            boxW = size
            boxH = size
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        timer.textSize = 16f
        timer.setTextColor(0xFFFF5A5F.toInt())
        timer.gravity = Gravity.CENTER
        timer.text = "● 0:00"
        column.addView(timer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Предпросмотр: круг (кружок) или скруглённый кадр (история), вокруг - полоса времени.
        val frame = FrameLayout(context)
        val previewView = androidx.camera.view.PreviewView(context)
        previewBox.removeAllViews()
        previewBox.addView(previewView, FrameLayout.LayoutParams(boxW, boxH))
        previewBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (story) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                } else {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        previewBox.clipToOutline = true
        val pad = dp(8)
        ring.story = story
        frame.addView(ring, FrameLayout.LayoutParams(boxW + pad * 2, boxH + pad * 2))
        frame.addView(previewBox, FrameLayout.LayoutParams(boxW, boxH).apply {
            leftMargin = pad
            topMargin = pad
        })
        column.addView(frame)

        controls.removeAllViews()
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        val btnLp = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
        }
        controls.addView(roundButton("✕", 0x33FFFFFF) { finish(send = false) }, btnLp)
        controls.addView(roundButton("🔄", 0x33FFFFFF) {
            recorder?.switchCamera()
            previewBox.scaleX = 0.92f
            previewBox.scaleY = 0.92f
            previewBox.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
        }, btnLp)
        controls.addView(roundButton("➤", 0xFF5B4BDB.toInt()) { finish(send = true) }, btnLp)
        column.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })
        column.addView(
            TextView(context).apply {
                setText(if (story) R.string.story_record_hint else R.string.vnote_record_hint)
                setTextColor(0x99FFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(16), dp(24), 0)
            },
        )
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        column.scaleX = 0.85f
        column.scaleY = 0.85f
        column.alpha = 0f
        column.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(OvershootInterpolator(1.4f)).setDuration(300).start()

        val out = if (story) {
            File(File(context.filesDir, "outgoing/story").apply { mkdirs() }, "rec_${System.currentTimeMillis()}.mp4")
        } else {
            videoNoteFile(context)
        }
        file = out
        // Камера - через CameraX: поворот, зеркало и пропорции учитываются автоматически.
        val rec = CameraXRecorder(context, out, story)
        rec.onError = {
            Toast.makeText(context, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            recorder = null
            sent = true // запись уже остановлена
            runCatching { dialog.dismiss() }
        }
        rec.onStarted = {
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            main.removeCallbacks(tick)
            main.post(tick)
        }
        recorder = rec
        previewView.post { if (recorder === rec) rec.start(previewView, front) }
    }

    private fun roundButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        setOnClickListener {
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
            onClick()
        }
    }

    private fun finish(send: Boolean) {
        main.removeCallbacks(tick)
        val r = recorder
        recorder = null
        if (r == null) {
            sent = true
            runCatching { dialog.dismiss() }
            return
        }
        val tooShort = r.elapsedMs < 700
        sent = true
        controls.isEnabled = false
        r.stop(cancel = !send || tooShort) { f ->
            runCatching { dialog.dismiss() }
            if (f != null && send && !tooShort) {
                // Длительность - в имени файла, как у голосовых.
                val dur = durationOf(f)
                val prefix = if (story) "story" else "vnote"
                val named = File(f.parentFile, "${prefix}_${System.currentTimeMillis()}_d$dur.mp4")
                onResult(if (f.renameTo(named)) named else f)
            } else if (send && tooShort) {
                Toast.makeText(context, R.string.vnote_too_short, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun durationOf(f: File): Long = try {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { mmr.release() }
        }
    } catch (_: Exception) {
        0L
    }

    private fun cancelRecording() {
        main.removeCallbacks(tick)
        val r = recorder ?: return
        recorder = null
        r.stop(cancel = true) { }
    }

    /** Полоса времени вокруг предпросмотра. */
    private class RingView(context: Context) : View(context) {
        var story = false
        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val d = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * d
            color = 0x33FFFFFF
        }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4 * d
            strokeCap = Paint.Cap.ROUND
            color = 0xFF00C2A8.toInt()
        }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = 3 * d
            r.set(inset, inset, width - inset, height - inset)
            if (story) {
                // История: полоса сверху.
                canvas.drawLine(inset, inset, width - inset, inset, bg)
                canvas.drawLine(inset, inset, inset + (width - 2 * inset) * progress, inset, fg)
            } else {
                canvas.drawArc(r, 0f, 360f, false, bg)
                canvas.drawArc(r, -90f, 360f * progress, false, fg)
            }
        }
    }
}
