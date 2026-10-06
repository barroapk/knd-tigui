package com.kounadia.kndtigui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
 * Point d'entree unique de l'application : un seul formulaire, aucun choix
 * de role visible. Le backend (POST /auth/login) determine si l'identifiant
 * correspond a un agent ou a un manager/admin, et cette activite route
 * ensuite vers l'ecran correspondant sans jamais reveler l'existence des
 * autres categories de comptes.
 */
class LoginActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (SessionStorage.isLoggedIn(this)) {
            routeToRoleActivity(SessionStorage.getRole(this))
            return
        }

        showLoginForm()
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun t(value: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(this, value, size, color, bold)

    private fun field(hint: String, password: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Ui.TEXT2)
        e.setTextColor(Ui.TEXT)
        e.textSize = 15f
        e.isSingleLine = true
        e.inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        e.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, 0, 0, dp(12))
        e.layoutParams = lp
        return e
    }

    private fun showLoginForm() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(28), dp(96), dp(28), dp(32))
        sv.addView(col)
        setContentView(sv)

        col.addView(t("KND-Tigui", 34f, Ui.TEXT, true))
        val sub = t("Connexion", 14f, Ui.TEXT2)
        sub.setPadding(0, dp(4), 0, dp(40))
        col.addView(sub)

        val identifierInput = field("Identifiant", false)
        identifierInput.setText(SessionStorage.getIdentifier(this) ?: "")
        col.addView(identifierInput)

        val passwordInput = field("Mot de passe", true)
        col.addView(passwordInput)

        val messageText = t("", 13f, Ui.ERROR)
        messageText.setPadding(0, dp(12), 0, 0)
        col.addView(messageText)

        val loginButton = Ui.button(this, "Se connecter") { }
        col.addView(loginButton)

        loginButton.setOnClickListener {
            val identifier = identifierInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (identifier.isBlank() || password.isBlank()) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Identifiant et mot de passe requis"
                return@setOnClickListener
            }
            if (busy) return@setOnClickListener

            busy = true
            loginButton.isEnabled = false
            loginButton.alpha = 0.5f
            messageText.setTextColor(Ui.TEXT2)
            messageText.text = "Connexion… le serveur peut mettre jusqu'à 1 minute à se réveiller."

            scope.launch {
                try {
                    val base = ConfigStorage.getApiBaseUrl(this@LoginActivity)
                    val requestBody = JSONObject()
                        .put("identifier", identifier)
                        .put("password", password)

                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/auth/login", null, requestBody))
                    }

                    val role = response.getString("role")
                    val user = response.getJSONObject("user")

                    val displayName = when (role) {
                        "AGENT" -> "${user.optString("firstName")} ${user.optString("lastName")}".trim()
                        else -> user.optString("displayName")
                    }

                    SessionStorage.save(
                        this@LoginActivity,
                        response.getString("accessToken"),
                        user.getString("id"),
                        identifier,
                        displayName,
                        role,
                    )

                    busy = false
                    routeToRoleActivity(role)
                } catch (e: ApiException) {
                    busy = false
                    loginButton.isEnabled = true
                    loginButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = e.message
                } catch (e: Exception) {
                    busy = false
                    loginButton.isEnabled = true
                    loginButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = "Connexion impossible. Vérifiez votre connexion internet."
                }
            }
        }
    }

    private fun routeToRoleActivity(role: String?) {
        val target = if (role == "AGENT") {
            Intent(this, AgentActivity::class.java)
        } else {
            Intent(this, ManagerActivity::class.java)
        }
        target.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(target)
        finish()
    }
}
