// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.stoyko.smessenger.R
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Установка через системный PackageInstaller (сессия):
 *  - обновление самого мессенджера в фоне. На Android 12+ после первого такого обновления
 *    система разрешает ставить следующие без вопросов ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]);
 *    если подтверждение всё же нужно - открываем его сразу (приложение на экране) или
 *    показываем уведомление «Нажмите, чтобы установить»;
 *  - «разделённые» приложения из чата: `.apks` / `.xapk` (архив из base.apk и split_*.apk) -
 *    архив распаковывается и все части ставятся одной сессией, как обычное приложение.
 */
object SessionInstaller {
    private const val TAG = "SessionInstaller"
    const val ACTION_STATUS = "com.stoyko.smessenger.INSTALL_STATUS"
    private const val NOTIFY_ID = 0x5E23
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Можно ли ставить пакеты (Android 8+: разрешение «Установка неизвестных приложений»). */
    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Поставить готовые APK-файлы одной сессией ([self] - обновление самого мессенджера). */
    fun install(ctx: Context, apks: List<File>, self: Boolean) {
        val app = ctx.applicationContext
        io.execute {
            runCatching { commit(app, apks, self) }.onFailure { e ->
                Log.e(TAG, "install", e)
                main.post { Toast.makeText(app, app.getString(R.string.install_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun commit(ctx: Context, apks: List<File>, self: Boolean) {
        require(apks.isNotEmpty()) { "нет APK" }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (self) params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        params.setSize(apks.sumOf { it.length() })
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apks.forEachIndexed { i, f ->
                s.openWrite("part_$i.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_STATUS).putExtra("self", self)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /**
     * `.apks` / `.xapk` из чата: распаковать части (только *.apk, без путей вне папки) и
     * поставить. Перед этим спрашиваем разрешение на установку, если его нет.
     */
    fun installSplit(ctx: Context, uri: Uri, name: String) {
        if (!allowed(ctx)) {
            askPermission(ctx)
            return
        }
        val app = ctx.applicationContext
        Toast.makeText(ctx, R.string.install_unpacking, Toast.LENGTH_SHORT).show()
        io.execute {
            val dir = File(app.cacheDir, "split_install").apply {
                deleteRecursively()
                mkdirs()
            }
            val parts = ArrayList<File>()
            val result = runCatching {
                app.contentResolver.openInputStream(uri)!!.use { raw ->
                    ZipInputStream(raw.buffered(256 * 1024)).use { z ->
                        var total = 0L
                        while (true) {
                            val e = z.nextEntry ?: break
                            val base = e.name.substringAfterLast('/')
                            if (e.isDirectory || !base.endsWith(".apk", ignoreCase = true)) continue
                            // Защита: имя без путей; не больше 64 частей и 4 ГБ.
                            val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val f = File(dir, "${parts.size}_$safe")
                            f.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = z.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > 4L * 1024 * 1024 * 1024) throw IllegalStateException("слишком большой архив")
                                    out.write(buf, 0, n)
                                }
                            }
                            parts += f
                            if (parts.size > 64) throw IllegalStateException("слишком много частей")
                        }
                    }
                }
                // base.apk - первым (некоторые телефоны этого ждут).
                parts.sortBy { if (it.name.contains("base", ignoreCase = true)) 0 else 1 }
                if (parts.isEmpty()) throw IllegalStateException(app.getString(R.string.install_no_apk))
                commit(app, parts, self = false)
            }
            result.onFailure { e ->
                Log.e(TAG, "split $name", e)
                main.post { Toast.makeText(app, app.getString(R.string.install_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun askPermission(ctx: Context) {
        Toast.makeText(ctx, R.string.apk_allow_install, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Системе нужно подтверждение: на экране - сразу окно, иначе - уведомление. */
    internal fun confirm(ctx: Context, confirm: Intent, self: Boolean) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val act = Updater.foreground?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            if (runCatching { act.startActivity(confirm) }.isSuccess) return
        }
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = "updates_install"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(channel, ctx.getString(R.string.install_channel), android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = PendingIntent.getActivity(ctx, 0x5E24, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(if (self) R.string.install_update_ready else R.string.install_app_ready))
                .setContentText(ctx.getString(R.string.install_tap))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }
}

/** Итог установки от системы. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val self = intent.getBooleanExtra("self", false)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) SessionInstaller.confirm(context.applicationContext, confirm, self)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (!self) Toast.makeText(context, R.string.install_done, Toast.LENGTH_SHORT).show()
            }
            // Закрыли окно подтверждения - файл остаётся скачанным, предложим установить снова.
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (self) Updater.onSelfInstallAborted(context.applicationContext)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.w("SessionInstaller", "status $status $msg")
                if (self) Updater.onSelfInstallFailed(context.applicationContext, msg.ifBlank { status.toString() })
                Toast.makeText(context, context.getString(R.string.install_failed, msg.ifBlank { status.toString() }), Toast.LENGTH_LONG).show()
            }
        }
    }
}
