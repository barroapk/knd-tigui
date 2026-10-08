package com.kounadia.kndtigui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
private const val HISTORY_LIMIT = 15

/**
 * Espace agent : en-tete, onglets fixes en bas (Accueil / Notifications /
 * Commission). Sur l'accueil, seule la liste d'historique defile ; la carte
 * des montants et les boutons restent fixes. Tout s'affiche d'abord depuis
 * le cache local, puis se met a jour depuis le serveur.
 */
class AgentActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentTab = 0
    private var firstResume = true

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
        if (firstResume) {
            firstResume = false
            return
        }
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

    private fun shortDate(iso: String): String =
        if (iso.length >= 16) "${iso.substring(8, 10)}/${iso.substring(5, 7)} ${iso.substring(11, 16)}" else iso

    // ---------- cache local (notifications, commission) ----------

    private fun cacheRead(name: String): String? =
        getSharedPreferences("knd_tigui_agent_misc", MODE_PRIVATE)
            .getString(name + "_" + (SessionStorage.getUserId(this) ?: "none"), null)

    private fun cacheWrite(name: String, value: String) {
        getSharedPreferences("knd_tigui_agent_misc", MODE_PRIVATE).edit()
            .putString(name + "_" + (SessionStorage.getUserId(this) ?: "none"), value)
            .apply()
    }

    // ---------- structure de l'ecran ----------

    private fun render() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), 0)
        setContentView(root, ViewGroup.LayoutParams(MATCH, MATCH))

        root.addView(buildHeader())
        root.addView(spacer(14))

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        scroll.isVerticalScrollBarEnabled = false
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)

        when (currentTab) {
            0 -> {
                buildHomeFixedPart(root)
                root.addView(scroll)
                showHistory(box, AgentHistoryCache.load(this))
                loadHistory(box)
            }
            1 -> {
                root.addView(Ui.text(this, "Notifications", 16f, Ui.TEXT, true))
                root.addView(spacer(10))
                root.addView(scroll)
                val cached = cacheRead("notifications")
                showNotifications(box, if (cached != null) JSONArray(cached) else null)
                loadNotifications(box)
            }
            else -> {
                root.addView(Ui.text(this, "Commission", 16f, Ui.TEXT, true))
                root.addView(spacer(10))
                root.addView(scroll)
                val cached = cacheRead("commission_history")
                if (cached != null) showCommission(box, JSONObject(cached))
                loadCommission(box)
            }
        }

        root.addView(buildTabBar())
    }

    private fun buildHeader(): LinearLayout {
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val identityCol = LinearLayout(this)
        identityCol.orientation = LinearLayout.VERTICAL
        identityCol.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val companyName = SessionStorage.getCompanyName(this) ?: (SessionStorage.getDisplayName(this) ?: "")
        val agentCode = SessionStorage.getAgentCode(this) ?: ""
        identityCol.addView(Ui.text(this, companyName, 18f, Ui.TEXT, true))
        if (agentCode.isNotBlank()) {
            identityCol.addView(Ui.text(this, agentCode, 12f, Ui.TEXT2))
        }
        headerRow.addView(identityCol)

        val whatsappButton = Ui.text(this, "WhatsApp", 13f, Ui.TEXT)
        whatsappButton.setPadding(dp(12), dp(8), dp(4), dp(8))
        whatsappButton.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/22655337782")))
            } catch (_: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir WhatsApp", Toast.LENGTH_SHORT).show()
            }
        }
        headerRow.addView(whatsappButton)
        return headerRow
    }

    /** Barre flottante : fond et marges distincts des boutons systeme du telephone. */
    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = Ui.rounded(this, Ui.ELEVATED, 22, Ui.BORDER)
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(dp(4), dp(10), dp(4), dp(18))
        bar.layoutParams = lp

        val labels = listOf("Accueil", "Notifications", "Commission")
        labels.forEachIndexed { index, label ->
            val selected = index == currentTab
            val tab = Ui.text(this, label, 13f, if (selected) 0xFFFFFFFF.toInt() else Ui.TEXT2, true)
            tab.gravity = Gravity.CENTER
            tab.setPadding(0, dp(12), 0, dp(12))
            if (selected) tab.background = Ui.rounded(this, Ui.PRIMARY, 16)
            tab.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            tab.setOnClickListener {
                if (currentTab != index) {
                    currentTab = index
                    render()
                }
            }
            bar.addView(tab)
        }
        return bar
    }

    // ---------- onglet Accueil ----------

    private fun buildHomeFixedPart(root: LinearLayout) {
        val summaryCard = LinearLayout(this)
        summaryCard.orientation = LinearLayout.VERTICAL
        summaryCard.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        summaryCard.setPadding(dp(18), dp(14), dp(18), dp(14))
        summaryCard.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

        summaryCard.addView(Ui.text(this, "Ce mois-ci", 13f, Ui.TEXT2))
        summaryCard.addView(spacer(6))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun column(title: String, color: Int): TextView {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            c.addView(Ui.text(this, title, 12f, Ui.TEXT2))
            val value = Ui.text(this, "—", 18f, color, true)
            c.addView(value)
            row.addView(c)
            return value
        }

        val depositValue = column("Dépôts", 0xFF2ECC71.toInt())
        val withdrawalValue = column("Retraits", 0xFFFF5C5C.toInt())
        val commissionValue = column("Commission", Ui.TEXT)
        summaryCard.addView(row)
        root.addView(summaryCard)

        val actionsRow = LinearLayout(this)
        actionsRow.orientation = LinearLayout.HORIZONTAL

        val depositButton = Ui.button(this, "⬇ Déposer") {
            startActivity(Intent(this, AgentDepositActivity::class.java))
        }
        val depositLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        depositLp.setMargins(0, dp(12), dp(6), 0)
        depositButton.layoutParams = depositLp
        actionsRow.addView(depositButton)

        val withdrawButton = Ui.button(this, "⬆ Retirer", "danger") {
            startActivity(Intent(this, AgentWithdrawActivity::class.java))
        }
        val withdrawLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        withdrawLp.setMargins(dp(6), dp(12), 0, 0)
        withdrawButton.layoutParams = withdrawLp
        actionsRow.addView(withdrawButton)
        root.addView(actionsRow)

        root.addView(spacer(16))
        root.addView(Ui.text(this, "Historique · $HISTORY_LIMIT dernières opérations", 15f, Ui.TEXT, true))
        root.addView(spacer(8))

        val cached = cacheRead("summary")
        if (cached != null) applySummary(JSONObject(cached), depositValue, withdrawalValue, commissionValue)
        loadSummary(depositValue, withdrawalValue, commissionValue)
    }

    private fun applySummary(o: JSONObject, d: TextView, w: TextView, c: TextView) {
        d.text = "${formatAmount(o.optLong("depositVolume", 0))} F"
        w.text = "${formatAmount(o.optLong("withdrawalVolume", 0))} F"
        c.text = "${formatAmount(o.optLong("totalCommission", 0))} F"
    }

    private fun loadSummary(d: TextView, w: TextView, c: TextView) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "GET", "/agents/commissions", token, null))
                }
                cacheWrite("summary", response.toString())
                applySummary(response, d, w, c)
            } catch (_: Exception) {
                // Echec silencieux : le dernier resume en cache reste affiche.
            }
        }
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
                for (item in items.take(HISTORY_LIMIT)) merged.put(item)

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

        val count = minOf(items.length(), HISTORY_LIMIT)
        for (i in 0 until count) {
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

    // ---------- onglet Notifications ----------

    private fun loadNotifications(box: LinearLayout) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/agents/notifications", token, null))
                }
                cacheWrite("notifications", response.toString())
                showNotifications(box, response)
            } catch (_: Exception) {
                // Le cache local reste affiche.
            }
        }
    }

    private fun showNotifications(box: LinearLayout, items: JSONArray?) {
        box.removeAllViews()

        if (items == null) {
            box.addView(Ui.text(this, "Chargement…", 13f, Ui.TEXT2))
            return
        }
        if (items.length() == 0) {
            box.addView(Ui.text(this, "Aucune information pour l'instant.", 13f, Ui.TEXT2))
            return
        }

        for (i in 0 until items.length()) {
            val n = items.getJSONObject(i)
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(14), dp(16), dp(14))
            card.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, 0, 0, dp(8))
            card.layoutParams = lp

            card.addView(Ui.text(this, n.optString("title"), 14f, Ui.TEXT, true))
            card.addView(spacer(4))
            card.addView(Ui.text(this, n.optString("message"), 13f, Ui.TEXT2))
            card.addView(spacer(6))
            card.addView(Ui.text(this, shortDate(n.optString("createdAt")), 11f, Ui.TEXT2))
            box.addView(card)
        }
    }

    // ---------- onglet Commission ----------

    private fun loadCommission(box: LinearLayout) {
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@AgentActivity)
                val token = SessionStorage.getToken(this@AgentActivity) ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "GET", "/agents/commissions/history", token, null))
                }
                cacheWrite("commission_history", response.toString())
                showCommission(box, response)
            } catch (_: Exception) {
                // Le cache local reste affiche.
            }
        }
    }

    private fun monthLabel(iso: String): String {
        val names = listOf(
            "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
            "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
        )
        if (iso.length < 7) return iso
        val m = iso.substring(5, 7).toIntOrNull() ?: return iso
        return names.getOrElse(m - 1) { iso } + " " + iso.substring(0, 4)
    }

    private fun showCommission(box: LinearLayout, o: JSONObject) {
        box.removeAllViews()
        val months = o.optJSONArray("months") ?: return

        var shown = 0
        for (i in 0 until months.length()) {
            val m = months.getJSONObject(i)
            val deposits = m.optLong("depositVolume", 0)
            val withdrawals = m.optLong("withdrawalVolume", 0)
            val total = m.optLong("totalCommission", 0)
            // Les mois anciens sans aucune activite ne sont pas affiches.
            if (i > 0 && deposits == 0L && withdrawals == 0L && total == 0L) continue

            val status = m.optString("status")
            val statusText = when (status) {
                "PAID" -> {
                    val paidAt = if (m.isNull("paidAt")) "" else m.optString("paidAt")
                    "Payé le " + shortDate(paidAt).substringBefore(' ')
                }
                "IN_PROGRESS" -> "Mois en cours"
                else -> "En attente de paiement"
            }

            box.addView(Ui.section(this, monthLabel(m.optString("periodStart")), listOf(
                "Dépôts réussis" to "${formatAmount(deposits)} FCFA",
                "Retraits payés" to "${formatAmount(withdrawals)} FCFA",
                "Commission" to "${formatAmount(total)} FCFA",
                "Statut" to statusText,
            )))
            shown++
        }

        if (shown == 0) {
            box.addView(Ui.text(this, "Aucune activité pour l'instant.", 13f, Ui.TEXT2))
        }

        val note = Ui.text(
            this,
            "Seules les opérations terminées comptent. La commission d'un mois est payée entre le 3 et le 5 du mois suivant.",
            12f, Ui.TEXT2,
        )
        note.setPadding(0, dp(14), 0, 0)
        box.addView(note)
    }
}
