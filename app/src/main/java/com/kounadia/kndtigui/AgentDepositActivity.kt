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

        val idInput = field("ID joueur", true)
        idInput.inputType = InputType.TYPE_CLASS_NUMBER
        idInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        col.addView(idInput)

        val favButton = Ui.button(this, "Mes joueurs favoris", "secondary") {
            showFavoritesSheet(idInput)
        }
        col.addView(favButton)

        val messageText = t("", 13f, Ui.ERROR)
        messageText.setPadding(0, dp(8), 0, dp(8))
        col.addView(messageText)

        col.addView(spacer(8))

        val nextButton = Ui.button(this, "Suivant") { }
        col.addView(nextButton)

        col.addView(spacer(16))
        col.addView(Ui.numericKeypad(this, idInput, allowDecimal = false))

        nextButton.setOnClickListener {
            val playerId = idInput.text.toString().trim()

            if (playerId.isBlank()) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Saisissez un ID 1xBet"
                idInput.requestFocus()
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
                        JSONObject(
                            ApiClient.request(
                                base,
                                "GET",
                                "/player-verification/verify?playerId=$playerId",
                                null,
                                null
                            )
                        )
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
        val (sheet, content) = Ui.bottomSheet(this)
        content.addView(t("Mes joueurs", 18f, Ui.TEXT, true))
        content.addView(spacer(12))

        val listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        content.addView(listBox)

        content.addView(spacer(8))
        content.addView(Ui.button(this, "Ajouter un joueur", "secondary") {
            sheet.dismiss()
            showAddFavoriteSheet(idInput)
        })

        fun renderList() {
            listBox.removeAllViews()
            val favorites = FavoritesCache.load(this)

            if (favorites.length() == 0) {
                listBox.addView(t("Aucun joueur favori pour l'instant.", 13f, Ui.TEXT2))
                return
            }

            for (i in 0 until favorites.length()) {
                val fav = favorites.getJSONObject(i)
                val favId = fav.getString("playerId")
                val favName = fav.optString("playerName", "")
                val favRowId = fav.getString("id")

                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(8), 0, dp(8))

                val label = t("$favId · $favName", 14f, Ui.TEXT)
                label.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                label.setOnClickListener {
                    idInput.setText(favId)
                    sheet.dismiss()
                }
                row.addView(label)

                val removeIcon = t("✕", 16f, Ui.ERROR)
                removeIcon.setPadding(dp(12), 0, dp(4), 0)
                removeIcon.setOnClickListener {
                    // Suppression locale immediate, serveur synchronise ensuite.
                    FavoritesCache.remove(this, favRowId)
                    renderList()
                    scope.launch {
                        try {
                            val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                            val token = SessionStorage.getToken(this@AgentDepositActivity) ?: return@launch
                            withContext(Dispatchers.IO) {
                                ApiClient.request(base, "DELETE", "/agents/favorites/$favRowId", token, null)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                row.addView(removeIcon)

                listBox.addView(row)
            }
        }

        // Affichage instantane depuis le cache local.
        renderList()
        sheet.show()

        // Synchronisation serveur en arriere-plan.
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                val token = SessionStorage.getToken(this@AgentDepositActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/agents/favorites", token, null))
                }
                FavoritesCache.save(this@AgentDepositActivity, response)
                if (sheet.isShowing) renderList()
            } catch (_: Exception) {
                // Hors ligne ou serveur endormi : le cache local reste affiche.
            }
        }
    }

    private fun showAddFavoriteSheet(originIdInput: EditText) {
        val (sheet, content) = Ui.bottomSheet(this)
        content.addView(t("Ajouter un joueur favori", 18f, Ui.TEXT, true))
        content.addView(spacer(12))
        content.addView(t("ID 1xBet du joueur", 13f, Ui.TEXT2))
        content.addView(spacer(6))

        val newIdInput = field("ID joueur", true)
        content.addView(newIdInput)

        val messageText = t("", 12f, Ui.ERROR)
        messageText.setPadding(0, dp(8), 0, 0)
        content.addView(messageText)
        content.addView(spacer(12))

        val confirmButton = Ui.button(this, "Vérifier et ajouter") { }
        content.addView(confirmButton)

        confirmButton.setOnClickListener {
            val playerId = newIdInput.text.toString().trim()
            if (playerId.isBlank()) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Saisissez un ID 1xBet"
                return@setOnClickListener
            }

            confirmButton.isEnabled = false
            confirmButton.alpha = 0.5f
            messageText.setTextColor(Ui.TEXT2)
            messageText.text = "Vérification…"

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@AgentDepositActivity)
                    val token = SessionStorage.getToken(this@AgentDepositActivity) ?: return@launch

                    val verifyResponse = withContext(Dispatchers.IO) {
                        JSONObject(
                            ApiClient.request(
                                base,
                                "GET",
                                "/player-verification/verify?playerId=$playerId",
                                null,
                                null
                            )
                        )
                    }

                    val valid = verifyResponse.optBoolean("valid", false)
                    val playerName = verifyResponse.optString("playerName", "")

                    if (!valid || playerName.isBlank()) {
                        confirmButton.isEnabled = true
                        confirmButton.alpha = 1f
                        messageText.setTextColor(Ui.ERROR)
                        messageText.text = "Compte 1xBet introuvable ou non vérifié"
                        return@launch
                    }

                    val body = JSONObject()
                        .put("playerId", playerId)
                        .put("playerName", playerName)

                    val created = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/agents/favorites", token, body))
                    }
                    FavoritesCache.add(this@AgentDepositActivity, created)

                    originIdInput.setText(playerId)
                    sheet.dismiss()
                    Toast.makeText(this@AgentDepositActivity, "$playerName ajouté aux favoris", Toast.LENGTH_SHORT).show()
                } catch (e: ApiException) {
                    confirmButton.isEnabled = true
                    confirmButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = e.message
                } catch (e: Exception) {
                    confirmButton.isEnabled = true
                    confirmButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = "Connexion impossible. Réessayez."
                }
            }
        }

        sheet.show()
    }

    // ---------- Etape 2 : montant cumulatif + USSD ----------

    private fun renderStep2() {
        amountCents = 0

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(28), dp(24), dp(12))
        sv.addView(col)
        setContentView(sv)

        col.addView(header("Recharger compte 1xBet", "Étape 2/2"))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        card.setPadding(dp(14), dp(8), dp(14), dp(8))
        val cardLp = LinearLayout.LayoutParams(MATCH, WRAP)
        cardLp.setMargins(0, 0, 0, dp(8))
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


        val amountInput = field("Montant", true)
        amountInput.textSize = 24f
        amountInput.gravity = Gravity.CENTER
        amountInput.inputType = InputType.TYPE_CLASS_NUMBER
        val amountRow = LinearLayout(this)
        amountRow.orientation = LinearLayout.HORIZONTAL
        amountRow.gravity = Gravity.CENTER_VERTICAL
        amountInput.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        amountRow.addView(amountInput)
        val fcfaLabel = t("FCFA", 16f, Ui.TEXT2, true)
        fcfaLabel.setPadding(dp(12), 0, dp(4), 0)
        amountRow.addView(fcfaLabel)
        col.addView(amountRow)

        fun syncFromInput() {
            amountCents = amountInput.text.toString().toLongOrNull() ?: 0L
        }

        fun setAmount(value: Long) {
            amountCents = value
            amountInput.setText(if (value > 0) value.toString() else "")
            amountInput.setSelection(amountInput.text.length)
        }

        amountInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(editable: android.text.Editable?) { syncFromInput() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        col.addView(spacer(6))

        val quickRow = LinearLayout(this)
        quickRow.orientation = LinearLayout.HORIZONTAL
        val amounts = listOf(500L, 1000L, 2000L, 5000L, 10000L)
        for (amt in amounts) {
            val label = if (amt >= 1000) "${amt / 1000}K" else amt.toString()
            val btn = t(label, 13f, Ui.TEXT, true)
            btn.gravity = Gravity.CENTER
            btn.background = Ui.rounded(this, Ui.ELEVATED, 12, Ui.BORDER)
            btn.setPadding(0, dp(10), 0, dp(10))
            val btnLp = LinearLayout.LayoutParams(0, WRAP, 1f)
            btnLp.setMargins(dp(3), 0, dp(3), 0)
            btn.layoutParams = btnLp
            btn.setOnClickListener { setAmount(amountCents + amt) }
            quickRow.addView(btn)
        }
        col.addView(quickRow)

        val messageText = t("", 13f, Ui.ERROR)
        col.addView(messageText)

        val confirmButton = Ui.button(this, "Confirmer le dépôt") { }
        col.addView(confirmButton)
        col.addView(spacer(6))
        col.addView(Ui.numericKeypad(this, amountInput, allowDecimal = false) { syncFromInput() })

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
        val agentPhone = SessionStorage.getIdentifier(this) ?: ""
        col.addView(t("Effectuez le paiement avec votre compte Orange Money $agentPhone", 13f, Ui.TEXT2))
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
