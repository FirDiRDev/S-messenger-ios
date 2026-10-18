// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Готовая сетка: треугольники, на вершину 10 чисел - позиция (3), нормаль (3), цвет (3) и
 * «ночное свечение» (1: окна, фары, фонари загораются в темноте). Загружается в видеопамять
 * рендерером (буфер [vbo] пересоздаётся после потери контекста - по [gen]).
 */
internal class Mesh(val data: FloatArray) {
    val count = data.size / MeshBuilder.STRIDE
    var vbo = 0
    var gen = -1
}

/**
 * Сборщик сеток в коде: коробки (и со скошенными рёбрами), цилиндры и конусы, сферы и
 * эллипсоиды, произвольные четырёхугольники. Текущая матрица ([push]/[pop], сдвиги и
 * повороты) применяется ко всему, что добавляется, - удобно собирать сложные модели.
 */
internal class MeshBuilder {
    companion object {
        const val STRIDE = 10
    }

    private var d = FloatArray(4096)
    private var n = 0
    private val mat = FloatArray(16).also { M4.identity(it) }
    private val stack = ArrayList<FloatArray>()

    val vertexCount get() = n / STRIDE

    fun push() = stack.add(mat.copyOf())
    fun pop() {
        stack.removeAt(stack.size - 1).copyInto(mat)
    }

    fun translate(x: Float, y: Float, z: Float) = M4.translate(mat, x, y, z)
    fun rotX(deg: Float) = M4.rotX(mat, deg)
    fun rotY(deg: Float) = M4.rotY(mat, deg)
    fun rotZ(deg: Float) = M4.rotZ(mat, deg)
    fun scale(x: Float, y: Float, z: Float) = M4.scale(mat, x, y, z)

    /** Выполнить [block] со сдвигом и поворотом вокруг вертикали (матрица потом восстанавливается). */
    inline fun at(x: Float, y: Float, z: Float, ry: Float = 0f, block: () -> Unit) {
        push()
        translate(x, y, z)
        rotY(ry)
        block()
        pop()
    }

    fun build(): Mesh = Mesh(d.copyOf(n))

    /** Добавить готовую сетку (с текущей матрицей). */
    fun add(m: Mesh) {
        val s = m.data
        var i = 0
        while (i < s.size) {
            v(s[i], s[i + 1], s[i + 2], s[i + 3], s[i + 4], s[i + 5], s[i + 6], s[i + 7], s[i + 8], s[i + 9])
            i += STRIDE
        }
    }

    private fun ensure(k: Int) {
        if (n + k > d.size) d = d.copyOf(maxOf(d.size * 2, n + k + 1024))
    }

