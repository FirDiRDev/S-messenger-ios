// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Размеры мира «Бега по рельсам» (в метрах). Вперёд по ходу бега - ось -z. */
internal object RW {
    /** Расстояние между путями. */
    const val LW = 2.5f

    /** Длина куска пути (рельсы, обстановка по сторонам). */
    const val CH = 12f

    /** Высота крыши вагона - по ней можно бегать. */
    const val TRAIN_H = 3.4f
    const val CAR_LEN = 10f
    const val CAR_GAP = 0.6f
    const val RAMP_LEN = 7f

    /** Высота полёта на ракетном ранце (ниже свода тоннеля). */
    const val JET_Y = 6f

    fun trainLen(cars: Int) = cars * CAR_LEN + (cars - 1) * CAR_GAP
}

/** Боковая обстановка куска пути: сетка и места фонарей (x, y, z - для ночного свечения). */
internal class SideModel(val mesh: Mesh, val lamps: FloatArray)

/**
 * Все сетки мира, собранные в коде: пути, обстановка по сторонам (граффити-стены, дома,
 * парк, склады, платформа станции), тоннель с порталом, мост, город вдали, вагоны четырёх
 * расцветок и товарный, рампы, барьеры, конусы, монета, бонусы, бегун и преследователь.
 * В облегчённом режиме ([lite]) - меньше шпал, окон и мелочей.
 */
internal class Models(private val lite: Boolean) {
    companion object {
        /** Число вариантов боковой обстановки (3 вида × 2-3 варианта). */
        fun sideCount(lite: Boolean) = 3 * (if (lite) 2 else 3)
    }

    // ------------------------------------------------------------------ цвета
    private val BALLAST = 0xFF8C8478.toInt()
    private val GRAVEL = 0xFF6E6860.toInt()
    private val STEEL = 0xFF7D8590.toInt()
    private val STEEL_HI = 0xFFE4E9EF.toInt()
    private val CONCRETE = 0xFFB8B2A6.toInt()
    private val CONCRETE_HI = 0xFFD8D2C4.toInt()
    private val ASPHALT = 0xFF5F6066.toInt()
    private val GRASS = 0xFF6FAE4A.toInt()
    private val DARK = 0xFF2A2D33.toInt()

    private val rnd = Random(20260)

    val track: Mesh = buildTrack(deep = true)
    val trackBridge: Mesh = buildTrack(deep = false)
    val sidesL: Array<SideModel>
    val sidesR: Array<SideModel>
    val stationL: SideModel
    val stationR: SideModel
    val tunnel: Mesh
    val portal: Mesh
    val bridge: Mesh
    val skyline: Mesh

    /** Вагоны: [scheme] → сетка. Схема 4 - товарный. */
    val car: Array<Mesh>
    val carRear: Array<Mesh>
    val cab: Array<Mesh>
    val ramp: Mesh
    val hurdle: Mesh
    val bar: Mesh
    val block: Mesh
    val cone: Mesh
    val coin: Mesh

    /** Бонусы: магнит, ранец, кеды, ×2, доска. */
    val power: Array<Mesh>
    val board: Mesh
    val boardGlow: Mesh
    val wheel: Mesh
    val jetpack: Mesh
    val flashCone: Mesh

    val player = Rig(1f, hero = true, q = if (lite) 1f else 1.5f)
    val chaser = Rig(1.1f, hero = false, q = if (lite) 1f else 1.4f)
    val dog: Array<Mesh>

    /** Зимой робопёс превращается в оленя (те же части: тело, голова, лапа, хвост). */
    val deer: Array<Mesh>

    /** Новогодняя ёлка у путей (зимний режим): ярусы хвои и ствол; звезда - свечением. */
    val xmasTree: Mesh

    /** Сезонное убранство у путей: сакура и клумбы (весна), пальма и зонтик (лето), клён, тыквы и сено (осень). */
    val sakura: Mesh
    val flowerBed: Mesh
    val palm: Mesh
    val umbrella: Mesh
    val mapleTree: Mesh
    val pumpkins: Mesh
    val hay: Mesh

    init {
        val variants = if (lite) 2 else 3
        val l = ArrayList<SideModel>()
        for (kind in 0 until 3) for (v in 0 until variants) l.add(buildSide(kind))
        sidesL = l.toTypedArray()
        sidesR = Array(sidesL.size) { mirror(sidesL[it]) }
        stationL = buildStation()
        stationR = mirror(stationL)
        tunnel = buildTunnel()
        portal = buildPortal()
        bridge = buildBridge()
        skyline = buildSkyline()
        car = Array(5) { buildCar(it, 0) }
        carRear = Array(5) { buildCar(it, 1) }
        cab = Array(5) { buildCar(it, 2) }
        ramp = buildRamp()
        hurdle = buildHurdle()
        bar = buildBar()
        block = buildBlock()
        cone = buildCones()
        coin = buildCoin()
        power = Array(World.P_COUNT) { buildPower(it) }
        board = MeshBuilder().apply {
            bevelBox(-0.3f, 0f, -0.78f, 0.3f, 0.09f, 0.78f, 0.04f, 0xFFFFFFFF.toInt())
            // Загнутые нос и хвост деки.
            push()
            translate(0f, 0.045f, -0.78f)
            rotX(-22f)
            bevelBox(-0.29f, -0.04f, -0.16f, 0.29f, 0.04f, 0f, 0.035f, 0xFFFFFFFF.toInt())
            pop()
            push()
            translate(0f, 0.045f, 0.78f)
            rotX(22f)
            bevelBox(-0.29f, -0.04f, 0f, 0.29f, 0.04f, 0.16f, 0.035f, 0xFFFFFFFF.toInt())
            pop()
            // Подвески колёс.
            for (z in floatArrayOf(-0.5f, 0.5f)) {
                box(-0.05f, -0.06f, z - 0.05f, 0.05f, 0f, z + 0.05f, 0xFF4B5563.toInt())
                cylX(-0.08f, z, -0.2f, 0.2f, 0.022f, 8, 0xFF9CA3AF.toInt())
            }
        }.build()
        wheel = MeshBuilder().apply {
            cylX(0f, 0f, -0.045f, 0.045f, 0.075f, 14, 0xFFFFFFFF.toInt())
            cylX(0f, 0f, -0.05f, 0.05f, 0.032f, 8, 0xFFCBD5E1.toInt())
            // Метка на колесе - видно, как крутится.
            box(-0.048f, 0.045f, -0.012f, 0.048f, 0.076f, 0.012f, 0xFF1F2937.toInt())
        }.build()
        boardGlow = MeshBuilder().apply {
            box(-0.31f, 0.03f, -0.7f, 0.31f, 0.06f, 0.7f, 0xFFFFFFFF.toInt())
            box(-0.06f, 0.092f, -0.7f, 0.06f, 0.1f, 0.7f, 0xFFFFFFFF.toInt())
        }.build()
        jetpack = MeshBuilder().apply {
            bevelBox(-0.16f, -0.2f, 0f, 0.16f, 0.2f, 0.12f, 0.03f, 0xFFDC2626.toInt())
            for (x in floatArrayOf(-0.13f, 0.13f)) {
                cylY(x, 0.17f, -0.28f, 0.26f, 0.09f, 0.09f, 10, 0xFFCBD5E1.toInt())
                cylY(x, 0.17f, -0.38f, -0.28f, 0.11f, 0.06f, 10, 0xFF374151.toInt())
                sphere(x, 0.26f, 0.17f, 0.09f, 0.06f, 0.09f, 10, 5, 0xFFF59E0B.toInt(), yFrom = 0.5f)
            }
        }.build()
        flashCone = MeshBuilder().apply {
            // Луч фонаря: конус вдоль -y (по направлению руки), яркий у фонаря и гаснущий к концу.
            cylY(0f, 0f, -7f, 0f, 1.4f, 0.07f, 14, 0xFF000000.toInt(), 0xFFFFF4C4.toInt(), caps = false)
        }.build()
        dog = buildDog()
        deer = buildDeer()
        xmasTree = MeshBuilder().apply {
            cylY(0f, 0f, 0f, 0.5f, 0.12f, 0.14f, 7, 0xFF5B3A1E.toInt())
            val greens = intArrayOf(0xFF166534.toInt(), 0xFF15803D.toInt(), 0xFF14532D.toInt(), 0xFF16A34A.toInt())
            for (i in 0 until 4) {
                val y0 = 0.4f + i * 0.62f
                val r = 1.15f - i * 0.25f
                cylY(0f, 0f, y0, y0 + 0.95f, 0.02f, r, 10, greens[i])
                // Снег на ярусе.
                cylY(0f, 0f, y0 + 0.02f, y0 + 0.12f, r * 0.9f, r, 10, 0xFFF1F5F9.toInt())
            }
        }.build()
        val sg = if (lite) 8 else 11
        sakura = MeshBuilder().apply {
            cylY(0f, 0f, 0f, 1.6f, 0.16f, 0.11f, 7, 0xFF4A2C20.toInt())
            push(); translate(0f, 1.4f, 0f); rotZ(28f); cylY(0f, 0f, 0f, 0.9f, 0.08f, 0.05f, 6, 0xFF4A2C20.toInt()); pop()
            push(); translate(0f, 1.3f, 0f); rotZ(-32f); cylY(0f, 0f, 0f, 0.8f, 0.08f, 0.05f, 6, 0xFF4A2C20.toInt()); pop()
            val pinks = intArrayOf(0xFFF9A8D4.toInt(), 0xFFFBCFE8.toInt(), 0xFFF472B6.toInt(), 0xFFFCE7F3.toInt())
            val blobs = floatArrayOf(0f, 2.4f, 0f, 1.05f, -0.6f, 2.1f, 0.2f, 0.75f, 0.65f, 2.15f, -0.15f, 0.8f, 0.1f, 2.85f, 0.25f, 0.65f, -0.2f, 2.3f, -0.55f, 0.7f)
            var i = 0
            while (i < blobs.size) {
                sphere(blobs[i], blobs[i + 1], blobs[i + 2], blobs[i + 3], blobs[i + 3] * 0.8f, blobs[i + 3], sg, sg / 2 + 1, pinks[(i / 4) % pinks.size])
                i += 4
            }
        }.build()
        flowerBed = MeshBuilder().apply {
            bevelBox(-0.5f, 0f, -1.4f, 0.5f, 0.22f, 1.4f, 0.05f, 0xFF78350F.toInt())
            box(-0.44f, 0.22f, -1.34f, 0.44f, 0.27f, 1.34f, 0xFF15803D.toInt())
            val fl = intArrayOf(0xFFF43F5E.toInt(), 0xFFFDE047.toInt(), 0xFFA78BFA.toInt(), 0xFFFFFFFF.toInt(), 0xFFFB923C.toInt())
            for (k in 0 until 10) {
                val x = -0.3f + (k % 3) * 0.3f
                val z = -1.15f + k * 0.25f
                cylY(x, z, 0.25f, 0.45f, 0.015f, 0.015f, 4, 0xFF166534.toInt())
                sphere(x, 0.48f, z, 0.08f, 0.06f, 0.08f, 6, 4, fl[k % fl.size])
            }
        }.build()
        palm = MeshBuilder().apply {
            // Изогнутый ствол из колец.
            for (k in 0 until 7) {
                val y0 = k * 0.5f
                val bend = k * k * 0.018f
                cylY(-bend, 0f, y0, y0 + 0.52f, 0.17f - k * 0.012f, 0.15f - k * 0.012f, 7, if (k % 2 == 0) 0xFF8B6B4A.toInt() else 0xFF7A5C3E.toInt())
            }
            val topX = -0.68f
            for (k in 0 until 7) {
                push()
                translate(topX, 3.5f, 0f)
                rotY(k * 51.4f)
                rotZ(-62f)
                bevelBox(-0.12f, 0f, -0.03f, 0.12f, 1.5f, 0.03f, 0.02f, if (k % 2 == 0) 0xFF16A34A.toInt() else 0xFF15803D.toInt())
                pop()
            }
            for (k in 0 until 3) sphere(topX + (k - 1) * 0.14f, 3.38f, 0.1f * (k - 1), 0.1f, 0.1f, 0.1f, 6, 4, 0xFF5B3A1E.toInt())
        }.build()
        umbrella = MeshBuilder().apply {
            cylY(0f, 0f, 0f, 2.2f, 0.04f, 0.04f, 6, 0xFFE5E7EB.toInt())
            // Купол: красный конус, белая верхушка и белый кант.
            cylY(0f, 0f, 1.85f, 2.35f, 1.3f, 0.02f, 14, 0xFFEF4444.toInt())
            cylY(0f, 0f, 2.12f, 2.38f, 0.62f, 0.02f, 14, 0xFFFFFFFF.toInt(), caps = false)
            cylY(0f, 0f, 1.8f, 1.88f, 1.32f, 1.3f, 14, 0xFFFFFFFF.toInt(), caps = false)
            // Шезлонг.
            push()
            translate(0.9f, 0f, 0.6f)
            bevelBox(-0.3f, 0.25f, -0.7f, 0.3f, 0.32f, 0.5f, 0.03f, 0xFF38BDF8.toInt())
            push(); translate(0f, 0.3f, 0.5f); rotX(-55f); bevelBox(-0.3f, 0f, -0.04f, 0.3f, 0.6f, 0.04f, 0.03f, 0xFF38BDF8.toInt()); pop()
            for (z in floatArrayOf(-0.6f, 0.4f)) for (x in floatArrayOf(-0.25f, 0.25f)) box(x - 0.03f, 0f, z - 0.03f, x + 0.03f, 0.25f, z + 0.03f, 0xFFE5E7EB.toInt())
            pop()
        }.build()
        mapleTree = MeshBuilder().apply {
            cylY(0f, 0f, 0f, 1.7f, 0.17f, 0.12f, 7, 0xFF3F2A1E.toInt())
            val leaves = intArrayOf(0xFFEA580C.toInt(), 0xFFDC2626.toInt(), 0xFFF59E0B.toInt(), 0xFFFB923C.toInt(), 0xFFB91C1C.toInt())
            val blobs = floatArrayOf(0f, 2.5f, 0f, 1.0f, -0.6f, 2.2f, 0.25f, 0.72f, 0.6f, 2.25f, -0.2f, 0.78f, 0.15f, 3.1f, 0.15f, 0.62f, -0.2f, 2.6f, -0.55f, 0.6f)
            var i = 0
            while (i < blobs.size) {
                sphere(blobs[i], blobs[i + 1], blobs[i + 2], blobs[i + 3], blobs[i + 3] * 0.85f, blobs[i + 3], sg, sg / 2 + 1, leaves[(i / 4) % leaves.size])
                i += 4
            }
            // Опавшие листья под деревом.
            cylY(0f, 0f, 0f, 0.05f, 1.2f, 1.1f, 10, 0xFFD97706.toInt())
        }.build()
        pumpkins = MeshBuilder().apply {
            val pos = floatArrayOf(0f, 0f, 0.5f, 0.3f, 0.75f, 0.4f, -0.45f, 0.9f, 0.33f)
            var i = 0
            while (i < pos.size) {
                val x = pos[i]
                val z = pos[i + 1]
                val r = pos[i + 2]
                // Дольки - несколько сплющенных шаров по кругу.
                for (k in 0 until 6) {
                    val a = k * 1.047f
                    sphere(x + cos(a) * r * 0.35f, r * 0.75f, z + sin(a) * r * 0.35f, r * 0.62f, r * 0.75f, r * 0.62f, 8, 5, if (k % 2 == 0) 0xFFEA580C.toInt() else 0xFFF97316.toInt())
                }
                cylY(x, z, r * 1.4f, r * 1.75f, 0.05f, 0.03f, 5, 0xFF3F6212.toInt())
                i += 3
            }
        }.build()
        hay = MeshBuilder().apply {
            push()
            rotZ(90f)
            cylY(0.55f, 0f, -0.7f, 0.7f, 0.55f, 0.55f, 12, 0xFFEAB308.toInt())
            cylY(0.555f, 0f, -0.72f, -0.68f, 0.3f, 0.3f, 10, 0xFFCA8A04.toInt())
            pop()
        }.build()
    }

