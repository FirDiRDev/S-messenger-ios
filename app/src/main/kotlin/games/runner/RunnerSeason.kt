// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.util.Calendar

/**
 * Сезонные темы «Бега по рельсам»: у каждой - своя погода, небо и музыка.
 *  - ❄ Зима (Новый год): снегопад, снег на крышах и земле, Санта и олень;
 *  - 🌸 Весна: лепестки сакуры и нежное небо;
 *  - ☀ Лето: тёплое солнце и летние искорки;
 *  - 🍂 Осень: листопад и золотое небо.
 * Режим: «Авто» (по умолчанию: зима - с 1 декабря по 1 февраля, весна - март…май, лето -
 * июнь…август, осень - сентябрь…ноябрь), любой сезон вручную или «Выключено».
 * Музыку сезона можно выключить отдельно - тогда играет обычная музыка игры.
 */
object RunnerSeason {
    const val NONE = 0
    const val WINTER = 1
    const val SPRING = 2
    const val SUMMER = 3
    const val AUTUMN = 4

    /** Режимы по кругу: авто, зима, весна, лето, осень, выключено. */
    const val MODE_AUTO = 0
    const val MODE_OFF = 5

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)

    fun mode(ctx: Context): Int {
        val p = prefs(ctx)
        if (!p.contains("season_mode") && p.contains("winter_mode")) {
            // Прежний переключатель зимы (4.9.6): «всегда» → зима, «выключена» → выкл.
            val m = when (p.getInt("winter_mode", 0)) {
                1 -> WINTER
                2 -> MODE_OFF
                else -> MODE_AUTO
            }
            p.edit().putInt("season_mode", m).apply()
        }
        return p.getInt("season_mode", MODE_AUTO).coerceIn(MODE_AUTO, MODE_OFF)
    }

    fun next(ctx: Context): Int = ((mode(ctx) + 1) % 6).also { prefs(ctx).edit().putInt("season_mode", it).apply() }

    fun bySeasonDate(now: Calendar = Calendar.getInstance()): Int = when (now.get(Calendar.MONTH)) {
        Calendar.DECEMBER, Calendar.JANUARY -> WINTER
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> SPRING
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> SUMMER
        Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> AUTUMN
        else -> NONE
    }

    fun current(ctx: Context): Int = when (val m = mode(ctx)) {
        MODE_AUTO -> bySeasonDate()
        MODE_OFF -> NONE
        else -> m
    }

    fun musicOn(ctx: Context) = prefs(ctx).getBoolean("season_music", true)
    fun setMusicOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("season_music", v).apply()

    /** Файл музыки сезона (assets/music/…_loop.ogg) или null. */
    fun track(season: Int): String? = when (season) {
        // Музыка - только у зимнего события; остальные сезоны играют обычную музыку игры.
        WINTER -> "winter_loop"
        else -> null
    }
}

/**
 * Музыка сезона: трек из assets (OGG) раскодируется один раз в память и играется через
 * AudioTrack с точной до сэмпла петлёй - вступление звучит один раз, дальше середина
 * повторяется бесконечно. Стык петли подобран по похожему месту трека и сглажен
 * перекрёстным затуханием, поэтому не слышно ни концовки, ни «начала заново».
 */
object SeasonMusic {
    /** Какой трек играть (имя без расширения, см. [RunnerSeason.track]). */
    @Volatile var track: String = "winter_loop"
        set(v) {
            if (field == v) return
            field = v
            // Другой сезон - прежний трек выгружаем из памяти, новый раскодируется заново.
            release()
            synchronized(lock) { pcm = null }
        }

    private class Pcm(val name: String, val data: ShortArray, val channels: Int, val rate: Int, val loopStart: Int)

    /** Громкость (0..1): в игре громче, фоном в приложении - тише. */
    @Volatile var volume: Float = 0.8f
        set(v) {
            field = v.coerceIn(0f, 1f)
            worker?.track?.let { t -> runCatching { t.setVolume(field) } }
        }

    @Volatile private var pcm: Pcm? = null
    private val lock = Any()
    private var worker: Worker? = null

    /** Раскодировать заранее (в фоне), чтобы музыка началась вместе с забегом. */
    fun prepare(ctx: Context) {
        if (pcm?.name == track) return
        val app = ctx.applicationContext
        Thread({ runCatching { load(app) } }, "season-decode").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun load(ctx: Context): Pcm? = synchronized(lock) {
        val name = track
        pcm?.takeIf { it.name == name }?.let { return it }
        pcm = null
        val meta = runCatching {
            org.json.JSONObject(ctx.assets.open("music/$name.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        val decoded = decode(ctx, "music/$name.ogg") ?: return null
        val (data, ch, rate) = decoded
        val frames = data.size / ch
        val loopFrame = (meta?.optInt("loopStart", 0) ?: 0).coerceIn(0, (frames - rate).coerceAtLeast(0))
        Pcm(name, data, ch, rate, loopFrame * ch).also { pcm = it }
    }

    private fun decode(ctx: Context, file: String): Triple<ShortArray, Int, Int>? {
        val ex = MediaExtractor()
        val afd = ctx.assets.openFd(file)
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var out = ShortArray(rate * ch * 100)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val cnt = sb.remaining()
                        if (n + cnt > out.size) out = out.copyOf(maxOf(out.size * 3 / 2, n + cnt))
                        sb.get(out, n, cnt)
                        n += cnt
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            return Triple(out.copyOf(n - n % ch), ch, rate)
        } finally {
            runCatching { ex.release() }
            runCatching { afd.close() }
        }
    }

    fun play(ctx: Context) {
        val app = ctx.applicationContext
        val w = worker?.takeIf { it.alive } ?: Worker(app).also {
            worker = it
            it.start()
        }
        w.want = true
        w.track?.let { t -> runCatching { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() } }
    }

    fun pause() {
        val w = worker ?: return
        w.want = false
        w.track?.let { t -> runCatching { t.pause() } }
    }

    fun release() {
        val w = worker ?: return
        worker = null
        w.alive = false
        w.want = false
        w.track?.let { t -> runCatching { t.pause(); t.flush(); t.stop() } }
        w.interrupt()
    }

    private class Worker(val ctx: Context) : Thread() {
        @Volatile var alive = true
        @Volatile var want = false
        @Volatile var track: AudioTrack? = null

        init {
            isDaemon = true
            name = "runner-season-music"
        }

        override fun run() {
            val p = runCatching { load(ctx) }.getOrNull()
            if (p == null || !alive) {
                alive = false
                return
            }
            val chMask = if (p.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val t = runCatching {
                val min = AudioTrack.getMinBufferSize(p.rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                val size = maxOf(min * 2, p.rate * p.channels / 2)
                if (Build.VERSION.SDK_INT >= 23) {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(p.rate).setChannelMask(chMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(size)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(AudioManager.STREAM_MUSIC, p.rate, chMask, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
                }
            }.getOrNull()
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t?.release() }
                alive = false
                return
            }
            runCatching { t.setVolume(volume) }
            track = t
            val data = p.data
            try {
                var pos = 0
                while (alive) {
                    if (!want) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                        sleep(30)
                        continue
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    val n = minOf(4096, data.size - pos)
                    val wr = t.write(data, pos, n)
                    if (wr <= 0) {
                        if (!alive) break
                        sleep(15)
                        continue
                    }
                    pos += wr
                    // Конец петли - сразу на её начало (вступление больше не повторяется).
                    if (pos >= data.size) pos = p.loopStart
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
            } finally {
                alive = false
                track = null
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }
}
