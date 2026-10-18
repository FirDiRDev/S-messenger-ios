// SPDX-FileCopyrightText: 2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.userprofile

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.core.vo.UserStatus
import com.stoyko.smessenger.domain.feature.UserManager
import com.stoyko.smessenger.domain.tox.Tox

class UserProfileViewModel @Inject constructor(
    private val userManager: UserManager,
    private val tox: Tox,
    val selfAvatar: com.stoyko.smessenger.profile.SelfAvatar,
    val profileCard: com.stoyko.smessenger.profile.ProfileCard,
    private val contactManager: com.stoyko.smessenger.domain.feature.ContactManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {
    val publicKey by lazy { tox.publicKey }
    val toxId by lazy { tox.toxId }
    val user: LiveData<User> = userManager.get(publicKey).filterNotNull().asLiveData()

    fun setName(name: String) = userManager.setName(name)
    fun setStatusMessage(statusMessage: String) = userManager.setStatusMessage(statusMessage)
    fun setStatus(status: UserStatus) = userManager.setStatus(status)
    suspend fun setAvatar(uri: android.net.Uri): Boolean = selfAvatar.set(uri)
    fun removeAvatar() = selfAvatar.remove()

    /** Сохранить расширенный профиль и сразу разослать его тем, кто в сети. */
    fun setExtProfile(p: com.stoyko.smessenger.profile.ExtProfile) {
        profileCard.mine = p
        broadcastCard()
    }

    fun broadcastCard() = scope.launch {
        // Даём ядру применить новое имя/статус.
        kotlinx.coroutines.delay(600)
        val contacts = contactManager.getAll().first()
        for (c in contacts) {
            if (c.connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None) {
                profileCard.sendTo(com.stoyko.smessenger.core.vo.PublicKey(c.publicKey))
            }
        }
    }
}
