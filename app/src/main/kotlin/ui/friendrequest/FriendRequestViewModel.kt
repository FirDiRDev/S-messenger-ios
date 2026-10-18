// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.friendrequest

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.FriendRequestManager

class FriendRequestViewModel @Inject constructor(private val friendRequests: FriendRequestManager) : ViewModel() {
    fun byId(pk: PublicKey): LiveData<FriendRequest?> = friendRequests.get(pk).asLiveData()
    fun accept(request: FriendRequest) = friendRequests.accept(request)
    fun reject(request: FriendRequest) = friendRequests.reject(request)
}
