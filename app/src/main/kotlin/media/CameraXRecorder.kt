// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File

/**
 * Запись «кружков» и историй через CameraX - как обычная камера телефона.
 * CameraX сам учитывает угол датчика, поворот экрана, зеркало фронтальной камеры и пропорции
 * на любой модели, поэтому видео больше не выходит боком, вверх ногами или «сплюснутым».
 * Камеру можно переключать прямо во время записи - запись продолжается в тот же файл.
 */
class CameraXRecorder(
    private val context: Context,
    private val output: File,
    story: Boolean,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Ошибка (в главном потоке). */
    var onError: ((String) -> Unit)? = null

    /** Запись пошла (в главном потоке). */
    var onStarted: (() -> Unit)? = null

    var front = true
        private set

    @Volatile
    private var startNs = 0L
    val elapsedMs: Long get() = if (startNs == 0L) 0L else (System.nanoTime() - startNs) / 1_000_000

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var finished: ((File?) -> Unit)? = null
    private var cancelled = false
    private var released = false

    private val preview = Preview.Builder().build()
    private val videoCapture: VideoCapture<Recorder> = VideoCapture.Builder(
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    if (story) Quality.HD else Quality.SD,
                    FallbackStrategy.higherQualityOrLowerThan(if (story) Quality.HD else Quality.SD),
                ),
            )
            .setTargetVideoEncodingBitRate(if (story) 2_500_000 else 1_200_000)
            .build(),
    )
        // Фронтальная камера - зеркально и в обычной камере.
        .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        .build()

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start(view: PreviewView, frontCamera: Boolean) {
        front = frontCamera
        // TextureView внутри: так предпросмотр обрезается кругом/скруглением, как и раньше.
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        preview.setSurfaceProvider(view.surfaceProvider)
        registry.currentState = Lifecycle.State.RESUMED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
                record()
            } catch (e: Throwable) {
                Log.e(TAG, "start", e)
                fail(e.message ?: e.toString())
            }
        }, mainExecutor)
    }

    private fun bind() {
        val p = provider ?: return
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        p.unbindAll()
        p.bindToLifecycle(this, selector, preview, videoCapture)
    }

    @SuppressLint("MissingPermission", "UnsafeOptInUsageError")
    private fun record() {
        var pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(output).build())
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        // Запись не прерывается при смене камеры.
        pending = pending.asPersistentRecording()
        recording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    startNs = System.nanoTime()
                    onStarted?.invoke()
                }
                is VideoRecordEvent.Finalize -> {
                    val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                    if (!ok) Log.w(TAG, "finalize error ${event.error}: ${event.cause}")
                    val file = if (!cancelled && output.exists() && output.length() > 0) output else null
                    if (file == null) output.delete()
                    releaseCamera()
                    finished?.invoke(file)
                    finished = null
                }
                else -> Unit
            }
        }
    }

    /** Переключить камеру (в том числе во время записи). */
    fun switchCamera() {
        if (released) return
        front = !front
        try {
            bind()
        } catch (e: Throwable) {
            Log.e(TAG, "switch", e)
        }
    }

    /** Остановить. cancel = true - файл удаляется. [done] - готовый файл или null (в главном потоке). */
    fun stop(cancel: Boolean, done: (File?) -> Unit) {
        cancelled = cancel
        val r = recording
        recording = null
        if (r == null) {
            releaseCamera()
            if (cancel) output.delete()
            done(if (!cancel && output.exists() && output.length() > 0) output else null)
            return
        }
        finished = done
        r.stop()
    }

    private fun fail(msg: String) {
        if (released) return
        runCatching { recording?.close() }
        recording = null
        releaseCamera()
        output.delete()
        onError?.invoke(msg)
    }

    private fun releaseCamera() {
        if (released) return
        released = true
        runCatching { provider?.unbindAll() }
        registry.currentState = Lifecycle.State.DESTROYED
    }

    companion object {
        private const val TAG = "CameraXRecorder"
    }
}
