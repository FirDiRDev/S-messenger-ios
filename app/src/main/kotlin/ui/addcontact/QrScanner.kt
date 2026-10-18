// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.addcontact

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Встроенный сканер QR-кодов: камера прямо в приложении (не нужно ставить сторонний сканер).
 * Распознавание - библиотека ZXing, без интернета. [onResult] вызывается один раз, в главном потоке.
 */
@Suppress("DEPRECATION")
class QrScanner(private val context: Context, private val onResult: (String) -> Unit) {
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val texture = TextureView(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "qr-scan").apply { isDaemon = true } }
    private val decoding = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private var camera: Camera? = null
    private var previewW = 0
    private var previewH = 0
    private var torch = false
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    fun show() {
        val d = context.resources.displayMetrics.density
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(Frame(context), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            TextView(context).apply {
                setText(R.string.qr_scan_hint)
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = (110 * d).toInt()
            },
        )
        root.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.dismiss() }
            },
            FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt(), Gravity.TOP or Gravity.START).apply {
                topMargin = (24 * d).toInt()
                leftMargin = (8 * d).toInt()
            },
        )
        root.addView(
            TextView(context).apply {
                text = "🔦"
                textSize = 28f
                gravity = Gravity.CENTER
                setOnClickListener { toggleTorch() }
            },
            FrameLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (32 * d).toInt()
            },
        )
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = start(surface)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fitPreview()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener {
            stop()
            worker.shutdown()
        }
        dialog.show()
    }

    private fun start(surface: SurfaceTexture) {
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(it, info)
                info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            val cam = Camera.open(id)
            camera = cam
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(id, info)
            val params = cam.parameters
            val size = params.supportedPreviewSizes
                .minByOrNull { Math.abs(it.width * it.height - 1280 * 720) } ?: params.previewSize
            params.setPreviewSize(size.width, size.height)
            when {
                params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            cam.parameters = params
            previewW = size.width
            previewH = size.height
            val rotation = displayDegrees()
            val orientation = (info.orientation - rotation + 360) % 360
            cam.setDisplayOrientation(orientation)
            swap = orientation % 180 != 0
            cam.setPreviewTexture(surface)
            cam.setPreviewCallback { data, _ -> onFrame(data) }
            cam.startPreview()
            if (params.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) autoFocusLoop()
            fitPreview()
        } catch (e: Exception) {
            Log.e(TAG, "camera: $e")
            android.widget.Toast.makeText(context, R.string.camera_unavailable, android.widget.Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
    }

    private var swap = false

    /** Превью без искажений: заполняет экран с обрезкой краёв. */
    private fun fitPreview() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0 || previewW == 0) return
        val pw = (if (swap) previewH else previewW).toFloat()
        val ph = (if (swap) previewW else previewH).toFloat()
        val scale = maxOf(w / pw, h / ph)
        val m = Matrix()
        m.setScale(pw * scale / w, ph * scale / h, w / 2, h / 2)
        texture.setTransform(m)
    }

    private fun autoFocusLoop() {
        main.postDelayed({
            val cam = camera ?: return@postDelayed
            runCatching { cam.autoFocus { _, _ -> } }
            autoFocusLoop()
        }, 2000)
    }

    private fun onFrame(data: ByteArray?) {
        if (data == null || done.get() || !decoding.compareAndSet(false, true)) return
        val w = previewW
        val h = previewH
        try {
            worker.execute { decode(data, w, h) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            decoding.set(false)
        }
    }

    private fun decode(data: ByteArray, w: Int, h: Int) {
        run {
            try {
                val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
                    ?: runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))) }.getOrNull()
                reader.reset()
                val text = result?.text
                // Принимаем только QR с ID контакта; чужие коды (ссылки, текст) пропускаем и сканируем дальше.
                if (!text.isNullOrBlank() && hasId(text) && done.compareAndSet(false, true)) {
                    main.post {
                        // Экран мог закрыться, пока шло распознавание - закрываем окно без падения.
                        runCatching { dialog.dismiss() }
                        onResult(extractId(text))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode: $e")
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        runCatching {
            val p = cam.parameters
            if (p.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) != true) return
            torch = !torch
            p.flashMode = if (torch) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            cam.parameters = p
        }
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        val cam = camera ?: return
        camera = null
        runCatching {
            cam.setPreviewCallback(null)
            cam.stopPreview()
            cam.release()
        }
    }

    private fun displayDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Рамка прицела посередине экрана. */
    private class Frame(context: Context) : View(context) {
        private val dim = Paint().apply { color = 0x88000000.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00C2A8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4 * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height) * 0.7f
            val l = (width - size) / 2
            val t = (height - size) / 2
            val r = RectF(l, t, l + size, t + size)
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, dim)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
            val rad = 18 * resources.displayMetrics.density
            canvas.drawRoundRect(r, rad, rad, stroke)
        }
    }

    companion object {
        private const val TAG = "QrScanner"

        /** Достаёт ID из текста QR: «smessenger:ID», «tox:ID», ссылка с ID или просто ID. */
        fun extractId(text: String): String {
            val t = text.trim()
            // Из любого текста берём только ID: 76 шестнадцатеричных символов подряд.
            Regex("[0-9A-Fa-f]{76}").find(t)?.let { return it.value.uppercase() }
            // ID, разбитый пробелами/дефисами («S-messenger: AB12 CD34 …»): убираем приписку и разделители.
            val body = t.substringAfterLast(':').ifBlank { t }
            val hex = body.filter { it.isLetterOrDigit() }
            if (hex.length == 76 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return hex.uppercase()
            return ""
        }

        /** В тексте QR есть ID контакта (только он и будет использован). */
        fun hasId(text: String): Boolean = extractId(text).length == 76
    }
}
