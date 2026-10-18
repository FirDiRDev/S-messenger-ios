// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import java.util.EnumSet

// Замена перечислений tox4j. Порядок элементов = числовые значения из tox.h / toxav.h.

enum class ToxConnection { NONE, TCP, UDP }

enum class ToxUserStatus { NONE, AWAY, BUSY }

enum class ToxMessageType { NORMAL, ACTION }

enum class ToxFileControl { RESUME, PAUSE, CANCEL }

object ToxFileKind {
    const val DATA = 0
    const val AVATAR = 1
}

enum class ToxavCallControl { RESUME, PAUSE, CANCEL, MUTE_AUDIO, UNMUTE_AUDIO, HIDE_VIDEO, SHOW_VIDEO }

/** Биты TOXAV_FRIEND_CALL_STATE. */
enum class ToxavFriendCallState(val bit: Int) {
    ERROR(1),
    FINISHED(2),
    SENDING_A(4),
    SENDING_V(8),
    ACCEPTING_A(16),
    ACCEPTING_V(32),
    ;

    companion object {
        fun fromBits(state: Int): EnumSet<ToxavFriendCallState> =
            EnumSet.noneOf(ToxavFriendCallState::class.java).apply {
                entries.forEach { if (state and it.bit != 0) add(it) }
            }
    }
}

/** Ошибка запуска ядра Tox: причина - в [status]. */
class ToxInitException(val status: ToxSaveStatus, message: String) : Exception(message)

/** Не удалось принять звонок. */
class ToxavAnswerException(message: String) : Exception(message)

internal fun Int.toEnumOr(default: ToxConnection) = ToxConnection.entries.getOrElse(this) { default }
