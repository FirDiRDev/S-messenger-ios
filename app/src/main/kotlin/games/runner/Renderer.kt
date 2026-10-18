// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Сетки мира собираются один раз в фоне и переиспользуются между запусками игры. */
internal object ModelCache {
    @Volatile var models: Models? = null
        private set
    @Volatile private var builtLite = false
    @Volatile private var building = false

    fun prepare(lite: Boolean) {
        if (building) return
        if (models != null && builtLite == lite) return
        building = true
        Thread {
            try {
                val m = Models(lite)
                builtLite = lite
                models = m
            } catch (_: Throwable) {
            } finally {
                building = false
            }
        }.apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }
}

/**
 * 3D-отрисовка «Бега по рельсам» на OpenGL ES 2.0: камера сзади-сверху бегуна, освещение по
 * вершинам (солнце + небо/земля + контровой свет), дымка к горизонту, небо с градиентом, солнце,
 * луна и звёзды по времени суток, свечения (фары, фонари, монеты, след облика) - аддитивно,
 * мягкие тени. Фигуры - скелет из частей с анимацией (бег, прыжок, кувырок, наклон, спотыкание,
 * падение, поимка). Обновляет мир ([World.update]) в каждом кадре.
 */
internal class RunnerRenderer(private val world: World, private val lite: Boolean) : GLSurfaceView.Renderer {
    companion object {
        @Volatile private var genCounter = 0

        private const val VS_MAIN = """
uniform mat4 uMVP;
uniform mat4 uModel;
uniform vec3 uLight;
uniform vec3 uSun;
uniform vec3 uSky;
uniform vec3 uGround;
uniform vec3 uCam;
uniform vec3 uFogP;
uniform vec3 uTint;
uniform vec3 uEmit;
uniform float uNight;
uniform float uUnlit;
uniform float uSnow;
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec4 aCol;
varying vec3 vCol;
varying float vFog;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vec3 n = normalize((uModel * vec4(aNor, 0.0)).xyz);
    float d = max(dot(n, uLight), 0.0);
    vec3 amb = mix(uGround, uSky, n.y * 0.5 + 0.5);
    vec3 base = aCol.rgb * uTint;
    // Зима: снег на всём, что смотрит вверх (земля, крыши вагонов, верх препятствий).
    base = mix(base, vec3(0.92, 0.95, 1.0), uSnow * 0.8 * smoothstep(0.6, 0.95, n.y) * (1.0 - aCol.a));
    vec3 v = normalize(uCam - wp.xyz);
    float rim = 1.0 - max(dot(n, v), 0.0);
    vec3 lit = base * (amb + uSun * d) + uSky * (rim * rim * rim * 0.22);
    lit += aCol.rgb * (aCol.a * uNight * 1.15) + uEmit;
    vCol = mix(lit, base + uEmit, uUnlit);
    float dist = length(wp.xyz - uCam);
    vFog = clamp((dist - uFogP.x) / (uFogP.y - uFogP.x), 0.0, 1.0) * uFogP.z;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val FS_MAIN = """
precision mediump float;
uniform vec3 uFogCol;
uniform float uAlpha;
varying vec3 vCol;
varying float vFog;
void main() {
    gl_FragColor = vec4(mix(vCol, uFogCol, vFog), uAlpha);
}
"""
        /**
         * Качественный режим (не на слабых телефонах): освещение по пикселям - мягкий
         * рассеянный свет, блик солнца, контровой свет по краям, мягкое сжатие ярких тонов
         * и чуть больше насыщенности. Те же uniform-ы, что и у простого режима.
         */
        private const val VS_MAIN_HQ = """
uniform mat4 uMVP;
uniform mat4 uModel;
uniform vec3 uLight;
uniform vec3 uSun;
uniform vec3 uSky;
uniform vec3 uGround;
uniform vec3 uCam;
uniform vec3 uFogP;
uniform vec3 uTint;
uniform vec3 uEmit;
uniform float uNight;
uniform float uUnlit;
uniform float uSnow;
attribute vec3 aPos;
attribute vec3 aNor;
attribute vec4 aCol;
varying vec3 vN;
varying vec3 vV;
varying vec3 vL;
varying vec3 vBase;
varying vec3 vSky;
varying vec3 vGround;
varying vec3 vSun;
varying vec4 vAdd;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vN = (uModel * vec4(aNor, 0.0)).xyz;
    vV = uCam - wp.xyz;
    vL = uLight;
    vBase = aCol.rgb * uTint;
    float up = normalize(vN).y;
    vBase = mix(vBase, vec3(0.92, 0.95, 1.0), uSnow * 0.8 * smoothstep(0.6, 0.95, up) * (1.0 - aCol.a));
    vSky = uSky;
    vGround = uGround;
    vSun = uSun;
    float dist = length(wp.xyz - uCam);
    float fog = clamp((dist - uFogP.x) / (uFogP.y - uFogP.x), 0.0, 1.0) * uFogP.z;
    vAdd = vec4(aCol.rgb * (aCol.a * uNight * 1.15) + uEmit, fog);
    vL = uUnlit > 0.5 ? vec3(0.0) : uLight;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val FS_MAIN_HQ = """
precision mediump float;
uniform vec3 uFogCol;
uniform float uAlpha;
varying vec3 vN;
varying vec3 vV;
varying vec3 vL;
varying vec3 vBase;
varying vec3 vSky;
varying vec3 vGround;
varying vec3 vSun;
varying vec4 vAdd;
void main() {
    vec3 col;
    if (dot(vL, vL) < 0.01) {
        col = vBase + vAdd.rgb;
    } else {
        vec3 n = normalize(vN);
        vec3 v = normalize(vV);
        vec3 l = normalize(vL);
        float nl = dot(n, l);
        float diff = clamp(nl * 0.85 + 0.15, 0.0, 1.0);
        vec3 amb = mix(vGround, vSky, n.y * 0.5 + 0.5);
        vec3 h = normalize(l + v);
        float spec = pow(max(dot(n, h), 0.0), 40.0) * step(0.0, nl) * 0.45;
        float rim = 1.0 - max(dot(n, v), 0.0);
        rim = rim * rim * rim;
        col = vBase * (amb + vSun * diff) + vSun * spec + vSky * (rim * 0.28) + vAdd.rgb;
        col = col / (1.0 + col * 0.12) * 1.1;
        float g = dot(col, vec3(0.299, 0.587, 0.114));
        col = mix(vec3(g), col, 1.08);
    }
    gl_FragColor = vec4(mix(col, uFogCol, vAdd.a), uAlpha);
}
"""
        private const val VS_GLOW = """
uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uCenter;
uniform vec2 uSize;
uniform float uFlat;
attribute vec2 aCorner;
varying vec2 vUV;
void main() {
    vUV = aCorner;
    vec4 vc = uView * vec4(uCenter, 1.0);
    vec4 bb = vc + vec4(aCorner * uSize, 0.0, 0.0);
    vec4 fl = uView * vec4(uCenter + vec3(aCorner.x * uSize.x, 0.0, aCorner.y * uSize.y), 1.0);
    gl_Position = uProj * mix(bb, fl, uFlat);
}
"""
        private const val FS_GLOW = """
precision mediump float;
uniform vec4 uColor;
uniform float uSharp;
varying vec2 vUV;
void main() {
    float r = length(vUV);
    float soft = 1.0 - smoothstep(0.0, 1.0, r);
    soft = soft * soft;
    float disc = 1.0 - smoothstep(0.55, 1.0, r);
    float a = mix(soft, disc, uSharp);
    gl_FragColor = vec4(uColor.rgb, uColor.a * a);
}
"""
        private const val VS_SKY = """
attribute vec2 aCorner;
varying float vY;
void main() {
    vY = aCorner.y;
    gl_Position = vec4(aCorner, 0.9999, 1.0);
}
"""
        private const val FS_SKY = """
precision mediump float;
uniform vec3 uTop;
uniform vec3 uHor;
uniform float uHorY;
varying float vY;
void main() {
    float t = clamp((vY - uHorY) / (1.0 - uHorY + 0.001), 0.0, 1.0);
    vec3 c = mix(uHor, uTop, sqrt(t));
    gl_FragColor = vec4(c, 1.0);
}
"""

        // Палитра времени суток: tod, верх неба, горизонт, солнце (цвет·яркость), небо-свет, земля-свет, ночь.
        private val KEYS = arrayOf(
            floatArrayOf(0.00f, 0.24f, 0.33f, 0.62f, 0.96f, 0.70f, 0.56f, 0.95f, 0.72f, 0.52f, 0.62f, 0.60f, 0.72f, 0.36f, 0.30f, 0.28f, 0.35f),
            floatArrayOf(0.12f, 0.25f, 0.55f, 0.90f, 0.75f, 0.88f, 1.00f, 1.00f, 0.96f, 0.86f, 0.62f, 0.70f, 0.82f, 0.44f, 0.40f, 0.34f, 0.00f),
            floatArrayOf(0.46f, 0.22f, 0.52f, 0.88f, 0.78f, 0.89f, 1.00f, 1.00f, 0.96f, 0.86f, 0.62f, 0.70f, 0.82f, 0.44f, 0.40f, 0.34f, 0.00f),
            floatArrayOf(0.58f, 0.30f, 0.25f, 0.56f, 1.00f, 0.62f, 0.42f, 1.00f, 0.66f, 0.40f, 0.62f, 0.52f, 0.66f, 0.40f, 0.30f, 0.30f, 0.40f),
            floatArrayOf(0.68f, 0.05f, 0.08f, 0.24f, 0.17f, 0.22f, 0.42f, 0.38f, 0.44f, 0.70f, 0.30f, 0.34f, 0.52f, 0.14f, 0.13f, 0.20f, 1.00f),
            floatArrayOf(0.90f, 0.04f, 0.06f, 0.20f, 0.15f, 0.20f, 0.38f, 0.36f, 0.42f, 0.68f, 0.28f, 0.32f, 0.50f, 0.13f, 0.12f, 0.19f, 1.00f),
            floatArrayOf(1.00f, 0.24f, 0.33f, 0.62f, 0.96f, 0.70f, 0.56f, 0.95f, 0.72f, 0.52f, 0.62f, 0.60f, 0.72f, 0.36f, 0.30f, 0.28f, 0.35f),
        )
    }

    private val chaserSkin = Skin(
        0, 0, 0, skin = 0xFFE0A47E.toInt(), hairColor = 0xFF3B2A20.toInt(), hair = 0, top = 0xFF1E3A5F.toInt(),
        accent = 0xFFFF7A1A.toInt(), pants = 0xFF1E293B.toInt(), shoes = 0xFF141414.toInt(), sole = 0xFF2A2A2A.toInt(),
    )

    /** Зимой обходчик - Дед Мороз: красная шуба и штаны, чёрные сапоги. */
    private val santaSkin = Skin(
        0, 0, 0, skin = 0xFFF0B79A.toInt(), hairColor = 0xFFF8FAFC.toInt(), hair = 0, top = 0xFFDC2626.toInt(),
        accent = 0xFFF8FAFC.toInt(), pants = 0xFFB91C1C.toInt(), shoes = 0xFF111827.toInt(), sole = 0xFF1F2937.toInt(),
    )

    private var gen = 0
    private var ok = false
    private var w = 1
    private var h = 1
    private var lastNs = 0L
    private var time = 0f

