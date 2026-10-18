// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalForeignApi::class)

package com.stoyko.smessenger.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_get_main_queue
import platform.posix.memset
import platform.zlib.Z_BEST_COMPRESSION
import platform.zlib.Z_DATA_ERROR
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_MEM_ERROR
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_STREAM_ERROR
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// ------------------------------------------------------------------ raw deflate (zlib, windowBits = -15)

private const val CHUNK = 16 * 1024

private fun List<ByteArray>.concat(): ByteArray {
    val out = ByteArray(sumOf { it.size })
    var pos = 0
    for (c in this) { c.copyInto(out, pos); pos += c.size }
    return out
}

actual fun rawDeflate(input: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    memset(strm.ptr, 0, sizeOf<z_stream>().convert())
    check(
        deflateInit2_(strm.ptr, Z_BEST_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK
    ) { "deflateInit2 failed" }
    val chunks = ArrayList<ByteArray>()
    val src = if (input.isEmpty()) ByteArray(1) else input // addressOf(0) у пустого массива недопустим
    val buf = ByteArray(CHUNK)
    try {
        src.usePinned { inPin ->
            buf.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                do {
                    strm.next_out = outPin.addressOf(0).reinterpret()
                    strm.avail_out = CHUNK.convert()
                    val rc = deflate(strm.ptr, Z_FINISH)
                    check(rc != Z_STREAM_ERROR) { "deflate failed" }
                    val have = CHUNK - strm.avail_out.toInt()
                    if (have > 0) chunks += buf.copyOf(have)
                } while (strm.avail_out == 0u)
            }
        }
    } finally {
        deflateEnd(strm.ptr)
    }
    chunks.concat()
}

actual fun rawInflate(input: ByteArray, maxOut: Int): ByteArray = memScoped {
    if (input.isEmpty()) return@memScoped ByteArray(0)
    val strm = alloc<z_stream>()
    memset(strm.ptr, 0, sizeOf<z_stream>().convert())
    check(inflateInit2_(strm.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) { "inflateInit2 failed" }
    val chunks = ArrayList<ByteArray>()
    var total = 0
    val buf = ByteArray(CHUNK)
    try {
        input.usePinned { inPin ->
            buf.usePinned { outPin ->
                strm.next_in = inPin.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                while (total < maxOut) {
                    strm.next_out = outPin.addressOf(0).reinterpret()
                    strm.avail_out = CHUNK.convert()
                    val rc = inflate(strm.ptr, Z_NO_FLUSH)
                    if (rc == Z_NEED_DICT || rc == Z_DATA_ERROR || rc == Z_MEM_ERROR || rc == Z_STREAM_ERROR) {
                        error("inflate failed: $rc")
                    }
                    val have = CHUNK - strm.avail_out.toInt()
                    if (have > 0) { chunks += buf.copyOf(have); total += have }
                    if (rc == Z_STREAM_END) break
                    if (have == 0 && strm.avail_in == 0u) break // данные оборваны - отдаём, что есть
                }
            }
        }
    } finally {
        inflateEnd(strm.ptr)
    }
    chunks.concat()
}

// ------------------------------------------------------------------ календарь и настройки

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun nowNanos(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()

actual fun currentMonth(): Int =
    NSCalendar.currentCalendar.component(NSCalendarUnitMonth, fromDate = NSDate()).toInt()

actual class KeyValueStore actual constructor(private val name: String) {
    private val d = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$name.$key"

    actual fun getBoolean(key: String, def: Boolean): Boolean =
        if (d.objectForKey(k(key)) == null) def else d.boolForKey(k(key))

    actual fun putBoolean(key: String, value: Boolean) = d.setBool(value, forKey = k(key))

    actual fun getInt(key: String, def: Int): Int =
        if (d.objectForKey(k(key)) == null) def else d.integerForKey(k(key)).toInt()

    actual fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), forKey = k(key))

    actual fun getString(key: String, def: String): String = d.stringForKey(k(key)) ?: def

    actual fun putString(key: String, value: String) = d.setObject(value, forKey = k(key))
}

// ------------------------------------------------------------------ сеть (NWPathMonitor)

actual class NetworkMonitor actual constructor() {
    private val flow = MutableStateFlow(NetState())
    actual val state: StateFlow<NetState> get() = flow
    private var monitor: nw_path_monitor_t = null

    actual fun start() {
        if (monitor != null) return
        val m = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(m) { path ->
            flow.value = NetState(
                online = nw_path_get_status(path) == nw_path_status_satisfied,
                expensive = nw_path_is_expensive(path),   // сотовая сеть / точка доступа
                constrained = nw_path_is_constrained(path), // «Экономия данных» в настройках iOS
            )
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
        monitor = m
    }

    actual fun stop() {
        monitor?.let { nw_path_monitor_cancel(it) }
        monitor = null
    }
}

// ------------------------------------------------------------------ фон (BGTaskScheduler / beginBackgroundTask)

actual object BackgroundWork {
    /** Должен совпадать с BGTaskSchedulerPermittedIdentifiers в Info.plist (iosApp/project.yml). */
    const val REFRESH_ID = "com.stoyko.smessenger.refresh"

    private val scope = MainScope()
    private var registered = false

    actual var onRefresh: (suspend () -> Unit)? = null

    actual fun register() {
        if (registered) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(REFRESH_ID, usingQueue = null) { task ->
            val t = task ?: return@registerForTaskWithIdentifier
            scheduleRefresh(15 * 60) // следующее пробуждение
            var done = false
            fun finish(ok: Boolean) {
                if (done) return
                done = true
                t.setTaskCompletedWithSuccess(ok)
            }
            val job = scope.launch {
                val ok = runCatching { onRefresh?.invoke() }.isSuccess
                finish(ok)
            }
            // iOS даёт ~30 с; не успели - отменяем и честно сообщаем системе.
            t.expirationHandler = {
                job.cancel()
                finish(false)
            }
        }
    }

    actual fun scheduleRefresh(minDelaySec: Long) {
        val req = BGAppRefreshTaskRequest(identifier = REFRESH_ID)
        req.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(minDelaySec.toDouble())
        // Ошибка (например, в симуляторе или без UIBackgroundModes=fetch) не критична.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(req, error = null)
    }

    actual fun finishInBackground(name: String, block: suspend () -> Unit) {
        val app = UIApplication.sharedApplication
        var id = UIBackgroundTaskInvalid
        var job: Job? = null
        fun end() {
            if (id != UIBackgroundTaskInvalid) {
                app.endBackgroundTask(id)
                id = UIBackgroundTaskInvalid
            }
        }
        id = app.beginBackgroundTaskWithName(name) {
            job?.cancel()
            end()
        }
        job = scope.launch {
            try { block() } finally { end() }
        }
    }
}
