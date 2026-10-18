// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import com.squareup.picasso.Picasso
import java.io.File

/**
 * Обои конкретного чата: своя картинка или «живые» обои (видео без звука, по кругу, с обрезкой по экрану).
 * Видны только у вас. Хранятся в папке приложения (wallpapers/).
 */
object ChatWallpaper {
    private const val TAG = "ChatWallpaper"

    private fun dir(ctx: Context) = File(ctx.filesDir, "wallpapers").apply { mkdirs() }

    fun file(ctx: Context, pk: String): File? = dir(ctx).listFiles()?.firstOrNull { it.name.startsWith(pk.take(64) + ".") }

    fun isVideo(f: File) = f.extension.lowercase() in setOf("mp4", "webm", "3gp", "mkv", "mov")

    /** Скопировать выбранное фото/видео в обои чата. Не в главном потоке. */
    fun set(ctx: Context, pk: String, uri: Uri): Boolean = try {
        clear(ctx, pk)
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val ext = if (type.startsWith("video/")) "mp4" else "jpg"
        val out = File(dir(ctx), pk.take(64) + "." + ext)
        ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.length() > 0
    } catch (e: Exception) {
        Log.e(TAG, "set: $e")
        false
    }

    // ---------- 4.12.0: обои для всех чатов (Оформление) ----------

    /** Встроенные живые обои: ключ → (название, видео в res/raw). */
    val BUILTIN = linkedMapOf(
        "rain" to Pair(com.stoyko.smessenger.ai.t("🌧 Дождливая ночь (живые)", "🌧 Rainy night (live)"), com.stoyko.smessenger.R.raw.live_rain),
    )
    private const val ALL = "_all"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chat_wallpaper", Context.MODE_PRIVATE)

    /** Что стоит для всех чатов: "" - ничего, ключ из [BUILTIN] или "file" (своё фото/видео). */
    fun globalKind(ctx: Context): String = prefs(ctx).getString("global", "").orEmpty()

    fun globalFile(ctx: Context): File? = dir(ctx).listFiles()?.firstOrNull { it.name.startsWith("$ALL.") }

    fun setGlobalBuiltin(ctx: Context, key: String) {
        dir(ctx).listFiles()?.filter { it.name.startsWith("$ALL.") }?.forEach { it.delete() }
        prefs(ctx).edit().putString("global", key).apply()
    }

    /** Своё фото/видео для всех чатов. Не в главном потоке. */
    fun setGlobal(ctx: Context, uri: Uri): Boolean {
        val ok = set(ctx, ALL, uri)
        prefs(ctx).edit().putString("global", if (ok) "file" else "").apply()
        return ok
    }

    fun clearGlobal(ctx: Context) = setGlobalBuiltin(ctx, "")

    /** Название текущих общих обоев для настроек. */
    fun globalLabel(ctx: Context): String = when (val k = globalKind(ctx)) {
        "" -> com.stoyko.smessenger.ai.t("Нет", "None")
        "file" -> if (globalFile(ctx)?.let { isVideo(it) } == true) com.stoyko.smessenger.ai.t("Своё видео", "Own video") else com.stoyko.smessenger.ai.t("Своё фото", "Own photo")
        else -> BUILTIN[k]?.first ?: k
    }

    const val REQUEST_PICK = 7314

