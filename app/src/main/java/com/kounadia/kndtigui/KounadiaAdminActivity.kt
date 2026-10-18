package com.kounadia.kndtigui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private const val KM = ViewGroup.LayoutParams.MATCH_PARENT
private const val KW = ViewGroup.LayoutParams.WRAP_CONTENT

/** Onglet KOUNADIA : statistiques d'usage de l'application et gestion des publicités. */
class KounadiaAdminActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var days = 7
    private var stats: JSONObject? = null
    private var biz: JSONObject? = null
    private var ads = JSONArray()
    private var loading = false
    private var error = ""
    private val images = HashMap<String, Bitmap>()
    private val imageLoading = HashSet<String>()

    private var onPicked: ((String, Bitmap) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val cb = onPicked
        if (uri != null && cb != null) {
            scope.launch {
                val res = withContext(Dispatchers.IO) { compress(uri) }
                if (res == null) toast("Image illisible") else cb(res.first, res.second)
            }
        }
    }

    private val presets = listOf(
        "#4F46E5" to "#7C3AED",
        "#059669" to "#10B981",
        "#128C7E" to "#25D366",
        "#EA580C" to "#F59E0B",
        "#DC2626" to "#F43F5E",
        "#0284C7" to "#2563EB",
        "#B45309" to "#EAB308",
        "#DB2777" to "#9333EA",
        "#111827" to "#374151",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(16), dp(16), dp(40))
        scroll.addView(content)
        setContentView(scroll)
        render()
        loadAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---------- utilitaires ----------

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun t(value: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(this, value, size, color, bold)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun n(v: Int): String = String.format(Locale.FRANCE, "%,d", v).replace(' ', ' ').replace(' ', ' ')

    private fun str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    private fun color(s: String, fallback: Int): Int = try {
        Color.parseColor(s)
    } catch (e: Exception) {
        fallback
    }

    private fun hex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

    private fun sectionTitle(value: String) {
        val v = t(value.uppercase(), 12f, Ui.TEXT2, true)
        v.letterSpacing = 0.08f
        v.setPadding(0, dp(24), 0, dp(8))
        content.addView(v)
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, 0, 0, dp(12))
        c.layoutParams = lp
        return c
    }

    private suspend fun api(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val token = SessionStorage.getToken(this@KounadiaAdminActivity) ?: throw ApiException(401, "Session expirée, reconnecte-toi.")
        val base = ConfigStorage.getApiBaseUrl(this@KounadiaAdminActivity)
        ApiClient.request(base, method, path, token, body)
    }

    private fun unwrapObj(text: String): JSONObject {
        val o = JSONObject(text)
        return o.optJSONObject("data") ?: o
    }

    private fun unwrapArr(text: String): JSONArray {
        val s = text.trim()
        if (s.startsWith("[")) return JSONArray(s)
        val o = JSONObject(s)
        return o.optJSONArray("ads") ?: o.optJSONArray("data") ?: o.optJSONArray("items") ?: JSONArray()
    }

    private fun idOf(text: String): String {
        return try {
            val o = JSONObject(text)
            val inner = o.optJSONObject("ad") ?: o.optJSONObject("data") ?: o
            str(inner, "id")
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- chargement ----------

    private fun loadAll() {
        if (loading) return
        loading = true
        error = ""
        render()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.warmUp(ConfigStorage.getApiBaseUrl(this@KounadiaAdminActivity)) }
                stats = unwrapObj(api("GET", "/admin/kounadia/stats?days=$days"))
                biz = try {
                    unwrapObj(api("GET", "/admin/kounadia/business"))
                } catch (e: Exception) {
                    null
                }
                ads = unwrapArr(api("GET", "/admin/kounadia/ads"))
                loadImages()
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
            }
            loading = false
            render()
        }
    }

    private fun reloadAds() {
        scope.launch {
            try {
                ads = unwrapArr(api("GET", "/admin/kounadia/ads"))
                loadImages()
            } catch (e: Exception) {
                toast(e.message ?: "Erreur réseau")
            }
            render()
        }
    }

    private fun loadImages() {
        for (i in 0 until ads.length()) {
            val url = str(ads.getJSONObject(i), "image_url")
            if (url.isEmpty() || images.containsKey(url) || !imageLoading.add(url)) continue
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { fetchBitmap(url) }
                imageLoading.remove(url)
                if (bmp != null) {
                    images[url] = bmp
                    render()
                }
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 20000
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        decodeScaled(bytes, 720)
    } catch (e: Exception) {
        null
    }

    private fun decodeScaled(bytes: ByteArray, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    /** Photo choisie : redressée, réduite à 1080 px de large, JPEG, base64. */
    private fun compress(uri: Uri): Pair<String, Bitmap>? = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException()
        var bmp = decodeScaled(bytes, 1080) ?: throw IllegalStateException()
        val ori = try {
            android.media.ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        } catch (e: Exception) {
            1
        }
        val deg = when (ori) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix()
            m.postRotate(deg)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (bmp.width > 1080) {
            bmp = Bitmap.createScaledBitmap(bmp, 1080, bmp.height * 1080 / bmp.width, true)
        }
        var q = 85
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            out = bos.toByteArray()
            q -= 10
        } while (out.size > 1_200_000 && q > 40)
        Pair(Base64.encodeToString(out, Base64.NO_WRAP), bmp)
    } catch (e: Exception) {
        null
    }

    // ---------- écran ----------

    private fun render() {
        content.removeAllViews()

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = t("←", 24f, Ui.TEXT, true)
        back.setPadding(0, 0, dp(14), 0)
        Ui.pressable(back) { finish() }
        top.addView(back)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(t("KOUNADIA", 24f, Ui.TEXT, true))
        col.addView(t(if (loading) "Chargement…" else "Statistiques et publicités de l'application", 13f, Ui.TEXT2))
        top.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        val refresh = ImageView(this)
        refresh.setImageResource(R.drawable.ic_refresh)
        refresh.setColorFilter(Ui.TEXT2)
        refresh.setPadding(dp(10), dp(10), dp(10), dp(10))
        Ui.pressable(refresh) { loadAll() }
        top.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(top)

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, dp(16), 0, dp(4))
        for ((d, label) in listOf(1 to "24 h", 7 to "7 jours", 30 to "30 jours", 90 to "90 jours")) {
            val on = d == days
            val c = t(label, 13f, if (on) Color.WHITE else Ui.TEXT2, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(9), 0, dp(9))
            c.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.SURFACE, 12, if (on) null else Ui.BORDER)
            val lp = LinearLayout.LayoutParams(0, KW, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            c.setOnClickListener {
                if (days != d && !loading) {
                    days = d
                    loadAll()
                }
            }
            chips.addView(c, lp)
        }
        content.addView(chips)

        if (error.isNotEmpty()) {
            val e = card()
            e.background = Ui.rounded(this, Ui.withAlpha(Ui.ERROR, 0x1F), 18, Ui.withAlpha(Ui.ERROR, 0x55))
            e.addView(t(error, 13f, Ui.ERROR, true))
            val lp = e.layoutParams as LinearLayout.LayoutParams
            lp.setMargins(0, dp(12), 0, 0)
            content.addView(e)
        }

        val s = stats
        biz?.let { renderBusiness(it) }
        if (s != null) renderStats(s)
        renderAds(s)
    }

    private fun kpi(label: String, value: String, sub: String, accent: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.background = Ui.rounded(this, Ui.SURFACE, 16, Ui.BORDER)
        val l = t(label.uppercase(), 10f, Ui.TEXT2, true)
        l.letterSpacing = 0.08f
        box.addView(l)
        val v = t(value, 24f, accent, true)
        v.setPadding(0, dp(4), 0, 0)
        box.addView(v)
        box.addView(t(sub, 11f, Ui.TEXT2))
        return box
    }

    private fun pair(a: View, b: View): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val la = LinearLayout.LayoutParams(0, KM, 1f)
        la.setMargins(0, 0, dp(6), 0)
        val lb = LinearLayout.LayoutParams(0, KM, 1f)
        lb.setMargins(dp(6), 0, 0, 0)
        r.addView(a, la)
        r.addView(b, lb)
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, dp(12), 0, 0)
        r.layoutParams = lp
        return r
    }

    private fun featureLabel(name: String): String = when (name) {
        "app_open" -> "Ouvertures de l'application"
        "tab_view" -> "Changements d'onglet"
        "match_open" -> "Fiches match ouvertes"
        "coupon_generate" -> "Coupons générés"
        "coupon_save" -> "Coupons enregistrés"
        "coupon_share" -> "Coupons partagés"
        "settings_open" -> "Rubriques des paramètres"
        "knd_menu" -> "Menu 1xBet ouvert"
        "deposit_whatsapp" -> "Demandes de dépôt (WhatsApp)"
        "withdraw_whatsapp" -> "Demandes de retrait (WhatsApp)"
        "ad_view" -> "Publicités vues"
        "ad_click" -> "Clics sur une publicité"
        else -> name
    }

    private fun rankCard(rows: List<Triple<String, Int, String>>): View {
        val c = card()
        val max = (rows.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        rows.forEachIndexed { i, (label, value, sub) ->
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(0, if (i == 0) 0 else dp(10), 0, dp(4))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.addView(t(label, 14f, Ui.TEXT, true))
            if (sub.isNotEmpty()) left.addView(t(sub, 11f, Ui.TEXT2))
            line.addView(left, LinearLayout.LayoutParams(0, KW, 1f))
            line.addView(t(n(value), 15f, Ui.PRIMARY, true))
            c.addView(line)

            val track = FrameLayout(this)
            track.background = Ui.rounded(this, Ui.ELEVATED, 4)
            val fill = View(this)
            fill.background = Ui.rounded(this, Ui.withAlpha(Ui.PRIMARY, 0xCC), 4)
            val ratio = value.toFloat() / max
            track.addView(fill, FrameLayout.LayoutParams(KM, KM))
            track.post {
                val lp = fill.layoutParams
                lp.width = (track.width * ratio).toInt().coerceAtLeast(dp(4))
                fill.layoutParams = lp
            }
            c.addView(track, LinearLayout.LayoutParams(KM, dp(5)))
        }
        return c
    }

    private fun renderStats(s: JSONObject) {
        val period = if (days == 1) "dernières 24 h" else "$days derniers jours"
        content.addView(
            pair(
                kpi("Utilisateurs", n(s.optInt("users")), period, Ui.TEXT),
                kpi("Actifs aujourd'hui", n(s.optInt("usersToday")), "téléphones différents", Ui.SUCCESS),
            ),
        )
        content.addView(
            pair(
                kpi("Nouveaux", n(s.optInt("newUsers")), "première ouverture", Ui.PRIMARY),
                kpi("Actions", n(s.optInt("events")), period, Ui.WARNING),
            ),
        )

        val daily = s.optJSONArray("daily") ?: JSONArray()
        if (daily.length() > 0) {
            sectionTitle("Utilisateurs par jour")
            val c = card()
            val labels = ArrayList<String>()
            val values = ArrayList<Int>()
            for (i in 0 until daily.length()) {
                val o = daily.getJSONObject(i)
                val d = str(o, "day")
                labels.add(if (d.length >= 10) d.substring(8, 10) + "/" + d.substring(5, 7) else d)
                values.add(o.optInt("users"))
            }
            c.addView(KndDailyChart(this, labels, values), LinearLayout.LayoutParams(KM, dp(150)))
            content.addView(c)
        }

        val features = s.optJSONArray("features") ?: JSONArray()
        if (features.length() > 0) {
            sectionTitle("Ce qui intéresse les utilisateurs")
            val rows = ArrayList<Triple<String, Int, String>>()
            for (i in 0 until features.length()) {
                val o = features.getJSONObject(i)
                val users = o.optInt("users")
                rows.add(Triple(featureLabel(str(o, "name")), o.optInt("count"), "$users utilisateur" + if (users > 1) "s" else ""))
            }
            rows.sortByDescending { it.second }
            content.addView(rankCard(rows))
        }

        val tabs = s.optJSONArray("tabs") ?: JSONArray()
        if (tabs.length() > 0) {
            sectionTitle("Onglets les plus ouverts")
            val rows = ArrayList<Triple<String, Int, String>>()
            for (i in 0 until tabs.length()) {
                val o = tabs.getJSONObject(i)
                rows.add(Triple(str(o, "tab").ifEmpty { "?" }, o.optInt("count"), ""))
            }
            rows.sortByDescending { it.second }
            content.addView(rankCard(rows))
        }

        if (s.optInt("events") == 0) {
            val c = card()
            c.addView(t("Aucune donnée sur cette période. Les statistiques arrivent dès que des utilisateurs ouvrent la nouvelle version de l'application.", 13f, Ui.TEXT2))
            content.addView(c)
        }
    }

    // ---------- chiffre d'affaires ----------

    private fun f(v: Int): String = n(v) + " F"

    private fun planName(p: String): String = when (p) {
        "day" -> "3 jours"
        "week" -> "Semaine"
        "month" -> "Mois"
        "year" -> "Année"
        else -> p
    }

    private fun renderBusiness(b: JSONObject) {
        val key = when (days) {
            1 -> "today"
            7 -> "week"
            30 -> "month"
            else -> "all"
        }
        val label = when (days) {
            1 -> "aujourd'hui"
            7 -> "7 derniers jours"
            30 -> "30 derniers jours"
            else -> "depuis le début"
        }
        val p = b.optJSONObject(key) ?: JSONObject()
        sectionTitle("Chiffre d'affaires · $label")
        content.addView(
            pair(
                kpi("Encaissé (net)", f(p.optInt("net")), "après frais MoneyFusion", Ui.SUCCESS),
                kpi("Paiements", n(p.optInt("n")), "abonnements payés", Ui.TEXT),
            ),
        )
        val gross = p.optInt("gross")
        val fee = p.optInt("fee")
        val rate = if (gross > 0) String.format(Locale.FRANCE, "%.1f %%", fee * 100.0 / gross) else "–"
        content.addView(
            pair(
                kpi("Brut", f(gross), "payé par les clients", Ui.PRIMARY),
                kpi("Frais", f(fee), "soit $rate", Ui.WARNING),
            ),
        )
        val users = b.optInt("users")
        val payers = b.optInt("payers")
        val conv = if (users > 0) String.format(Locale.FRANCE, "%.1f %%", payers * 100.0 / users) else "–"
        content.addView(
            pair(
                kpi("Abonnés actifs", n(b.optInt("active")), "${n(b.optInt("expiring"))} expirent sous 3 j", Ui.SUCCESS),
                kpi("Comptes", n(users), "+${n(b.optInt("newUsers7"))} cette semaine · conv. $conv", Ui.TEXT),
            ),
        )
        val plans = b.optJSONArray("plans") ?: JSONArray()
        if (plans.length() > 0) {
            sectionTitle("Offres vendues · 30 jours")
            val rows = ArrayList<Triple<String, Int, String>>()
            for (i in 0 until plans.length()) {
                val o = plans.getJSONObject(i)
                rows.add(Triple(planName(str(o, "plan")), o.optInt("n"), f(o.optInt("gross"))))
            }
            content.addView(rankCard(rows))
        }
        val recent = b.optJSONArray("recent") ?: JSONArray()
        if (recent.length() > 0) {
            sectionTitle("Derniers paiements")
            val c = card()
            for (i in 0 until recent.length()) {
                val o = recent.getJSONObject(i)
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                line.setPadding(0, if (i == 0) 0 else dp(8), 0, 0)
                val left = LinearLayout(this)
                left.orientation = LinearLayout.VERTICAL
                left.addView(t("${str(o, "username")} · ••${str(o, "phone4")}", 13.5f, Ui.TEXT, true))
                left.addView(t("${planName(str(o, "plan"))} · ${str(o, "paid_at").take(16).replace("T", " ")}", 11.5f, Ui.TEXT2))
                line.addView(left, LinearLayout.LayoutParams(0, KW, 1f))
                line.addView(t(f(o.optInt("amount")), 14f, Ui.SUCCESS, true))
                c.addView(line)
            }
            content.addView(c)
        }
        if (b.optInt("pending") > 0) {
            val c = card()
            c.addView(t("${b.optInt("pending")} paiement(s) commencé(s) mais non finalisé(s) depuis 24 h.", 12.5f, Ui.TEXT2))
            content.addView(c)
        }
    }

    // ---------- publicités ----------

    private fun adStats(s: JSONObject?): Map<String, Pair<Int, Int>> {
        val m = HashMap<String, Pair<Int, Int>>()
        val arr = s?.optJSONArray("ads") ?: return m
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            m[str(o, "ad")] = Pair(o.optInt("views"), o.optInt("clicks"))
        }
        return m
    }

    private fun renderAds(s: JSONObject?) {
        sectionTitle("Publicités")
        val add = Ui.button(this, "+  Nouvelle publicité") { openEditor(null) }
        (add.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(12))
        content.addView(add)

        if (ads.length() == 0) {
            val c = card()
            c.addView(t(if (loading) "Chargement…" else "Aucune publicité. Sans publicité active, la bannière est masquée dans l'application.", 13f, Ui.TEXT2))
            content.addView(c)
            return
        }
        val st = adStats(s)
        for (i in 0 until ads.length()) {
            content.addView(adCard(ads.getJSONObject(i), st))
        }
    }

    private fun adCard(ad: JSONObject, st: Map<String, Pair<Int, Int>>): View {
        val id = str(ad, "id")
        val active = ad.optBoolean("active", true)
        val c = card()

        val url = str(ad, "image_url")
        val prev = adPreview(
            str(ad, "title"), str(ad, "text"), str(ad, "cta"), str(ad, "link").isNotEmpty(),
            color(str(ad, "color1"), 0xFF4F46E5.toInt()), color(str(ad, "color2"), 0xFF7C3AED.toInt()),
            if (url.isEmpty()) null else images[url],
        )
        if (!active) prev.alpha = 0.45f
        c.addView(prev)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        info.setPadding(0, dp(12), 0, 0)
        info.addView(Ui.pill(this, if (active) "Active" else "Désactivée", if (active) Ui.SUCCESS else Ui.TEXT2))
        val pos = t("  Position ${ad.optInt("position")}", 12f, Ui.TEXT2, true)
        info.addView(pos, LinearLayout.LayoutParams(0, KW, 1f))
        val (views, clicks) = st[id] ?: Pair(0, 0)
        val ctr = if (views > 0) String.format(Locale.FRANCE, "%.1f %%", clicks * 100.0 / views) else "–"
        info.addView(t("${n(views)} vues · ${n(clicks)} clics · $ctr", 12f, Ui.TEXT, true))
        c.addView(info)

        val link = str(ad, "link")
        if (link.isNotEmpty()) {
            val l = t(if (link.startsWith("https://wa.me/")) "WhatsApp : +" + link.removePrefix("https://wa.me/").substringBefore("?") else link, 12f, Ui.TEXT2)
            l.maxLines = 1
            l.ellipsize = TextUtils.TruncateAt.END
            l.setPadding(0, dp(8), 0, 0)
            c.addView(l)
        }

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(12), 0, 0)
        actions.addView(smallButton("Modifier", Ui.TEXT) { openEditor(ad) }, weightLp(0))
        actions.addView(smallButton(if (active) "Désactiver" else "Activer", if (active) Ui.WARNING else Ui.SUCCESS) { toggleAd(id, !active) }, weightLp(8))
        actions.addView(smallButton("Supprimer", Ui.ERROR) { confirmDelete(id, str(ad, "title")) }, weightLp(8))
        c.addView(actions)
        return c
    }

    private fun weightLp(left: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, KW, 1f)
        lp.setMargins(dp(left), 0, 0, 0)
        return lp
    }

    private fun smallButton(label: String, color: Int, onClick: () -> Unit): TextView {
        val b = t(label, 13f, color, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(11), dp(8), dp(11))
        b.background = Ui.rounded(this, Ui.ELEVATED, 12, Ui.BORDER)
        Ui.pressable(b, onClick)
        return b
    }

    private fun toggleAd(id: String, active: Boolean) {
        scope.launch {
            try {
                api("PATCH", "/admin/kounadia/ads/$id", JSONObject().put("active", active))
                toast(if (active) "Publicité activée" else "Publicité désactivée")
            } catch (e: Exception) {
                toast(e.message ?: "Erreur")
            }
            reloadAds()
        }
    }

    private fun confirmDelete(id: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer la publicité ?")
            .setMessage("« $title » et sa photo seront supprimées définitivement.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                scope.launch {
                    try {
                        api("DELETE", "/admin/kounadia/ads/$id")
                        toast("Publicité supprimée")
                    } catch (e: Exception) {
                        toast(e.message ?: "Erreur")
                    }
                    reloadAds()
                }
            }
            .show()
    }

    /** Aperçu identique à la bannière de l'application KOUNADIA. */
    private fun adPreview(title: String, text: String, cta: String, hasLink: Boolean, c1: Int, c2: Int, bmp: Bitmap?): FrameLayout {
        val f = FrameLayout(this)
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
        g.cornerRadius = dp(18).toFloat()
        f.background = g
        f.clipToOutline = true

        if (bmp != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
            f.addView(iv, FrameLayout.LayoutParams(KM, KM))
            val ov = View(this)
            ov.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.argb(40, 0, 0, 0)),
            )
            f.addView(ov, FrameLayout.LayoutParams(KM, KM))
        } else {
            val b1 = View(this)
            b1.background = Ui.circle(Color.argb(31, 255, 255, 255))
            val l1 = FrameLayout.LayoutParams(dp(120), dp(120), Gravity.END or Gravity.TOP)
            l1.setMargins(0, -dp(40), -dp(30), 0)
            f.addView(b1, l1)
            val b2 = View(this)
            b2.background = Ui.circle(Color.argb(20, 255, 255, 255))
            val l2 = FrameLayout.LayoutParams(dp(90), dp(90), Gravity.END or Gravity.BOTTOM)
            l2.setMargins(0, 0, dp(60), -dp(50))
            f.addView(b2, l2)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(10), dp(12))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sp = t("SPONSORISÉ", 9f, Color.argb(180, 255, 255, 255), true)
        sp.letterSpacing = 0.12f
        col.addView(sp)
        val tt = t(title.ifEmpty { "Titre de la publicité" }, 15f, Color.WHITE, true)
        tt.maxLines = 1
        tt.ellipsize = TextUtils.TruncateAt.END
        col.addView(tt)
        if (text.isNotEmpty()) {
            val tx = t(text, 12f, Color.argb(230, 255, 255, 255))
            tx.maxLines = 2
            tx.ellipsize = TextUtils.TruncateAt.END
            col.addView(tx)
        }
        row.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        if (cta.isNotEmpty() && hasLink) {
            val c = t("$cta  →", 11f, c1, true)
            c.maxLines = 1
            c.ellipsize = TextUtils.TruncateAt.END
            c.maxWidth = dp(110)
            c.setPadding(dp(10), dp(6), dp(10), dp(6))
            c.background = Ui.rounded(this, Color.WHITE, 20)
            val lp = LinearLayout.LayoutParams(KW, KW)
            lp.setMargins(dp(8), 0, 0, 0)
            row.addView(c, lp)
        }
        f.addView(row, FrameLayout.LayoutParams(KM, KM))
        f.layoutParams = LinearLayout.LayoutParams(KM, dp(100))
        return f
    }

    // ---------- éditeur ----------

    private fun label(box: LinearLayout, value: String) {
        val v = t(value.uppercase(), 11f, Ui.TEXT2, true)
        v.letterSpacing = 0.08f
        v.setPadding(0, dp(12), 0, dp(6))
        box.addView(v)
    }

    private fun field(box: LinearLayout, hint: String, value: String, max: Int, multi: Boolean = false, type: Int = -1): EditText {
        val e = Ui.input(this, hint)
        e.setText(value)
        e.filters = arrayOf(InputFilter.LengthFilter(max))
        if (multi) {
            e.isSingleLine = false
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.maxLines = 3
        }
        if (type >= 0) e.inputType = type
        box.addView(e)
        return e
    }

    private fun onChange(e: EditText, action: () -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = action()
        })
    }

    private fun chipRow(box: LinearLayout, options: List<Pair<String, String>>, selected: () -> String, onSelect: (String) -> Unit): () -> Unit {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<Pair<String, TextView>>()
        for ((key, text) in options) {
            val c = t(text, 13f, Ui.TEXT2, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(10), 0, dp(10))
            c.setOnClickListener { onSelect(key) }
            row.addView(c, weightLp(if (views.isEmpty()) 0 else 6))
            views.add(key to c)
        }
        val refresh = {
            for ((key, v) in views) {
                val on = key == selected()
                v.setTextColor(if (on) Color.WHITE else Ui.TEXT2)
                v.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.ELEVATED, 12, if (on) null else Ui.BORDER)
            }
        }
        refresh()
        box.addView(row)
        return refresh
    }

    private fun openEditor(ad: JSONObject?) {
        val (dialog, box) = Ui.bottomSheet(this)
        val adId = if (ad == null) "" else str(ad, "id")
        val oldUrl = if (ad == null) "" else str(ad, "image_url")

        var c1 = color(if (ad == null) presets[0].first else str(ad, "color1"), 0xFF4F46E5.toInt())
        var c2 = color(if (ad == null) presets[0].second else str(ad, "color2"), 0xFF7C3AED.toInt())
        var active = ad?.optBoolean("active", true) ?: true
        var bmp: Bitmap? = if (oldUrl.isEmpty()) null else images[oldUrl]
        var pendingB64: String? = null
        var removeImage = false

        val oldLink = if (ad == null) "" else str(ad, "link")
        var linkMode = when {
            ad == null -> "wa"
            oldLink.isEmpty() -> "none"
            oldLink.startsWith("https://wa.me/") -> "wa"
            else -> "web"
        }
        var waNumber = ""
        var waText = ""
        if (oldLink.startsWith("https://wa.me/")) {
            val rest = oldLink.removePrefix("https://wa.me/")
            waNumber = "+" + rest.substringBefore("?")
            val q = rest.substringAfter("?text=", "")
            waText = try {
                URLDecoder.decode(q, "UTF-8")
            } catch (e: Exception) {
                q
            }
        }

        box.addView(t(if (ad == null) "Nouvelle publicité" else "Modifier la publicité", 20f, Ui.TEXT, true))
        box.addView(t("Aperçu en direct, tel qu'il s'affichera dans KOUNADIA.", 13f, Ui.TEXT2))

        val previewHolder = FrameLayout(this)
        val ph = LinearLayout.LayoutParams(KM, dp(100))
        ph.setMargins(0, dp(14), 0, dp(4))
        box.addView(previewHolder, ph)

        label(box, "Titre (60 caractères max.)")
        val title = field(box, "Ex. : Dépôt 1xBet en 2 minutes", if (ad == null) "" else str(ad, "title"), 60)
        label(box, "Texte (160 caractères max.)")
        val text = field(box, "Ex. : Code promo 99KND, service 7j/7", if (ad == null) "" else str(ad, "text"), 160, multi = true)

        label(box, "Lien du bouton")
        val waBox = LinearLayout(this)
        waBox.orientation = LinearLayout.VERTICAL
        val webBox = LinearLayout(this)
        webBox.orientation = LinearLayout.VERTICAL
        lateinit var refreshPreview: () -> Unit
        lateinit var refreshMode: () -> Unit
        refreshMode = chipRow(box, listOf("wa" to "WhatsApp", "web" to "Site web", "none" to "Aucun"), { linkMode }) {
            linkMode = it
            refreshMode()
            waBox.visibility = if (it == "wa") View.VISIBLE else View.GONE
            webBox.visibility = if (it == "web") View.VISIBLE else View.GONE
            refreshPreview()
        }
        val spacer = View(this)
        box.addView(spacer, LinearLayout.LayoutParams(KM, dp(10)))
        val waNum = field(waBox, "Numéro WhatsApp, ex. +226 55 33 77 82", waNumber.ifEmpty { "+226 " }, 20, type = InputType.TYPE_CLASS_PHONE)
        val waMsg = field(waBox, "Message pré-rempli (facultatif)", waText, 200)
        box.addView(waBox)
        val web = field(webBox, "https://…", if (linkMode == "web") oldLink else "https://", 500, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        box.addView(webBox)
        waBox.visibility = if (linkMode == "wa") View.VISIBLE else View.GONE
        webBox.visibility = if (linkMode == "web") View.VISIBLE else View.GONE

        label(box, "Texte du bouton (20 max.)")
        val cta = field(box, "Ex. : Écrire, Découvrir", if (ad == null) "Écrire" else str(ad, "cta"), 20)

        label(box, "Couleur de fond")
        val swatches = LinearLayout(this)
        swatches.orientation = LinearLayout.HORIZONTAL
        val swatchViews = ArrayList<Pair<Pair<String, String>, View>>()
        val refreshSwatches = {
            for ((p, v) in swatchViews) {
                val on = color(p.first, 0) == c1 && color(p.second, 0) == c2
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color(p.first, 0), color(p.second, 0)))
                g.shape = GradientDrawable.OVAL
                if (on) g.setStroke(dp(3), Color.WHITE)
                v.background = g
            }
        }
        for (p in presets) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(0, dp(30), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            v.setOnClickListener {
                c1 = color(p.first, c1)
                c2 = color(p.second, c2)
                refreshSwatches()
                refreshPreview()
            }
            swatches.addView(v, lp)
            swatchViews.add(p to v)
        }
        refreshSwatches()
        box.addView(swatches)

        label(box, "Photo (facultatif)")
        box.addView(t("Format large conseillé (environ 1080 × 300). Un voile sombre garde le texte lisible.", 12f, Ui.TEXT2))
        val photoRow = LinearLayout(this)
        photoRow.orientation = LinearLayout.HORIZONTAL
        photoRow.setPadding(0, dp(8), 0, 0)
        val removeBtn = smallButton("Retirer la photo", Ui.ERROR) {
            bmp = null
            pendingB64 = null
            removeImage = true
            refreshPreview()
        }
        photoRow.addView(
            smallButton("Choisir une photo", Ui.PRIMARY) {
                onPicked = { b64, b ->
                    pendingB64 = b64
                    bmp = b
                    removeImage = false
                    refreshPreview()
                }
                picker.launch("image/*")
            },
            weightLp(0),
        )
        photoRow.addView(removeBtn, weightLp(8))
        box.addView(photoRow)

        label(box, "Ordre d'affichage")
        val position = field(
            box, "1 = en premier",
            (if (ad == null) ads.length() + 1 else ad.optInt("position", 1)).toString(), 3,
            type = InputType.TYPE_CLASS_NUMBER,
        )

        label(box, "État")
        lateinit var refreshActive: () -> Unit
        refreshActive = chipRow(box, listOf("on" to "Active", "off" to "Désactivée"), { if (active) "on" else "off" }) {
            active = it == "on"
            refreshActive()
        }

        val status = t("", 13f, Ui.ERROR, true)
        status.setPadding(0, dp(12), 0, 0)
        box.addView(status)

        fun currentLink(): String = when (linkMode) {
            "wa" -> {
                val digits = waNum.text.toString().filter { it.isDigit() }
                if (digits.length < 8) "" else {
                    val msg = waMsg.text.toString().trim()
                    "https://wa.me/$digits" + if (msg.isEmpty()) "" else "?text=" + URLEncoder.encode(msg, "UTF-8").replace("+", "%20")
                }
            }
            "web" -> web.text.toString().trim().let { if (it == "https://" || it == "http://") "" else it }
            else -> ""
        }

        refreshPreview = {
            removeBtn.visibility = if (bmp != null) View.VISIBLE else View.GONE
            previewHolder.removeAllViews()
            val p = adPreview(
                title.text.toString().trim(), text.text.toString().trim(), cta.text.toString().trim(),
                currentLink().isNotEmpty(), c1, c2, bmp,
            )
            p.layoutParams = FrameLayout.LayoutParams(KM, KM)
            if (!active) p.alpha = 0.6f
            previewHolder.addView(p)
        }
        for (e in listOf(title, text, cta, waNum, waMsg, web)) onChange(e) { refreshPreview() }
        refreshPreview()

        box.addView(
            Ui.button(this, if (ad == null) "Publier" else "Enregistrer") {
                val ttl = title.text.toString().trim()
                val link = currentLink()
                status.text = ""
                if (ttl.isEmpty()) {
                    status.text = "Le titre est obligatoire."
                    return@button
                }
                if (linkMode == "wa" && link.isEmpty()) {
                    status.text = "Numéro WhatsApp invalide (indicatif + numéro)."
                    return@button
                }
                if (linkMode == "web" && !(link.startsWith("http://") || link.startsWith("https://"))) {
                    status.text = "Le lien doit commencer par https://"
                    return@button
                }
                val body = JSONObject()
                    .put("title", ttl)
                    .put("text", text.text.toString().trim())
                    .put("cta", cta.text.toString().trim())
                    .put("link", link)
                    .put("color1", hex(c1))
                    .put("color2", hex(c2))
                    .put("active", active)
                    .put("position", position.text.toString().toIntOrNull() ?: 1)
                status.setTextColor(Ui.TEXT2)
                status.text = "Enregistrement…"
                scope.launch {
                    try {
                        var id = adId
                        if (id.isEmpty()) {
                            id = idOf(api("POST", "/admin/kounadia/ads", body))
                        } else {
                            api("PATCH", "/admin/kounadia/ads/$id", body)
                        }
                        val b64 = pendingB64
                        if (b64 != null && id.isNotEmpty()) {
                            status.text = "Envoi de la photo…"
                            api("POST", "/admin/kounadia/ads/$id/image", JSONObject().put("base64", b64).put("contentType", "image/jpeg"))
                        } else if (removeImage && oldUrl.isNotEmpty() && id.isNotEmpty()) {
                            api("DELETE", "/admin/kounadia/ads/$id/image")
                        }
                        dialog.dismiss()
                        toast("Publicité enregistrée. Visible dans l'application sous 15 min.")
                        reloadAds()
                    } catch (e: Exception) {
                        status.setTextColor(Ui.ERROR)
                        status.text = e.message ?: "Erreur réseau"
                    }
                }
            },
        )
        dialog.show()
    }
}

/** Histogramme simple des utilisateurs par jour. */
private class KndDailyChart(context: Context, private val labels: List<String>, private val values: List<Int>) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.PRIMARY }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.withAlpha(Ui.PRIMARY, 0x66) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT2
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, context.resources.displayMetrics)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width == 0) return
        val labelH = 18 * d
        val topPad = 16 * d
        val h = height - labelH - topPad
        val slot = width.toFloat() / values.size
        val bw = (slot * 0.62f).coerceAtMost(28 * d)
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        for (i in values.indices) {
            val bh = ((values[i].toFloat() / max) * h).coerceAtLeast(2 * d)
            val x = i * slot + (slot - bw) / 2
            canvas.drawRoundRect(RectF(x, topPad + h - bh, x + bw, topPad + h), 4 * d, 4 * d, if (i == values.size - 1) strong else soft)
        }
        txt.textAlign = Paint.Align.LEFT
        canvas.drawText("max $max / jour", 0f, 11 * d, txt)
        canvas.drawText(labels.first(), 0f, height - 4 * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        canvas.drawText(labels.last(), width.toFloat(), height - 4 * d, txt)
    }
}
