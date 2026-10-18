// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.os.bundleOf
import androidx.navigation.NavDeepLinkBuilder
import com.squareup.picasso.Picasso
import com.squareup.picasso.Transformation
import javax.inject.Inject
import javax.inject.Singleton
import com.stoyko.smessenger.Action
import com.stoyko.smessenger.ActionReceiver
import com.stoyko.smessenger.KEY_ACTION
import com.stoyko.smessenger.KEY_CONTACT_PK
import com.stoyko.smessenger.KEY_TEXT_REPLY
import com.stoyko.smessenger.PendingIntentCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.hasPermission
import com.stoyko.smessenger.ui.chat.CONTACT_PUBLIC_KEY
import com.stoyko.smessenger.ui.chat.FOCUS_ON_MESSAGE_BOX
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.UserStatus

private const val TAG = "NotificationHelper"

// Новый канал - со своим мягким звуком (у старого канала Android не даёт сменить звук).
// v4: текст сообщения виден и на экране блокировки (у старого канала это не поменять).
private const val MESSAGE = "S-messenger messages v4"
private const val MESSAGE_V3 = "S-messenger messages v3"
private const val MESSAGE_OLD = "S-messenger messages"
private const val MESSAGE_V2 = "S-messenger messages v2"
private const val FRIEND_REQUEST = "S-messenger friend requests"
private const val CALL = "S-messenger calls"

@Singleton
class NotificationHelper @Inject constructor(private val context: Context) {
    private val notifier = NotificationManagerCompat.from(context)
    private val notifierOld = context.getSystemService<NotificationManager>()!!

    init {
        createNotificationChannel()
        // Прогресс отправки и загрузки файлов в шторке.
        runCatching { TransferNotifier.attach(context) }
    }

