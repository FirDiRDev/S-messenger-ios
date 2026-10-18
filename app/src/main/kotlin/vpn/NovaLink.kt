// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Связь главного процесса с ядром Nova, которое работает в отдельном процессе «:nova».
 *
 * Зачем отдельный процесс. В приложении два ядра, собранных gomobile: Xray (libv2ray.aar,
 * libgojni.so) и Nova (WARP/AmneziaWG и Cloudflare MASQUE, libgnjni.so). Каждое несёт свой
 * рантайм Go, а два рантайма Go в одном процессе роняют его (общий слот TLS - разбор в проекте
 * Nova и в журнале 3.6.0). Ядро Nova перенесено в пакет `gn` (tools/relocate_gomobile_aar.py),
 * чтобы оба .aar поместились в один APK, а загружается оно только в процессе «:nova». Поэтому
 * переключение Xray ↔ WARP через Nova идёт без перезапуска приложения: каждое ядро живёт в
 * своём процессе, а процесс «:nova» после выключения VPN завершается целиком.
 *
 * Главный процесс НИКОГДА не трогает классы nova.* и gn.* - их статическая инициализация
 * загрузила бы второй рантайм Go сюда же.
 *
 * Обмен - широковещательными сообщениями внутри приложения (setPackage, не экспортируются):
 *  главный → nova:  STOP, PING, RECONNECT (+ новый конфиг), HANDOVER_OK;
 *  nova → главный:  STATE (состояние туннеля), HANDOVER (сейчас поднимется туннель Nova -
 *                   погасите своё ядро), IDENTITY (получен ключ MASQUE - сохранить).
 */
object NovaLink {
    private const val TAG = "NovaLink"
    private const val P = "com.stoyko.smessenger.nova."
    const val A_STATE = P + "STATE"
    const val A_HANDOVER = P + "HANDOVER"
    const val A_HANDOVER_OK = P + "HANDOVER_OK"
    const val A_IDENTITY = P + "IDENTITY"
    const val A_STOP = P + "STOP"
    const val A_PING = P + "PING"
    const val A_RECONNECT = P + "RECONNECT"

    const val X_STATUS = "status"
    const val X_NAME = "name"
    const val X_ERROR = "error"
    const val X_USER = "user"
    const val X_IDENTITY = "identity"
    const val X_TOKEN = "token"
    const val X_DEVICE = "device"
    const val X_WG_CONF = "wg_conf"
    const val X_CONF = "conf"

    /** Туннель сейчас ведёт ядро Nova (а не Xray/AmneziaWG главного процесса). */
    @Volatile
    var active = false

    @Volatile
    private var offLatch: CountDownLatch? = null

    /** Когда главный процесс последний раз просил Nova выключиться (запоздалые сообщения - мимо). */
    @Volatile
    private var stopAt = 0L

    /** Есть ли ядро Nova в этой сборке и для этого процессора (только arm/arm64). */
    fun available(ctx: Context): Boolean = runCatching {
        File(ctx.applicationInfo.nativeLibraryDir, "libgnjni.so").isFile
    }.getOrDefault(false)

    /** Отправить сообщение в другой процесс приложения. */
    fun send(ctx: Context, action: String, fill: Intent.() -> Unit = {}) {
        runCatching { ctx.sendBroadcast(Intent(action).setPackage(ctx.packageName).apply(fill)) }
            .onFailure { Log.w(TAG, "send $action: $it") }
    }

