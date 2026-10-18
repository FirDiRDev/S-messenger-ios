// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Живое видео по кругу без звука (кнопка канала, живые обои). Заполняет рамку без искажений
 * (лишнее обрезается). Пока видео не началось, а также в режиме экономии и в лёгком режиме
 * показывается картинка-заставка. Играет только пока видно на экране.
 */
class LoopVideoView(ctx: Context, private val source: Uri, poster: Int = 0, circle: Boolean = false) : FrameLayout(ctx) {
    private val texture = TextureView(ctx)
    private val posterView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0

    private val live: Boolean
        // Видео декодирует видеочип, а не процессор - играет и на слабых телефонах; выключается
        // только экономией энергии и выключенными анимациями.
        get() = Kinetics.enabled && !EnergySaver.on

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (poster != 0) posterView.setImageResource(poster)
        addView(posterView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (circle) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = start(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = crop()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                // Первый кадр уже на экране - заставку убираем.
                if (posterView.visibility == View.VISIBLE) posterView.visibility = View.INVISIBLE
            }
        }
    }

    private fun start(st: SurfaceTexture) {
        if (!live || player != null) return
        try {
            val s = Surface(st)
            surface = s
            val mp = MediaPlayer()
            mp.setDataSource(context, source)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                crop()
            }
            mp.setOnPreparedListener { if (isShown) it.start() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "video error $what/$extra")
                posterView.visibility = View.VISIBLE
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "start: $e")
            stop()
        }
    }

    private fun stop() {
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
        posterView.visibility = View.VISIBLE
    }

    private fun crop() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0 || videoW <= 0 || videoH <= 0) return
        val scale = maxOf(w / videoW, h / videoH)
        val m = Matrix()
        m.setScale(videoW * scale / w, videoH * scale / h, w / 2, h / 2)
        texture.setTransform(m)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val mp = player ?: return
        runCatching { if (visibility == View.VISIBLE && isShown) mp.start() else if (mp.isPlaying) mp.pause() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        texture.surfaceTexture?.let { start(it) }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun pause() = runCatching { player?.takeIf { it.isPlaying }?.pause() }
    fun resume() = runCatching { if (isShown) player?.start() }

    companion object {
        private const val TAG = "LoopVideo"

        fun raw(ctx: Context, res: Int): Uri = Uri.parse("android.resource://${ctx.packageName}/$res")
    }
}