    private fun createNotificationChannel() {
        val chime = android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.smessenger_notify}")
        val chimeAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val messageChannel = NotificationChannelCompat.Builder(MESSAGE, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.messages))
            .setDescription(context.getString(R.string.messages_incoming))
            .setSound(chime, chimeAttrs)
            .setVibrationEnabled(true)
            .build()
        runCatching { notifier.deleteNotificationChannel(MESSAGE_OLD) }
        runCatching { notifier.deleteNotificationChannel(MESSAGE_V2) }
        runCatching { notifier.deleteNotificationChannel(MESSAGE_V3) }

        val friendChannel = NotificationChannelCompat.Builder(FRIEND_REQUEST, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.friend_requests))
            .build()

        // We can't use getActualDefaultRingtoneUri as Samsung requires the WRITE_SETTINGS permission for that. :D
        // See: https://github.com/evilcorpltd/aTox/issues/958
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
        val callChannel = NotificationChannelCompat.Builder(CALL, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.calls))
            .setVibrationEnabled(true)
            .setSound(ringtone, attrs)
            .build()

        notifier.createNotificationChannelsCompat(listOf(messageChannel, friendChannel, callChannel))
        // Первые строки нового сообщения - прямо на экране блокировки.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                notifierOld.getNotificationChannel(MESSAGE)?.let { ch ->
                    if (ch.lockscreenVisibility != android.app.Notification.VISIBILITY_PUBLIC) {
                        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        notifierOld.createNotificationChannel(ch)
                    }
                }
            }
        }
    }

    fun dismissNotifications(publicKey: PublicKey) {
        notifier.cancel(publicKey.string().hashCode())
        AppBadge.clear(context, publicKey.string())
    }

    private val circleTransform = object : Transformation {
        override fun transform(bitmap: Bitmap): Bitmap {
            val output = createBitmap(bitmap.width, bitmap.height)
            val canvas = Canvas(output)
            val paint = Paint()
            val rect = Rect(0, 0, bitmap.width, bitmap.height)

            paint.isAntiAlias = true
            canvas.drawARGB(0, 0, 0, 0)
            canvas.drawCircle(bitmap.width / 2.0f, bitmap.height / 2.0f, bitmap.width / 2.0f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, rect, rect, paint)
            if (bitmap != output) {
                bitmap.recycle()
            }
            return output
        }

        override fun key() = "circleTransform"
    }

    fun showMessageNotification(
        contact: Contact,
        message: String,
        outgoing: Boolean = false,
        silent: Boolean = outgoing,
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Received message, notifications disallowed")
            return
        }

        val prefs = com.stoyko.smessenger.settings.Settings(context)
        // Уведомления о личных чатах выключены в настройках.
        if (!outgoing && !prefs.notifyPrivate) return

        // Конфиденциальность: вместо имени и текста - просто «Новое сообщение».
        // Число непрочитанных в этом чате - на значке приложения и в уведомлении.
        val unread = if (!outgoing) AppBadge.bump(context, contact.publicKey) else 0

        // Скрытый чат (PIN): ни имени, ни текста, ни «Ответить»; нажатие ведёт в список чатов,
        // а не в сам чат - иначе уведомление было лазейкой в обход PIN.
        if (!outgoing && com.stoyko.smessenger.chat.HiddenChats.isHidden(context, contact.publicKey)) {
            val secret = NotificationCompat.Builder(context, MESSAGE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(context.getText(R.string.hidden_chat_notification))
                .setContentText(context.getText(R.string.hidden_chat_new_message))
                .setContentIntent(
                    NavDeepLinkBuilder(context)
                        .setGraph(R.navigation.nav_graph)
                        .setDestination(R.id.contactListFragment)
                        .createPendingIntent(),
                )
                .setAutoCancel(true)
                .setSilent(silent || !prefs.notifySound)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            notifier.notify("hidden-chat".hashCode(), secret)
            wakeForMessage()
            return
        }

        if (!outgoing && prefs.hideNotificationText) {
            val hidden = NotificationCompat.Builder(context, MESSAGE)
                .setNumber(unread)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(context.getText(R.string.app_name))
                .setContentText(context.getText(R.string.notification_hidden_text))
                .setContentIntent(deepLinkToChat(PublicKey(contact.publicKey)))
                .setAutoCancel(true)
                .setSilent(silent || !prefs.notifySound)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            AppBadge.decorate(hidden, unread)
            notifier.notify(contact.publicKey.hashCode(), hidden)
            wakeForMessage()
            return
        }

        val notificationBuilder = NotificationCompat.Builder(context, MESSAGE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(contact.name.ifEmpty { context.getText(R.string.contact_default_name) })
            .setContentText(message)
            .setContentIntent(deepLinkToChat(PublicKey(contact.publicKey)))
            .setAutoCancel(true)
            .setSilent(silent || !prefs.notifySound)
            // Текст виден на экране блокировки (если в настройках не включено «скрывать текст»).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setNumber(unread)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val icon = if (contact.avatarUri.isNotEmpty()) {
                IconCompat.createWithBitmap(Picasso.get().load(contact.avatarUri).transform(circleTransform).get())
            } else {
                null
            }

            val chatPartner = Person.Builder()
                .setName(contact.name.ifEmpty { context.getText(R.string.contact_default_name) })
                .setKey(if (outgoing) "myself" else contact.publicKey)
                .setIcon(icon)
                .setImportant(true)
                .build()

            val style =
                notifierOld.activeNotifications.find { it.notification.group == contact.publicKey }?.notification?.let {
                    NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
                } ?: NotificationCompat.MessagingStyle(chatPartner)

            style.messages.add(
                NotificationCompat.MessagingStyle.Message(message, System.currentTimeMillis(), chatPartner),
            )

            notificationBuilder
                .setStyle(style)
                .setGroup(contact.publicKey)
        }

        // I can't find it in the documentation for RemoteInput or anything, but per
        // https://developer.android.com/training/notify-user/build-notification#reply-action
        // support for this was only introduced in Android N (API 24).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationBuilder.addAction(
                NotificationCompat.Action
                    .Builder(
                        IconCompat.createWithResource(context, R.drawable.ic_send),
                        context.getString(R.string.reply),
                        PendingIntentCompat.getBroadcast(
                            context,
                            contact.publicKey.hashCode(),
                            Intent(context, ActionReceiver::class.java).putExtra(KEY_CONTACT_PK, contact.publicKey),
                            PendingIntent.FLAG_UPDATE_CURRENT,
                            mutable = true,
                        ),
                    )
                    .addRemoteInput(
                        RemoteInput.Builder(KEY_TEXT_REPLY)
                            .setLabel(context.getString(R.string.message))
                            .build(),
                    )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(true)
                    .build(),
            )
        } else {
            notificationBuilder.addAction(
                NotificationCompat.Action
                    .Builder(
                        IconCompat.createWithResource(context, R.drawable.ic_send),
                        context.getString(R.string.reply),
                        deepLinkToChat(PublicKey(contact.publicKey), focusMessageBox = true),
                    )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )
        }

        notificationBuilder.addAction(
            NotificationCompat.Action.Builder(
                null,
                context.getString(R.string.mark_as_read),
                PendingIntentCompat.getBroadcast(
                    context,
                    "${contact.publicKey}_mark_as_read".hashCode(),
                    Intent(context, ActionReceiver::class.java)
                        .putExtra(KEY_CONTACT_PK, contact.publicKey)
                        .putExtra(KEY_ACTION, Action.MarkAsRead),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build(),
        )

        notificationBuilder.setPriority(NotificationCompat.PRIORITY_HIGH)
        val built = notificationBuilder.build()
        if (unread > 0) AppBadge.decorate(built, unread)
        notifier.notify(contact.publicKey.hashCode(), built)
        if (!outgoing) wakeForMessage()
    }

    /**
     * Новое сообщение при выключенном экране: на части телефонов (Xiaomi, Honor, Huawei)
     * уведомление приходит «молча» и экран не загорается - коротко включаем экран, чтобы текст
     * был виден на экране блокировки. Выключается в Настройках → Уведомления.
     */
    @Suppress("DEPRECATION")
    private fun wakeForMessage() {
        val on = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("notify_wake_screen", true)
        }.getOrDefault(true)
        if (!on) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) return
            pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "smessenger:new_message",
            ).acquire(4_000)
        }
    }

    /** Приглашение в группу или канал: карточка уже в чате с пригласившим. */
    fun showGroupInviteNotification(publicKey: String, groupName: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, MESSAGE)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(context.getString(R.string.card_invite_notification))
            .setContentText(context.getString(R.string.card_invite_title, groupName))
            .setContentIntent(deepLinkToChat(PublicKey(publicKey)))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifier.notify(("invite" + publicKey).hashCode(), n)
        wakeForMessage()
    }

    fun dismissFriendRequest(publicKey: String) = notifier.cancel(publicKey.hashCode())

    fun showFriendRequestNotification(friendRequest: FriendRequest, silent: Boolean) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Received friend request, notifications disallowed")
            return
        }

        // Ник и аватарка - из карточки в тексте запроса (RequestCard); нет её (старая версия у
        // друга) - короткий ID вместо 76 символов.
        val card = com.stoyko.smessenger.chat.RequestCard.parse(friendRequest.message)
        val who = com.stoyko.smessenger.chat.RequestCard.title(card, friendRequest.publicKey)
        val sizePx = (64 * context.resources.displayMetrics.density).toInt()
        val avatar = runCatching {
            card.bitmap()?.let { com.stoyko.smessenger.chat.RequestCard.circle(it, sizePx) }
                ?: com.stoyko.smessenger.ui.AvatarFactory.create(context.resources, card.name.ifBlank { who }, friendRequest.publicKey, com.stoyko.smessenger.ui.Px(sizePx))
        }.getOrNull()
        val text = card.text.trim().ifBlank { com.stoyko.smessenger.ai.t("Хочет добавить вас в контакты", "Wants to add you as a contact") }
        fun act(a: Action, code: Int) = PendingIntentCompat.getBroadcast(
            context,
            (friendRequest.publicKey + code).hashCode(),
            Intent(context, ActionReceiver::class.java)
                .putExtra(KEY_CONTACT_PK, friendRequest.publicKey)
                .putExtra(KEY_ACTION, a),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notificationBuilder = NotificationCompat.Builder(context, FRIEND_REQUEST)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setSmallIcon(R.drawable.ic_person_add)
            .setLargeIcon(avatar)
            .setContentTitle(com.stoyko.smessenger.ai.t("👋 $who хочет добавить вас", "👋 $who wants to add you"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.accept), act(Action.FriendAccept, 1))
            .addAction(0, context.getString(R.string.reject), act(Action.FriendReject, 2))
            .setContentIntent(
                NavDeepLinkBuilder(context)
                    .setGraph(R.navigation.nav_graph)
                    .setDestination(R.id.friendRequestFragment)
                    .setArguments(bundleOf(com.stoyko.smessenger.ui.friendrequest.FRIEND_REQUEST_PUBLIC_KEY to friendRequest.publicKey))
                    .createPendingIntent(),
            )
            .setAutoCancel(true)
            .setSilent(silent)

        notifier.notify(friendRequest.publicKey.hashCode(), notificationBuilder.build())
    }

    /**
     * Включить экран при входящем звонке. Полноэкранное уведомление на части телефонов (Xiaomi,
     * Android 14+ без разрешения) экран само не будит - поэтому включаем его коротким замком.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) return
            pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "smessenger:incoming_call",
            ).acquire(15_000)
        }
    }

    /** Может ли приложение показывать звонки на весь экран (Android 14+ требует разрешения). */
    fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < 34 || (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    fun dismissCallNotification(pk: PublicKey) = notifier.cancel(pk.string().hashCode() + CALL.hashCode())

    fun showOngoingCallNotification(contact: Contact) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Call ongoing, notifications disallowed")
            return
        }

        dismissCallNotification(PublicKey(contact.publicKey))
        val notificationBuilder = NotificationCompat.Builder(context, CALL)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.ongoing_call))
            .setContentText(
                context.getString(
                    R.string.in_call_with,
                    contact.name.ifEmpty { context.getString(R.string.contact_default_name) },
                ),
            )
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(
                NavDeepLinkBuilder(context)
                    .setGraph(R.navigation.nav_graph)
                    .addDestination(R.id.chatFragment, bundleOf(CONTACT_PUBLIC_KEY to contact.publicKey))
                    .addDestination(R.id.callFragment, bundleOf(CONTACT_PUBLIC_KEY to contact.publicKey))
                    .createPendingIntent(),
            )
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        IconCompat.createWithResource(context, R.drawable.ic_call_end),
                        context.getString(R.string.end_call),
                        PendingIntentCompat.getBroadcast(
                            context,
                            "${contact.publicKey}_end_call".hashCode(),
                            Intent(context, ActionReceiver::class.java)
                                .putExtra(KEY_CONTACT_PK, contact.publicKey)
                                .putExtra(KEY_ACTION, Action.CallEnd),
                            PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build(),
            )
            .setOngoing(true)
            .setSilent(true)

        notifier.notify(contact.publicKey.hashCode() + CALL.hashCode(), notificationBuilder.build())
    }

    fun showPendingCallNotification(status: UserStatus, c: Contact) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Call pending, notifications disallowed")
            return
        }

        val notificationBuilder = NotificationCompat.Builder(context, CALL)

        // Экран входящего звонка на весь экран: будит телефон и показывается поверх блокировки.
        val fullScreen = PendingIntentCompat.getActivity(
            context,
            "${c.publicKey}_incoming_call".hashCode(),
            Intent(context, com.stoyko.smessenger.MainActivity::class.java)
                .setAction(com.stoyko.smessenger.ACTION_INCOMING_CALL)
                .putExtra(KEY_CONTACT_PK, c.publicKey)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notificationBuilder
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(60_000)
        wakeScreen()

        val notification = notificationBuilder
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.incoming_call))
            .setContentText(context.getString(R.string.incoming_call_from, c.name))
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        IconCompat.createWithResource(context, R.drawable.ic_call),
                        context.getString(R.string.accept),
                        // «Принять» сразу открывает мессенджер на весь экран - экран звонка.
                        PendingIntentCompat.getActivity(
                            context,
                            "${c.publicKey}_accept_call".hashCode(),
                            Intent(context, com.stoyko.smessenger.MainActivity::class.java)
                                .setAction(com.stoyko.smessenger.ACTION_ACCEPT_CALL)
                                .putExtra(KEY_CONTACT_PK, c.publicKey)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_CALL)
                    .build(),
            )
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        IconCompat.createWithResource(context, R.drawable.ic_not_interested),
                        context.getString(R.string.reject),
                        PendingIntentCompat.getBroadcast(
                            context,
                            "${c.publicKey}_reject_call".hashCode(),
                            Intent(context, ActionReceiver::class.java)
                                .putExtra(KEY_CONTACT_PK, c.publicKey)
                                .putExtra(KEY_ACTION, Action.CallReject),
                            PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build(),
            )
            .setDeleteIntent(
                PendingIntentCompat.getBroadcast(
                    context,
                    "${c.publicKey}_ignore_call".hashCode(),
                    Intent(context, ActionReceiver::class.java)
                        .putExtra(KEY_CONTACT_PK, c.publicKey)
                        .putExtra(KEY_ACTION, Action.CallIgnore),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setSound(RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE))
            .setSilent(status == UserStatus.Busy)
            .build()
            .apply {
                flags = flags.or(NotificationCompat.FLAG_INSISTENT)
            }

        notifier.notify(c.publicKey.hashCode() + CALL.hashCode(), notification)
    }

    /** Уведомление о сообщении в группе или канале. */
    fun showGroupMessageNotification(
        chatId: String,
        groupName: String,
        sender: String,
        message: String,
        isChannel: Boolean = false,
    ) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Уведомления группы/канала выключены (на время или навсегда).
        if (com.stoyko.smessenger.chat.ChatMute.isMuted(context, chatId)) return
        val prefs = com.stoyko.smessenger.settings.Settings(context)
        if (if (isChannel) !prefs.notifyChannels else !prefs.notifyGroups) return
        val hide = prefs.hideNotificationText
        val title = if (hide) context.getString(R.string.app_name) else groupName.ifEmpty { context.getString(R.string.group_default_name) }
        val text = if (hide) context.getString(R.string.notification_hidden_text) else if (sender.isEmpty()) message else "$sender: $message"
        val intent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.groupChatFragment)
            .setArguments(bundleOf(com.stoyko.smessenger.ui.groups.GROUP_CHAT_ID to chatId))
            .createPendingIntent()
        val unread = AppBadge.bump(context, chatId)
        val n = NotificationCompat.Builder(context, MESSAGE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setSilent(!prefs.notifySound)
            .setNumber(unread)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        AppBadge.decorate(n, unread)
        notifier.notify(chatId.hashCode(), n)
    }

    fun dismissGroupNotification(chatId: String) {
        notifier.cancel(chatId.hashCode())
        AppBadge.clear(context, chatId)
    }

    private fun deepLinkToChat(publicKey: PublicKey, focusMessageBox: Boolean = false) = NavDeepLinkBuilder(context)
        .setGraph(R.navigation.nav_graph)
        .setDestination(R.id.chatFragment)
        .setArguments(
            bundleOf(
                CONTACT_PUBLIC_KEY to publicKey.string(),
                FOCUS_ON_MESSAGE_BOX to focusMessageBox,
            ),
        )
        .createPendingIntent()
}
