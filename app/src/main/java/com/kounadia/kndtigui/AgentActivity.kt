package com.kounadia.kndtigui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * Dashboard agent : identite (entreprise + code PDV), service client,
 * resume du mois en cours (depots/retraits/commission), acces
 * Deposer/Retirer, et placeholder historique (construit a l'etape
 * suivante de la Phase 9).
 */
class AgentActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun dp(value: Int): Int = Ui.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionStorage.isLoggedIn(this) || SessionStorage.getRole(this) != "AGENT") {
            goToLogin()
            return
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (SessionStorage.isLoggedIn(this) && SessionStorage.getRole(this) == "AGENT") {
            render()
        }
    }

    private fun goToLogin() {
        SessionStorage.clear(this)
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun spacer(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        return v
    }

    private fun formatAmount(value: Long): String =
        value.toString().reversed().chunked(3).joinToString(" ").reversed()

    private fun render() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(48), dp(24), dp(32))
        sv.addView(col)
        setContentView(sv)

        // ---------- En-tete ----------
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val identityCol = LinearLayout(this)
        identityCol.orientation = LinearLayout.VERTICAL
        val identityLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        identityCol.layoutParams = identityLp
        val companyName = SessionStorage.getCompanyName(this) ?: (SessionStorage.getDisplayName(this) ?: "")
        val agentCode = SessionStorage.getAgentCode(this) ?: ""
        identityCol.addView(Ui.text(this, companyName, 18f, Ui.TEXT, true))
        if (agentCode.isNotBlank()) {
            identityCol.addView(Ui.text(this, agentCode, 12f, Ui.TEXT2))
        }
        headerRow.addView(identityCol)

        val whatsappButton = Ui.text(this, "WhatsApp", 13f, Ui.TEXT)
        whatsappButton.setOnClickListener {
            try {
                val uri = Uri.parse("https://wa.me/22655337782")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir WhatsApp", Toast.LENGTH_SHORT).show()
            }
        }
        headerRow.addView(whatsappButton)
        col.addView(headerRow)
        col.addView(spacer(24))

        // ---------- Carte resume (placeholder pendant le chargement) ----------
        val summaryCard = LinearLayout(this)
        summaryCard.orientation = LinearLayout.VERTICAL
        summaryCard.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        summaryCard.setPadding(dp(18), dp(18), dp(18), dp(18))
        val summaryLp = LinearLayout.LayoutParams(MATCH, WRAP)
        summaryLp.setMargins(0, 0, 0, dp(20))
        summaryCard.layoutParams = summaryLp

        val summaryTitle = Ui.text(this, "Ce mois-ci", 13f, Ui.TEXT2)
        summaryCard.addView(summaryTitle)
        summaryCard.addView(spacer(8))

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL

        val depositCol = LinearLayout(this)
        depositCol.orientation = LinearLayout.VERTICAL
        depositCol.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        depositCol.addView(Ui.text(this, "Dépôts", 12f, Ui.TEXT2))
        val depositValue = Ui.text(this, "—", 20f, 0xFF2ECC71.toInt(), true)
        depositCol.addView(depositValue)
        row1.addView(depositCol)

        val withdrawalCol = LinearLayout(this)
        withdrawalCol.orientation = LinearLayout.VERTICAL
        withdrawalCol.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        withdrawalCol.addView(Ui.text(this, "Retraits", 12f, Ui.TEXT2))
        val withdrawalValue = Ui.text(this, "—", 20f, 0xFFFF5C5C.toInt(), true)
        withdrawalCol.addView(withdrawalValue)
        row1.addView(withdrawalCol)

        val commissionCol = LinearLayout(this)
        commissionCol.orientation = LinearLayout.VERTICAL
        commissionCol.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        commissionCol.addView(Ui.text(this, "Commission", 12f, Ui.TEXT2))
        val commissionValue = Ui.text(this, "—", 20f, Ui.TEXT, true)
        commissionCol.addView(commissionValue)
        row1.addView(commissionCol)

        summaryCard.addView(row1)
        col.addView(summaryCard)

        // ---------- Boutons Deposer / Retirer ----------
        val actionsRow = LinearLayout(this)
        actionsRow.orientation = LinearLayout.HORIZONTAL

        val depositButton = Ui.button(this, "⬇ Déposer") {
            startActivity(Intent(this, AgentDepositActivity::class.java))
        }
        val depositLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        depositLp.setMargins(0, 0, dp(8), 0)
        depositButton.layoutParams = depositLp
        actionsRow.addView(depositButton)

        val withdrawButton = Ui.button(this, "⬆ Retirer", "danger") {
            startActivity(Intent(this, AgentWithdrawActivity::class.java))
        }
        val withdrawLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        withdrawLp.setMargins(dp(8), 0, 0, 0)
        withdrawButton.layoutParams = withdrawLp
        actionsRow.addView(withdrawButton)

        col.addView(actionsRow)
        col.addView(spacer(28))

        // ---------- Historique (placeholder) ----------
        col.addView(Ui.text(this, "Historique", 16f, Ui.TEXT, true))
        col.addView(spacer(12))
        val historyBox = LinearLayout(this)
        historyBox.orientation = LinearLayout.VERTICAL
        col.addView(historyBox)

        showHistory(historyBox, AgentHistoryCache.load(this))
        loadHistory(historyBox)
        loadSummary(depositValue, withdrawalValue, commissionValue)
    }

    // ---------- historique ----------

    private fun loadHistory(box: LinearLayout) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch

                val deposits = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/agents/deposits", token, null))
                }
                val withdrawals = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/agents/withdrawals", token, null))
                }

                val items = ArrayList<JSONObject>()
                for (i in 0 until deposits.length()) {
                    items.add(deposits.getJSONObject(i).put("kind", "DEPOSIT"))
                }
                for (i in 0 until withdrawals.length()) {
                    items.add(withdrawals.getJSONObject(i).put("kind", "WITHDRAWAL"))
                }
                items.sortByDescending { it.optString("createdAt") }

                val merged = JSONArray()
                for (item in items.take(30)) merged.put(item)

                AgentHistoryCache.save(this@AgentActivity, merged)
                showHistory(box, merged)
            } catch (_: Exception) {
                // Hors ligne ou serveur endormi : le cache local reste affiche.
            }
        }
    }

    private fun showHistory(box: LinearLayout, items: JSONArray) {
        box.removeAllViews()

        if (items.length() == 0) {
            box.addView(Ui.text(this, "Aucune opération pour l'instant.", 13f, Ui.TEXT2))
            return
        }

        for (i in 0 until items.length()) {
            box.addView(historyRow(items.getJSONObject(i)))
        }
    }

    private fun withdrawalStatus(status: String): Pair<String, Int> = when (status) {
        "CREATED" -> Pair("En attente", Ui.WARNING)
        "PROCESSING" -> Pair("En cours", Ui.PRIMARY)
        "COMPLETED" -> Pair("Payé", Ui.SUCCESS)
        "FAILED" -> Pair("Échec", Ui.ERROR)
        "CANCELLED" -> Pair("Annulé", Ui.TEXT2)
        else -> Pair(status, Ui.TEXT2)
    }

    private fun statusOf(item: JSONObject): Pair<String, Int> {
        val status = item.optString("status")
        return if (item.optString("kind") == "WITHDRAWAL") withdrawalStatus(status) else Ui.statusInfo(status)
    }

    private fun shortDate(iso: String): String =
        if (iso.length >= 16) "${iso.substring(8, 10)}/${iso.substring(5, 7)} ${iso.substring(11, 16)}" else iso

    private fun historyRow(item: JSONObject): View {
        val isDeposit = item.optString("kind") == "DEPOSIT"
        val amount = item.optLong("amount", 0)
        val (label, color) = statusOf(item)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, 0, 0, dp(8))
        row.layoutParams = lp

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val title = if (isDeposit) "Dépôt · ID " + item.optString("playerId") else "Retrait"
        left.addView(Ui.text(this, title, 14f, Ui.TEXT, true))
        left.addView(Ui.text(this, shortDate(item.optString("createdAt")), 12f, Ui.TEXT2))
        row.addView(left)

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        val sign = if (isDeposit) "+" else "−"
        right.addView(Ui.text(this, "$sign${formatAmount(amount)} F", 15f, if (isDeposit) Ui.SUCCESS else Ui.ERROR, true))
        right.addView(Ui.pill(this, label, color))
        row.addView(right)

        row.setOnClickListener { showHistorySheet(item) }
        return row
    }

    private fun showHistorySheet(item: JSONObject) {
        val isDeposit = item.optString("kind") == "DEPOSIT"
        val id = item.optString("id")
        val status = item.optString("status")
        val amount = item.optLong("amount", 0)
        val (label, _) = statusOf(item)
        val (sheet, content) = Ui.bottomSheet(this)

        val title = Ui.text(this, if (isDeposit) "Dépôt" else "Retrait", 20f, Ui.TEXT, true)
        title.gravity = Gravity.CENTER
        title.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        content.addView(title)

        val rows = ArrayList<Pair<String, String>>()
        rows.add("Référence" to item.optString("reference"))
        rows.add("Montant" to "${formatAmount(amount)} FCFA")
        rows.add("Statut" to label)
        if (isDeposit) {
            rows.add("Compte 1xBet" to item.optString("playerId"))
            rows.add("Joueur" to item.optString("playerName"))
        } else {
            rows.add("Envoyé sur" to item.optString("agentOrangeMoneyPhone"))
            if (status == "FAILED") rows.add("Motif" to item.optString("failureReason"))
        }
        rows.add("Date" to shortDate(item.optString("createdAt")))
        content.addView(Ui.section(this, "Détails", rows))

        val cancelPath: String? = when {
            !isDeposit && status == "CREATED" -> "/agents/withdrawals/$id/cancel"
            isDeposit && (status == "PAYMENT_PENDING" || status == "PAYMENT_LATE") -> "/deposits/$id/cancel"
            else -> null
        }

        if (cancelPath != null) {
            content.addView(Ui.button(this, "Annuler cette opération", "danger") {
                sheet.dismiss()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Annuler l'opération ?")
                    .setMessage(
                        if (isDeposit) "N'annulez pas si vous avez déjà effectué le paiement Orange Money."
                        else "Cette demande de retrait sera annulée."
                    )
                    .setPositiveButton("Annuler l'opération") { _, _ -> cancelOperation(cancelPath) }
                    .setNegativeButton("Retour", null)
                    .show()
            })
        }

        content.addView(Ui.button(this, "Fermer", "secondary") { sheet.dismiss() })
        sheet.show()
    }

    private fun cancelOperation(path: String) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch
                withContext(Dispatchers.IO) {
                    ApiClient.request(base, "POST", path, token, JSONObject())
                }
                Toast.makeText(this@AgentActivity, "Opération annulée", Toast.LENGTH_SHORT).show()
                render()
            } catch (e: ApiException) {
                Toast.makeText(this@AgentActivity, e.message, Toast.LENGTH_LONG).show()
                render()
            } catch (e: Exception) {
                Toast.makeText(this@AgentActivity, "Connexion impossible. Réessayez.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSummary(
        depositValue: android.widget.TextView,
        withdrawalValue: android.widget.TextView,
        commissionValue: android.widget.TextView,
    ) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "GET", "/agents/commissions", token, null))
                }

                val depositVolume = response.optLong("depositVolume", 0)
                val withdrawalVolume = response.optLong("withdrawalVolume", 0)
                val totalCommission = response.optLong("totalCommission", 0)

                depositValue.text = "${formatAmount(depositVolume)} F"
                withdrawalValue.text = "${formatAmount(withdrawalVolume)} F"
                commissionValue.text = "${formatAmount(totalCommission)} F"
            } catch (_: Exception) {
                // Echec silencieux : le dashboard reste utilisable sans le resume.
            }
        }
    }
}
