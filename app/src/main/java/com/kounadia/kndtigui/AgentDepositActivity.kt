package com.kounadia.kndtigui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import org.json.JSONArray
import org.json.JSONObject

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * Depot agent en 2 etapes : ID 1xBet + verification, puis montant cumulatif
 * + USSD agent dedie. Le serveur reste l'autorite : bonus force a 0,
 * numero Orange Money toujours celui du compte agent (jamais saisi ici).
 */
class AgentDepositActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var step = 1
    private var busy = false
    private var verifiedPlayerId: String? = null
    private var verifiedPlayerName: String? = null
    private var amountCents: Long = 0

    private fun dp(value: Int): Int = Ui.dp(this, value)
    private fun t(value: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(this, value, size, color, bold)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionStorage.isLoggedIn(this) || SessionStorage.getRole(this) != "AGENT") {
            finish()
            return
        }
        renderStep1()
    }

    private fun header(title: String, subtitle: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, dp(28))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.layoutParams = colLp
        col.addView(t(title, 20f, Ui.TEXT, true))
        col.addView(t(subtitle, 13f, Ui.TEXT2))
        row.addView(col)

        val close = t("✕", 20f, Ui.TEXT2)
        close.setOnClickListener { finish() }
        row.addView(close)

        return row
    }

    private fun field(hint: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Ui.TEXT2)
        e.setTextColor(Ui.TEXT)
        e.textSize = 15f
        e.isSingleLine = true
        e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, 0, 0, dp(12))
        e.layoutParams = lp
        return e
    }

    // ---------- Etape 1 : ID 1xBet + favoris + verification ----------

    private fun renderStep1() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(48), dp(24), dp(32))
        sv.addView(col)
        setContentView(sv)

        col.addView(header("Recharger compte 1xBet", "Étape 1/2"))
        col.addView(t("Saisissez l'ID 1xBet du joueur", 14f, Ui.TEXT2))
        col.addView(spacer(8))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val idInput = field("ID joueur", true)
        val idLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        idInput.layoutParams = idLp
        row.addView(idInput)

        row.addView(spacerHoriz(8))

        val favButton = Ui.button(this, "☰", "secondary") { showFavoritesSheet(idInput) }
        row.addView(favButton)
        col.addView(row)

        val messageText = t("", 13f, Ui.ERROR)
        messageText.setPadding(0, dp(8), 0, dp(8))
        col.addView(messageText)

        col.addView(spacer(16))

        val nextButton = Ui.button(this, "Suivant") { }
        col.addView(nextButton)

        nextButton.setOnClickListener {
            val playerId = idInput.text.toString().trim()
            if (playerId.isBlank()) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Saisissez un ID 1xBet"
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener

            busy = true
            nextButton.isEnabled = false
            nextButton.alpha = 0.5f
            messageText.setTextColor(Ui.TEXT2)
            messageText.text = "Vérification…"

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "GET", "/player-verification/verify?playerId=$playerId", null, null))
                    }

                    val valid = response.optBoolean("valid", false)
                    val playerName = response.optString("playerName", "")

                    busy = false
                    nextButton.isEnabled = true
                    nextButton.alpha = 1f

                    if (valid && playerName.isNotBlank()) {
                        verifiedPlayerId = playerId
                        verifiedPlayerName = playerName
                        step = 2
                        renderStep2()
                    } else {
                        messageText.setTextColor(Ui.ERROR)
                        messageText.text = "Compte 1xBet introuvable ou non vérifié"
                    }
                } catch (e: ApiException) {
                    busy = false
                    nextButton.isEnabled = true
                    nextButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = e.message
                } catch (e: Exception) {
                    busy = false
                    nextButton.isEnabled = true
                    nextButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = "Connexion impossible. Réessayez."
                }
            }
        }
    }

    private fun showFavoritesSheet(idInput: EditText) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                val token = SessionStorage.getToken(this@AgentDepositActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/agents/favorites", token, null))
                }

                val (sheet, content) = Ui.bottomSheet(this@AgentDepositActivity)
                content.addView(t("Mes joueurs", 18f, Ui.TEXT, true))
                content.addView(spacer(12))

                if (response.length() == 0) {
                    content.addView(t("Aucun joueur favori pour l'instant.", 13f, Ui.TEXT2))
                }

                for (i in 0 until response.length()) {
                    val fav = response.getJSONObject(i)
                    val favId = fav.getString("playerId")
                    val favName = fav.optString("playerName", "")
                    val favFavId = fav.getString("id")

                    val row = LinearLayout(this@AgentDepositActivity)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(0, dp(8), 0, dp(8))

                    val label = t("$favId · $favName", 14f, Ui.TEXT)
                    val labelLp = LinearLayout.LayoutParams(0, WRAP, 1f)
                    label.layoutParams = labelLp
                    label.setOnClickListener {
                        idInput.setText(favId)
                        sheet.dismiss()
                    }
                    row.addView(label)

                    val removeIcon = t("✕", 16f, Ui.ERROR)
                    removeIcon.setPadding(dp(12), 0, dp(4), 0)
                    removeIcon.setOnClickListener {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient.request(base, "DELETE", "/agents/favorites/$favFavId", token, null)
                                }
                            } catch (_: Exception) {
                            }
                            sheet.dismiss()
                        }
                    }
                    row.addView(removeIcon)

                    content.addView(row)
                }

                sheet.show()
            } catch (_: Exception) {
                Toast.makeText(this@AgentDepositActivity, "Impossible de charger les favoris", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Etape 2 : montant cumulatif + USSD ----------

    private fun renderStep2() {
        amountCents = 0

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(48), dp(24), dp(32))
        sv.addView(col)
        setContentView(sv)

        col.addView(header("Recharger compte 1xBet", "Étape 2/2"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val cardLp = LinearLayout.LayoutParams(MATCH, WRAP)
        cardLp.setMargins(0, 0, 0, dp(20))
        card.layoutParams = cardLp

        val icon = t("✓", 16f, 0xFF2ECC71.toInt(), true)
        card.addView(icon)
        card.addView(spacerHoriz(10))

        val textCol = LinearLayout(this)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.addView(t("ID $verifiedPlayerId", 16f, Ui.TEXT, true))
        textCol.addView(t(verifiedPlayerName ?: "", 13f, Ui.TEXT2))
        card.addView(textCol)
        col.addView(card)

        col.addView(t("Saisissez le montant", 14f, Ui.TEXT2))
        col.addView(spacer(8))

        val amountDisplay = t("0 FCFA", 32f, Ui.TEXT, true)
        amountDisplay.gravity = Gravity.CENTER
        amountDisplay.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        col.addView(amountDisplay)
        col.addView(spacer(16))

        val quickRow = LinearLayout(this)
        quickRow.orientation = LinearLayout.HORIZONTAL
        val amounts = listOf(500L, 1000L, 2000L, 5000L, 10000L)
        for (amt in amounts) {
            val btn = Ui.button(this, if (amt >= 1000) "${amt / 1000}K" else amt.toString(), "secondary") {
                amountCents += amt
                amountDisplay.text = "${formatAmount(amountCents)} FCFA"
            }
            val btnLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            btnLp.setMargins(dp(4), 0, dp(4), 0)
            btn.layoutParams = btnLp
            quickRow.addView(btn)
        }
        col.addView(quickRow)
        col.addView(spacer(12))

        val resetButton = Ui.button(this, "Réinitialiser le montant", "secondary") {
            amountCents = 0
            amountDisplay.text = "0 FCFA"
        }
        col.addView(resetButton)
        col.addView(spacer(12))

        val messageText = t("", 13f, Ui.ERROR)
        col.addView(messageText)
        col.addView(spacer(8))

        val confirmButton = Ui.button(this, "Confirmer le dépôt") { }
        col.addView(confirmButton)

        confirmButton.setOnClickListener {
            if (amountCents <= 0) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Saisissez un montant"
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener

            busy = true
            confirmButton.isEnabled = false
            confirmButton.alpha = 0.5f
            messageText.setTextColor(Ui.TEXT2)
            messageText.text = "Création du dépôt…"

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                    val token = SessionStorage.getToken(this@AgentDepositActivity) ?: return@launch
                    val body = JSONObject()
                        .put("playerId", verifiedPlayerId)
                        .put("amount", amountCents)

                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/agents/deposits", token, body))
                    }

                    busy = false
                    renderStep3(response)
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

    // ---------- Etape 3 : USSD ----------

    private fun renderStep3(deposit: JSONObject) {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(48), dp(24), dp(32))
        sv.addView(col)
        setContentView(sv)

        val amount = deposit.optLong("amount", 0)
        val playerId = deposit.optString("playerId", "")
        val ussdCode = deposit.optString("ussdCode", "")
        val merchantName = deposit.optString("merchantName", "")

        col.addView(header("Payer pour confirmer", ""))
        col.addView(t("Dépôt de ${formatAmount(amount)} FCFA au ID $playerId", 15f, Ui.TEXT))
        col.addView(spacer(8))
        col.addView(t("Effectuez le paiement avec votre compte Orange Money", 13f, Ui.TEXT2))
        col.addView(spacer(24))

        val ussdCard = t(ussdCode, 22f, Ui.TEXT, true)
        ussdCard.gravity = Gravity.CENTER
        ussdCard.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        ussdCard.setPadding(dp(16), dp(20), dp(16), dp(20))
        val ussdLp = LinearLayout.LayoutParams(MATCH, WRAP)
        ussdLp.setMargins(0, 0, 0, dp(16))
        ussdCard.layoutParams = ussdLp
        ussdCard.setOnClickListener {
            try {
                val uri = Uri.parse("tel:" + Uri.encode(ussdCode))
                val intent = Intent(Intent.ACTION_DIAL, uri)
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir le composeur", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(ussdCard)

        col.addView(t("Marchand : $merchantName", 13f, Ui.TEXT2))
        col.addView(spacer(16))

        val copyButton = Ui.button(this, "Copier le code", "secondary") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("USSD", ussdCode))
            Toast.makeText(this, "Code copié", Toast.LENGTH_SHORT).show()
        }
        col.addView(copyButton)
        col.addView(spacer(12))

        val doneButton = Ui.button(this, "Terminer") { finish() }
        col.addView(doneButton)
    }

    private fun formatAmount(value: Long): String =
        value.toString().reversed().chunked(3).joinToString(" ").reversed()

    private fun spacer(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        return v
    }

    private fun spacerHoriz(widthDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(dp(widthDp), WRAP)
        return v
    }
}