    // Программы и их параметры.
    private var pMain = 0
    private var uMVP = 0
    private var uModel = 0
    private var uLight = 0
    private var uSun = 0
    private var uSky = 0
    private var uGround = 0
    private var uCam = 0
    private var uFogP = 0
    private var uTint = 0
    private var uEmit = 0
    private var uNight = 0
    private var uUnlit = 0
    private var uSnow = 0
    private var uFogCol = 0
    private var uAlpha = 0
    private var pGlow = 0
    private var gView = 0
    private var gProj = 0
    private var gCenter = 0
    private var gSize = 0
    private var gFlat = 0
    private var gColor = 0
    private var gSharp = 0
    private var pSky = 0
    private var sTop = 0
    private var sHor = 0
    private var sHorY = 0
    private var quadVbo = 0
    private var current = -1

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val stack = Array(12) { FloatArray(16) }
    private var sp = 0
    private val pt = FloatArray(3)

    // Палитра кадра.
    private val top = FloatArray(3)
    private val hor = FloatArray(3)
    private val sun = FloatArray(3)
    private val amb = FloatArray(3)
    private val gnd = FloatArray(3)
    private var night = 0f

    // Камера (сглаженная).
    private var camX = 0f
    private var camY = 3.4f
    private var camBack = 9f
    private var camLookY = 1f
    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0f
    private var shake = 0f
    private var lastState = -1

    /** Положение путей на экране (0…1 по ширине) - для подсказок интерфейса. */
    @Volatile var laneScreenX = floatArrayOf(0.25f, 0.5f, 0.75f)
        private set

    @Volatile var ready = false
        private set

    // Плавные веса поз.
    private var wJump = 0f
    private var wBoard = 0f
    private var wJet = 0f

    /** «Лёг на доску» (кувырок с доской) и трюки: кикфлип доски, сальто в кедах. */
    private var wLie = 0f
    private var wasAir = false
    private var airT = 0f
    private var trickT = 0f
    private var flipSneak = false
    private val pose = Pose()
    private val cpose = Pose()

