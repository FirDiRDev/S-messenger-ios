// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.content.IntentCompat
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stoyko.smessenger.ui.NotificationHelper
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.UserStatus
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.CallState
import com.stoyko.smessenger.domain.feature.ChatManager
import com.stoyko.smessenger.domain.feature.ContactManager
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxavAnswerException

const val KEY_TEXT_REPLY = "key_text_reply"
const val KEY_CONTACT_PK = "key_contact_pk"
const val KEY_ACTION = "key_action"

/** Открыть приложение и сразу принять звонок (кнопка «Принять» в уведомлении). */
const val ACTION_ACCEPT_CALL = "com.stoyko.smessenger.ACCEPT_CALL"

/** Входящий звонок на весь экран (будит телефон, показывается поверх блокировки). */
const val ACTION_INCOMING_CALL = "com.stoyko.smessenger.INCOMING_CALL"

enum class Action {
    CallAccept,
    CallEnd,
    CallIgnore,
    CallReject,
    MarkAsRead,
    FriendAccept,
    FriendReject,
}

private const val TAG = "ActionReceiver"

class ActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var chatManager: ChatManager

    @Inject
    lateinit var contactManager: ContactManager

    @Inject
    lateinit var contactRepository: ContactRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var tox: Tox

    @Inject
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var friendRequestManager: com.stoyko.smessenger.domain.feature.FriendRequestManager

    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as App).component.inject(this)

        scope.launch {
            val pk = intent.getStringExtra(KEY_CONTACT_PK)?.let { PublicKey(it) }
            if (pk == null) {
                Log.e(TAG, "Got intent without required key $KEY_CONTACT_PK $intent")
                return@launch
            }
            // Запрос в друзья - «Принять» / «Отклонить» прямо из уведомления (контакта ещё нет).
            val act = IntentCompat.getSerializableExtra(intent, KEY_ACTION, Action::class.java)
            if (act == Action.FriendAccept || act == Action.FriendReject) {
                notificationHelper.dismissFriendRequest(pk.string())
                val req = friendRequestManager.get(pk).firstOrNull() ?: return@launch
                if (act == Action.FriendAccept) friendRequestManager.accept(req) else friendRequestManager.reject(req)
                return@launch
            }
            if (!contactRepository.exists(pk.string())) {
                notificationHelper.dismissNotifications(pk)
                notificationHelper.dismissCallNotification(pk)
                return@launch
            }

            RemoteInput.getResultsFromIntent(intent)?.let { results ->
                results.getCharSequence(KEY_TEXT_REPLY)?.toString()?.let { input ->
                    contactRepository.setHasUnreadMessages(pk.string(), false)
                    chatManager.sendMessage(pk, input)
                    notificationHelper.showMessageNotification(
                        Contact(pk.string(), tox.getName()),
                        input,
                        outgoing = true,
                    )
                    return@launch
                }
            }

            when (IntentCompat.getSerializableExtra(intent, KEY_ACTION, Action::class.java)) {
                Action.CallAccept -> acceptCall(context, pk)
                Action.CallEnd, Action.CallReject -> {
                    callManager.endCall(pk)
                    notificationHelper.dismissCallNotification(pk)
                }
                Action.CallIgnore -> callManager.removePendingCall(pk)
                Action.FriendAccept, Action.FriendReject -> Unit
                Action.MarkAsRead -> {
                    contactRepository.setHasUnreadMessages(pk.string(), false)
                    notificationHelper.dismissNotifications(pk)
                }
                null -> Log.e(TAG, "Missing action in intent $intent")
            }
        }
    }

    private suspend fun acceptCall(context: Context, pk: PublicKey) {
        val contact = contactManager.get(pk).firstOrNull().let {
            if (it != null) {
                it
            } else {
                Log.e(TAG, "Unable to get contact ${pk.fingerprint()} for call notification")
                Contact(publicKey = pk.string(), name = pk.fingerprint())
            }
        }

        if (callManager.inCall.value is CallState.InCall) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.error_simultaneous_calls), Toast.LENGTH_LONG).show()
                notificationHelper.showPendingCallNotification(UserStatus.Busy, contact)
            }
            return
        }

        try {
            callManager.startCall(pk)
            notificationHelper.showOngoingCallNotification(contact)
        } catch (e: ToxavAnswerException) {
            Log.e(TAG, e.toString())
            return
        }

        val isSendingAudio = context.hasPermission(Manifest.permission.RECORD_AUDIO) && callManager.startSendingAudio()
        if (!isSendingAudio) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.call_mic_permission_needed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