    /** Регистрация приёмника, который не виден другим приложениям. */
    fun register(ctx: Context, r: BroadcastReceiver, vararg actions: String) {
        val f = IntentFilter().apply { actions.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
    }

    /** Главный процесс: слушать ядро Nova. Вызывается один раз из App.onCreate. */
    fun install(app: Context) {
        val a = app.applicationContext
        runCatching {
            register(
                a,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) = onMessage(a, i)
                },
                A_STATE, A_HANDOVER, A_IDENTITY,
            )
        }.onFailure { Log.w(TAG, "install: $it") }
        // Приложение открыли заново, а туннель Nova всё это время работал - узнать его состояние.
        send(a, A_PING)
    }

    private fun onMessage(app: Context, i: Intent) {
        when (i.action) {
            A_STATE -> {
                val st = runCatching { VpnState.Status.valueOf(i.getStringExtra(X_STATUS).orEmpty()) }.getOrNull() ?: return
                val name = i.getStringExtra(X_NAME).orEmpty()
                if (st == VpnState.Status.Off || st == VpnState.Status.Error) {
                    offLatch?.countDown()
                    if (!active) return
                    active = false
                    if (st == VpnState.Status.Error) {
                        VpnState.set(VpnState.Status.Error, name, i.getStringExtra(X_ERROR).orEmpty())
                    }
                    SmVpnService.novaStopped(app, user = i.getBooleanExtra(X_USER, false), error = st == VpnState.Status.Error)
                    return
                }
                // Состояние пришло, а главный процесс о туннеле Nova не знал (его перезапустили).
                if (!active) {
                    // Только что сами выключили Nova (переход на Xray) - это запоздавшее сообщение.
                    if (android.os.SystemClock.elapsedRealtime() - stopAt < 8000) return
                    if (!SmVpnService.novaWanted(app)) {
                        // Пользователь уже выключил VPN - туннель Nova остался «сиротой».
                        send(app, A_STOP)
                        return
                    }
                    active = true
                }
                VpnState.localProxy = false
                VpnState.set(st, name.ifBlank { VpnState.profileName })
                if (st == VpnState.Status.On) {
                    runCatching { VpnWatchdog.start(app) }
                    SmVpnService.novaOn(app)
                }
            }
            A_HANDOVER -> {
                // Nova сейчас поднимет свой туннель: гасим ядро главного процесса (Xray, DPI,
                // AmneziaWG) и только потом отвечаем - иначе два туннеля спорили бы за сеть.
                Thread({
                    runCatching { SmVpnService.releaseForNova(app) }.onFailure { Log.w(TAG, "release: $it") }
                    send(app, A_HANDOVER_OK)
                }, "nova-handover").apply { isDaemon = true }.start()
            }
            A_IDENTITY -> {
                val store = VpnStore(app)
                i.getStringExtra(X_IDENTITY)?.takeIf { it.isNotBlank() }?.let { store.masqueIdentity = it }
                i.getStringExtra(X_TOKEN)?.takeIf { it.isNotBlank() }?.let { store.warpToken = it }
                i.getStringExtra(X_DEVICE)?.takeIf { it.isNotBlank() }?.let { store.warpDeviceId = it }
                // Регистрация заодно дала собственный аккаунт WARP - если своего конфига ещё нет,
                // берём его (он надёжнее ключей из генераторов).
                i.getStringExtra(X_WG_CONF)?.takeIf { it.isNotBlank() && store.warpConf.isBlank() }?.let { store.warpConf = it }
            }
        }
    }

    /** Попросить Nova погасить туннель (без ожидания). */
    fun stop(ctx: Context) {
        stopAt = android.os.SystemClock.elapsedRealtime()
        val was = active
        active = false
        if (was) runCatching { VpnWatchdog.stop() }
        send(ctx.applicationContext, A_STOP)
    }

    /**
     * Погасить туннель Nova и дождаться, пока он отпустит сеть (перед запуском Xray/AmneziaWG
     * в главном процессе). Не из главного потока.
     */
    fun stopAndWait(ctx: Context, timeoutMs: Long = 4000) {
        if (!active) {
            // На всякий случай (главный процесс мог не знать о туннеле) - без ожидания.
            stopAt = android.os.SystemClock.elapsedRealtime()
            send(ctx.applicationContext, A_STOP)
            return
        }
        val l = CountDownLatch(1)
        offLatch = l
        stop(ctx)
        runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }
        offLatch = null
    }

    /** Сторож VPN: туннель Nova не отвечает - переподключить (с новым конфигом WARP, если дан). */
    fun reconnect(ctx: Context, conf: String? = null) {
        send(ctx.applicationContext, A_RECONNECT) { conf?.let { putExtra(X_CONF, it) } }
    }
}