    // ------------------------------------------------------------------ жизненный цикл

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        genCounter++
        gen = genCounter
        current = -1
        ok = false
        try {
            // Сначала качественный режим; не собрался на этом GPU - простой (как раньше).
            pMain = if (!lite) runCatching { program(VS_MAIN_HQ, FS_MAIN_HQ, arrayOf("aPos", "aNor", "aCol")) }.getOrDefault(0) else 0
            if (pMain == 0) pMain = program(VS_MAIN, FS_MAIN, arrayOf("aPos", "aNor", "aCol"))
            pGlow = program(VS_GLOW, FS_GLOW, arrayOf("aCorner"))
            pSky = program(VS_SKY, FS_SKY, arrayOf("aCorner"))
            ok = pMain != 0 && pGlow != 0 && pSky != 0
        } catch (_: Throwable) {
            ok = false
        }
        if (!ok) return
        uMVP = GLES20.glGetUniformLocation(pMain, "uMVP")
        uModel = GLES20.glGetUniformLocation(pMain, "uModel")
        uLight = GLES20.glGetUniformLocation(pMain, "uLight")
        uSun = GLES20.glGetUniformLocation(pMain, "uSun")
        uSky = GLES20.glGetUniformLocation(pMain, "uSky")
        uGround = GLES20.glGetUniformLocation(pMain, "uGround")
        uCam = GLES20.glGetUniformLocation(pMain, "uCam")
        uFogP = GLES20.glGetUniformLocation(pMain, "uFogP")
        uTint = GLES20.glGetUniformLocation(pMain, "uTint")
        uEmit = GLES20.glGetUniformLocation(pMain, "uEmit")
        uNight = GLES20.glGetUniformLocation(pMain, "uNight")
        uUnlit = GLES20.glGetUniformLocation(pMain, "uUnlit")
        uSnow = GLES20.glGetUniformLocation(pMain, "uSnow")
        uFogCol = GLES20.glGetUniformLocation(pMain, "uFogCol")
        uAlpha = GLES20.glGetUniformLocation(pMain, "uAlpha")
        gView = GLES20.glGetUniformLocation(pGlow, "uView")
        gProj = GLES20.glGetUniformLocation(pGlow, "uProj")
        gCenter = GLES20.glGetUniformLocation(pGlow, "uCenter")
        gSize = GLES20.glGetUniformLocation(pGlow, "uSize")
        gFlat = GLES20.glGetUniformLocation(pGlow, "uFlat")
        gColor = GLES20.glGetUniformLocation(pGlow, "uColor")
        gSharp = GLES20.glGetUniformLocation(pGlow, "uSharp")
        sTop = GLES20.glGetUniformLocation(pSky, "uTop")
        sHor = GLES20.glGetUniformLocation(pSky, "uHor")
        sHorY = GLES20.glGetUniformLocation(pSky, "uHorY")
        val q = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        val fb = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(q).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, q.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = max(1, width)
        h = max(1, height)
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        time += dt
        world.update(dt)
        if (!ok) {
            GLES20.glClearColor(0.05f, 0.07f, 0.15f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }
        palette()
        camera(dt)
        GLES20.glClearColor(hor[0], hor[1], hor[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawSky()
        val m = ModelCache.models ?: return
        ready = true
        drawWorld(m, dt)
    }

    // ------------------------------------------------------------------ шейдеры

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        if (s == 0) return 0
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            android.util.Log.w("Runner3D", "shader: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun program(vs: String, fs: String, attrs: Array<String>): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        for (i in attrs.indices) GLES20.glBindAttribLocation(p, i, attrs[i])
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            android.util.Log.w("Runner3D", "link: " + GLES20.glGetProgramInfoLog(p))
            return 0
        }
        return p
    }

    private fun use(p: Int) {
        if (current == p) return
        GLES20.glUseProgram(p)
        if (p == pMain) {
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glEnableVertexAttribArray(2)
        } else {
            GLES20.glDisableVertexAttribArray(1)
            GLES20.glDisableVertexAttribArray(2)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        }
        current = p
    }

    private fun upload(m: Mesh) {
        m.gen = gen
        m.vbo = 0
        if (m.count == 0) return
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val fb = ByteBuffer.allocateDirect(m.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(m.data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        m.vbo = ids[0]
    }

    // ------------------------------------------------------------------ палитра и камера

    private fun palette() {
        val t = world.tod
        var i = 0
        while (i < KEYS.size - 2 && t > KEYS[i + 1][0]) i++
        val a = KEYS[i]
        val b = KEYS[i + 1]
        var f = ((t - a[0]) / (b[0] - a[0]).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        f = f * f * (3 - 2 * f)
        fun lerp(k: Int) = a[k] + (b[k] - a[k]) * f
        for (c in 0 until 3) {
            top[c] = lerp(1 + c)
            hor[c] = lerp(4 + c)
            sun[c] = lerp(7 + c)
            amb[c] = lerp(10 + c)
            gnd[c] = lerp(13 + c)
        }
        night = lerp(16)
        if (world.winter) {
            // Зимнее небо: холоднее и светлее, снег подсвечивает всё снизу.
            val k = 0.45f * (1f - night * 0.6f)
            val wh = floatArrayOf(0.80f, 0.87f, 0.96f)
            val wt = floatArrayOf(0.42f, 0.56f, 0.78f)
            for (c in 0 until 3) {
                hor[c] += (wh[c] - hor[c]) * k
                top[c] += (wt[c] - top[c]) * k * 0.8f
                gnd[c] += (0.62f + 0.08f * c - gnd[c]) * k
                amb[c] += ((if (c == 2) 0.86f else 0.74f) - amb[c]) * k * 0.5f
            }
        } else if (world.season != RunnerSeason.NONE) {
            // Весна - нежно-розовый горизонт, лето - яркое синее небо и жаркое солнце,
            // осень - золотистый закатный свет.
            val k = 0.32f * (1f - night * 0.7f)
            val (h, tp) = when (world.season) {
                RunnerSeason.SPRING -> floatArrayOf(0.99f, 0.84f, 0.92f) to floatArrayOf(0.55f, 0.70f, 0.96f)
                RunnerSeason.SUMMER -> floatArrayOf(0.78f, 0.92f, 1.0f) to floatArrayOf(0.22f, 0.52f, 0.96f)
                else -> floatArrayOf(1.0f, 0.76f, 0.48f) to floatArrayOf(0.52f, 0.44f, 0.62f)
            }
            for (c in 0 until 3) {
                hor[c] += (h[c] - hor[c]) * k
                top[c] += (tp[c] - top[c]) * k
            }
            if (world.season == RunnerSeason.SUMMER) for (c in 0 until 3) sun[c] *= 1.12f
            if (world.season == RunnerSeason.AUTUMN) {
                sun[0] *= 1.08f
                sun[2] *= 0.85f
            }
        }
    }

    private fun camera(dt: Float) {
        val st = world.state
        if (st != lastState) {
            if (st == World.DYING) shake = 0.35f
            lastState = st
        }
        val k = min(1f, dt * 5f)
        val aspect = w.toFloat() / h
        val fov = if (aspect < 1f) 64f else 50f
        M4.perspective(proj, fov, aspect, 0.35f, 270f)
        val menu = st == World.MENU
        if (menu && world.showcase) {
            // Витрина облика: камера спереди, фигура в верхней половине экрана.
            eyeX = 0.6f
            eyeY = 1.1f
            eyeZ = -3.9f
            M4.lookAt(view, eyeX, eyeY, eyeZ, 0f, 0.2f, 0f)
        } else if (menu) {
            eyeX = 2.2f
            eyeY = 2.5f
            eyeZ = 6.8f
            M4.lookAt(view, eyeX, eyeY, eyeZ, 0f, 1.1f, -2.5f)
            camX = 0f
            camY = 3.4f
            camBack = 9.5f
        } else {
            val py = world.py
            val ground = if (world.power[World.P_JET] > 0f) py else min(py, max(world.groundY(), py * 0.55f))
            val ty = 3.25f + ground * 0.8f
            camY += (ty - camY) * min(1f, dt * 3.5f)
            camX += (world.px * 0.8f - camX) * k
            val wantBack = when {
                st == World.COUNT && world.runTime == 0f -> 8.6f
                st == World.DYING || st == World.OVER -> 7.4f
                world.stumbleT > 0f -> 6.4f
                else -> 5.7f
            }
            camBack += (wantBack - camBack) * min(1f, dt * 2.2f)
            camLookY += ((ground + 0.6f) - camLookY) * min(1f, dt * 4f)
            var sx = 0f
            var sy = 0f
            if (shake > 0f) {
                shake -= dt
                val s = shake * 0.5f
                sx = sin(time * 61f) * s
                sy = cos(time * 53f) * s
            }
            eyeX = camX + sx
            eyeY = camY + sy
            eyeZ = camBack
            M4.lookAt(view, eyeX, eyeY, eyeZ, camX * 0.92f, camLookY, -10f)
        }
        M4.mul(vp, proj, view)
        // Пути на экране (для подсказок «встречный поезд»).
        val ls = FloatArray(3)
        for (l in 0..2) {
            M4.point(vp, (l - 1) * RW.LW, 1f, -35f, pt)
            val wv = vp[3] * (l - 1) * RW.LW + vp[7] * 1f + vp[11] * -35f + vp[15]
            ls[l] = if (abs(wv) > 1e-4f) (pt[0] / wv) * 0.5f + 0.5f else 0.5f
        }
        laneScreenX = ls
    }

    private fun drawSky() {
        use(pSky)
        // Высота горизонта на экране: дальняя точка прямо по ходу.
        M4.point(vp, eyeX, eyeY, eyeZ - 1000f, pt)
        val wv = vp[3] * eyeX + vp[7] * eyeY + vp[11] * (eyeZ - 1000f) + vp[15]
        val horY = if (wv > 1e-4f) (pt[1] / wv).coerceIn(-1.5f, 0.98f) else -0.2f
        GLES20.glUniform3f(sTop, top[0], top[1], top[2])
        GLES20.glUniform3f(sHor, hor[0], hor[1], hor[2])
        GLES20.glUniform1f(sHorY, horY)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        // Солнце / луна, звёзды, облака - далеко впереди, едут с камерой.
        use(pGlow)
        GLES20.glUniformMatrix4fv(gView, 1, false, view, 0)
        GLES20.glUniformMatrix4fv(gProj, 1, false, proj, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        val t = world.tod
        if (night < 0.6f) {
            // Солнце: днём высоко, на закате/рассвете - у горизонта.
            val el = when {
                t < 0.12f -> 8f + t / 0.12f * 40f
                t < 0.46f -> 48f
                t < 0.62f -> 48f - (t - 0.46f) / 0.16f * 44f
                else -> 4f
            }
            val sy = eyeY - 2f + el * 2.2f
            val a = 1f - night / 0.6f
            glow(eyeX + 55f, sy, eyeZ - 200f, 16f, 1f, 0.95f, 0.8f, 0.9f * a, sharp = 1f)
            glow(eyeX + 55f, sy, eyeZ - 199f, 60f, sun[0], sun[1] * 0.9f, sun[2] * 0.7f, 0.5f * a)
        }
        if (night > 0.3f) {
            val a = ((night - 0.3f) / 0.7f).coerceIn(0f, 1f)
            glow(eyeX - 45f, eyeY + 70f, eyeZ - 200f, 10f, 0.92f, 0.95f, 1f, a, sharp = 1f)
            glow(eyeX - 45f, eyeY + 70f, eyeZ - 199f, 34f, 0.5f, 0.6f, 0.9f, 0.4f * a)
            if (!lite) {
                for (i in 0 until 40) {
                    val sx = ((i * 73 % 101) / 101f - 0.5f) * 520f
                    val sy = 30f + (i * 37 % 89) / 89f * 160f
                    val tw = 0.6f + 0.4f * sin(time * (1.5f + i % 5) + i)
                    glow(eyeX + sx, eyeY + sy, eyeZ - 210f, 1.6f, 1f, 1f, 1f, a * tw)
                }
            }
        }
        if (!lite && night < 0.8f) {
            val a = 0.32f * (1f - night)
            for (i in 0 until 6) {
                val cx = ((i * 97 % 113) / 113f - 0.5f) * 420f + ((time * 1.5f + i * 40f) % 120f) - 60f
                val cy = 45f + (i * 29 % 31) * 2.2f
                glow(eyeX + cx, eyeY + cy, eyeZ - 205f, 40f, 1f, 1f, 1f, a)
                glow(eyeX + cx + 22f, eyeY + cy - 4f, eyeZ - 205f, 30f, 1f, 1f, 1f, a * 0.8f)
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ------------------------------------------------------------------ мир

    private fun beginMain() {
        use(pMain)
        // Свет - сзади-слева-сверху (освещены лица предметов, обращённые к камере).
        val lx = -0.4f
        val ly = 0.7f
        val lz = 0.6f
        val l = sqrt(lx * lx + ly * ly + lz * lz)
        GLES20.glUniform3f(uLight, lx / l, ly / l, lz / l)
        GLES20.glUniform3f(uSun, sun[0] * 0.6f, sun[1] * 0.6f, sun[2] * 0.6f)
        GLES20.glUniform3f(uSky, amb[0] * 0.8f, amb[1] * 0.8f, amb[2] * 0.8f)
        GLES20.glUniform3f(uGround, gnd[0] * 0.8f, gnd[1] * 0.8f, gnd[2] * 0.8f)
        GLES20.glUniform3f(uCam, eyeX, eyeY, eyeZ)
        GLES20.glUniform3f(uFogCol, hor[0], hor[1], hor[2])
        GLES20.glUniform1f(uNight, night)
        GLES20.glUniform1f(uUnlit, 0f)
        GLES20.glUniform1f(uSnow, if (world.winter) 1f else 0f)
        GLES20.glUniform1f(uAlpha, 1f)
        fog(1f)
        tint(1f)
        emit(0f, 0f, 0f)
    }

    private var fogMax = -1f
    private fun fog(maxF: Float) {
        if (maxF == fogMax) return
        fogMax = maxF
        val far = world.viewDist
        GLES20.glUniform3f(uFogP, far * 0.3f, far * 0.98f, maxF)
    }

    private fun tint(v: Float) = GLES20.glUniform3f(uTint, v, v, v)
    private fun tintC(c: Int, k: Float = 1f) =
        GLES20.glUniform3f(uTint, (c shr 16 and 0xFF) / 255f * k, (c shr 8 and 0xFF) / 255f * k, (c and 0xFF) / 255f * k)

    private fun emit(r: Float, g: Float, b: Float) = GLES20.glUniform3f(uEmit, r, g, b)
    private fun emitC(c: Int, k: Float) = emit((c shr 16 and 0xFF) / 255f * k, (c shr 8 and 0xFF) / 255f * k, (c and 0xFF) / 255f * k)

    /** Нарисовать сетку с матрицей [model]. */
    private fun draw(m: Mesh, mm: FloatArray = model) {
        if (m.gen != gen) upload(m)
        if (m.count == 0 || m.vbo == 0) return
        M4.mul(mvp, vp, mm)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, mm, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 40, 0)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 40, 12)
        GLES20.glVertexAttribPointer(2, 4, GLES20.GL_FLOAT, false, 40, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count)
    }

    private fun at(x: Float, y: Float, z: Float): FloatArray {
        M4.identity(model)
        M4.translate(model, x, y, z)
        return model
    }

    /** Мировая точка пути [s] → z камеры (бегун в z = 0, вперёд - минус). */
    private fun zOf(s: Float) = -(s - world.ps)

    private fun glow(x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float, flat: Boolean = false, sharp: Float = 0f, sizeZ: Float = size) {
        if (a <= 0.003f) return
        GLES20.glUniform3f(gCenter, x, y, z)
        GLES20.glUniform2f(gSize, size, sizeZ)
        GLES20.glUniform1f(gFlat, if (flat) 1f else 0f)
        GLES20.glUniform4f(gColor, r, g, b, a)
        GLES20.glUniform1f(gSharp, sharp)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    private fun glowC(x: Float, y: Float, z: Float, size: Float, c: Int, a: Float, flat: Boolean = false, sizeZ: Float = size) =
        glow(x, y, z, size, (c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, a, flat, 0f, sizeZ)

    // Отложенные свечения (рисуются после непрозрачного).
    // 4.10.0: запас побольше - зимой гирлянды на поездах, станциях, мостах и в тоннелях.
    private val glows = FloatArray(8192)
    private val glowCol = IntArray(1024)
    private var gn = 0
    private fun addGlow(x: Float, y: Float, z: Float, size: Float, c: Int, a: Float, flat: Float = 0f, sizeZ: Float = size) {
        if (gn + 8 > glows.size || a <= 0.003f) return
        glows[gn] = x
        glows[gn + 1] = y
        glows[gn + 2] = z
        glows[gn + 3] = size
        glowCol[gn / 8] = c
        glows[gn + 5] = a
        glows[gn + 6] = flat
        glows[gn + 7] = sizeZ
        gn += 8
    }

    // Отложенные тени (x, y, z, размер, сила).
    private val shadows = FloatArray(64)
    private var sn = 0
    private fun addShadow(x: Float, y: Float, z: Float, size: Float, a: Float) {
        if (sn + 5 > shadows.size) return
        shadows[sn] = x
        shadows[sn + 1] = y
        shadows[sn + 2] = z
        shadows[sn + 3] = size
        shadows[sn + 4] = a
        sn += 5
    }

    private fun drawWorld(m: Models, dt: Float) {
        gn = 0
        sn = 0
        fogMax = -1f
        beginMain()
        val ps = world.ps
        val st = world.state

        // Город вдали.
        fog(0.72f)
        tint(0.85f + 0.15f * (1f - night))
        draw(m.skyline, at(eyeX * 0.6f, -1f, 0f))
        fog(1f)

        // Куски пути.
        val k0 = kotlin.math.floor((ps - 14f) / RW.CH).toInt()
        val k1 = kotlin.math.floor((ps + world.viewDist) / RW.CH).toInt()
        for (k in k0..k1) {
            val c = world.chunk(k)
            val z = zOf(k * RW.CH)
            at(0f, 0f, z)
            when (c.kind) {
                1 -> {
                    tint(0.62f)
                    draw(m.track)
                    draw(m.tunnel)
                    if (c.portal) {
                        tint(1f)
                        draw(m.portal)
                    }
                    tint(1f)
                    for (lz in floatArrayOf(-3f, -9f)) {
                        addGlow(-4.25f, 3.72f, z + lz, 1.3f, 0xFFFFE9B0.toInt(), 0.55f)
                        addGlow(4.25f, 3.72f, z + lz, 1.3f, 0xFFFFE9B0.toInt(), 0.55f)
                    }
                    if (c.portal) addGlow(0f, 10f, z + 0.6f, 2.5f, 0xFFFDE68A.toInt(), 0.35f * night)
                    if (world.winter) winterChunk(1, k, z)
                }
                2 -> {
                    draw(m.trackBridge)
                    draw(m.bridge)
                    if (world.winter) winterChunk(2, k, z)
                }
                3 -> {
                    draw(m.track)
                    draw(m.stationL.mesh)
                    draw(m.stationR.mesh)
                    lampGlows(m.stationL, z)
                    lampGlows(m.stationR, z)
                    if (world.winter) winterChunk(3, k, z)
                }
                else -> {
                    draw(m.track)
                    val l = m.sidesL[(c.left).mod(m.sidesL.size)]
                    val r = m.sidesR[(c.right).mod(m.sidesR.size)]
                    draw(l.mesh)
                    draw(r.mesh)
                    lampGlows(l, z)
                    lampGlows(r, z)
                    when (world.season) {
                        RunnerSeason.WINTER -> winterDecor(m, k, z)
                        RunnerSeason.SPRING, RunnerSeason.SUMMER, RunnerSeason.AUTUMN -> seasonDecor(m, k, z)
                    }
                }
            }
        }

        // Предметы.
        for (o in world.objs) {
            val z = zOf(o.s)
            if (z < -world.viewDist - 2f) continue
            if (o.type != RObj.TRAIN && o.type != RObj.RAMP && z > 8f) continue
            if (o.type == RObj.TRAIN && z - o.len > 8f) continue
            val light = world.lightAt(o.s)
            when (o.type) {
                RObj.TRAIN -> drawTrain(m, o, z, light)
                RObj.RAMP -> {
                    tint(light)
                    draw(m.ramp, at(o.x, 0f, z))
                }
                RObj.HURDLE, RObj.BAR, RObj.BLOCK, RObj.CONES -> drawBarrier(m, o, z, light)
                RObj.COIN -> if (!o.taken) {
                    val mm = at(o.x, o.y, z)
                    M4.rotY(mm, time * 200f + o.s * 13f)
                    tint(1f)
                    emit(0.32f, 0.22f, 0.02f)
                    draw(m.coin, mm)
                    emit(0f, 0f, 0f)
                    if (!lite && z > -40f) addGlow(o.x, o.y, z + 0.1f, 0.55f, 0xFFFFE27A.toInt(), 0.18f)
                }
                RObj.POWER -> if (!o.taken) {
                    val bob = sin(time * 3f + o.s) * 0.15f
                    val mm = at(o.x, o.y + bob, z)
                    M4.rotY(mm, time * 120f)
                    tint(1f)
                    emit(0.12f, 0.12f, 0.12f)
                    m.power.getOrNull(o.kind)?.let { draw(it, mm) }
                    emit(0f, 0f, 0f)
                    addGlow(o.x, o.y + bob, z + 0.2f, 1.25f, POWER_COLOR.getOrElse(o.kind) { 0xFFFFFFFF.toInt() }, 0.55f + 0.15f * sin(time * 6f))
                }
            }
        }

        // Фигуры.
        drawPlayer(m, dt)
        if (!(st == World.MENU && world.showcase)) drawChaser(m)
        tint(1f)
        emit(0f, 0f, 0f)

        // Тени (обычное смешивание), затем свечения (сложение).
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        use(pGlow)
        GLES20.glUniformMatrix4fv(gView, 1, false, view, 0)
        GLES20.glUniformMatrix4fv(gProj, 1, false, proj, 0)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        var i = 0
        while (i < sn) {
            glow(shadows[i], shadows[i + 1], shadows[i + 2], shadows[i + 3], 0f, 0f, 0f, shadows[i + 4], flat = true, sharp = 1f, sizeZ = shadows[i + 3] * 0.8f)
            i += 5
        }
        // Листья (осень) и лепестки (весна) - обычным смешиванием: видны и на светлом небе.
        if (world.season == RunnerSeason.AUTUMN || world.season == RunnerSeason.SPRING) drawSnow(dt)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        // Снегопад (зимний режим): хлопья летят навстречу, кружатся и падают.
        if (world.season == RunnerSeason.WINTER || world.season == RunnerSeason.SUMMER) drawSnow(dt)
        // Частицы.
        for (p in world.parts) {
            val a = 1f - p.age / p.life
            glowC(p.x, p.y, zOf(p.s), p.size * (0.6f + a * 0.6f), p.color, a * 0.9f)
        }
        i = 0
        while (i < gn) {
            glowC(glows[i], glows[i + 1], glows[i + 2], glows[i + 3], glowCol[i / 8], glows[i + 5], glows[i + 6] > 0.5f, glows[i + 7])
            i += 8
        }
        // Луч фонаря обходчика (сетка-конус, без света и дымки).
        if (coneValid) {
            use(pMain)
            GLES20.glUniform1f(uUnlit, 1f)
            fog(0f)
            tint(0.32f + 0.25f * night)
            emit(0f, 0f, 0f)
            draw(m.flashCone, coneM)
            GLES20.glUniform1f(uUnlit, 0f)
            fog(1f)
            tint(1f)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    private val flakeN = if (lite) 60 else 170
    private val fx = FloatArray(flakeN)
    private val fy = FloatArray(flakeN)
    private val fz = FloatArray(flakeN)
    private val fs = FloatArray(flakeN)
    private val fph = FloatArray(flakeN)
    private var flakesReady = false
    private val snowRnd = java.util.Random(7)

    private val fcol = IntArray(flakeN)
    private var flakeSeason = -1

    private fun respawnFlake(i: Int, anywhere: Boolean) {
        // Хлопья (и листья) висят в мире, а не «приклеены» к бегуну: прыжок в сторону или
        // вверх не сдвигает небо вместе с ним.
        fx[i] = (snowRnd.nextFloat() - 0.5f) * 22f
        fcol[i] = when (world.season) {
            RunnerSeason.AUTUMN -> intArrayOf(0xFFF97316.toInt(), 0xFFDC2626.toInt(), 0xFFFBBF24.toInt(), 0xFFB45309.toInt())[snowRnd.nextInt(4)]
            RunnerSeason.SPRING -> intArrayOf(0xFFF9A8D4.toInt(), 0xFFFBCFE8.toInt(), 0xFFFFFFFF.toInt())[snowRnd.nextInt(3)]
            RunnerSeason.SUMMER -> intArrayOf(0xFFFDE047.toInt(), 0xFFFEF3C7.toInt(), 0xFF86EFAC.toInt())[snowRnd.nextInt(3)]
            else -> 0xFFFFFFFF.toInt()
        }
        fy[i] = if (anywhere) snowRnd.nextFloat() * 10f else 8f + snowRnd.nextFloat() * 3f
        fz[i] = if (anywhere) 4f - snowRnd.nextFloat() * 60f else -20f - snowRnd.nextFloat() * 40f
        fs[i] = 0.05f + snowRnd.nextFloat() * 0.08f
        fph[i] = snowRnd.nextFloat() * 6.28f
    }

    private fun drawSnow(dt: Float) {
        val season = world.season
        if (!flakesReady || flakeSeason != season) {
            flakeSeason = season
            for (i in 0 until flakeN) respawnFlake(i, true)
            flakesReady = true
        }
        val run = if (world.state == World.RUN || world.state == World.DYING) world.speed else 0f
        val bright = 0.75f + 0.25f * (1f - night)
        // Сколько частиц: снег - все, листья и лепестки - меньше, летние искорки - ещё меньше.
        val n = when (season) {
            RunnerSeason.WINTER -> flakeN
            RunnerSeason.SUMMER -> flakeN / 3
            else -> flakeN / 2
        }
        for (i in 0 until n) {
            when (season) {
                RunnerSeason.WINTER -> {
                    fy[i] -= (1.1f + fs[i] * 6f) * dt
                    fx[i] += sin(time * 1.3f + fph[i]) * 0.5f * dt
                }
                RunnerSeason.SUMMER -> {
                    // Искорки и пыльца парят и мерцают.
                    fy[i] += sin(time * 0.8f + fph[i]) * 0.25f * dt
                    fx[i] += cos(time * 0.6f + fph[i]) * 0.3f * dt
                }
                else -> {
                    // Листья и лепестки падают медленно и сильно раскачиваются.
                    fy[i] -= (0.55f + fs[i] * 3f) * dt
                    fx[i] += sin(time * 1.8f + fph[i]) * 1.4f * dt
                }
            }
            fz[i] += run * dt * 0.92f
            if (fy[i] < 0f || fz[i] > 5f) respawnFlake(i, false)
            val c = fcol[i]
            val r = (c shr 16 and 0xFF) / 255f * bright
            val g = (c shr 8 and 0xFF) / 255f * bright
            val b = (c and 0xFF) / 255f * bright
            when (season) {
                RunnerSeason.WINTER -> glow(fx[i], fy[i], fz[i], fs[i], bright, bright, bright, 0.85f, sharp = 0.7f)
                RunnerSeason.SUMMER -> glow(fx[i], fy[i], fz[i], fs[i] * 0.8f, r, g, b, 0.45f + 0.45f * sin(time * 4f + fph[i] * 3f).coerceAtLeast(0f), sharp = 0.2f)
                else -> {
                    // Лист «кувыркается»: то узкий, то широкий.
                    val flip = 0.45f + 0.55f * kotlin.math.abs(sin(time * 2.6f + fph[i]))
                    glow(fx[i], fy[i], fz[i], fs[i] * 2.1f * flip, r, g, b, 0.9f, sharp = 1f, sizeZ = fs[i] * 2.1f)
                }
            }
        }
    }

    private val bulbCols = intArrayOf(0xFFEF4444.toInt(), 0xFFFDE047.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt())

    /** Зимой у путей: ёлки с игрушками и звездой, гирлянды вдоль ограждений. */
    private fun winterDecor(m: Models, k: Int, z: Float) {
        if (k % 2 == 0) {
            val side = if ((k / 2) % 2 == 0) -1f else 1f
            val tx = side * 6.4f
            val tz = z - 6f
            tint(1f)
            draw(m.xmasTree, at(tx, 0f, tz))
            addGlow(tx, 3.45f, tz, 0.55f + 0.08f * sin(time * 3f + k), 0xFFFDE047.toInt(), 0.9f)
            for (i in 0 until 5) {
                val a = i * 1.26f + k
                val y = 0.9f + i * 0.5f
                val r = 0.95f - i * 0.15f
                val on = ((time * 2f + i + k).toInt() % 3) != 0
                addGlow(tx + cos(a) * r, y, tz + sin(a) * r * 0.6f + 0.35f, 0.22f, bulbCols[(i + k).mod(bulbCols.size)], if (on) 0.8f else 0.25f)
            }
        }
        // Гирлянда вдоль ограждения - только вблизи (экономим кадры).
        if (!lite && z > -70f) {
            for (side in floatArrayOf(-1f, 1f)) for (i in 0 until 6) {
                val bz = z - 1f - i * 2f
                val c = bulbCols[(i + k * 2 + (if (side > 0) 2 else 0)).mod(bulbCols.size)]
                val tw = 0.45f + 0.45f * sin(time * 3f + i * 1.7f + k).coerceAtLeast(0f)
                addGlow(side * 4.83f, 2.9f - 0.12f * sin((i % 2) * 3.14f + 1.57f), bz, 0.24f, c, tw)
            }
        }
    }

    /**
     * Убранство остальных сезонов: весной - сакура, клумбы и пастельные фонарики, летом -
     * пальмы, пляжные зонтики и шезлонги, осенью - клёны, тыквы-светильники и тюки сена.
     */
    private fun seasonDecor(m: Models, k: Int, z: Float) {
        val side = if ((k / 2) % 2 == 0) -1f else 1f
        val tz = z - 6f
        tint(1f)
        when (world.season) {
            RunnerSeason.SPRING -> {
                if (k % 2 == 0) draw(m.sakura, at(side * 6.4f, 0f, tz))
                else draw(m.flowerBed, at(-side * 5.6f, 0f, z - 4f))
                if (!lite && z > -70f) {
                    // Бумажные фонарики пастельных цветов вдоль ограждения.
                    for (sd in floatArrayOf(-1f, 1f)) for (i in 0 until 4) {
                        val c = springCols[(i + k).mod(springCols.size)]
                        addGlow(sd * 4.83f, 2.75f + 0.08f * sin(time * 2f + i), z - 1.5f - i * 3f, 0.3f, c, 0.35f + 0.35f * night)
                    }
                }
            }
            RunnerSeason.SUMMER -> {
                if (k % 2 == 0) {
                    val mm = at(side * 6.6f, 0f, tz)
                    if (side < 0f) M4.rotY(mm, 180f)
                    draw(m.palm, mm)
                } else {
                    draw(m.umbrella, at(-side * 6.2f, 0f, z - 5f))
                }
                // Солнечные блики днём и светлячки ночью.
                if (!lite && z > -60f) for (i in 0 until 3) {
                    val a = time * 0.8f + i * 2.1f + k
                    val c = if (night > 0.4f) 0xFFFDE68A.toInt() else 0xFFFFFFFF.toInt()
                    addGlow(side * (5.6f + 0.6f * sin(a)), 1.2f + 0.6f * sin(a * 1.3f), z - 2f - i * 3f, 0.18f, c, if (night > 0.4f) 0.8f else 0.25f)
                }
            }
            else -> {
                if (k % 2 == 0) draw(m.mapleTree, at(side * 6.4f, 0f, tz))
                else draw(m.hay, at(-side * 6.0f, 0f, z - 5f))
                val pz = z - 2.5f
                draw(m.pumpkins, at(side * 5.4f, 0f, pz))
                // Тыквы светятся изнутри, как фонари (вечером - ярче).
                val fl = 0.75f + 0.25f * sin(time * 9f + k * 1.7f)
                addGlow(side * 5.4f, 0.4f, pz, 0.7f, 0xFFFF8A1F.toInt(), (0.25f + 0.55f * night) * fl)
                addGlow(side * 5.4f + 0.3f, 0.3f, pz + 0.75f, 0.5f, 0xFFFFB020.toInt(), (0.2f + 0.45f * night) * fl)
            }
        }
    }

    private val springCols = intArrayOf(0xFFF9A8D4.toInt(), 0xFFFDE68A.toInt(), 0xFFA5F3FC.toInt(), 0xFFC4B5FD.toInt())

    private fun lampGlows(s: SideModel, z: Float) {
        if (night < 0.15f) return
        val l = s.lamps
        var i = 0
        while (i + 2 < l.size) {
            val lz = z + l[i + 2]
            if (lz > -world.viewDist * 0.8f) {
                addGlow(l[i], l[i + 1], lz, 1.6f, 0xFFFFE2A0.toInt(), 0.5f * night)
                if (!lite) addGlow(l[i], 0.06f, lz, 2.6f, 0xFFFFD58A.toInt(), 0.16f * night, flat = 1f)
            }
            i += 3
        }
    }

    private fun drawTrain(m: Models, o: RObj, z: Float, light: Float) {
        val moving = o.v > 0f
        val scheme = if (moving) o.style % 4 else o.style % 5
        tint(light)
        for (i in 0 until o.cars) {
            val cz = z - i * (RW.CAR_LEN + RW.CAR_GAP)
            if (cz < -world.viewDist - 2f) break
            if (cz - RW.CAR_LEN > 10f) continue
            val mesh = when {
                i > 0 -> m.car[scheme]
                moving -> m.cab[scheme]
                else -> m.carRear[scheme]
            }
            draw(mesh, at(o.x, 0f, cz))
        }
        if (moving) {
            // Фары: яркие точки, ореол, пятно света на путях; мигают при гудке.
            val blink = if (o.horn == 1) 0.75f + 0.25f * sin(time * 30f) else 1f
            for (s in floatArrayOf(-0.78f, 0.78f)) {
                addGlow(o.x + s, 1.1f, z + 0.25f, 0.55f, 0xFFFFFFFF.toInt(), 0.95f * blink)
                addGlow(o.x + s, 1.1f, z + 0.3f, 1.8f, 0xFFFFF3C4.toInt(), 0.5f * blink)
            }
            addGlow(o.x, 1.4f, z + 0.4f, 4.5f, 0xFFFFE9A8.toInt(), (0.22f + 0.25f * night) * blink)
            addGlow(o.x, 0.36f, z + 4.2f, 1.25f, 0xFFFFF1C2.toInt(), (0.25f + 0.35f * night) * blink, flat = 1f, sizeZ = 4.2f)
            addGlow(o.x, 3.07f, z + 0.15f, 0.5f, 0xFFFFFFFF.toInt(), 0.6f)
        } else if (night > 0.2f || light < 0.9f) {
            for (s in floatArrayOf(-0.85f, 0.85f)) addGlow(o.x + s, 1.08f, z + 0.1f, 0.5f, 0xFFFF3B3B.toInt(), 0.55f)
        }
        if (world.winter) winterTrain(o, z, moving)
    }

    /**
     * Новогодний поезд (4.10.0): гирлянды разноцветных огоньков по краям крыши каждого вагона
     * (бегут волной), венок с красным бантом на лбу кабины, звезда над кабиной, светящиеся
     * снежинки-наклейки на окнах. Снег на крышах рисует сам шейдер (uSnow).
     * Только для ближних вагонов - дальние всё равно не видно, а кадр дороже.
     */
    private fun winterTrain(o: RObj, z: Float, moving: Boolean) {
        val near = if (lite) -45f else -80f
        val per = if (lite) 3 else 6
        for (i in 0 until o.cars) {
            val cz = z - i * (RW.CAR_LEN + RW.CAR_GAP)
            if (cz < near) break
            if (cz - RW.CAR_LEN > 9f) continue
            for (s in floatArrayOf(-1f, 1f)) for (j in 0 until per) {
                val bz = cz - 0.6f - j * (RW.CAR_LEN - 1.2f) / (per - 1)
                // Огоньки провисают дугой между креплениями.
                val sag = 0.12f * sin(((j % 2) + 0.5f) * 3.14f)
                val c = bulbCols[(j + i * 2 + (if (s > 0) 1 else 0) + o.style).mod(bulbCols.size)]
                val wave = 0.45f + 0.55f * sin(time * 4f - j * 0.9f - i * 1.3f).coerceAtLeast(0f)
                addGlow(o.x + s * 1.19f, 3.18f - sag, bz, 0.2f, c, 0.35f + 0.55f * wave)
            }
            if (!lite) {
                // Снежинки на окнах - мягкий белый отсвет.
                for (s in floatArrayOf(-1f, 1f)) {
                    addGlow(o.x + s * 1.2f, 2.2f, cz - 3f, 0.32f, 0xFFE0F2FE.toInt(), 0.35f)
                    addGlow(o.x + s * 1.2f, 2.2f, cz - 7f, 0.32f, 0xFFE0F2FE.toInt(), 0.35f)
                }
            }
        }
        if (z < near || z > 9f) return
        // Венок на торце (кабина или хвост): кольцо хвои с огоньками и красный бант.
        val wy = if (moving) 2.35f else 2.1f
        val wz = z + 0.18f
        val ring = if (lite) 8 else 14
        for (k in 0 until ring) {
            val a = k * (6.283f / ring)
            val x = o.x + cos(a) * 0.42f
            val y = wy + sin(a) * 0.42f
            addGlow(x, y, wz, 0.2f, 0xFF16A34A.toInt(), 0.55f)
            if (k % 3 == 0) addGlow(x, y, wz + 0.02f, 0.12f, bulbCols[(k / 3 + o.style).mod(bulbCols.size)], 0.6f + 0.35f * sin(time * 5f + k))
        }
        addGlow(o.x, wy - 0.45f, wz + 0.03f, 0.24f, 0xFFDC2626.toInt(), 0.85f)
        // Звезда над кабиной.
        if (moving) addGlow(o.x, RW.TRAIN_H + 0.55f, z - 0.4f, 0.5f + 0.06f * sin(time * 3f), 0xFFFDE047.toInt(), 0.9f)
    }

    /** Зимой на станциях, мостах и в тоннелях - гирлянды и фонарики (4.10.0). */
    private fun winterChunk(kind: Int, k: Int, z: Float) {
        if (z < (if (lite) -50f else -90f)) return
        val n = if (lite) 4 else 8
        when (kind) {
            // Станция: гирлянды под краем навесов обеих платформ и ёлочные шары.
            3 -> for (side in floatArrayOf(-1f, 1f)) for (i in 0 until n) {
                val bz = z - 0.5f - i * (RW.CH - 1f) / (n - 1)
                val c = bulbCols[(i + k + (if (side > 0) 2 else 0)).mod(bulbCols.size)]
                val tw = 0.4f + 0.5f * sin(time * 3f + i * 1.3f + k).coerceAtLeast(0f)
                addGlow(side * 5.3f, 3.9f - 0.15f * ((i % 2)), bz, 0.24f, c, tw + 0.1f)
                if (!lite && i % 3 == 1) addGlow(side * 5.3f, 3.35f, bz, 0.34f, c, 0.45f)
            }
            // Мост: огоньки по перилам, как у набережной.
            2 -> for (side in floatArrayOf(-1f, 1f)) for (i in 0 until n) {
                val bz = z - i * RW.CH / n
                val c = bulbCols[(i + k * 3 + (if (side > 0) 1 else 0)).mod(bulbCols.size)]
                addGlow(side * 4.2f, 6.5f, bz, 0.22f, c, 0.45f + 0.4f * sin(time * 2.5f + i + k).coerceAtLeast(0f))
                if (!lite) addGlow(side * 4.2f, 0.45f, bz, 0.18f, c, 0.35f)
            }
            // Тоннель: бегущая гирлянда по своду.
            1 -> for (i in 0 until n) {
                val bz = z - i * RW.CH / n
                val c = bulbCols[(i + k).mod(bulbCols.size)]
                val run = 0.3f + 0.6f * sin(time * 6f - (k * n + i) * 0.7f).coerceAtLeast(0f)
                addGlow(-4.35f, 4.7f, bz, 0.2f, c, run)
                addGlow(4.35f, 4.7f, bz, 0.2f, c, run)
            }
        }
    }

    private fun drawBarrier(m: Models, o: RObj, z: Float, light: Float) {
        val mesh = when (o.type) {
            RObj.HURDLE -> m.hurdle
            RObj.BAR -> m.bar
            RObj.BLOCK -> m.block
            else -> m.cone
        }
        val mm = at(o.x, 0f, z)
        if (o.hit) {
            val t = o.hitT
            if (t > 1.1f) return
            M4.translate(mm, 0f, max(0f, t * 5f - t * t * 9f), -t * 7f)
            M4.rotX(mm, -t * 420f)
            M4.rotZ(mm, t * 90f)
        }
        tint(light)
        draw(mesh, mm)
        if (!o.hit) {
            when (o.type) {
                RObj.BAR -> {
                    val b = if ((time * 3f + o.s).toInt() % 2 == 0) 1f else 0.35f
                    addGlow(o.x - 1.05f, 2.62f, z + 0.15f, 0.6f, 0xFFFB923C.toInt(), 0.6f * b)
                    addGlow(o.x + 1.05f, 2.62f, z + 0.15f, 0.6f, 0xFFFB923C.toInt(), 0.6f * (1.35f - b))
                }
                RObj.BLOCK -> {
                    val b = 0.5f + 0.5f * sin(time * 6f + o.s)
                    addGlow(o.x - 0.85f, 2.42f, z + 0.2f, 0.6f, 0xFFEF4444.toInt(), 0.35f + 0.4f * b)
                    addGlow(o.x + 0.85f, 2.42f, z + 0.2f, 0.6f, 0xFFEF4444.toInt(), 0.75f - 0.4f * b)
                }
            }
        }
    }

    // ------------------------------------------------------------------ фигуры

    /** Поза фигуры (углы - в градусах). Индекс 0 - левая сторона (-x), 1 - правая. */
    private class Pose {
        var x = 0f
        var y = 0f
        var z = 0f
        var yaw = 0f
        var hip = 0f
        var pitch = 0f
        var lean = 0f
        var spine = 0f
        var twist = 0f
        var headPitch = 0f
        var headYaw = 0f
        val thigh = FloatArray(2)
        val knee = FloatArray(2)
        val foot = FloatArray(2)
        val shoulder = FloatArray(2)
        val out = FloatArray(2)
        val elbow = FloatArray(2)
        var sqY = 1f
        var sqXZ = 1f

        fun idle(t: Float) {
            hip = 0f; pitch = 0f; lean = 0f; twist = 0f
            spine = -2f + sin(t * 2f) * 1.5f
            headPitch = 2f
            headYaw = sin(t * 0.7f) * 12f
            for (i in 0..1) {
                thigh[i] = 2f
                knee[i] = 5f
                foot[i] = 0f
                shoulder[i] = 4f + sin(t * 2f + i) * 2f
                out[i] = 9f
                elbow[i] = 14f
            }
            sqY = 1f
            sqXZ = 1f
        }

        fun run(phase: Float, amp: Float) {
            val s = sin(phase)
            val c = cos(phase)
            hip = (0.05f * abs(c) - 0.04f) * amp
            spine = -13f * amp
            twist = 7f * s * amp
            headPitch = 9f * amp
            thigh[0] = 8f + 44f * s * amp
            thigh[1] = 8f - 44f * s * amp
            knee[0] = 12f + (80f * max(0f, c) + 22f * max(0f, -s)) * amp
            knee[1] = 12f + (80f * max(0f, -c) + 22f * max(0f, s)) * amp
            foot[0] = 10f - knee[0] * 0.2f
            foot[1] = 10f - knee[1] * 0.2f
            shoulder[0] = -46f * s * amp
            shoulder[1] = 46f * s * amp
            out[0] = 9f
            out[1] = 9f
            elbow[0] = 70f + 18f * s
            elbow[1] = 70f - 18f * s
        }

        fun blend(o: FloatArray, a: FloatArray, f: Float) {
            for (i in 0..1) o[i] += (a[i] - o[i]) * f
        }
    }

    private fun push() {
        M4.copy(model, stack[sp])
        sp++
    }

    private fun pop() {
        sp--
        M4.copy(stack[sp], model)
    }

    private fun lerpP(p: Pose, f: Float, hip: Float, pitch: Float, spine: Float, head: Float, th: FloatArray, kn: FloatArray, sh: FloatArray, ou: FloatArray, el: FloatArray) {
        p.hip += (hip - p.hip) * f
        p.pitch += (pitch - p.pitch) * f
        p.spine += (spine - p.spine) * f
        p.headPitch += (head - p.headPitch) * f
        p.blend(p.thigh, th, f)
        p.blend(p.knee, kn, f)
        p.blend(p.shoulder, sh, f)
        p.blend(p.out, ou, f)
        p.blend(p.elbow, el, f)
    }

    private val tA = FloatArray(2)
    private val tB = FloatArray(2)
    private val tC = FloatArray(2)
    private val tD = FloatArray(2)
    private val tE = FloatArray(2)
    private fun set2(a: FloatArray, l: Float, r: Float): FloatArray {
        a[0] = l
        a[1] = r
        return a
    }

    private fun drawPlayer(m: Models, dt: Float) {
        val st = world.state
        val showcase = st == World.MENU && world.showcase
        val sk = RunnerSkins.all.getOrElse(if (showcase) world.previewSkin else world.skin) { RunnerSkins.all[0] }
        val p = pose
        val t = time
        val jet = world.power[World.P_JET] > 0f
        // В магазине досок доска показывается под бегуном.
        val boardPreview = showcase && world.previewBoard >= 0
        val board = (world.power[World.P_BOARD] > 0f && !jet) || boardPreview
        wJet += ((if (jet) 1f else 0f) - wJet) * min(1f, dt * 6f)
        wBoard += ((if (board) 1f else 0f) - wBoard) * min(1f, dt * 8f)
        val air = world.airborne && !jet && !world.rolling
        wJump += ((if (air) 1f else 0f) - wJump) * min(1f, dt * 14f)
        // На доске «кувырок» - не перекат, а бегун ложится на деку животом.
        val lie = world.rolling && board && st == World.RUN
        wLie += ((if (lie) 1f else 0f) - wLie) * min(1f, dt * 16f)
        // Отрыв от земли: с доской - кикфлип, в кедах-пружинах - сальто.
        if (st == World.RUN && air && !wasAir) {
            airT = 0f
            trickT = if (board) 0.62f else 0f
            flipSneak = world.power[World.P_SNEAKERS] > 0f && !board
        }
        wasAir = air
        if (air) airT += dt
        if (trickT > 0f) trickT = max(0f, trickT - dt)

        p.x = world.px
        p.y = world.py
        p.z = 0f
        p.yaw = 0f
        p.lean = 0f
        p.sqY = 1f
        p.sqXZ = 1f
        when (st) {
            World.MENU -> {
                p.idle(t)
                p.x = 0f
                p.y = 0f
                if (!showcase) {
                    // Ожидание старта: то потянется, то подпрыгнет на месте, то разомнёт шею.
                    val c = t % 12f
                    when {
                        c in 3f..4.6f -> {
                            val q = sin((c - 3f) / 1.6f * PI.toFloat())
                            p.shoulder[0] = 4f + 160f * q
                            p.shoulder[1] = 4f + 160f * q
                            p.out[0] = 9f + 10f * q
                            p.out[1] = 9f + 10f * q
                            p.spine = -2f - 8f * q
                        }
                        c in 7f..8.2f -> {
                            val ph = (c - 7f) / 0.4f
                            val hop = kotlin.math.abs(sin(ph * PI.toFloat()))
                            p.y = 0.35f * hop
                            p.knee[0] = 5f + 40f * (1f - hop)
                            p.knee[1] = 5f + 40f * (1f - hop)
                            p.shoulder[0] = 20f + 30f * hop
                            p.shoulder[1] = 20f + 30f * hop
                        }
                        c in 10f..11.4f -> {
                            p.headYaw = sin((c - 10f) * 7f) * 30f
                            p.headPitch = 10f
                        }
                    }
                }
                if (showcase) {
                    // Медленно поворачивается - облик видно со всех сторон.
                    p.yaw = (t * 32f) % 360f
                    // Иногда машет рукой.
                    val wave = ((t % 6f) > 4f)
                    if (wave) {
                        p.shoulder[1] = 160f
                        p.out[1] = 28f
                        p.elbow[1] = 30f + 25f * sin(t * 12f)
                    }
                }
            }
            World.COUNT -> {
                p.idle(t)
                // Стойка «на старт».
                lerpP(p, 1f, -0.06f, 0f, -14f, 6f, set2(tA, 30f, -6f), set2(tB, 40f, 18f), set2(tC, -30f, 35f), set2(tD, 10f, 10f), set2(tE, 70f, 70f))
            }
            World.DYING, World.OVER -> {
                val dtm = if (st == World.OVER) 3f else world.deathT
                if (world.deathKind == 1) {
                    p.idle(t)
                    val f = min(1f, dtm * 3f)
                    p.yaw = 180f * f
                    lerpP(p, f, 0.02f, 0f, 6f, 18f, set2(tA, 4f, 4f), set2(tB, 6f, 6f), set2(tC, 165f, 165f), set2(tD, 22f, 22f), set2(tE, 25f, 25f))
                    p.headYaw = sin(t * 9f) * 6f * (1f - f * 0.5f)
                } else {
                    p.idle(0f)
                    val f = min(1f, dtm * 2.6f)
                    p.pitch = 82f * f
                    p.hip = -0.62f * f
                    p.spine = 10f * f
                    set2(p.thigh, 30f * f, 18f * f)
                    set2(p.knee, 40f * f, 22f * f)
                    set2(p.shoulder, 60f * f, 120f * f)
                    set2(p.out, 70f * f, 60f * f)
                    p.headPitch = -15f * f
                }
            }
            else -> {
                p.run(world.runPhase, 1f)
                // Прыжок (группировка, ноги разные); при падении ноги готовятся к приземлению.
                if (wJump > 0.01f) {
                    val fall = (-world.vy / 10f).coerceIn(0f, 1f)
                    lerpP(
                        p, wJump, 0.02f, 0f, -8f, 4f,
                        set2(tA, 70f - 40f * fall, -12f + 10f * fall), set2(tB, 105f - 70f * fall, 70f - 40f * fall),
                        set2(tC, 150f - 60f * fall, -45f), set2(tD, 22f, 30f), set2(tE, 25f, 60f),
                    )
                }
                if (world.rolling && !lie) {
                    val q = 1f - world.rollT / World.ROLL_TIME
                    lerpP(p, 1f, -0.5f, -360f * q, -48f, 30f, set2(tA, 120f, 115f), set2(tB, 140f, 140f), set2(tC, 95f, 95f), set2(tD, 15f, 15f), set2(tE, 110f, 110f))
                }
                if (wBoard > 0.01f) {
                    lerpP(p, wBoard * (1f - wJump * 0.6f) * (1f - wLie), -0.12f, 0f, -10f, 6f, set2(tA, 28f, 18f), set2(tB, 40f, 34f), set2(tC, 20f, 20f), set2(tD, 55f, 55f), set2(tE, 30f, 30f))
                    p.yaw = -18f * wBoard * (1f - wLie)
                    // В прыжке на доске - захват рукой за край деки.
                    if (trickT > 0f) {
                        val g = sin((1f - trickT / 0.62f) * PI.toFloat())
                        p.shoulder[1] += 40f * g
                        p.elbow[1] += 50f * g
                        p.knee[0] += 35f * g
                        p.knee[1] += 35f * g
                    }
                }
                if (wLie > 0.01f) {
                    // Лёг на доску: корпус вдоль деки, руки держат нос, ноги чуть болтаются.
                    val kick = sin(t * 9f) * 14f
                    lerpP(
                        p, wLie, -0.84f, -84f, 0f, -62f,
                        set2(tA, 4f, 4f), set2(tB, 18f + kick, 18f - kick), set2(tC, 168f, 168f), set2(tD, 16f, 16f), set2(tE, 22f, 22f),
                    )
                    p.z += 0.12f * wLie
                }
                // Сальто в кедах-пружинах на взлёте.
                if (flipSneak && air && airT in 0.12f..0.74f) {
                    val q = ((airT - 0.12f) / 0.62f).coerceIn(0f, 1f)
                    val e = q * q * (3f - 2f * q)
                    p.pitch += -360f * e
                    p.knee[0] += 70f * sin(q * PI.toFloat())
                    p.knee[1] += 70f * sin(q * PI.toFloat())
                    p.thigh[0] += 60f * sin(q * PI.toFloat())
                    p.thigh[1] += 60f * sin(q * PI.toFloat())
                }
                if (wJet > 0.01f) {
                    lerpP(p, wJet, 0f, -18f, -22f, 20f, set2(tA, 12f, -8f), set2(tB, 25f, 15f), set2(tC, -25f, -25f), set2(tD, 20f, 20f), set2(tE, 15f, 15f))
                    // Полёт на ранце: плавное покачивание и болтающиеся ноги.
                    p.lean += sin(t * 1.7f) * 7f * wJet
                    p.y += sin(t * 2.3f) * 0.12f * wJet
                    p.knee[0] += (10f + 12f * sin(t * 5f)) * wJet
                    p.knee[1] += (10f - 12f * sin(t * 5f)) * wJet
                }
                // Наклон при смене пути.
                p.lean = (-world.latVel * 2.6f).coerceIn(-22f, 22f)
                p.twist += p.lean * 0.3f
                if (world.bumpT > 0f) p.lean += world.bumpDir * -14f * (world.bumpT / 0.3f)
                // Спотыкание: откинулся, машет руками.
                if (world.stumbleAnim > 0f) {
                    val f = world.stumbleAnim / 0.5f
                    p.spine += 30f * f
                    p.headYaw = sin(t * 30f) * 18f * f
                    p.out[0] += 50f * f
                    p.out[1] += 50f * f
                    p.shoulder[0] += sin(t * 25f) * 50f * f
                    p.shoulder[1] -= sin(t * 25f) * 50f * f
                }
                // Приземление: сжатие и пружина.
                if (world.landT > 0f) {
                    val q = sin(world.landT / 0.22f * PI.toFloat())
                    p.sqY = 1f - 0.14f * q
                    p.sqXZ = 1f + 0.08f * q
                    p.knee[0] += 30f * q
                    p.knee[1] += 30f * q
                    p.hip -= 0.08f * q
                }
            }
        }
        if (board) p.y += 0.22f + sin(t * 4f) * 0.03f

        // Мигание после спасения/продолжения.
        val blinkHide = world.invul > 0f && st == World.RUN && ((t * 12f).toInt() % 2 == 0) && world.invul < 2.9f
        if (!blinkHide) drawHuman(m, m.player, p, sk, hero = true)

        // Тень.
        val gy = if (st == World.MENU) 0f else world.groundY()
        val hgt = (world.py - gy).coerceAtLeast(0f)
        addShadow(p.x, gy + 0.03f, 0f, 0.55f / (1f + hgt * 0.25f), 0.42f / (1f + hgt * 0.35f))

        // Доска под ногами: из магазина (дека, полоса, колёса) или в цвет облика.
        if (board && !blinkHide) {
            val bi = if (boardPreview) world.previewBoard else world.board
            val bs = RunnerBoards.all.getOrNull(bi)?.takeIf { bi > 0 }
            val deck = bs?.deck ?: sk.board
            val stripe = bs?.stripe ?: (sk.glow.takeIf { it != 0 } ?: sk.board)
            val mm = at(p.x, p.y - 0.1f, 0f)
            M4.rotY(mm, p.yaw)
            M4.rotZ(mm, p.lean * 0.6f)
            // Кикфлип: доска делает оборот вдоль своей оси в прыжке.
            if (trickT > 0f) {
                val q = 1f - trickT / 0.62f
                val e = q * q * (3f - 2f * q)
                M4.translate(mm, 0f, 0.25f * sin(q * PI.toFloat()), 0f)
                M4.rotZ(mm, 360f * e)
            }
            tintC(deck)
            draw(m.board, mm)
            emitC(stripe, 0.9f)
            tint(0.3f)
            draw(m.boardGlow, mm)
            emit(0f, 0f, 0f)
            // Колёса крутятся со скоростью бега.
            val wc = bs?.wheel ?: 0xFF3A3F47.toInt()
            val spin = if (st == World.RUN) world.ps * 260f else time * 200f
            for (wz in floatArrayOf(-0.5f, 0.5f)) for (wx in floatArrayOf(-0.24f, 0.24f)) {
                val wm = wheelM
                M4.copy(mm, wm)
                M4.translate(wm, wx, -0.08f, wz)
                M4.rotX(wm, -spin)
                tintC(wc)
                if (bs?.wheelGlow == true) emitC(wc, 0.55f)
                draw(m.wheel, wm)
                emit(0f, 0f, 0f)
            }
            tint(1f)
            addGlow(p.x, p.y - 0.12f, 0f, 1.1f, stripe, 0.45f, flat = 1f, sizeZ = 1.4f)
        }
        // Свечение особого облика.
        if (sk.glow != 0 && !blinkHide) addGlow(p.x, p.y + 1.0f, 0.15f, 1.5f, sk.glow, 0.18f + 0.06f * sin(t * 4f))
        if (world.power[World.P_MAGNET] > 0f && st == World.RUN) addGlow(p.x, p.y + 1f, 0.1f, 1.8f, 0xFFF43F5E.toInt(), 0.12f + 0.08f * sin(t * 8f))
        if (st == World.RUN && (world.power[World.P_SHIELD] > 0f || world.shieldPopT > 0f)) {
            // Щит - переливающийся пузырь вокруг бегуна; кольцо искр бежит по нему.
            val pop = world.shieldPopT / 0.6f
            val left = world.power[World.P_SHIELD]
            val warnBlink = left in 0.01f..2f && ((t * 8f).toInt() % 2 == 0)
            val a = if (pop > 0f) 0.5f * pop else if (warnBlink) 0.08f else 0.16f + 0.05f * sin(t * 5f)
            addGlow(p.x, p.y + 1f, 0.1f, 2.3f + pop * 1.5f, 0xFF67E8F9.toInt(), a)
            if (pop <= 0f) for (i in 0 until 6) {
                val ang = t * 3f + i * 1.047f
                addGlow(p.x + cos(ang) * 0.9f, p.y + 1f + sin(ang * 1.3f) * 0.8f, 0.1f + sin(ang) * 0.5f, 0.22f, 0xFFE0F2FE.toInt(), 0.6f)
            }
        }
        if (world.power[World.P_COINS] > 0f && st == World.RUN) {
            for (i in 0 until 3) {
                val ang = t * 4f + i * 2.09f
                addGlow(p.x + cos(ang) * 0.6f, p.y + 2.2f + sin(t * 6f + i) * 0.1f, 0.1f + sin(ang) * 0.4f, 0.32f, 0xFFFACC15.toInt(), 0.75f)
            }
        }
        if (world.power[World.P_SLOW] > 0f && st == World.RUN) {
            addGlow(p.x, p.y + 1f, 0.1f, 1.9f, 0xFF818CF8.toInt(), 0.1f + 0.05f * sin(t * 2f))
        }
        if (world.power[World.P_X2] > 0f && st == World.RUN) {
            val mm = at(p.x, p.y + 2.35f, 0f)
            M4.rotY(mm, t * 150f)
            M4.scale(mm, 0.55f, 0.55f, 0.55f)
            tint(1f)
            emit(0.2f, 0.1f, 0.3f)
            draw(m.power[World.P_X2], mm)
            emit(0f, 0f, 0f)
            addGlow(p.x, p.y + 2.35f, 0.1f, 0.6f, 0xFFA855F7.toInt(), 0.4f)
        }
    }

    // Точки, найденные при обходе скелета (мир): спина (для ранца), правая кисть (фонарь).
    private val backPt = FloatArray(3)
    private val handM = FloatArray(16)
    private val coneM = FloatArray(16)
    private var coneValid = false

    /** Нарисовать фигуру по позе [p]. [hero] - бегун (облик [sk]) или обходчик. */
    private fun drawHuman(m: Models, rig: Rig, p: Pose, sk: Skin, hero: Boolean) {
        val k = rig.k
        sp = 0
        M4.identity(model)
        M4.translate(model, p.x, p.y, p.z)
        M4.rotY(model, p.yaw)
        M4.scale(model, p.sqXZ, p.sqY, p.sqXZ)
        // 4.12.0: у персонажей своё телосложение (шире/уже, выше/ниже) - ноги остаются на земле.
        if (hero && (sk.bulk != 1f || sk.tall != 1f)) M4.scale(model, sk.bulk, sk.tall, sk.bulk)
        M4.translate(model, 0f, rig.hipY + p.hip, 0f)
        M4.rotX(model, p.pitch)
        M4.rotZ(model, p.lean)
        val glowOn = hero && sk.glow != 0
        fun has(bit: Int) = hero && (sk.parts and bit) != 0
        val running = world.state == World.RUN
        val pulse = 0.55f + 0.25f * sin(time * 4f)
        val sneakers = hero && world.power[World.P_SNEAKERS] > 0f && world.state == World.RUN
        val magnet = hero && world.power[World.P_MAGNET] > 0f && world.state == World.RUN
        val jet = hero && wJet > 0.05f
        tintC(sk.pants)
        draw(rig.pelvis)
        // Ноги.
        for (i in 0..1) {
            val s = if (i == 0) -1f else 1f
            push()
            M4.translate(model, s * 0.1f * k, -0.03f * k, 0f)
            M4.rotX(model, p.thigh[i])
            M4.rotZ(model, s * 2f)
            tintC(sk.pants)
            draw(rig.thigh)
            M4.translate(model, 0f, -rig.thighLen, 0f)
            M4.rotX(model, -p.knee[i])
            tintC(if (sk.shorts) sk.skin else sk.pants)
            draw(rig.shin)
            M4.translate(model, 0f, -rig.shinLen, 0f)
            M4.rotX(model, p.foot[i])
            tintC(sk.shoes)
            if (sneakers) emit(0.1f, 0.5f, 0.15f) else if (glowOn) emitC(sk.glow, 0.15f)
            draw(rig.shoe)
            tintC(sk.sole)
            if (glowOn) emitC(sk.glow, pulse)
            draw(rig.sole)
            emit(0f, 0f, 0f)
            if (sneakers) {
                M4.point(model, 0f, -0.1f * k, 0f, pt)
                addGlow(pt[0], pt[1], pt[2], 0.45f, 0xFF4ADE80.toInt(), 0.5f)
            }
            pop()
        }
        // Хвост (кошка, чертёнок, лис, дракон): виляет на бегу.
        if (has(Parts.TAIL)) {
            (if (has(Parts.BUSHY)) rig.tailBushy else rig.tailThin)?.let {
                push()
                M4.translate(model, 0f, 0.02f * k, 0.08f * k)
                M4.rotY(model, sin(time * (if (running) 9f else 4f)) * 16f)
                M4.rotX(model, -8f + sin(time * 3f) * 6f)
                tintC(sk.partColor)
                if (glowOn) emitC(sk.glow, 0.12f)
                draw(it)
                emit(0f, 0f, 0f)
                pop()
            }
        }
        // Корпус.
        push()
        M4.translate(model, 0f, 0.06f * k, 0f)
        M4.rotX(model, p.spine)
        M4.rotY(model, p.twist)
        tintC(sk.top)
        draw(rig.abdomen)
        draw(rig.chest)
        if (hero) {
            tintC(sk.accent)
            if (glowOn) emitC(sk.glow, pulse)
            draw(rig.stripe)
            emit(0f, 0f, 0f)
            if (sk.extra in 1..2 || sk.extra == 6) {
                tintC(sk.extraColor)
                draw(rig.extras[sk.extra])
            }
            // Плащ: развевается на бегу.
            if (has(Parts.CAPE)) rig.cape?.let {
                push()
                M4.translate(model, 0f, 0.52f * k, 0.135f * k)
                M4.rotX(model, -(6f + if (running) 22f + 7f * sin(time * 11f) else 3f * sin(time * 2f)))
                tintC(sk.partColor)
                draw(it)
                pop()
            }
            // Крылья: машут, в прыжке - сильнее.
            if (has(Parts.WINGS)) {
                val flap = if (world.airborne && running) 28f * sin(time * 14f) else 12f * sin(time * 5f)
                for (s in floatArrayOf(-1f, 1f)) {
                    (if (s < 0f) rig.wingL else rig.wingR)?.let {
                        push()
                        M4.translate(model, s * 0.07f * k, 0.44f * k, 0.15f * k)
                        M4.rotY(model, -s * (32f + flap))
                        M4.rotZ(model, s * 14f)
                        tintC(sk.partColor)
                        if (glowOn) emitC(sk.glow, 0.3f)
                        draw(it)
                        emit(0f, 0f, 0f)
                        pop()
                    }
                }
            }
            if (jet) {
                push()
                M4.translate(model, 0f, 0.32f * k, 0.13f * k)
                tint(1f)
                draw(m.jetpack)
                for (s in floatArrayOf(-0.13f, 0.13f)) {
                    M4.point(model, s, -0.42f, 0.17f, pt)
                    addGlow(pt[0], pt[1], pt[2], 0.42f + 0.1f * sin(time * 40f + s * 10f), 0xFFFFB020.toInt(), 0.95f)
                    addGlow(pt[0], pt[1] - 0.35f, pt[2] + 0.1f, 0.65f, 0xFFFF5A1F.toInt(), 0.55f)
                }
                pop()
            }
        } else if (world.winter) {
            rig.santaTrim?.let {
                tint(1f)
                draw(it)
            }
        } else {
            rig.vest?.let {
                tint(1f)
                draw(it)
            }
        }
        // Руки.
        for (i in 0..1) {
            val s = if (i == 0) -1f else 1f
            push()
            M4.translate(model, s * rig.shoulderX, rig.shoulderY, 0f)
            if (has(Parts.PADS)) rig.pad?.let {
                tintC(sk.partColor)
                draw(it)
            }
            M4.rotZ(model, s * p.out[i])
            M4.rotX(model, p.shoulder[i])
            tintC(if (hero && !sk.sleeves) sk.skin else sk.top)
            draw(rig.upperArm)
            M4.translate(model, 0f, -rig.upperLen, 0f)
            M4.rotX(model, p.elbow[i])
            tintC(if (hero && !sk.sleeves) sk.skin else sk.top)
            draw(rig.foreArm)
            M4.translate(model, 0f, -rig.foreLen, 0f)
            tintC(sk.skin)
            draw(rig.hand)
            if (i == 1) {
                if (!hero) {
                    rig.flashlight?.let {
                        tint(1f)
                        draw(it)
                    }
                    M4.copy(model, handM)
                } else if (magnet) {
                    push()
                    M4.translate(model, 0f, -0.12f * k, -0.05f)
                    M4.rotX(model, 90f)
                    M4.scale(model, 0.5f, 0.5f, 0.5f)
                    tint(1f)
                    draw(m.power[World.P_MAGNET])
                    pop()
                }
            }
            pop()
        }
        // Голова.
        tintC(sk.skin)
        draw(rig.neck)
        push()
        M4.translate(model, 0f, rig.neckY, 0f)
        M4.rotX(model, p.headPitch)
        M4.rotY(model, p.headYaw)
        if (hero && sk.headK != 1f) M4.scale(model, sk.headK, sk.headK, sk.headK)
        draw(rig.head)
        tint(1f)
        draw(rig.eyes)
        if (hero) {
            if (sk.hat != 6 && sk.hat != 2 && sk.hat != 3 && sk.hat != 5) {
                tintC(sk.hairColor)
                draw(rig.hair[sk.hair.coerceIn(0, rig.hair.size - 1)])
            } else if (sk.hat == 6 || sk.hat == 2) {
                // Из-под капюшона/шапки видна чёлка - короткая причёска.
                tintC(sk.hairColor)
                draw(rig.hair[3])
            }
            if (sk.hat > 0) {
                tintC(sk.hatColor)
                draw(rig.hats[sk.hat])
                if (sk.hat == 3) {
                    tint(1f)
                    draw(rig.helmetLamp)
                    M4.point(model, 0f, 0.3f * k, -0.23f * k, pt)
                    addGlow(pt[0], pt[1], pt[2], 0.35f, 0xFFFFF7C2.toInt(), 0.7f)
                }
            }
            if (sk.extra in 3..5) {
                tintC(sk.extraColor)
                if (sk.extra == 5 && glowOn) emitC(sk.glow, pulse + 0.2f)
                draw(rig.extras[sk.extra])
                emit(0f, 0f, 0f)
            }
            // Части головы персонажей.
            if (has(Parts.EARS)) {
                rig.catEars?.let {
                    tintC(sk.partColor)
                    draw(it)
                }
                rig.earsInner?.let {
                    tintC(sk.part2Color)
                    draw(it)
                }
            }
            if (has(Parts.HORNS)) rig.horns?.let {
                tintC(sk.partColor, 0.85f)
                if (glowOn) emitC(sk.glow, 0.2f)
                draw(it)
                emit(0f, 0f, 0f)
            }
            if (has(Parts.CROWN)) {
                rig.crown?.let {
                    tintC(sk.partColor)
                    emitC(sk.partColor, 0.15f)
                    draw(it)
                }
                rig.crownGems?.let {
                    tintC(sk.part2Color)
                    emitC(sk.part2Color, 0.5f)
                    draw(it)
                }
                emit(0f, 0f, 0f)
            }
            if (has(Parts.ANTENNA)) {
                rig.antenna?.let {
                    tintC(sk.partColor)
                    draw(it)
                }
                rig.antennaTips?.let {
                    tintC(sk.part2Color)
                    emitC(sk.part2Color, pulse + 0.3f)
                    draw(it)
                    emit(0f, 0f, 0f)
                }
            }
            if (has(Parts.HALO)) rig.halo?.let {
                push()
                M4.translate(model, 0f, 0.025f * k * sin(time * 3f), 0f)
                M4.rotY(model, time * 40f)
                tintC(sk.part2Color)
                emitC(sk.part2Color, 0.9f)
                draw(it)
                emit(0f, 0f, 0f)
                M4.point(model, 0f, 0.47f * k, 0f, pt)
                addGlow(pt[0], pt[1], pt[2], 0.55f, sk.part2Color, 0.45f)
                pop()
            }
        } else if (world.winter) {
            rig.beard?.let {
                tint(1f)
                draw(it)
            }
            rig.santaHat?.let {
                tint(1f)
                draw(it)
            }
        } else {
            rig.mustache?.let {
                tint(1f)
                draw(it)
            }
            rig.cap?.let {
                tint(1f)
                draw(it)
            }
        }
        pop()
        pop()
        sp = 0
        // Аура легендарного облика: огоньки кружат вокруг и мягкое свечение.
        if (hero && sk.legendary && sk.aura != 0) {
            for (i in 0 until 6) {
                val a = time * 1.8f + i * 1.047f
                addGlow(
                    p.x + kotlin.math.cos(a) * 0.75f,
                    p.y + 0.4f + (i % 3) * 0.55f + sin(time * 3f + i) * 0.12f,
                    p.z + sin(a) * 0.75f,
                    0.3f, sk.aura, 0.6f,
                )
            }
            addGlow(p.x, p.y + 1.0f, p.z, 1.7f, sk.aura, 0.16f + 0.06f * sin(time * 2.5f))
        }
    }

    private val wheelM = FloatArray(16)
    private var lastChaserX = 0f
    private var chaserLean = 0f

    private fun drawChaser(m: Models) {
        coneValid = false
        val st = world.state
        val gap = world.chaserGap
        if (gap > 9f && st != World.MENU) return
        val p = cpose
        val t = time
        p.x = if (st == World.MENU) -0.9f else world.chaserX
        // По следу бегуна: прыгает там же, где прыгал он, пригибается под теми же шлагбаумами.
        p.y = if (st == World.MENU) 0f else world.chaserY
        p.z = if (st == World.MENU) 2.6f else gap
        p.yaw = 0f
        p.lean = 0f
        p.sqY = 1f
        p.sqXZ = 1f
        val running = st == World.RUN || (st == World.DYING && world.chaserSpeed > 1f)
        val cdx = if (st == World.MENU) 0f else world.chaserX - lastChaserX
        lastChaserX = if (st == World.MENU) -0.9f else world.chaserX
        if (running) {
            p.run(world.chaserPhase, 0.9f)
            chaserLean += ((cdx * -260f).coerceIn(-14f, 14f) - chaserLean) * 0.2f
            p.lean = chaserLean
            // В воздухе - поджимает ноги, под шлагбаумом - пригибается.
            val air = (world.chaserY - world.groundAtChaser()).coerceIn(0f, 2f)
            if (air > 0.15f) {
                p.knee[0] += 40f
                p.knee[1] += 40f
                p.thigh[0] += 25f
                p.thigh[1] += 25f
            }
            val duck = world.chaserDuck
            if (duck > 0.05f) {
                p.sqY = 1f - 0.38f * duck
                p.sqXZ = 1f + 0.12f * duck
                p.spine -= 25f * duck
            }
        } else {
            p.idle(t)
        }
        // Правая рука с фонарём - вперёд, на бегуна.
        p.shoulder[1] = if (running) 72f + sin(t * 9f) * 6f else 62f
        p.out[1] = 6f
        p.elbow[1] = 12f
        if (st == World.MENU || st == World.COUNT) {
            // Грозит пальцем левой рукой.
            p.shoulder[0] = 95f
            p.out[0] = -10f
            p.elbow[0] = 55f + sin(t * 10f) * 15f
            p.headYaw = 0f
        }
        if ((st == World.DYING || st == World.OVER) && world.deathKind == 1 && world.chaserGap < 0.9f) {
            p.shoulder[0] = 100f
            p.elbow[0] = 15f
        }
        drawHuman(m, m.chaser, p, if (world.winter) santaSkin else chaserSkin, hero = false)
        addShadow(p.x, 0.03f + (if (st == World.MENU) 0f else world.groundAtChaser()), p.z, 0.62f, 0.4f)
        // Луч фонаря.
        M4.point(handM, 0f, -0.23f * m.chaser.k, 0f, pt)
        addGlow(pt[0], pt[1], pt[2], 0.32f, 0xFFFFFFFF.toInt(), 0.9f)
        addGlow(pt[0], pt[1], pt[2], 0.9f, 0xFFFFF1B8.toInt(), 0.35f + 0.25f * night)
        M4.copy(handM, coneM)
        M4.translate(coneM, 0f, -0.23f * m.chaser.k, 0f)
        coneValid = true

        // Робопёс «Болтик» - рядом с хозяином, по следу бегуна (в своей дорожке).
        val dogX = if (st == World.MENU) p.x + 0.8f else world.dogX
        val dogY = if (st == World.MENU) 0f else world.dogY
        val dz = p.z - 0.5f
        val ph = if (running) world.chaserPhase * 1.6f else t * 0.8f
        val amp = if (running) 1f else 0.08f
        val bob = abs(sin(ph)) * 0.04f * amp
        val base = at(dogX, bob + dogY, dz)
        tint(1f)
        val bodyM = FloatArray(16)
        M4.copy(base, bodyM)
        val dogMesh = if (world.winter) m.deer else m.dog
        draw(dogMesh[0], bodyM)
        // Голова (кивает, при лае - подпрыгивает).
        M4.copy(base, model)
        M4.translate(model, 0f, 0.55f, -0.32f)
        M4.rotX(model, sin(t * 3f) * 8f)
        draw(dogMesh[1])
        val eye = FloatArray(3)
        if (world.winter) {
            // Красный нос оленя светится и чуть пульсирует.
            M4.point(model, 0f, -0.02f, -0.29f, eye)
            addGlow(eye[0], eye[1], eye[2], 0.26f + 0.06f * sin(t * 5f), 0xFFEF4444.toInt(), 0.85f)
        } else {
            M4.point(model, 0f, 0.02f, -0.22f, eye)
            addGlow(eye[0], eye[1], eye[2], 0.28f, 0xFF22D3EE.toInt(), 0.6f)
            M4.point(model, 0.05f, 0.33f, 0.03f, eye)
            if ((t * 2f).toInt() % 2 == 0) addGlow(eye[0], eye[1], eye[2], 0.12f, 0xFFF43F5E.toInt(), 0.9f)
        }
        // Лапы.
        val legs = floatArrayOf(-0.12f, -0.25f, 0.12f, -0.25f, -0.12f, 0.2f, 0.12f, 0.2f)
        for (i in 0 until 4) {
            M4.copy(base, model)
            M4.translate(model, legs[i * 2], 0.32f, legs[i * 2 + 1])
            val diag = if (i == 0 || i == 3) 0f else PI.toFloat()
            M4.rotX(model, sin(ph + diag) * 38f * amp)
            draw(dogMesh[2])
        }
        M4.copy(base, model)
        M4.translate(model, 0f, 0.48f, 0.28f)
        M4.rotX(model, -50f)
        M4.rotZ(model, sin(t * 14f) * 30f)
        draw(dogMesh[3])
        addShadow(dogX, 0.03f + dogY, dz, 0.42f, 0.35f)
    }

    private val POWER_COLOR = intArrayOf(
        0xFFF43F5E.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(), 0xFFA855F7.toInt(), 0xFF38BDF8.toInt(),
        0xFF67E8F9.toInt(), 0xFFFACC15.toInt(), 0xFF818CF8.toInt(),
    )
}