    private fun mirror(s: SideModel): SideModel {
        val lamps = s.lamps.copyOf()
        var i = 0
        while (i < lamps.size) {
            lamps[i] = -lamps[i]
            i += 3
        }
        return SideModel(s.mesh.mirrorX(), lamps)
    }

    private fun vary(c: Int, amt: Float): Int {
        val k = 1f + (rnd.nextFloat() * 2 - 1) * amt
        val r = ((c shr 16 and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun pick(vararg c: Int) = c[rnd.nextInt(c.size)]

    // ------------------------------------------------------------------ пути

    private fun buildTrack(deep: Boolean): Mesh {
        val b = MeshBuilder()
        val ch = RW.CH
        // Верх шпал - на уровне ног бегуна (y = 0), рельсы чуть выше.
        b.translate(0f, -0.19f, 0f)
        b.box(-3.7f, if (deep) -10f else -0.3f, -ch, 3.7f, 0f, 0f, GRAVEL)
        val sleepers = if (lite) 10 else 16
        for (lane in -1..1) {
            val x = lane * RW.LW
            b.box(x - 1.15f, -0.05f, -ch, x + 1.15f, 0.04f, 0f, BALLAST)
            b.box(x - 1.0f, 0.04f, -ch, x + 1.0f, 0.08f, 0f, vary(BALLAST, 0.06f))
            for (i in 0 until sleepers) {
                val z = -(i + 0.5f) * ch / sleepers
                b.box(x - 0.95f, 0.08f, z - 0.13f, x + 0.95f, 0.19f, z + 0.13f, vary(0xFF6B4A33.toInt(), 0.12f))
            }
            for (sx in floatArrayOf(-0.55f, 0.55f)) {
                b.box(x + sx - 0.07f, 0.19f, -ch, x + sx + 0.07f, 0.23f, 0f, STEEL)
                b.box(x + sx - 0.035f, 0.23f, -ch, x + sx + 0.035f, 0.32f, 0f, STEEL)
                b.box(x + sx - 0.045f, 0.32f, -ch, x + sx + 0.045f, 0.35f, 0f, STEEL_HI)
            }
        }
        return b.build()
    }

    // ------------------------------------------------------------------ обстановка

    /** [kind]: 0 - граффити-стена и дома, 1 - парк с домиком, 2 - склады и контейнеры. */
    private fun buildSide(kind: Int): SideModel {
        val b = MeshBuilder()
        val ch = RW.CH
        val lamps = ArrayList<Float>()
        when (kind) {
            0 -> {
                b.box(-80f, -10f, -ch, -3.7f, -0.02f, 0f, ASPHALT, top = 0xFF6A6B70.toInt())
                b.box(-5.0f, 0f, -ch, -4.65f, 2.8f, 0f, CONCRETE)
                b.box(-5.05f, 2.8f, -ch, -4.6f, 2.95f, 0f, CONCRETE_HI)
                graffiti(b, -4.65f, -0.4f, -ch + 0.4f)
                building(b, -9f, -0.3f, -ch + 0.3f)
                lamp(b, -4.1f, -ch / 2, lamps)
            }
            1 -> {
                b.box(-80f, -10f, -ch, -3.7f, -0.02f, 0f, GRASS, top = vary(GRASS, 0.08f))
                // Невысокий заборчик.
                var z = 0f
                while (z > -ch) {
                    b.box(-4.45f, 0f, z - 0.08f, -4.3f, 1.1f, z + 0.08f, 0xFF8B5E3C.toInt())
                    z -= 2f
                }
                b.box(-4.42f, 0.45f, -ch, -4.33f, 0.55f, 0f, 0xFFA0703F.toInt())
                b.box(-4.42f, 0.9f, -ch, -4.33f, 1.0f, 0f, 0xFFA0703F.toInt())
                for (i in 0 until if (lite) 2 else 3) tree(b, -6.5f - rnd.nextFloat() * 7f, -1.5f - rnd.nextFloat() * (ch - 3f))
                house(b, -15f, -1.5f, -ch + 1.5f)
                lamp(b, -4.0f, -ch * 0.25f, lamps)
            }
            else -> {
                b.box(-80f, -10f, -ch, -3.7f, -0.02f, 0f, ASPHALT, top = 0xFF707070.toInt())
                // Контейнеры (иногда в два яруса).
                container(b, -5.2f, 0f, -0.5f, pick(0xFF1D7FD1.toInt(), 0xFFE0592A.toInt(), 0xFF2E9E5B.toInt(), 0xFFD6A419.toInt()))
                if (rnd.nextBoolean()) container(b, -5.2f, 2.6f, -1.5f, pick(0xFF9B2C2C.toInt(), 0xFF3B4A9B.toInt(), 0xFFE7E5E4.toInt()))
                container(b, -5.2f, 0f, -6.8f, pick(0xFF7C3AED.toInt(), 0xFF0F766E.toInt(), 0xFFB45309.toInt()))
                // Склад с большой росписью на стене.
                val wh = 6f + rnd.nextFloat() * 3f
                val wc = pick(0xFFA1A1AA.toInt(), 0xFFB9A389.toInt(), 0xFF8FA3B5.toInt())
                b.box(-24f, 0f, -ch, -9.5f, wh, 0f, wc)
                b.box(-24.3f, wh, -ch, -9.2f, wh + 0.4f, 0f, vary(wc, 0.2f))
                graffiti(b, -9.5f, -0.3f, -ch + 0.3f, tall = wh - 0.6f)
                b.box(-9.45f, 0f, -ch + 3f, -9.4f, 3.4f, -ch + 6.5f, 0xFF52525B.toInt())
                lamp(b, -4.0f, -ch * 0.75f, lamps)
            }
        }
        return SideModel(b.build(), lamps.toFloatArray())
    }

    private fun lamp(b: MeshBuilder, x: Float, z: Float, lamps: ArrayList<Float>) {
        b.cylY(x, z, 0f, 5.4f, 0.09f, 0.06f, 8, 0xFF3F4652.toInt())
        b.box(x - 0.15f, 0f, z - 0.15f, x + 0.15f, 0.5f, z + 0.15f, 0xFF3F4652.toInt())
        b.box(x, 5.3f, z - 0.05f, x + 1.1f, 5.4f, z + 0.05f, 0xFF3F4652.toInt())
        b.box(x + 0.75f, 5.15f, z - 0.16f, x + 1.25f, 5.3f, z + 0.16f, 0xFF4B5563.toInt())
        b.box(x + 0.8f, 5.1f, z - 0.12f, x + 1.2f, 5.15f, z + 0.12f, 0xFFFFE6A8.toInt(), glow = 1f)
        lamps.add(x + 1f)
        lamps.add(5.05f)
        lamps.add(z)
    }

    /**
     * Граффити (своё, абстрактное): цветной фон, «пузырчатые» буквы-капсулы с обводкой,
     * подтёки, звёзды-зигзаги и неоновые штрихи, светящиеся ночью. Рисуется на плоскости
     * x = [x] (лицом к путям), от [z0] до [z1].
     */
    private fun graffiti(b: MeshBuilder, x: Float, z0: Float, z1: Float, tall: Float = 2.5f) {
        val side = 1f
        var layer = 1
        fun lx() = x + 0.025f * layer
        val pal = arrayOf(
            intArrayOf(0xFFFF3D7F.toInt(), 0xFF00D1FF.toInt(), 0xFFFFE600.toInt(), 0xFF7CFF4F.toInt()),
            intArrayOf(0xFFFF8A00.toInt(), 0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt(), 0xFFFFFFFF.toInt()),
            intArrayOf(0xFF34D399.toInt(), 0xFFF43F5E.toInt(), 0xFFFDE047.toInt(), 0xFF60A5FA.toInt()),
            intArrayOf(0xFFE879F9.toInt(), 0xFFFACC15.toInt(), 0xFF4ADE80.toInt(), 0xFFF97316.toInt()),
        )[rnd.nextInt(4)]
        val zs = minOf(z0, z1)
        val ze = maxOf(z0, z1)
        val len = ze - zs
        val y0 = 0.35f
        val y1 = tall
        // Фон-облако.
        b.panelX(lx(), y0, zs + 0.3f, y1, ze - 0.3f, side, vary(pal[0], 0.05f).let { mixC(it, 0xFF202020.toInt(), 0.55f) })
        layer++
        // Буквы-капсулы.
        val letters = 3 + rnd.nextInt(3)
        val step = (len - 1.2f) / letters
        for (i in 0 until letters) {
            val cz = zs + 0.6f + step * (i + 0.5f)
            val cy = y0 + (y1 - y0) * (0.45f + rnd.nextFloat() * 0.15f)
            val hh = (y1 - y0) * (0.28f + rnd.nextFloat() * 0.12f)
            val ww = step * 0.36f
            val c = pal[1 + rnd.nextInt(3)]
            layer = 2
            b.panelX(lx(), cy - hh - 0.08f, cz - ww - 0.08f, cy + hh + 0.08f, cz + ww + 0.08f, side, 0xFF111111.toInt())
            b.discX(lx(), cy + hh, cz, ww + 0.08f, 10, side, 0xFF111111.toInt())
            b.discX(lx(), cy - hh, cz, ww + 0.08f, 10, side, 0xFF111111.toInt())
            layer = 3
            b.panelX(lx(), cy - hh, cz - ww, cy + hh, cz + ww, side, c, glow = 0.35f)
            b.discX(lx(), cy + hh, cz, ww, 10, side, c, glow = 0.35f)
            b.discX(lx(), cy - hh, cz, ww, 10, side, c, glow = 0.35f)
            // Блик на букве и подтёк.
            layer = 4
            b.panelX(lx(), cy + hh * 0.2f, cz - ww * 0.55f, cy + hh * 0.9f, cz - ww * 0.3f, side, 0xFFFFFFFF.toInt(), glow = 0.2f)
            if (rnd.nextFloat() < 0.6f) {
                val dz = cz + (rnd.nextFloat() - 0.5f) * ww
                b.panelX(lx(), cy - hh - ww - 0.5f - rnd.nextFloat() * 0.4f, dz - 0.04f, cy - hh, dz + 0.04f, side, c)
            }
        }
        // Неоновые штрихи-зигзаги.
        layer = 4
        val zc = pal[rnd.nextInt(4)]
        var zz = zs + 0.5f
        var up = true
        val zy = y0 + 0.25f
        while (zz < ze - 0.9f) {
            val ya = if (up) zy else zy + 0.35f
            val yb = if (up) zy + 0.35f else zy
            b.slashX(lx(), ya, zz, yb, zz + 0.5f, 0.09f, side, zc, glow = 1f)
            zz += 0.5f
            up = !up
        }
        // Звёздочка.
        val sz = zs + len * (0.15f + rnd.nextFloat() * 0.7f)
        val sy = y1 - 0.3f
        for (k in 0 until 3) {
            val a = (PI / 3 * k).toFloat()
            b.slashX(lx(), sy - cos(a) * 0.28f, sz - sin(a) * 0.28f, sy + cos(a) * 0.28f, sz + sin(a) * 0.28f, 0.07f, side, 0xFFFFFFFF.toInt(), glow = 1f)
        }
    }

    private fun mixC(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun building(b: MeshBuilder, xFace: Float, z0: Float, z1: Float) {
        val h = 8f + rnd.nextFloat() * 16f
        val depth = 8f + rnd.nextFloat() * 6f
        val c = pick(0xFFB4533C.toInt(), 0xFFD9C9A8.toInt(), 0xFF7B9EA8.toInt(), 0xFFC78B5A.toInt(), 0xFF9A8FB3.toInt(), 0xFFE2B866.toInt())
        val zs = minOf(z0, z1)
        val ze = maxOf(z0, z1)
        b.box(xFace - depth, 0f, zs, xFace, h, ze, c)
        b.box(xFace - depth - 0.2f, h, zs - 0.2f, xFace + 0.2f, h + 0.5f, ze + 0.2f, vary(c, 0.25f))
        // Первый этаж - витрина под навесом.
        val shop = pick(0xFFEF4444.toInt(), 0xFF10B981.toInt(), 0xFF3B82F6.toInt(), 0xFFF59E0B.toInt())
        b.panelX(xFace + 0.03f, 0.4f, zs + 1.2f, 2.4f, ze - 1.2f, 1f, 0xFF7DD3FC.toInt(), glow = 0.9f)
        val a1 = floatArrayOf(xFace, 2.9f, zs + 0.9f)
        val a2 = floatArrayOf(xFace, 2.9f, ze - 0.9f)
        val a3 = floatArrayOf(xFace + 1.2f, 2.5f, ze - 0.9f)
        val a4 = floatArrayOf(xFace + 1.2f, 2.5f, zs + 0.9f)
        b.quadH(a1, a2, a3, a4, 0.3f, 1f, 0f, shop)
        // Окна этажей.
        val rows = ((h - 4f) / 2.7f).toInt()
        val cols = ((ze - zs - 1f) / 2.2f).toInt()
        val win = if (lite) 0xFF4A6080.toInt() else 0xFF365277.toInt()
        for (r in 0 until rows) {
            val wy = 4f + r * 2.7f
            for (k in 0 until cols) {
                val wz = zs + 1f + k * 2.2f
                val lit = if (rnd.nextFloat() < 0.55f) 1f else 0f
                b.panelX(xFace + 0.03f, wy, wz + 0.4f, wy + 1.5f, wz + 1.6f, 1f, if (lit > 0) 0xFFFFE3A3.toInt() else win, glow = lit)
                if (!lite) b.box(xFace, wy - 0.12f, wz + 0.3f, xFace + 0.12f, wy, wz + 1.7f, vary(c, 0.3f))
            }
        }
        // На крыше - бак или кондиционеры.
        if (rnd.nextBoolean()) {
            val tx = xFace - depth / 2
            val tz = (zs + ze) / 2
            for (lx in floatArrayOf(-0.9f, 0.9f)) b.box(tx + lx - 0.08f, h + 0.5f, tz - 0.08f, tx + lx + 0.08f, h + 2f, tz + 0.08f, DARK)
            b.cylY(tx, tz, h + 2f, h + 4f, 1.2f, 1.2f, 10, 0xFF8D6E4C.toInt())
            b.cylY(tx, tz, h + 4f, h + 4.8f, 1.3f, 0.1f, 10, 0xFF5B4632.toInt())
        } else {
            for (i in 0 until 2) {
                val ax = xFace - 2f - rnd.nextFloat() * (depth - 4f)
                val az = zs + 1.5f + rnd.nextFloat() * (ze - zs - 3f)
                b.box(ax - 0.7f, h + 0.5f, az - 0.5f, ax + 0.7f, h + 1.4f, az + 0.5f, 0xFFBFC5CC.toInt())
            }
        }
    }

    private fun tree(b: MeshBuilder, x: Float, z: Float) {
        val h = 2.2f + rnd.nextFloat() * 1.6f
        b.cylY(x, z, 0f, h, 0.2f, 0.14f, 7, 0xFF6B4423.toInt())
        val g = pick(0xFF4C9A3B.toInt(), 0xFF5FB04A.toInt(), 0xFF3F8F4F.toInt(), 0xFF7DBA3E.toInt())
        val r = 1.3f + rnd.nextFloat() * 0.6f
        val seg = if (lite) 6 else 8
        b.sphere(x, h + r * 0.6f, z, r, r * 0.9f, r, seg, 5, g)
        b.sphere(x + r * 0.45f, h + r * 1.2f, z - r * 0.2f, r * 0.7f, r * 0.65f, r * 0.7f, seg, 5, vary(g, 0.15f))
        b.sphere(x - r * 0.4f, h + r * 1.05f, z + r * 0.3f, r * 0.65f, r * 0.6f, r * 0.65f, seg, 5, vary(g, 0.15f))
    }

    private fun house(b: MeshBuilder, x: Float, z0: Float, z1: Float) {
        val zs = minOf(z0, z1)
        val ze = maxOf(z0, z1)
        val w = 7f
        val h = 3.6f
        val c = pick(0xFFF5E6C8.toInt(), 0xFFE8C4A0.toInt(), 0xFFCFE3E8.toInt(), 0xFFF2D0D9.toInt())
        b.box(x - w, 0f, zs, x, h, ze, c)
        val roof = pick(0xFFB91C1C.toInt(), 0xFF7C2D12.toInt(), 0xFF334155.toInt(), 0xFF166534.toInt())
        val mid = x - w / 2
        val top = h + 2.4f
        b.quadH(floatArrayOf(x + 0.4f, h - 0.2f, zs - 0.3f), floatArrayOf(x + 0.4f, h - 0.2f, ze + 0.3f), floatArrayOf(mid, top, ze + 0.3f), floatArrayOf(mid, top, zs - 0.3f), 1f, 1f, 0f, roof)
        b.quadH(floatArrayOf(x - w - 0.4f, h - 0.2f, zs - 0.3f), floatArrayOf(x - w - 0.4f, h - 0.2f, ze + 0.3f), floatArrayOf(mid, top, ze + 0.3f), floatArrayOf(mid, top, zs - 0.3f), -1f, 1f, 0f, roof)
        for (zf in floatArrayOf(zs, ze)) {
            b.triH(floatArrayOf(x, h, zf), floatArrayOf(x - w, h, zf), floatArrayOf(mid, top, zf), 0f, 0f, if (zf == ze) 1f else -1f, c)
        }
        val n = ((ze - zs) / 2.6f).toInt()
        for (i in 0 until n) {
            val wz = zs + 0.8f + i * 2.6f
            val lit = if (rnd.nextFloat() < 0.6f) 1f else 0f
            b.panelX(x + 0.03f, 1.2f, wz, 2.6f, wz + 1.2f, 1f, if (lit > 0) 0xFFFFE0A0.toInt() else 0xFF5A7896.toInt(), glow = lit)
        }
        b.box(x - 1.5f, top - 1f, zs + 1f, x - 0.9f, top + 0.6f, zs + 1.6f, 0xFF7F1D1D.toInt())
    }

    private fun container(b: MeshBuilder, x: Float, y: Float, z: Float, c: Int) {
        val w = 2.4f
        val h = 2.55f
        val l = 5.6f
        b.box(x - w, y, z - l, x, y + h, z, c)
        if (!lite) {
            var k = z - 0.3f
            while (k > z - l + 0.2f) {
                b.box(x, y + 0.1f, k - 0.06f, x + 0.05f, y + h - 0.1f, k + 0.06f, vary(c, 0.25f))
                k -= 0.45f
            }
        }
        b.box(x - w, y + h - 0.1f, z - l, x + 0.02f, y + h, z, vary(c, 0.3f))
    }

    private fun buildStation(): SideModel {
        val b = MeshBuilder()
        val ch = RW.CH
        val lamps = ArrayList<Float>()
        b.box(-80f, -10f, -ch, -3.7f, -0.02f, 0f, ASPHALT)
        b.box(-8f, 0f, -ch, -3.85f, 1.05f, 0f, CONCRETE, top = 0xFFCFC8BA.toInt())
        b.box(-4.15f, 1.05f, -ch, -3.85f, 1.075f, 0f, 0xFFFACC15.toInt())
        for (z in floatArrayOf(-3f, -9f)) {
            b.box(-6.6f, 1.05f, z - 0.25f, -6.1f, 4.4f, z + 0.25f, 0xFF64748B.toInt())
            lamps.add(-5.2f); lamps.add(4.2f); lamps.add(z)
        }
        b.box(-8.2f, 4.4f, -ch, -3.9f, 4.65f, 0f, 0xFF334155.toInt())
        b.box(-6f, 4.3f, -ch + 0.5f, -4.4f, 4.4f, -0.5f, 0xFFFFF7D6.toInt(), glow = 1f)
        // Задняя стена с яркими плакатами (свой рисунок).
        b.box(-8.3f, 1.05f, -ch, -8f, 4.4f, 0f, 0xFFD6CFC0.toInt())
        for (i in 0 until 3) {
            val z = -1.2f - i * 3.8f
            val c = pick(0xFFF43F5E.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFF59E0B.toInt(), 0xFFA855F7.toInt())
            b.panelX(-7.97f, 1.8f, z - 2.4f, 3.8f, z, 1f, c, glow = 0.5f)
            b.panelX(-7.94f, 2.2f, z - 2.0f, 2.6f, z - 0.4f, 1f, 0xFFFFFFFF.toInt(), glow = 0.5f)
            b.discX(-7.94f, 3.2f, z - 1.2f, 0.45f, 10, 1f, mixC(c, 0xFFFFFFFF.toInt(), 0.5f), glow = 0.5f)
        }
        // Скамейка и табло.
        b.box(-7.6f, 1.5f, -6.8f, -7.0f, 1.6f, -5.2f, 0xFF92400E.toInt())
        b.box(-7.6f, 1.05f, -6.7f, -7.5f, 1.5f, -5.3f, DARK)
        b.box(-5.6f, 3.5f, -7.4f, -5.4f, 4.3f, -4.6f, DARK)
        b.panelX(-5.38f, 3.6f, -7.3f, 4.2f, -4.7f, 1f, 0xFF22D3EE.toInt(), glow = 1f)
        return SideModel(b.build(), lamps.toFloatArray())
    }

    /** Кусок тоннеля: стены, свод, светильники, служебная дорожка и холм сверху. */
    private fun buildTunnel(): Mesh {
        val b = MeshBuilder()
        val ch = RW.CH
        val wall = 0xFF5B5A57.toInt()
        for (s in floatArrayOf(-1f, 1f)) {
            b.box(s * 4.5f, 0f, -ch, s * 5.0f, 5.4f, 0f, wall)
            b.box(s * 3.75f, 0f, -ch, s * 4.5f, 0.4f, 0f, 0xFF6B6A66.toInt())
            b.box(s * 4.45f, 1.6f, -ch, s * 4.5f, 1.75f, 0f, 0xFFD97706.toInt())
            for (z in floatArrayOf(-3f, -9f)) {
                b.box(s * 4.3f, 3.6f, z - 0.5f, s * 4.5f, 3.85f, z + 0.5f, 0xFFFFF1C2.toInt(), glow = 1f)
            }
        }
        val segs = 7
        for (i in 0 until segs) {
            val a0 = (PI * i / segs).toFloat()
            val a1 = (PI * (i + 1) / segs).toFloat()
            val x0 = cos(a0) * 4.5f
            val y0 = 5.4f + sin(a0) * 3.2f
            val x1 = cos(a1) * 4.5f
            val y1 = 5.4f + sin(a1) * 3.2f
            val c = if (i % 2 == 0) 0xFF4F4E4B.toInt() else 0xFF55534F.toInt()
            b.quadH(floatArrayOf(x0, y0, 0f), floatArrayOf(x1, y1, 0f), floatArrayOf(x1, y1, -ch), floatArrayOf(x0, y0, -ch), -(x0 + x1), -(y0 + y1 - 10.8f), 0f, c)
        }
        // Холм над тоннелем.
        b.box(-60f, 8.7f, -ch, 60f, 14f, 0f, 0xFF6E8F4A.toInt(), top = 0xFF7FA650.toInt())
        b.box(-60f, 0f, -ch, -5f, 8.8f, 0f, 0xFF7A6A55.toInt())
        b.box(5f, 0f, -ch, 60f, 8.8f, 0f, 0xFF7A6A55.toInt())
        return b.build()
    }

    /** Портал тоннеля (у ближнего края первого куска). */
    private fun buildPortal(): Mesh {
        val b = MeshBuilder()
        val stone = 0xFF9C9284.toInt()
        b.box(-60f, 0f, -0.9f, -5f, 14.2f, 0.3f, stone)
        b.box(5f, 0f, -0.9f, 60f, 14.2f, 0.3f, stone)
        b.box(-5f, 8.6f, -0.9f, 5f, 14.2f, 0.3f, stone)
        val segs = 7
        for (i in 0 until segs) {
            val a0 = (PI * i / segs).toFloat()
            val a1 = (PI * (i + 1) / segs).toFloat()
            val x0 = cos(a0) * 4.5f
            val y0 = 5.4f + sin(a0) * 3.2f
            val x1 = cos(a1) * 4.5f
            val y1 = 5.4f + sin(a1) * 3.2f
            for (zz in floatArrayOf(0.3f, -0.9f)) {
                b.quadH(floatArrayOf(x0, y0, zz), floatArrayOf(x1, y1, zz), floatArrayOf(x1, 8.6f, zz), floatArrayOf(x0, 8.6f, zz), 0f, 0f, if (zz > 0) 1f else -1f, stone)
            }
            // Кирпичная арка.
            b.quadH(floatArrayOf(x0 * 1.12f, 5.4f + sin(a0) * 3.6f, 0.32f), floatArrayOf(x1 * 1.12f, 5.4f + sin(a1) * 3.6f, 0.32f), floatArrayOf(x1, y1, 0.32f), floatArrayOf(x0, y0, 0.32f), 0f, 0f, 1f, if (i % 2 == 0) 0xFFA0412D.toInt() else 0xFFB4533C.toInt())
        }
        b.box(-4.95f, 0f, 0.3f, -4.5f, 5.4f, 0.34f, 0xFFA0412D.toInt())
        b.box(4.5f, 0f, 0.3f, 4.95f, 5.4f, 0.34f, 0xFFA0412D.toInt())
        // Табличка и сигнальные огни.
        b.box(-1.6f, 9.4f, 0.3f, 1.6f, 10.4f, 0.45f, 0xFF1E3A8A.toInt())
        b.panelZ(0.46f, -1.4f, 9.6f, 1.4f, 10.2f, 1f, 0xFFFDE68A.toInt(), glow = 1f)
        b.box(-60f, 14.2f, -0.9f, 60f, 15f, 0.3f, 0xFF6E8F4A.toInt())
        return b.build()
    }

    /** Мост: настил, перила, фермы над путями, опора и вода внизу. */
    private fun buildBridge(): Mesh {
        val b = MeshBuilder()
        val ch = RW.CH
        val steel = 0xFFC2410C.toInt()
        b.box(-4.4f, -1.2f, -ch, 4.4f, -0.3f, 0f, CONCRETE)
        b.box(-120f, -9.7f, -ch, 120f, -9.5f, 0f, 0xFF2B7BB9.toInt(), glow = 0.12f)
        b.box(-3.2f, -9.6f, -7.5f, 3.2f, -1.2f, -4.5f, 0xFFA8A29E.toInt())
        for (s in floatArrayOf(-1f, 1f)) {
            val x = s * 4.2f
            b.box(x - 0.12f, -0.3f, -ch, x + 0.12f, 0.25f, 0f, steel)
            b.box(x - 0.15f, 6.0f, -ch, x + 0.15f, 6.3f, 0f, steel)
            var z = 0f
            while (z > -ch - 0.01f) {
                b.box(x - 0.12f, 0f, z - 0.12f, x + 0.12f, 6.1f, z + 0.12f, steel)
                z -= 3f
            }
            for (k in 0 until 4) {
                val zc = -1.5f - k * 3f
                b.push()
                b.translate(x, 3.05f, zc)
                b.rotX(if (k % 2 == 0) 26f else -26f)
                b.box(-0.08f, -3.35f, -0.08f, 0.08f, 3.35f, 0.08f, vary(steel, 0.08f))
                b.pop()
            }
            b.box(x + s * 0.2f - 0.05f, 1.0f, -ch, x + s * 0.2f + 0.05f, 1.1f, 0f, 0xFFE5E7EB.toInt())
        }
        for (z in floatArrayOf(-0.1f, -6.1f)) b.box(-4.3f, 6.0f, z - 0.15f, 4.3f, 6.35f, z + 0.15f, steel)
        return b.build()
    }

    /** Город вдали (едет вместе с камерой; дымка оставляет силуэты). */
    private fun buildSkyline(): Mesh {
        val b = MeshBuilder()
        var x = -260f
        while (x < 260f) {
            val w = 8f + rnd.nextFloat() * 18f
            val h = 18f + rnd.nextFloat() * 55f
            val z = -170f - rnd.nextFloat() * 40f
            val c = vary(0xFF6B7A99.toInt(), 0.12f)
            b.box(x, -2f, z - 12f, x + w, h, z, c)
            if (!lite) {
                var y = 6f
                while (y < h - 3f) {
                    var wx = x + 1.2f
                    while (wx < x + w - 1.5f) {
                        if (rnd.nextFloat() < 0.35f) b.panelZ(z + 0.05f, wx, y, wx + 1.1f, y + 1.4f, 1f, 0xFFFFE7A8.toInt(), glow = 1f)
                        wx += 2.4f
                    }
                    y += 4f
                }
            }
            if (rnd.nextFloat() < 0.2f) b.cylY(x + w / 2, z - 6f, h, h + 12f, 0.25f, 0.05f, 6, c)
            x += w + rnd.nextFloat() * 5f
        }
        return b.build()
    }

    // ------------------------------------------------------------------ поезда

    /**
     * Вагон [scheme] (0-3 - пассажирские, 4 - товарный), [end]: 0 - средний, 1 - хвостовой
     * (задние красные огни у ближнего торца), 2 - кабина (лобовое стекло и фары у ближнего
     * торца - так выглядит встречный поезд). Вагон лежит от z = 0 до z = -CAR_LEN.
     */
    private fun buildCar(scheme: Int, end: Int): Mesh {
        val b = MeshBuilder()
        val l = RW.CAR_LEN
        val base = intArrayOf(0xFF2F6FD6.toInt(), 0xFFE23B3B.toInt(), 0xFF2FA36B.toInt(), 0xFFC9D1DA.toInt(), 0xFFA4502E.toInt())[scheme]
        val accent = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE08A.toInt(), 0xFFFFD43B.toInt(), 0xFFFF7A1A.toInt(), 0xFFE7D7C1.toInt())[scheme]
        val lower = mixC(base, 0xFF000000.toInt(), 0.35f)
        val roof = if (scheme == 4) 0xFF6B3A24.toInt() else 0xFFAAB2BE.toInt()
        val glass = 0xFF1E3550.toInt()
        val freight = scheme == 4
        val noseZ = if (end == 2) 0.0f else -0.1f
        // Кузов, юбка, крыша.
        b.bevelBox(-1.15f, 0.62f, -l + 0.1f, 1.15f, 3.22f, noseZ, 0.14f, base)
        b.box(-1.12f, 0.5f, -l + 0.3f, 1.12f, 0.75f, -0.3f, lower)
        b.bevelBox(-1.05f, 3.15f, -l + 0.25f, 1.05f, RW.TRAIN_H, -0.25f, 0.1f, roof)
        if (!freight) {
            b.box(-1.17f, 1.25f, -l + 0.2f, 1.17f, 1.45f, -0.2f, accent)
            b.box(-1.17f, 2.85f, -l + 0.2f, 1.17f, 2.95f, -0.2f, accent)
            // Окна и двери по бокам.
            for (s in floatArrayOf(-1f, 1f)) {
                val x = s * 1.163f
                val wins = if (lite) 3 else 5
                for (i in 0 until wins) {
                    val z0 = -1.2f - i * (l - 2.2f) / wins
                    val z1 = z0 - (l - 2.2f) / wins + 0.35f
                    if (i == 1 || i == wins - 2) {
                        // Дверь: тёмная створка с окошком и ручкой.
                        b.box(x - s * 0.01f, 0.7f, z1, x + s * 0.02f, 2.75f, z0, mixC(base, 0xFF000000.toInt(), 0.2f))
                        b.panelX(x + s * 0.03f, 1.75f, z1 + 0.15f, 2.55f, z0 - 0.15f, s, glass, glow = 0.55f)
                        b.box(x + s * 0.02f, 0.7f, (z0 + z1) / 2 - 0.02f, x + s * 0.035f, 2.75f, (z0 + z1) / 2 + 0.02f, 0xFF111827.toInt())
                    } else {
                        b.panelX(x, 1.65f, z1, 2.7f, z0, s, glass, glow = 0.55f)
                        b.panelX(x + s * 0.004f, 2.3f, z1 + 0.1f, 2.62f, z1 + 0.35f, s, 0xFF7FA6C9.toInt())
                    }
                }
            }
        } else {
            // Товарный: рёбра, раздвижная дверь, своя роспись.
            for (s in floatArrayOf(-1f, 1f)) {
                var z = -0.6f
                while (z > -l + 0.5f) {
                    b.box(s * 1.15f, 0.7f, z - 0.05f, s * 1.19f, 3.15f, z + 0.05f, mixC(base, 0xFF000000.toInt(), 0.25f))
                    z -= 0.9f
                }
                b.box(s * 1.16f, 0.75f, -6.2f, s * 1.2f, 3.0f, -3.8f, 0xFF7C3A22.toInt())
                val gx = s * 1.205f
                val gc = pick(0xFFFF3D7F.toInt(), 0xFF22D3EE.toInt(), 0xFFFDE047.toInt(), 0xFF7CFF4F.toInt())
                b.panelX(gx, 1.0f, -3.4f, 2.6f, -1.0f, s, 0xFF111111.toInt())
                b.panelX(gx + s * 0.01f, 1.1f, -3.3f, 2.5f, -1.1f, s, gc, glow = 0.3f)
                b.discX(gx + s * 0.02f, 1.8f, -2.2f, 0.45f, 10, s, 0xFFFFFFFF.toInt(), glow = 0.3f)
                b.slashX(gx + s * 0.02f, 1.2f, -8.6f, 2.6f, -7.0f, 0.18f, s, gc, glow = 0.4f)
                b.slashX(gx + s * 0.02f, 2.6f, -8.0f, 1.2f, -6.6f, 0.18f, s, 0xFFFFFFFF.toInt(), glow = 0.4f)
            }
        }
        // Тележки и колёса.
        for (bz in floatArrayOf(-1.9f, -l + 1.9f)) {
            b.box(-0.95f, 0.18f, bz - 1.25f, 0.95f, 0.55f, bz + 1.25f, 0xFF262A30.toInt())
            for (wz in floatArrayOf(bz - 0.75f, bz + 0.75f)) {
                for (s in floatArrayOf(-1f, 1f)) {
                    b.cylX(0.4f, wz, s * 0.88f, s * 1.02f, 0.4f, if (lite) 8 else 12, 0xFF3A3F47.toInt())
                    b.cylX(0.4f, wz, s * 1.02f, s * 1.04f, 0.18f, 8, 0xFF9CA3AF.toInt())
                }
            }
        }
        // Гармошка-переход к следующему вагону.
        b.box(-0.9f, 0.9f, -l - RW.CAR_GAP - 0.05f, 0.9f, 3.0f, -l + 0.1f, 0xFF2B2F36.toInt())
        // Оборудование на крыше.
        b.bevelBox(-0.6f, RW.TRAIN_H - 0.02f, -l / 2 - 1.2f, 0.6f, RW.TRAIN_H + 0.12f, -l / 2 + 1.2f, 0.05f, mixC(roof, 0xFF000000.toInt(), 0.2f))
        when (end) {
            1 -> {
                // Хвост: торцевые окна и красные огни.
                if (!freight) b.panelZ(0.0f, -0.75f, 1.7f, 0.75f, 2.7f, 1f, glass, glow = 0.55f)
                for (s in floatArrayOf(-1f, 1f)) b.box(s * 0.85f - 0.14f, 0.95f, -0.12f, s * 0.85f + 0.14f, 1.2f, 0.02f, 0xFFDC2626.toInt(), glow = 1f)
                b.box(-0.2f, 0.65f, -0.1f, 0.2f, 0.9f, 0.25f, DARK)
            }
            2 -> {
                // Кабина: большое лобовое стекло, фары, номер, отбойник.
                b.box(-1.0f, 1.75f, 0.0f, 1.0f, 2.95f, 0.06f, 0xFF0F1B2D.toInt(), glow = 0.25f)
                b.box(-0.95f, 2.6f, 0.06f, 0.95f, 2.9f, 0.08f, 0xFF5F7EA3.toInt())
                b.box(-1.12f, 1.4f, 0.0f, 1.12f, 1.6f, 0.07f, accent)
                for (s in floatArrayOf(-1f, 1f)) {
                    b.box(s * 0.78f - 0.18f, 0.95f, 0.0f, s * 0.78f + 0.18f, 1.25f, 0.1f, 0xFFFFF7D1.toInt(), glow = 1f)
                }
                b.box(-0.35f, 3.0f, 0.0f, 0.35f, 3.15f, 0.08f, 0xFFFFFFFF.toInt(), glow = 0.8f)
                b.box(-1.1f, 0.45f, 0.0f, 1.1f, 0.7f, 0.35f, 0xFF374151.toInt())
                b.box(-0.3f, 0.95f, 0.06f, 0.3f, 1.2f, 0.1f, 0xFFF1F5F9.toInt())
                b.cylY(0.5f, -0.8f, RW.TRAIN_H, RW.TRAIN_H + 0.25f, 0.08f, 0.06f, 6, 0xFF9CA3AF.toInt())
            }
        }
        return b.build()
    }

    private fun buildRamp(): Mesh {
        val b = MeshBuilder()
        val l = RW.RAMP_LEN
        val h = RW.TRAIN_H
        val n = 8
        for (i in 0 until n) {
            val z0 = -l * i / n
            val z1 = -l * (i + 1) / n
            val y0 = h * i / n + 0.05f
            val y1 = h * (i + 1) / n + 0.05f
            val c = if (i % 2 == 0) 0xFFFACC15.toInt() else 0xFF1F2937.toInt()
            b.quadH(floatArrayOf(-1.1f, y0, z0), floatArrayOf(1.1f, y0, z0), floatArrayOf(1.1f, y1, z1), floatArrayOf(-1.1f, y1, z1), 0f, 1f, 0.5f, c)
        }
        for (s in floatArrayOf(-1f, 1f)) {
            b.triH(floatArrayOf(s * 1.12f, 0f, 0f), floatArrayOf(s * 1.12f, 0f, -l), floatArrayOf(s * 1.12f, h + 0.05f, -l), s, 0f, 0f, 0xFF52525B.toInt())
            b.quadH(floatArrayOf(s * 1.1f, 0.05f, 0f), floatArrayOf(s * 1.1f, h + 0.05f, -l), floatArrayOf(s * 1.2f, h + 0.12f, -l), floatArrayOf(s * 1.2f, 0.12f, 0f), 0f, 1f, 0.5f, 0xFFE5E7EB.toInt())
        }
        b.box(-1.12f, 0f, -l - 0.05f, 1.12f, h + 0.05f, -l + 0.15f, 0xFF3F3F46.toInt())
        return b.build()
    }

    private fun stripes(b: MeshBuilder, x0: Float, x1: Float, y0: Float, y1: Float, z: Float, ca: Int, cb: Int, n: Int, glow: Float = 0f) {
        val w = (x1 - x0) / n
        for (i in 0 until n) b.box(x0 + w * i, y0, z - 0.07f, x0 + w * (i + 1), y1, z + 0.07f, if (i % 2 == 0) ca else cb, glow)
    }

    /** Низкий барьер - перепрыгнуть (задев - спотыкаешься). */
    private fun buildHurdle(): Mesh {
        val b = MeshBuilder()
        for (s in floatArrayOf(-1f, 1f)) {
            b.box(s * 1.0f - 0.07f, 0f, -0.07f, s * 1.0f + 0.07f, 1.0f, 0.07f, 0xFFE5E7EB.toInt())
            b.box(s * 1.0f - 0.12f, 0f, -0.35f, s * 1.0f + 0.12f, 0.08f, 0.35f, 0xFF374151.toInt())
        }
        stripes(b, -1.05f, 1.05f, 0.55f, 0.95f, 0f, 0xFFEF4444.toInt(), 0xFFFFFFFF.toInt(), 7, glow = 0.15f)
        return b.build()
    }

    /** Высокий барьер - только кувырком под ним. */
    private fun buildBar(): Mesh {
        val b = MeshBuilder()
        for (s in floatArrayOf(-1f, 1f)) {
            b.box(s * 1.05f - 0.09f, 0f, -0.09f, s * 1.05f + 0.09f, 2.5f, 0.09f, 0xFF4B5563.toInt())
            b.box(s * 1.05f - 0.15f, 0f, -0.3f, s * 1.05f + 0.15f, 0.12f, 0.3f, 0xFF1F2937.toInt())
            b.box(s * 1.05f - 0.12f, 2.5f, -0.12f, s * 1.05f + 0.12f, 2.72f, 0.12f, 0xFFFB923C.toInt(), glow = 1f)
        }
        stripes(b, -1.1f, 1.1f, 1.35f, 2.0f, 0f, 0xFFFACC15.toInt(), 0xFF111827.toInt(), 8, glow = 0.15f)
        return b.build()
    }

    /** Глухой барьер во весь путь - только обойти соседним путём. */
    private fun buildBlock(): Mesh {
        val b = MeshBuilder()
        b.bevelBox(-1.15f, 0f, -0.9f, 1.15f, 0.8f, 0.3f, 0.08f, CONCRETE)
        b.box(-1.1f, 0.8f, -0.2f, 1.1f, 2.3f, 0.05f, 0xFFF8FAFC.toInt())
        // Красные шевроны.
        for (i in 0 until 4) {
            val x = -0.9f + i * 0.55f
            b.slashZ(x, 0.95f, 2.15f, 0.12f, 0.06f, 0xFFDC2626.toInt())
        }
        for (s in floatArrayOf(-1f, 1f)) b.box(s * 0.85f - 0.12f, 2.3f, -0.15f, s * 0.85f + 0.12f, 2.5f, 0.1f, 0xFFEF4444.toInt(), glow = 1f)
        return b.build()
    }

    private fun MeshBuilder.slashZ(x: Float, y0: Float, y1: Float, w: Float, z: Float, c: Int) {
        val a = floatArrayOf(x - w, y0, z)
        val bb = floatArrayOf(x + w, y0, z)
        val cc = floatArrayOf(x + w + 0.3f, y1, z)
        val dd = floatArrayOf(x - w + 0.3f, y1, z)
        quad(a, bb, cc, dd, 0f, 0f, 1f, c, 0.2f)
    }

    /** Конусы - задев, спотыкаешься. */
    private fun buildCones(): Mesh {
        val b = MeshBuilder()
        for (x in floatArrayOf(-0.5f, 0.45f)) {
            b.box(x - 0.24f, 0f, -0.24f, x + 0.24f, 0.05f, 0.24f, 0xFFEA580C.toInt())
            b.cylY(x, 0f, 0.05f, 0.62f, 0.2f, 0.03f, 10, 0xFFF97316.toInt())
            b.cylY(x, 0f, 0.28f, 0.38f, 0.135f, 0.115f, 10, 0xFFFFFFFF.toInt(), glow = 0.3f)
        }
        return b.build()
    }

    /** Монета: золотой диск с ободком и звездой (вращается вокруг вертикали). */
    private fun buildCoin(): Mesh {
        val b = MeshBuilder()
        val seg = if (lite) 12 else 18
        b.cylZ(0f, 0f, -0.05f, 0.05f, 0.42f, seg, 0xFFF5B90F.toInt())
        b.cylZ(0f, 0f, -0.07f, 0.07f, 0.3f, seg, 0xFFFFD84D.toInt())
        for (s in floatArrayOf(-1f, 1f)) {
            for (k in 0 until 3) {
                b.push()
                b.translate(0f, 0f, s * 0.075f)
                b.rotZ(60f * k)
                b.box(-0.04f, -0.17f, -0.01f, 0.04f, 0.17f, 0.01f, 0xFFFFF3B0.toInt())
                b.pop()
            }
        }
        return b.build()
    }

    private fun buildPower(kind: Int): Mesh {
        val b = MeshBuilder()
        when (kind) {
            0 -> {
                // Магнит.
                val red = 0xFFE11D48.toInt()
                for (s in floatArrayOf(-1f, 1f)) {
                    b.bevelBox(s * 0.3f - 0.11f, -0.05f, -0.1f, s * 0.3f + 0.11f, 0.38f, 0.1f, 0.03f, red)
                    b.box(s * 0.3f - 0.11f, 0.38f, -0.1f, s * 0.3f + 0.11f, 0.5f, 0.1f, 0xFFE5E7EB.toInt())
                }
                for (k in 0 until 5) {
                    val a = (PI * (k + 0.5) / 5).toFloat()
                    b.push()
                    b.translate(cos(a) * 0.3f, -0.05f - sin(a) * 0.3f, 0f)
                    b.rotZ(-(a * 180f / PI.toFloat()) + 90f)
                    b.box(-0.11f, -0.1f, -0.1f, 0.11f, 0.1f, 0.1f, red)
                    b.pop()
                }
            }
            1 -> b.add(jetpackPreview())
            2 -> {
                // Пружинные кеды.
                b.bevelBox(-0.16f, -0.05f, -0.38f, 0.16f, 0.05f, 0.32f, 0.03f, 0xFFFFFFFF.toInt())
                b.bevelBox(-0.15f, 0.05f, -0.2f, 0.15f, 0.32f, 0.3f, 0.06f, 0xFF22C55E.toInt())
                b.bevelBox(-0.14f, 0.05f, -0.36f, 0.14f, 0.2f, -0.15f, 0.05f, 0xFF16A34A.toInt())
                for (k in 0 until 3) b.box(-0.1f, 0.2f + k * 0.0f, -0.12f + k * 0.12f, 0.1f, 0.34f, -0.08f + k * 0.12f, 0xFFFFFFFF.toInt())
                b.cylY(0f, 0f, -0.35f, -0.05f, 0.1f, 0.1f, 8, 0xFFFDE047.toInt(), glow = 0.6f)
            }
            3 -> {
                // ×2 - фиолетовый кристалл.
                b.gem(0f, 0.1f, 0f, 0.32f, 0.45f, 0xFFA855F7.toInt(), glow = 0.3f)
                b.gem(0f, 0.1f, 0f, 0.2f, 0.52f, 0xFFE9D5FF.toInt(), glow = 0.5f)
            }
            5 -> {
                // Щит: голубой купол-эмблема с золотым ободом.
                b.sphere(0f, 0.1f, 0f, 0.36f, 0.42f, 0.1f, 14, 8, 0xFF0EA5E9.toInt(), glow = 0.25f)
                b.sphere(0f, 0.1f, -0.04f, 0.26f, 0.31f, 0.08f, 12, 6, 0xFFBAE6FD.toInt(), glow = 0.5f)
                b.box(-0.04f, -0.12f, -0.13f, 0.04f, 0.32f, -0.1f, 0xFFFDE047.toInt(), glow = 0.6f)
                b.box(-0.16f, 0.12f, -0.13f, 0.16f, 0.18f, -0.1f, 0xFFFDE047.toInt(), glow = 0.6f)
            }
            6 -> {
                // Монеты ×2: две золотые монеты одна за другой.
                for ((i, z) in floatArrayOf(0.07f, -0.07f).withIndex()) {
                    b.push()
                    b.translate(-0.1f + i * 0.2f, 0.1f + i * 0.08f, z)
                    b.cylZ(0f, 0f, -0.05f, 0.05f, 0.3f, 18, 0xFFFACC15.toInt(), glow = 0.3f)
                    b.cylZ(0f, 0f, -0.06f, 0.06f, 0.2f, 14, 0xFFFDE68A.toInt(), glow = 0.5f)
                    b.pop()
                }
            }
            7 -> {
                // Песочные часы.
                val wood = 0xFF92400E.toInt()
                b.bevelBox(-0.26f, -0.42f, -0.2f, 0.26f, -0.34f, 0.2f, 0.03f, wood)
                b.bevelBox(-0.26f, 0.5f, -0.2f, 0.26f, 0.58f, 0.2f, 0.03f, wood)
                for (sx in floatArrayOf(-0.2f, 0.2f)) b.cylY(sx, 0f, -0.34f, 0.5f, 0.025f, 0.025f, 6, wood)
                b.cylY(0f, 0f, -0.34f, 0.08f, 0.17f, 0.03f, 12, 0xFFE0F2FE.toInt(), glow = 0.25f)
                b.cylY(0f, 0f, 0.08f, 0.5f, 0.03f, 0.17f, 12, 0xFFE0F2FE.toInt(), glow = 0.25f)
                b.cylY(0f, 0f, -0.34f, -0.12f, 0.15f, 0.08f, 10, 0xFFFBBF24.toInt(), glow = 0.5f)
            }
            else -> {
                // Парящая доска.
                b.push()
                b.rotX(-25f)
                b.bevelBox(-0.25f, -0.04f, -0.6f, 0.25f, 0.04f, 0.6f, 0.03f, 0xFF38BDF8.toInt())
                b.box(-0.05f, 0.04f, -0.55f, 0.05f, 0.05f, 0.55f, 0xFFFFFFFF.toInt(), glow = 0.8f)
                b.pop()
            }
        }
        return b.build()
    }

    private fun jetpackPreview(): Mesh = MeshBuilder().apply {
        bevelBox(-0.18f, -0.25f, -0.08f, 0.18f, 0.25f, 0.08f, 0.04f, 0xFFDC2626.toInt())
        for (x in floatArrayOf(-0.17f, 0.17f)) {
            cylY(x, 0.08f, -0.3f, 0.3f, 0.1f, 0.1f, 10, 0xFFCBD5E1.toInt())
            cylY(x, 0.08f, -0.42f, -0.3f, 0.12f, 0.07f, 10, 0xFFF59E0B.toInt(), glow = 0.5f)
        }
    }.build()

    /** Робот-пёс «Болтик»: корпус, голова с визором, уши, антенна, лапы, хвостик. */
    /** Северный олень: коричневый, белая грудка, ветвистые рога, светящийся красный нос. */
    private fun buildDeer(): Array<Mesh> {
        val fur = 0xFF8B5A2B.toInt()
        val dark = 0xFF5B3A1E.toInt()
        val body = MeshBuilder().apply {
            sphere(0f, 0.42f, -0.02f, 0.19f, 0.15f, 0.36f, 12, 8, fur)
            sphere(0f, 0.38f, -0.3f, 0.12f, 0.12f, 0.08f, 8, 5, 0xFFF5F0E6.toInt())
            // Упряжь с бубенчиком.
            box(-0.2f, 0.44f, -0.3f, 0.2f, 0.49f, -0.26f, 0xFFDC2626.toInt())
            sphere(0f, 0.36f, -0.33f, 0.035f, 0.035f, 0.035f, 6, 4, 0xFFFACC15.toInt(), glow = 0.6f)
        }.build()
        val head = MeshBuilder().apply {
            sphere(0f, 0f, -0.08f, 0.1f, 0.1f, 0.14f, 10, 6, fur)
            sphere(0f, -0.03f, -0.21f, 0.07f, 0.06f, 0.08f, 8, 5, 0xFFA16207.toInt())
            // Нос (светится - эффект добавляет рендер).
            sphere(0f, -0.02f, -0.29f, 0.035f, 0.035f, 0.035f, 8, 5, 0xFFEF4444.toInt(), glow = 1f)
            for (s in floatArrayOf(-1f, 1f)) {
                // Глаза и уши.
                sphere(s * 0.055f, 0.04f, -0.17f, 0.018f, 0.022f, 0.012f, 6, 4, 0xFF111827.toInt())
                push()
                translate(s * 0.09f, 0.07f, -0.02f)
                rotZ(-s * 55f)
                sphere(0f, 0.05f, 0f, 0.025f, 0.06f, 0.02f, 6, 4, dark)
                pop()
                // Рога: ствол и три отростка.
                push()
                translate(s * 0.05f, 0.08f, -0.03f)
                rotZ(-s * 18f)
                cylY(0f, 0f, 0f, 0.26f, 0.016f, 0.012f, 6, 0xFFD6C3A0.toInt())
                for ((y, a) in listOf(0.1f to 45f, 0.17f to -40f, 0.23f to 50f)) {
                    push()
                    translate(0f, y, 0f)
                    rotZ(-s * a)
                    cylY(0f, 0f, 0f, 0.1f, 0.011f, 0.007f, 5, 0xFFD6C3A0.toInt())
                    pop()
                }
                pop()
            }
        }.build()
        val leg = MeshBuilder().apply {
            cylY(0f, 0f, -0.28f, 0f, 0.03f, 0.042f, 7, fur)
            cylY(0f, 0f, -0.32f, -0.27f, 0.035f, 0.032f, 7, 0xFF1F1A16.toInt())
        }.build()
        val tail = MeshBuilder().apply {
            sphere(0f, 0.05f, 0f, 0.05f, 0.06f, 0.04f, 6, 4, 0xFFF5F0E6.toInt())
        }.build()
        return arrayOf(body, head, leg, tail)
    }

    private fun buildDog(): Array<Mesh> {
        val metal = 0xFFB7C0CC.toInt()
        val body = MeshBuilder().apply {
            bevelBox(-0.17f, 0.28f, -0.34f, 0.17f, 0.52f, 0.3f, 0.06f, metal)
            box(-0.12f, 0.5f, -0.2f, 0.12f, 0.56f, 0.15f, 0xFF64748B.toInt())
            box(-0.175f, 0.36f, -0.15f, 0.175f, 0.42f, 0.1f, 0xFF22D3EE.toInt(), glow = 1f)
        }.build()
        val head = MeshBuilder().apply {
            bevelBox(-0.14f, -0.1f, -0.2f, 0.14f, 0.13f, 0.06f, 0.05f, metal)
            box(-0.12f, -0.02f, -0.205f, 0.12f, 0.06f, -0.19f, 0xFF22D3EE.toInt(), glow = 1f)
            box(-0.05f, -0.12f, -0.3f, 0.05f, -0.02f, -0.18f, 0xFF94A3B8.toInt())
            for (s in floatArrayOf(-1f, 1f)) {
                push()
                translate(s * 0.1f, 0.12f, -0.02f)
                rotZ(-s * 20f)
                box(-0.035f, 0f, -0.04f, 0.035f, 0.13f, 0.04f, 0xFF64748B.toInt())
                pop()
            }
            cylY(0.05f, 0.03f, 0.12f, 0.32f, 0.012f, 0.01f, 5, 0xFF334155.toInt())
        }.build()
        val leg = MeshBuilder().apply {
            cylY(0f, 0f, -0.28f, 0f, 0.035f, 0.045f, 7, 0xFF64748B.toInt())
            sphere(0f, -0.28f, -0.01f, 0.05f, 0.035f, 0.06f, 7, 4, 0xFF334155.toInt())
        }.build()
        val tail = MeshBuilder().apply {
            cylY(0f, 0f, 0f, 0.2f, 0.025f, 0.015f, 6, 0xFF94A3B8.toInt())
            sphere(0f, 0.21f, 0f, 0.03f, 0.03f, 0.03f, 6, 4, 0xFFF43F5E.toInt(), glow = 1f)
        }.build()
        return arrayOf(body, head, leg, tail)
    }
}

/**
 * Части фигуры (бегуна или преследователя) - сетки белые (кроме глаз), цвет одежды задаётся
 * при рисовании. [k] - масштаб роста, [hero] - бегун (с причёсками, шапками и аксессуарами)
 * или обходчик (жилет, фуражка, усы, фонарь).
 */
internal class Rig(val k: Float, hero: Boolean, private val q: Float = 1f) {
    /** Число граней с учётом качества: на сильных телефонах персонажи глаже (больше граней). */
    private fun seg(v: Int) = (v * q).toInt().coerceAtLeast(3)
    private val W = 0xFFFFFFFF.toInt()
    val hipY = 0.96f * k
    val thighLen = 0.44f * k
    val shinLen = 0.42f * k
    val upperLen = 0.3f * k
    val foreLen = 0.27f * k
    val shoulderX = 0.25f * k * (if (hero) 1f else 1.08f)
    val shoulderY = 0.47f * k
    val neckY = 0.52f * k
    val headY = 0.2f * k

    val pelvis = MeshBuilder().apply { bevelBox(-0.16f * k, -0.09f * k, -0.105f * k, 0.16f * k, 0.1f * k, 0.105f * k, 0.04f * k, W) }.build()
    val abdomen = MeshBuilder().apply { bevelBox(-0.17f * k, 0f, -0.11f * k, 0.17f * k, 0.27f * k, 0.11f * k, 0.05f * k, W) }.build()
    val chest = MeshBuilder().apply {
        val wide = if (hero) 1f else 1.1f
        bevelBox(-0.215f * k * wide, 0.22f * k, -0.13f * k, 0.215f * k * wide, 0.53f * k, 0.13f * k, 0.07f * k, W)
    }.build()
    val stripe = MeshBuilder().apply { box(-0.22f * k, 0.33f * k, -0.135f * k, 0.22f * k, 0.39f * k, 0.135f * k, W) }.build()
    val neck = MeshBuilder().apply { cylY(0f, 0f, 0.48f * k, 0.62f * k, 0.055f * k, 0.05f * k, seg(8), W) }.build()

    /** Голова: начало координат - основание шеи; лицо смотрит в -z. */
    val head = MeshBuilder().apply {
        sphere(0f, 0.17f * k, 0f, 0.15f * k, 0.17f * k, 0.155f * k, seg(14), seg(10), W)
        for (s in floatArrayOf(-1f, 1f)) sphere(s * 0.148f * k, 0.16f * k, 0.01f * k, 0.025f * k, 0.04f * k, 0.03f * k, seg(6), seg(4), W)
        sphere(0f, 0.15f * k, -0.155f * k, 0.025f * k, 0.03f * k, 0.03f * k, seg(6), seg(4), W)
    }.build()
    val eyes = MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            sphere(s * 0.055f * k, 0.19f * k, -0.135f * k, 0.032f * k, 0.038f * k, 0.018f * k, seg(8), seg(5), 0xFFFFFFFF.toInt())
            sphere(s * 0.055f * k, 0.188f * k, -0.151f * k, 0.017f * k, 0.022f * k, 0.008f * k, seg(7), seg(4), 0xFF1F2937.toInt())
            box(s * 0.055f * k - 0.035f * k, 0.245f * k, -0.15f * k, s * 0.055f * k + 0.035f * k, 0.258f * k, -0.135f * k, 0xFF3B2A20.toInt())
        }
        box(-0.035f * k, 0.085f * k, -0.153f * k, 0.035f * k, 0.1f * k, -0.14f * k, 0xFF8B3A3A.toInt())
    }.build()
    val upperArm = MeshBuilder().apply {
        sphere(0f, 0f, 0f, 0.065f * k, 0.065f * k, 0.065f * k, seg(8), seg(5), W)
        cylY(0f, 0f, -upperLen, 0f, 0.05f * k, 0.062f * k, seg(8), W)
    }.build()
    val foreArm = MeshBuilder().apply { cylY(0f, 0f, -foreLen, 0f, 0.042f * k, 0.05f * k, seg(8), W) }.build()
    val hand = MeshBuilder().apply { sphere(0f, -0.04f * k, 0f, 0.05f * k, 0.058f * k, 0.045f * k, seg(8), seg(5), W) }.build()
    val thigh = MeshBuilder().apply {
        sphere(0f, 0f, 0f, 0.085f * k, 0.085f * k, 0.085f * k, seg(8), seg(5), W)
        cylY(0f, 0f, -thighLen, 0f, 0.065f * k, 0.085f * k, seg(9), W)
    }.build()
    val shin = MeshBuilder().apply {
        sphere(0f, 0f, 0f, 0.066f * k, 0.066f * k, 0.066f * k, seg(8), seg(4), W)
        cylY(0f, 0f, -shinLen, 0f, 0.05f * k, 0.064f * k, seg(9), W)
    }.build()

    /** Обувь: начало - щиколотка, носок в -z. */
    val shoe = MeshBuilder().apply { bevelBox(-0.075f * k, -0.075f * k, -0.2f * k, 0.075f * k, 0.04f * k, 0.07f * k, 0.035f * k, W) }.build()
    val sole = MeshBuilder().apply { bevelBox(-0.08f * k, -0.105f * k, -0.215f * k, 0.08f * k, -0.07f * k, 0.08f * k, 0.015f * k, W) }.build()

    /** Причёски бегуна: 0 короткая, 1 ирокез, 2 хвост, 3 ёжик, 4 колючки. */
    val hair: Array<Mesh> = if (!hero) emptyArray() else arrayOf(
        MeshBuilder().apply {
            sphere(0f, 0.185f * k, 0.005f * k, 0.162f * k, 0.18f * k, 0.168f * k, seg(12), seg(8), W, yFrom = 0.6f)
            sphere(0f, 0.15f * k, 0.05f * k, 0.155f * k, 0.165f * k, 0.15f * k, seg(12), seg(8), W, yFrom = 0.3f)
            box(-0.1f * k, 0.27f * k, -0.17f * k, 0.12f * k, 0.31f * k, -0.1f * k, W)
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.17f * k, 0.01f * k, 0.153f * k, 0.172f * k, 0.158f * k, seg(12), seg(8), W, yFrom = 0.62f)
            for (i in 0 until 6) {
                val z = -0.12f * k + i * 0.05f * k
                val h = (0.12f + 0.03f * sin(i * 1.3f)) * k
                bevelBox(-0.03f * k, 0.3f * k, z - 0.025f * k, 0.03f * k, 0.3f * k + h, z + 0.025f * k, 0.01f * k, W)
            }
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.185f * k, 0.005f * k, 0.162f * k, 0.18f * k, 0.168f * k, seg(12), seg(8), W, yFrom = 0.6f)
            sphere(0f, 0.15f * k, 0.05f * k, 0.155f * k, 0.165f * k, 0.15f * k, seg(12), seg(8), W, yFrom = 0.3f)
            push()
            translate(0f, 0.22f * k, 0.16f * k)
            rotX(35f)
            cylY(0f, 0f, -0.28f * k, 0f, 0.03f * k, 0.055f * k, seg(8), W)
            pop()
            sphere(0f, 0.24f * k, 0.155f * k, 0.04f * k, 0.04f * k, 0.04f * k, seg(6), seg(4), W)
        }.build(),
        MeshBuilder().apply { sphere(0f, 0.175f * k, 0.008f * k, 0.155f * k, 0.174f * k, 0.162f * k, seg(12), seg(8), W, yFrom = 0.58f) }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.18f * k, 0.008f * k, 0.157f * k, 0.176f * k, 0.163f * k, seg(12), seg(8), W, yFrom = 0.58f)
            for (i in 0 until 7) {
                val a = i * 51f
                push()
                translate(0f, 0.27f * k, 0.01f * k)
                rotY(a)
                rotX(-35f)
                cylY(0f, 0f, 0f, 0.13f * k, 0.045f * k, 0f, seg(5), W)
                pop()
            }
        }.build(),
    )

    /** Головные уборы: 0 нет, 1 кепка, 2 шапка с помпоном, 3 каска с фонариком, 4 бандана, 5 панама, 6 капюшон. */
    val hats: Array<Mesh> = if (!hero) emptyArray() else arrayOf(
        Mesh(FloatArray(0)),
        MeshBuilder().apply {
            sphere(0f, 0.2f * k, 0.005f * k, 0.165f * k, 0.165f * k, 0.172f * k, seg(12), seg(8), W, yFrom = 0.55f)
            push()
            translate(0f, 0.235f * k, -0.15f * k)
            rotX(-8f)
            bevelBox(-0.12f * k, -0.012f * k, -0.13f * k, 0.12f * k, 0.012f * k, 0.04f * k, 0.01f * k, W)
            pop()
            sphere(0f, 0.37f * k, 0f, 0.02f * k, 0.015f * k, 0.02f * k, seg(6), seg(3), W)
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.2f * k, 0.005f * k, 0.168f * k, 0.19f * k, 0.174f * k, seg(12), seg(8), W, yFrom = 0.55f)
            cylY(0f, 0.005f * k, 0.2f * k, 0.26f * k, 0.172f * k, 0.172f * k, seg(14), W)
            sphere(0f, 0.4f * k, 0f, 0.055f * k, 0.055f * k, 0.055f * k, seg(8), seg(5), W)
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.205f * k, 0.005f * k, 0.178f * k, 0.18f * k, 0.185f * k, seg(12), seg(8), W, yFrom = 0.52f)
            cylY(0f, 0.005f * k, 0.19f * k, 0.215f * k, 0.2f * k, 0.2f * k, seg(14), W)
            box(-0.012f * k, 0.3f * k, -0.17f * k, 0.012f * k, 0.38f * k, 0.17f * k, W)
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.19f * k, 0.005f * k, 0.16f * k, 0.172f * k, 0.166f * k, seg(12), seg(8), W, yFrom = 0.52f)
            for (s in floatArrayOf(-1f, 1f)) {
                push()
                translate(s * 0.03f * k, 0.22f * k, 0.16f * k)
                rotX(40f)
                rotZ(s * 18f)
                bevelBox(-0.025f * k, -0.14f * k, -0.012f * k, 0.025f * k, 0f, 0.012f * k, 0.008f * k, W)
                pop()
            }
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.22f * k, 0.005f * k, 0.165f * k, 0.16f * k, 0.172f * k, seg(12), seg(8), W, yFrom = 0.5f)
            cylY(0f, 0.005f * k, 0.18f * k, 0.25f * k, 0.27f * k, 0.17f * k, seg(14), W)
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.17f * k, 0.03f * k, 0.19f * k, 0.21f * k, 0.2f * k, seg(12), seg(8), W, yFrom = 0.25f)
            box(-0.2f * k, -0.04f * k, 0.0f * k, 0.2f * k, 0.04f * k, 0.2f * k, W)
        }.build(),
    )

    /** Фонарик каски (светится). */
    val helmetLamp = MeshBuilder().apply {
        cylZ(0f, 0.3f * k, -0.21f * k, -0.16f * k, 0.04f * k, seg(8), 0xFF374151.toInt())
        cylZ(0f, 0.3f * k, -0.215f * k, -0.205f * k, 0.03f * k, seg(8), 0xFFFFF7C2.toInt(), glow = 1f)
    }.build()

    /** Аксессуары: 0 нет, 1 рюкзак, 2 шарф, 3 наушники, 4 маска, 5 светящийся визор, 6 цепочка. */
    val extras: Array<Mesh> = if (!hero) emptyArray() else arrayOf(
        Mesh(FloatArray(0)),
        MeshBuilder().apply {
            bevelBox(-0.15f * k, 0.12f * k, 0.12f * k, 0.15f * k, 0.48f * k, 0.27f * k, 0.05f * k, W)
            bevelBox(-0.11f * k, 0.14f * k, 0.25f * k, 0.11f * k, 0.3f * k, 0.31f * k, 0.03f * k, W)
        }.build(),
        MeshBuilder().apply {
            cylY(0f, 0f, 0.47f * k, 0.57f * k, 0.12f * k, 0.1f * k, seg(12), W)
            push()
            translate(0.07f * k, 0.5f * k, -0.1f * k)
            rotX(12f)
            bevelBox(-0.04f * k, -0.25f * k, -0.015f * k, 0.04f * k, 0f, 0.015f * k, 0.01f * k, W)
            pop()
        }.build(),
        MeshBuilder().apply {
            for (s in floatArrayOf(-1f, 1f)) {
                push()
                translate(s * 0.16f * k, 0.17f * k, 0.01f * k)
                rotZ(s * 90f)
                cylY(0f, 0f, -0.035f * k, 0.035f * k, 0.065f * k, 0.065f * k, seg(10), W)
                pop()
            }
            for (i in 0 until 7) {
                val a = (PI * i / 6).toFloat()
                box(cos(a) * 0.17f * k - 0.025f * k, 0.17f * k + sin(a) * 0.2f * k, -0.02f * k, cos(a) * 0.17f * k + 0.025f * k, 0.17f * k + sin(a) * 0.2f * k + 0.03f * k, 0.02f * k, W)
            }
        }.build(),
        MeshBuilder().apply {
            sphere(0f, 0.1f * k, -0.01f * k, 0.157f * k, 0.09f * k, 0.162f * k, seg(12), seg(6), W)
        }.build(),
        MeshBuilder().apply {
            bevelBox(-0.15f * k, 0.165f * k, -0.165f * k, 0.15f * k, 0.22f * k, -0.07f * k, 0.02f * k, W)
        }.build(),
        MeshBuilder().apply {
            for (i in 0 until 9) {
                val a = (PI * i / 8).toFloat()
                sphere(cos(a) * 0.12f * k, 0.47f * k - sin(a) * 0.12f * k, -0.135f * k, 0.018f * k, 0.018f * k, 0.018f * k, seg(5), seg(3), W)
            }
        }.build(),
    )

    // ---------- 4.12.0: части персонажей (уши, рога, нимб, корона, антенна, плащ, крылья, хвост, наплечники) ----------
    // Голова: начало - основание шеи, лицо в -z; спина - +z.

    val catEars = if (!hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.095f * k, 0.28f * k, 0.02f * k)
            rotZ(-s * 22f)
            cylY(0f, 0f, 0f, 0.14f * k, 0.062f * k, 0.004f * k, 4, W)
            pop()
        }
    }.build()
    val earsInner = if (!hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.095f * k, 0.285f * k, -0.012f * k)
            rotZ(-s * 22f)
            cylY(0f, 0f, 0f, 0.1f * k, 0.036f * k, 0.003f * k, 4, W)
            pop()
        }
    }.build()
    val horns = if (!hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.085f * k, 0.29f * k, -0.01f * k)
            rotZ(-s * 30f)
            cylY(0f, 0f, 0f, 0.08f * k, 0.042f * k, 0.03f * k, seg(8), W)
            translate(0f, 0.08f * k, 0f)
            rotZ(s * 45f)
            rotX(-20f)
            cylY(0f, 0f, 0f, 0.09f * k, 0.03f * k, 0.002f * k, seg(8), W)
            pop()
        }
    }.build()
    val halo = if (!hero) null else MeshBuilder().apply {
        val n = seg(18)
        for (i in 0 until n) {
            val a = (2 * PI * i / n).toFloat()
            sphere(cos(a) * 0.13f * k, 0.47f * k, sin(a) * 0.13f * k, 0.026f * k, 0.014f * k, 0.026f * k, 6, 3, W, glow = 1f)
        }
    }.build()
    val crown = if (!hero) null else MeshBuilder().apply {
        cylY(0f, 0f, 0.29f * k, 0.36f * k, 0.13f * k, 0.135f * k, seg(14), W)
        for (i in 0 until 6) {
            val a = (2 * PI * i / 6).toFloat()
            push()
            translate(cos(a) * 0.12f * k, 0.36f * k, sin(a) * 0.12f * k)
            cylY(0f, 0f, 0f, 0.08f * k, 0.03f * k, 0.002f * k, 5, W)
            pop()
        }
    }.build()
    val crownGems = if (!hero) null else MeshBuilder().apply {
        for (i in 0 until 6) {
            val a = (2 * PI * i / 6 + PI / 6).toFloat()
            sphere(cos(a) * 0.135f * k, 0.325f * k, sin(a) * 0.135f * k, 0.018f * k, 0.018f * k, 0.018f * k, 6, 4, W, glow = 0.6f)
        }
    }.build()
    val antenna = if (!hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.07f * k, 0.31f * k, 0.02f * k)
            rotZ(-s * 18f)
            cylY(0f, 0f, 0f, 0.17f * k, 0.012f * k, 0.01f * k, 6, W)
            pop()
        }
        // «Уши» робота - диски по бокам головы.
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.158f * k, 0.17f * k, 0f)
            rotZ(s * 90f)
            cylY(0f, 0f, -0.02f * k, 0.02f * k, 0.06f * k, 0.06f * k, seg(10), W)
            pop()
        }
    }.build()
    val antennaTips = if (!hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.07f * k, 0.31f * k, 0.02f * k)
            rotZ(-s * 18f)
            sphere(0f, 0.18f * k, 0f, 0.028f * k, 0.028f * k, 0.028f * k, 8, 5, W, glow = 1f)
            pop()
        }
    }.build()

    /** Плащ: начало - верх спины (у плеч), висит вниз по +z. */
    val cape = if (!hero) null else MeshBuilder().apply {
        bevelBox(-0.23f * k, -0.66f * k, 0f, 0.23f * k, 0f, 0.025f * k, 0.012f * k, W)
        box(-0.2f * k, -0.05f * k, -0.02f * k, 0.2f * k, 0f, 0.01f * k, W)
    }.build()

    /** Крыло (правое, [side] = 1) или левое (-1): перья-лопасти веером от спины. */
    private fun wing(side: Float) = MeshBuilder().apply {
        for (i in 0 until 5) {
            push()
            rotZ(side * (-25f + i * 18f))
            val len = (0.42f - i * 0.05f) * k
            sphere(side * len * 0.5f, 0f, 0f, len * 0.5f, 0.055f * k, 0.018f * k, seg(10), 5, W)
            pop()
        }
    }.build()
    val wingL = if (!hero) null else wing(-1f)
    val wingR = if (!hero) null else wing(1f)

    /** Хвост: начало - низ спины, изгибается назад-вверх. [bushy] - пушистый (лис). */
    private fun tail(bushy: Boolean) = MeshBuilder().apply {
        val n = 8
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val r = if (bushy) (0.05f + 0.07f * sin(f * PI.toFloat())) * k else (0.035f - 0.015f * f) * k
            sphere(0f, (-0.02f + 0.22f * f * f) * k, (0.04f + 0.36f * f) * k, r, r, r * 1.1f, seg(8), 5, W)
        }
    }.build()
    val tailThin = if (!hero) null else tail(false)
    val tailBushy = if (!hero) null else tail(true)

    /** Наплечник (на каждое плечо). */
    val pad = if (!hero) null else MeshBuilder().apply {
        sphere(0f, 0.015f * k, 0f, 0.105f * k, 0.07f * k, 0.105f * k, seg(10), seg(6), W, yFrom = 0.45f)
        box(-0.09f * k, -0.01f * k, -0.012f * k, 0.09f * k, 0.03f * k, 0.012f * k, W)
    }.build()

    // Обходчик: жилет со светоотражающими полосами, фуражка, усы, фонарь.
    val vest = if (hero) null else MeshBuilder().apply {
        bevelBox(-0.245f * k, 0.12f * k, -0.145f * k, 0.245f * k, 0.52f * k, 0.145f * k, 0.06f * k, 0xFFFF7A1A.toInt())
        for (y in floatArrayOf(0.22f, 0.38f)) box(-0.25f * k, y * k, -0.15f * k, 0.25f * k, (y + 0.04f) * k, 0.15f * k, 0xFFF1F5F9.toInt(), glow = 0.9f)
    }.build()
    val cap = if (hero) null else MeshBuilder().apply {
        cylY(0f, 0f, 0.27f * k, 0.37f * k, 0.165f * k, 0.18f * k, seg(14), 0xFF1E293B.toInt())
        cylY(0f, 0f, 0.37f * k, 0.39f * k, 0.18f * k, 0.17f * k, seg(14), 0xFF1E293B.toInt())
        box(-0.17f * k, 0.3f * k, -0.172f * k, 0.17f * k, 0.33f * k, -0.165f * k, 0xFFFACC15.toInt())
        push()
        translate(0f, 0.28f * k, -0.16f * k)
        rotX(-15f)
        bevelBox(-0.13f * k, -0.012f * k, -0.11f * k, 0.13f * k, 0.012f * k, 0.02f * k, 0.008f * k, 0xFF0F172A.toInt())
        pop()
        sphere(0f, 0.355f * k, -0.17f * k, 0.03f * k, 0.03f * k, 0.01f * k, seg(8), seg(4), 0xFFFACC15.toInt(), glow = 0.5f)
    }.build()
    val mustache = if (hero) null else MeshBuilder().apply {
        for (s in floatArrayOf(-1f, 1f)) {
            push()
            translate(s * 0.035f * k, 0.115f * k, -0.155f * k)
            rotZ(s * 15f)
            bevelBox(-0.04f * k, -0.012f * k, -0.012f * k, 0.04f * k, 0.012f * k, 0.012f * k, 0.008f * k, 0xFF4A3426.toInt())
            pop()
        }
    }.build()
    // Зимой обходчик - Дед Мороз: колпак с помпоном, борода, белая опушка и пояс с пряжкой.
    val santaHat = if (hero) null else MeshBuilder().apply {
        cylY(0f, 0.01f * k, 0.24f * k, 0.31f * k, 0.18f * k, 0.18f * k, 14, 0xFFF8FAFC.toInt())
        push()
        translate(0f, 0.3f * k, 0.02f * k)
        rotX(18f)
        cylY(0f, 0f, 0f, 0.3f * k, 0.16f * k, 0.02f * k, 12, 0xFFDC2626.toInt())
        sphere(0f, 0.31f * k, 0f, 0.05f * k, 0.05f * k, 0.05f * k, 8, 5, 0xFFF8FAFC.toInt())
        pop()
    }.build()
    val beard = if (hero) null else MeshBuilder().apply {
        sphere(0f, 0.06f * k, -0.1f * k, 0.13f * k, 0.13f * k, 0.08f * k, 10, 6, 0xFFF8FAFC.toInt())
        sphere(0f, -0.02f * k, -0.08f * k, 0.1f * k, 0.12f * k, 0.07f * k, 10, 6, 0xFFF1F5F9.toInt())
        for (sd in floatArrayOf(-1f, 1f)) sphere(sd * 0.045f * k, 0.115f * k, -0.155f * k, 0.045f * k, 0.018f * k, 0.02f * k, 6, 4, 0xFFF8FAFC.toInt())
    }.build()
    val santaTrim = if (hero) null else MeshBuilder().apply {
        // Пояс и пряжка.
        box(-0.235f * k, 0.12f * k, -0.14f * k, 0.235f * k, 0.18f * k, 0.14f * k, 0xFF111827.toInt())
        box(-0.05f * k, 0.115f * k, -0.147f * k, 0.05f * k, 0.185f * k, -0.14f * k, 0xFFFACC15.toInt(), glow = 0.3f)
        // Белая опушка по центру и внизу куртки.
        box(-0.035f * k, 0.19f * k, -0.142f * k, 0.035f * k, 0.52f * k, -0.135f * k, 0xFFF8FAFC.toInt())
        box(-0.24f * k, 0.0f, -0.145f * k, 0.24f * k, 0.05f * k, 0.145f * k, 0xFFF8FAFC.toInt())
    }.build()

    /** Фонарь в руке - вдоль -y кисти (луч светит туда же). */
    val flashlight = if (hero) null else MeshBuilder().apply {
        cylY(0f, 0f, -0.16f * k, 0.06f * k, 0.03f * k, 0.03f * k, seg(8), 0xFF1F2937.toInt())
        cylY(0f, 0f, -0.22f * k, -0.16f * k, 0.05f * k, 0.03f * k, seg(8), 0xFF9CA3AF.toInt())
        cylY(0f, 0f, -0.225f * k, -0.22f * k, 0.045f * k, 0.045f * k, seg(8), 0xFFFFF7C2.toInt(), glow = 1f)
    }.build()
}
