// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.userprofile

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.profile.ExtProfile
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * «Изменить профиль»: фото сверху, затем разделы с полями и подсказками -
 * имя и «о себе», имя пользователя, статус, эмодзи-статус, город, день рождения, ссылка.
 * Сохранение - галочкой в шапке; всё сразу уходит друзьям в карточке профиля.
 */
object ProfileEditor {
    class Values(
        val name: String,
        val statusMessage: String,
        val ext: ExtProfile,
    )

    fun show(
        ctx: Context,
        current: Values,
        bindAvatar: (ImageView) -> Unit,
        onPhoto: () -> Unit,
        pickEmoji: ((String) -> Unit) -> Unit,
        /** Статус сети (В сети / Отошёл / Занят): подпись и выбор (после выбора - обновить подпись). */
        availabilityLabel: (() -> String)? = null,
        onAvailability: ((() -> Unit) -> Unit)? = null,
        onSave: (Values) -> Unit,
    ) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        val cCard = ContextCompat.getColor(ctx, R.color.colorSurface)
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val cAccent = ContextCompat.getColor(ctx, R.color.settingsAccent)

        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        // ---- шапка ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_brand_gradient)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        bar.addView(
            TextView(ctx).apply {
                setText(R.string.profile_edit_title)
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val save = TextView(ctx).apply {
            text = "✓"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }
        bar.addView(save, LinearLayout.LayoutParams(dp(52), dp(48)))
        Kinetics.squish(save, 0.85f)
        root.addView(bar)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        // ---- фото ----
        val photoBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(20), 0, dp(10))
        }
        // Фото строго по центру: рамка фиксированного размера, круглая обрезка, значок камеры
        // в правом нижнем углу рамки (раньше рамка подстраивалась под картинку и фото «съезжало»).
        val avatar = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        bindAvatar(avatar)
        val avatarFrame = FrameLayout(ctx)
        avatarFrame.addView(avatar, FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER))
        avatarFrame.addView(
            TextView(ctx).apply {
                text = "📷"
                textSize = 16f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF5B4BDB.toInt())
                    setStroke(dp(3), cBg)
                }
            },
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END),
        )
        photoBox.addView(avatarFrame, LinearLayout.LayoutParams(dp(116), dp(116)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // После выбора/обрезки фото окно снова в фокусе - обновляем аватар здесь же.
        avatar.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (focused) bindAvatar(avatar) }
        photoBox.addView(
            TextView(ctx).apply {
                setText(R.string.profile_photo_choose)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(cAccent)
                setPadding(0, dp(10), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        photoBox.setOnClickListener { onPhoto() }
        Kinetics.squish(avatarFrame, 0.94f)
        avatarFrame.setOnClickListener { onPhoto() }
        body.addView(photoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun card(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(cCard)
            }
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        fun section(title: Int?, hint: Int?, build: (LinearLayout) -> Unit) {
            if (title != null) {
                body.addView(
                    TextView(ctx).apply {
                        setText(title)
                        textSize = 13f
                        setTextColor(cAccent)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(28), dp(18), dp(28), dp(6))
                    },
                )
            }
            val c = card()
            build(c)
            body.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(14), if (title == null) dp(10) else 0, dp(14), 0)
            })
            if (hint != null) {
                body.addView(
                    TextView(ctx).apply {
                        setText(hint)
                        textSize = 13f
                        setTextColor(cSub)
                        setPadding(dp(28), dp(6), dp(28), 0)
                    },
                )
            }
        }
        fun divider(c: LinearLayout) = c.addView(View(ctx).apply { setBackgroundColor(0x1F888888) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        fun field(c: LinearLayout, hint: Int, value: String, max: Int, multi: Boolean = false, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): EditText {
            val e = EditText(ctx).apply {
                setHint(hint)
                setText(value)
                textSize = 16f
                setTextColor(cText)
                setHintTextColor(cSub)
                background = null
                inputType = if (multi) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
                filters = arrayOf(InputFilter.LengthFilter(max))
                if (multi) {
                    minLines = 1
                    maxLines = 5
                } else {
                    setSingleLine()
                }
                setPadding(0, dp(12), 0, dp(12))
            }
            c.addView(e)
            return e
        }

        var name: EditText? = null
        var bio: EditText? = null
        val counter = TextView(ctx).apply {
            textSize = 12f
            setTextColor(cSub)
            gravity = Gravity.END
        }
        section(null, R.string.profile_bio_hint) { c ->
            name = field(c, R.string.profile_name_hint, current.name, 128)
            divider(c)
            bio = field(c, R.string.profile_bio, current.ext.bio, ExtProfile.MAX_BIO, multi = true)
            c.addView(counter)
        }
        fun updateCounter() {
            counter.text = (ExtProfile.MAX_BIO - (bio?.text?.length ?: 0)).toString()
        }
        updateCounter()
        bio?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) = updateCounter()
        })

        var username: EditText? = null
        section(R.string.profile_username, R.string.profile_username_hint) { c ->
            username = field(c, R.string.profile_username, current.ext.username, 32, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        }

        var status: EditText? = null
        var city: EditText? = null
        var link: EditText? = null
        var birthday = current.ext.birthday
        var emojiStatus = current.ext.emojiStatus
        section(R.string.profile_more, null) { c ->
            if (availabilityLabel != null && onAvailability != null) {
                val av = TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(cText)
                    setPadding(0, dp(14), 0, dp(14))
                }
                fun avLabel() {
                    av.text = ctx.getString(R.string.profile_availability_label, availabilityLabel())
                }
                avLabel()
                av.setOnClickListener { onAvailability { avLabel() } }
                c.addView(av)
                divider(c)
            }
            status = field(c, R.string.status_message, current.statusMessage, 1007)
            divider(c)
            // Эмодзи-статус
            val es = TextView(ctx).apply {
                textSize = 16f
                setTextColor(cText)
                setPadding(0, dp(14), 0, dp(14))
            }
            fun esLabel() {
                es.text = ctx.getString(R.string.emoji_status_label, emojiStatus.ifEmpty { ctx.getString(R.string.emoji_status_none) })
            }
            esLabel()
            es.setOnClickListener {
                pickEmoji { e ->
                    emojiStatus = e
                    esLabel()
                    es.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90).withEndAction {
                        es.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
                    }.start()
                }
            }
            c.addView(es)
            divider(c)
            city = field(c, R.string.profile_city, current.ext.city, 64)
            divider(c)
            // День рождения - через календарь.
            val bd = TextView(ctx).apply {
                textSize = 16f
                setPadding(0, dp(14), 0, dp(14))
            }
            fun bdLabel() {
                bd.text = if (birthday.isBlank()) ctx.getString(R.string.profile_birthday) else ctx.getString(R.string.profile_birthday) + ": " + birthday
                bd.setTextColor(if (birthday.isBlank()) cSub else cText)
            }
            bdLabel()
            val bdLocked = {
                if (!com.stoyko.smessenger.chat.Birthday.canChange(ctx, current.ext.birthday, current.ext.birthday + "?")) {
                    android.widget.Toast.makeText(ctx, com.stoyko.smessenger.chat.Birthday.lockText(ctx), android.widget.Toast.LENGTH_LONG).show()
                    true
                } else {
                    false
                }
            }
            bd.setOnClickListener {
                if (bdLocked()) return@setOnClickListener
                android.widget.Toast.makeText(ctx, R.string.bd_once_year, android.widget.Toast.LENGTH_LONG).show()
                val cal = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(ctx, { _, y, m, day ->
                    birthday = "%02d.%02d.%04d".format(day, m + 1, y)
                    bdLabel()
                }, cal.get(java.util.Calendar.YEAR) - 20, cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
            bd.setOnLongClickListener {
                if (bdLocked()) return@setOnLongClickListener true
                birthday = ""
                bdLabel()
                true
            }
            c.addView(bd)
            divider(c)
            link = field(c, R.string.profile_link, current.ext.link, 200, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        }

        save.setOnClickListener {
            val newName = name?.text?.toString()?.trim().orEmpty()
            if (newName.isEmpty()) {
                name?.error = ctx.getString(R.string.profile_name_required)
                name?.requestFocus()
                return@setOnClickListener
            }
            com.stoyko.smessenger.chat.Birthday.onChanged(ctx, current.ext.birthday, birthday)
            onSave(
                Values(
                    newName,
                    status?.text?.toString()?.trim().orEmpty(),
                    current.ext.copy(
                        username = username?.text?.toString()?.trim()?.removePrefix("@").orEmpty(),
                        bio = bio?.text?.toString()?.trim().orEmpty(),
                        city = city?.text?.toString()?.trim().orEmpty(),
                        birthday = birthday,
                        link = link?.text?.toString()?.trim().orEmpty(),
                        emojiStatus = emojiStatus,
                    ),
                ),
            )
            dialog.dismiss()
        }

        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        // Разделы появляются по очереди.
        Kinetics.stagger((0 until body.childCount).map { body.getChildAt(it) }, step = 40)
    }
}
