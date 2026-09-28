package com.kounadia.kndtigui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class ManagerActivity : AppCompatActivity() {

    private enum class Tab(val label: String, val iconRes: Int) {
        HOME("Accueil", R.drawable.ic_home),
        QUEUE("À traiter", R.drawable.ic_inbox),
        HISTORY("Historique", R.drawable.ic_history),
        PAYMENTS("Paiements", R.drawable.ic_alert),
        ADMIN("Admin", R.drawable.ic_users),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private var navBar: LinearLayout? = null
    private var navRow: LinearLayout? = null
    private var currentScroll: ScrollView? = null
    private var currentTab = Tab.HOME
    private var deposits = JSONArray()
    private var unmatched = JSONArray()
    private var history: JSONObject? = null
    private var managers = JSONArray()
    private var managersLoaded = false
    private var managersLoading = false
    private var statusMessage = ""
    private var busy = false

    companion object {
        private const val POLL_INTERVAL_MS = 20000L
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (SessionStorage.isLoggedIn(this@ManagerActivity) && navBar != null) {
                loadData(silent = true)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        if (SessionStorage.isLoggedIn(this)) showApp() else showLogin(null)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        if (SessionStorage.isLoggedIn(this) && navBar != null) {
            loadData(silent = true)
        }
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

    // ---------- petits utilitaires ----------

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun t(value: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(this, value, size, color, bold)

    private fun centered(value: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = t(value, size, color, bold)
        v.gravity = Gravity.CENTER
        v.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        return v
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun fcfa(value: Double): String =
        String.format(Locale.US, "%,d", value.toLong()).replace(',', ' ') + " FCFA"

    private fun shortDate(iso: String): String =
        if (iso.length >= 16) "${iso.substring(8, 10)}/${iso.substring(5, 7)} ${iso.substring(11, 16)}" else iso

    private fun longDate(iso: String): String =
        if (iso.length >= 16) "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)} ${iso.substring(11, 16)}" else iso

    private fun dateOf(d: JSONObject): String {
        val processed = str(d, "processedAt")
        return if (processed.isNotEmpty()) processed else str(d, "createdAt")
    }

    /** Provisoire : affiche la partie avant @ de l'email, mise en forme, en attendant les vrais noms d'utilisateur. */
    private fun who(raw: String): String {
        if (raw.isBlank()) return "—"
        return raw.substringBefore('@')
            .split('.', '_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copié", Toast.LENGTH_SHORT).show()
    }

    private fun spacer(heightDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        return v
    }

    // ---------- connexion ----------

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
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        e.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, 0, 0, dp(12))
        e.layoutParams = lp
        return e
    }

    private fun showLogin(message: String?) {
        navBar?.let { root.removeView(it) }
        navBar = null
        navRow = null
        currentScroll = null
        body.removeAllViews()

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(28), dp(96), dp(28), dp(32))
        sv.addView(col)

        col.addView(t("KND-Tigui", 34f, Ui.TEXT, true))
        val sub = t("Centre de gestion des opérations", 14f, Ui.TEXT2)
        sub.setPadding(0, dp(4), 0, dp(40))
        col.addView(sub)

        val emailInput = field("Adresse email", false)
        emailInput.setText(SessionStorage.getLastEmail(this) ?: "")
        col.addView(emailInput)

        val passwordInput = field("Mot de passe", true)
        col.addView(passwordInput)

        val messageText = t(message ?: "", 13f, Ui.ERROR)
        messageText.setPadding(0, dp(12), 0, 0)

        val loginButton = Ui.button(this, "Se connecter") { }
        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (email.isBlank() || password.isBlank()) {
                messageText.setTextColor(Ui.ERROR)
                messageText.text = "Email et mot de passe requis"
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
                    val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                    val requestBody = JSONObject().put("email", email).put("password", password)
                    val response = withContext(Dispatchers.IO) {
                        JSONObject(ApiClient.request(base, "POST", "/auth/manager/login", null, requestBody))
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
                    showApp()
                } catch (e: ApiException) {
                    busy = false
                    passwordInput.setText("")
                    loginButton.isEnabled = true
                    loginButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = e.message
                } catch (e: Exception) {
                    busy = false
                    loginButton.isEnabled = true
                    loginButton.alpha = 1f
                    messageText.setTextColor(Ui.ERROR)
                    messageText.text = "Serveur injoignable. Vérifiez la connexion et réessayez."
                }
            }
        }
        col.addView(loginButton)
        col.addView(messageText)

        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------- application ----------

    private fun showApp() {
        currentTab = Tab.HOME
        deposits = JSONArray()
        unmatched = JSONArray()
        history = null
        statusMessage = ""
        buildNav()
        renderTab()
        loadData(silent = false)
    }

    private fun buildNav() {
        navBar?.let { root.removeView(it) }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(Ui.SURFACE)

        val divider = View(this)
        divider.setBackgroundColor(Ui.BORDER)
        bar.addView(divider, LinearLayout.LayoutParams(MATCH, 1))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        bar.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        navBar = bar
        navRow = row
        updateNav()
    }

    private fun countToProcess(): Int {
        var n = 0
        for (i in 0 until deposits.length()) {
            if (deposits.getJSONObject(i).getString("status") == "PAYMENT_CONFIRMED") n++
        }
        return n
    }

    private fun updateNav() {
        val row = navRow ?: return
        row.removeAllViews()
        val toProcess = countToProcess()

        for (tab in Tab.values()) {
            if (tab == Tab.ADMIN && SessionStorage.getRole(this) != "ADMIN") continue
            val selected = tab == currentTab
            val color = if (selected) Ui.PRIMARY else Ui.TEXT2
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(0, dp(10), 0, dp(10))
            val iconBox = FrameLayout(this)
            val iv = android.widget.ImageView(this)
            iv.setImageResource(tab.iconRes)
            iv.setColorFilter(color)
            iconBox.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            if (tab == Tab.QUEUE && toProcess > 0) {
                val badge = t(if (toProcess > 9) "9+" else toProcess.toString(), 10f, 0xFFFFFFFF.toInt(), true)
                badge.gravity = Gravity.CENTER
                badge.background = Ui.circle(Ui.ERROR)
                iconBox.addView(badge, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
            }
            val iconParams = LinearLayout.LayoutParams(dp(36), dp(28))
            iconParams.gravity = Gravity.CENTER_HORIZONTAL
            item.addView(iconBox, iconParams)
            val labelView = t(tab.label, 11f, color, selected)
            labelView.gravity = Gravity.CENTER
            labelView.maxLines = 1
            labelView.setPadding(0, dp(2), 0, 0)
            item.addView(labelView, LinearLayout.LayoutParams(MATCH, WRAP))
            item.setOnClickListener { setTab(tab) }
            row.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun setTab(tab: Tab) {
        currentTab = tab
        currentScroll = null
        renderTab()
        updateNav()
    }

    private fun renderTab() {
        val previousScroll = currentScroll?.scrollY ?: 0
        body.removeAllViews()

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(20), dp(20), dp(28))
        sv.addView(content)

        when (currentTab) {
            Tab.HOME -> buildHome(content)
            Tab.QUEUE -> buildQueue(content)
            Tab.HISTORY -> buildHistory(content)
            Tab.PAYMENTS -> buildPayments(content)
            Tab.ADMIN -> buildAdmin(content)
        }

        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        currentScroll = sv
        sv.post { sv.scrollTo(0, previousScroll) }
    }

    // ---------- en-tete et composants de page ----------

    private fun header(content: LinearLayout, title: String, subtitle: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(t(title, 26f, Ui.TEXT, true))
        col.addView(t(subtitle, 13f, Ui.TEXT2))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val refresh = android.widget.ImageView(this)
        refresh.setImageResource(R.drawable.ic_refresh)
        refresh.setColorFilter(Ui.TEXT2)
        refresh.setPadding(dp(10), dp(10), dp(10), dp(10))
        Ui.pressable(refresh) { loadData(silent = false) }
        row.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))

        val initial = (SessionStorage.getDisplayName(this) ?: "?").take(1).uppercase()
        val avatar = t(initial, 16f, Ui.PRIMARY, true)
        avatar.gravity = Gravity.CENTER
        avatar.background = Ui.circle(Ui.withAlpha(Ui.PRIMARY, 0x33))
        Ui.pressable(avatar) { showProfileSheet() }
        row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

        content.addView(row)

        if (statusMessage.isNotEmpty()) {
            val status = t(statusMessage, 11f, Ui.TEXT2)
            status.setPadding(0, dp(8), 0, 0)
            content.addView(status)
        }
        content.addView(spacer(16))
    }

    private fun sectionTitle(content: LinearLayout, value: String) {
        val v = t(value.uppercase(), 12f, Ui.TEXT2, true)
        v.letterSpacing = 0.08f
        v.setPadding(0, dp(24), 0, dp(8))
        content.addView(v)
    }

    private fun linkText(content: LinearLayout, value: String, onClick: () -> Unit) {
        val v = t(value, 13f, Ui.PRIMARY, true)
        v.setPadding(0, dp(8), 0, dp(8))
        Ui.pressable(v, onClick)
        content.addView(v)
    }

    private fun emptyState(content: LinearLayout, value: String) {
        val v = t(value, 14f, Ui.TEXT2)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(16), dp(32), dp(16), dp(32))
        v.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)
        content.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun statCard(label: String, big: String, sub: String, accent: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)

        val l = t(label, 11f, Ui.TEXT2, true)
        l.letterSpacing = 0.08f
        card.addView(l)

        val b = t(big, 30f, accent, true)
        b.setPadding(0, dp(8), 0, dp(2))
        card.addView(b)

        card.addView(t(sub, 12f, Ui.TEXT2))
        return card
    }

    private fun statRow(content: LinearLayout, left: View, right: View) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp1.setMargins(0, 0, dp(6), 0)
        val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp2.setMargins(dp(6), 0, 0, 0)
        row.addView(left, lp1)
        row.addView(right, lp2)
        content.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ---------- onglets ----------

    private fun summaryCards(content: LinearLayout) {
        var inProgress = 0
        for (i in 0 until deposits.length()) {
            if (deposits.getJSONObject(i).getString("status") == "PROCESSING") inProgress++
        }
        val toProcess = countToProcess()
        val h = history
        val todayCount = h?.optInt("todayCount") ?: 0
        val todayTotal = h?.optDouble("todayTotal") ?: 0.0

        val sub = if (inProgress > 0) "$inProgress en cours" else "à prendre en charge"
        val accent = if (toProcess > 0) Ui.WARNING else Ui.TEXT
        statRow(
            content,
            statCard("À TRAITER", toProcess.toString(), sub, accent),
            statCard("CRÉDITÉS AUJOURD'HUI", todayCount.toString(), fcfa(todayTotal), Ui.SUCCESS),
        )
    }

    private fun addDepositCards(content: LinearLayout, array: JSONArray, max: Int) {
        val n = minOf(array.length(), max)
        for (i in 0 until n) {
            content.addView(depositCard(array.getJSONObject(i)))
        }
    }

    private fun buildHome(content: LinearLayout) {
        val name = SessionStorage.getDisplayName(this) ?: ""
        val role = if (SessionStorage.getRole(this) == "ADMIN") "Administrateur" else "Gestionnaire"
        header(content, "Bonjour, ${name.substringBefore(' ')}", role)
        summaryCards(content)

        if (deposits.length() > 0) {
            sectionTitle(content, "À traiter maintenant")
            addDepositCards(content, deposits, 3)
            if (deposits.length() > 3) {
                linkText(content, "Voir les ${deposits.length()} opérations") { setTab(Tab.QUEUE) }
            }
        }

        sectionTitle(content, "Activité récente")
        val items = history?.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) {
            emptyState(content, "Aucune opération pour le moment.")
        } else {
            addDepositCards(content, items, 5)
            if (items.length() > 5) {
                linkText(content, "Voir tout l'historique") { setTab(Tab.HISTORY) }
            }
        }
    }

    private fun buildQueue(content: LinearLayout) {
        header(content, "À traiter", "${deposits.length()} opération(s)")
        if (deposits.length() == 0) {
            emptyState(content, "Aucun dépôt à traiter.\nLes nouveaux paiements apparaissent ici automatiquement.")
        } else {
            addDepositCards(content, deposits, deposits.length())
        }
    }

    private fun buildHistory(content: LinearLayout) {
        val subtitle = if (SessionStorage.getRole(this) == "ADMIN") "Tous les managers" else "Mes opérations"
        header(content, "Historique", subtitle)
        summaryCards(content)

        val items = history?.optJSONArray("items") ?: JSONArray()
        sectionTitle(content, "Dernières opérations")
        if (history == null) {
            emptyState(content, "Historique indisponible pour le moment.")
        } else if (items.length() == 0) {
            emptyState(content, "Aucun dépôt crédité pour le moment.")
        } else {
            addDepositCards(content, items, items.length())
        }
    }

    private fun buildPayments(content: LinearLayout) {
        header(content, "Paiements à vérifier", "${unmatched.length()} paiement(s)")
        val note = t("Paiements reçus sans dépôt correspondant. Ne rien créditer sans vérification.", 13f, Ui.TEXT2)
        note.setPadding(0, 0, 0, dp(8))
        content.addView(note)

        if (unmatched.length() == 0) {
            emptyState(content, "Aucun paiement à vérifier.")
        } else {
            for (i in 0 until unmatched.length()) {
                content.addView(paymentCard(unmatched.getJSONObject(i)))
            }
        }
    }

    // ---------- administration : telephone hote ----------

    private val smsRequestCode = 100

    private fun ensureSmsPermission() {
        val receive = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS)
        val read = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
        if (receive != android.content.pm.PackageManager.PERMISSION_GRANTED || read != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS),
                smsRequestCode,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == smsRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            val message = if (granted) "Autorisation SMS accordée" else "Sans l'autorisation SMS, ce téléphone ne peut pas recevoir les paiements"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildAdmin(content: LinearLayout) {
        header(content, "Administration", "Téléphone hôte des SMS")

        val configured = ConfigStorage.isConfigured(this)
        val label = ConfigStorage.getHostLabel(this)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)

        val title = t("TÉLÉPHONE HÔTE", 11f, Ui.TEXT2, true)
        title.letterSpacing = 0.08f
        card.addView(title)

        val state = t(if (configured) "Actif" else "Non configuré", 22f, if (configured) Ui.SUCCESS else Ui.WARNING, true)
        state.setPadding(0, dp(8), 0, dp(2))
        card.addView(state)

        val detail = if (configured) label.ifEmpty { "Configuré" } else "Ce téléphone ne reçoit pas les paiements Orange Money."
        card.addView(t(detail, 13f, Ui.TEXT2))
        content.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(Ui.button(this, if (configured) "Reconfigurer ce téléphone" else "Configurer ce téléphone") {
            chooseHostManager()
        })
        if (configured) {
            content.addView(Ui.button(this, "Retirer ce téléphone", "danger") { confirmRemoveHost() })
        }

        sectionTitle(content, "Gestionnaires")
        content.addView(Ui.button(this, "Nouveau gestionnaire", "secondary") { showNewManagerSheet() })
        if (!managersLoaded && !managersLoading) loadManagers()
        if (!managersLoaded) {
            val loadingText = t("Chargement…", 13f, Ui.TEXT2)
            loadingText.setPadding(0, dp(12), 0, 0)
            content.addView(loadingText)
        } else if (managers.length() == 0) {
            emptyState(content, "Aucun gestionnaire.")
        } else {
            for (i in 0 until managers.length()) {
                content.addView(managerCard(managers.getJSONObject(i)))
            }
        }
    }

    private fun loadManagers() {
        val token = SessionStorage.getToken(this) ?: return
        if (managersLoading) return
        managersLoading = true
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                managers = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/admin/managers", token))
                }
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    managersLoading = false
                    handleApiError(e, false)
                    return@launch
                }
            } catch (e: Exception) {
            }
            managersLoaded = true
            managersLoading = false
            if (currentTab == Tab.ADMIN) renderTab()
        }
    }

    private fun reloadManagers() {
        managersLoaded = false
        managersLoading = false
        renderTab()
    }

    private fun roleLabel(role: String): String = if (role == "ADMIN") "Administrateur" else "Gestionnaire"

    private fun managerCard(m: JSONObject): View {
        val enabled = m.optBoolean("enabled", true)
        val card = cardShell(R.drawable.ic_users, if (enabled) Ui.PRIMARY else Ui.TEXT2)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12), 0, dp(8), 0)
        val name = t(m.getString("display_name"), 15f, Ui.TEXT, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        mid.addView(name)
        mid.addView(t(roleLabel(m.getString("role")), 12f, Ui.TEXT2))
        card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        card.addView(t(if (enabled) "● Actif" else "● Désactivé", 12f, if (enabled) Ui.SUCCESS else Ui.TEXT2, true))
        Ui.pressable(card) { showManagerSheet(m) }
        return card
    }

    private fun showManagerSheet(m: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)
        val id = m.getString("id")
        val enabled = m.optBoolean("enabled", true)
        val isSelf = id == (SessionStorage.getManagerId(this) ?: "")

        content.addView(centered(m.getString("display_name"), 22f, Ui.TEXT, true))

        val pillWrap = LinearLayout(this)
        pillWrap.gravity = Gravity.CENTER
        pillWrap.setPadding(0, dp(10), 0, dp(4))
        pillWrap.addView(Ui.pill(this, if (enabled) "Actif" else "Désactivé", if (enabled) Ui.SUCCESS else Ui.TEXT2))
        content.addView(pillWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        val lastLogin = str(m, "last_login_at")
        content.addView(
            Ui.section(
                this, "Compte",
                listOf(
                    "Rôle" to roleLabel(m.getString("role")),
                    "Email" to m.getString("email"),
                    "Dernière connexion" to (if (lastLogin.isEmpty()) "Jamais" else longDate(lastLogin)),
                ),
            ),
        )

        if (isSelf) {
            val note = t("C'est votre compte.", 13f, Ui.TEXT2)
            note.setPadding(0, dp(16), 0, 0)
            content.addView(note)
        } else {
            content.addView(Ui.button(this, if (enabled) "Désactiver le compte" else "Réactiver le compte", if (enabled) "danger" else "primary") {
                sheet.dismiss()
                setManagerEnabled(id, !enabled)
            })
        }
        sheet.show()
    }

    private fun setManagerEnabled(id: String, enabled: Boolean) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) {
                    ApiClient.request(base, "PATCH", "/admin/managers/$id/status", token, JSONObject().put("enabled", enabled))
                }
                Toast.makeText(this@ManagerActivity, if (enabled) "Compte réactivé" else "Compte désactivé", Toast.LENGTH_SHORT).show()
                reloadManagers()
            } catch (e: ApiException) {
                handleApiError(e, true)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showNewManagerSheet() {
        val (sheet, content) = Ui.bottomSheet(this)
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        content.addView(centered("Nouveau gestionnaire", 20f, Ui.TEXT, true))
        content.addView(spacer(12))

        val nameInput = Ui.input(this, "Nom complet")
        val emailInput = Ui.input(this, "Adresse email", email = true)
        val passwordInput = Ui.input(this, "Mot de passe (8 caractères minimum)", password = true)
        content.addView(nameInput)
        content.addView(emailInput)
        content.addView(passwordInput)

        var role = "MANAGER"
        val roleView = t("Rôle : Gestionnaire  (appuyer pour changer)", 13f, Ui.PRIMARY, true)
        roleView.setPadding(0, dp(4), 0, dp(4))
        Ui.pressable(roleView) {
            role = if (role == "MANAGER") "ADMIN" else "MANAGER"
            roleView.text = "Rôle : " + roleLabel(role) + "  (appuyer pour changer)"
        }
        content.addView(roleView)

        var sending = false
        content.addView(Ui.button(this, "Créer le compte") {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (name.isEmpty() || email.isEmpty() || password.length < 8) {
                Toast.makeText(this, "Nom, email et mot de passe (8 caractères minimum) requis", Toast.LENGTH_LONG).show()
            } else if (!sending) {
                sending = true
                createManager(sheet, name, email, password, role) { sending = false }
            }
        })
        sheet.show()
    }

    private fun createManager(sheet: android.app.Dialog, name: String, email: String, password: String, role: String, onDone: () -> Unit) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val body = JSONObject()
                    .put("displayName", name)
                    .put("email", email)
                    .put("password", password)
                    .put("role", role)
                withContext(Dispatchers.IO) { ApiClient.request(base, "POST", "/admin/managers", token, body) }
                sheet.dismiss()
                Toast.makeText(this@ManagerActivity, "Compte créé ✅", Toast.LENGTH_SHORT).show()
                reloadManagers()
            } catch (e: ApiException) {
                onDone()
                handleApiError(e, true)
            } catch (e: Exception) {
                onDone()
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseHostManager() {
        val token = SessionStorage.getToken(this) ?: return
        ensureSmsPermission()
        Toast.makeText(this, "Chargement des gestionnaires…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val list = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/admin/managers", token))
                }
                showManagerPicker(list)
            } catch (e: ApiException) {
                handleApiError(e, true)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showManagerPicker(list: JSONArray) {
        val (sheet, content) = Ui.bottomSheet(this)
        content.addView(centered("Choisir le gestionnaire", 20f, Ui.TEXT, true))
        content.addView(centered("Ce téléphone recevra les paiements Orange Money pour lui.", 13f, Ui.TEXT2, false))

        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            if (!m.optBoolean("enabled", true)) continue
            val id = m.getString("id")
            val name = m.getString("display_name")
            val roleLabel = if (m.getString("role") == "ADMIN") "Administrateur" else "Gestionnaire"

            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(14), dp(16), dp(14))
            row.background = Ui.rounded(this, Ui.ELEVATED, 16, Ui.BORDER)
            row.addView(t(name, 16f, Ui.TEXT, true))
            row.addView(t(roleLabel, 12f, Ui.TEXT2))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(12), 0, 0)
            row.layoutParams = lp
            Ui.pressable(row) {
                sheet.dismiss()
                configureHost(id, name)
            }
            content.addView(row)
        }
        sheet.show()
    }

    private fun configureHost(managerId: String, managerName: String) {
        val token = SessionStorage.getToken(this) ?: return
        Toast.makeText(this, "Configuration en cours…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val oldId = ConfigStorage.getDeviceId(this@ManagerActivity)
                val result = withContext(Dispatchers.IO) {
                    val deviceName = android.os.Build.MODEL + " - " + managerName
                    val created = JSONObject(
                        ApiClient.request(base, "POST", "/admin/devices", token, JSONObject().put("deviceName", deviceName)),
                    )
                    val deviceId = created.getJSONObject("device").getString("id")
                    val deviceToken = created.getString("deviceToken")
                    ApiClient.request(base, "PATCH", "/admin/devices/$deviceId/assign", token, JSONObject().put("managerId", managerId))
                    if (oldId != null) {
                        try {
                            ApiClient.request(base, "PATCH", "/admin/devices/$oldId/status", token, JSONObject().put("enabled", false))
                        } catch (e: Exception) {
                        }
                    }
                    Pair(deviceId, deviceToken)
                }
                ConfigStorage.saveHost(this@ManagerActivity, result.first, result.second, "Hôte de $managerName")
                (applicationContext as? KndTiguiApplication)?.triggerSync()
                Toast.makeText(this@ManagerActivity, "Téléphone configuré ✅", Toast.LENGTH_LONG).show()
                renderTab()
            } catch (e: ApiException) {
                handleApiError(e, true)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRemoveHost() {
        AlertDialog.Builder(this)
            .setTitle("Retirer ce téléphone")
            .setMessage("Ce téléphone ne recevra plus les paiements Orange Money. Continuer ?")
            .setPositiveButton("Retirer") { _, _ ->
                val token = SessionStorage.getToken(this)
                val deviceId = ConfigStorage.getDeviceId(this)
                val base = ConfigStorage.getApiBaseUrl(this)
                if (token != null && deviceId != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            ApiClient.request(base, "PATCH", "/admin/devices/$deviceId/status", token, JSONObject().put("enabled", false))
                        } catch (e: Exception) {
                        }
                    }
                }
                ConfigStorage.clearHost(this)
                renderTab()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- cartes compactes ----------

    private fun cardShell(iconRes: Int, iconColor: Int): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)

        val icon = android.widget.ImageView(this)
        icon.setImageResource(iconRes)
        icon.setColorFilter(iconColor)
        icon.setPadding(dp(10), dp(10), dp(10), dp(10))
        icon.background = Ui.circle(Ui.withAlpha(iconColor, 0x26))
        card.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(6), 0, dp(6))
        card.layoutParams = lp
        return card
    }

    private fun depositCard(d: JSONObject): View {
        val (label, color) = Ui.statusInfo(d.getString("status"))
        val card = cardShell(R.drawable.ic_arrow_down, Ui.PRIMARY)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12), 0, dp(8), 0)
        mid.addView(t("DÉPÔT", 10f, Ui.TEXT2, true))
        val name = t(d.getString("playerName"), 15f, Ui.TEXT, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        mid.addView(name)
        mid.addView(t(d.getString("reference"), 12f, Ui.TEXT2))
        card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.addView(t(fcfa(d.optDouble("totalCredit")), 15f, Ui.TEXT, true))
        right.addView(t("● $label", 12f, color, true))
        right.addView(t(shortDate(dateOf(d)), 11f, Ui.TEXT2))
        card.addView(right)

        Ui.pressable(card) { showDepositSheet(d) }
        return card
    }

    private fun reasonText(reason: String): String = when (reason) {
        "after_cancel" -> "Reçu après l'annulation du dépôt"
        "after_expiry" -> "Reçu après l'expiration du dépôt"
        "ambiguous" -> "Plusieurs dépôts possibles"
        else -> "Aucun dépôt correspondant"
    }

    private fun paymentCard(p: JSONObject): View {
        val card = cardShell(R.drawable.ic_alert, Ui.WARNING)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12), 0, dp(8), 0)
        mid.addView(t("PAIEMENT ORANGE MONEY", 10f, Ui.TEXT2, true))
        mid.addView(t(p.optString("senderPhone"), 15f, Ui.TEXT, true))
        val reason = t(reasonText(str(p, "lateMatchReason")), 12f, Ui.TEXT2)
        reason.maxLines = 1
        reason.ellipsize = TextUtils.TruncateAt.END
        mid.addView(reason)
        card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.addView(t(fcfa(p.optDouble("amount")), 15f, Ui.TEXT, true))
        right.addView(t("● À vérifier", 12f, Ui.WARNING, true))
        right.addView(t(shortDate(str(p, "receivedAt")), 11f, Ui.TEXT2))
        card.addView(right)

        Ui.pressable(card) { showPaymentSheet(p) }
        return card
    }

    // ---------- fiches detaillees ----------

    private fun showDepositSheet(d: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)
        val id = d.getString("id")
        val status = d.getString("status")
        val playerId = d.getString("playerId")
        val playerName = d.getString("playerName")
        val total = d.optDouble("totalCredit")
        val bonus = d.optDouble("bonusAmount")
        val bonusPct = d.optDouble("bonusPercentage")
        val owner = str(d, "processedBy")
        val ownerId = str(d, "processedByManagerId")
        val myId = SessionStorage.getManagerId(this) ?: ""
        val isMine = ownerId.isNotEmpty() && ownerId == myId
        val isAdmin = SessionStorage.getRole(this) == "ADMIN"
        val (label, color) = Ui.statusInfo(status)
        val payment = d.optJSONObject("payment")

        content.addView(centered("DÉPÔT 1XBET", 12f, Ui.TEXT2, true))
        content.addView(centered("+ ${fcfa(total)}", 30f, Ui.TEXT, true))

        val pillWrap = LinearLayout(this)
        pillWrap.gravity = Gravity.CENTER
        pillWrap.setPadding(0, dp(10), 0, dp(4))
        pillWrap.addView(Ui.pill(this, label, color))
        content.addView(pillWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(
            Ui.section(
                this, "Joueur",
                listOf("Nom" to playerName, "ID 1xBet" to playerId),
                setOf("ID 1xBet"),
            ) { l, v -> copy(l, v) },
        )

        val amountRows = mutableListOf("Dépôt" to fcfa(d.optDouble("amount")))
        if (bonus > 0) amountRows.add("Bonus (${bonusPct.toInt()} %)" to fcfa(bonus))
        amountRows.add("Crédit total" to fcfa(total))
        content.addView(Ui.section(this, "Montants", amountRows))

        if (payment != null) {
            content.addView(
                Ui.section(
                    this, "Paiement Orange Money",
                    listOf(
                        "Montant reçu" to fcfa(payment.optDouble("amount")),
                        "Téléphone" to payment.optString("senderPhone"),
                        "Transaction" to payment.optString("transactionId"),
                    ),
                    setOf("Transaction"),
                ) { l, v -> copy(l, v) },
            )
        }

        val processingRows = mutableListOf("Référence" to d.getString("reference"))
        if (owner.isNotEmpty()) {
            val role = if (status == "SUCCESS") "Traité par" else "Pris en charge par"
            processingRows.add(role to who(owner))
        }
        val processedAt = str(d, "processedAt")
        if (processedAt.isNotEmpty()) {
            processingRows.add("Traité le" to longDate(processedAt))
        } else {
            processingRows.add("Créé le" to longDate(str(d, "createdAt")))
        }
        content.addView(Ui.section(this, "Traitement", processingRows))

        if (status == "PAYMENT_CONFIRMED") {
            content.addView(Ui.button(this, "Prendre en charge") {
                sheet.dismiss()
                runAction("/manager/deposits/$id/claim", "Dépôt pris en charge")
            })
        } else if (status == "PROCESSING") {
            if (isMine) {
                content.addView(Ui.button(this, "Copier le montant à créditer", "secondary") {
                    copy("Montant", total.toLong().toString())
                })
                content.addView(Ui.button(this, "Crédit effectué") {
                    confirmComplete(sheet, id, playerId, playerName, total)
                })
                content.addView(Ui.button(this, "Libérer", "secondary") {
                    confirmRelease(sheet, id, false)
                })
            } else if (isAdmin) {
                content.addView(Ui.button(this, "Libérer (admin)", "danger") {
                    confirmRelease(sheet, id, true)
                })
            }
        }

        sheet.show()
    }

    private fun showPaymentSheet(p: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)

        content.addView(centered("PAIEMENT À VÉRIFIER", 12f, Ui.TEXT2, true))
        content.addView(centered(fcfa(p.optDouble("amount")), 30f, Ui.TEXT, true))

        val pillWrap = LinearLayout(this)
        pillWrap.gravity = Gravity.CENTER
        pillWrap.setPadding(0, dp(10), 0, dp(4))
        pillWrap.addView(Ui.pill(this, "À vérifier", Ui.WARNING))
        content.addView(pillWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(
            Ui.section(
                this, "Paiement Orange Money",
                listOf(
                    "Téléphone" to p.optString("senderPhone"),
                    "Nom" to p.optString("senderName").ifEmpty { "—" },
                    "Transaction" to p.optString("transactionId"),
                    "Reçu le" to longDate(str(p, "receivedAt")),
                ),
                setOf("Transaction"),
            ) { l, v -> copy(l, v) },
        )

        val analysis = mutableListOf("Motif" to reasonText(str(p, "lateMatchReason")))
        val linked = p.optJSONObject("linkedDeposit")
        if (linked != null) {
            analysis.add("Dépôt lié" to linked.optString("reference"))
            analysis.add("Statut du dépôt" to Ui.statusInfo(linked.optString("status")).first)
            analysis.add("Joueur" to linked.optString("playerName"))
        }
        content.addView(Ui.section(this, "Analyse", analysis))

        val note = t("Aucun crédit automatique. Vérifiez avec le client avant toute action.", 13f, Ui.TEXT2)
        note.setPadding(0, dp(16), 0, 0)
        content.addView(note)

        sheet.show()
    }

    private fun showProfileSheet() {
        val (sheet, content) = Ui.bottomSheet(this)
        val name = SessionStorage.getDisplayName(this) ?: "?"
        val role = if (SessionStorage.getRole(this) == "ADMIN") "Administrateur" else "Gestionnaire"

        content.addView(centered(name, 22f, Ui.TEXT, true))
        content.addView(centered(role, 13f, Ui.TEXT2, false))
        content.addView(
            Ui.section(
                this, "Compte",
                listOf("Rôle" to role, "Email" to (SessionStorage.getLastEmail(this) ?: "—")),
            ),
        )
        content.addView(Ui.button(this, "Se déconnecter", "danger") {
            sheet.dismiss()
            SessionStorage.clear(this)
            showLogin(null)
        })
        sheet.show()
    }

    // ---------- confirmations ----------

    private fun confirmComplete(sheet: android.app.Dialog, id: String, playerId: String, playerName: String, total: Double) {
        AlertDialog.Builder(this)
            .setTitle("Confirmer le crédit")
            .setMessage("Avez-vous bien crédité ${fcfa(total)} sur le compte 1xBet $playerId ($playerName) dans MobCash ?")
            .setPositiveButton("Oui, crédit effectué") { _, _ ->
                sheet.dismiss()
                runAction("/manager/deposits/$id/complete", "Dépôt clôturé")
            }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun confirmRelease(sheet: android.app.Dialog, id: String, adminOverride: Boolean) {
        val message = if (adminOverride) {
            "Vérifiez dans MobCash que ce dépôt n'a PAS déjà été crédité avant de le libérer, sinon il pourrait être crédité deux fois. Libérer ce dépôt ?"
        } else {
            "Le dépôt retournera dans la file et un collègue pourra le prendre. Continuer ?"
        }
        AlertDialog.Builder(this)
            .setTitle("Libérer le dépôt")
            .setMessage(message)
            .setPositiveButton("Libérer") { _, _ ->
                sheet.dismiss()
                runAction("/manager/deposits/$id/release", "Dépôt libéré")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- chargement et actions ----------

    private fun loadData(silent: Boolean) {
        if (busy) return
        val token = SessionStorage.getToken(this) ?: return
        busy = true
        if (!silent) {
            statusMessage = "Chargement… le serveur peut mettre jusqu'à 1 minute à se réveiller."
            renderTab()
        }

        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val result = withContext(Dispatchers.IO) {
                    val d = JSONArray(ApiClient.request(base, "GET", "/manager/deposits", token))
                    val u = JSONArray(ApiClient.request(base, "GET", "/manager/payments/unmatched", token))
                    val h: JSONObject? = try {
                        JSONObject(ApiClient.request(base, "GET", "/manager/deposits/history", token))
                    } catch (e: ApiException) {
                        if (e.httpCode == 401) throw e else null
                    }
                    Triple(d, u, h)
                }
                deposits = result.first
                unmatched = result.second
                history = result.third
                statusMessage = "Mis à jour à " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                renderTab()
                updateNav()
            } catch (e: ApiException) {
                handleApiError(e, false)
            } catch (e: Exception) {
                statusMessage = "Serveur injoignable. Nouvel essai automatique dans quelques secondes."
                renderTab()
            } finally {
                busy = false
            }
        }
    }

    private fun runAction(path: String, successMessage: String) {
        if (busy) return
        val token = SessionStorage.getToken(this) ?: return
        busy = true
        statusMessage = "Envoi…"
        renderTab()

        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) { ApiClient.request(base, "POST", path, token) }
                busy = false
                Toast.makeText(this@ManagerActivity, successMessage, Toast.LENGTH_SHORT).show()
                loadData(silent = false)
            } catch (e: ApiException) {
                busy = false
                handleApiError(e, true)
                loadData(silent = true)
            } catch (e: Exception) {
                busy = false
                statusMessage = "Serveur injoignable. Réessayez."
                renderTab()
            }
        }
    }

    private fun handleApiError(e: ApiException, showDialog: Boolean) {
        if (e.httpCode == 401) {
            SessionStorage.clear(this)
            showLogin("Session expirée. Reconnectez-vous.")
            return
        }
        statusMessage = "⚠️ ${e.message}"
        renderTab()
        if (showDialog) {
            AlertDialog.Builder(this)
                .setTitle("Action impossible")
                .setMessage(e.message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
