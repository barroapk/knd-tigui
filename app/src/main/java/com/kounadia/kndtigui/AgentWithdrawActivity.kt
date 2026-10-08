package com.kounadia.kndtigui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val SUPPORT_WHATSAPP = "22655337782"

/**
 * Retrait agent : saisie du montant (clavier fixe), creation de la demande
 * via POST /agents/withdrawals (statut CREATED), puis ouverture automatique
 * de WhatsApp avec le message prerempli. Le serveur reste l'autorite : le
 * numero Orange Money de l'agent est celui du compte, jamais saisi ici.
 */
class AgentWithdrawActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false
    private var amountValue: Long = 0

    private fun dp(value: Int): Int = Ui.dp(this, value)
    private fun t(value: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(this, value, size, color, bold)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionStorage.isLoggedIn(this) || SessionStorage.getRole(this) != "AGENT") {
            finish()
            return
        }
        renderAmountStep()
    }

    private fun newRootColumn(): LinearLayout {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(28), dp(24), dp(12))
        sv.addView(col)
        setContentView(sv)
        return col
    }

    private fun header(title: String, subtitle: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, dp(16))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.addView(t(title, 20f, Ui.TEXT, true))
        if (subtitle.isNotBlank()) col.addView(t(subtitle, 13f, Ui.TEXT2))
        row.addView(col)

        val close = t("✕", 20f, Ui.TEXT2)
        close.setOnClickListener { finish() }
        row.addView(close)
        return row
    }

    private fun spacer(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        return v
    }

    private fun formatAmount(value: Long): String =
        value.toString().reversed().chunked(3).joinToString(" ").reversed()

    private fun amountField(): EditText {
        val e = EditText(this)
        e.hint = "Montant"
        e.setHintTextColor(Ui.TEXT2)
        e.setTextColor(Ui.TEXT)
        e.textSize = 24f
        e.gravity = Gravity.CENTER
        e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        e.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        return e
    }

    // ---------- Etape 1 : montant ----------

    private fun renderAmountStep() {
        amountValue = 0
        val col = newRootColumn()

        col.addView(header("Retirer", "Demande de retrait"))

        val amountInput = amountField()
        val amountRow = LinearLayout(this)
        amountRow.orientation = LinearLayout.HORIZONTAL
        amountRow.gravity = Gravity.CENTER_VERTICAL
        amountRow.addView(amountInput)
        val fcfaLabel = t("FCFA", 16f, Ui.TEXT2, true)
        fcfaLabel.setPadding(dp(12), 0, dp(4), 0)
        amountRow.addView(fcfaLabel)
        col.addView(amountRow)

        fun syncFromInput() {
            amountValue = amountInput.text.toString().toLongOrNull() ?: 0L
        }

        fun setAmount(value: Long) {
            amountValue = value
            amountInput.setText(if (value > 0) value.toString() else "")
            amountInput.setSelection(amountInput.text.length)
        }

        amountInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) { syncFromInput() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        col.addView(spacer(6))

        val quickRow = LinearLayout(this)
        quickRow.orientation = LinearLayout.HORIZONTAL
        for (amt in listOf(500L, 1000L, 2000L, 5000L, 10000L)) {
            val label = if (amt >= 1000) "${amt / 1000}K" else amt.toString()
            val btn = t(label, 13f, Ui.TEXT, true)
            btn.gravity = Gravity.CENTER
            btn.background = Ui.rounded(this, Ui.ELEVATED, 12, Ui.BORDER)
            btn.setPadding(0, dp(10), 0, dp(10))
            val btnLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            btnLp.setMargins(dp(3), 0, dp(3), 0)
            btn.layoutParams = btnLp
            btn.setOnClickListener { setAmount(amountValue + amt) }
            quickRow.addView(btn)
        }
        col.addView(quickRow)

        val messageText = t("", 13f, Ui.ERROR)
        messageText.setPadding(0, dp(6), 0, 0)
        col.addView(messageText)

        val confirmButton = Ui.button(this, "Demander le retrait") { }
        col.addView(confirmButton)
        col.addView(spacer(6))
        col.addView(Ui.numericKeypad(this, amountInput, allowDecimal = false) { syncFromInput() })

        confirmButton.setOnClickListener {
            if (amountValue < 200) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Montant minimum : 200 FCFA"
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener

            busy = true
            confirmButton.isEnabled = false
            confirmButton.alpha = 0.5f
            messageText.setTextColor(Ui.TEXT2)
            messageText.text = "Création de la demande…"

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@AgentWithdrawActivity)
                    val token = SessionStorage.getToken(this@AgentWithdrawActivity) ?: return@launch
                    val body = JSONObject().put("amount", amountValue)

                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/agents/withdrawals", token, body))
                    }

                    busy = false
                    renderDoneStep(response)
                    openWhatsapp(response.optLong("amount", amountValue))
                } catch (e: ApiException) {
                    busy = false
                    confirmButton.isEnabled = true
                    confirmButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = e.message
                } catch (e: Exception) {
                    busy = false
                    confirmButton.isEnabled = true
                    confirmButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = "Connexion impossible. Réessayez."
                }
            }
        }
    }

    // ---------- Etape 2 : confirmation ----------

    private fun renderDoneStep(withdrawal: JSONObject) {
        val col = newRootColumn()
        val amount = withdrawal.optLong("amount", 0)
        val reference = withdrawal.optString("reference", "")

        col.addView(header("Demande envoyée", ""))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        card.setPadding(dp(18), dp(16), dp(18), dp(16))
        card.addView(t("${formatAmount(amount)} FCFA", 26f, Ui.TEXT, true))
        card.addView(spacer(4))
        card.addView(t("Référence : $reference", 13f, Ui.TEXT2))
        card.addView(spacer(8))
        card.addView(Ui.pill(this, "En attente de traitement", Ui.WARNING))
        col.addView(card)

        col.addView(spacer(16))
        col.addView(t(
            "Un gestionnaire va traiter votre demande et envoyer le montant sur votre compte Orange Money.",
            13f, Ui.TEXT2,
        ))
        col.addView(spacer(8))
        col.addView(t(
            "WhatsApp s'ouvre pour envoyer votre message. Indiquez la ville et la rue pour le retrait.",
            13f, Ui.TEXT2,
        ))

        col.addView(Ui.button(this, "Renvoyer sur WhatsApp", "secondary") { openWhatsapp(amount) })
        col.addView(Ui.button(this, "Terminer") { finish() })
    }

    private fun openWhatsapp(amount: Long) {
        val company = SessionStorage.getCompanyName(this) ?: (SessionStorage.getDisplayName(this) ?: "")
        val message = "Bonjour, je suis agent $company, je souhaiterais faire un retrait de " +
            "${formatAmount(amount)} FCFA pour mon client. " +
            "Merci de m'envoyer l'adresse (ville et rue) pour effectuer le retrait."

        val uri = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(SUPPORT_WHATSAPP)
            .appendQueryParameter("text", message)
            .build()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }
}
