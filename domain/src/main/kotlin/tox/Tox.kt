// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.content.Context
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.forEach as kForEach
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.UserRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FileKind
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.UserStatus

private const val TAG = "Tox"
private const val SLOW_ITERATION_LIMIT_MS = 10
// Используем все известные узлы и все их TCP-порты (443 - первым): больше шансов пробиться через фильтры.
private const val BOOTSTRAP_NODE_COUNT = 64
private const val PERIODIC_SAVE_MS = 5 * 60 * 1000L
private const val BOOTSTRAP_RETRY_MS = 3_000L
private const val BOOTSTRAP_MAX_RETRY_MS = 60_000L
private const val FAST_ITERATION_MS = 1L
// Найденный адрес узла считаем верным 6 часов: не дёргаем DNS на каждой попытке подключения.
private const val DNS_FRESH_MS = 6 * 3600_000L
// Первые секунды после запуска ядро грузит сохранённые узлы - долгий первый проход нормален.
private const val WARMUP_MS = 15_000L

@Singleton
class Tox @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val contactRepository: ContactRepository,
    private val userRepository: UserRepository,
    private val saveManager: SaveManager,
    private val nodeRegistry: BootstrapNodeRegistry,
    private val groupListener: GroupEventListener,
) {
    val toxId: ToxID get() = tox.getToxId()
    // Ключ читается заново после каждого запуска ядра (импорт другого профиля в том же процессе).
    @Volatile
    private var cachedPublicKey: PublicKey? = null
    val publicKey: PublicKey
        get() = cachedPublicKey ?: tox.getPublicKey().also { cachedPublicKey = it }
    var nospam: Int
        get() = tox.getNospam()
        set(value) = tox.setNospam(value)

    @Volatile
    var started = false

    @Volatile
    var isBootstrapNeeded = true
        set(v) {
            // Новый запрос подключения (было подключено, а теперь нужно снова) - пробуем сразу.
            // Неудачная попытка тоже ставит true, но поле уже true - тогда ждём паузу, а не
            // повторяем без остановки (раньше без сети подключение к узлам крутилось непрерывно).
            if (v && !field) {
                bootstrapBackoff = BOOTSTRAP_RETRY_MS
                lastBootstrapAttempt = 0L
            }
            field = v
        }

    /** Подключиться к узлам заново прямо сейчас (сменилась сеть, Tor готов, перезапуск). */
    fun kickBootstrap() {
        bootstrapBackoff = BOOTSTRAP_RETRY_MS
        lastBootstrapAttempt = 0L
        isBootstrapNeeded = true
    }

    @Volatile
    private var bootstrapBackoff = BOOTSTRAP_RETRY_MS

    /** Есть ли у телефона сеть вообще (без неё подключение к узлам бессмысленно). */
    private fun hasNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= 23) cm.activeNetwork != null else @Suppress("DEPRECATION") (cm.activeNetworkInfo?.isConnected == true)
    }.getOrDefault(true)

    /** Подключились к сети - в следующий раз снова начинаем с лучших узлов. */
    fun onConnected() {
        bootstrapRound = 0
    }
    private var lastBootstrapAttempt = 0L

    private var running = false
    private var bootstrapJob: Job? = null
    private var toxAvRunning = false

    var password: String? = null
        private set

    // Пароль профиля: шифрование делает нативная библиотека при сохранении (tox_pass_encrypt).
    fun changePassword(new: String?) {
        password = new?.ifEmpty { null }
        tox.passphrase = password.orEmpty()
        save()
    }

    private lateinit var tox: ToxWrapper

    /** Бросает [ToxInitException], если профиль не удалось открыть (неверный пароль, формат, прокси). */
    fun start(saveOption: SaveOptions, password: String?, listener: ToxEventListener, avListener: ToxAvEventListener) {
        tox = ToxWrapper(listener, avListener, groupListener, saveOption, File(context.filesDir, "toxcore"), password)
        cachedPublicKey = null

        this.password = password
        started = true

        fun loadContacts() = scope.launch {
            // Сброс «в сети» - в iterateForever, до первого прохода ядра (иначе гонка с первыми событиями).
            for ((publicKey, _) in tox.getContacts()) {
                if (!contactRepository.exists(publicKey.string())) {
                    contactRepository.add(Contact(publicKey.string()))
                }
            }
        }

        fun iterateForeverAv() = scope.launch {
            toxAvRunning = true
            while (running) {
                tox.iterateAv()
                val av = tox.iterationIntervalAv()
                // Без звонка звуковой цикл почти не нужен - не будим процессор 10 раз в секунду.
                delay(if (av >= 100) com.stoyko.smessenger.domain.feature.PowerHint.avIdleInterval() else av)
            }
            toxAvRunning = false
        }

        var lastPeriodicSave = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()

        fun iterateForever() = scope.launch {
            userRepository.updateConnection(publicKey.string(), ConnectionStatus.None)
            // Статусы прошлого запуска (процесс убит, ядро перезапущено) недостоверны: все «не в сети»,
            // пока ядро не сообщит заново. Строго до первого tox.iterate - иначе сброс мог затереть
            // уже пришедшее «в сети», и контакт оставался «не в сети» до следующей смены статуса.
            contactRepository.resetTransientData()
            presenceSyncRequested = false
            lastPresenceSync = System.currentTimeMillis()
            while (running || toxAvRunning) {
                val now = System.currentTimeMillis()
                // Сверка «в сети» с ядром: по запросу (вернулись в приложение, восстановилась связь)
                // и периодически - чаще при включённом экране.
                if (running && (presenceSyncRequested || now - lastPresenceSync > presenceSyncInterval())) {
                    presenceSyncRequested = false
                    lastPresenceSync = now
                    try {
                        tox.syncConnectionStatus()
                    } catch (e: Exception) {
                        Log.w(TAG, "Presence sync failed: $e")
                    }
                }
                // Без сети - реже (раз в минуту); явный запрос (kickBootstrap) - сразу, в любом
                // случае (локальная сеть Wi-Fi Direct системе может казаться «без сети»).
                // Сеть проверяем только когда подключение действительно нужно (не каждый проход ядра).
                if (isBootstrapNeeded && bootstrapJob?.isActive != true && now - lastBootstrapAttempt > bootstrapBackoff &&
                    (lastBootstrapAttempt == 0L || now - lastBootstrapAttempt > BOOTSTRAP_MAX_RETRY_MS || hasNetwork())
                ) {
                    lastBootstrapAttempt = now
                    // Пауза между неудачными попытками растёт 3 → 6 → 12 … 60 с (без сети и при
                    // недоступных узлах не крутим DNS каждые 3 с); удалось - снова 3 с.
                    bootstrapBackoff = (bootstrapBackoff * 2).coerceAtMost(BOOTSTRAP_MAX_RETRY_MS)
                    // Подключение к узлам - в отдельном потоке: поиск адресов (DNS) бывает долгим,
                    // и ядро не должно на это время замирать (иначе "приложение не отвечает").
                    bootstrapJob = scope.launch(Dispatchers.IO) {
                        isBootstrapNeeded = !bootstrap()
                        if (!isBootstrapNeeded) bootstrapBackoff = BOOTSTRAP_RETRY_MS
                    }
                }

                // Раз в 5 минут сохраняем профиль: в нём и найденные узлы сети - после перезапуска
                // связь восстанавливается за секунды, а не заново через начальные узлы.
                if (now - lastPeriodicSave > PERIODIC_SAVE_MS) {
                    lastPeriodicSave = now
                    save()
                }

                val before = System.currentTimeMillis()
                tox.iterate()
                // Идёт передача файлов: ядро запрашивает следующие куски внутри iterate - если оно
                // отработало быстро, крутим его ещё раз сразу (скорость выше, пакеты не теряются:
                // передача файлов в ядре надёжная, с подтверждениями и контролем перегрузки).
                if (com.stoyko.smessenger.domain.feature.TransferMonitor.busy) {
                    var extra = 0
                    // 4.1.0: больше проходов за цикл (до 16 за 10 мс) - быстрее приём и отправка.
                    // Во время звонка пачка короче (до 4 за 4 мс): звук принимается этим же циклом,
                    // и длинная пачка передачи файла давала бы рывки голоса.
                    val call = com.stoyko.smessenger.domain.feature.PowerHint.inCall
                    val maxExtra = if (call) 4 else 16
                    val budgetMs = if (call) 4 else 10
                    while (extra < maxExtra && System.currentTimeMillis() - before < budgetMs) {
                        tox.iterate()
                        extra++
                    }
                }
                val timeTaken = System.currentTimeMillis() - before
                // Во время передачи файлов крутим ядро чаще: куски запрашиваются и уходят
                // внутри iterate, так что скорость передачи растёт в разы.
                val baseInterval = tox.iterationInterval()
                val iterationInterval = if (com.stoyko.smessenger.domain.feature.TransferMonitor.busy) {
                    minOf(baseInterval, FAST_ITERATION_MS)
                } else {
                    // Экран выключен / экономия батареи - реже (меньше пробуждений процессора).
                    com.stoyko.smessenger.domain.feature.PowerHint.interval(baseInterval)
                }
                // Во время передачи файлов интервал 1 мс и пачка проходов до 10 мс - это нормально,
                // предупреждаем только о настоящих задержках (иначе журнал засыпан «35/1»).
                val slowLimit = if (com.stoyko.smessenger.domain.feature.TransferMonitor.busy) 60L else SLOW_ITERATION_LIMIT_MS.toLong()
                if (timeTaken > slowLimit && timeTaken > iterationInterval && now - startedAt > WARMUP_MS) {
                    // 4.12.1: не чаще раза в 30 с, со счётчиком - на слабых телефонах редкие
                    // затяжки (сборка мусора) раньше засыпали журнал одинаковыми строками.
                    overruns++
                    worstOverrun = maxOf(worstOverrun, timeTaken)
                    if (now - lastOverrunLog > 30_000L) {
                        Log.w(TAG, "Tox thread overran: $timeTaken/$iterationInterval (за 30 с: $overruns, худшее $worstOverrun мс).")
                        lastOverrunLog = now
                        overruns = 0
                        worstOverrun = 0L
                    }
                }
                delay(iterationInterval - timeTaken)
            }
            started = false
        }

        // Флаг ставим ДО запуска корутин: иначе цикл звука мог увидеть running=false и сразу завершиться.
        running = true
        save()
        loadContacts()
        iterateForever()
        iterateForeverAv()
    }

    fun stop() = scope.launch {
        running = false
        while (started) delay(10)
        save().join()
        val selfKey = runCatching { publicKey.string() }.getOrNull()
        tox.stop()
        // Ядро остановлено - событий больше не будет: не оставляем в базе «в сети» прошлого сеанса.
        // (Если ядро уже запущено заново - перезапуск, - сброс сделает новый запуск сам.)
        if (!started) runCatching {
            contactRepository.resetTransientData()
            if (selfKey != null) userRepository.updateConnection(selfKey, ConnectionStatus.None)
        }
    }

    @Volatile
    private var presenceSyncRequested = false

    @Volatile
    private var lastPresenceSync = 0L

    /** Попросить цикл ядра сверить статусы «в сети» с ядром при ближайшем проходе. */
    fun requestPresenceSync() {
        presenceSyncRequested = true
    }

    /** Экран включён - раз в 10 с, выключен - раз в минуту (вызовы дешёвые, без сети). */
    private fun presenceSyncInterval(): Long =
        if (com.stoyko.smessenger.domain.feature.PowerHint.screenOff) 60_000L else 10_000L

    /** Сохранить профиль сейчас (Job завершится, когда файл записан). */
    fun saveNow() = save()

    private val saveMutex = Mutex()
    private fun save() = scope.launch {
        saveMutex.withLock {
            saveManager.save(publicKey, tox.getSaveData())
        }
    }

    fun acceptFriendRequest(publicKey: PublicKey) {
        tox.acceptFriendRequest(publicKey)
        save()
    }

    fun seekFile(pk: PublicKey, fileNumber: Int, position: Long) = tox.seekFile(pk, fileNumber, position)
    fun setCallBitRate(pk: PublicKey, audioKbps: Long, videoKbps: Long) = tox.setCallBitRate(pk, audioKbps, videoKbps)

    fun startFileTransfer(pk: PublicKey, fileNumber: Int) {
        Log.i(TAG, "Starting file transfer $fileNumber from ${pk.fingerprint()}")
        tox.startFileTransfer(pk, fileNumber)
    }

    fun stopFileTransfer(pk: PublicKey, fileNumber: Int) {
        Log.i(TAG, "Stopping file transfer $fileNumber from ${pk.fingerprint()}")
        tox.stopFileTransfer(pk, fileNumber)
    }

    fun sendFile(pk: PublicKey, fileKind: FileKind, fileSize: Long, fileName: String) =
        tox.sendFile(pk, fileKind, fileSize, fileName)

    fun sendFileChunk(pk: PublicKey, fileNo: Int, pos: Long, data: ByteArray, len: Int = data.size): Result<Unit> =
        tox.sendFileChunk(pk, fileNo, pos, data, len)

    fun getName() = tox.getName()
    fun setName(name: String) {
        tox.setName(name)
        save()
    }

    fun getStatusMessage() = tox.getStatusMessage()
    fun setStatusMessage(statusMessage: String) {
        tox.setStatusMessage(statusMessage)
        save()
    }

    fun addContact(toxId: ToxID, message: String) {
        tox.addContact(toxId, message)
        save()
    }

    fun deleteContact(publicKey: PublicKey) {
        tox.deleteContact(publicKey)
        save()
    }

    fun sendMessage(publicKey: PublicKey, message: String, type: MessageType) =
        tox.sendMessage(publicKey, message, type)

    fun getSaveData(): ByteArray = tox.getSaveData()

    // Подключаемся к нескольким узлам сразу и регистрируем все их TCP-порты как релеи.
    // Если UDP заблокирован (мобильная сеть, белые списки, корпоративный Wi-Fi),
    // сообщения, файлы и звонки идут через TCP-релеи; при доступном UDP toxcore
    // сам переключается на прямое соединение.
    /** Сколько попыток подряд подключиться не удалось: по нему перебираем другие узлы. */
    @Volatile
    private var overruns = 0
    private var worstOverrun = 0L
    private var lastOverrunLog = 0L

    private var bootstrapRound = 0

    /**
     * Узлы для очередной попытки: лучшие 16 (свои и самые быстрые) - всегда, остальные места
     * занимает «окно», которое при неудачах сдвигается по всему списку. Раньше при плохой сети
     * снова и снова пробовались одни и те же (возможно, заблокированные) узлы.
     */
    private fun pickNodes(): List<BootstrapNode> {
        val all = nodeRegistry.get(BOOTSTRAP_NODE_COUNT * 4)
        if (all.size <= BOOTSTRAP_NODE_COUNT) return all
        val head = all.take(16)
        val rest = all.drop(16)
        val window = BOOTSTRAP_NODE_COUNT - head.size
        val start = (bootstrapRound * window) % rest.size
        val rotated = rest.drop(start) + rest.take(start)
        return head + rotated.take(window)
    }

    // Последние найденные адреса узлов (сохраняются между запусками): если DNS временно не
    // работает или заблокирован, подключаемся по известному адресу - связь появляется сразу.
    private val dnsPrefs by lazy { context.getSharedPreferences("node_dns", Context.MODE_PRIVATE) }
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun remember(host: String, ip: String): String {
        if (host != ip) {
            dnsFresh[host] = System.currentTimeMillis()
            if (dnsCache.put(host, ip) != ip) runCatching { dnsPrefs.edit().putString(host, ip).putLong("t_$host", System.currentTimeMillis()).apply() }
        }
        return ip
    }

    private fun cached(host: String): String? = dnsCache[host] ?: runCatching { dnsPrefs.getString(host, null) }.getOrNull()

    /** Когда адрес узла последний раз удалось найти (0 - никогда). */
    private val dnsFresh = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun freshAt(host: String): Long = dnsFresh[host] ?: runCatching { dnsPrefs.getLong("t_$host", 0L) }.getOrDefault(0L)

    /**
     * Поиск адреса узла (4.10.0): свежий известный адрес (до 6 ч) - сразу, без сети; затем
     * системный DNS; затем свой DNS-клиент (UDP 53 к Яндексу/Cloudflare/Google и DNS через HTTPS
     * на порт 443, неотвечающие серверы пропускаются); затем последний известный адрес.
     */
    private fun resolve(host: String): String? {
        if (host.all { it.isDigit() || it == '.' || it == ':' }) return host
        // Через Tor: никаких DNS-запросов мимо Tor - только ранее известный адрес.
        if (com.stoyko.smessenger.domain.feature.RouteHint.torOnly) return cached(host)
        val known = cached(host)
        if (known != null && System.currentTimeMillis() - freshAt(host) < DNS_FRESH_MS) return known
        runCatching { return remember(host, InetAddress.getByName(host).hostAddress!!) }
        NodeDns.resolve(host)?.let { return remember(host, it) }
        return known
    }

    private fun bootstrap(): Boolean {
        val list = pickNodes()
        if (list.isEmpty()) return false
        // Адреса узлов ищем параллельно (раньше - по одному, и на плохой сети подключение
        // растягивалось на минуты). Ядро при этом не блокируется.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val resolved = try {
            list.map { node ->
                pool.submit(
                    java.util.concurrent.Callable {
                        resolve(node.address).also { if (it == null) Log.w(TAG, "Cannot resolve ${node.address}") }
                    },
                )
            }.mapIndexed { i, f ->
                try {
                    f.get(8, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // Не дождались DNS - последний известный адрес узла.
                    cached(list[i].address)
                }
            }
        } finally {
            pool.shutdownNow()
        }

        var ok = 0
        list.forEachIndexed { i, node ->
            if (!running) return ok > 0
            val address = resolved.getOrNull(i) ?: return@forEachIndexed
            val key = node.publicKey.bytes()
            if (node.udp) {
                if (tox.bootstrap(address, node.port, key)) {
                    ok++
                } else {
                    Log.w(TAG, "UDP bootstrap failed for ${node.address}")
                }
            }
            // Только реально открытые порты узла (443/3389/33445 - из свежего списка):
            // «пустые» порты занимали бы слоты релеев ядра и ухудшали связь.
            node.tcpPorts.kForEach { port ->
                if (tox.addTcpRelay(address, port, key)) {
                    ok++
                } else {
                    Log.w(TAG, "TCP relay failed for ${node.address}:$port")
                }
            }
        }
        Log.i(TAG, "Bootstrap round $bootstrapRound: $ok successful node/relay registrations")
        // Следующая попытка (если сеть так и не появится) - с другими узлами.
        bootstrapRound++
        return ok > 0
    }

    fun setTyping(publicKey: PublicKey, typing: Boolean) = tox.setTyping(publicKey, typing)

    fun getStatus() = tox.getStatus()
    fun setStatus(status: UserStatus) {
        tox.setStatus(status)
        save()
    }

    fun sendLosslessPacket(pk: PublicKey, packet: ByteArray) = tox.sendLosslessPacket(pk, packet)

    // ---- Группы и каналы ----
    fun groups() = tox.groups()
    fun createGroup(name: String, selfName: String) = tox.createGroup(name, selfName).also { save() }
    fun joinGroup(chatId: String, selfName: String) = tox.joinGroup(chatId, selfName).also { save() }
    fun friendPublicKey(number: Long): String? = tox.friendPublicKey(number)
    fun friendNumber(pk: PublicKey): Long = tox.friendNumber(pk)
    fun acceptGroupInvite(friend: Long, data: ByteArray, selfName: String) =
        tox.acceptGroupInvite(friend, data, selfName).also { save() }
    fun inviteToGroup(chatId: String, friendPk: PublicKey) = tox.inviteToGroup(chatId, friendPk)
    fun leaveGroup(chatId: String) {
        tox.leaveGroup(chatId)
        save()
    }
    fun sendGroupMessage(chatId: String, message: String) = tox.sendGroupMessage(chatId, message)
    fun sendGroupPacket(chatId: String, data: ByteArray, lossless: Boolean) = tox.sendGroupPacket(chatId, data, lossless)
    fun sendGroupPrivatePacket(chatId: String, peerId: Long, data: ByteArray) =
        tox.sendGroupPrivatePacket(chatId, peerId, data)
    fun groupSelfKey(chatId: String) = tox.groupSelfKey(chatId)
    fun groupPeerRole(chatId: String, peerId: Long) = tox.groupPeerRole(chatId, peerId)
    fun groupSelfRole(chatId: String) = tox.groupSelfRole(chatId)
    fun groupPeerCount(chatId: String) = tox.groupPeerCount(chatId)
    fun groupConnected(chatId: String) = tox.groupConnected(chatId)
    fun makePeerObserver(chatId: String, peerId: Long) = tox.makePeerObserver(chatId, peerId)
    fun makeAllObservers(chatId: String) = tox.makeAllObservers(chatId)
    fun makePeerUser(chatId: String, peerId: Long) = tox.makePeerUser(chatId, peerId)
    fun makeAllUsers(chatId: String) = tox.makeAllUsers(chatId)
    fun groupPeers(chatId: String) = tox.groupPeers(chatId)
    fun groupHasPeer(chatId: String, peerId: Long) = tox.groupHasPeer(chatId, peerId)
    fun kickGroupPeer(chatId: String, peerId: Long) = tox.kickGroupPeer(chatId, peerId)
    fun setGroupModerator(chatId: String, peerId: Long, moderator: Boolean) =
        tox.setGroupModerator(chatId, peerId, moderator).also { save() }
    fun encodeGroupAudio(pcm: ByteArray, out: ByteArray) = tox.encodeGroupAudio(pcm, out)
    fun decodeGroupAudio(encoded: ByteArray, size: Int, pcmOut: ByteArray) = tox.decodeGroupAudio(encoded, size, pcmOut)

    // ToxAv, probably move these.
    fun startCall(pk: PublicKey, video: Boolean = false) = tox.startCall(pk, video)
    fun answerCall(pk: PublicKey, video: Boolean = false) = tox.answerCall(pk, video)
    fun setVideoSending(pk: PublicKey, on: Boolean) = tox.setVideoSending(pk, on)
    fun sendVideo(pk: PublicKey, width: Int, height: Int, i420: ByteArray) = tox.sendVideo(pk, width, height, i420)
    fun endCall(pk: PublicKey) = tox.endCall(pk)
    fun sendAudio(pk: PublicKey, pcm: ShortArray, channels: Int, samplingRate: Int) =
        tox.sendAudio(pk, pcm, channels, samplingRate)
}
