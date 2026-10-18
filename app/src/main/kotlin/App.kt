// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2019 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import com.stoyko.smessenger.di.AppComponent
import com.stoyko.smessenger.di.DaggerAppComponent
import com.stoyko.smessenger.domain.Diagnostics

class App : Application() {
    val component: AppComponent by lazy {
        componentOverride ?: DaggerAppComponent.factory().create(applicationContext)
    }

    @VisibleForTesting
    var componentOverride: AppComponent? = null

    /**
     * Чем закончился прошлый запуск: true - вылет/зависание, false - штатно (выгружен системой,
     * смахнут, обновлён), null - неизвестно (Android < 11).
     */
    var lastExitWasCrash: Boolean? = null
        private set

    override fun attachBaseContext(base: android.content.Context) {
        // Импортирован полный профиль (.smsave): раскладываем переписку, настройки, игры и
        // файлы по местам самым первым делом - до того как прочитана хоть одна настройка (язык
        // читается прямо здесь, «лёгкий режим» - в onCreate): иначе Android держит в памяти
        // старые настройки и при первой записи затёр бы ими восстановленные. Здесь только
        // переносы уже проверенных файлов (быстро); при сбое всё возвращается как было.
        if (!isRestartProcess()) runCatching { com.stoyko.smessenger.profile.FullBackup.applyPending(base) }
        super.attachBaseContext(com.stoyko.smessenger.ui.AppLanguage.wrap(base))
    }

    private fun isRestartProcess(): Boolean = runCatching {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.app.Application.getProcessName()
        } else {
            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }
        // Любой вспомогательный процесс: «:restart» (перезапуск) и «:nova» (ядро WARP/MASQUE).
        name.endsWith(":restart") || name.endsWith(":nova")
    }.getOrDefault(false)

    override fun onCreate() {
        super.onCreate()
        // Процесс перезапуска (RestartActivity) - только открывает приложение заново:
        // ни базы, ни сети, ни служб в нём не запускаем.
        if (isRestartProcess()) return
        // Связь с ядром Nova (процесс «:nova»): состояние туннеля, передача сети, ключ MASQUE.
        runCatching { com.stoyko.smessenger.vpn.NovaLink.install(this) }
        runCatching { com.stoyko.smessenger.ui.LiteMode.refresh(this) }
        runCatching { com.stoyko.smessenger.ui.AnimPrefs.load(this) }
        // Музыка сезонной темы во всём приложении (сама молчит в фоне, в звонках, при видео).
        runCatching { com.stoyko.smessenger.ui.ThemeMusic.install(this) }
        // Скрытые чаты: открытые PIN-ом снова закрываются, когда приложение ушло в фон.
        runCatching { com.stoyko.smessenger.chat.HiddenChats.install(this) }
        // Ник и аватарка в запросе в друзья (вместо длинного ID у получателя).
        runCatching { com.stoyko.smessenger.chat.RequestCard.install(this) }
        // Обезличенные счётчики для разработчика (установки, в сети, версии) - без ID и данных.
        runCatching { com.stoyko.smessenger.stats.Analytics.start(this) }
        // Тяжёлые списки (тысячи VPN-серверов) разбираем заранее в фоне - не на главном потоке.
        runCatching {
            Thread({ runCatching { com.stoyko.smessenger.vpn.VpnStore(this).let { it.profiles(); it.subscriptions() } } }, "prewarm").apply { priority = Thread.MIN_PRIORITY }.start()
        }
        // Профиль из .smsave разложен (в attachBaseContext) - сказать, чем закончилось.
        runCatching {
            com.stoyko.smessenger.profile.FullBackup.takeRestoreResult(this)?.let { text ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
                }, 1500)
            }
        }
        // Картинки в чатах грузятся быстрее: 4-6 потоков вместо 3 и кэш в памяти побольше
        // (1/6 доступной памяти) - пролистанные фото не декодируются заново.
        runCatching {
            // Режим для слабых устройств: кэш 1/8 памяти и 3 потока - меньше пиковая нагрузка.
            val lite = com.stoyko.smessenger.ui.LiteMode.on
            val mem = (Runtime.getRuntime().maxMemory() / (if (lite) 8 else 6)).coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024).toInt()
            com.squareup.picasso.Picasso.setSingletonInstance(
                com.squareup.picasso.Picasso.Builder(this)
                    .memoryCache(com.squareup.picasso.LruCache(mem))
                    // Потоков по числу ядер: 8-ядерные POCO/OnePlus - 6 потоков, слабые - 3.
                    .executor(
                        java.util.concurrent.Executors.newFixedThreadPool(
                            if (lite) 3 else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(4, 6),
                        ),
                    )
                    .build(),
            )
        }
        // Подписки VPN обновляются сами (раз в 6 часов) - через 20 с после запуска, в фоне.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { com.stoyko.smessenger.vpn.VpnSubscriptions.autoUpdate(this) }
        }, 20_000)
        // Любой вылет (в любом потоке) записывается и показывается при следующем запуске.
        Diagnostics.init(filesDir)
        com.stoyko.smessenger.chat.GiftCatalog.init(this)
        com.stoyko.smessenger.games.GameNet.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Diagnostics.record("поток ${thread.name}", e, fatal = true)
            previous?.uncaughtException(thread, e)
        }

        // Ошибки в главном потоке (нажатия, открытие файлов) больше не закрывают приложение.
        CrashGuard.install(this)

        // Тему применяем здесь, до создания экрана: иначе экран пересоздаётся сразу после
        // запуска (мигание, «тёмный экран» и двойная загрузка на медленных телефонах).
        try {
            AppCompatDelegate.setDefaultNightMode(com.stoyko.smessenger.settings.Settings(this).theme)
        } catch (_: Exception) {
        }

        lastExitWasCrash = readLastExit()
        // Подробный журнал: состояние при ошибках, события, зависания, отчёты системы.
        runCatching { DiagnosticsExtra.install(this) }
    }

    private fun readLastExit(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return null
            when (last.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                -> true
                else -> false
            }
        } catch (_: Exception) {
            null
        }
    }
}
