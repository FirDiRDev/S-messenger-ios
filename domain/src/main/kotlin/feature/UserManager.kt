// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.stoyko.smessenger.core.repository.UserRepository
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.User
import com.stoyko.smessenger.core.vo.UserStatus
import com.stoyko.smessenger.domain.tox.Tox

class UserManager @Inject constructor(
    private val scope: CoroutineScope,
    private val userRepository: UserRepository,
    private val tox: Tox,
) {
    fun get(publicKey: PublicKey) = userRepository.get(publicKey.string())

    fun create(user: User) = scope.launch {
        userRepository.add(user)
        tox.setName(user.name)
        tox.setStatusMessage(user.statusMessage)
    }

    fun verifyExists(publicKey: PublicKey) = scope.launch {
        if (!userRepository.exists(publicKey.string())) {
            val name = tox.getName()
            val statusMessage = tox.getStatusMessage()
            val user = User(publicKey.string(), name, statusMessage)
            userRepository.add(user)
        }
    }

    fun setName(name: String) = scope.launch {
        tox.setName(name)
        userRepository.updateName(tox.publicKey.string(), name)
    }

    fun setStatusMessage(statusMessage: String) = scope.launch {
        tox.setStatusMessage(statusMessage)
        userRepository.updateStatusMessage(tox.publicKey.string(), statusMessage)
    }

    fun setStatus(status: UserStatus) = scope.launch {
        tox.setStatus(status)
        userRepository.updateStatus(tox.publicKey.string(), status)
    }
}
