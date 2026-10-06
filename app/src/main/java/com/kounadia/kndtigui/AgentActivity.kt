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
        val companyName = SessionStorage.getDisplayName(this) ?: ""
        identityCol.addView(Ui.text(this, companyName, 18f, Ui.TEXT, true))
        headerRow.addView(identityCol)

        val whatsappButton = Ui.text(this, "☎", 22f, Ui.TEXT)
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
            Toast.makeText(this, "Écran de retrait à venir", Toast.LENGTH_SHORT).show()
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
        col.addView(Ui.text(this, "Les opérations récentes apparaîtront ici.", 13f, Ui.TEXT2))

        loadSummary(depositValue, withdrawalValue, commissionValue)
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
