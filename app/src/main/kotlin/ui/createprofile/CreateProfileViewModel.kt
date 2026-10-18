// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.createprofile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.tox.ToxStarter
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.domain.feature.UserManager
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxSaveStatus
import com.stoyko.smessenger.domain.tox.SaveManager
import com.stoyko.smessenger.domain.Diagnostics

class ProfileStartException(val status: ToxSaveStatus) : Exception(status.name)

class CreateProfileViewModel @Inject constructor(
    private val resolver: ContentResolver,
    private val userManager: UserManager,
    private val tox: Tox,
    private val toxStarter: ToxStarter,
    private val saveManager: SaveManager,
) : ViewModel() {
    val publicKey: PublicKey get() = tox.publicKey

    fun startTox(save: ByteArray? = null, password: String? = null): ToxSaveStatus = toxStarter.startTox(save, password)
    fun tryImportToxSave(uri: Uri): ByteArray? = resolver.openInputStream(uri)?.use { it.readBytes() }
    fun verifyUserExists(publicKey: PublicKey) = userManager.verifyExists(publicKey)

    /**
     * Создаёт новый профиль вне главного потока. Никогда не роняет приложение:
     * любая ошибка возвращается как Result.failure.
     */
    suspend fun createProfile(name: String, statusMessage: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Diagnostics.track("создание профиля") {
                val status = if (tox.started) ToxSaveStatus.Ok else startTox()
                if (status != ToxSaveStatus.Ok) throw ProfileStartException(status)
                val job = userManager.create(
                    User(publicKey = tox.publicKey.string(), name = name, statusMessage = statusMessage),
                )
                job.join()
                if (job.isCancelled) throw IllegalStateException("Не удалось записать профиль в базу данных")
                // Профиль должен оказаться на диске ДО перехода дальше - иначе после перезапуска
                // приложение снова попросит создать профиль.
                val saveJob = tox.saveNow()
                saveJob.join()
                if (saveJob.isCancelled || saveManager.load(tox.publicKey) == null) {
                    throw IllegalStateException("Профиль не сохранился на устройстве")
                }
            }
        }.onFailure { Diagnostics.record("создание профиля", it, fatal = false) }
    }

    suspend fun importProfile(save: ByteArray, password: String?): ToxSaveStatus = withContext(Dispatchers.IO) {
        try {
            val status = startTox(save, password)
            if (status == ToxSaveStatus.Ok) verifyUserExists(tox.publicKey).join()
            status
        } catch (e: Exception) {
            ToxSaveStatus.Null
        }
    }
}
