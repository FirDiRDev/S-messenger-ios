// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.json.JSONObject

/**
 * Расшифровка голосовых сообщений в текст.
 *
 * 1. Если в S-AI указан ключ Groq (бесплатный) - распознавание Whisper large-v3: лучшее качество
 *    русской речи, пунктуация, длинные сообщения.
 * 2. Иначе - распознавание речи телефона без интернета (Android 13+): голосовое раскодируется в
 *    звук 16 кГц и подаётся распознавателю по частям, поэтому паузы в речи не обрывают текст.
 * Готовый текст запоминается и показывается под голосовым.
 */
object VoiceTranscriber {
    private const val TAG = "VoiceTranscriber"
    private const val RATE = 16_000
    private val main = Handler(Looper.getMainLooper())

    // ---------- хранение ----------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("voice_text", Context.MODE_PRIVATE)
    private fun key(name: String, size: Long) = "$name|$size"

    fun get(ctx: Context, name: String, size: Long): String? = prefs(ctx).getString(key(name, size), null)
    private fun put(ctx: Context, name: String, size: Long, text: String) = prefs(ctx).edit().putString(key(name, size), text).apply()

    private val listeners = LinkedHashSet<() -> Unit>()
    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    // ---------- расшифровка ----------

    /** Есть ключ Groq - расшифровка через Whisper (разрешение микрофона не нужно). */
    fun hasCloud(ctx: Context): Boolean =
        runCatching { com.stoyko.smessenger.ai.AiConfig(ctx.applicationContext).keyFor("groq").isNotBlank() }.getOrDefault(false)

