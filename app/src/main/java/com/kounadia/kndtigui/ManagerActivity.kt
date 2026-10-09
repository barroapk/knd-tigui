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
import java.util.TimeZone
import android.graphics.Color

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
    private var agentWithdrawals = JSONArray()
    private var agentWithdrawalsLoaded = false
    private var agentWithdrawalsLoading = false
    private var deviceList = JSONArray()
    private var devicesLoaded = false
    private var devicesLoading = false
    private var campaigns = JSONArray()
    private var campaignsLoaded = false
    private var campaignsLoading = false
    private var queueSubTab = "pending"
    private var hQuery = ""
    private var hDate = ""
    private var lastSignature = ""
    private var hPeriod = ""
    private var hSort = "date_desc"
    private var hPage = 1
    private var hTotalPages = 1
    private var hTotal = 0
    private var hLoadingMore = false
    private val hExtra = mutableListOf<JSONObject>()
    private var statusMessage = ""
    private var busy = false

    companion object {
        private const val POLL_INTERVAL_MS = 4000L
        private const val SEARCH_DEBOUNCE_MS = 500L
    }

    private var lastSeenSignature = ""

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (SessionStorage.isLoggedIn(this@ManagerActivity) && navBar != null) {
                checkForChanges()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun checkForChanges() {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val sig = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "GET", "/manager/activity-signature", token)).optString("signature", "")
                }
                if (sig != lastSeenSignature) {
                    lastSeenSignature = sig
                    loadData(silent = true)
                }
            } catch (e: Exception) {
            }
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
        SessionStorage.clear(this)
        val intent = android.content.Intent(this, LoginActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showLegacyLogin(message: String?) {
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

        val emailInput = field("Nom d'utilisateur ou email", false)
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
        sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, previousScroll)
                return true
            }
        })
        if (currentTab == Tab.HISTORY) {
            content.findViewWithTag<android.widget.EditText>("history_search")?.let { field ->
                field.requestFocus()
                field.setSelection(field.text.length)
            }
        }
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

    private fun queueFiltered(): JSONArray {
        val result = JSONArray()
        for (i in 0 until deposits.length()) {
            val d = deposits.getJSONObject(i)
            val status = d.getString("status")
            val matches = when (queueSubTab) {
                "pending" -> status == "PAYMENT_CONFIRMED" || status == "PROCESSING"
                "waiting" -> status == "PAYMENT_PENDING" || status == "PAYMENT_LATE"
                "expired" -> status == "PAYMENT_EXPIRED"
                else -> true
            }
            if (matches) result.put(d)
        }
        return result
    }

    private fun buildQueue(content: LinearLayout) {
        header(content, "À traiter", "${deposits.length()} opération(s)")

        val subTabRow = LinearLayout(this)
        subTabRow.orientation = LinearLayout.HORIZONTAL
        subTabRow.addView(chip("À traiter", queueSubTab == "pending") {
            queueSubTab = "pending"
            renderTab()
        })
        subTabRow.addView(chip("En attente", queueSubTab == "waiting") {
            queueSubTab = "waiting"
            renderTab()
        })
        subTabRow.addView(chip("Expiré", queueSubTab == "expired") {
            queueSubTab = "expired"
            renderTab()
        })
        content.addView(subTabRow)
        content.addView(spacer(12))

        val filtered = queueFiltered()
        if (filtered.length() == 0) {
            emptyState(content, "Aucune opération dans cette catégorie.")
        } else {
            addDepositCards(content, filtered, filtered.length())
        }
    }

    private fun buildHistory(content: LinearLayout) {
        val subtitle = if (SessionStorage.getRole(this) == "ADMIN") "Tous les managers" else "Mes opérations"
        header(content, "Historique", subtitle)
        summaryCards(content)

        // Recherche
        val search = Ui.input(this, "Rechercher : référence, ID joueur, nom, gestionnaire")
        search.tag = "history_search"
        search.setText(hQuery)
        search.setSelection(search.text.length)
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        var searchRunnable: Runnable? = null
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                val value = s?.toString()?.trim() ?: ""
                val r = Runnable {
                    if (value != hQuery) {
                        hQuery = value
                        applyHistoryFilters()
                    }
                }
                searchRunnable = r
                handler.postDelayed(r, SEARCH_DEBOUNCE_MS)
            }
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                hQuery = v.text.toString().trim()
                applyHistoryFilters()
                true
            } else {
                false
            }
        }
        val lp = search.layoutParams as LinearLayout.LayoutParams
        lp.setMargins(0, dp(16), 0, dp(4))
        content.addView(search)

        // Date precise (calendrier)
        val dateRow = LinearLayout(this)
        dateRow.orientation = LinearLayout.HORIZONTAL
        dateRow.gravity = Gravity.CENTER_VERTICAL
        val dateLabel = if (hDate.isEmpty()) "Choisir une date" else formatDateFr(hDate)
        dateRow.addView(chip("📅 $dateLabel", hDate.isNotEmpty()) { pickHistoryDate() })
        if (hDate.isNotEmpty()) {
            dateRow.addView(chip("✕ Retirer", false) {
                hDate = ""
                applyHistoryFilters()
            })
        }
        content.addView(dateRow)

        // Periode
        val periods = listOf("" to "Tout", "today" to "Aujourd'hui", "yesterday" to "Hier", "7d" to "7 jours", "30d" to "30 jours", "month" to "Ce mois")
        val periodRow = LinearLayout(this)
        periodRow.orientation = LinearLayout.HORIZONTAL
        for ((key, label) in periods) {
            periodRow.addView(chip(label, hDate.isEmpty() && hPeriod == key) {
                hDate = ""
                hPeriod = key
                applyHistoryFilters()
            })
        }
        val periodScroll = android.widget.HorizontalScrollView(this)
        periodScroll.isHorizontalScrollBarEnabled = false
        periodScroll.addView(periodRow)
        content.addView(periodScroll)

        // Tri
        val sorts = listOf("date_desc" to "Plus récent", "date_asc" to "Plus ancien", "amount_desc" to "Montant ↓", "amount_asc" to "Montant ↑")
        val sortRow = LinearLayout(this)
        sortRow.orientation = LinearLayout.HORIZONTAL
        for ((key, label) in sorts) {
            sortRow.addView(chip(label, hSort == key) {
                hSort = key
                applyHistoryFilters()
            })
        }
        val sortScroll = android.widget.HorizontalScrollView(this)
        sortScroll.isHorizontalScrollBarEnabled = false
        sortScroll.addView(sortRow)
        content.addView(sortScroll)

        // Resultats
        val items = history?.optJSONArray("items") ?: JSONArray()
        sectionTitle(content, "$hTotal opération(s)")
        if (history == null) {
            emptyState(content, "Historique indisponible pour le moment.")
        } else if (items.length() == 0 && hExtra.isEmpty()) {
            emptyState(content, if (hQuery.isEmpty() && hPeriod.isEmpty() && hDate.isEmpty()) "Aucun dépôt crédité pour le moment." else "Aucun résultat pour ces filtres.")
        } else {
            addDepositCards(content, items, items.length())
            for (d in hExtra) {
                content.addView(depositCard(d))
            }
            if (hPage < hTotalPages) {
                content.addView(Ui.button(this, if (hLoadingMore) "Chargement…" else "Charger plus", "secondary") {
                    loadMoreHistory()
                })
            }
        }
    }

    private fun formatDateFr(iso: String): String =
        if (iso.length == 10) "${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}" else iso

    private fun pickHistoryDate() {
        val cal = java.util.Calendar.getInstance()
        if (hDate.length == 10) {
            cal.set(hDate.substring(0, 4).toInt(), hDate.substring(5, 7).toInt() - 1, hDate.substring(8, 10).toInt())
        }
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                hDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                applyHistoryFilters()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val v = t(label, 13f, if (selected) Color.WHITE else Ui.TEXT2, selected)
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        v.background = Ui.rounded(this, if (selected) Ui.PRIMARY else Ui.ELEVATED, 20, if (selected) null else Ui.BORDER)
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(8), dp(8), 0)
        v.layoutParams = lp
        Ui.pressable(v, onClick)
        return v
    }

    private fun historyQueryString(page: Int): String {
        val sb = StringBuilder("page=$page&limit=20&sort=$hSort")
        if (hDate.isNotEmpty()) sb.append("&date=").append(hDate)
        else if (hPeriod.isNotEmpty()) sb.append("&period=").append(hPeriod)
        if (hQuery.isNotEmpty()) sb.append("&q=").append(java.net.URLEncoder.encode(hQuery, "UTF-8"))
        return sb.toString()
    }

    private fun applyHistoryFilters() {
        hPage = 1
        hExtra.clear()
        forceRenderNext = true
        loadData(silent = true, force = true)
    }

    private fun loadMoreHistory() {
        if (hLoadingMore || hPage >= hTotalPages) return
        val token = SessionStorage.getToken(this) ?: return
        hLoadingMore = true
        renderTab()
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val next = hPage + 1
                val result = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "GET", "/manager/deposits/history?" + historyQueryString(next), token))
                }
                val more = result.optJSONArray("items") ?: JSONArray()
                for (i in 0 until more.length()) hExtra.add(more.getJSONObject(i))
                hPage = next
            } catch (e: ApiException) {
                handleApiError(e, false)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_SHORT).show()
            }
            hLoadingMore = false
            renderTab()
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

        sectionTitle(content, "Agents")
        content.addView(Ui.button(this, "Nouvel agent", "secondary") { showNewAgentSheet() })

        buildAgentWithdrawalsSection(content)

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

        sectionTitle(content, "Bonus")
        content.addView(Ui.button(this, "Nouvelle campagne", "secondary") { showNewCampaignSheet() })
        if (!campaignsLoaded && !campaignsLoading) loadCampaigns()
        if (!campaignsLoaded) {
            val loadingText = t("Chargement…", 13f, Ui.TEXT2)
            loadingText.setPadding(0, dp(12), 0, 0)
            content.addView(loadingText)
        } else if (campaigns.length() == 0) {
            emptyState(content, "Aucune campagne. Sans campagne active, il n'y a pas de bonus.")
        } else {
            for (i in 0 until minOf(campaigns.length(), 8)) {
                content.addView(campaignCard(campaigns.getJSONObject(i)))
            }
        }

        sectionTitle(content, "Appareils")
        if (!devicesLoaded && !devicesLoading) loadDevices()
        if (!devicesLoaded) {
            val loadingText = t("Chargement…", 13f, Ui.TEXT2)
            loadingText.setPadding(0, dp(12), 0, 0)
            content.addView(loadingText)
        } else if (deviceList.length() == 0) {
            emptyState(content, "Aucun appareil enregistré.")
        } else {
            for (i in 0 until deviceList.length()) {
                content.addView(deviceCard(deviceList.getJSONObject(i)))
            }
        }
    }

    private fun loadCampaigns() {
        val token = SessionStorage.getToken(this) ?: return
        if (campaignsLoading) return
        campaignsLoading = true
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                campaigns = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/admin/bonus", token))
                }
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    campaignsLoading = false
                    handleApiError(e, false)
                    return@launch
                }
            } catch (e: Exception) {
            }
            campaignsLoaded = true
            campaignsLoading = false
            if (currentTab == Tab.ADMIN) renderTab()
        }
    }

    private fun reloadCampaigns() {
        campaignsLoaded = false
        campaignsLoading = false
        renderTab()
    }

    private fun campaignState(state: String): Pair<String, Int> = when (state) {
        "RUNNING" -> Pair("En cours", Ui.SUCCESS)
        "SCHEDULED" -> Pair("Programmée", Ui.WARNING)
        "ENDED" -> Pair("Terminée", Ui.TEXT2)
        else -> Pair("Inactive", Ui.TEXT2)
    }

    private fun isoUtc(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun periodEndMs(period: String): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (period == "7d") {
            return System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        }
        if (period == "month") {
            cal.set(java.util.Calendar.DAY_OF_MONTH, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        return cal.timeInMillis
    }

    private fun campaignCard(k: JSONObject): View {
        val (label, color) = campaignState(k.getString("state"))
        val card = cardShell(R.drawable.ic_percent, if (k.getString("state") == "RUNNING") Ui.SUCCESS else Ui.PRIMARY)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12), 0, dp(8), 0)
        val name = t(k.getString("name"), 15f, Ui.TEXT, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        mid.addView(name)
        val firstPct = k.optDouble("percentage").toString().removeSuffix(".0")
        val returningPct = if (k.isNull("returningPercentage")) null else k.optDouble("returningPercentage").toString().removeSuffix(".0")
        val pctSummary = if (returningPct != null) "$firstPct % / $returningPct % ensuite" else "$firstPct %"
        mid.addView(t("$pctSummary · jusqu'au ${shortDate(k.getString("endsAt")).substringBefore(' ')}", 12f, Ui.TEXT2))
        card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        card.addView(t("● $label", 12f, color, true))
        Ui.pressable(card) { showCampaignSheet(k) }
        return card
    }

    private fun showCampaignSheet(k: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)
        val id = k.getString("id")
        val state = k.getString("state")
        val (label, color) = campaignState(state)

        content.addView(centered(k.getString("name"), 20f, Ui.TEXT, true))
        val pillWrap = LinearLayout(this)
        pillWrap.gravity = Gravity.CENTER
        pillWrap.setPadding(0, dp(10), 0, dp(4))
        pillWrap.addView(Ui.pill(this, label, color))
        content.addView(pillWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        val maxBonus = if (k.isNull("maxBonus")) "Aucun" else fcfa(k.optDouble("maxBonus"))
        content.addView(
            Ui.section(
                this, "Règles",
                listOf(
                    "Premier dépôt" to (k.optDouble("percentage").toString().removeSuffix(".0") + " %"),
                    "Dépôts suivants" to (
                        if (k.isNull("returningPercentage")) "Non défini"
                        else k.optDouble("returningPercentage").toString().removeSuffix(".0") + " %"
                    ),
                    "Dépôt minimum" to fcfa(k.optDouble("minDeposit")),
                    "Bonus maximum" to maxBonus,
                    "Début" to longDate(k.getString("startsAt")),
                    "Fin" to longDate(k.getString("endsAt")),
                ),
            ),
        )

        content.addView(Ui.button(this, "Modifier", "secondary") {
            sheet.dismiss()
            showEditCampaignSheet(k)
        })

        if (state == "INACTIVE") {
            content.addView(Ui.button(this, "Activer la campagne") {
                sheet.dismiss()
                campaignAction("/admin/bonus/$id/activate", "Campagne activée")
            })
        } else if (state == "RUNNING" || state == "SCHEDULED") {
            content.addView(Ui.button(this, "Arrêter la campagne", "danger") {
                sheet.dismiss()
                campaignAction("/admin/bonus/$id/stop", "Campagne arrêtée")
            })
        }
        sheet.show()
    }

    private fun campaignAction(path: String, okMessage: String) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) { ApiClient.request(base, "POST", path, token) }
                Toast.makeText(this@ManagerActivity, okMessage, Toast.LENGTH_SHORT).show()
                reloadCampaigns()
            } catch (e: ApiException) {
                handleApiError(e, true)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showNewCampaignSheet() {
        val (sheet, content) = Ui.bottomSheet(this)
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        content.addView(centered("Nouvelle campagne de bonus", 20f, Ui.TEXT, true))
        content.addView(spacer(12))

        val nameInput = Ui.input(this, "Nom (ex : Bonus vendredi)")
        val percentInput = Ui.input(this, "Premier dépôt (ex : 5)")
        val returningPercentInput = Ui.input(this, "Dépôts suivants (ex : 1)")
        val minInput = Ui.input(this, "Dépôt minimum en FCFA (facultatif)")
        val maxInput = Ui.input(this, "Bonus maximum en FCFA (facultatif)")
        val numeric = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        percentInput.inputType = numeric
        returningPercentInput.inputType = numeric
        minInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        maxInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        content.addView(nameInput)
        content.addView(percentInput)
        content.addView(returningPercentInput)
        content.addView(minInput)
        content.addView(maxInput)

        val periods = listOf("today" to "Aujourd'hui", "7d" to "7 jours", "month" to "Jusqu'à la fin du mois")
        var periodIndex = 0
        val periodView = t("Période : ${periods[0].second}  (appuyer pour changer)", 13f, Ui.PRIMARY, true)
        periodView.setPadding(0, dp(4), 0, dp(4))
        Ui.pressable(periodView) {
            periodIndex = (periodIndex + 1) % periods.size
            periodView.text = "Période : ${periods[periodIndex].second}  (appuyer pour changer)"
        }
        content.addView(periodView)

        var sending = false
        content.addView(Ui.button(this, "Créer et activer") {
            val name = nameInput.text.toString().trim()
            val percent = percentInput.text.toString().replace(',', '.').toDoubleOrNull()
            val returningPercent = returningPercentInput.text.toString().replace(',', '.').toDoubleOrNull()
            val minDeposit = minInput.text.toString().trim().toLongOrNull() ?: 0L
            val maxText = maxInput.text.toString().trim()
            val maxBonus = if (maxText.isEmpty()) null else maxText.toLongOrNull()
            if (
                name.isEmpty() ||
                percent == null || percent <= 0 || percent > 100 ||
                returningPercent == null || returningPercent < 0 || returningPercent > 100
            ) {
                Toast.makeText(
                    this,
                    "Nom et pourcentages valides requis (0 à 100)",
                    Toast.LENGTH_LONG
                ).show()
            } else if (maxText.isNotEmpty() && (maxBonus == null || maxBonus <= 0)) {
                Toast.makeText(this, "Bonus maximum invalide", Toast.LENGTH_LONG).show()
            } else if (!sending) {
                sending = true
                val now = System.currentTimeMillis()
                val body = JSONObject()
                    .put("name", name)
                    .put("percentage", percent)
                    .put("returningPercentage", returningPercent)
                    .put("startsAt", isoUtc(now - 60000))
                    .put("endsAt", isoUtc(periodEndMs(periods[periodIndex].first)))
                    .put("minDeposit", minDeposit)
                if (maxBonus != null) body.put("maxBonus", maxBonus)
                createCampaign(sheet, body) { sending = false }
            }
        })
        sheet.show()
    }

    private fun showEditCampaignSheet(k: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)
        sheet.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        content.addView(centered("Modifier la campagne", 20f, Ui.TEXT, true))
        content.addView(spacer(12))

        val nameInput = Ui.input(this, "Nom")
        val percentInput = Ui.input(this, "Premier dépôt")
        val returningPercentInput = Ui.input(this, "Dépôts suivants")
        val minInput = Ui.input(this, "Dépôt minimum en FCFA")
        val maxInput = Ui.input(this, "Bonus maximum en FCFA")

        val numeric =
            android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        percentInput.inputType = numeric
        returningPercentInput.inputType = numeric
        minInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        maxInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        nameInput.setText(k.optString("name"))
        percentInput.setText(k.optDouble("percentage").toString().removeSuffix(".0"))

        if (k.isNull("returningPercentage")) {
            returningPercentInput.setText("")
        } else {
            returningPercentInput.setText(
                k.optDouble("returningPercentage").toString().removeSuffix(".0")
            )
        }

        minInput.setText(
            k.optDouble("minDeposit").toLong().takeIf { it > 0 }?.toString() ?: ""
        )

        if (!k.isNull("maxBonus")) {
            maxInput.setText(k.optDouble("maxBonus").toLong().toString())
        }

        content.addView(nameInput)
        content.addView(percentInput)
        content.addView(returningPercentInput)
        content.addView(minInput)
        content.addView(maxInput)

        var sending = false

        content.addView(Ui.button(this, "Enregistrer les modifications") {
            val name = nameInput.text.toString().trim()
            val percent =
                percentInput.text.toString().replace(',', '.').toDoubleOrNull()
            val returningPercent =
                returningPercentInput.text.toString().replace(',', '.').toDoubleOrNull()

            val minText = minInput.text.toString().trim()
            val minDeposit = if (minText.isEmpty()) 0L else minText.toLongOrNull()

            val maxText = maxInput.text.toString().trim()
            val maxBonus = if (maxText.isEmpty()) null else maxText.toLongOrNull()

            val token = SessionStorage.getToken(this)

            if (
                name.isEmpty() ||
                percent == null || percent <= 0 || percent > 100 ||
                returningPercent == null || returningPercent < 0 || returningPercent > 100
            ) {
                Toast.makeText(
                    this,
                    "Nom et pourcentages valides requis (0 à 100)",
                    Toast.LENGTH_LONG
                ).show()
            } else if (minDeposit == null || minDeposit < 0) {
                Toast.makeText(
                    this,
                    "Dépôt minimum invalide",
                    Toast.LENGTH_LONG
                ).show()
            } else if (maxText.isNotEmpty() && (maxBonus == null || maxBonus <= 0)) {
                Toast.makeText(
                    this,
                    "Bonus maximum invalide",
                    Toast.LENGTH_LONG
                ).show()
            } else if (token == null) {
                Toast.makeText(
                    this,
                    "Session expirée. Reconnectez-vous.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (!sending) {
                sending = true

                val body = JSONObject()
                    .put("name", name)
                    .put("percentage", percent)
                    .put("returningPercentage", returningPercent)
                    .put("minDeposit", minDeposit ?: 0L)

                if (maxBonus != null) {
                    body.put("maxBonus", maxBonus)
                } else {
                    body.put("maxBonus", JSONObject.NULL)
                }

                val id = k.getString("id")

                scope.launch {
                    try {
                        val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)

                        withContext(Dispatchers.IO) {
                            ApiClient.request(
                                base,
                                "PATCH",
                                "/admin/bonus/$id",
                                token,
                                body
                            )
                        }

                        sheet.dismiss()

                        Toast.makeText(
                            this@ManagerActivity,
                            "Campagne modifiée ✅",
                            Toast.LENGTH_SHORT
                        ).show()

                        reloadCampaigns()
                    } catch (e: ApiException) {
                        sending = false
                        handleApiError(e, true)
                    } catch (e: Exception) {
                        sending = false
                        Toast.makeText(
                            this@ManagerActivity,
                            "Serveur injoignable. Réessayez.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })

        sheet.show()
    }

    private fun createCampaign(sheet: android.app.Dialog, body: JSONObject, onDone: () -> Unit) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) {
                    val created = JSONObject(ApiClient.request(base, "POST", "/admin/bonus", token, body))
                    ApiClient.request(base, "POST", "/admin/bonus/" + created.getString("id") + "/activate", token)
                }
                sheet.dismiss()
                Toast.makeText(this@ManagerActivity, "Campagne créée et activée ✅", Toast.LENGTH_SHORT).show()
                reloadCampaigns()
            } catch (e: ApiException) {
                onDone()
                handleApiError(e, true)
            } catch (e: Exception) {
                onDone()
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadDevices() {
        val token = SessionStorage.getToken(this) ?: return
        if (devicesLoading) return
        devicesLoading = true
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                deviceList = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/admin/devices", token))
                }
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    devicesLoading = false
                    handleApiError(e, false)
                    return@launch
                }
            } catch (e: Exception) {
            }
            devicesLoaded = true
            devicesLoading = false
            if (currentTab == Tab.ADMIN) renderTab()
        }
    }

    private fun reloadDevices() {
        devicesLoaded = false
        devicesLoading = false
        renderTab()
    }

    private fun managerNameById(id: String): String {
        for (i in 0 until managers.length()) {
            val m = managers.getJSONObject(i)
            if (m.getString("id") == id) return m.getString("display_name")
        }
        return "Gestionnaire"
    }

    private fun deviceCard(d: JSONObject): View {
        val enabled = d.optBoolean("enabled", true)
        val isThis = d.getString("id") == (ConfigStorage.getDeviceId(this) ?: "")
        val assigned = str(d, "assignedManagerId")
        val card = cardShell(R.drawable.ic_phone, if (enabled) Ui.PRIMARY else Ui.TEXT2)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12), 0, dp(8), 0)
        val name = t(d.getString("deviceName"), 15f, Ui.TEXT, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        mid.addView(name)
        val sub = if (assigned.isEmpty()) "Non affecté" else "Affecté à " + managerNameById(assigned)
        mid.addView(t(if (isThis) "Ce téléphone · $sub" else sub, 12f, Ui.TEXT2))
        card.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        card.addView(t(if (enabled) "● Actif" else "● Désactivé", 12f, if (enabled) Ui.SUCCESS else Ui.TEXT2, true))
        Ui.pressable(card) { showDeviceSheet(d) }
        return card
    }

    private fun showDeviceSheet(d: JSONObject) {
        val (sheet, content) = Ui.bottomSheet(this)
        val id = d.getString("id")
        val enabled = d.optBoolean("enabled", true)
        val isThis = id == (ConfigStorage.getDeviceId(this) ?: "")
        val assigned = str(d, "assignedManagerId")
        val lastSeen = str(d, "lastSeenAt")

        content.addView(centered(d.getString("deviceName"), 20f, Ui.TEXT, true))

        val pillWrap = LinearLayout(this)
        pillWrap.gravity = Gravity.CENTER
        pillWrap.setPadding(0, dp(10), 0, dp(4))
        pillWrap.addView(Ui.pill(this, if (enabled) "Actif" else "Désactivé", if (enabled) Ui.SUCCESS else Ui.TEXT2))
        content.addView(pillWrap, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(
            Ui.section(
                this, "Appareil",
                listOf(
                    "Affectation" to (if (assigned.isEmpty()) "Non affecté" else managerNameById(assigned)),
                    "Dernière activité" to (if (lastSeen.isEmpty()) "Jamais" else longDate(lastSeen)),
                    "Créé le" to longDate(str(d, "createdAt")),
                    "Ce téléphone" to (if (isThis) "Oui" else "Non"),
                ),
            ),
        )

        content.addView(Ui.button(this, if (assigned.isEmpty()) "Affecter à un gestionnaire" else "Changer l'affectation", "secondary") {
            sheet.dismiss()
            showAssignPicker(id)
        })
        if (assigned.isNotEmpty()) {
            content.addView(Ui.button(this, "Retirer l'affectation", "secondary") {
                sheet.dismiss()
                deviceAction("/admin/devices/$id/unassign", null, "Affectation retirée")
            })
        }
        content.addView(Ui.button(this, if (enabled) "Désactiver l'appareil" else "Réactiver l'appareil", if (enabled) "danger" else "primary") {
            sheet.dismiss()
            if (enabled && isThis) {
                AlertDialog.Builder(this)
                    .setTitle("Désactiver ce téléphone")
                    .setMessage("C'est le téléphone que vous utilisez : il cessera de recevoir les paiements. Continuer ?")
                    .setPositiveButton("Désactiver") { _, _ ->
                        deviceAction("/admin/devices/$id/status", JSONObject().put("enabled", false), "Appareil désactivé")
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            } else {
                deviceAction("/admin/devices/$id/status", JSONObject().put("enabled", !enabled), if (enabled) "Appareil désactivé" else "Appareil réactivé")
            }
        })
        sheet.show()
    }

    private fun showAssignPicker(deviceId: String) {
        val (sheet, content) = Ui.bottomSheet(this)
        content.addView(centered("Affecter à", 20f, Ui.TEXT, true))
        for (i in 0 until managers.length()) {
            val m = managers.getJSONObject(i)
            if (!m.optBoolean("enabled", true)) continue
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(14), dp(16), dp(14))
            row.background = Ui.rounded(this, Ui.ELEVATED, 16, Ui.BORDER)
            row.addView(t(m.getString("display_name"), 16f, Ui.TEXT, true))
            row.addView(t(roleLabel(m.getString("role")), 12f, Ui.TEXT2))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(12), 0, 0)
            row.layoutParams = lp
            val managerId = m.getString("id")
            Ui.pressable(row) {
                sheet.dismiss()
                deviceAction("/admin/devices/$deviceId/assign", JSONObject().put("managerId", managerId), "Appareil affecté")
            }
            content.addView(row)
        }
        sheet.show()
    }

    private fun deviceAction(path: String, body: JSONObject?, okMessage: String) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) { ApiClient.request(base, "PATCH", path, token, body) }
                Toast.makeText(this@ManagerActivity, okMessage, Toast.LENGTH_SHORT).show()
                reloadDevices()
            } catch (e: ApiException) {
                handleApiError(e, true)
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_LONG).show()
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

    // ---------- creation d'agent (admin) ----------

    private fun showNewAgentSheet() {
        val (sheet, content) = Ui.bottomSheet(this)
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        content.addView(centered("Nouvel agent", 20f, Ui.TEXT, true))
        content.addView(spacer(12))

        val firstNameInput = Ui.input(this, "Prénom")
        val lastNameInput = Ui.input(this, "Nom")
        val companyInput = Ui.input(this, "Nom de l'entreprise")
        val phoneInput = Ui.input(this, "Numéro Orange Money (ex : 74123456)")
        phoneInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
        val cnibInput = Ui.input(this, "Numéro CNIB")
        cnibInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val passwordInput = Ui.input(this, "Mot de passe initial (6 caractères minimum)", password = true)

        content.addView(firstNameInput)
        content.addView(lastNameInput)
        content.addView(companyInput)
        content.addView(phoneInput)
        content.addView(cnibInput)
        content.addView(passwordInput)

        var sending = false
        content.addView(Ui.button(this, "Créer l'agent") {
            val firstName = firstNameInput.text.toString().trim()
            val lastName = lastNameInput.text.toString().trim()
            val company = companyInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val cnib = cnibInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (firstName.isEmpty() || lastName.isEmpty() || company.isEmpty() ||
                phone.isEmpty() || cnib.isEmpty() || password.length < 6
            ) {
                Toast.makeText(this, "Tous les champs sont requis (mot de passe : 6 caractères minimum)", Toast.LENGTH_LONG).show()
            } else if (!sending) {
                sending = true
                createAgent(sheet, firstName, lastName, company, phone, cnib, password) { sending = false }
            }
        })
        sheet.show()
    }

    private fun createAgent(
        sheet: android.app.Dialog,
        firstName: String,
        lastName: String,
        company: String,
        phone: String,
        cnib: String,
        password: String,
        onDone: () -> Unit,
    ) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val body = JSONObject()
                    .put("firstName", firstName)
                    .put("lastName", lastName)
                    .put("companyName", company)
                    .put("orangeMoneyPhone", phone)
                    .put("cnibNumber", cnib)
                    .put("password", password)
                val created = withContext(Dispatchers.IO) {
                    JSONObject(ApiClient.request(base, "POST", "/agents", token, body))
                }
                sheet.dismiss()
                showAgentCreatedSheet(created, password)
            } catch (e: ApiException) {
                onDone()
                if (e.httpCode == 401) {
                    handleApiError(e, false)
                } else {
                    Toast.makeText(this@ManagerActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                onDone()
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAgentCreatedSheet(agent: JSONObject, password: String) {
        val (sheet, content) = Ui.bottomSheet(this)
        val loginPhone = str(agent, "orangeMoneyPhone").takeLast(8)

        content.addView(centered("Agent créé ✅", 20f, Ui.TEXT, true))
        content.addView(Ui.section(
            this,
            "Identifiants à remettre à l'agent",
            listOf(
                "Entreprise" to str(agent, "companyName"),
                "Code agent" to str(agent, "agentCode"),
                "Connexion" to loginPhone,
                "Mot de passe" to password,
            ),
            copyable = setOf("Code agent", "Connexion", "Mot de passe"),
            onCopy = { label, value -> copy(label, value) },
        ))

        val warning = t(
            "L'agent se connecte avec son numéro Orange Money et ce mot de passe. " +
                "Le mot de passe ne sera plus affiché : notez-le maintenant.",
            12f, Ui.WARNING,
        )
        warning.setPadding(0, dp(12), 0, 0)
        content.addView(warning)

        content.addView(Ui.button(this, "Terminer") { sheet.dismiss() })
        sheet.show()
    }

    // ---------- retraits agents (admin) ----------

    private fun loadAgentWithdrawals() {
        val token = SessionStorage.getToken(this) ?: return
        if (agentWithdrawalsLoading) return
        agentWithdrawalsLoading = true
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                agentWithdrawals = withContext(Dispatchers.IO) {
                    JSONArray(ApiClient.request(base, "GET", "/manager/agent-withdrawals", token))
                }
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    agentWithdrawalsLoading = false
                    handleApiError(e, false)
                    return@launch
                }
            } catch (e: Exception) {
            }
            agentWithdrawalsLoaded = true
            agentWithdrawalsLoading = false
            if (currentTab == Tab.ADMIN) renderTab()
        }
    }

    private fun reloadAgentWithdrawals() {
        agentWithdrawalsLoaded = false
        agentWithdrawalsLoading = false
        renderTab()
    }

    private fun buildAgentWithdrawalsSection(content: LinearLayout) {
        sectionTitle(content, "Retraits agents")

        if (!agentWithdrawalsLoaded && !agentWithdrawalsLoading) loadAgentWithdrawals()
        if (!agentWithdrawalsLoaded) {
            val loadingText = t("Chargement…", 13f, Ui.TEXT2)
            loadingText.setPadding(0, dp(12), 0, 0)
            content.addView(loadingText)
            return
        }

        var active = 0
        var closed = 0
        for (i in 0 until agentWithdrawals.length()) {
            val w = agentWithdrawals.getJSONObject(i)
            val status = str(w, "status")
            if (status == "CREATED" || status == "PROCESSING") {
                active++
                content.addView(agentWithdrawalCard(w))
            } else {
                closed++
            }
        }

        if (active == 0) emptyState(content, "Aucun retrait agent à traiter.")
        if (closed > 0) {
            val info = t("$closed retrait(s) déjà clôturé(s)", 12f, Ui.TEXT2)
            info.setPadding(0, dp(10), 0, 0)
            content.addView(info)
        }
    }

    private fun agentWithdrawalStatus(status: String): Pair<String, Int> = when (status) {
        "CREATED" -> Pair("À traiter", Ui.WARNING)
        "PROCESSING" -> Pair("En cours", Ui.PRIMARY)
        "COMPLETED" -> Pair("Payé", Ui.SUCCESS)
        "FAILED" -> Pair("Échec", Ui.ERROR)
        "CANCELLED" -> Pair("Annulé", Ui.TEXT2)
        else -> Pair(status, Ui.TEXT2)
    }

    private fun agentWithdrawalCard(w: JSONObject): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(10), 0, 0)
        card.layoutParams = lp

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val amount = t(fcfa(w.optDouble("amount", 0.0)), 18f, Ui.TEXT, true)
        amount.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        top.addView(amount)
        val (label, color) = agentWithdrawalStatus(str(w, "status"))
        top.addView(Ui.pill(this, label, color))
        card.addView(top)

        card.addView(t(str(w, "reference"), 12f, Ui.TEXT2))
        card.addView(t("Orange Money : " + str(w, "agentOrangeMoneyPhone"), 12f, Ui.TEXT2))
        card.addView(t(shortDate(str(w, "createdAt")), 12f, Ui.TEXT2))

        card.setOnClickListener { showAgentWithdrawalSheet(w) }
        return card
    }

    private fun showAgentWithdrawalSheet(w: JSONObject) {
        val id = str(w, "id")
        val status = str(w, "status")
        val amount = w.optDouble("amount", 0.0)
        val (sheet, content) = Ui.bottomSheet(this)

        content.addView(centered("Retrait agent", 20f, Ui.TEXT, true))
        content.addView(Ui.section(this, "Demande", listOf(
            "Référence" to str(w, "reference"),
            "Montant" to fcfa(amount),
            "Envoyer sur" to str(w, "agentOrangeMoneyPhone"),
            "Créé le" to longDate(str(w, "createdAt")),
        )))

        if (status == "CREATED") {
            content.addView(Ui.button(this, "Prendre en charge") {
                sheet.dismiss()
                agentWithdrawalAction("/manager/agent-withdrawals/$id/take-charge", null, "Retrait pris en charge")
            })
        } else if (status == "PROCESSING") {
            content.addView(t(
                "Envoyez ${fcfa(amount)} sur le numéro ci-dessus, puis confirmez.",
                13f, Ui.TEXT2,
            ).also { it.setPadding(0, dp(12), 0, 0) })

            content.addView(Ui.button(this, "Paiement envoyé") {
                sheet.dismiss()
                val input = Ui.input(this, "Référence de transaction (facultatif)")
                val wrap = LinearLayout(this)
                wrap.setPadding(dp(20), dp(8), dp(20), 0)
                wrap.addView(input)
                AlertDialog.Builder(this)
                    .setTitle("Confirmer le paiement")
                    .setMessage("Confirmez uniquement si les ${fcfa(amount)} ont réellement été envoyés.")
                    .setView(wrap)
                    .setPositiveButton("Oui, envoyé") { _, _ ->
                        val body = JSONObject()
                        val ref = input.text.toString().trim()
                        if (ref.isNotEmpty()) body.put("paymentTransactionId", ref)
                        agentWithdrawalAction("/manager/agent-withdrawals/$id/complete", body, "Retrait confirmé")
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            })

            content.addView(Ui.button(this, "Marquer en échec", "danger") {
                sheet.dismiss()
                val input = Ui.input(this, "Motif de l'échec")
                val wrap = LinearLayout(this)
                wrap.setPadding(dp(20), dp(8), dp(20), 0)
                wrap.addView(input)
                AlertDialog.Builder(this)
                    .setTitle("Retrait en échec")
                    .setView(wrap)
                    .setPositiveButton("Valider") { _, _ ->
                        val reason = input.text.toString().trim()
                        if (reason.isEmpty()) {
                            Toast.makeText(this, "Motif requis", Toast.LENGTH_SHORT).show()
                        } else {
                            agentWithdrawalAction(
                                "/manager/agent-withdrawals/$id/fail",
                                JSONObject().put("reason", reason),
                                "Retrait marqué en échec",
                            )
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            })
        }

        sheet.show()
    }

    private fun agentWithdrawalAction(path: String, body: JSONObject?, successMessage: String) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                withContext(Dispatchers.IO) { ApiClient.request(base, "POST", path, token, body) }
                Toast.makeText(this@ManagerActivity, successMessage, Toast.LENGTH_SHORT).show()
                reloadAgentWithdrawals()
            } catch (e: ApiException) {
                if (e.httpCode == 401) {
                    handleApiError(e, false)
                } else {
                    Toast.makeText(this@ManagerActivity, e.message, Toast.LENGTH_LONG).show()
                    reloadAgentWithdrawals()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ManagerActivity, "Serveur injoignable. Réessayez.", Toast.LENGTH_SHORT).show()
            }
        }
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
        val handle = str(m, "username")
        mid.addView(t(roleLabel(m.getString("role")) + (if (handle.isEmpty()) "" else " · @$handle"), 12f, Ui.TEXT2))
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
                    "Nom d'utilisateur" to (if (str(m, "username").isEmpty()) "—" else str(m, "username")),
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
        val usernameInput = Ui.input(this, "Nom d'utilisateur (ex : moussa)")
        val emailInput = Ui.input(this, "Adresse email", email = true)
        val passwordInput = Ui.input(this, "Mot de passe (8 caractères minimum)", password = true)
        content.addView(nameInput)
        content.addView(usernameInput)
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
            val username = usernameInput.text.toString().trim().lowercase()
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (name.isEmpty() || username.length < 3 || email.isEmpty() || password.length < 8) {
                Toast.makeText(this, "Nom, nom d'utilisateur (3 caractères minimum), email et mot de passe (8 caractères minimum) requis", Toast.LENGTH_LONG).show()
            } else if (!sending) {
                sending = true
                createManager(sheet, name, username, email, password, role) { sending = false }
            }
        })
        sheet.show()
    }

    private fun createManager(sheet: android.app.Dialog, name: String, username: String, email: String, password: String, role: String, onDone: () -> Unit) {
        val token = SessionStorage.getToken(this) ?: return
        scope.launch {
            try {
                val base = ConfigStorage.getApiBaseUrl(this@ManagerActivity)
                val body = JSONObject()
                    .put("displayName", name)
                    .put("username", username)
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
        } else if (status == "PAYMENT_PENDING" || status == "PAYMENT_LATE" || status == "PAYMENT_EXPIRED") {
            content.addView(Ui.button(this, "Paiement confirmé (vérifié autrement)", "secondary") {
                AlertDialog.Builder(this)
                    .setTitle("Confirmer manuellement ?")
                    .setMessage("Vérifiez d'abord dans Max It ou un autre moyen que ce paiement a bien été reçu avant de continuer. Cette action attribue le dépôt à vous-même pour créditer le compte.")
                    .setPositiveButton("Oui, paiement confirmé") { _, _ ->
                        sheet.dismiss()
                        runAction("/manager/deposits/$id/confirm-manually", "Dépôt confirmé manuellement")
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
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

    private var reloadRequested = false
    private var forceRenderNext = false

    private fun loadData(silent: Boolean, force: Boolean = false) {
        if (busy) {
            if (force) reloadRequested = true
            return
        }
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
                        JSONObject(ApiClient.request(base, "GET", "/manager/deposits/history?" + historyQueryString(1), token))
                    } catch (e: ApiException) {
                        if (e.httpCode == 401) throw e else null
                    }
                    Triple(d, u, h)
                }
                val signature = result.first.toString() + "|" + result.second.toString() + "|" + (result.third?.toString() ?: "")
                val changed = signature != lastSignature
                lastSignature = signature
                deposits = result.first
                unmatched = result.second
                history = result.third
                if (changed) {
                    hPage = 1
                    hExtra.clear()
                    hTotalPages = result.third?.optInt("totalPages", 1) ?: 1
                    hTotal = result.third?.optInt("total", 0) ?: 0
                }
                statusMessage = "Mis à jour à " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                if (changed || !silent || forceRenderNext) {
                    forceRenderNext = false
                    renderTab()
                    updateNav()
                }
            } catch (e: ApiException) {
                handleApiError(e, false)
            } catch (e: Exception) {
                statusMessage = "Serveur injoignable. Nouvel essai automatique dans quelques secondes."
                renderTab()
            } finally {
                busy = false
                if (reloadRequested) {
                    reloadRequested = false
                    loadData(silent = true, force = true)
                }
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
