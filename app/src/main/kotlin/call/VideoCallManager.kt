// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.call

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.Diagnostics
import com.stoyko.smessenger.domain.tox.Tox

private const val TAG = "VideoCall"
// Качество видео (Настройки → Чаты и звонки): размер кадра, частота и битрейт.
private class Quality(val w: Int, val h: Int, val intervalMs: Long, val kbps: Long)

private val QUALITIES = arrayOf(
    Quality(960, 540, 50L, 900L), // авто (мобильная сеть): ~20 кадров/с, дальше ядро подстраивает битрейт
    Quality(480, 360, 83L, 300L), // экономия трафика: ~12 кадров/с
    Quality(1280, 720, 33L, 2200L), // высокое: 720p, ~30 кадров/с
)

// Авто по Wi-Fi: сразу HD (720p, ~25 кадров/с) - раньше было 640×480 и 15 кадров/с («мыло»).
private val AUTO_WIFI = Quality(1280, 720, 40L, 1800L)

/**
 * Видеозвонок: камера (Camera2) -> I420 с поворотом -> ядро (кодирует VP8 и шифрует сквозным
 * шифрованием) -> собеседник. Входящие кадры I420 -> Bitmap -> экран звонка.
 */
@Singleton
class VideoCallManager @Inject constructor(
    private val context: Context,
    private val tox: Tox,
) {
    private var quality = QUALITIES[0]

    private fun loadQuality() {
        val i = runCatching { com.stoyko.smessenger.settings.Settings(context).callVideoQuality }.getOrDefault(0)
        quality = QUALITIES[i.coerceIn(0, QUALITIES.size - 1)]
        if (i == 0 && com.stoyko.smessenger.domain.feature.NetHint.wifi) quality = AUTO_WIFI
        com.stoyko.smessenger.domain.tox.VideoQuality.bitRate = quality.kbps
    }

    private val main = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newSingleThreadExecutor { Thread(it, "video-render") }

    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn

    private val _frontCamera = MutableStateFlow(true)
    val frontCamera: StateFlow<Boolean> = _frontCamera

    /** Куда показывать кадры (ставит экран звонка). Вызывается в главном потоке. */
    @Volatile
    var remoteRenderer: ((Bitmap) -> Unit)? = null

    @Volatile
    var localRenderer: ((Bitmap) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var target: PublicKey? = null
    private var sensorOrientation = 0
    private var lastFrameAt = 0L

    // Буферы для преобразования (живут в потоке камеры).
    private var yRaw = ByteArray(0)
    private var uRaw = ByteArray(0)
    private var vRaw = ByteArray(0)
    private var i420 = ByteArray(0)

    // Двойная буферизация отображения: пока один Bitmap на экране, рисуем в другой.
    private val remoteBitmaps = arrayOfNulls<Bitmap>(2)
    private var remoteIndex = 0
    private var remotePixels = IntArray(0)

    @Volatile
    private var remoteBusy = false
    private val localBitmaps = arrayOfNulls<Bitmap>(2)
    private var localIndex = 0
    private var localPixels = IntArray(0)

    @Volatile
    private var localBusy = false

    // ---------------- камера ----------------

    /** Включает камеру и отправку видео собеседнику. Разрешение CAMERA должно быть уже выдано. */
    fun startCamera(pk: PublicKey): Boolean {
        if (_cameraOn.value) return true
        target = pk
        loadQuality()
        tox.setVideoSending(pk, true)
        return openCamera()
    }

    fun stopCamera() {
        val pk = target
        target = null
        closeCamera()
        _cameraOn.value = false
        if (pk != null) runCatching { tox.setVideoSending(pk, false) }
    }

    fun switchCamera() {
        _frontCamera.value = !_frontCamera.value
        if (_cameraOn.value) {
            closeCamera()
            openCamera()
        }
    }

    /** Звонок закончился: всё выключаем и забываем кадры. */
    fun reset() {
        stopCamera()
        remoteRenderer = null
        localRenderer = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            val facing = if (_frontCamera.value) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Diagnostics.record("камера: список", e, fatal = false)
            null
        } ?: return false

        val chars = manager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        // Размер, ближайший к выбранному качеству, не больше 1280x720.
        val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - quality.w * quality.h) }
            ?: return false
        val fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= (1000 / quality.intervalMs).toInt().coerceAtMost(30) }
            ?.minByOrNull { it.upper }

        val t = HandlerThread("video-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                onCameraFrame(img)
            } catch (e: Exception) {
                Log.e(TAG, "frame", e)
            } finally {
                img.close()
            }
        }, h)

        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(
                            listOf(r.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(r.surface)
                                            if (fpsRange != null) {
                                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fpsRange.lower, fpsRange.upper))
                                            }
                                        }
                                        s.setRepeatingRequest(req.build(), null, h)
                                        _cameraOn.value = true
                                    } catch (e: Exception) {
                                        Diagnostics.record("камера: запуск", e, fatal = false)
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "configure failed")
                                }
                            },
                            h,
                        )
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        camera = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e(TAG, "camera error $error")
                        device.close()
                        camera = null
                    }
                },
                h,
            )
        } catch (e: Exception) {
            Diagnostics.record("камера: открытие", e, fatal = false)
            closeCamera()
            return false
        }
        return true
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { reader?.close() }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun onCameraFrame(img: Image) {
        val now = System.currentTimeMillis()
        // Допуск в 1/4 интервала: камера даёт кадры через 33 мс, и строгое «< 40 мс»
        // пропускало каждый второй кадр - вместо 25 кадров/с выходило ~15 (видео дёргалось).
        if (now - lastFrameAt < quality.intervalMs * 3 / 4) return
        lastFrameAt = now
        val pk = target ?: return
        val (w, h) = toI420Rotated(img, sensorOrientation)
        val code = tox.sendVideo(pk, w, h, i420)
        if (code != 0) Log.v(TAG, "send frame: $code")
        showLocal(w, h, i420)
    }

    /** Кадр камеры (YUV_420_888 с любыми stride) -> плотный I420, повёрнутый «головой вверх». */
    private fun toI420Rotated(img: Image, rotation: Int): Pair<Int, Int> {
        val w = img.width and 1.inv()
        val h = img.height and 1.inv()
        val p = img.planes
        val yb = p[0].buffer
        val ub = p[1].buffer
        val vb = p[2].buffer
        if (yRaw.size < yb.remaining()) yRaw = ByteArray(yb.remaining())
        if (uRaw.size < ub.remaining()) uRaw = ByteArray(ub.remaining())
        if (vRaw.size < vb.remaining()) vRaw = ByteArray(vb.remaining())
        val yLen = yb.remaining()
        val uLen = ub.remaining()
        val vLen = vb.remaining()
        yb.get(yRaw, 0, yLen)
        ub.get(uRaw, 0, uLen)
        vb.get(vRaw, 0, vLen)
        val yRow = p[0].rowStride
        val yPix = p[0].pixelStride
        val cRow = p[1].rowStride
        val cPix = p[1].pixelStride

        val rot = ((rotation % 360) + 360) % 360
        val outW = if (rot == 90 || rot == 270) h else w
        val outH = if (rot == 90 || rot == 270) w else h
        val size = outW * outH * 3 / 2
        if (i420.size < size) i420 = ByteArray(size)

        fun dst(x: Int, y: Int, sw: Int, sh: Int, dw: Int): Int = when (rot) {
            90 -> x * dw + (sh - 1 - y)
            180 -> (sh - 1 - y) * dw + (sw - 1 - x)
            270 -> (sw - 1 - x) * dw + y
            else -> y * dw + x
        }

        for (y in 0 until h) {
            val row = y * yRow
            for (x in 0 until w) {
                val i = row + x * yPix
                if (i < yLen) i420[dst(x, y, w, h, outW)] = yRaw[i]
            }
        }
        val cw = w / 2
        val ch = h / 2
        val dcw = outW / 2
        val uOff = outW * outH
        val vOff = uOff + dcw * (outH / 2)
        for (y in 0 until ch) {
            val row = y * cRow
            for (x in 0 until cw) {
                val i = row + x * cPix
                val d = dst(x, y, cw, ch, dcw)
                if (i < uLen) i420[uOff + d] = uRaw[i]
                if (i < vLen) i420[vOff + d] = vRaw[i]
            }
        }
        return outW to outH
    }

    // ---------------- показ ----------------

    /** Кадр собеседника (вызывается из видеопотока ядра). Если прошлый ещё рисуется - кадр пропускаем. */
    fun onRemoteFrame(width: Int, height: Int, frame: ByteArray) {
        if (remoteRenderer == null || remoteBusy) return
        remoteBusy = true
        renderExecutor.execute {
            try {
                if (remotePixels.size < width * height) remotePixels = IntArray(width * height)
                i420ToArgb(frame, width, height, remotePixels)
                remoteIndex = 1 - remoteIndex
                val bmp = bitmapFor(remoteBitmaps, remoteIndex, width, height)
                bmp.setPixels(remotePixels, 0, width, 0, 0, width, height)
                main.post {
                    remoteRenderer?.invoke(bmp)
                    remoteBusy = false
                }
            } catch (e: Throwable) {
                remoteBusy = false
                Log.e(TAG, "render remote", e)
            }
        }
    }

    private fun showLocal(width: Int, height: Int, frame: ByteArray) {
        if (localRenderer == null || localBusy) return
        localBusy = true
        val copy = frame.copyOf(width * height * 3 / 2)
        renderExecutor.execute {
            try {
                if (localPixels.size < width * height) localPixels = IntArray(width * height)
                i420ToArgb(copy, width, height, localPixels)
                localIndex = 1 - localIndex
                val bmp = bitmapFor(localBitmaps, localIndex, width, height)
                bmp.setPixels(localPixels, 0, width, 0, 0, width, height)
                main.post {
                    localRenderer?.invoke(bmp)
                    localBusy = false
                }
            } catch (e: Throwable) {
                localBusy = false
            }
        }
    }

    private fun bitmapFor(pool: Array<Bitmap?>, index: Int, w: Int, h: Int): Bitmap {
        val cur = pool[index]
        if (cur != null && cur.width == w && cur.height == h) return cur
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { pool[index] = it }
    }
}

/** I420 -> ARGB (целочисленная формула BT.601). */
internal fun i420ToArgb(src: ByteArray, w: Int, h: Int, out: IntArray) {
    val frame = w * h
    val cw = w / 2
    val uBase = frame
    val vBase = frame + cw * (h / 2)
    var o = 0
    for (j in 0 until h) {
        val cRow = (j shr 1) * cw
        for (i in 0 until w) {
            val y = (src[j * w + i].toInt() and 0xFF)
            val c = cRow + (i shr 1)
            val u = (src[uBase + c].toInt() and 0xFF) - 128
            val v = (src[vBase + c].toInt() and 0xFF) - 128
            var r = y + ((359 * v) shr 8)
            var g = y - ((88 * u + 183 * v) shr 8)
            var b = y + ((454 * u) shr 8)
            if (r < 0) r = 0 else if (r > 255) r = 255
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (b < 0) b = 0 else if (b > 255) b = 255
            out[o++] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
    }
}