    private fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float, glow: Float) {
        ensure(STRIDE)
        val m = mat
        d[n] = m[0] * x + m[4] * y + m[8] * z + m[12]
        d[n + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        d[n + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        var tx = m[0] * nx + m[4] * ny + m[8] * nz
        var ty = m[1] * nx + m[5] * ny + m[9] * nz
        var tz = m[2] * nx + m[6] * ny + m[10] * nz
        val l = sqrt(tx * tx + ty * ty + tz * tz)
        if (l > 1e-6f) {
            tx /= l; ty /= l; tz /= l
        }
        d[n + 3] = tx
        d[n + 4] = ty
        d[n + 5] = tz
        d[n + 6] = r
        d[n + 7] = g
        d[n + 8] = b
        d[n + 9] = glow
        n += STRIDE
    }

    private fun cv(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Int, glow: Float) =
        v(x, y, z, nx, ny, nz, (c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, glow)

    /** Треугольник с общей нормалью. */
    fun tri(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
        nx: Float, ny: Float, nz: Float, c: Int, glow: Float = 0f,
    ) {
        cv(ax, ay, az, nx, ny, nz, c, glow)
        cv(bx, by, bz, nx, ny, nz, c, glow)
        cv(cx, cy, cz, nx, ny, nz, c, glow)
    }

    /** Четырёхугольник a-b-c-d (по кругу) с общей нормалью. */
    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, dd: FloatArray, nx: Float, ny: Float, nz: Float, col: Int, glow: Float = 0f) {
        tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], nx, ny, nz, col, glow)
        tri(a[0], a[1], a[2], c[0], c[1], c[2], dd[0], dd[1], dd[2], nx, ny, nz, col, glow)
    }

    /** Четырёхугольник; нормаль считается сама и разворачивается в сторону ([hx], [hy], [hz]). */
    fun quadH(a: FloatArray, b: FloatArray, c: FloatArray, dd: FloatArray, hx: Float, hy: Float, hz: Float, col: Int, glow: Float = 0f) {
        val ux = b[0] - a[0]
        val uy = b[1] - a[1]
        val uz = b[2] - a[2]
        val wx = c[0] - a[0]
        val wy = c[1] - a[1]
        val wz = c[2] - a[2]
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx
        if (nx * hx + ny * hy + nz * hz < 0) {
            nx = -nx; ny = -ny; nz = -nz
        }
        quad(a, b, c, dd, nx, ny, nz, col, glow)
    }

    /** Треугольник; нормаль считается сама (в сторону [hx], [hy], [hz]). */
    fun triH(a: FloatArray, b: FloatArray, c: FloatArray, hx: Float, hy: Float, hz: Float, col: Int, glow: Float = 0f) {
        val ux = b[0] - a[0]
        val uy = b[1] - a[1]
        val uz = b[2] - a[2]
        val wx = c[0] - a[0]
        val wy = c[1] - a[1]
        val wz = c[2] - a[2]
        var nx = uy * wz - uz * wy
        var ny = uz * wx - ux * wz
        var nz = ux * wy - uy * wx
        if (nx * hx + ny * hy + nz * hz < 0) {
            nx = -nx; ny = -ny; nz = -nz
        }
        tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], nx, ny, nz, col, glow)
    }

    /** Коробка по двум углам. [top] - отдельный цвет верхней грани (0 = как у боков). */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int, glow: Float = 0f, top: Int = 0) {
        val ax = minOf(x0, x1)
        val bx = maxOf(x0, x1)
        val ay = minOf(y0, y1)
        val by = maxOf(y0, y1)
        val az = minOf(z0, z1)
        val bz = maxOf(z0, z1)
        val tc = if (top != 0) top else c
        // +x / -x
        rect(bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, 1f, 0f, 0f, c, glow)
        rect(ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az, -1f, 0f, 0f, c, glow)
        // +y / -y
        rect(ax, by, az, ax, by, bz, bx, by, bz, bx, by, az, 0f, 1f, 0f, tc, glow)
        rect(ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, 0f, -1f, 0f, c, glow)
        // +z / -z
        rect(ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz, 0f, 0f, 1f, c, glow)
        rect(ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, 0f, 0f, -1f, c, glow)
    }

    private fun rect(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        nx: Float, ny: Float, nz: Float, c: Int, glow: Float,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, c, glow)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz, c, glow)
    }

    /** Плоский прямоугольник на плоскости x = [x] (смотрит в сторону [side] = ±1). */
    fun panelX(x: Float, y0: Float, z0: Float, y1: Float, z1: Float, side: Float, c: Int, glow: Float = 0f) =
        rect(x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1, side, 0f, 0f, c, glow)

    /** Плоский прямоугольник на плоскости z = [z] (смотрит в сторону [side] = ±1). */
    fun panelZ(z: Float, x0: Float, y0: Float, x1: Float, y1: Float, side: Float, c: Int, glow: Float = 0f) =
        rect(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, 0f, 0f, side, c, glow)

    /** Плоский прямоугольник на плоскости y = [y] (смотрит вверх). */
    fun panelY(y: Float, x0: Float, z0: Float, x1: Float, z1: Float, c: Int, glow: Float = 0f) =
        rect(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, 0f, 1f, 0f, c, glow)

    /** Коробка со скошенными рёбрами ([bev]) - мягкие блики на гранях, «игрушечный» вид. */
    fun bevelBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, bev: Float, c: Int, glow: Float = 0f) {
        val lo = floatArrayOf(minOf(x0, x1), minOf(y0, y1), minOf(z0, z1))
        val hi = floatArrayOf(maxOf(x0, x1), maxOf(y0, y1), maxOf(z0, z1))
        val b = minOf(bev, (hi[0] - lo[0]) * 0.45f, (hi[1] - lo[1]) * 0.45f, (hi[2] - lo[2]) * 0.45f)
        val ilo = floatArrayOf(lo[0] + b, lo[1] + b, lo[2] + b)
        val ihi = floatArrayOf(hi[0] - b, hi[1] - b, hi[2] - b)
        fun face(s: Int) = if (s > 0) hi else lo
        fun inner(s: Int) = if (s > 0) ihi else ilo
        val p0 = FloatArray(3)
        val p1 = FloatArray(3)
        val p2 = FloatArray(3)
        val p3 = FloatArray(3)
        val nrm = FloatArray(3)
        // Грани.
        for (a in 0 until 3) {
            val u = (a + 1) % 3
            val w = (a + 2) % 3
            for (s in intArrayOf(-1, 1)) {
                val fa = face(s)[a]
                p0[a] = fa; p0[u] = ilo[u]; p0[w] = ilo[w]
                p1[a] = fa; p1[u] = ihi[u]; p1[w] = ilo[w]
                p2[a] = fa; p2[u] = ihi[u]; p2[w] = ihi[w]
                p3[a] = fa; p3[u] = ilo[u]; p3[w] = ihi[w]
                nrm[0] = 0f; nrm[1] = 0f; nrm[2] = 0f
                nrm[a] = s.toFloat()
                quad(p0, p1, p2, p3, nrm[0], nrm[1], nrm[2], c, glow)
            }
        }
        // Рёбра.
        val k = 0.7071f
        for (a in 0 until 3) {
            val bb = (a + 1) % 3
            val cc = (a + 2) % 3
            for (sa in intArrayOf(-1, 1)) for (sb in intArrayOf(-1, 1)) {
                p0[a] = face(sa)[a]; p0[bb] = inner(sb)[bb]; p0[cc] = ilo[cc]
                p1[a] = inner(sa)[a]; p1[bb] = face(sb)[bb]; p1[cc] = ilo[cc]
                p2[a] = inner(sa)[a]; p2[bb] = face(sb)[bb]; p2[cc] = ihi[cc]
                p3[a] = face(sa)[a]; p3[bb] = inner(sb)[bb]; p3[cc] = ihi[cc]
                nrm[0] = 0f; nrm[1] = 0f; nrm[2] = 0f
                nrm[a] = sa * k
                nrm[bb] = sb * k
                quad(p0, p1, p2, p3, nrm[0], nrm[1], nrm[2], c, glow)
            }
        }
        // Углы.
        val q = 0.57735f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            tri(
                face(sx)[0], inner(sy)[1], inner(sz)[2],
                inner(sx)[0], face(sy)[1], inner(sz)[2],
                inner(sx)[0], inner(sy)[1], face(sz)[2],
                sx * q, sy * q, sz * q, c, glow,
            )
        }
    }

    /**
     * Цилиндр/конус вдоль оси y от [y0] (радиус [r0], цвет [c0]) до [y1] ([r1], [c1]).
     * Боковые нормали гладкие. [caps] - закрыть торцы.
     */
    fun cylY(
        cx: Float, cz: Float, y0: Float, y1: Float, r0: Float, r1: Float, seg: Int, c0: Int,
        c1: Int = c0, caps: Boolean = true, glow: Float = 0f,
    ) {
        val h = y1 - y0
        val slope = if (abs(h) > 1e-6f) (r0 - r1) / h else 0f
        val nl = sqrt(1 + slope * slope)
        for (i in 0 until seg) {
            val a0 = (2 * PI * i / seg).toFloat()
            val a1 = (2 * PI * (i + 1) / seg).toFloat()
            val ca = cos(a0)
            val sa = sin(a0)
            val cb = cos(a1)
            val sb = sin(a1)
            // Два треугольника стороны с нормалями по вершинам.
            val n0x = ca / nl
            val n0z = sa / nl
            val n1x = cb / nl
            val n1z = sb / nl
            val ny = slope / nl
            cv(cx + ca * r0, y0, cz + sa * r0, n0x, ny, n0z, c0, glow)
            cv(cx + cb * r0, y0, cz + sb * r0, n1x, ny, n1z, c0, glow)
            cv(cx + cb * r1, y1, cz + sb * r1, n1x, ny, n1z, c1, glow)
            cv(cx + ca * r0, y0, cz + sa * r0, n0x, ny, n0z, c0, glow)
            cv(cx + cb * r1, y1, cz + sb * r1, n1x, ny, n1z, c1, glow)
            cv(cx + ca * r1, y1, cz + sa * r1, n0x, ny, n0z, c1, glow)
            if (caps) {
                val up = if (y1 >= y0) 1f else -1f
                if (r1 > 0f) tri(cx, y1, cz, cx + ca * r1, y1, cz + sa * r1, cx + cb * r1, y1, cz + sb * r1, 0f, up, 0f, c1, glow)
                if (r0 > 0f) tri(cx, y0, cz, cx + cb * r0, y0, cz + sb * r0, cx + ca * r0, y0, cz + sa * r0, 0f, -up, 0f, c0, glow)
            }
        }
    }

    /** Цилиндр вдоль оси x (колёса, монета-ребро). */
    fun cylX(cy: Float, cz: Float, x0: Float, x1: Float, r: Float, seg: Int, c: Int, glow: Float = 0f) {
        push()
        translate(0f, cy, cz)
        rotZ(-90f)
        // После поворота локальная ось y смотрит вдоль +x.
        cylY(0f, 0f, x0, x1, r, r, seg, c, glow = glow)
        pop()
    }

    /** Цилиндр вдоль оси z. */
    fun cylZ(cx: Float, cy: Float, z0: Float, z1: Float, r: Float, seg: Int, c: Int, glow: Float = 0f) {
        push()
        translate(cx, cy, 0f)
        rotX(90f)
        // Локальная y теперь смотрит вдоль +z.
        cylY(0f, 0f, z0, z1, r, r, seg, c, glow = glow)
        pop()
    }

    /** Эллипсоид (сфера при равных радиусах); [yFrom] 0…1 - срезать низ (купол: 0.5). */
    fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, seg: Int, rings: Int, c: Int, glow: Float = 0f, yFrom: Float = 0f) {
        val startRing = (rings * yFrom).toInt()
        for (j in startRing until rings) {
            val t0 = (PI * j / rings - PI / 2).toFloat()
            val t1 = (PI * (j + 1) / rings - PI / 2).toFloat()
            for (i in 0 until seg) {
                val a0 = (2 * PI * i / seg).toFloat()
                val a1 = (2 * PI * (i + 1) / seg).toFloat()
                sv(cx, cy, cz, rx, ry, rz, t0, a0, c, glow)
                sv(cx, cy, cz, rx, ry, rz, t0, a1, c, glow)
                sv(cx, cy, cz, rx, ry, rz, t1, a1, c, glow)
                sv(cx, cy, cz, rx, ry, rz, t0, a0, c, glow)
                sv(cx, cy, cz, rx, ry, rz, t1, a1, c, glow)
                sv(cx, cy, cz, rx, ry, rz, t1, a0, c, glow)
            }
        }
        if (startRing > 0) {
            // Донышко среза.
            val t = (PI * startRing / rings - PI / 2).toFloat()
            val y = cy + sin(t) * ry
            val rr = cos(t)
            for (i in 0 until seg) {
                val a0 = (2 * PI * i / seg).toFloat()
                val a1 = (2 * PI * (i + 1) / seg).toFloat()
                tri(cx, y, cz, cx + cos(a1) * rx * rr, y, cz + sin(a1) * rz * rr, cx + cos(a0) * rx * rr, y, cz + sin(a0) * rz * rr, 0f, -1f, 0f, c, glow)
            }
        }
    }

    private fun sv(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, t: Float, a: Float, c: Int, glow: Float) {
        val ct = cos(t)
        val ux = ct * cos(a)
        val uy = sin(t)
        val uz = ct * sin(a)
        cv(cx + ux * rx, cy + uy * ry, cz + uz * rz, ux / rx, uy / ry, uz / rz, c, glow)
    }

    /** Октаэдр («кристалл»). */
    fun gem(cx: Float, cy: Float, cz: Float, r: Float, h: Float, c: Int, glow: Float = 0f) {
        val top = floatArrayOf(cx, cy + h, cz)
        val bot = floatArrayOf(cx, cy - h, cz)
        val ring = Array(4) { i ->
            val a = (PI / 2 * i).toFloat()
            floatArrayOf(cx + cos(a) * r, cy, cz + sin(a) * r)
        }
        for (i in 0 until 4) {
            val a = ring[i]
            val b = ring[(i + 1) % 4]
            val mx = (a[0] + b[0]) / 2 - cx
            val mz = (a[2] + b[2]) / 2 - cz
            triH(a, b, top, mx, h * 0.5f, mz, c, glow)
            triH(a, b, bot, mx, -h * 0.5f, mz, c, glow)
        }
    }

    /** Плоский круг в плоскости x = [x] (граффити на стенах). */
    fun discX(x: Float, cy: Float, cz: Float, r: Float, seg: Int, side: Float, c: Int, glow: Float = 0f) {
        for (i in 0 until seg) {
            val a0 = (2 * PI * i / seg).toFloat()
            val a1 = (2 * PI * (i + 1) / seg).toFloat()
            tri(x, cy, cz, x, cy + sin(a0) * r, cz + cos(a0) * r, x, cy + sin(a1) * r, cz + cos(a1) * r, side, 0f, 0f, c, glow)
        }
    }

    /** Наклонная полоса (параллелограмм) на плоскости x = [x]. */
    fun slashX(x: Float, y0: Float, z0: Float, y1: Float, z1: Float, w: Float, side: Float, c: Int, glow: Float = 0f) {
        val a = floatArrayOf(x, y0, z0 - w / 2)
        val b = floatArrayOf(x, y0, z0 + w / 2)
        val cc = floatArrayOf(x, y1, z1 + w / 2)
        val dd = floatArrayOf(x, y1, z1 - w / 2)
        quad(a, b, cc, dd, side, 0f, 0f, c, glow)
    }
}

/** Зеркальная копия сетки по x (правая сторона пути из левой). */
internal fun Mesh.mirrorX(): Mesh {
    val o = data.copyOf()
    var i = 0
    while (i < o.size) {
        o[i] = -o[i]
        o[i + 3] = -o[i + 3]
        i += MeshBuilder.STRIDE
    }
    return Mesh(o)
}
