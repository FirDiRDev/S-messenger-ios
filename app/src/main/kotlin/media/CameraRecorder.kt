// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

private const val TAG = "CameraRecorder"
private const val VIDEO_MIME = "video/avc"
private const val AUDIO_MIME = "audio/mp4a-latm"
private const val AUDIO_RATE = 44_100
private const val AUDIO_BITRATE = 64_000
private const val FPS = 30

/**
 * Запись «кружков» (и видео для историй): камера → OpenGL (поворот, зеркало,
 * обрезка до квадрата/кадра) → аппаратный кодер H.264 + AAC → MP4.
 *
 * Камеру можно переключать ПРЯМО ВО ВРЕМЯ записи: кадры обеих камер рисуются в один и тот же
 * кодер, поэтому видео не рвётся и не переворачивается.
 *
 * Предпросмотр рисуется в [preview] (SurfaceTexture из TextureView). [circle] - маска-круг
 * в предпросмотре (в файл пишется полный квадрат, круг рисует получатель).
 */
class CameraRecorder(
    private val context: Context,
    private val output: File,
    private val outW: Int,
    private val outH: Int,
    private val bitRate: Int,
    private val circle: Boolean,
) {
    /** Ошибка (в главном потоке): не удалось открыть камеру/кодер. */
    var onError: ((String) -> Unit)? = null

    /** Первый кадр записан (в главном потоке). */
    var onStarted: (() -> Unit)? = null

    @Volatile
    var front = true
        private set

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("camera-recorder").apply { start() }
    private val gl = Handler(thread.looper)

    // --- EGL / GL ---
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0
    private var previewH = 0
    private var program = 0
    private var texId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    // --- камера ---
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sensorOrientation = 90
    private var bufferSize = Size(1280, 720)

    // --- кодеры ---
    private var videoCodec: MediaCodec? = null
    private var videoInput: Surface? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var muxer: MediaMuxer? = null
    private val muxLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var audioWanted = true
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()
    private var samplesWritten = 0

    @Volatile
    private var recording = false

    @Volatile
    private var startNs = 0L

    @Volatile
    private var released = false

    val elapsedMs: Long get() = if (startNs == 0L) 0L else (System.nanoTime() - startNs) / 1_000_000

    /** Запуск: предпросмотр и сразу запись. Возвращает сразу; ошибки - через [onError]. */
    fun start(preview: SurfaceTexture, width: Int, height: Int, frontCamera: Boolean) {
        front = frontCamera
        com.stoyko.smessenger.ui.ThemeMusic.hold("cam")
        gl.post {
            try {
                setupCodecs()
                setupEgl()
                setPreviewLocked(preview, width, height)
                recording = true
                openCamera()
                startAudio()
            } catch (e: Throwable) {
                Log.e(TAG, "start", e)
                fail(e.message ?: e.toString())
            }
        }
    }

    /** Переключить фронтальную/основную камеру (в том числе во время записи). */
    fun switchCamera() {
        gl.post {
            if (released) return@post
            front = !front
            closeCamera()
            try {
                openCamera()
            } catch (e: Throwable) {
                Log.e(TAG, "switch", e)
            }
        }
    }

    /** Размер предпросмотра изменился. */
    fun setPreviewSize(width: Int, height: Int) {
        gl.post {
            previewW = width
            previewH = height
        }
    }

    /**
     * Остановить. cancel = true - файл удаляется. [done] получает готовый файл или null (в главном потоке).
     */
    fun stop(cancel: Boolean, done: (File?) -> Unit) {
        gl.post {
            val file = try {
                finish(cancel)
            } catch (e: Throwable) {
                Log.e(TAG, "stop", e)
                null
            }
            releaseAll()
            main.post { done(file) }
            thread.quitSafely()
        }
    }

    private fun fail(msg: String) {
        if (released) return
        releaseAll()
        output.delete()
        main.post { onError?.invoke(msg) }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- кодеры

    private fun setupCodecs() {
        val vf = MediaFormat.createVideoFormat(VIDEO_MIME, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val vc = MediaCodec.createEncoderByType(VIDEO_MIME)
        vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoInput = vc.createInputSurface()
        vc.start()
        videoCodec = vc

        audioWanted = try {
            val af = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val ac = MediaCodec.createEncoderByType(AUDIO_MIME)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
            true
        } catch (e: Exception) {
            Log.w(TAG, "no audio encoder: $e")
            false
        }

        output.parentFile?.mkdirs()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun addTrack(format: MediaFormat, video: Boolean) = synchronized(muxLock) {
        val m = muxer ?: return@synchronized
        if (video) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
        val ready = videoTrack >= 0 && (!audioWanted || audioTrack >= 0)
        if (ready && !muxerStarted) {
            m.start()
            muxerStarted = true
            for ((track, data, info) in pending) {
                val t = if (track == 0) videoTrack else audioTrack
                runCatching { m.writeSampleData(t, data, info) }
            }
            pending.clear()
        }
    }

    private fun writeSample(video: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(muxLock) {
        if (info.size <= 0) return@synchronized
        data.position(info.offset)
        data.limit(info.offset + info.size)
        if (!muxerStarted) {
            // Дорожки ещё не готовы: придерживаем немного данных.
            if (pending.size < 120) {
                val copy = ByteBuffer.allocateDirect(info.size)
                copy.put(data)
                copy.flip()
                val ci = MediaCodec.BufferInfo()
                ci.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Triple(if (video) 0 else 1, copy, ci))
            }
            return@synchronized
        }
        val m = muxer ?: return@synchronized
        m.writeSampleData(if (video) videoTrack else audioTrack, data, info)
        if (video) samplesWritten++
    }

    private fun drainVideo(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.uptimeMillis() + 1500
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || SystemClock.uptimeMillis() > deadline) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, true)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writeSample(true, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val codec = audioCodec ?: return
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
        if (rec == null) {
            Log.w(TAG, "no microphone")
            // Без звука: запускаем мультиплексор только с видео.
            synchronized(muxLock) { audioWanted = false }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            audioCodec = null
            return
        }
        audioRecord = rec
        audioThread = Thread({ audioLoop(rec, codec) }, "camera-recorder-audio").apply { start() }
    }

    private fun audioLoop(rec: AudioRecord, codec: MediaCodec) {
        val buf = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        var baseUs = -1L
        try {
            rec.startRecording()
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (startNs == 0L) continue // ждём первого кадра видео - общее начало отсчёта
                if (baseUs < 0) baseUs = (System.nanoTime() - startNs) / 1000
                val pts = baseUs + totalSamples * 1_000_000L / AUDIO_RATE
                totalSamples += n / 2
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx) ?: continue
                    ib.clear()
                    val len = minOf(n, ib.remaining())
                    ib.put(buf, 0, len)
                    codec.queueInputBuffer(inIdx, 0, len, pts, 0)
                }
                drainAudio(codec, info, false)
            }
            // Конец записи.
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val pts = maxOf(0L, baseUs) + totalSamples * 1_000_000L / AUDIO_RATE
                codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudio(codec, info, true)
        } catch (e: Exception) {
            Log.w(TAG, "audio loop: $e")
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 1000
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || SystemClock.uptimeMillis() > deadline) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, false)
                idx >= 0 -> {
                    val b = codec.getOutputBuffer(idx)
                    if (b != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) writeSample(false, b, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- EGL / GL

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw IllegalStateException("eglInitialize")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw IllegalStateException("eglChooseConfig")
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
        encoderEgl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, videoInput, intArrayOf(EGL14.EGL_NONE), 0)
        if (encoderEgl == EGL14.EGL_NO_SURFACE) throw IllegalStateException("encoder surface")
        EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)

        program = buildProgram()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ onFrame() }, gl)
        cameraTexture = st
        cameraSurface = Surface(st)
    }

    private fun setPreviewLocked(preview: SurfaceTexture, width: Int, height: Int) {
        previewW = width
        previewH = height
        previewEgl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, preview, intArrayOf(EGL14.EGL_NONE), 0)
    }

    private val vertexShader = """
        uniform mat4 uTexMatrix;
        uniform mat4 uPosMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTex;
        varying vec2 vPos;
        void main() {
            gl_Position = uPosMatrix * aPosition;
            vTex = (uTexMatrix * aTexCoord).xy;
            vPos = gl_Position.xy;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        varying vec2 vPos;
        uniform samplerExternalOES sTex;
        uniform float uCircle;
        void main() {
            vec4 c = texture2D(sTex, vTex);
            if (uCircle > 0.5) {
                float d = length(vPos);
                float a = 1.0 - smoothstep(0.985, 1.0, d);
                gl_FragColor = vec4(c.rgb * a, a);
            } else {
                gl_FragColor = vec4(c.rgb, 1.0);
            }
        }
    """.trimIndent()

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("shader: $log")
        }
        return s
    }

    private fun buildProgram(): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertexShader))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    /**
     * Матрица положения картинки (а не координат текстуры):
     *  1) координаты текстуры - только матрица SurfaceTexture, как есть: кадр в системе датчика;
     *  2) квадрат поворачивается по часовой стрелке на угол датчика - «головой вверх»;
     *  3) зеркало для фронтальной камеры и растяжение до пропорций цели с обрезкой по центру.
     * Раньше поворот делался в координатах текстуры, после переворота по Y из матрицы SurfaceTexture
     * он менял направление - на части телефонов кружки выходили вверх ногами или боком.
     */
    private fun buildCrop(targetW: Int, targetH: Int, out: FloatArray) {
        val rot = ((sensorOrientation % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val uprightW = if (swap) bufferSize.height else bufferSize.width
        val uprightH = if (swap) bufferSize.width else bufferSize.height
        val au = uprightW.toFloat() / uprightH.coerceAtLeast(1)
        val ao = targetW.toFloat() / targetH.coerceAtLeast(1)
        var sx = 1f
        var sy = 1f
        if (au > ao) sx = au / ao else sy = ao / au
        if (front) sx = -sx
        Matrix.setIdentityM(out, 0)
        Matrix.scaleM(out, 0, sx, sy, 1f)
        // В пространстве экрана (Y вверх) поворот по часовой стрелке - отрицательный угол.
        Matrix.rotateM(out, 0, -rot.toFloat(), 0f, 0f, 1f)
    }

    private fun draw(targetW: Int, targetH: Int, circleMask: Boolean) {
        GLES20.glViewport(0, 0, targetW, targetH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        buildCrop(targetW, targetH, cropMatrix)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tc = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uPosMatrix"), 1, false, cropMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCircle"), if (circleMask) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(tc)
        GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tc)
    }

    private fun onFrame() {
        if (released) return
        val st = cameraTexture ?: return
        try {
            EGL14.eglMakeCurrent(eglDisplay, encoderEgl, encoderEgl, eglContext)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            if (recording) {
                if (startNs == 0L) {
                    startNs = System.nanoTime()
                    main.post { onStarted?.invoke() }
                }
                draw(outW, outH, false)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEgl, System.nanoTime() - startNs)
                EGL14.eglSwapBuffers(eglDisplay, encoderEgl)
                drainVideo(false)
            }
            if (previewEgl != EGL14.EGL_NO_SURFACE && previewW > 0 && previewH > 0) {
                EGL14.eglMakeCurrent(eglDisplay, previewEgl, previewEgl, eglContext)
                draw(previewW, previewH, circle)
                EGL14.eglSwapBuffers(eglDisplay, previewEgl)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "frame: $e")
        }
    }

    // ---------------------------------------------------------------- камера

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("no camera")
        val chars = manager.getCameraCharacteristics(id)
        front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        // Около 1280x720: хватает для кружка и истории, и не грузит слабые телефоны.
        bufferSize = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: Size(1280, 720)
        cameraTexture?.setDefaultBufferSize(bufferSize.width, bufferSize.height)
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper in 24..30 }
            ?.maxByOrNull { it.lower }
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)

        manager.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (released) {
                        device.close()
                        return
                    }
                    camera = device
                    val target = cameraSurface ?: return
                    try {
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(
                            listOf(target),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (released || camera !== device) {
                                        runCatching { s.close() }
                                        return
                                    }
                                    session = s
                                    try {
                                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(target)
                                            if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.lower, fps.upper))
                                            if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
                                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                            }
                                        }
                                        s.setRepeatingRequest(req.build(), null, gl)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "request", e)
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    fail("camera configure failed")
                                }
                            },
                            gl,
                        )
                    } catch (e: Exception) {
                        fail(e.toString())
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device) {
                        camera = null
                        fail("camera error $error")
                    }
                }
            },
            gl,
        )
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    // ---------------------------------------------------------------- завершение

    private fun finish(cancel: Boolean): File? {
        recording = false
        closeCamera()
        runCatching { audioThread?.join(1500) }
        audioThread = null
        val vc = videoCodec
        if (vc != null) {
            runCatching {
                vc.signalEndOfInputStream()
                drainVideo(true)
            }
        }
        var ok = false
        synchronized(muxLock) {
            val m = muxer
            if (m != null && muxerStarted && samplesWritten > 0) {
                ok = runCatching { m.stop() }.isSuccess
            }
        }
        if (cancel || !ok) {
            output.delete()
            return null
        }
        return output.takeIf { it.exists() && it.length() > 0 }
    }

    private fun releaseAll() {
        com.stoyko.smessenger.ui.ThemeMusic.release("cam")
        if (released) return
        released = true
        recording = false
        closeCamera()
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        synchronized(muxLock) {
            runCatching { muxer?.release() }
            muxer = null
        }
        runCatching { cameraSurface?.release() }
        cameraSurface = null
        runCatching { cameraTexture?.release() }
        cameraTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEgl)
            if (encoderEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEgl)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        previewEgl = EGL14.EGL_NO_SURFACE
        encoderEgl = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        runCatching { videoInput?.release() }
        videoInput = null
    }
}
