package com.kounadia.kndtigui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Point d'entree de l'espace Agent. Volontairement minimal pour l'instant :
 * identite + deconnexion. Les ecrans Depot/Retrait/Favoris/Historique/
 * Notifications seront ajoutes dans les prochaines etapes de la Phase 9,
 * sans toucher a LoginActivity ni a ManagerActivity.
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

    private fun goToLogin() {
        SessionStorage.clear(this)
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun render() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(28), dp(64), dp(28), dp(32))
        sv.addView(col)
        setContentView(sv)

        val displayName = SessionStorage.getDisplayName(this) ?: ""
        val title = Ui.text(this, displayName, 24f, Ui.TEXT, true)
        col.addView(title)

        val subtitle = Ui.text(this, "Espace Agent KND-Tigui", 14f, Ui.TEXT2)
        subtitle.setPadding(0, dp(4), 0, dp(40))
        col.addView(subtitle)

        val logoutButton = Ui.button(this, "Se déconnecter") { goToLogin() }
        col.addView(logoutButton)
    }
}
