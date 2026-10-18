// SPDX-FileCopyrightText: 2022 aTox contributors
// SPDX-FileCopyrightText: 2022-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.doOnPreDraw
import com.google.android.material.imageview.ShapeableImageView
import kotlin.math.min
import kotlin.math.sqrt
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.UserStatus

private const val TAG = "AvatarImageView"

class AvatarImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ShapeableImageView(context, attrs, defStyleAttr) {

    private val statusIndicatorPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = colorFromStatus(context, UserStatus.None)
    }

    private val statusIndicatorVisible: Boolean = context.theme.obtainStyledAttributes(
        attrs,
        R.styleable.AvatarImageView,
        0,
        0,
    ).use { it.getBoolean(R.styleable.AvatarImageView_statusIndicatorVisible, true) }

    private fun colorByContactStatus(context: Context, contact: Contact) =
        if (contact.connectionStatus == ConnectionStatus.None) {
            ContextCompat.getColor(context, R.color.statusOffline)
        } else {
            colorFromStatus(context, contact.status)
        }

    private var name = ""
    private var publicKey = ""
    private var avatarUri = ""

    fun setFrom(contact: Contact) {
        statusIndicatorPaint.color = colorByContactStatus(context, contact)
        // Assigning to the object's properties as capturing those values in the doOnPreDraw's lambda
        // either directly or using temporary `val` objects decreases performance of scrolling SIGNIFICANTLY.
        name = contact.name
        publicKey = contact.publicKey
        avatarUri = contact.avatarUri

        doOnPreDraw {
            val uri = if (avatarUri.isNotEmpty()) existingAvatar(avatarUri, publicKey) else null
            if (uri != null) {
                setImageURI(uri)
            } else if (width > 0 && height > 0) {
                setImageBitmap(AvatarFactory.create(resources, name, publicKey, Px(min(width, height))))
            } else {
                // TODO(robinlinden): When does this even happen?
                Log.w(TAG, "Got 0-size drawing area: $width $height")
            }
        }
        invalidate()
    }

    /**
     * Файл аватарки мог смениться (новое фото - новое имя файла с другим временем), а в
     * карточке контакта ещё старый путь: раньше это давало ошибку «файл не найден» и пустой
     * круг. Берём самый свежий файл этого контакта, нет ни одного - рисуем букву.
     */
    private fun existingAvatar(uri: String, pk: String): android.net.Uri? {
        if (!uri.startsWith("file:")) return uri.toUri()
        val f = java.io.File(uri.toUri().path ?: return null)
        if (f.isFile && f.length() > 0) return uri.toUri()
        val dir = f.parentFile ?: return null
        val prefix = pk.ifEmpty { f.name.substringBefore('_') }
        if (prefix.isEmpty()) return null
        val fresh = dir.listFiles { x -> x.isFile && x.name.startsWith(prefix, ignoreCase = true) && x.length() > 0 }
            ?.maxByOrNull { it.lastModified() } ?: return null
        return android.net.Uri.fromFile(fresh)
    }

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
    }

    /**
     * Точка «в сети» - в углу, СНАРУЖИ круга аватарки (касается его, но не налезает): раньше её
     * центр лежал прямо на краю круга и точка закрывала часть фото и видео-аватарки.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!statusIndicatorVisible) return

        val size = min(width, height).toFloat()
        val r = size / 2
        val gap = resources.displayMetrics.density * 0.8f
        // Самая большая точка, которая помещается в угол квадрата, не задевая круг.
        val radius = ((sqrt(2f) - 1f) * r - gap) / (1f + sqrt(2f))
        if (radius <= 0f) return
        val offset = (r + radius + gap) / sqrt(2f)
        val x = width / 2f + offset
        val y = height / 2f + offset
        canvas.drawCircle(x, y, radius, statusIndicatorPaint)
        ringPaint.strokeWidth = (radius * 0.22f).coerceAtLeast(1f)
        canvas.drawCircle(x, y, radius - ringPaint.strokeWidth / 2, ringPaint)
    }
}
