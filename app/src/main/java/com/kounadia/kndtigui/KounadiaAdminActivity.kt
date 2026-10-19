package com.kounadia.kndtigui

import android.content.Context
import android.content.Intent
import android.view.inputmethod.EditorInfo
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val KM = ViewGroup.LayoutParams.MATCH_PARENT
private const val KW = ViewGroup.LayoutParams.WRAP_CONTENT

/** Onglet KOUNADIA : statistiques d'usage de l'application et gestion des publicités. */
class KounadiaAdminActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var days = 7
    private var stats: JSONObject? = null
    private var biz: JSONObject? = null
    private var yearly: JSONObject? = null
    private var admin: JSONObject? = null
    private var tab = 0
    private var subs = JSONArray()
    private var subsMode = "active"
    private var subsQuery = ""
    private var subsLoading = false
    private var subsLoaded = false
    private var csvPending: String? = null
    private val csvSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val csv = csvPending
        csvPending = null
        if (uri != null && csv != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + csv).toByteArray(Charsets.UTF_8)) }
                toast("Export enregistré")
            } catch (e: Exception) {
                toast("Enregistrement impossible")
            }
        }
    }
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
                stats = try {
                    unwrapObj(api("GET", "/admin/kounadia/stats2?days=$days"))
                } catch (e: Exception) {
                    unwrapObj(api("GET", "/admin/kounadia/stats?days=${if (days == 0) 90 else days}"))
                }
                yearly = try {
                    unwrapObj(api("GET", "/admin/kounadia/yearly"))
                } catch (e: Exception) {
                    null
                }
                admin = try {
                    unwrapObj(api("GET", "/admin/kounadia/overview?days=$days"))
                } catch (e: Exception) {
                    null
                }
                if (tab == 3) subsLoaded = false
                biz = try {
                    unwrapObj(api("GET", "/admin/kounadia/business"))
                } catch (e: Exception) {
                    null
                }
                ads = fetchAds()
                loadImages()
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
            }
            loading = false
            render()
        }
    }

    /** Nettoie (pubs expirées + photos orphelines) puis charge la liste complète. */
    private suspend fun fetchAds(): JSONArray {
        try {
            api("POST", "/admin/kounadia/ads/sweep", JSONObject())
        } catch (e: Exception) {
        }
        return try {
            unwrapArr(api("GET", "/admin/kounadia/ads-full"))
        } catch (e: Exception) {
            unwrapArr(api("GET", "/admin/kounadia/ads"))
        }
    }

    /** Pages d'une pub : photo (url) ou texte (titre + texte). */
    private fun pagesOf(ad: JSONObject): List<AdPage> {
        val out = ArrayList<AdPage>()
        val arr = ad.optJSONArray("pages")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            if (o is JSONObject) {
                val ti = str(o, "title")
                val tx = str(o, "text")
                if (ti.isNotEmpty() || tx.isNotEmpty()) out.add(AdPage(null, ti, tx))
            } else {
                val u = arr.optString(i, "")
                if (u.startsWith("http")) out.add(AdPage(u, "", ""))
            }
        }
        if (out.isEmpty()) {
            val img = str(ad, "image_url")
            if (img.startsWith("http")) out.add(AdPage(img, "", ""))
            else if (str(ad, "title").isNotEmpty()) out.add(AdPage(null, str(ad, "title"), str(ad, "text")))
        }
        return out.take(5)
    }

    private fun endsOf(ad: JSONObject): Date? {
        val v = str(ad, "ends_at")
        if (v.isEmpty()) return null
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(v.take(19))
        } catch (e: Exception) {
            null
        }
    }

    private fun isoUtc(d: Date): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(d)
    }

    private fun dayLabel(d: Date): String = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(d)

    private fun reloadAds() {
        scope.launch {
            try {
                ads = fetchAds()
                loadImages()
            } catch (e: Exception) {
                toast(e.message ?: "Erreur réseau")
            }
            render()
        }
    }

    private fun loadImages() {
        val urls = LinkedHashSet<String>()
        for (i in 0 until ads.length()) urls.addAll(pagesOf(ads.getJSONObject(i)).mapNotNull { it.url })
        for (url in urls) {
            if (images.containsKey(url) || !imageLoading.add(url)) continue
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
        // Recadrage au centre au format de la carte publicitaire (2:1).
        val targetH = bmp.width / 2
        if (bmp.height > targetH) {
            bmp = Bitmap.createBitmap(bmp, 0, (bmp.height - targetH) / 2, bmp.width, targetH)
        } else if (bmp.height < targetH) {
            val w = bmp.height * 2
            bmp = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, 0, w, bmp.height)
        }
        if (bmp.width > 1080) {
            bmp = Bitmap.createScaledBitmap(bmp, 1080, 540, true)
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
        col.addView(t(if (loading) "Mise à jour…" else "Administration de l'application", 13f, Ui.TEXT2))
        top.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        val refresh = ImageView(this)
        refresh.setImageResource(R.drawable.ic_refresh)
        refresh.setColorFilter(Ui.TEXT2)
        refresh.setPadding(dp(10), dp(10), dp(10), dp(10))
        Ui.pressable(refresh) { loadAll() }
        top.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(top)

        val tabsScroll = android.widget.HorizontalScrollView(this)
        tabsScroll.isHorizontalScrollBarEnabled = false
        val tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.setPadding(0, dp(16), 0, 0)
        for ((i, label) in listOf("Vue d'ensemble", "Revenus", "Utilisateurs", "Abonnés", "Publicités").withIndex()) {
            val on = i == tab
            val c = t(label, 13.5f, if (on) Color.WHITE else Ui.TEXT2, true)
            c.setPadding(dp(16), dp(10), dp(16), dp(10))
            c.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.SURFACE, 20, if (on) null else Ui.BORDER)
            val lp = LinearLayout.LayoutParams(KW, KW)
            lp.setMargins(0, 0, dp(8), 0)
            c.setOnClickListener {
                if (tab != i) {
                    tab = i
                    if (i == 3 && !subsLoaded) loadSubs() else render()
                }
            }
            tabsRow.addView(c, lp)
        }
        tabsScroll.addView(tabsRow)
        content.addView(tabsScroll)

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, dp(16), 0, dp(4))
        for ((d, label) in listOf(1 to "24 h", 7 to "7 j", 30 to "30 j", 90 to "90 j", 0 to "Tous")) {
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
        if (tab <= 2) content.addView(chips)

        if (error.isNotEmpty()) {
            val e = card()
            e.background = Ui.rounded(this, Ui.withAlpha(Ui.ERROR, 0x1F), 18, Ui.withAlpha(Ui.ERROR, 0x55))
            e.addView(t(error, 13f, Ui.ERROR, true))
            val lp = e.layoutParams as LinearLayout.LayoutParams
            lp.setMargins(0, dp(12), 0, 0)
            content.addView(e)
        }

        val s = stats
        when (tab) {
            0 -> renderOverview()
            1 -> {
                biz?.let { renderBusiness(it) }
                renderRevenueChart()
                renderExport()
                yearly?.let { renderYearly(it) }
            }
            2 -> if (s != null) renderStats(s) else if (loading) loadingCard()
            3 -> renderSubs()
            else -> renderAds(s)
        }
    }

    private fun loadingCard() {
        val c = card()
        c.addView(t("Chargement…", 13f, Ui.TEXT2))
        content.addView(c)
    }

    private fun periodLabel(): String = when (days) {
        0 -> "depuis le début"
        1 -> "aujourd'hui"
        else -> "$days derniers jours"
    }

    // ---------- vue d'ensemble ----------

    /** Indicateur + évolution par rapport à la période précédente. */
    private fun kpiTrend(label: String, value: String, cur: Double, prev: Double?, accent: Int, onClick: (() -> Unit)? = null): View {
        val (txt, col) = when {
            prev == null -> Pair(periodLabel(), Ui.TEXT2)
            prev == 0.0 && cur == 0.0 -> Pair("= stable", Ui.TEXT2)
            prev == 0.0 -> Pair("▲ nouveau", Ui.SUCCESS)
            else -> {
                val pct = (cur - prev) * 100.0 / prev
                when {
                    pct >= 0.5 -> Pair(String.format(Locale.FRANCE, "▲ %.0f %% vs avant", pct), Ui.SUCCESS)
                    pct <= -0.5 -> Pair(String.format(Locale.FRANCE, "▼ %.0f %% vs avant", -pct), Ui.ERROR)
                    else -> Pair("= stable", Ui.TEXT2)
                }
            }
        }
        val box = kpi(label, value, txt, accent) as LinearLayout
        (box.getChildAt(box.childCount - 1) as TextView).setTextColor(col)
        if (onClick != null) Ui.pressable(box, onClick)
        return box
    }

    private fun renderOverview() {
        val a = admin
        val b = biz
        if (a == null && b == null) {
            if (loading) loadingCard()
            return
        }
        val cur = a?.optJSONObject("cur") ?: JSONObject()
        val prev = a?.optJSONObject("prev")
        fun pv(k: String): Double? = prev?.optDouble(k, 0.0)
        sectionTitle("Vue d'ensemble · ${periodLabel()}")
        content.addView(
            pair(
                kpiTrend("Chiffre d'affaires", f(cur.optInt("net")), cur.optDouble("net", 0.0), pv("net"), Ui.SUCCESS) { tab = 1; render() },
                kpiTrend("Paiements", n(cur.optInt("payments")), cur.optDouble("payments", 0.0), pv("payments"), Ui.TEXT) { tab = 1; render() },
            ),
        )
        content.addView(
            pair(
                kpiTrend("Utilisateurs / jour", String.format(Locale.FRANCE, "%.1f", cur.optDouble("avgDaily", 0.0)).removeSuffix(",0"), cur.optDouble("avgDaily", 0.0), pv("avgDaily"), Ui.PRIMARY) { tab = 2; render() },
                kpiTrend("Nouveaux", n(cur.optInt("newUsers")), cur.optDouble("newUsers", 0.0), pv("newUsers"), Ui.WARNING) { tab = 2; render() },
            ),
        )
        if (b != null) {
            content.addView(
                pair(
                    kpiTrend("Abonnés actifs", n(b.optInt("active")), 0.0, null, Ui.SUCCESS) { openSubs("active") }.also {
                        ((it as LinearLayout).getChildAt(it.childCount - 1) as TextView).text = "en ce moment · voir la liste"
                    },
                    kpiTrend("À relancer", n(b.optInt("expiring")), 0.0, null, Ui.WARNING) { openSubs("expiring") }.also {
                        ((it as LinearLayout).getChildAt(it.childCount - 1) as TextView).text = "expirent sous 3 jours"
                    },
                ),
            )
            content.addView(
                pair(
                    kpiTrend("Comptes créés", n(cur.optInt("accounts")), cur.optDouble("accounts", 0.0), pv("accounts"), Ui.TEXT),
                    kpiTrend("Actions", n(cur.optInt("events")), cur.optDouble("events", 0.0), pv("events"), Ui.TEXT2),
                ),
            )
        }
        renderRevenueChart()
    }

    private fun renderRevenueChart() {
        val arr = admin?.optJSONArray("revenue") ?: return
        if (arr.length() == 0) return
        val monthly = admin?.optBoolean("monthly") == true
        sectionTitle(if (monthly) "Chiffre d'affaires net par mois" else "Chiffre d'affaires net par jour")
        val c = card()
        val labels = ArrayList<String>()
        val values = ArrayList<Int>()
        var total = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            labels.add(str(o, "label"))
            values.add(o.optInt("net"))
            total += o.optInt("net")
        }
        c.addView(t("Total sur la courbe : ${f(total)}", 12.5f, Ui.TEXT2, true))
        c.addView(KndDailyChart(this, labels, values, " F"), LinearLayout.LayoutParams(KM, dp(150)))
        content.addView(c)
    }

    private fun renderExport() {
        val b = Ui.button(this, "Exporter tous les paiements (Excel / CSV)", "secondary") {
            scope.launch {
                try {
                    val r = unwrapObj(api("GET", "/admin/kounadia/payments-csv"))
                    csvPending = str(r, "csv")
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    csvSaver.launch("kounadia-paiements-$day.csv")
                } catch (e: Exception) {
                    toast(e.message ?: "Erreur réseau")
                }
            }
        }
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, dp(12), 0, 0)
        content.addView(b, lp)
    }

    // ---------- abonnés ----------

    private fun openSubs(mode: String) {
        tab = 3
        subsMode = mode
        loadSubs()
    }

    private fun loadSubs() {
        subsLoading = true
        render()
        scope.launch {
            try {
                val q = URLEncoder.encode(subsQuery, "UTF-8")
                subs = unwrapArr(api("GET", "/admin/kounadia/subscribers?mode=$subsMode&q=$q"))
                subsLoaded = true
            } catch (e: Exception) {
                toast(e.message ?: "Erreur réseau")
            }
            subsLoading = false
            render()
        }
    }

    private fun parseIso(v: String): Date? {
        if (v.length < 19) return null
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(v.take(19))
        } catch (e: Exception) {
            null
        }
    }

    private fun renderSubs() {
        sectionTitle("Abonnés")
        val search = LinearLayout(this)
        search.orientation = LinearLayout.HORIZONTAL
        search.gravity = Gravity.CENTER_VERTICAL
        val q = Ui.input(this, "Nom ou e-mail")
        q.setText(subsQuery)
        q.isSingleLine = true
        q.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val go = {
            subsQuery = q.text.toString().trim()
            loadSubs()
        }
        q.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                go()
                true
            } else {
                false
            }
        }
        search.addView(q, LinearLayout.LayoutParams(0, KW, 1f))
        val btn = smallButton("Chercher", Ui.PRIMARY) { go() }
        val bl = LinearLayout.LayoutParams(KW, KW)
        bl.setMargins(dp(8), 0, 0, 0)
        search.addView(btn, bl)
        content.addView(search)

        val modes = LinearLayout(this)
        modes.orientation = LinearLayout.HORIZONTAL
        modes.setPadding(0, dp(10), 0, dp(4))
        for ((key, label) in listOf("active" to "Actifs", "expiring" to "À relancer", "expired" to "Expirés", "all" to "Tous")) {
            val on = key == subsMode
            val c = t(label, 12.5f, if (on) Color.WHITE else Ui.TEXT2, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(9), 0, dp(9))
            c.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.SURFACE, 12, if (on) null else Ui.BORDER)
            c.setOnClickListener {
                if (subsMode != key) {
                    subsMode = key
                    loadSubs()
                }
            }
            modes.addView(c, weightLp(if (key == "active") 0 else 6))
        }
        content.addView(modes)

        if (subsLoading && subs.length() == 0) {
            loadingCard()
            return
        }
        if (subs.length() == 0) {
            val c = card()
            c.addView(t(if (subsQuery.isEmpty()) "Personne dans cette liste pour le moment." else "Aucun résultat pour « $subsQuery ».", 13f, Ui.TEXT2))
            content.addView(c)
            return
        }
        content.addView(t("${subs.length()} personne(s)" + if (subs.length() >= 200) " (200 premières)" else "", 12f, Ui.TEXT2).also { it.setPadding(dp(4), dp(8), 0, dp(8)) })
        val now = System.currentTimeMillis()
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        for (i in 0 until subs.length()) {
            val o = subs.getJSONObject(i)
            val name = str(o, "username")
            val email = str(o, "email")
            val until = parseIso(str(o, "premium_until"))
            val c = card()
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.addView(t(name, 15.5f, Ui.TEXT, true))
            left.addView(t(email.ifEmpty { "—" }, 12.5f, Ui.TEXT2))
            head.addView(left, LinearLayout.LayoutParams(0, KW, 1f))
            val (status, sc) = when {
                until == null -> Pair("Jamais abonné", Ui.TEXT2)
                until.time <= now -> Pair("Expiré", Ui.ERROR)
                until.time - now < 3L * 86400000 -> Pair("Expire bientôt", Ui.WARNING)
                else -> Pair("Premium", Ui.SUCCESS)
            }
            head.addView(Ui.pill(this, status, sc))
            c.addView(head)
            val detail = when {
                until == null -> "Compte créé, aucun abonnement"
                until.time <= now -> "Terminé le ${df.format(until)}"
                else -> {
                    val left2 = ((until.time - now) / 86400000L).toInt()
                    "Jusqu'au ${df.format(until)} · " + if (left2 <= 0) "dernier jour" else "encore $left2 j"
                }
            }
            c.addView(t(detail, 13f, Ui.TEXT).also { it.setPadding(0, dp(8), 0, 0) })
            val paid = o.optInt("paid")
            val np = o.optInt("payments")
            if (np > 0) c.addView(t("$np paiement(s) · ${f(paid)} au total · dernière offre : ${planName(str(o, "plan"))}", 12f, Ui.TEXT2).also { it.setPadding(0, dp(2), 0, 0) })

            val actions = LinearLayout(this)
            actions.orientation = LinearLayout.HORIZONTAL
            actions.setPadding(0, dp(12), 0, 0)
            val msg = when {
                until == null -> "Bonjour $name 👋\nMerci d'avoir créé ton compte KOUNADIA. Avec Premium, tu débloques les prédictions, Coup sûr et les coupons. Tu peux t'abonner dans l'application : Paramètres › Mon compte.\n— L'équipe KOUNADIA"
                until.time <= now -> "Bonjour $name 👋\nTon abonnement KOUNADIA Premium est terminé depuis le ${df.format(until)}. Tu peux le réactiver quand tu veux dans l'application : Paramètres › Mon compte.\nMerci pour ta confiance !\n— L'équipe KOUNADIA"
                until.time - now < 3L * 86400000 -> "Bonjour $name 👋\nTon abonnement KOUNADIA Premium se termine le ${df.format(until)}. Pour continuer sans interruption, tu peux le renouveler dans l'application : Paramètres › Mon compte.\nMerci pour ta confiance !\n— L'équipe KOUNADIA"
                else -> "Bonjour $name 👋\nMerci d'utiliser KOUNADIA Premium ! Si tu as une question ou une idée, réponds simplement à ce message.\n— L'équipe KOUNADIA"
            }
            if (email.isNotEmpty()) {
                actions.addView(smallButton("E-mail", Ui.PRIMARY) {
                    try {
                        val uri = Uri.parse("mailto:$email?subject=" + Uri.encode("KOUNADIA") + "&body=" + Uri.encode(msg))
                        startActivity(Intent(Intent.ACTION_SENDTO, uri))
                    } catch (e: Exception) {
                        toast("Aucune application e-mail")
                    }
                }, weightLp(0))
            }
            if (actions.childCount > 0) c.addView(actions)
            content.addView(c)
        }
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
        val period = when (days) {
            0 -> "depuis le début"
            1 -> "dernières 24 h"
            else -> "$days derniers jours"
        }
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

    // ---------- bilan annuel ----------

    private fun renderYearly(y: JSONObject) {
        val arr = y.optJSONArray("years") ?: return
        if (arr.length() == 0) return
        sectionTitle("Bilan par année")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val c = card()
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(t(o.optInt("year").toString(), 20f, Ui.TEXT, true), LinearLayout.LayoutParams(0, KW, 1f))
            head.addView(t(f(o.optInt("net")) + " net", 15f, Ui.SUCCESS, true))
            c.addView(head)
            val gross = o.optInt("gross")
            val fee = o.optInt("fee")
            val rate = if (gross > 0) String.format(Locale.FRANCE, "%.1f %%", fee * 100.0 / gross) else "–"
            val lines = listOf(
                "Nouveaux utilisateurs" to n(o.optInt("newUsers")),
                "Utilisateurs par jour (moyenne)" to n(o.optInt("avgUsers")),
                "Record d'utilisateurs en un jour" to n(o.optInt("peak")),
                "Jours d'activité" to n(o.optInt("activeDays")),
                "Actions dans l'application" to n(o.optInt("events")),
                "Comptes créés" to n(o.optInt("accounts")),
                "Abonnements payés" to n(o.optInt("payments")),
                "Chiffre d'affaires brut" to f(gross),
                "Frais MoneyFusion" to "${f(fee)} ($rate)",
            )
            for ((k, v) in lines) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(7), 0, 0)
                row.addView(t(k, 13f, Ui.TEXT2), LinearLayout.LayoutParams(0, KW, 1f))
                row.addView(t(v, 13f, Ui.TEXT, true))
                c.addView(row)
            }
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
        val p = (if (days == 90) yearly?.optJSONObject("q90") else null) ?: b.optJSONObject(key) ?: JSONObject()
        sectionTitle("Chiffre d'affaires · " + if (days == 90 && yearly?.optJSONObject("q90") != null) "90 derniers jours" else label)
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
                left.addView(t("${str(o, "username")} · " + str(o, "contact").ifEmpty { "—" }, 13.5f, Ui.TEXT, true))
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

        val pages = pagesOf(ad)
        val first = pages.firstOrNull()
        val prev = adPreview(
            first?.title ?: str(ad, "title"), first?.text ?: str(ad, "text"), str(ad, "cta"), str(ad, "link").isNotEmpty(),
            color(str(ad, "color1"), 0xFF4F46E5.toInt()), color(str(ad, "color2"), 0xFF7C3AED.toInt()),
            first?.url?.let { images[it] }, pages.size, 0, first?.url != null,
        )
        if (!active) prev.alpha = 0.45f
        c.addView(prev)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.HORIZONTAL
        info.gravity = Gravity.CENTER_VERTICAL
        info.setPadding(0, dp(12), 0, 0)
        info.addView(Ui.pill(this, if (active) "Active" else "Désactivée", if (active) Ui.SUCCESS else Ui.TEXT2))
        val end = endsOf(ad)
        val pos = t("  " + (if (pages.size > 1) "${pages.size} pages · " else "") + (if (end == null) "sans limite" else "jusqu'au ${dayLabel(end)}"), 12f, Ui.TEXT2, true)
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
            .setMessage("« $title » et toutes ses photos seront supprimées définitivement (espace libéré sur Supabase).")
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
    private fun chip(value: String, size: Float): TextView {
        val c = t(value, size, Color.WHITE, true)
        c.letterSpacing = 0.06f
        c.setPadding(dp(8), dp(3), dp(8), dp(3))
        c.background = Ui.rounded(this, Color.argb(110, 0, 0, 0), 20)
        return c
    }

    private fun adPreview(title: String, text: String, cta: String, hasLink: Boolean, c1: Int, c2: Int, bmp: Bitmap?, pageCount: Int = 0, page: Int = 0, loadingPhoto: Boolean = false): FrameLayout {
        val f = RatioFrame(this)
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
        g.cornerRadius = dp(18).toFloat()
        f.background = g
        f.clipToOutline = true

        val count = maxOf(pageCount, if (bmp != null) 1 else 0)
        val lastPage = count <= 1 || page >= count - 1
        if (bmp != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(bmp)
            f.addView(iv, FrameLayout.LayoutParams(KM, KM))
        } else if (loadingPhoto) {
            f.addView(t("Chargement de la photo…", 12f, Color.argb(200, 255, 255, 255)), FrameLayout.LayoutParams(KW, KW, Gravity.CENTER))
        } else {
            val b1 = View(this)
            b1.background = Ui.circle(Color.argb(31, 255, 255, 255))
            val l1 = FrameLayout.LayoutParams(dp(170), dp(170), Gravity.END or Gravity.TOP)
            l1.setMargins(0, -dp(50), -dp(40), 0)
            f.addView(b1, l1)
            val b2 = View(this)
            b2.background = Ui.circle(Color.argb(20, 255, 255, 255))
            val l2 = FrameLayout.LayoutParams(dp(130), dp(130), Gravity.START or Gravity.BOTTOM)
            l2.setMargins(-dp(30), 0, 0, -dp(60))
            f.addView(b2, l2)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_VERTICAL
            col.setPadding(dp(18), dp(34), dp(18), dp(56))
            val tt = t(title.ifEmpty { "Titre de la publicité" }, 19f, Color.WHITE, true)
            tt.maxLines = 2
            tt.ellipsize = TextUtils.TruncateAt.END
            col.addView(tt)
            if (text.isNotEmpty()) {
                val tx = t(text, 13f, Color.argb(230, 255, 255, 255))
                tx.maxLines = 3
                tx.ellipsize = TextUtils.TruncateAt.END
                tx.setPadding(0, dp(6), 0, 0)
                col.addView(tx)
            }
            f.addView(col, FrameLayout.LayoutParams(KM, KM))
        }
        val sp = FrameLayout.LayoutParams(KW, KW, Gravity.START or Gravity.TOP)
        sp.setMargins(dp(10), dp(10), 0, 0)
        f.addView(chip("SPONSORISÉ", 9f), sp)
        if (count > 1) {
            val cp = FrameLayout.LayoutParams(KW, KW, Gravity.END or Gravity.TOP)
            cp.setMargins(0, dp(10), dp(10), 0)
            f.addView(chip("${page + 1}/$count", 11f), cp)
        }
        if (cta.isNotEmpty() && hasLink && lastPage) {
            val c = t("$cta  →", 13f, c1, true)
            c.maxLines = 1
            c.ellipsize = TextUtils.TruncateAt.END
            c.maxWidth = dp(170)
            c.setPadding(dp(14), dp(8), dp(14), dp(8))
            c.background = Ui.rounded(this, Color.WHITE, 22)
            val lp = FrameLayout.LayoutParams(KW, KW, Gravity.END or Gravity.BOTTOM)
            lp.setMargins(0, 0, dp(12), dp(12))
            f.addView(c, lp)
        }
        f.layoutParams = LinearLayout.LayoutParams(KM, KW)
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
        // Pages : URL déjà en ligne, ou photo locale (base64) à envoyer.
        class Pg(val url: String?, val b64: String?, val bmp: Bitmap?, var title: String = "", var text: String = "") {
            val photo: Boolean get() = url != null || b64 != null
        }
        val pages = ArrayList<Pg>()
        if (ad != null) for (p in pagesOf(ad)) pages.add(Pg(p.url, null, p.url?.let { images[it] }, p.title, p.text))
        var previewPage = 0
        var endMode = if (ad == null || endsOf(ad) == null) "none" else "keep"
        val oldEnd = if (ad == null) null else endsOf(ad)

        var c1 = color(if (ad == null) presets[0].first else str(ad, "color1"), 0xFF4F46E5.toInt())
        var c2 = color(if (ad == null) presets[0].second else str(ad, "color2"), 0xFF7C3AED.toInt())
        var active = ad?.optBoolean("active", true) ?: true

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
        val ph = LinearLayout.LayoutParams(KM, KW)
        ph.setMargins(0, dp(14), 0, dp(4))
        box.addView(previewHolder, ph)

        label(box, "Nom de la publicité (visible seulement dans Tigui)")
        val title = field(box, "Ex. : Promo dépôt 1xBet", if (ad == null) "" else str(ad, "title"), 60)
        val text = field(box, "", if (ad == null) "" else str(ad, "text"), 160)
        text.visibility = View.GONE

        label(box, "Lien du bouton")
        val waBox = LinearLayout(this)
        waBox.orientation = LinearLayout.VERTICAL
        val webBox = LinearLayout(this)
        webBox.orientation = LinearLayout.VERTICAL
        lateinit var refreshPreview: () -> Unit
        lateinit var refreshStrip: () -> Unit
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
                refreshStrip()
                refreshPreview()
            }
            swatches.addView(v, lp)
            swatchViews.add(p to v)
        }
        refreshSwatches()
        box.addView(swatches)

        label(box, "Pages (5 max.)")
        box.addView(t("Chaque page est une photo (recadrée en 2:1, idéal 1080 × 540) ou un texte sur le fond de couleur. Les pages défilent du bas vers le haut ; le bouton apparaît sur la dernière page. Touche une page texte pour la modifier.", 12f, Ui.TEXT2))
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        val stripScroll = android.widget.HorizontalScrollView(this)
        stripScroll.isHorizontalScrollBarEnabled = false
        stripScroll.setPadding(0, dp(10), 0, 0)
        stripScroll.addView(strip)
        box.addView(stripScroll)
        fun editTextPage(i: Int) {
            val pg = pages.getOrNull(i) ?: return
            val form = LinearLayout(this)
            form.orientation = LinearLayout.VERTICAL
            form.setPadding(dp(20), dp(8), dp(20), 0)
            val ti = field(form, "Titre (60 max.)", pg.title, 60)
            val tx = field(form, "Texte (160 max.)", pg.text, 160, multi = true)
            AlertDialog.Builder(this)
                .setTitle("Page ${i + 1} · texte")
                .setView(form)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("OK") { _, _ ->
                    pg.title = ti.text.toString().trim()
                    pg.text = tx.text.toString().trim()
                    refreshStrip()
                    refreshPreview()
                }
                .show()
        }
        val addRow = LinearLayout(this)
        addRow.orientation = LinearLayout.HORIZONTAL
        val addBtn = smallButton("+  Page photo", Ui.PRIMARY) {
            if (pages.size >= 5) {
                toast("5 pages maximum")
                return@smallButton
            }
            onPicked = { b64, b ->
                if (pages.size < 5) {
                    pages.add(Pg(null, b64, b))
                    previewPage = pages.size - 1
                    refreshStrip()
                    refreshPreview()
                }
            }
            picker.launch("image/*")
        }
        val addTextBtn = smallButton("+  Page texte", Ui.PRIMARY) {
            if (pages.size >= 5) {
                toast("5 pages maximum")
                return@smallButton
            }
            pages.add(Pg(null, null, null))
            previewPage = pages.size - 1
            refreshStrip()
            refreshPreview()
            editTextPage(previewPage)
        }
        addRow.addView(addBtn, weightLp(0))
        addRow.addView(addTextBtn, weightLp(8))
        val addLp = LinearLayout.LayoutParams(KM, KW)
        addLp.setMargins(0, dp(10), 0, 0)
        box.addView(addRow, addLp)
        refreshStrip = {
            strip.removeAllViews()
            for ((i, p) in pages.withIndex()) {
                val cell = FrameLayout(this)
                cell.background = Ui.rounded(this, Ui.ELEVATED, 10, if (i == previewPage) Ui.PRIMARY else Ui.BORDER)
                cell.setPadding(dp(2), dp(2), dp(2), dp(2))
                if (p.photo) {
                    val iv = ImageView(this)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    if (p.bmp != null) iv.setImageBitmap(p.bmp)
                    cell.addView(iv, FrameLayout.LayoutParams(KM, KM))
                } else {
                    val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
                    g.cornerRadius = dp(8).toFloat()
                    val tv = t(p.title.ifEmpty { p.text.ifEmpty { "Texte…" } }, 10f, Color.WHITE, true)
                    tv.maxLines = 2
                    tv.ellipsize = TextUtils.TruncateAt.END
                    tv.gravity = Gravity.CENTER
                    tv.setPadding(dp(6), dp(4), dp(6), dp(14))
                    tv.background = g
                    cell.addView(tv, FrameLayout.LayoutParams(KM, KM))
                }
                val num = FrameLayout.LayoutParams(KW, KW, Gravity.START or Gravity.BOTTOM)
                num.setMargins(dp(4), 0, 0, dp(4))
                cell.addView(chip("${i + 1}/${pages.size}", 10f), num)
                val x = t("✕", 13f, Color.WHITE, true)
                x.gravity = Gravity.CENTER
                x.background = Ui.circle(Color.argb(200, 220, 38, 38))
                val xl = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END or Gravity.TOP)
                xl.setMargins(0, dp(3), dp(3), 0)
                x.setOnClickListener {
                    pages.removeAt(i)
                    previewPage = previewPage.coerceAtMost(maxOf(0, pages.size - 1))
                    refreshStrip()
                    refreshPreview()
                }
                cell.addView(x, xl)
                cell.setOnClickListener {
                    previewPage = i
                    refreshStrip()
                    refreshPreview()
                    if (!p.photo) editTextPage(i)
                }
                val lp = LinearLayout.LayoutParams(dp(112), dp(56) + dp(4))
                lp.setMargins(0, 0, dp(8), 0)
                strip.addView(cell, lp)
            }
            stripScroll.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
            addBtn.text = if (pages.size >= 5) "5 pages max." else "+  Page photo"
            addTextBtn.text = if (pages.size >= 5) "${pages.size}/5" else "+  Page texte (${pages.size}/5)"
        }
        refreshStrip()

        label(box, "Durée de diffusion")
        val endOptions = ArrayList<Pair<String, String>>()
        if (oldEnd != null) endOptions.add("keep" to "Jusqu'au ${dayLabel(oldEnd).take(5)}")
        endOptions.addAll(listOf("7" to "7 j", "30" to "30 j", "none" to "Illimitée"))
        lateinit var refreshEnd: () -> Unit
        refreshEnd = chipRow(box, endOptions, { endMode }) {
            endMode = it
            refreshEnd()
        }
        box.addView(t("À la fin, la pub et ses photos sont supprimées automatiquement.", 12f, Ui.TEXT2).also { it.setPadding(0, dp(6), 0, 0) })

        val position = field(
            box, "1 = en premier",
            (if (ad == null) ads.length() + 1 else ad.optInt("position", 1)).toString(), 3,
            type = InputType.TYPE_CLASS_NUMBER,
        )
        position.visibility = View.GONE

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
            previewHolder.removeAllViews()
            val pg = pages.getOrNull(previewPage)
            val p = adPreview(
                pg?.title ?: "Ajoute une page", pg?.text ?: "Photo ou texte, 5 pages max.", cta.text.toString().trim(),
                currentLink().isNotEmpty(), c1, c2, pg?.bmp, pages.size, previewPage, pg?.photo == true,
            )
            p.setOnClickListener {
                if (pages.size > 1) {
                    previewPage = (previewPage + 1) % pages.size
                    refreshStrip()
                    refreshPreview()
                }
            }
            p.layoutParams = FrameLayout.LayoutParams(KM, KW)
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
                pages.removeAll { !it.photo && it.title.isEmpty() && it.text.isEmpty() }
                if (pages.isEmpty()) {
                    previewPage = 0
                    refreshStrip()
                    refreshPreview()
                    status.text = "Ajoute au moins une page (photo ou texte)."
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
                    .put("title", ttl.ifEmpty { "Publicité" })
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
                        if (id.isEmpty()) throw IllegalStateException("Publicité non créée, réessaie.")
                        val urls = JSONArray()
                        for ((i, p) in pages.withIndex()) {
                            if (!p.photo) {
                                urls.put(JSONObject().put("title", p.title).put("text", p.text))
                            } else if (p.url != null) {
                                urls.put(p.url)
                            } else if (p.b64 != null) {
                                status.text = "Envoi de la page ${i + 1}/${pages.size}…"
                                val r = unwrapObj(api("POST", "/admin/kounadia/ads/$id/pages", JSONObject().put("base64", p.b64).put("contentType", "image/jpeg")))
                                val u = str(r, "url")
                                if (u.isEmpty()) throw IllegalStateException("La page ${i + 1} n'a pas pu être envoyée, réessaie.")
                                if (u.isNotEmpty()) {
                                    urls.put(u)
                                    if (p.bmp != null) images[u] = p.bmp
                                }
                            }
                        }
                        val body2 = JSONObject().put("pages", urls)
                        when (endMode) {
                            "7" -> body2.put("endsAt", isoUtc(Date(System.currentTimeMillis() + 7L * 86400000)))
                            "30" -> body2.put("endsAt", isoUtc(Date(System.currentTimeMillis() + 30L * 86400000)))
                            "none" -> body2.put("endsAt", JSONObject.NULL)
                        }
                        api("PUT", "/admin/kounadia/ads/$id/pages", body2)
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
private class KndDailyChart(context: Context, private val labels: List<String>, private val values: List<Int>, private val unit: String = " / jour") : View(context) {
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
        canvas.drawText("max " + String.format(Locale.FRANCE, "%,d", max).replace('\u202F', ' ').replace('\u00A0', ' ') + unit, 0f, 11 * d, txt)
        canvas.drawText(labels.first(), 0f, height - 4 * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        canvas.drawText(labels.last(), width.toFloat(), height - 4 * d, txt)
    }
}

/** Cadre au format de la carte publicitaire (largeur = 2 × hauteur). */
private class RatioFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w / 2, MeasureSpec.EXACTLY),
        )
    }
}

/** Page d'une publicité : photo (url) ou texte. */
private data class AdPage(val url: String?, val title: String, val text: String)