    /** [done] - текст или ошибка (на главном потоке). */
    fun transcribe(ctx: Context, file: File, name: String, size: Long, done: (Result<String>) -> Unit) {
        val app = ctx.applicationContext
        Thread({
            // Язык - как в приложении (Настройки → Язык), иначе - как в телефоне.
            val lang = (if (Build.VERSION.SDK_INT >= 24) runCatching { app.resources.configuration.locales[0].language }.getOrNull() else null)?.ifBlank { null }
                ?: Locale.getDefault().language.ifBlank { "ru" }
            val groq = runCatching { com.stoyko.smessenger.ai.AiConfig(app).keyFor("groq") }.getOrDefault("")
            if (groq.isNotBlank()) {
                val r = runCatching { whisper(file, groq, lang) }
                if (r.isSuccess && r.getOrThrow().isNotBlank()) {
                    finish(app, name, size, r.getOrThrow(), done)
                    return@Thread
                }
                Log.w(TAG, "whisper: ${r.exceptionOrNull()}")
            }
            if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(app)) {
                val pcm = runCatching { decodeToPcm16k(file) }.getOrElse {
                    main.post { done(Result.failure(it)) }
                    return@Thread
                }
                main.post { recognize(app, pcm, lang) { r -> r.onSuccess { t -> finish(app, name, size, t, done) }.onFailure { done(Result.failure(it)) } } }
                return@Thread
            }
            main.post { done(Result.failure(IllegalStateException(app.getString(com.stoyko.smessenger.R.string.voice_text_unavailable)))) }
        }, "voice-text").start()
    }

    private fun finish(ctx: Context, name: String, size: Long, text: String, done: (Result<String>) -> Unit) {
        var clean = text.trim().replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?…])"), "$1")
        // Заглавная буква в начале каждого предложения и точка в конце, если её нет.
        clean = clean.replace(Regex("([.!?…]\\s+)(\\p{Ll})")) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
            .replaceFirstChar { it.uppercase() }
        if (clean.isNotEmpty() && clean.last().isLetterOrDigit()) clean += "."
        put(ctx, name, size, clean)
        changed()
        main.post { done(Result.success(clean)) }
    }

    /** Whisper (Groq, OpenAI-совместимый /audio/transcriptions). */
    private fun whisper(file: File, key: String, lang: String): String {
        val boundary = "----sm" + System.nanoTime()
        val conn = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            conn.outputStream.use { out ->
                fun field(n: String, v: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
                }
                field("model", "whisper-large-v3")
                field("language", lang)
                field("response_format", "json")
                field("temperature", "0")
                // Подсказка стиля: знаки препинания и заглавные буквы в ответе.
                field("prompt", if (lang == "ru") "Привет! Это голосовое сообщение, записанное в мессенджере." else "Hi! This is a voice message recorded in a messenger.")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("text")
        } finally {
            conn.disconnect()
        }
    }

    /** AAC (.m4a) → PCM 16 бит, моно, 16 кГц. */
    private fun decodeToPcm16k(file: File): ByteArray {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                fmt = f
                break
            }
        }
        if (track < 0 || fmt == null) throw IllegalStateException("no audio")
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk)
                        raw.write(chunk)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            ex.release()
        }
        // Моно и 16 кГц. Перед уменьшением частоты - сглаживающий фильтр (иначе высокие
        // частоты «заворачиваются» и речь звучит для распознавателя грязно).
        val bytes = raw.toByteArray()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = sb.remaining() / channels.coerceAtLeast(1)
        if (frames <= 0) throw IllegalStateException("empty audio")
        val mono = FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += sb.get(f * channels + c)
            s / channels
        }
        return prepare(mono, rate)
    }

    /**
     * Подготовка звука для распознавания: убрать постоянную составляющую и гул (фильтр
     * ~80 Гц), сгладить перед уменьшением частоты до 16 кГц, выровнять громкость (тихие
     * сообщения - громче, без перегруза), обрезать тишину по краям и добавить короткие
     * паузы в начало и конец - так распознаватель не теряет первое и последнее слово.
     */
    internal fun prepare(src: FloatArray, rate: Int): ByteArray {
        val x = src.copyOf()
        // 1) Фильтр низких частот (гул, ветер): однополюсный high-pass ~80 Гц.
        val hpA = (1.0 / (1.0 + 2 * Math.PI * 80.0 / rate)).toFloat()
        var prevIn = 0f
        var prevOut = 0f
        for (i in x.indices) {
            val y = hpA * (prevOut + x[i] - prevIn)
            prevIn = x[i]
            prevOut = y
            x[i] = y
        }
        // 2) Сглаживание перед понижением частоты (двойной однополюсный low-pass ~7 кГц).
        if (rate > RATE) {
            val a = (1 - Math.exp(-2 * Math.PI * 7000.0 / rate)).toFloat()
            repeat(2) {
                var y = 0f
                for (i in x.indices) {
                    y += a * (x[i] - y)
                    x[i] = y
                }
            }
        }
        // 3) Новая частота 16 кГц (линейная интерполяция).
        val outLen = (x.size.toLong() * RATE / rate).toInt().coerceAtLeast(1)
        val r = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * rate / RATE
            val a = pos.toInt().coerceAtMost(x.size - 1)
            val b = (a + 1).coerceAtMost(x.size - 1)
            val t = (pos - a).toFloat()
            r[i] = x[a] * (1 - t) + x[b] * t
        }
        // 4) Тишина по краям: окна по 20 мс, порог - от уровня громких участков.
        val win = RATE / 50
        val rms = FloatArray((outLen + win - 1) / win) { w ->
            var s = 0.0
            val from = w * win
            val to = minOf(outLen, from + win)
            for (i in from until to) s += r[i] * r[i].toDouble()
            kotlin.math.sqrt(s / (to - from).coerceAtLeast(1)).toFloat()
        }
        val loud = rms.sortedArray().let { it[(it.size * 0.9).toInt().coerceAtMost(it.size - 1)] }
        val gate = maxOf(loud * 0.08f, 60f)
        var first = rms.indexOfFirst { it > gate }
        var last = rms.indexOfLast { it > gate }
        if (first < 0) {
            first = 0
            last = rms.size - 1
        }
        // Оставляем 200 мс запаса вокруг речи.
        val s0 = (first - 10).coerceAtLeast(0) * win
        val s1 = minOf(outLen, (last + 11) * win)
        // 5) Громкость: пик -3 дБ, усиление не больше ×8 (шум не раздуваем).
        var peak = 1f
        for (i in s0 until s1) peak = maxOf(peak, kotlin.math.abs(r[i]))
        val gain = (23_000f / peak).coerceIn(1f, 8f)
        val pad = RATE * 3 / 10
        val total = pad + (s1 - s0) + pad
        val out = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(pad) { out.putShort(0) }
        for (i in s0 until s1) out.putShort((r[i] * gain).toInt().coerceIn(-32767, 32767).toShort())
        repeat(pad) { out.putShort(0) }
        return out.array()
    }

    /** Распознавание речи телефона по готовому звуку (Android 13+), сессия по частям. */
    @android.annotation.TargetApi(33)
    private fun recognize(ctx: Context, pcm: ByteArray, lang: String, done: (Result<String>) -> Unit) {
        // Сначала - распознавание на самом телефоне; не вышло (пусто/ошибка) - через сервис
        // распознавания телефона (обычно точнее на длинных сообщениях).
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        recognizeOnce(ctx, pcm, lang, onDevice) { r ->
            if (r.isSuccess || !onDevice) done(r) else recognizeOnce(ctx, pcm, lang, false, done)
        }
    }

    @android.annotation.TargetApi(33)
    private fun recognizeOnce(ctx: Context, pcm: ByteArray, lang: String, onDevice: Boolean, done: (Result<String>) -> Unit) {
        val sr = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val parts = ArrayList<String>()
        var finished = false
        fun end(r: Result<String>) {
            if (finished) return
            finished = true
            runCatching { sr.destroy() }
            runCatching { pipe[0].close() }
            done(r)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) {
                segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            override fun onEndOfSegmentedSession() {
                if (parts.isEmpty()) end(Result.failure(IllegalStateException(ctx.getString(com.stoyko.smessenger.R.string.voice_text_empty))))
                else end(Result.success(parts.joinToString(" ")))
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (parts.isEmpty()) end(Result.failure(IllegalStateException(ctx.getString(com.stoyko.smessenger.R.string.voice_text_empty))))
                else end(Result.success(parts.joinToString(" ")))
            }
            override fun onError(error: Int) {
                if (parts.isNotEmpty()) end(Result.success(parts.joinToString(" ")))
                else end(Result.failure(IllegalStateException(ctx.getString(com.stoyko.smessenger.R.string.voice_text_error, error))))
            }
        })
        val tag = when (lang) {
            "ru" -> "ru-RU"
            "en" -> if (Locale.getDefault().language == "en") Locale.getDefault().toLanguageTag() else "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        sr.startListening(intent)
        // Звук подаём в «трубу» в фоне; закрытие конца - сигнал «звук кончился».
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    var off = 0
                    while (off < pcm.size) {
                        val n = minOf(6400, pcm.size - off)
                        out.write(pcm, off, n)
                        off += n
                    }
                }
            }
        }, "voice-text-feed").start()
        // Страховка: если распознаватель молчит - отдаём то, что есть.
        main.postDelayed({
            if (parts.isNotEmpty()) end(Result.success(parts.joinToString(" ")))
            else end(Result.failure(IllegalStateException(ctx.getString(com.stoyko.smessenger.R.string.voice_text_empty))))
        }, (pcm.size / (RATE * 2) * 1000L + 25_000L))
    }
}
