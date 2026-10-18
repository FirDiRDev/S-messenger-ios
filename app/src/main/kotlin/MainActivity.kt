// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import javax.inject.Inject
import com.stoyko.smessenger.di.ViewModelFactory
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.ui.contactlist.ARG_ADD_CONTACT
import com.stoyko.smessenger.ui.contactlist.ARG_SHARE

private const val TAG = "MainActivity"
// Принимаем свою схему ссылок и старую совместимую.
private val SCHEMES = listOf("smessenger:", "tox:")
private const val TOX_ID_LENGTH = 76

/** Системный запрос «не ограничивать работу в фоне»; если он недоступен - открываем настройки батареи. */
fun requestBatteryExemption(activity: android.app.Activity) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    try {
        @android.annotation.SuppressLint("BatteryLife")
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:" + activity.packageName))
        activity.startActivity(intent)
    } catch (e: Exception) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

/** ID разработчика мессенджера (Стойко Андрей) - для предложений и сообщений об ошибках. */
const val DEVELOPER_ID = "F8D9758FDE926935379CD87AD8F7021790D5180F4F6ADCDE95F4539149111245519F6107B930"

class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var vmFactory: ViewModelFactory

    @Inject
    lateinit var autoAway: AutoAway

    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var callManager: com.stoyko.smessenger.domain.feature.CallManager

    private var incoming: com.stoyko.smessenger.ui.call.IncomingCallScreen? = null

    // Подстройка под экран: очень крупный шрифт и узкие экраны (см. ScreenFit).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.stoyko.smessenger.ui.ScreenFit.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)

        // Заставка (AppTheme.Launch) показывается, пока грузится процесс; дальше - обычная тема.
        // Осенью и зимой (сезонное оформление) - весь мессенджер в цветах сезона.
        setTheme(
            when (runCatching { com.stoyko.smessenger.ui.Seasonal.current(this) }.getOrDefault(com.stoyko.smessenger.ui.Seasonal.Kind.NONE)) {
                com.stoyko.smessenger.ui.Seasonal.Kind.AUTUMN -> R.style.AppTheme_Autumn
                com.stoyko.smessenger.ui.Seasonal.Kind.WINTER -> R.style.AppTheme_Winter
                com.stoyko.smessenger.ui.Seasonal.Kind.SPRING -> R.style.AppTheme_Spring
                com.stoyko.smessenger.ui.Seasonal.Kind.SUMMER -> R.style.AppTheme_Summer
                else -> R.style.AppTheme
            },
        )
        super.onCreate(savedInstanceState)

        if (settings.disableScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Тема (день/ночь) применяется в App.onCreate - до создания экрана.

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // «3D-кнопки» (Персонализация): подключаем до создания экранов.
        com.stoyko.smessenger.ui.Buttons3d.install(this)
        com.stoyko.smessenger.ui.SeasonTint.install(this)
        setContentView(R.layout.activity_main)
        // Режим для слабовидящих: высокий контраст подписей; слежение за плавностью; 90/120 Гц.
        runCatching { com.stoyko.smessenger.ui.Accessibility.install(this) }
        runCatching { com.stoyko.smessenger.ui.LiteMode.watch(this) }
        runCatching { com.stoyko.smessenger.ui.FrameRate.apply(this) }
        // VPN ждал перезапуска для смены ядра (WARP ↔ серверы/DPI) - включаем.
        runCatching { com.stoyko.smessenger.vpn.GoGuard.resumePending(this) }
        // Журнал: какие экраны открывались перед ошибкой.
        runCatching {
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()
                ?.addOnDestinationChangedListener { _, dest, _ ->
                    val name = dest.label?.toString()?.ifBlank { null } ?: runCatching { resources.getResourceEntryName(dest.id) }.getOrDefault("?")
                    DiagnosticsExtra.screen = name
                    com.stoyko.smessenger.domain.Diagnostics.crumb("экран: $name")
                }
        }
        // Режим планшета: слева - список чатов, справа - чат/настройки (на телефонах не меняется).
        twoPane = com.stoyko.smessenger.ui.TwoPane.active(this)
        runCatching {
            com.stoyko.smessenger.ui.TwoPane.setup(this, savedInstanceState) {
                com.stoyko.smessenger.ui.contactlist.ContactListFragment().apply {
                    arguments = androidx.core.os.bundleOf(com.stoyko.smessenger.ui.TwoPane.ARG_SIDE to true)
                }
            }
        }
        // Запрет отката на старую версию, обязательное обновление, «Что нового» (один раз).
        runCatching { com.stoyko.smessenger.update.Updater.onLaunch(this) }
        // Плавная смена темы (если её только что переключили кнопкой «солнце/луна»).
        com.stoyko.smessenger.ui.ThemeSwitcher.onActivityCreated(this)

        // «Стекло» и общий проигрыватель голосовых/«кружков» (играет и в других чатах).
        com.stoyko.smessenger.ui.GlassFrameLayout.blurEnabled = settings.glassEffect
        com.stoyko.smessenger.ui.UiPrefs.load(settings)
        val content = findViewById<android.widget.FrameLayout>(android.R.id.content)
        miniPlayer = com.stoyko.smessenger.media.MiniPlayer(this, content).also { it.install() }
        floatingNote = com.stoyko.smessenger.media.FloatingVideoNote(this, content).also { it.install() }

        // Подкачка анимированных эмодзи и стикеров в фоне (раз в сутки) - потом они открываются сразу.
        window.decorView.postDelayed({ runCatching { com.stoyko.smessenger.chat.AnimatedEmoji.warmUp(this) } }, 8000)
        // Знакомство ещё впереди - его живые эмодзи качаем сразу, чтобы они играли без задержки.
        if (com.stoyko.smessenger.ui.Onboarding.shouldShow(this)) {
            runCatching { com.stoyko.smessenger.chat.AnimatedEmoji.prefetchNow(this, com.stoyko.smessenger.ui.Onboarding.emojiCodes()) }
        }

        // Иконка на рабочем столе - вместе с сезоном (режим «Авто») или выбранная вручную.
        runCatching { com.stoyko.smessenger.ui.AppIcon.apply(this) }
        // Красивая заставка при запуске (значок, кольца, название по буквам).
        if (savedInstanceState == null && com.stoyko.smessenger.ui.Kinetics.enabled) {
            runCatching { com.stoyko.smessenger.ui.IntroOverlay.show(this) }
        }

        if (!showCrashReportIfAny()) {
            askBatteryExemptionOnce()
            askFullScreenCallsOnce()
            showBatteryTipOnce()
        }

        // Only handle intent the first time it triggers the app.
        if (savedInstanceState != null) return
        handleIntent(intent)
    }

    /** Если прошлый запуск закончился вылетом - показываем отчёт, который можно отправить. */
    private fun showCrashReportIfAny(): Boolean {
        // Окно - только после настоящего вылета. Ошибки в фоне пишутся в журнал
        // (Настройки → «Журнал ошибок») и больше не всплывают при каждом запуске.
        val report = com.stoyko.smessenger.domain.Diagnostics.takePendingReport((application as App).lastExitWasCrash)
            ?: return false
        Thread {
            val text = buildString {
                append("StoykoMessenger ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ")
                append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ")
                append(android.os.Build.SUPPORTED_ABIS.joinToString()).append("\n\n")
                append(report)
                append("\n--- журнал (logcat) ---\n")
                append(readOwnLog())
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.crash_report_title)
                    .setMessage(getString(R.string.crash_report_hint) + "\n\n" + text.take(3000))
                    .setPositiveButton(R.string.share) { _, _ ->
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                getString(R.string.share),
                            ),
                        )
                    }
                    .setNeutralButton(R.string.copy_report) { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
        return true
    }

    /**
     * Один раз предлагаем убрать приложение из «экономии заряда». Иначе Android (особенно Xiaomi,
     * Huawei, Samsung) усыпляет сеть в фоне, и собеседник постоянно «отваливается».
     */
    /**
     * Совет для всех (один раз): какой режим расхода батареи выбрать, чтобы связь была стабильной,
     * а приложение не «ело» заряд. Кнопки сразу включают выбранный режим.
     */
    private fun showBatteryTipOnce() {
        val p = getSharedPreferences("battery_tip", MODE_PRIVATE)
        if (p.getBoolean("shown_361", false)) return
        p.edit().putBoolean("shown_361", true).apply()
        window.decorView.postDelayed({
            if (isFinishing) return@postDelayed
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.battery_tip_title)
                .setMessage(R.string.battery_tip_text)
                .setPositiveButton(R.string.battery_tip_balance) { _, _ ->
                    settings.batteryMode = 1
                    android.widget.Toast.makeText(this, R.string.battery_tip_set, android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.battery_tip_max) { _, _ ->
                    settings.batteryMode = 0
                    requestBatteryExemption(this)
                }
                .setNegativeButton(R.string.battery_prompt_later, null)
                .show()
        }, 4000)
    }

    private fun askBatteryExemptionOnce() {
        if (settings.batteryPromptShown || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        settings.batteryPromptShown = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.battery_prompt_title)
            .setMessage(R.string.battery_prompt_text)
            .setPositiveButton(R.string.battery_prompt_allow) { _, _ -> requestBatteryExemption(this) }
            .setNegativeButton(R.string.battery_prompt_later, null)
            .show()
    }

    /**
     * Android 14+ по умолчанию может запретить звонкам открываться на весь экран - тогда входящий
     * звонок виден только как уведомление при выключенном экране. Один раз просим разрешить.
     */
    private fun askFullScreenCallsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.canUseFullScreenIntent()) return
        val prefs = getSharedPreferences("prompts", MODE_PRIVATE)
        if (prefs.getBoolean("fullscreen_calls", false)) return
        prefs.edit().putBoolean("fullscreen_calls", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.fullscreen_calls_title)
            .setMessage(R.string.fullscreen_calls_text)
            .setPositiveButton(R.string.battery_prompt_allow) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(android.net.Uri.parse("package:$packageName")),
                    )
                }
            }
            .setNegativeButton(R.string.battery_prompt_later, null)
            .show()
    }

    /** Последние строки журнала приложения (свой журнал читать разрешено без особых прав). */
    private fun readOwnLog(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "600").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        p.destroy()
        val keys = listOf(
            "AndroidRuntime", "DEBUG", "libc", "crash", "FATAL", "SIG", "JNI", "toxcore", "Trifa",
            "Tox", "Diag", "Profile", "Service", "smessenger", "Room", "Exception", "Error",
        )
        lines.filter { l -> keys.any { l.contains(it, ignoreCase = true) } }.takeLast(250).joinToString("\n")
    } catch (e: Exception) {
        "журнал недоступен: $e"
    }

    override fun onStart() {
        super.onStart()
        com.stoyko.smessenger.ui.AppLock.onStart(this, settings)
    }

    override fun onStop() {
        // Свернули / экран выключился / открыли другое приложение - сохраняем прогресс сразу.
        com.stoyko.smessenger.ui.BackgroundSaver.saveAll()
        com.stoyko.smessenger.ui.AppLock.onStop(this)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Система готовится выгрузить приложение - ещё раз сохраняем всё.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) com.stoyko.smessenger.ui.BackgroundSaver.saveAll()
    }

    private var miniPlayer: com.stoyko.smessenger.media.MiniPlayer? = null
    private var floatingNote: com.stoyko.smessenger.media.FloatingVideoNote? = null

    override fun onDestroy() {
        miniPlayer?.uninstall()
        miniPlayer = null
        if (isFinishing) com.stoyko.smessenger.ui.chat.VoicePlayer.stop()
        floatingNote?.uninstall()
        floatingNote = null
        com.stoyko.smessenger.ui.AppLock.onDestroy()
        com.stoyko.smessenger.ui.DialogTracker.dismissAll(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        com.stoyko.smessenger.ui.BackgroundSaver.saveAll()
        if (com.stoyko.smessenger.games.GameNet.onInvite === gameInvite) com.stoyko.smessenger.games.GameNet.onInvite = null
        if (com.stoyko.smessenger.games.DurakNet.onInvite === durakInvite) com.stoyko.smessenger.games.DurakNet.onInvite = null
        if (com.stoyko.smessenger.update.Updater.foreground?.get() === this) com.stoyko.smessenger.update.Updater.foreground = null
        super.onPause()
        autoAway.onBackground()
    }

    private var updateCheckedThisLaunch = false
    private var twoPane = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Планшет повернули (или раскрыли складной телефон): одно окно <-> два окна.
        if (com.stoyko.smessenger.ui.TwoPane.active(this) != twoPane) recreate()
    }

    /** Приглашение в игру, пока приложение открыто - сразу окно «Принять / Отклонить». */
    private val gameInvite: (com.stoyko.smessenger.games.GameNet.ChessSave) -> Unit = { s ->
        runOnUiThread {
            runCatching {
                android.widget.Toast.makeText(this, getString(R.string.game_invite_in_chat, s.oppName.ifBlank { "?" }), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    /** Выбор фото из галереи для окон, собранных в коде (например, фото в S-AI). */
    private var imageCb: ((android.net.Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        val cb = imageCb
        imageCb = null
        if (uri != null) cb?.invoke(uri)
    }

    fun pickImage(cb: (android.net.Uri) -> Unit) {
        imageCb = cb
        runCatching { imagePicker.launch("image/*") }
    }

    private val durakInvite: (com.stoyko.smessenger.games.DurakNet.Save) -> Unit = { s ->
        runOnUiThread {
            runCatching {
                android.widget.Toast.makeText(this, getString(R.string.game_invite_in_chat, s.names.getOrElse(0) { "?" }), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Свайп «Назад» с резиновой оттяжкой на экране чата (см. ui/anim/SwipeBack). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val swipe = com.stoyko.smessenger.ui.anim.SwipeBack.active
        if (swipe != null && runCatching { swipe.dispatch(ev) { super.dispatchTouchEvent(it) } }.getOrDefault(false)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        com.stoyko.smessenger.games.GameNet.onInvite = gameInvite
        com.stoyko.smessenger.games.DurakNet.onInvite = durakInvite
        com.stoyko.smessenger.update.Updater.foreground = java.lang.ref.WeakReference(this)
        super.onResume()
        autoAway.onForeground()
        // Вернулись в приложение (в т. ч. после сна телефона): сверяем «в сети» контактов с ядром сразу.
        runCatching { (application as App).component.tox().requestPresenceSync() }
        // Экономия энергии: системный режим / «Экономия батареи» / мало заряда.
        runCatching { com.stoyko.smessenger.ui.EnergySaver.refresh(this) }
        runCatching { com.stoyko.smessenger.ui.LiteMode.refresh(this) }
        runCatching { com.stoyko.smessenger.ui.FrameRate.apply(this) }
        // Проверка обновлений при каждом входе в приложение (сама, в фоне; сеть - не чаще раза
        // в 30 минут). Небольшая пауза, чтобы не мешать открытию экрана.
        val cold = !updateCheckedThisLaunch
        updateCheckedThisLaunch = true
        window.decorView.postDelayed({
            // Первый вход после запуска (в т. ч. после перезапуска/закрытия) - проверка всегда.
            if (!isFinishing && !isDestroyed) com.stoyko.smessenger.update.Updater.autoCheck(this, force = cold)
        }, if (cold) 2500 else 4000)
        // Вернулись из оплаты пака монет - забрать подписанные чеки и показать зачисление.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                // Настройки магазина - из update.json (раз в 3 часа), затем - новые чеки.
                com.stoyko.smessenger.chat.CoinShop.refreshRemote(this) {
                    if (isFinishing || isDestroyed) return@refreshRemote
                    // Чеки + всё, что зачислилось в фоне; ждём оплату - частые проверки дальше.
                    com.stoyko.smessenger.chat.CoinShop.onResume(this) { n ->
                        if (n > 0 && !isFinishing && !isDestroyed) com.stoyko.smessenger.chat.CoinShop.celebrate(this, n)
                    }
                }
            }
        }, 3000)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            com.stoyko.smessenger.update.Updater.ACTION_SHOW_UPDATE -> {
                com.stoyko.smessenger.update.Updater.showFromNotification(this)
                intent.action = null
            }
            ACTION_INCOMING_CALL -> {
                val pk = intent.getStringExtra(KEY_CONTACT_PK) ?: return
                showOverLockScreen(true)
                showIncomingCall(pk)
                intent.action = null
            }
            ACTION_ACCEPT_CALL -> {
                val pk = intent.getStringExtra(KEY_CONTACT_PK) ?: return
                showOverLockScreen(true)
                acceptCall(pk)
                intent.action = null
            }
            com.stoyko.smessenger.vpn.SmVpnService.ACTION_OPEN_VPN -> com.stoyko.smessenger.vpn.VpnScreen(this).show()
            // Приглашение в игру из уведомления.
            com.stoyko.smessenger.games.GameNet.ACTION_OPEN_GAME -> {
                intent.action = null
                val id = intent.getStringExtra(com.stoyko.smessenger.games.GameNet.EXTRA_GAME)
                if (id != null && id.startsWith("durak:")) {
                    val ds = com.stoyko.smessenger.games.DurakNet.load(this, id.removePrefix("durak:"))
                    if (ds != null) com.stoyko.smessenger.games.GamesHub.askDurak(this, ds)
                    return
                }
                val s = id?.let { com.stoyko.smessenger.games.GameNet.load(this, it) }
                if (s != null) {
                    if (s.state == "incoming") com.stoyko.smessenger.games.GamesHub.askInvite(this, s) else com.stoyko.smessenger.games.ChessGame.show(this, s.id)
                }
            }
            // Уведомление «S-AI ответил» - сразу открываем чат с S-AI.
            com.stoyko.smessenger.ai.AiBackground.ACTION_OPEN -> {
                intent.action = null
                com.stoyko.smessenger.ai.AiChatDialog(this).show()
            }
            Intent.ACTION_VIEW -> handleToxLinkIntent(intent)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val files = sharedStreams(intent)
                when {
                    files.isNotEmpty() -> handleFileShare(files)
                    com.stoyko.smessenger.vpn.VpnLinks.isLink(text) -> com.stoyko.smessenger.vpn.VpnScreen.openWithLink(this, text)
                    else -> handleShareIntent(intent)
                }
                intent.action = null
            }
        }
    }

    /**
     * Любой запуск чужого экрана (открыть файл, поделиться, установить APK, открыть ссылку)
     * больше не может уронить приложение: при отказе в доступе к файлу пробуем через копию,
     * при отсутствии подходящего приложения - показываем сообщение.
     */
    /** Показывать экран поверх блокировки и включать дисплей (только на время звонка). */
    private fun showOverLockScreen(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** Полноэкранный входящий звонок. Закрывается сам, если звонок отменили. */
    private fun showIncomingCall(pk: String) {
        val contact = callManager.pendingCalls.value.firstOrNull { it.publicKey == pk }
        if (contact == null) {
            // Звонок уже закончился, пока телефон просыпался.
            showOverLockScreen(false)
            return
        }
        incoming?.dismiss()
        incoming = com.stoyko.smessenger.ui.call.IncomingCallScreen(
            this,
            pk,
            contact.name.ifEmpty { getString(R.string.contact_default_name) },
            onAccept = { acceptCall(pk) },
            onReject = {
                sendBroadcast(
                    Intent(this, ActionReceiver::class.java)
                        .putExtra(KEY_CONTACT_PK, pk)
                        .putExtra(KEY_ACTION, Action.CallReject),
                )
                showOverLockScreen(false)
            },
        ).also { it.show() }
        watchCalls()
    }

    private var callWatch: kotlinx.coroutines.Job? = null

    /** Следим за звонками: звонящий положил трубку - убираем экран; звонок закончился - снимаем показ поверх блокировки. */
    private fun watchCalls() {
        if (callWatch?.isActive == true) return
        callWatch = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(callManager.pendingCalls, callManager.inCall) { p, c -> p to c }
                .collect { (pending, state) ->
                    com.stoyko.smessenger.ui.ThemeMusic.set("call", pending.isNotEmpty() || state is com.stoyko.smessenger.domain.feature.CallState.InCall)
                    val screen = incoming
                    if (screen != null && pending.none { it.publicKey == screen.publicKey }) {
                        screen.dismiss()
                        incoming = null
                    }
                    if (pending.isEmpty() && state !is com.stoyko.smessenger.domain.feature.CallState.InCall) {
                        showOverLockScreen(false)
                    }
                }
        }
    }

    /**
     * «Принять»: сразу открываем экран звонка - он сам отвечает на звонок. Раньше звонок принимался
     * одновременно и в фоне, и экраном - отсюда «звонок в фоне» и ложное «уже идёт другой звонок».
     */
    private fun acceptCall(pk: String) {
        incoming?.dismiss()
        incoming = null
        val nav = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        val state = callManager.inCall.value
        if (state is com.stoyko.smessenger.domain.feature.CallState.InCall && state.publicKey.string() != pk) {
            android.widget.Toast.makeText(this, R.string.error_simultaneous_calls, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (nav.currentDestination?.id == R.id.callFragment) nav.popBackStack()
        nav.navigate(R.id.callFragment, bundleOf(com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY to pk))
        watchCalls()
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        try {
            @Suppress("DEPRECATION")
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: SecurityException) {
            Log.w(TAG, "start: $e")
            if (SafeLaunch.hasForeignUris(this, intent)) {
                SafeLaunch.retryWithCopies(this, intent) { fixed ->
                    try {
                        @Suppress("DEPRECATION")
                        super.startActivityForResult(fixed, requestCode, options)
                    } catch (e2: Exception) {
                        Log.w(TAG, "start retry: $e2")
                        android.widget.Toast.makeText(this, R.string.file_no_access, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                android.widget.Toast.makeText(this, R.string.file_no_access, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "start: $e")
            android.widget.Toast.makeText(this, R.string.no_app_to_open, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.stoyko.smessenger.vpn.VpnScreen.REQUEST_VPN) {
            com.stoyko.smessenger.vpn.VpnScreen.onPermissionResult(this, resultCode == RESULT_OK)
        }
        if (requestCode == com.stoyko.smessenger.ui.chat.ChatWallpaper.REQUEST_PICK && resultCode == RESULT_OK) {
            com.stoyko.smessenger.ui.chat.ChatWallpaper.onPicked(this, data?.data)
        }
        if (requestCode == com.stoyko.smessenger.vpn.VpnScreen.REQUEST_FILE && resultCode == RESULT_OK) {
            com.stoyko.smessenger.vpn.VpnScreen.onFilePicked(this, data?.data)
        }
    }

    private fun handleToxLinkIntent(intent: Intent) {
        val data = intent.dataString ?: ""
        Log.i(TAG, "Got uri with data: $data")
        // Ссылка VPN (vless://, vmess://, trojan://, ss://) - добавить во встроенный VPN, как в Happ.
        if (com.stoyko.smessenger.vpn.VpnLinks.isLink(data)) {
            com.stoyko.smessenger.vpn.VpnScreen.openWithLink(this, data)
            return
        }
        // Ссылка на узел в формате aTox: tox://КЛЮЧ@адрес:порт.
        if (data.contains("@") && data.startsWith("tox://", ignoreCase = true)) {
            val n = com.stoyko.smessenger.settings.Nodes.parseLink(data) ?: return
            com.stoyko.smessenger.settings.Nodes.confirmAdd(this, n) {
                (application as App).component.nodeRegistry().reset()
            }
            return
        }
        // Ссылка на узел (smessenger:node?host=..&key=..) - предложить добавить в «Мои узлы».
        if (data.contains("node?", ignoreCase = true)) {
            val n = com.stoyko.smessenger.settings.Nodes.parseLink(data) ?: return
            com.stoyko.smessenger.settings.Nodes.confirmAdd(this, n) {
                // Новый узел сразу используется: ядро переподключится при следующей попытке.
                (application as App).component.nodeRegistry().reset()
            }
            return
        }
        // Ссылка на прокси (smessenger:socks?server=..&port=..) - предложить добавить.
        if (data.contains("socks?", ignoreCase = true)) {
            val p = com.stoyko.smessenger.settings.Proxies.parse(data) ?: return
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.proxy_title)
                .setMessage(getString(R.string.proxy_add_from_link, p.id))
                .setPositiveButton(R.string.proxy_add) { _, _ ->
                    com.stoyko.smessenger.settings.Proxies.add(this, p)
                    com.stoyko.smessenger.settings.Proxies.show(this, settings) {}
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        // Ссылка на профиль (https://…/u/ID или smessenger:add/ID) - сразу заявка в друзья.
        com.stoyko.smessenger.ui.ProfileLink.parse(data)?.let { id ->
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()?.navigate(
                R.id.contactListFragment,
                bundleOf(com.stoyko.smessenger.ui.contactlist.ARG_AUTO_ADD to id),
            )
            return
        }
        val scheme = SCHEMES.firstOrNull { data.startsWith(it, ignoreCase = true) }
        if (scheme == null || data.length != scheme.length + TOX_ID_LENGTH) {
            Log.e(TAG, "Got malformed uri: $data")
            return
        }

        supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()?.navigate(
            R.id.contactListFragment,
            bundleOf(ARG_ADD_CONTACT to data.drop(scheme.length)),
        )
    }

    /**
     * «Написать разработчику»: уже в контактах - открывается чат, нет - экран добавления
     * с готовым ID разработчика (предложения по улучшению и сообщения об ошибках).
     */
    fun writeDeveloper() {
        supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()?.navigate(
            R.id.contactListFragment,
            bundleOf(ARG_ADD_CONTACT to DEVELOPER_ID),
        )
    }

    /** Отправить текст (сервер VPN, подписку, узел) контакту внутри StoykoMessenger. */
    fun shareToContact(text: String) {
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE to text))
    }

    @Suppress("DEPRECATION")
    private fun sharedStreams(intent: Intent): List<android.net.Uri> {
        val out = ArrayList<android.net.Uri>()
        runCatching {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            } else {
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            }
        }
        if (out.isEmpty()) {
            intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { out.add(it) } }
        }
        // Некоторые приложения кладут один и тот же файл в список дважды - иначе он уходил дважды.
        return out.distinct()
    }

    /**
     * Файлы из других приложений (проводник, Google, галерея, музыка): копируем к себе сразу,
     * пока действует временный доступ, и спрашиваем - в какой чат или в «Избранное».
     */
    private fun handleFileShare(uris: List<android.net.Uri>) {
        android.widget.Toast.makeText(this, R.string.preparing_file, android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val copies = uris.mapNotNull { com.stoyko.smessenger.ui.chat.AttachLauncher.copyToOutgoing(this, it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (copies.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.file_no_access, android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(resources.getQuantityString(R.plurals.share_files_title, copies.size, copies.size))
                    .setItems(
                        arrayOf<CharSequence>(getString(R.string.share_to_chat), "⭐ " + getString(R.string.saved_messages)),
                    ) { _, which ->
                        if (which == 1) {
                            copies.forEach { com.stoyko.smessenger.chat.SavedMessages.addUri(this, it) }
                            android.widget.Toast.makeText(this, R.string.saved_added, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val nav = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()
                                ?: return@setItems
                            nav.navigate(
                                R.id.contactListFragment,
                                bundleOf(com.stoyko.smessenger.ui.contactlist.ARG_SHARE_FILES to ArrayList(copies.map { it.toString() })),
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }, "share-copy").start()
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.type?.startsWith("text/") != true) {
            Log.e(TAG, "Got unsupported share type ${intent.type}")
            return
        }

        val data = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (data.isNullOrEmpty()) {
            Log.e(TAG, "Got share intent with no data")
            return
        }

        Log.i(TAG, "Got text share: $data")
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE to data))
    }
}
