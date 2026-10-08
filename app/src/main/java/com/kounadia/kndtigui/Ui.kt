package com.kounadia.kndtigui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Systeme de design KND-Tigui : couleurs, cartes, boutons, fiche en bas d'ecran. */
object Ui {
    val BG = 0xFF0B0C10.toInt()
    val SURFACE = 0xFF15171E.toInt()
    val ELEVATED = 0xFF1C1F28.toInt()
    val BORDER = 0xFF292D38.toInt()
    val PRIMARY = 0xFF6575FF.toInt()
    val SUCCESS = 0xFF35C759.toInt()
    val WARNING = 0xFFFFB020.toInt()
    val ERROR = 0xFFFF5C5C.toInt()
    val TEXT = 0xFFF5F6FA.toInt()
    val TEXT2 = 0xFFA6AAB8.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun rounded(context: Context, fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) d.setStroke(dp(context, 1), stroke)
        return d
    }

    fun circle(fill: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        return d
    }

    fun text(context: Context, value: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val v = TextView(context)
        v.text = value
        v.textSize = sizeSp
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    fun input(context: Context, hint: String, password: Boolean = false, email: Boolean = false): android.widget.EditText {
        val e = android.widget.EditText(context)
        e.hint = hint
        e.setHintTextColor(TEXT2)
        e.setTextColor(TEXT)
        e.textSize = 15f
        e.isSingleLine = true
        e.inputType = when {
            password -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            email -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        e.background = rounded(context, ELEVATED, 14, BORDER)
        e.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(context, 12))
        e.layoutParams = lp
        return e
    }

    fun statusInfo(status: String): Pair<String, Int> = when (status) {
        "PAYMENT_CONFIRMED" -> Pair("À traiter", WARNING)
        "PROCESSING" -> Pair("En cours", PRIMARY)
        "SUCCESS" -> Pair("Réussi", SUCCESS)
        "PAYMENT_PENDING", "PAYMENT_LATE" -> Pair("En attente", WARNING)
        "CANCELLED" -> Pair("Annulé", TEXT2)
        "PAYMENT_EXPIRED" -> Pair("Expiré", TEXT2)
        else -> Pair(status, TEXT2)
    }

    fun pill(context: Context, label: String, color: Int): TextView {
        val v = text(context, "● $label", 12f, color, true)
        v.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5))
        v.background = rounded(context, withAlpha(color, 0x26), 20)
        return v
    }

    /** Petit effet d'appui puis action. */
    fun pressable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.setOnClickListener { v ->
            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(70).withEndAction { onClick() }.start()
            }.start()
        }
    }

    fun button(context: Context, label: String, style: String = "primary", onClick: () -> Unit): TextView {
        val fill: Int
        val textColor: Int
        val stroke: Int?
        when (style) {
            "primary" -> { fill = PRIMARY; textColor = Color.WHITE; stroke = null }
            "danger" -> { fill = withAlpha(ERROR, 0x26); textColor = ERROR; stroke = null }
            else -> { fill = ELEVATED; textColor = TEXT; stroke = BORDER }
        }
        val v = text(context, label, 15f, textColor, true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        v.background = rounded(context, fill, 16, stroke)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(context, 12), 0, 0)
        v.layoutParams = lp
        pressable(v, onClick)
        return v
    }

    /** Bloc titre + lignes libelle/valeur. Les valeurs "copiables" s'affichent en bleu. */
    fun section(
        context: Context,
        title: String,
        rows: List<Pair<String, String>>,
        copyable: Set<String> = emptySet(),
        onCopy: (String, String) -> Unit = { _, _ -> },
    ): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 8))
        box.background = rounded(context, ELEVATED, 16, BORDER)

        val header = text(context, title.uppercase(), 11f, TEXT2, true)
        header.letterSpacing = 0.08f
        header.setPadding(0, 0, 0, dp(context, 4))
        box.addView(header)

        for ((label, value) in rows) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(context, 8), 0, dp(context, 8))
            row.addView(
                text(context, label, 13f, TEXT2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val isCopyable = copyable.contains(label)
            val v = text(context, value, 14f, if (isCopyable) PRIMARY else TEXT, true)
            v.gravity = Gravity.END
            if (isCopyable) {
                v.setOnClickListener { onCopy(label, value) }
            }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            box.addView(row)
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(context, 12), 0, 0)
        box.layoutParams = lp
        return box
    }

    /**
     * Desactive le clavier systeme sur ce champ (utilise avec numericKeypad
     * pour forcer la saisie via le clavier fixe de l'application).
     */
    fun disableSystemKeyboard(editText: android.widget.EditText) {
        editText.showSoftInputOnFocus = false
        editText.isLongClickable = true
        editText.setTextIsSelectable(true)
    }

    /**
     * Clavier numerique fixe (0-9, coller, effacer). La hauteur des touches
     * est calculee d'apres la hauteur de l'ecran (pas de poids en hauteur,
     * donc rendu previsible) pour que l'ecran tienne sans defilement.
     */
    fun numericKeypad(
        context: Context,
        editText: android.widget.EditText,
        allowDecimal: Boolean = false,
        onChanged: () -> Unit = {},
    ): LinearLayout {
        disableSystemKeyboard(editText)

        val metrics = context.resources.displayMetrics
        val screenDp = metrics.heightPixels / metrics.density
        val keyHeightDp = (screenDp * 0.065f).toInt().coerceIn(42, 60)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        fun appendChar(c: String) {
            val start = editText.selectionStart.coerceAtLeast(0)
            val end = editText.selectionEnd.coerceAtLeast(0)
            editText.text.replace(minOf(start, end), maxOf(start, end), c)
            onChanged()
        }

        fun backspace() {
            val start = editText.selectionStart.coerceAtLeast(0)
            val end = editText.selectionEnd.coerceAtLeast(0)
            if (start != end) {
                editText.text.replace(minOf(start, end), maxOf(start, end), "")
            } else if (start > 0) {
                editText.text.replace(start - 1, start, "")
            }
            onChanged()
        }

        fun pasteFromClipboard() {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).coerceToText(context).toString()
                val filtered = if (allowDecimal) {
                    pasted.filter { it.isDigit() || it == '.' }
                } else {
                    pasted.filter { it.isDigit() }
                }
                if (filtered.isNotEmpty()) {
                    editText.setText(filtered)
                    editText.setSelection(filtered.length)
                    onChanged()
                }
            }
        }

        fun keyButton(label: String, color: Int = TEXT, size: Float = 22f, action: () -> Unit): View {
            val btn = text(context, label, size, color, true)
            btn.gravity = Gravity.CENTER
            btn.background = rounded(context, ELEVATED, 14, BORDER)
            val lp = LinearLayout.LayoutParams(0, dp(context, keyHeightDp), 1f)
            lp.setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
            btn.layoutParams = lp
            btn.setOnClickListener { action() }
            btn.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(40).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
                }
                false
            }
            return btn
        }

        fun row(vararg views: View): LinearLayout {
            val r = LinearLayout(context)
            r.orientation = LinearLayout.HORIZONTAL
            r.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            for (v in views) r.addView(v)
            return r
        }

        root.addView(row(
            keyButton("1") { appendChar("1") },
            keyButton("2") { appendChar("2") },
            keyButton("3") { appendChar("3") },
        ))
        root.addView(row(
            keyButton("4") { appendChar("4") },
            keyButton("5") { appendChar("5") },
            keyButton("6") { appendChar("6") },
        ))
        root.addView(row(
            keyButton("7") { appendChar("7") },
            keyButton("8") { appendChar("8") },
            keyButton("9") { appendChar("9") },
        ))
        root.addView(row(
            if (allowDecimal) {
                keyButton(".", PRIMARY) { appendChar(".") }
            } else {
                keyButton("Coller", PRIMARY, 14f) { pasteFromClipboard() }
            },
            keyButton("0") { appendChar("0") },
            keyButton("⌫", ERROR) { backspace() },
        ))

        return root
    }

    /** Fiche qui monte du bas de l'ecran. Renvoie la fenetre et son conteneur. */
    fun bottomSheet(context: Context): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val scroll = ScrollView(context)
        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 28))

        val r = dp(context, 24).toFloat()
        val bg = GradientDrawable()
        bg.setColor(SURFACE)
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        scroll.background = bg
        scroll.addView(content)

        val handle = View(context)
        handle.background = rounded(context, BORDER, 4)
        val hp = LinearLayout.LayoutParams(dp(context, 40), dp(context, 4))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.setMargins(0, 0, 0, dp(context, 16))
        content.addView(handle, hp)

        dialog.setContentView(scroll)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            val attrs = window.attributes
            attrs.windowAnimations = android.R.style.Animation_InputMethod
            window.attributes = attrs
        }
        return Pair(dialog, content)
    }
}
