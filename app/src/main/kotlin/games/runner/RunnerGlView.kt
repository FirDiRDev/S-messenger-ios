// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Поверхность OpenGL ES 2.0 для «Бега по рельсам». Сглаживание краёв (MSAA ×4), если
 * устройство умеет и не включён облегчённый режим; буфер глубины 24 бита (иначе 16).
 * Разрешение отрисовки ограничено (короткая сторона ≤ 900 px, в облегчённом режиме ≤ 560) -
 * плавно и на средних телефонах.
 */
@SuppressLint("ViewConstructor")
internal class RunnerGlView(ctx: Context, world: World, private val lite: Boolean) : GLSurfaceView(ctx) {
    val renderer = RunnerRenderer(world, lite)
    var onFocusLost: () -> Unit = {}

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(Chooser(!lite))
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val short = minOf(w, h)
        val target = if (lite) 560 else 900
        // Размер буфера меняем после раскладки (не во время неё).
        post {
            if (short > target && w > 0 && h > 0) {
                val k = target.toFloat() / short
                holder.setFixedSize((w * k).toInt(), (h * k).toInt())
            } else {
                holder.setSizeFromLayout()
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) onFocusLost()
    }

    private class Chooser(private val msaa: Boolean) : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val red = 0x3024
            val green = 0x3023
            val blue = 0x3022
            val depth = 0x3025
            val renderable = 0x3040
            val es2 = 4
            val sampleBuffers = 0x3032
            val samples = 0x3031
            val none = 0x3038
            val tries = ArrayList<IntArray>()
            if (msaa) tries.add(intArrayOf(red, 8, green, 8, blue, 8, depth, 24, renderable, es2, sampleBuffers, 1, samples, 4, none))
            tries.add(intArrayOf(red, 8, green, 8, blue, 8, depth, 24, renderable, es2, none))
            tries.add(intArrayOf(red, 8, green, 8, blue, 8, depth, 16, renderable, es2, none))
            tries.add(intArrayOf(red, 5, green, 6, blue, 5, depth, 16, renderable, es2, none))
            tries.add(intArrayOf(depth, 16, renderable, es2, none))
            for (attrs in tries) {
                val cfg = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, cfg, 1, n) && n[0] > 0) {
                    val c = cfg[0]
                    if (c != null) return c
                }
            }
            throw IllegalArgumentException("No suitable EGL config")
        }
    }
}