    /** Выбор своего фото/видео для всех чатов (результат - в MainActivity → [onPicked]). */
    fun pickGlobal(act: android.app.Activity, video: Boolean) {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = if (video) "video/*" else "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        runCatching {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQUEST_PICK)
        }
    }

    fun onPicked(ctx: Context, uri: Uri?) {
        uri ?: return
        val app = ctx.applicationContext
        Thread {
            val ok = setGlobal(app, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    app,
                    if (ok) com.stoyko.smessenger.ai.t("Обои для всех чатов установлены", "Wallpaper set for all chats") else app.getString(com.stoyko.smessenger.R.string.wallpaper_failed),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }.start()
    }

    /** Окно выбора обоев для всех чатов (из «Оформления»). [done] - после выбора. */
    fun showGlobalPicker(act: android.app.Activity, done: () -> Unit = {}) {
        val keys = BUILTIN.keys.toList()
        val labels = BUILTIN.values.map { it.first } + listOf(
            com.stoyko.smessenger.ai.t("🖼 Своё фото", "🖼 Own photo"),
            com.stoyko.smessenger.ai.t("🎬 Своё видео (живые обои)", "🎬 Own video (live wallpaper)"),
            com.stoyko.smessenger.ai.t("✖ Без обоев", "✖ No wallpaper"),
        )
        com.stoyko.smessenger.ui.DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle(com.stoyko.smessenger.ai.t("Обои для всех чатов", "Wallpaper for all chats"))
                .setItems(labels.toTypedArray()) { _, w ->
                    when {
                        w < keys.size -> {
                            setGlobalBuiltin(act, keys[w])
                            done()
                        }
                        w == keys.size -> pickGlobal(act, video = false)
                        w == keys.size + 1 -> pickGlobal(act, video = true)
                        else -> {
                            clearGlobal(act)
                            done()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    fun clear(ctx: Context, pk: String) {
        dir(ctx).listFiles()?.filter { it.name.startsWith(pk.take(64) + ".") }?.forEach { it.delete() }
    }

    private var player: MediaPlayer? = null

    /** Показать обои чата (или спрятать, если их нет). Возвращает true, если обои есть. */
    fun apply(ctx: Context, pk: String, image: ImageView, video: TextureView): Boolean {
        release()
        // Свои обои этого чата важнее; нет их - обои для всех чатов (из «Оформления»).
        val own = file(ctx, pk)
        val kind = if (own == null) globalKind(ctx) else "own"
        val builtin = BUILTIN[kind]?.second
        val f = own ?: if (kind == "file") globalFile(ctx) else null
        image.visibility = View.GONE
        video.visibility = View.GONE
        if (f == null && builtin == null) return false
        if (f == null && builtin != null) {
            // Встроенные живые обои; в режиме экономии - неподвижный кадр.
            if (!com.stoyko.smessenger.ui.Kinetics.enabled || com.stoyko.smessenger.ui.EnergySaver.on) {
                image.visibility = View.VISIBLE
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageResource(com.stoyko.smessenger.R.drawable.live_rain_poster)
                return true
            }
            playVideo(video) { mp -> mp.setDataSource(ctx, com.stoyko.smessenger.ui.LoopVideoView.raw(ctx, builtin)) }
            return true
        }
        if (f == null) return false
        if (!isVideo(f)) {
            image.visibility = View.VISIBLE
            Picasso.get().load(f).fit().centerCrop().into(image)
            return true
        }
        playVideo(video) { mp -> mp.setDataSource(f.absolutePath) }
        return true
    }

    private fun playVideo(video: TextureView, source: (MediaPlayer) -> Unit) {
        video.visibility = View.VISIBLE
        fun start(st: SurfaceTexture) {
            try {
                val mp = MediaPlayer()
                source(mp)
                mp.setSurface(Surface(st))
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setOnVideoSizeChangedListener { _, w, h -> crop(video, w, h) }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                player = mp
            } catch (e: Exception) {
                Log.e(TAG, "video: $e")
            }
        }
        val st = video.surfaceTexture
        if (st != null) {
            start(st)
        } else {
            video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = start(s)
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                    release()
                    return true
                }

                override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
            }
        }
    }

    /** Видео заполняет экран без искажений (лишнее обрезается). */
    private fun crop(view: TextureView, vw: Int, vh: Int) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return
        val scale = maxOf(w / vw, h / vh)
        val m = Matrix()
        m.setScale(vw * scale / w, vh * scale / h, w / 2, h / 2)
        view.setTransform(m)
    }

    fun pause() = runCatching { player?.takeIf { it.isPlaying }?.pause() }
    fun resume() = runCatching { player?.start() }

    fun release() {
        runCatching { player?.release() }
        player = null
    }
}
