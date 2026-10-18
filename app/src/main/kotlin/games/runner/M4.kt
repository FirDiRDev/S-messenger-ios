// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Матрицы 4×4 для 3D «Бега по рельсам» (по столбцам, как в OpenGL). Все операции -
 * на месте, без выделения памяти в кадре: сдвиг/поворот/масштаб домножаются справа.
 */
internal object M4 {
    private const val RAD = (PI / 180.0).toFloat()

    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    fun copy(src: FloatArray, dst: FloatArray) = src.copyInto(dst, 0, 0, 16)

    /** m = m · T(x, y, z). */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0 until 4) m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z
    }

    /** m = m · S(x, y, z). */
    fun scale(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0 until 4) {
            m[r] *= x
            m[4 + r] *= y
            m[8 + r] *= z
        }
    }

    /** m = m · Rx(deg): вниз смотрящие «конечности» при deg > 0 уходят вперёд (к -z). */
    fun rotX(m: FloatArray, deg: Float) {
        if (deg == 0f) return
        val c = cos(deg * RAD)
        val s = sin(deg * RAD)
        for (r in 0 until 4) {
            val a = m[4 + r]
            val b = m[8 + r]
            m[4 + r] = c * a + s * b
            m[8 + r] = -s * a + c * b
        }
    }

    /** m = m · Ry(deg). */
    fun rotY(m: FloatArray, deg: Float) {
        if (deg == 0f) return
        val c = cos(deg * RAD)
        val s = sin(deg * RAD)
        for (r in 0 until 4) {
            val a = m[r]
            val b = m[8 + r]
            m[r] = c * a - s * b
            m[8 + r] = s * a + c * b
        }
    }

    /** m = m · Rz(deg): вниз смотрящая конечность при deg > 0 уходит к +x. */
    fun rotZ(m: FloatArray, deg: Float) {
        if (deg == 0f) return
        val c = cos(deg * RAD)
        val s = sin(deg * RAD)
        for (r in 0 until 4) {
            val a = m[r]
            val b = m[4 + r]
            m[r] = c * a + s * b
            m[4 + r] = -s * a + c * b
        }
    }

    /** Точка (x, y, z) через матрицу [m] → [out] (x, y, z). */
    fun point(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    /** out = a · b ([out] не должен совпадать с [a] или [b]). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
            }
        }
    }

    fun perspective(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovyDeg * RAD / 2f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float = 0f, uy: Float = 1f, uz: Float = 0f) {
        var fx = cx - ex
        var fy = cy - ey
        var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx /= l; fy /= l; fz /= l
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        l = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-6f)
        sx /= l; sy /= l; sz /= l
        val vx = sy * fz - sz * fy
        val vy = sz * fx - sx * fz
        val vz = sx * fy - sy * fx
        m[0] = sx; m[1] = vx; m[2] = -fx; m[3] = 0f
        m[4] = sy; m[5] = vy; m[6] = -fy; m[7] = 0f
        m[8] = sz; m[9] = vz; m[10] = -fz; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(vx * ex + vy * ey + vz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }
}
