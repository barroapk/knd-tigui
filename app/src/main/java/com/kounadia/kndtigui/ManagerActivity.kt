package com.kounadia.kndtigui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ManagerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var statusLine: TextView? = null
    private var depositsContainer: LinearLayout? = null
    private var unmatchedContainer: LinearLayout? = null
    private var busy = false

    companion object {
        private const val POLL_INTERVAL_MS = 20000L
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (SessionStorage.isLoggedIn(this@ManagerActivity)) {
                loadWorkspace(silent = true)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(32), dp(16), dp(16))
        val scroll = ScrollView(this)
        scroll.addView(container)
        setContentView(scroll)
        showCurrentScreen()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        scope.cancel()
    }

    // ---------- ecrans ----------

    private fun showCurrentScreen() {
        if (SessionStorage.isLoggedIn(this)) showWorkspace() else showLogin(null)
    }

    private fun showLogin(message: String?) {
        container.removeAllViews()
        statusLine = null
        depositsContainer = null
        unmatchedContainer = null

        container.addView(title("KND-Tigui — Espace Manager"))
        container.addView(label("Connectez-vous avec votre compte Manager."))

        val emailInput = EditText(this)
        emailInput.hint = "Email"
        emailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        emailInput.setText(SessionStorage.getLastEmail(this) ?: "")
        container.addView(emailInput)

        val passwordInput = EditText(this)
        passwordInput.hint = "Mot de passe"
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        container.addView(passwordInput)

        val messageText = label(message ?: "")
        val loginButton = Button(this)
        loginButton.text = "Se connecter"
        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (email.isBlank() || password.isBlank()) {
                messageText.text = "Email et mot de passe requis"
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener

            busy = true
            loginButton.isEnabled = false
            messageText.text = "Connexion… (le serveur peut mettre jusqu'à 1 minute à se réveiller)"

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                    val body = JSONObject().put("email", email).put("password", password)
                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/auth/manager/login", null, body))
                    }
                    val manager = response.getJSONObject("manager")
                    SessionStorage.save(
                        this@ManagerActivity,
                        response.getString("accessToken"),
                        manager.getString("id"),
                        manager.getString("email"),
                        manager.getString("displayName"),
                        manager.getString("role"),
                    )
                    busy = false
                    showWorkspace()
                } catch (e: ApiException) {
                    busy = false
                    passwordInput.setText("")
                    loginButton.isEnabled = true
                    messageText.text = e.message
                } catch (e: Exception) {
                    busy = false
                    loginButton.isEnabled = true
                    messageText.text = "Serveur injoignable. Vérifiez la connexion et réessayez."
                }
            }
        }
        container.addView(loginButton)
        container.addView(messageText)
    }

    private fun showWorkspace() {
        container.removeAllViews()
        val name = SessionStorage.getDisplayName(this) ?: "?"
        val role = SessionStorage.getRole(this) ?: "?"

        container.addView(title("Bonjour $name"))
        container.addView(label("Rôle : $role"))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(button("Actualiser") { loadWorkspace(silent = false) }, weight)
        row.addView(button("Déconnexion") {
            SessionStorage.clear(this)
            showLogin(null)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)

        val status = label("")
        statusLine = status
        container.addView(status)

        container.addView(title("Dépôts à traiter", 16f))
        val deposits = LinearLayout(this)
        deposits.orientation = LinearLayout.VERTICAL
        depositsContainer = deposits
        container.addView(deposits)

        container.addView(title("Paiements à vérifier", 16f))
        container.addView(label("Paiements reçus sans dépôt correspondant. Ne rien créditer sans vérification."))
        val unmatched = LinearLayout(this)
        unmatched.orientation = LinearLayout.VERTICAL
        unmatchedContainer = unmatched
        container.addView(unmatched)

        loadWorkspace(silent = false)
    }

    // ---------- chargement et actions ----------

    private fun loadWorkspace(silent: Boolean) {
        if (busy) return
        val token = SessionStorage.getToken(this) ?: return
        busy = true
        if (!silent) setStatus("Chargement… (le serveur peut mettre jusqu'à 1 minute à se réveiller)")

        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val (deposits, unmatched) = withContext(Dispatchers.IO) {
                    val d = JSONArray(ApiClient.request(base, "GET", "/manager/deposits", token))
                    val u = JSONArray(ApiClient.request(base, "GET", "/manager/payments/unmatched", token))
                    Pair(d, u)
                }
                renderDeposits(deposits)
                renderUnmatched(unmatched)
                setStatus("Mis à jour à " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            } catch (e: ApiException) {
                handleApiError(e, false)
            } catch (e: Exception) {
                setStatus("Serveur injoignable. Nouvel essai automatique dans quelques secondes.")
            } finally {
                busy = false
            }
        }
    }

    private fun runAction(path: String, successMessage: String) {
        if (busy) return
        val token = SessionStorage.getToken(this) ?: return
        busy = true
        setStatus("Envoi…")

        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) { ApiClient.request(base, "POST", path, token) }
                busy = false
                Toast.makeText(this@ManagerActivity, successMessage, Toast.LENGTH_SHORT).show()
                loadWorkspace(silent = false)
            } catch (e: ApiException) {
                busy = false
                handleApiError(e, true)
                loadWorkspace(silent = true)
            } catch (e: Exception) {
                busy = false
                setStatus("Serveur injoignable. Réessayez.")
            }
        }
    }

    private fun handleApiError(e: ApiException, showDialog: Boolean) {
        if (e.httpCode == 401) {
            SessionStorage.clear(this)
            showLogin("Session expirée. Reconnectez-vous.")
            return
        }
        setStatus("⚠️ ${e.message}")
        if (showDialog) {
            AlertDialog.Builder(this)
                .setTitle("Action impossible")
                .setMessage(e.message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------- affichage des listes ----------

    private fun renderDeposits(array: JSONArray) {
        val target = depositsContainer ?: return
        target.removeAllViews()

        if (array.length() == 0) {
            target.addView(label("Aucun dépôt à traiter pour le moment."))
            return
        }

        val myId = SessionStorage.getManagerId(this) ?: ""
        val isAdmin = SessionStorage.getRole(this) == "ADMIN"

        for (i in 0 until array.length()) {
            val d = array.getJSONObject(i)
            val id = d.getString("id")
            val status = d.getString("status")
            val owner = str(d, "processedBy")
            val ownerId = str(d, "processedByManagerId")
            val isMine = ownerId.isNotEmpty() && ownerId == myId
            val totalCredit = d.optDouble("totalCredit")
            val bonusAmount = d.optDouble("bonusAmount")
            val playerId = d.getString("playerId")
            val playerName = d.getString("playerName")
            val payment = d.optJSONObject("payment")

            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(12), dp(12), dp(12))
            card.setBackgroundColor(0x1A808080)

            val text = StringBuilder()
            text.append(d.getString("reference")).append("\n")
            text.append("Joueur : $playerName\n")
            text.append("ID 1xBet : $playerId\n")
            text.append("Dépôt : ${fcfa(d.optDouble("amount"))}\n")
            if (bonusAmount > 0) text.append("Bonus : ${fcfa(bonusAmount)}\n")
            text.append("TOTAL À CRÉDITER : ${fcfa(totalCredit)}\n")
            if (payment != null) {
                text.append("Paiement Orange : ${payment.optString("transactionId")} de ${payment.optString("senderPhone")}\n")
            }
            text.append(
                when (status) {
                    "PAYMENT_CONFIRMED" -> "💰 Payé — à créditer"
                    "PROCESSING" ->
                        if (isMine) "✋ Pris en charge par vous"
                        else "🔒 Pris en charge par ${owner.ifEmpty { "un collègue" }}"
                    else -> status
                }
            )
            card.addView(label(text.toString()))

            if (status == "PAYMENT_CONFIRMED") {
                card.addView(button("PRENDRE EN CHARGE") {
                    runAction("/manager/deposits/$id/claim", "Dépôt pris en charge")
                })
            } else if (status == "PROCESSING") {
                if (isMine) {
                    card.addView(button("Copier l'ID 1xBet") { copyToClipboard("ID 1xBet", playerId) })
                    card.addView(button("Copier le montant à créditer") {
                        copyToClipboard("Montant", totalCredit.toLong().toString())
                    })
                    card.addView(button("CRÉDIT EFFECTUÉ") { confirmComplete(id, playerId, playerName, totalCredit) })
                    card.addView(button("Libérer") { confirmRelease(id, false) })
                } else if (isAdmin) {
                    card.addView(button("Libérer (admin)") { confirmRelease(id, true) })
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.setMargins(0, dp(8), 0, dp(8))
            target.addView(card, params)
        }
    }

    private fun renderUnmatched(array: JSONArray) {
        val target = unmatchedContainer ?: return
        target.removeAllViews()

        if (array.length() == 0) {
            target.addView(label("Aucun paiement à vérifier."))
            return
        }

        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i)
            val reasonText = when (str(p, "lateMatchReason")) {
                "after_cancel" -> "Reçu après l'annulation du dépôt"
                "after_expiry" -> "Reçu après l'expiration du dépôt"
                "ambiguous" -> "Plusieurs dépôts possibles"
                else -> "Aucun dépôt correspondant"
            }
            val linked = p.optJSONObject("linkedDeposit")

            val text = StringBuilder()
            text.append("${fcfa(p.optDouble("amount"))} de ${p.optString("senderPhone")}\n")
            text.append("Transaction : ${p.optString("transactionId")}\n")
            text.append("Reçu : ${shortTime(str(p, "receivedAt"))}\n")
            text.append(reasonText)
            if (linked != null) {
                text.append("\nDépôt lié : ${linked.optString("reference")} (${linked.optString("status")})")
            }

            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(12), dp(12), dp(12))
            card.setBackgroundColor(0x1AFF9800)
            card.addView(label(text.toString()))

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.setMargins(0, dp(8), 0, dp(8))
            target.addView(card, params)
        }
    }

    // ---------- dialogues et utilitaires ----------

    private fun confirmComplete(id: String, playerId: String, playerName: String, total: Double) {
        AlertDialog.Builder(this)
            .setTitle("Confirmer le crédit")
            .setMessage("Avez-vous bien crédité ${fcfa(total)} sur le compte 1xBet $playerId ($playerName) dans MobCash ?")
            .setPositiveButton("Oui, crédit effectué") { _, _ ->
                runAction("/manager/deposits/$id/complete", "Dépôt clôturé ✅")
            }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun confirmRelease(id: String, adminOverride: Boolean) {
        val message = if (adminOverride) {
            "Vérifiez dans MobCash que ce dépôt n'a PAS déjà été crédité avant de le libérer, sinon il pourrait être crédité deux fois. Libérer ce dépôt ?"
        } else {
            "Le dépôt retournera dans la file et un collègue pourra le prendre. Continuer ?"
        }
        AlertDialog.Builder(this)
            .setTitle("Libérer le dépôt")
            .setMessage(message)
            .setPositiveButton("Libérer") { _, _ ->
                runAction("/manager/deposits/$id/release", "Dépôt libéré")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copié", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(text: String) {
        statusLine?.text = text
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun fcfa(value: Double): String =
        String.format(Locale.US, "%,d", value.toLong()).replace(',', ' ') + " FCFA"

    private fun shortTime(iso: String): String =
        if (iso.length >= 16) iso.substring(0, 16).replace('T', ' ') else iso

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun title(text: String, size: Float = 20f): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = size
        view.setTypeface(null, Typeface.BOLD)
        view.setPadding(0, dp(12), 0, dp(6))
        return view
    }

    private fun label(text: String, size: Float = 14f): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = size
        view.setPadding(0, dp(4), 0, dp(4))
        return view
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val view = Button(this)
        view.text = text
        view.setOnClickListener { onClick() }
        return view
    }
}
