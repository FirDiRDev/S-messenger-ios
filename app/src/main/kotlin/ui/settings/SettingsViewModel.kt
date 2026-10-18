// SPDX-FileCopyrightText: 2019-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.settings.BootstrapNodeSource
import com.stoyko.smessenger.settings.FtAutoAccept
import com.stoyko.smessenger.settings.Settings
import com.stoyko.smessenger.tox.ToxStarter
import com.stoyko.smessenger.domain.tox.BootstrapNodeJsonParser
import com.stoyko.smessenger.domain.tox.BootstrapNodeRegistry
import com.stoyko.smessenger.domain.tox.ProxyType
import com.stoyko.smessenger.domain.tox.SaveOptions
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxSaveStatus
import com.stoyko.smessenger.domain.tox.testToxSave

private const val TOX_SHUTDOWN_POLL_DELAY_MS = 200L

enum class ProxyStatus {
    Good,
    BadPort,
    BadHost,
    BadType,
    NotFound,
}

class SettingsViewModel @Inject constructor(
    private val context: Context,
    private val resolver: ContentResolver,
    private val settings: Settings,
    private val toxStarter: ToxStarter,
    private val tox: Tox,
    private val nodeParser: BootstrapNodeJsonParser,
    private val nodeRegistry: BootstrapNodeRegistry,
    private val callManager: com.stoyko.smessenger.domain.feature.CallManager,
    private val groupCallManager: com.stoyko.smessenger.groups.GroupCallManager,
    private val userManager: com.stoyko.smessenger.domain.feature.UserManager,
    private val profileCard: com.stoyko.smessenger.profile.ProfileCard,
) : ViewModel() {
    /** Свой публичный ключ (для шапки настроек); пусто, если ядро ещё не запущено. */
    val selfPublicKey: String get() = if (tox.started) runCatching { tox.publicKey.string() }.getOrDefault("") else ""

    /** Своё имя и статус - для карточки профиля в настройках. */
    fun self(): LiveData<com.stoyko.smessenger.core.vo.User>? {
        val pk = selfPublicKey
        if (pk.isEmpty()) return null
        return userManager.get(com.stoyko.smessenger.core.vo.PublicKey(pk)).filterNotNull().asLiveData()
    }

    private var restartNeeded = false

    private val _proxyStatus = MutableLiveData<ProxyStatus>()
    val proxyStatus: LiveData<ProxyStatus> get() = _proxyStatus

    private val _committed = MutableLiveData<Boolean>().apply { value = false }
    val committed: LiveData<Boolean> get() = _committed

    fun nospamAvailable(): Boolean = tox.started
    fun getNospam(): Int = tox.nospam
    fun setNospam(value: Int) {
        tox.nospam = value
        // Полный ID изменился - сразу сообщаем его друзьям, кто в сети.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { profileCard.sendToOnline() }
    }

    // The trickery here is because the values in the dropdown are 0, 1, 2 for auto, no, yes;
    // while in Android, the values are -1, 1, 2 for auto, no, yes; so we map -1 to 0 when getting,
    // and 0 to -1 when setting.
    fun getTheme(): Int = max(0, settings.theme)
    fun setTheme(theme: Int) {
        settings.theme = when (theme) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    fun getFtAutoAccept(): FtAutoAccept = settings.ftAutoAccept
    fun setFtAutoAccept(autoAccept: FtAutoAccept) {
        settings.ftAutoAccept = autoAccept
    }

    fun getUdpEnabled(): Boolean = settings.udpEnabled
    fun setUdpEnabled(enabled: Boolean) {
        if (enabled == getUdpEnabled()) return
        settings.udpEnabled = enabled
        restartNeeded = true
    }

    fun getRunAtStartup(): Boolean = settings.runAtStartup
    fun setRunAtStartup(enabled: Boolean) {
        settings.runAtStartup = enabled
    }

    fun getAutoAwayEnabled() = settings.autoAwayEnabled
    fun setAutoAwayEnabled(enabled: Boolean) {
        settings.autoAwayEnabled = enabled
    }

    fun getConfirmQuitting(): Boolean = settings.confirmQuitting
    fun setConfirmQuitting(enabled: Boolean) {
        settings.confirmQuitting = enabled
    }

    fun getConfirmCalling(): Boolean = settings.confirmCalling
    fun setConfirmCalling(enabled: Boolean) {
        settings.confirmCalling = enabled
    }

    fun getAutoAwaySeconds() = settings.autoAwaySeconds
    fun setAutoAwaySeconds(seconds: Long) {
        settings.autoAwaySeconds = seconds
    }

    fun commit() {
        if (!restartNeeded) {
            _committed.value = true
            return
        }

        val password = tox.password
        toxStarter.stopTox()

        viewModelScope.launch {
            while (tox.started) {
                delay(TOX_SHUTDOWN_POLL_DELAY_MS)
            }
            toxStarter.tryLoadTox(password)
            _committed.value = true
        }
    }

    private var checkProxyJob: Job? = null
    fun checkProxy() {
        checkProxyJob?.cancel(null)
        checkProxyJob = viewModelScope.launch(Dispatchers.IO) {
            val saveStatus = testToxSave(
                SaveOptions(saveData = null, getUdpEnabled(), getProxyType(), getProxyAddress(), getProxyPort()),
                null,
            )

            val proxyStatus = when (saveStatus) {
                ToxSaveStatus.BadProxyHost -> ProxyStatus.BadHost
                ToxSaveStatus.BadProxyPort -> ProxyStatus.BadPort
                ToxSaveStatus.BadProxyType -> ProxyStatus.BadType
                ToxSaveStatus.ProxyNotFound -> ProxyStatus.NotFound
                else -> ProxyStatus.Good
            }

            _proxyStatus.postValue(proxyStatus)
        }
    }

    fun getProxyType(): ProxyType = settings.proxyType
    fun setProxyType(type: ProxyType) {
        if (type != getProxyType()) {
            settings.proxyType = type
            restartNeeded = true
            checkProxy()
        }
    }

    fun getProxyAddress(): String = settings.proxyAddress
    fun setProxyAddress(address: String) {
        if (address != getProxyAddress()) {
            settings.proxyAddress = address
            if (getProxyType() != ProxyType.None) {
                restartNeeded = true
            }
            checkProxy()
        }
    }

    fun getProxyPort(): Int = settings.proxyPort
    fun setProxyPort(port: Int) {
        if (port != getProxyPort()) {
            settings.proxyPort = port
            if (getProxyType() != ProxyType.None) {
                restartNeeded = true
            }
            checkProxy()
        }
    }

    fun isCurrentPassword(maybeCurrentPassword: String) = tox.password == maybeCurrentPassword.ifEmpty { null }

    fun setPassword(newPassword: String) = tox.changePassword(newPassword.ifEmpty { null })

    fun getBootstrapNodeSource(): BootstrapNodeSource = settings.bootstrapNodeSource
    fun setBootstrapNodeSource(source: BootstrapNodeSource) {
        settings.bootstrapNodeSource = source
        nodeRegistry.reset()
        restartNeeded = true
    }

    suspend fun validateNodeJson(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return@withContext false

        return@withContext nodeParser.parse(bytes.decodeToString()).isNotEmpty()
    }

    suspend fun importNodeJson(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return@withContext false

        val out = File(context.filesDir, "user_nodes.json")
        out.delete()
        if (!out.createNewFile()) return@withContext false

        out.outputStream().use { it.write(bytes) }
        return@withContext true
    }

    fun getDisableScreenshots(): Boolean = settings.disableScreenshots
    fun setDisableScreenshots(disable: Boolean) {
        settings.disableScreenshots = disable
    }

    fun getStableConnection(): Boolean = settings.stableConnection
    fun setStableConnection(on: Boolean) {
        settings.stableConnection = on
    }

    val appSettings: com.stoyko.smessenger.settings.Settings get() = settings

    /** Список «Мои узлы» изменился - сразу подключаемся и к новым узлам (без перезапуска). */
    fun reloadNodes() {
        nodeRegistry.reset()
        if (tox.started) tox.kickBootstrap()
    }

    fun setCallEarpiece(on: Boolean) {
        settings.callEarpiece = on
        callManager.preferEarpiece = on
        groupCallManager.preferEarpiece = on
    }

    fun setHideIp(on: Boolean) {
        if (settings.hideIp != on) {
            settings.hideIp = on
            restartNeeded = true
        }
    }
}
