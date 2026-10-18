// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.squareup.picasso.Picasso
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.ui.fitSystemBars

private const val TAG = "Media"
private const val MAX_PICK = 20

/**
 * Кнопка «скрепка»: панель снизу с «Галерея / Камера / Файл».
 * Галерея - системный выбор фото и видео (без проводника), можно выбрать несколько.
 * Создаётся как поле фрагмента (регистрирует обработчики результатов при создании).
 *
 * [copyMedia] - копировать выбранные фото/видео в папку приложения перед отправкой
 * (личные чаты читают файл постепенно, а доступ к фото из галереи временный).
 */
class AttachLauncher(
    private val fragment: Fragment,
    private val copyMedia: Boolean,
    private val onFiles: (List<Uri>) -> Unit,
    private val onDocuments: () -> Unit,
) {
    private var pendingPhoto: File? = null

    private val gallery = fragment.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris -> if (uris.isNotEmpty()) deliver(uris) }

    private val takePicture = fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        pendingPhoto = null
        if (ok && f != null && f.length() > 0) {
            val ctx = fragment.requireContext().applicationContext
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                // Снимок камеры - 4-12 МБ: пересжимаем (если не выбран «Оригинал»), уходит в разы быстрее.
                val ready = withContext(Dispatchers.IO) { com.stoyko.smessenger.media.MediaOptimizer.optimize(ctx, f) ?: f }
                onFiles(listOf(providerUri(ctx, ready)))
            }
        } else {
            f?.delete()
        }
    }

    // Съёмка видео камерой телефона - сразу в чат.
    private var pendingVideo: File? = null
    private val captureVideo = fragment.registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = pendingVideo
        pendingVideo = null
        if (ok && f != null && f.length() > 0) {
            onFiles(listOf(providerUri(fragment.requireContext(), f)))
        } else {
            f?.delete()
        }
    }
    private var wantVideo = false

    private val cameraPermission = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (wantVideo) openVideo() else openCamera()
        } else {
            Toast.makeText(fragment.requireContext(), R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    fun show() {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx)
        val view = LayoutInflater.from(ctx).inflate(R.layout.sheet_attach, null, false)
        view.findViewById<View>(R.id.attachGallery).setOnClickListener {
            dialog.dismiss()
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        view.findViewById<View>(R.id.attachCamera).setOnClickListener {
            dialog.dismiss()
            wantVideo = false
            if (ctx.hasPermission(Manifest.permission.CAMERA)) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        view.findViewById<View>(R.id.attachVideo).setOnClickListener {
            dialog.dismiss()
            wantVideo = true
            if (ctx.hasPermission(Manifest.permission.CAMERA)) openVideo() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        view.findViewById<View>(R.id.attachFile).setOnClickListener {
            dialog.dismiss()
            onDocuments()
        }
        view.fitSystemBars(top = false)
        dialog.setContentView(view)
        // Прозрачный фон контейнера - видны скруглённые углы панели.
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    private fun openCamera() {
        val ctx = fragment.requireContext()
        val dir = File(ctx.filesDir, "outgoing/${System.currentTimeMillis()}").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "IMG_$stamp.jpg")
        pendingPhoto = f
        try {
            takePicture.launch(providerUri(ctx, f))
        } catch (e: Exception) {
            Log.e(TAG, "camera", e)
            pendingPhoto = null
            Toast.makeText(ctx, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openVideo() {
        val ctx = fragment.requireContext()
        val dir = File(ctx.filesDir, "outgoing/${System.currentTimeMillis()}").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "VID_$stamp.mp4")
        pendingVideo = f
        try {
            captureVideo.launch(providerUri(ctx, f))
        } catch (e: Exception) {
            Log.e(TAG, "video", e)
            pendingVideo = null
            Toast.makeText(ctx, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun deliver(uris0: List<Uri>) {
        // Системный выбор иногда возвращает один и тот же файл дважды - отправляем один раз.
        val uris = uris0.distinct()
        val ctx = fragment.requireContext().applicationContext
        if (!copyMedia) {
            // Группы копируют файл сами; фото всё равно сначала пересжимаем (если включено).
            if (com.stoyko.smessenger.media.MediaOptimizer.original(ctx)) {
                onFiles(uris)
                return
            }
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val out = withContext(Dispatchers.IO) {
                    uris.map { u ->
                        val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull().orEmpty()
                        if (mime.startsWith("image/") && mime != "image/gif") copyToOutgoing(ctx, u) ?: u else u
                    }
                }
                onFiles(out)
            }
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) { uris.mapNotNull { copyToOutgoing(ctx, it) } }
            if (copied.isNotEmpty()) onFiles(copied)
        }
    }

    companion object {
        fun providerUri(ctx: Context, f: File): Uri =
            FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", f)

        /** Копия в папку приложения с исходным именем (оно видно собеседнику). */
        fun copyToOutgoing(ctx: Context, uri: Uri, optimize: Boolean = true): Uri? = try {
            copyOrNull(ctx, uri, optimize)
        } catch (e: Exception) {
            Log.e(TAG, "copy $uri", e)
            null
        }

        private fun copyOrNull(ctx: Context, uri: Uri, optimize: Boolean): Uri? {
            val rawName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.replace('/', '_')
                ?.takeIf { it.isNotBlank() }
                ?: "media_${System.currentTimeMillis()}"
            // Системный выбор фото иногда отдаёт имя без расширения - тогда видео уходило
            // «просто файлом» и у собеседника не открывалось. Дописываем расширение по типу.
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?: when {
                    mime.startsWith("video/") -> "mp4"
                    mime.startsWith("image/") -> "jpg"
                    else -> null
                }
            val name = if (ext != null && !rawName.contains('.')) "$rawName.$ext" else rawName
            val dir = File(ctx.filesDir, "outgoing/${System.nanoTime()}").apply { mkdirs() }
            val out = File(dir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (out.length() <= 0L) {
                // Пустая копия (доступ к файлу уже отозван, файл в облаке не скачан) - не отправляем «пустышку».
                out.delete()
                dir.delete()
                return null
            }
            // Фото пересжимается для быстрой отправки (настройка «Качество фото»).
            val ready = if (optimize && mime.startsWith("image/") && mime != "image/gif") {
                com.stoyko.smessenger.media.MediaOptimizer.optimize(ctx, out) ?: out
            } else {
                out
            }
            return providerUri(ctx, ready)
        }
    }
}

/** Просмотр фото на весь экран внутри приложения: щипок - масштаб, двойное касание - приблизить. */
object MediaViewer {
    fun show(context: Context, uri: Uri, title: String) = showList(context, listOf(uri to title), 0)

    /** Просмотр с перелистыванием: свайп влево/вправо - следующее/предыдущее фото. */
    @SuppressLint("ClickableViewAccessibility")
    fun showList(context: Context, items: List<Pair<Uri, String>>, start: Int) {
        if (items.isEmpty()) return
        var index = start.coerceIn(0, items.size - 1)
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_media_viewer, null, false)
        val image = root.findViewById<ImageView>(R.id.viewerImage)
        val bar = root.findViewById<View>(R.id.viewerBar)
        val titleView = root.findViewById<TextView>(R.id.viewerTitle)
        val counter = root.findViewById<TextView>(R.id.viewerCounter)
        val metrics = context.resources.displayMetrics
        fun load(i: Int) {
            index = i
            val (u, t) = items[i]
            titleView.text = t
            counter.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            counter.text = "${i + 1} / ${items.size}"
            Picasso.get().load(u).resize(metrics.widthPixels * 2, 0).onlyScaleDown().into(image)
        }
        load(index)
        root.findViewById<View>(R.id.viewerClose).setOnClickListener { dialog.dismiss() }
        // Открыто из чата с запретом скриншотов и пересылки: окно защищено, «Поделиться/Сохранить» скрыты.
        com.stoyko.smessenger.chat.ScreenGuard.secureIfActive(dialog.window)
        if (com.stoyko.smessenger.chat.ScreenGuard.blocked) {
            root.findViewById<View>(R.id.viewerMore)?.visibility = View.GONE
            root.findViewById<View>(R.id.viewerShare)?.visibility = View.GONE
        }
        root.findViewById<View>(R.id.viewerMore).setOnClickListener { v ->
            val (u, t) = items[index]
            val popup = android.widget.PopupMenu(context, v)
            popup.menu.add(0, 1, 0, R.string.save_to_gallery)
            popup.menu.add(0, 2, 1, R.string.share)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> FileTypes.saveToPublic(context, u, t.takeIf { it.contains('.') } ?: "photo_${System.currentTimeMillis()}.jpg")
                    2 -> v.rootView.findViewById<View>(R.id.viewerShare)?.performClick()
                }
                true
            }
            popup.show()
        }
        root.findViewById<View>(R.id.viewerShare).setOnClickListener {
            val (uri, title) = items[index]
            // Под настоящим именем и с верным типом (раньше принятое фото уходило файлом без имени и
            // расширения - «белый файл»), пустой или удалённый файл не отправляется.
            val name = title.takeIf { it.contains('.') }
                ?: ForwardFiles.displayName(context, uri)?.takeIf { it.contains('.') }
                ?: "photo_${System.currentTimeMillis()}.jpg"
            FileTypes.shareFile(context, uri, name)
        }
        var scale = 1f
        fun apply() {
            image.scaleX = scale
            image.scaleY = scale
            if (scale <= 1.01f) {
                image.translationX = 0f
                image.translationY = 0f
            }
        }
        val scaler = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scale = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                    apply()
                    return true
                }
            },
        )
        var lastX = 0f
        var lastY = 0f
        var downY = 0f
        var downX = 0f
        var horizontal: Boolean? = null
        var tapTime = 0L
        image.setOnTouchListener { v, e ->
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    downY = e.rawY
                    downX = e.rawX
                    horizontal = null
                    // Двойное касание: приблизить / вернуть.
                    val now = System.currentTimeMillis()
                    if (now - tapTime < 280) {
                        scale = if (scale > 1.2f) 1f else 2.5f
                        v.animate().scaleX(scale).scaleY(scale).translationX(0f).translationY(0f).setDuration(200).start()
                        tapTime = 0
                    } else {
                        tapTime = now
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                    if (scale > 1.01f) {
                        v.translationX += dx
                        v.translationY += dy
                    } else {
                        // Без масштаба: влево/вправо - листать, вверх/вниз - закрыть.
                        if (horizontal == null && (kotlin.math.abs(e.rawX - downX) > 24 || kotlin.math.abs(e.rawY - downY) > 24)) {
                            horizontal = items.size > 1 && kotlin.math.abs(e.rawX - downX) > kotlin.math.abs(e.rawY - downY)
                        }
                        if (horizontal == true) {
                            v.translationX += dx
                        } else {
                            v.translationY += dy
                            root.alpha = 1f - (kotlin.math.abs(v.translationY) / (metrics.heightPixels / 2f)).coerceIn(0f, 0.7f)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (scale <= 1.01f && horizontal == true) {
                        val dxTotal = e.rawX - downX
                        val next = when {
                            dxTotal < -metrics.widthPixels / 5f && index < items.size - 1 -> index + 1
                            dxTotal > metrics.widthPixels / 5f && index > 0 -> index - 1
                            else -> -1
                        }
                        if (next >= 0) {
                            val out = if (next > index) -metrics.widthPixels.toFloat() else metrics.widthPixels.toFloat()
                            v.animate().translationX(out).alpha(0f).setDuration(140).withEndAction {
                                load(next)
                                v.translationX = -out * 0.4f
                                v.animate().translationX(0f).alpha(1f).setDuration(200).start()
                            }.start()
                        } else {
                            v.animate().translationX(0f).setDuration(150).start()
                        }
                    } else if (scale <= 1.01f) {
                        if (kotlin.math.abs(e.rawY - downY) > metrics.heightPixels / 6f) {
                            dialog.dismiss()
                        } else {
                            v.animate().translationY(0f).setDuration(150).start()
                            root.animate().alpha(1f).setDuration(150).start()
                        }
                    }
                    if (kotlin.math.abs(e.rawY - downY) < 10 && scale <= 1.01f && tapTime != 0L) {
                        // Одиночное касание - показать/спрятать панель.
                        bar.animate().alpha(if (bar.alpha > 0.5f) 0f else 1f).setDuration(150).start()
                    }
                }
            }
            true
        }

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.alpha = 0f
        dialog.show()
        root.animate().alpha(1f).setDuration(180).start()
    }
}
