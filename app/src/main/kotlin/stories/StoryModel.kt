// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stories

/** История: owner = "me" (своя) или ключ собеседника. */
data class Story(
    val id: String,
    val owner: String,
    val path: String,
    val video: Boolean,
    val createdAt: Long,
    val durationMs: Long,
    val viewed: Boolean,
    val sentIds: List<Int> = emptyList(),
)

object StoryMeta {
    const val ME = "me"
    const val TTL_MS = 24 * 60 * 60 * 1000L
    const val MAX_MINE = 3
    const val MAX_VIDEO_MS = 61_000L
    const val PHOTO_SHOW_MS = 6_000L

    fun isStory(name: String) = name.startsWith("story_")

    // ---------- Подписи к историям ----------
    // Подпись уходит каждому контакту служебным сообщением (со сквозным шифрованием)
    // вместе с файлом истории и хранится по имени файла - и у автора, и у получателя.

    private const val CAPTION = "⁣📖C:"

    fun isCaptionMessage(text: String) = text.startsWith(CAPTION)
    fun captionMessage(storyId: String, caption: String) = CAPTION + storyId + "|" + caption.take(400)

    private fun captions(ctx: android.content.Context) =
        ctx.applicationContext.getSharedPreferences("story_captions", android.content.Context.MODE_PRIVATE)

    fun caption(ctx: android.content.Context, storyId: String): String = captions(ctx).getString(storyId, "").orEmpty()
    fun setCaption(ctx: android.content.Context, storyId: String, caption: String) {
        if (caption.isBlank()) return
        captions(ctx).edit().putString(storyId, caption).apply()
    }

    fun onCaptionMessage(ctx: android.content.Context, text: String) {
        val body = text.removePrefix(CAPTION)
        val id = body.substringBefore('|')
        if (isStory(id)) setCaption(ctx, id, body.substringAfter('|', ""))
    }

    /** Время создания у отправителя - из имени story_<время>... */
    fun createdAt(name: String): Long? = Regex("^story_(\\d{10,})").find(name)?.groupValues?.get(1)?.toLongOrNull()

    fun isVideoName(name: String) = name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".3gp", ignoreCase = true) ||
        name.endsWith(".webm", ignoreCase = true)
}

