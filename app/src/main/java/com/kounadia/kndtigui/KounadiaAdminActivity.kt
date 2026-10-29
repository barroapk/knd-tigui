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
private const val PUB_BASE = "https://raw.githubusercontent.com/barroapk/kfeed/pub"

/** Onglet KOUNADIA : statistiques d'usage de l'application et gestion des publicités. */
class KounadiaAdminActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    // Période des statistiques : today, yesterday, month, year, all, custom (dates incluses, aaaa-mm-jj).
    private var pKey = "today"
    private var pFrom = ""
    private var pTo = ""
    private var range: JSONObject? = null
    // Codes 1xBet des coupons du jour (robot KOUNADIA).
    private var codes: JSONObject? = null
    private var codesLoading = false
    private var stats: JSONObject? = null
    private var biz: JSONObject? = null
    private var yearly: JSONObject? = null
    private var admin: JSONObject? = null
    private var tab = 0
    private var pubDay: JSONObject? = null
    private var pubLoading = false
    private var pubError = ""
    private val pubImages = HashMap<String, Bitmap>()
    private var subs = JSONArray()
    private var subsMode = "active"
    private var subsQuery = ""
    private var subsLoading = false
    private var subsLoaded = false
    private var sec: JSONObject? = null
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var robot: JSONObject? = null
    private var site: JSONObject? = null
    private val devResults = HashMap<String, Pair<String, Int>>()
    private var secLoading = false
    // Plantages de l'app KOUNADIA (boîte noire)
    private var crash: JSONObject? = null
    private var crashLoading = false
    private var crashDays = 7
    private var txtPending: String? = null
    private val txtSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val txt = txtPending
        txtPending = null
        if (uri != null && txt != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                toast("Rapport enregistré")
            } catch (e: Exception) {
                toast("Enregistrement impossible")
            }
        }
    }
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

    // Téléchargements réels de l'APK (compteur GitHub des versions publiées).
    private var downloads: JSONObject? = null

    private fun fetchDownloads(): JSONObject? = try {
        val c = URL("https://api.github.com/repos/barroapk/kounadia-app/releases?per_page=100").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "KND-Tigui")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val arr = JSONArray(text)
        var total = 0
        var latest = 0
        var ver = ""
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val assets = rel.optJSONArray("assets") ?: JSONArray()
            var count = 0
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == "kounadia.apk") count += a.optInt("download_count")
            }
            total += count
            if (i == 0) {
                latest = count
                ver = rel.optString("tag_name").removePrefix("v")
            }
        }
        JSONObject().put("total", total).put("latest", latest).put("ver", ver)
    } catch (e: Exception) {
        null
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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(KM, 0, 1f))
        root.addView(View(this).also { it.setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(KM, dp(1)))
        nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Ui.SURFACE)
        nav.setPadding(dp(6), dp(6), dp(6), dp(8))
        root.addView(nav, LinearLayout.LayoutParams(KM, KW))
        setContentView(root)
        if (!SessionStorage.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        PushRegistration.register(this)
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
                if (pFrom.isEmpty()) setPeriod(pKey)
                range = try {
                    unwrapObj(api("GET", "/admin/kounadia/range?from=$pFrom&to=$pTo" + if (pKey == "all") "&all=1" else ""))
                } catch (e: Exception) {
                    if (e is ApiException && e.httpCode == 401) throw e
                    null
                }
                stats = range?.optJSONObject("audience")
                admin = range
                yearly = try {
                    unwrapObj(api("GET", "/admin/kounadia/yearly"))
                } catch (e: Exception) {
                    null
                }
                if (tab == 3) subsLoaded = false
                biz = try {
                    unwrapObj(api("GET", "/admin/kounadia/business"))
                } catch (e: Exception) {
                    null
                }
                downloads = withContext(Dispatchers.IO) { fetchDownloads() } ?: downloads
                sec = try { unwrapObj(api("GET", "/admin/security")) } catch (e: Exception) { sec }
                robot = withContext(Dispatchers.IO) { httpJson("$ROBOT/") } ?: robot
                site = withContext(Dispatchers.IO) { httpJson("$SITE/version.json") } ?: site
                ads = fetchAds()
                loadImages()
                if (range == null) error = "Les statistiques n'ont pas pu être chargées. Touche ↻ pour réessayer."
            } catch (e: Exception) {
                if (e is ApiException && e.httpCode == 401) {
                    SessionExpiry.handle(this@KounadiaAdminActivity)
                    finish()
                    return@launch
                }
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

        renderHeader()
        buildNav()

        if (tab <= 2) renderPeriodBar()
        if (tab == 1 || tab == 3) content.addView(segment(listOf(1 to "Revenus", 3 to "Abonnés")))
        if (tab == 10 || tab == 11) {
            val back = t("‹  Pubs", 14f, Ui.PRIMARY, true)
            back.setPadding(0, dp(14), 0, 0)
            Ui.pressable(back) { go(4) }
            content.addView(back)
        }
        if (tab == 5 || tab == 6 || tab == 8 || tab == 9) {
            val back = t("‹  Plus", 14f, Ui.PRIMARY, true)
            back.setPadding(0, dp(14), 0, 0)
            Ui.pressable(back) { go(7) }
            content.addView(back)
        }

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
            0 -> {
                renderStatusStrip()
                renderOverview()
            }
            1 -> {
                biz?.let { renderBusiness(it) }
                renderRevenueChart()
                renderExport()
                yearly?.let { renderYearly(it) }
            }
            2 -> if (s != null) renderStats(s) else if (loading) loadingCard()
            3 -> renderSubs()
            4 -> renderAds(s)
            5 -> renderSecurity()
            7 -> renderMore()
            8 -> renderDev()
            9 -> renderCrashes()
            10 -> renderPub()
            11 -> renderCodes()
            else -> renderAccount()
        }
    }

    // ---------- navigation ----------

    private fun pageTitle(): String = when (tab) {
        0 -> "Tableau de bord"
        1 -> "Revenus"
        2 -> "Audience"
        3 -> "Abonnés"
        4 -> "Publicités"
        5 -> "Sécurité"
        7 -> "Plus"
        8 -> "Espace développeur"
        9 -> "Plantages de l'app"
        10 -> "Pub du jour"
        11 -> "Codes 1xBet"
        else -> "Mon compte"
    }

    /** Ouvre une page (et charge ses données si besoin). */
    private fun go(newTab: Int) {
        tab = newTab
        when {
            newTab == 3 && !subsLoaded -> loadSubs()
            newTab == 5 -> loadSecurity()
            newTab == 9 -> loadCrashes()
            newTab == 10 -> loadPub()
            newTab == 11 -> loadCodes()
            else -> render()
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun renderHeader() {
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val logo = t("K", 20f, Color.WHITE, true)
        logo.gravity = Gravity.CENTER
        logo.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF4F46E5.toInt(), 0xFF7C3AED.toInt()),
        ).also { it.cornerRadius = dp(12).toFloat() }
        top.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        col.addView(t(pageTitle(), 21f, Ui.TEXT, true))
        val today = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date()).replaceFirstChar { it.uppercase() }
        col.addView(t(if (loading) "Mise à jour…" else "KOUNADIA Admin · $today", 12.5f, Ui.TEXT2))
        top.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        val refresh = ImageView(this)
        refresh.setImageResource(R.drawable.ic_refresh)
        refresh.setColorFilter(Ui.TEXT2)
        refresh.setPadding(dp(10), dp(10), dp(10), dp(10))
        refresh.background = Ui.rounded(this, Ui.SURFACE, 12, Ui.BORDER)
        Ui.pressable(refresh) { loadAll() }
        top.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(top)
    }

    private fun buildNav() {
        nav.removeAllViews()
        val items = listOf(
            Triple("Accueil", R.drawable.ic_home, 0),
            Triple("Revenus", R.drawable.ic_chart, 1),
            Triple("Audience", R.drawable.ic_users, 2),
            Triple("Pubs", R.drawable.ic_megaphone, 4),
            Triple("Plus", R.drawable.ic_grid, 7),
        )
        val current = when (tab) {
            0 -> 0
            1, 3 -> 1
            2 -> 2
            4, 10, 11 -> 4
            else -> 7
        }
        for ((label, icon, target) in items) {
            val on = target == current
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(6), 0, dp(4))
            val iv = ImageView(this)
            iv.setImageResource(icon)
            iv.setColorFilter(if (on) Ui.PRIMARY else Ui.TEXT2)
            iv.setPadding(dp(14), dp(4), dp(14), dp(4))
            if (on) iv.background = Ui.rounded(this, Ui.withAlpha(Ui.PRIMARY, 0x2A), 14)
            col.addView(iv, LinearLayout.LayoutParams(dp(54), dp(30)))
            val tv = t(label, 11.5f, if (on) Ui.TEXT else Ui.TEXT2, on)
            tv.setPadding(0, dp(3), 0, 0)
            col.addView(tv)
            col.setOnClickListener { if (current != target || tab != target) go(target) }
            nav.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        }
    }

    /** Petit sélecteur à deux choix (ex. Revenus | Abonnés). */
    private fun segment(options: List<Pair<Int, String>>): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.setPadding(dp(4), dp(4), dp(4), dp(4))
        box.background = Ui.rounded(this, Ui.SURFACE, 14, Ui.BORDER)
        for ((target, label) in options) {
            val on = tab == target
            val c = t(label, 13.5f, if (on) Color.WHITE else Ui.TEXT2, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(9), 0, dp(9))
            if (on) c.background = Ui.rounded(this, Ui.PRIMARY, 11)
            c.setOnClickListener { if (!on) go(target) }
            box.addView(c, LinearLayout.LayoutParams(0, KW, 1f))
        }
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, dp(14), 0, 0)
        box.layoutParams = lp
        return box
    }

    // ---------- état des services ----------

    private fun httpJson(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "KOUNADIA-Admin")
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(txt)
    } catch (e: Exception) {
        null
    }

    /** Minutes écoulées depuis une date ISO (UTC), ou -1. */
    private fun ageMin(iso: String): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        (System.currentTimeMillis() - f.parse(iso.take(19))!!.time) / 60000
    } catch (e: Exception) {
        -1
    }

    private fun robotOk(): Boolean {
        val r = robot ?: return false
        val a = ageMin(str(r, "at"))
        return a in 0..3
    }

    private fun tile(label: String, value: String, sub: String, color: Int, onClick: (() -> Unit)?): View {
        val v = kpi(label, value, sub, color)
        if (onClick != null) Ui.pressable(v, onClick)
        return v
    }

    private fun renderStatusStrip() {
        sectionTitle("État de KOUNADIA")
        val st = str(sec ?: JSONObject(), "status")
        val (secTxt, secCol) = when (st) {
            "alerte" -> Pair("Alerte", Ui.ERROR)
            "surveillance" -> Pair("À surveiller", Ui.WARNING)
            "calme" -> Pair("Calme", Ui.SUCCESS)
            else -> Pair("—", Ui.TEXT2)
        }
        val serverOk = admin != null || stats != null
        content.addView(pair(
            tile("Serveur", if (serverOk) "En ligne" else "Injoignable", "API sur Render", if (serverOk) Ui.SUCCESS else Ui.ERROR) { go(8) },
            tile("Sécurité", secTxt, "dernières 24 h", secCol) { go(5) },
        ))
        val r = robot
        val live = r?.optInt("live") ?: 0
        val ver = str(site ?: JSONObject(), "version")
        content.addView(pair(
            tile("Robot direct", if (robotOk()) "Actif" else "À vérifier", if (robotOk()) "$live match(s) suivis" else "scores et notifications", if (robotOk()) Ui.SUCCESS else Ui.WARNING) { go(8) },
            tile("Version publiée", if (ver.isBlank()) "—" else ver, "sur le site", Ui.PRIMARY) { go(8) },
        ))
    }

    // ---------- page « Plus » ----------

    private fun menuCard(icon: Int, color: Int, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(14), dp(12), dp(14))
        row.background = Ui.rounded(this, Ui.SURFACE, 18, Ui.BORDER)
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(color)
        iv.setPadding(dp(10), dp(10), dp(10), dp(10))
        iv.background = Ui.rounded(this, Ui.withAlpha(color, 0x26), 14)
        row.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(8), 0)
        col.addView(t(title, 16f, Ui.TEXT, true))
        col.addView(t(sub, 12.5f, Ui.TEXT2))
        row.addView(col, LinearLayout.LayoutParams(0, KW, 1f))
        val ch = ImageView(this)
        ch.setImageResource(R.drawable.ic_chevron)
        ch.setColorFilter(Ui.TEXT2)
        row.addView(ch, LinearLayout.LayoutParams(dp(20), dp(20)))
        Ui.pressable(row, onClick)
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, dp(10), 0, 0)
        row.layoutParams = lp
        return row
    }

    private fun renderMore() {
        sectionTitle("Gestion")
        val st = str(sec ?: JSONObject(), "status")
        val secCol = when (st) { "alerte" -> Ui.ERROR; "surveillance" -> Ui.WARNING; else -> Ui.SUCCESS }
        content.addView(menuCard(R.drawable.ic_shield, secCol, "Sécurité", "Attaques bloquées, adresses suspectes, journal") { go(5) })
        content.addView(menuCard(R.drawable.ic_shield, Ui.ERROR, "Plantages de l'app", "Boîte noire : erreurs chez les utilisateurs, rapport à copier") { go(9) })
        content.addView(menuCard(R.drawable.ic_code, Ui.PRIMARY, "Espace développeur", "État des services, commandes, aide en français simple") { go(8) })
        content.addView(menuCard(R.drawable.ic_user, Ui.TEXT2, "Mon compte", "Mot de passe et déconnexion") { go(6) })
        content.addView(menuCard(R.drawable.ic_megaphone, Ui.SUCCESS, "Pub du jour", "Coups sûrs du jour en images, à partager") { go(10) })
        content.addView(menuCard(R.drawable.ic_code, Ui.WARNING, "Codes coupons 1xBet", "Le code 1xBet de chaque coupon du jour") { go(11) })
        content.addView(menuCard(R.drawable.ic_inbox, Ui.SUCCESS, "Numéro WhatsApp", "Le numéro affiché dans l'app, le site et les images") { editWhatsApp() })
        sectionTitle("Raccourcis")
        content.addView(menuCard(R.drawable.ic_home, Ui.SUCCESS, "Site KOUNADIA", "kounadia.pages.dev") { openLink(SITE) })
        content.addView(menuCard(R.drawable.ic_chart, Ui.WARNING, "Fabrications (GitHub Actions)", "Voir les builds de l'application") { openLink("https://github.com/barroapk/kounadia-app/actions") })
    }

    // ---------- espace développeur ----------

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Impossible d'ouvrir le lien")
        }
    }

    private fun copyText(label: String, value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        toast("Copié : $label")
    }

    private fun testService(key: String) {
        devResults[key] = Pair("Test en cours…", Ui.TEXT2)
        render()
        scope.launch {
            val res: Pair<String, Int> = withContext(Dispatchers.IO) {
                when (key) {
                    "api" -> {
                        val t0 = System.currentTimeMillis()
                        try {
                            val base = ConfigStorage.getApiBaseUrl(this@KounadiaAdminActivity).trimEnd('/')
                            val c = URL("$base/ping").openConnection() as HttpURLConnection
                            c.connectTimeout = 70000
                            c.readTimeout = 70000
                            c.inputStream.close()
                            c.disconnect()
                            val ms = System.currentTimeMillis() - t0
                            Pair(if (ms > 8000) "Réveillé en ${ms / 1000} s (il dormait)" else "Répond en $ms ms ✓", Ui.SUCCESS)
                        } catch (e: Exception) {
                            Pair("Ne répond pas ✗", Ui.ERROR)
                        }
                    }
                    "robot" -> {
                        val r = httpJson("$ROBOT/")
                        if (r == null) Pair("Injoignable ✗", Ui.ERROR) else {
                            robot = r
                            val err = if (r.isNull("err")) "" else str(r, "err")
                            if (robotOk()) Pair("Actif ✓ · ${r.optInt("live")} match(s) en direct", Ui.SUCCESS)
                            else Pair("Ne tourne plus depuis ${ageMin(str(r, "at"))} min ${err.take(60)}", Ui.WARNING)
                        }
                    }
                    else -> {
                        val v = httpJson("$SITE/version.json")
                        if (v == null) Pair("Injoignable ✗", Ui.ERROR) else {
                            site = v
                            Pair("En ligne ✓ · version ${str(v, "version")} du ${str(v, "date")}", Ui.SUCCESS)
                        }
                    }
                }
            }
            devResults[key] = res
            render()
        }
    }

    private fun serviceCard(key: String, title: String, what: String, where: String) {
        val c = card()
        c.addView(t(title, 16f, Ui.TEXT, true))
        c.addView(t(what, 13f, Ui.TEXT2))
        c.addView(t("Où : $where", 12.5f, Ui.TEXT2))
        devResults[key]?.let { (msg, col) ->
            val r = t(msg, 13.5f, col, true)
            r.setPadding(0, dp(8), 0, 0)
            c.addView(r)
        }
        c.addView(Ui.button(this, "Tester maintenant", "secondary") { testService(key) })
        content.addView(c)
    }

    private fun commandCard(title: String, explain: String, cmd: String) {
        val c = card()
        c.addView(t(title, 15f, Ui.TEXT, true))
        c.addView(t(explain, 12.5f, Ui.TEXT2))
        val code = t(cmd, 12f, 0xFF9BE7B0.toInt())
        code.typeface = android.graphics.Typeface.MONOSPACE
        code.setPadding(dp(12), dp(10), dp(12), dp(10))
        code.background = Ui.rounded(this, 0xFF0A0D12.toInt(), 10, Ui.BORDER)
        val lp = LinearLayout.LayoutParams(KM, KW)
        lp.setMargins(0, dp(8), 0, 0)
        c.addView(code, lp)
        c.addView(Ui.button(this, "Copier la commande", "secondary") { copyText(title, cmd) })
        content.addView(c)
    }

    private fun infoCard(title: String, body: String) {
        val c = card()
        c.addView(t(title, 15f, Ui.TEXT, true))
        val b = t(body, 13f, Ui.TEXT2)
        b.setPadding(0, dp(4), 0, 0)
        c.addView(b)
        content.addView(c)
    }

    private fun renderDev() {
        infoCard(
            "Bienvenue 👋",
            "Cet espace t'explique comment KOUNADIA fonctionne, en français simple. Tu peux tester chaque service, copier les commandes Termux utiles et retrouver quoi faire en cas de problème.",
        )

        sectionTitle("Comment KOUNADIA fonctionne")
        infoCard(
            "Le chemin d'une information",
            "1. L'utilisateur ouvre l'application KOUNADIA sur son téléphone.\n" +
                "2. Pour son compte et son abonnement, l'app parle au SERVEUR (Render).\n" +
                "3. Le serveur range tout dans la BASE DE DONNÉES (Supabase).\n" +
                "4. Le ROBOT (Cloudflare) lit les scores toutes les 3 secondes et envoie les notifications.\n" +
                "5. GITHUB fabrique l'application (APK) et met le site à jour.",
        )

        sectionTitle("Tester les services")
        serviceCard("api", "Serveur (API)", "Comptes, abonnements, publicités, statistiques, sécurité.", "Render · knd-api")
        serviceCard("robot", "Robot du direct", "Scores en temps réel, notifications, traduction des pubs.", "Cloudflare · kounadia-notif")
        serviceCard("site", "Site de téléchargement", "Page où les gens téléchargent KOUNADIA.", "Cloudflare Pages · kounadia.pages.dev")

        sectionTitle("Mes outils en ligne")
        content.addView(menuCard(R.drawable.ic_chart, Ui.WARNING, "GitHub · KOUNADIA", "Fabrications de l'app et du site") { openLink("https://github.com/barroapk/kounadia-app/actions") })
        content.addView(menuCard(R.drawable.ic_code, Ui.WARNING, "GitHub · KOUNADIA Admin", "Fabrications de cette application") { openLink("https://github.com/barroapk/knd-tigui/actions") })
        content.addView(menuCard(R.drawable.ic_home, Ui.PRIMARY, "Render", "Le serveur : journaux, redémarrage") { openLink("https://dashboard.render.com") })
        content.addView(menuCard(R.drawable.ic_inbox, Ui.SUCCESS, "Supabase", "La base de données (SQL Editor)") { openLink("https://supabase.com/dashboard") })
        content.addView(menuCard(R.drawable.ic_shield, Ui.WARNING, "Cloudflare", "Le robot et le site") { openLink("https://dash.cloudflare.com") })
        content.addView(menuCard(R.drawable.ic_alert, Ui.ERROR, "Firebase", "Les notifications") { openLink("https://console.firebase.google.com") })

        sectionTitle("Commandes Termux utiles")
        commandCard(
            "Fabriquer une version de test",
            "Crée un APK pour essayer, sans le donner aux utilisateurs.",
            "gh workflow run build-apk.yml -R barroapk/kounadia-app -f publier=false",
        )
        commandCard(
            "Récupérer le dernier APK de test",
            "Attend la fin de la fabrication puis met l'APK dans Téléchargements › kounadia-test.",
            "R=\$(gh run list -R barroapk/kounadia-app -w build-apk.yml -L 1 --json databaseId -q '.[0].databaseId') && gh run watch \$R -R barroapk/kounadia-app --exit-status >/dev/null && rm -rf ~/storage/downloads/kounadia-test && gh run download \$R -R barroapk/kounadia-app -n kounadia-apk -D ~/storage/downloads/kounadia-test && echo OK",
        )
        commandCard(
            "Publier pour tout le monde",
            "Nouvelle version sur le site + carte « Mise à jour » chez tous les utilisateurs. Remplace le texte des nouveautés.",
            "gh workflow run build-apk.yml -R barroapk/kounadia-app -f publier=true -f notes=\"Améliorations et corrections\"",
        )
        commandCard(
            "Voir l'état du robot",
            "Affiche : matchs suivis (live), notifications envoyées (sent), erreurs (err).",
            "curl -s https://kounadia-notif.barroapk.workers.dev/; echo",
        )
        commandCard(
            "Relancer le robot",
            "Remet le robot en ligne s'il ne répond plus.",
            "gh workflow run deploy-notif.yml -R barroapk/kounadia-app",
        )
        commandCard(
            "Fabriquer KOUNADIA Admin",
            "Crée un nouvel APK de cette application d'administration.",
            "gh workflow run build.yml -R barroapk/knd-tigui",
        )
        commandCard(
            "Voir les dernières fabrications",
            "✓ = réussie, X = échouée.",
            "gh run list -R barroapk/kounadia-app -L 5",
        )

        sectionTitle("Si quelque chose ne marche pas")
        infoCard("Le serveur met du temps à répondre", "C'est normal avec l'offre gratuite de Render : il s'endort après 15 min sans visite et met jusqu'à 1 minute à se réveiller.")
        infoCard("Les scores ne bougent plus", "Teste le « Robot du direct » ci-dessus. S'il ne tourne plus, copie la commande « Relancer le robot ».")
        infoCard("Un utilisateur ne peut plus se connecter", "Va dans Sécurité : si son adresse est bloquée, appuie sur « Débloquer ».")
        infoCard("Je suis bloqué hors de l'application", "Dans Supabase › SQL Editor, lance :  delete from blocked_ips;  puis reconnecte-toi.")
        infoCard("Une fabrication a échoué", "Copie le message d'erreur (commande « Voir les dernières fabrications ») et envoie-le à Claude.")

        sectionTitle("Petit lexique")
        infoCard(
            "Les mots à connaître",
            "• APK : le fichier qui installe une application Android.\n" +
                "• Build (fabrication) : GitHub transforme le code en APK.\n" +
                "• Commit / Push : enregistrer une modification du code / l'envoyer sur GitHub.\n" +
                "• Serveur / API : l'ordinateur en ligne qui répond aux applications.\n" +
                "• Base de données : là où sont rangés les comptes, paiements et pubs.\n" +
                "• Robot : petit programme qui travaille seul en permanence (scores, notifications).\n" +
                "• Adresse IP : l'adresse d'un appareil sur internet.\n" +
                "• Secret : mot de passe technique gardé hors du code (clés Firebase, Cloudflare…).",
        )
    }

    // ---------- sécurité ----------

    companion object {
        const val ROBOT = "https://kounadia-notif.barroapk.workers.dev"
        const val SITE = "https://kounadia.pages.dev"
    }

    // ---------- plantages (boîte noire) ----------

    private fun loadCrashes() {
        crashLoading = true
        render()
        scope.launch {
            try {
                crash = unwrapObj(api("GET", "/admin/kounadia/crashes?days=$crashDays"))
            } catch (e: Exception) {
                toast(e.message ?: "Erreur réseau")
            }
            crashLoading = false
            render()
        }
    }

    private fun renderCrashes() {
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, dp(14), 0, dp(4))
        for ((d, label) in listOf(1 to "24 h", 7 to "7 jours", 30 to "30 jours")) {
            val on = d == crashDays
            val c = t(label, 13f, if (on) Color.WHITE else Ui.TEXT2, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(9), 0, dp(9))
            c.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.SURFACE, 12, if (on) null else Ui.BORDER)
            val lp = LinearLayout.LayoutParams(0, KW, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            c.setOnClickListener {
                if (crashDays != d && !crashLoading) {
                    crashDays = d
                    loadCrashes()
                }
            }
            chips.addView(c, lp)
        }
        content.addView(chips)

        val data = crash
        if (data == null) {
            if (crashLoading) loadingCard() else infoCard("Plantages", "Impossible de charger les plantages. Vérifie internet puis réessaie.")
            return
        }
        val total = data.optInt("total", 0)
        val items = data.optJSONArray("items") ?: JSONArray()
        val report = data.optString("text", "")

        val head = card()
        head.addView(t(if (total == 0) "Aucun plantage ✅" else "$total plantage${if (total > 1) "s" else ""}", 20f, if (total == 0) Ui.SUCCESS else Ui.ERROR, true))
        head.addView(t(
            if (total == 0) "Aucune erreur signalée par les téléphones sur cette période."
            else "${items.length()} problème${if (items.length() > 1) "s" else ""} différent${if (items.length() > 1) "s" else ""}. Copie le rapport et colle-le dans la conversation pour la correction.",
            13f, Ui.TEXT2,
        ))
        if (total > 0) {
            head.addView(Ui.button(this, "Copier le rapport", "primary") { copyText("Rapport des plantages", report) })
            head.addView(Ui.button(this, "Télécharger (.txt)", "secondary") {
                txtPending = report
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
                txtSaver.launch("plantages-kounadia-$day.txt")
            })
        }
        content.addView(head)

        for (i in 0 until items.length()) {
            val g = items.optJSONObject(i) ?: continue
            val c = card()
            val fatal = g.optBoolean("fatal", false)
            c.addView(t("#${i + 1} · ${g.optInt("count")} fois · ${g.optInt("devices")} téléphone${if (g.optInt("devices") > 1) "s" else ""}", 14f, if (fatal) Ui.ERROR else Ui.WARNING, true))
            c.addView(t("${str(g, "type")} : ${str(g, "msg")}", 13f, Ui.TEXT))
            c.addView(t("Version ${str(g, "version")} · dernier ${secTime(str(g, "last"))}${if (fatal) " · bloquant" else ""}", 12f, Ui.TEXT2))
            content.addView(c)
        }
    }

    private fun loadSecurity() {
        secLoading = true
        render()
        scope.launch {
            try {
                sec = unwrapObj(api("GET", "/admin/security"))
            } catch (e: Exception) {
                toast(e.message ?: "Erreur réseau")
            }
            secLoading = false
            render()
        }
    }

    private fun secTime(iso: String): String {
        val d = parseIso(iso) ?: return iso
        return SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(d)
    }

    /** Explication en français simple de chaque type d'événement. */
    private fun secLabel(type: String): String = when (type) {
        "admin_login_fail" -> "Mauvais mot de passe sur la connexion admin"
        "many_fails" -> "Beaucoup de refus depuis la même adresse"
        "block" -> "Adresse bloquée"
        "unblock" -> "Adresse débloquée"
        else -> type
    }

    private fun secColor(level: String): Int = when (level) {
        "critique" -> Ui.ERROR
        "attention" -> Ui.WARNING
        else -> Ui.TEXT2
    }

    private fun renderSecurity() {
        val d = sec
        if (d == null) {
            if (secLoading) loadingCard() else {
                val c = card()
                c.addView(t("Données de sécurité indisponibles.", 14f, Ui.TEXT2))
                c.addView(Ui.button(this, "Réessayer") { loadSecurity() })
                content.addView(c)
            }
            return
        }
        val status = str(d, "status")
        val (title, desc, color) = when (status) {
            "alerte" -> Triple("🔴 Alerte", "Des événements graves ont eu lieu ces dernières 24 h. Regarde le journal ci-dessous.", Ui.ERROR)
            "surveillance" -> Triple("🟠 Sous surveillance", "Quelques comportements suspects, sans gravité pour l'instant.", Ui.WARNING)
            else -> Triple("🟢 Tout est calme", "Aucun comportement suspect ces dernières 24 h.", Ui.SUCCESS)
        }
        val head = card()
        head.background = Ui.rounded(this, Ui.withAlpha(color, 0x1F), 18, Ui.withAlpha(color, 0x55))
        head.addView(t(title, 20f, color, true))
        head.addView(t(desc, 13f, Ui.TEXT))
        val lpH = head.layoutParams as LinearLayout.LayoutParams
        lpH.setMargins(0, dp(16), 0, 0)
        content.addView(head)

        val day = d.optJSONObject("day") ?: JSONObject()
        val srv = d.optJSONObject("server") ?: JSONObject()
        content.addView(pair(
            kpi("Alertes graves", n(day.optInt("critical")), "dernières 24 h", Ui.ERROR),
            kpi("Avertissements", n(day.optInt("warnings")), "dernières 24 h", Ui.WARNING),
        ))
        content.addView(pair(
            kpi("Requêtes reçues", n(srv.optInt("requests")), "depuis le démarrage du serveur", Ui.TEXT),
            kpi("Requêtes refusées", n(srv.optInt("refused")), "adresses bloquées", Ui.PRIMARY),
        ))

        val watched = d.optJSONArray("watched") ?: JSONArray()
        if (watched.length() > 0) {
            sectionTitle("Adresses surveillées (10 dernières min)")
            val c = card()
            for (i in 0 until watched.length()) {
                val w = watched.getJSONObject(i)
                c.addView(t("${str(w, "ip")} · ${w.optInt("fails")} refus", 13.5f, Ui.WARNING, true))
            }
            content.addView(c)
        }

        sectionTitle("Adresses bloquées")
        val blocked = d.optJSONArray("blocked") ?: JSONArray()
        val bc = card()
        if (blocked.length() == 0) bc.addView(t("Aucune adresse bloquée.", 13.5f, Ui.TEXT2))
        for (i in 0 until blocked.length()) {
            val b = blocked.getJSONObject(i)
            val ip = str(b, "ip")
            val until = str(b, "until")
            bc.addView(t(ip, 15f, Ui.TEXT, true))
            bc.addView(t(str(b, "reason"), 12.5f, Ui.TEXT2))
            bc.addView(t(if (until.isBlank()) "Bloquée définitivement" else "Jusqu'au ${secTime(until)} · par ${str(b, "created_by")}", 12f, Ui.TEXT2))
            bc.addView(Ui.button(this, "Débloquer $ip", "secondary") {
                scope.launch {
                    try {
                        api("POST", "/admin/security/unblock", JSONObject().put("ip", ip))
                        toast("$ip débloquée")
                        loadSecurity()
                    } catch (e: Exception) {
                        toast(e.message ?: "Erreur réseau")
                    }
                }
            })
        }
        content.addView(bc)

        sectionTitle("Bloquer une adresse")
        val fc = card()
        val ipIn = Ui.input(this, "Adresse IP (ex. 102.180.12.34)")
        fc.addView(ipIn)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((mins, label) in listOf(60 to "1 heure", 1440 to "24 heures", 0 to "Définitif")) {
            val btn = Ui.button(this, label, if (mins == 0) "primary" else "secondary") {
                val ip = ipIn.text.toString().trim()
                if (!Regex("^[0-9a-fA-F:.]{3,64}$").matches(ip)) {
                    toast("Adresse invalide")
                    return@button
                }
                scope.launch {
                    try {
                        val body = JSONObject().put("ip", ip).put("reason", "Bloquée par l'admin")
                        if (mins > 0) body.put("minutes", mins)
                        api("POST", "/admin/security/block", body)
                        toast("$ip bloquée")
                        loadSecurity()
                    } catch (e: Exception) {
                        toast(e.message ?: "Erreur réseau")
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(0, KW, 1f)
            lp.setMargins(dp(2), dp(8), dp(2), 0)
            row.addView(btn, lp)
        }
        fc.addView(row)
        content.addView(fc)

        sectionTitle("Journal de sécurité")
        val events = d.optJSONArray("events") ?: JSONArray()
        val ec = card()
        if (events.length() == 0) ec.addView(t("Rien à signaler.", 13.5f, Ui.TEXT2))
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val lvl = str(e, "level")
            ec.addView(t("${secTime(str(e, "created_at"))} · ${secLabel(str(e, "type"))}", 13.5f, secColor(lvl), true))
            ec.addView(t("${str(e, "ip")} — ${str(e, "detail")}", 12.5f, Ui.TEXT2))
            if (i < events.length() - 1) ec.addView(View(this).also { it.minimumHeight = dp(10) })
        }
        content.addView(ec)

        val rules = d.optJSONObject("rules") ?: JSONObject()
        sectionTitle("Comment ça marche ?")
        val hc = card()
        hc.addView(t(
            "• Chaque requête qui arrive sur le serveur passe d'abord par un « garde-barrière ».\n" +
                "• Si une même adresse envoie plus de ${rules.optInt("floodPerMinute")} requêtes en une minute, elle est bloquée ${rules.optInt("autoBlockMinutes")} min (attaque par inondation).\n" +
                "• Si une adresse reçoit ${rules.optInt("failsBlock")} refus en 10 min (mots de passe faux, accès interdits), elle est bloquée ${rules.optInt("autoBlockMinutes")} min (attaque par essais).\n" +
                "• Toute tentative ratée sur la connexion admin est notée et t'est signalée par notification.\n" +
                "• Toi, connecté ici, tu n'es jamais bloqué : tu peux toujours débloquer une adresse.\n" +
                "• Au Burkina, beaucoup de téléphones partagent la même adresse chez l'opérateur : si de vrais utilisateurs se plaignent, débloque l'adresse.",
            13f, Ui.TEXT2,
        ))
        content.addView(hc)
    }

    // ---------- compte administrateur ----------

    private fun renderAccount() {
        sectionTitle("Mon compte")
        val c = card()
        c.addView(t(SessionStorage.getDisplayName(this).orEmpty().ifBlank { "Administrateur" }, 17f, Ui.TEXT, true))
        c.addView(t("Identifiant : ${SessionStorage.getIdentifier(this).orEmpty()}", 13f, Ui.TEXT2))
        c.addView(t("Rôle : administrateur (seul compte autorisé)", 13f, Ui.TEXT2))
        content.addView(c)

        sectionTitle("Changer le mot de passe")
        val f = card()
        val cur = Ui.input(this, "Mot de passe actuel", password = true)
        val nw = Ui.input(this, "Nouveau mot de passe (8 caractères minimum)", password = true)
        val nw2 = Ui.input(this, "Confirmer le nouveau mot de passe", password = true)
        f.addView(cur)
        f.addView(nw)
        f.addView(nw2)
        val msg = t("", 13f, Ui.TEXT2)
        f.addView(msg)
        f.addView(Ui.button(this, "Enregistrer le nouveau mot de passe") {
            val a = cur.text.toString()
            val b = nw.text.toString()
            when {
                a.isBlank() || b.isBlank() -> { msg.setTextColor(Ui.ERROR); msg.text = "Remplis tous les champs." }
                b.length < 8 -> { msg.setTextColor(Ui.ERROR); msg.text = "8 caractères minimum." }
                b != nw2.text.toString() -> { msg.setTextColor(Ui.ERROR); msg.text = "Les deux nouveaux mots de passe sont différents." }
                else -> {
                    msg.setTextColor(Ui.TEXT2)
                    msg.text = "Enregistrement…"
                    scope.launch {
                        try {
                            api("POST", "/auth/manager/password", JSONObject().put("currentPassword", a).put("newPassword", b))
                            cur.setText("")
                            nw.setText("")
                            nw2.setText("")
                            msg.setTextColor(Ui.SUCCESS)
                            msg.text = "Mot de passe changé ✓"
                        } catch (e: Exception) {
                            msg.setTextColor(Ui.ERROR)
                            msg.text = e.message ?: "Erreur réseau"
                        }
                    }
                }
            }
        })
        content.addView(f)

        sectionTitle("Session")
        val s = card()
        s.addView(t("Se déconnecter de ce téléphone. Tu devras saisir à nouveau ton identifiant et ton mot de passe.", 13f, Ui.TEXT2))
        s.addView(Ui.button(this, "Se déconnecter", "secondary") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setPositiveButton("Oui") { _, _ ->
                    PushRegistration.unregister(this)
                    SessionStorage.clear(this)
                    val i = Intent(this, LoginActivity::class.java)
                    i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(i)
                    finish()
                }
                .setNegativeButton("Annuler", null)
                .show()
        })
        content.addView(s)
    }

    private fun loadingCard() {
        val c = card()
        c.addView(t("Chargement…", 13f, Ui.TEXT2))
        content.addView(c)
    }

    private fun periodLabel(): String = when (pKey) {
        "today" -> "aujourd'hui"
        "yesterday" -> "hier"
        "month" -> "ce mois"
        "year" -> "année ${pTo.take(4)}"
        "all" -> "depuis le début"
        else -> if (pFrom == pTo) "le ${frDay(pFrom, false)}" else "du ${frDay(pFrom, false)} au ${frDay(pTo, false)}"
    }

    private fun prevName(): String = when (pKey) {
        "today" -> "hier"
        "yesterday" -> "avant-hier"
        else -> "la période d'avant"
    }

    // ---------- période (dates) ----------

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun dayStr(d: Date): String = dayFmt.format(d)

    private fun parseDay(v: String): Date? = try { dayFmt.parse(v) } catch (e: Exception) { null }

    /** « 29 octobre 2026 » ; « 1er » pour le premier du mois. */
    private fun frDay(v: String, withYear: Boolean = true): String {
        val d = parseDay(v) ?: return v
        val fmt = SimpleDateFormat(if (withYear) "d MMMM yyyy" else "d MMMM", Locale.FRANCE)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(d).replace(Regex("^1 "), "1er ")
    }

    private fun frMonth(v: String): String {
        val d = parseDay(if (v.length == 7) "$v-01" else v) ?: return v
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(d)
    }

    private fun setPeriod(key: String, from: String = "", to: String = "") {
        val now = Date()
        val today = dayStr(now)
        pKey = key
        when (key) {
            "yesterday" -> {
                val y = dayStr(Date(now.time - 86400000L))
                pFrom = y
                pTo = y
            }
            "month" -> {
                pFrom = today.substring(0, 8) + "01"
                pTo = today
            }
            "year" -> {
                pFrom = today.substring(0, 4) + "-01-01"
                pTo = today
            }
            "all" -> {
                pFrom = "2020-01-01"
                pTo = today
            }
            "custom" -> {
                pFrom = if (from <= to) from else to
                pTo = if (from <= to) to else from
            }
            else -> {
                pKey = "today"
                pFrom = today
                pTo = today
            }
        }
    }

    private fun periodText(): String {
        val r = range
        val from = r?.optString("from")?.ifEmpty { null } ?: pFrom
        val to = r?.optString("to")?.ifEmpty { null } ?: pTo
        val nd = r?.optInt("days") ?: 0
        val txt = when (pKey) {
            "today" -> "Aujourd'hui · ${frDay(to)}"
            "yesterday" -> "Hier · ${frDay(to)}"
            "month" -> frMonth(to).replaceFirstChar { it.uppercase() } + " · du ${frDay(from, false)} au ${frDay(to, false)}"
            "year" -> "Année ${to.take(4)} · du ${frDay(from, false)} au ${frDay(to, false)}"
            "all" -> "Depuis le début · du ${frDay(from)} au ${frDay(to)}"
            else -> if (from == to) "Le ${frDay(from)}" else "Du ${frDay(from)} au ${frDay(to)}"
        }
        return if (nd > 1) "$txt · $nd jours" else txt
    }

    private fun renderPeriodBar() {
        if (pFrom.isEmpty()) setPeriod(pKey)
        val year = dayStr(Date()).substring(0, 4)
        val rows = listOf(
            listOf("today" to "Aujourd'hui", "yesterday" to "Hier", "month" to "Ce mois"),
            listOf("year" to "Année $year", "all" to "Tous", "custom" to "📅  Calendrier"),
        )
        rows.forEachIndexed { ri, row ->
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, if (ri == 0) dp(16) else dp(6), 0, 0)
            for ((k, label) in row) {
                val on = k == pKey
                val c = t(label, 13f, if (on) Color.WHITE else Ui.TEXT2, true)
                c.gravity = Gravity.CENTER
                c.maxLines = 1
                c.setPadding(0, dp(9), 0, dp(9))
                c.background = Ui.rounded(this, if (on) Ui.PRIMARY else Ui.SURFACE, 12, if (on) null else Ui.BORDER)
                val lp = LinearLayout.LayoutParams(0, KW, 1f)
                lp.setMargins(dp(3), 0, dp(3), 0)
                c.setOnClickListener {
                    if (!loading) {
                        if (k == "custom") {
                            pickCalendar()
                        } else if (k != pKey) {
                            setPeriod(k)
                            loadAll()
                        }
                    }
                }
                line.addView(c, lp)
            }
            content.addView(line)
        }
        val p = t(periodText(), 12.5f, Ui.TEXT2, true)
        p.setPadding(dp(4), dp(10), 0, 0)
        content.addView(p)
    }

    private fun pickCalendar() {
        AlertDialog.Builder(this)
            .setTitle("Calendrier")
            .setItems(arrayOf("Un jour précis", "Une période (du … au …)")) { _, which ->
                pickDay(if (which == 0) "Choisis le jour" else "À partir de quel jour ?", null) { d ->
                    if (which == 0) {
                        setPeriod("custom", d, d)
                        loadAll()
                    } else {
                        pickDay("Jusqu'à quel jour ?", d) { e ->
                            setPeriod("custom", d, e)
                            loadAll()
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickDay(title: String, min: String?, onPick: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = parseDay(min ?: if (pKey == "custom" && pTo.isNotEmpty()) pTo else dayStr(Date())) ?: Date()
        val dlg = android.app.DatePickerDialog(
            this,
            { _, y, m, d -> onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        dlg.setTitle(title)
        dlg.datePicker.maxDate = System.currentTimeMillis()
        if (min != null) dlg.datePicker.minDate = parseDay(min)?.time ?: System.currentTimeMillis()
        dlg.show()
    }

    // ---------- résumés en texte ----------

    private fun pctTxt(cur: Double, prev: Double?): String {
        if (prev == null || pKey == "all") return ""
        if (prev <= 0.0) return if (cur > 0.0) " (en hausse par rapport à ${prevName()})" else ""
        val p = (cur - prev) * 100.0 / prev
        return when {
            p >= 0.5 -> String.format(Locale.FRANCE, " (+%.0f %% par rapport à %s)", p, prevName())
            p <= -0.5 -> String.format(Locale.FRANCE, " (−%.0f %% par rapport à %s)", -p, prevName())
            else -> " (stable par rapport à ${prevName()})"
        }
    }

    private fun plural(v: Int, one: String, many: String): String = "${n(v)} " + if (v > 1) many else one

    private fun summaryAudience(): String {
        val r = range ?: return ""
        val cur = r.optJSONObject("cur") ?: JSONObject()
        val prev = r.optJSONObject("prev")
        val a = r.optJSONObject("audience") ?: JSONObject()
        val visits = cur.optInt("visits")
        if (visits == 0) return "Aucune visite enregistrée sur cette période."
        val nd = r.optInt("days")
        val avg = cur.optDouble("avgDaily", 0.0)
        val avgS = String.format(Locale.FRANCE, "%.1f", avg).removeSuffix(",0")
        val sb = StringBuilder()
        if (nd <= 1) {
            sb.append("${plural(visits, "utilisateur a", "utilisateurs ont")} ouvert l'application${pctTxt(visits.toDouble(), prev?.optDouble("visits"))}.")
        } else {
            sb.append("${plural(visits, "visite", "visites")}, soit $avgS utilisateurs par jour en moyenne${pctTxt(avg, prev?.optDouble("avgDaily"))}.")
            val pk = a.optJSONObject("peak")
            if (pk != null && pk.optInt("users") > 0) sb.append(" Record : ${plural(pk.optInt("users"), "utilisateur", "utilisateurs")} le ${frDay(str(pk, "day"), false)}.")
            if (!a.isNull("unique")) sb.append(" Au total : ${plural(a.optInt("unique"), "téléphone différent", "téléphones différents")}.")
        }
        val nu = cur.optInt("newUsers")
        val acc = cur.optInt("accounts")
        if (nu > 0) {
            sb.append(" ${plural(nu, "nouvel utilisateur a", "nouveaux utilisateurs ont")} découvert KOUNADIA${pctTxt(nu.toDouble(), prev?.optDouble("newUsers"))}")
            sb.append(if (acc > 0) " et ${plural(acc, "compte a été créé", "comptes ont été créés")}." else ".")
        } else if (acc > 0) {
            sb.append(" ${plural(acc, "compte a été créé", "comptes ont été créés")}.")
        }
        return sb.toString()
    }

    private fun summaryMoney(): String {
        val r = range ?: return ""
        val m = r.optJSONObject("money") ?: return ""
        val prev = r.optJSONObject("prev")
        val cnt = m.optInt("n")
        if (cnt == 0) return "Aucun abonnement payé sur cette période."
        val sb = StringBuilder()
        sb.append("${plural(cnt, "abonnement payé", "abonnements payés")} par ${plural(m.optInt("payers"), "client", "clients")} : ")
        sb.append("${f(m.optInt("gross"))} encaissés, ${f(m.optInt("fee"))} de frais MoneyFusion, soit ${f(m.optInt("net"))} net${pctTxt(m.optDouble("net", 0.0), prev?.optDouble("net"))}.")
        sb.append(" Panier moyen : ${f(m.optInt("gross") / cnt)}.")
        val plans = m.optJSONArray("plans")
        if (plans != null && plans.length() > 0) {
            val p0 = plans.getJSONObject(0)
            sb.append(" Offre la plus vendue : ${planName(str(p0, "plan"))} (${plural(p0.optInt("n"), "vente", "ventes")}, ${f(p0.optInt("gross"))}).")
        }
        val best = m.optJSONObject("best")
        if (best != null && r.optInt("days") > 1) {
            val k = str(best, "key")
            sb.append(if (m.optBoolean("monthly")) " Meilleur mois : ${frMonth(k)} avec ${f(best.optInt("net"))} net." else " Meilleur jour : ${frDay(k, false)} avec ${f(best.optInt("net"))} net.")
        }
        return sb.toString()
    }

    private fun summaryUsage(): String {
        val a = range?.optJSONObject("audience") ?: return ""
        val skip = setOf("app_open", "tab_view", "ad_view", "ad_click", "crash")
        val feats = ArrayList<Pair<String, Int>>()
        val fa = a.optJSONArray("features") ?: JSONArray()
        for (i in 0 until fa.length()) {
            val o = fa.getJSONObject(i)
            if (str(o, "name") !in skip) feats.add(featureLabel(str(o, "name")) to o.optInt("count"))
        }
        feats.sortByDescending { it.second }
        val sb = StringBuilder()
        val top = feats.take(3).map { "${it.first.replaceFirstChar { c -> c.lowercase() }} (${n(it.second)})" }
        if (top.isNotEmpty()) {
            sb.append("Ce qui intéresse le plus : ")
            sb.append(if (top.size == 1) top[0] else top.dropLast(1).joinToString(", ") + " et " + top.last())
            sb.append(".")
        }
        val ta = a.optJSONArray("tabs") ?: JSONArray()
        var bestTab = ""
        var bestN = 0
        for (i in 0 until ta.length()) {
            val o = ta.getJSONObject(i)
            if (o.optInt("count") > bestN) {
                bestN = o.optInt("count")
                bestTab = str(o, "tab")
            }
        }
        if (bestTab.isNotEmpty()) sb.append(" Onglet le plus ouvert : $bestTab (${n(bestN)} fois).")
        val ads = a.optJSONArray("ads") ?: JSONArray()
        var v = 0
        var c = 0
        for (i in 0 until ads.length()) {
            v += ads.getJSONObject(i).optInt("views")
            c += ads.getJSONObject(i).optInt("clicks")
        }
        if (v > 0) sb.append(String.format(Locale.FRANCE, " Publicités : %s vues, %s clics (%.1f %%).", n(v), n(c), c * 100.0 / v))
        return sb.toString().trim()
    }

    private fun summaryCard(parts: List<String>) {
        val list = parts.filter { it.isNotBlank() }
        if (list.isEmpty()) return
        val c = card()
        c.background = Ui.rounded(this, Ui.withAlpha(Ui.PRIMARY, 0x18), 18, Ui.withAlpha(Ui.PRIMARY, 0x55))
        c.addView(t("En bref · ${periodLabel()}", 14f, Ui.TEXT, true))
        for (p in list) {
            val tv = t(p, 13.5f, Ui.TEXT)
            tv.setPadding(0, dp(8), 0, 0)
            tv.setLineSpacing(0f, 1.18f)
            c.addView(tv)
        }
        val lp = c.layoutParams as LinearLayout.LayoutParams
        lp.setMargins(0, dp(12), 0, 0)
        content.addView(c)
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
                    pct >= 0.5 -> Pair(String.format(Locale.FRANCE, "▲ %.0f %% vs %s", pct, prevShort()), Ui.SUCCESS)
                    pct <= -0.5 -> Pair(String.format(Locale.FRANCE, "▼ %.0f %% vs %s", -pct, prevShort()), Ui.ERROR)
                    else -> Pair("= stable", Ui.TEXT2)
                }
            }
        }
        val box = kpi(label, value, txt, accent) as LinearLayout
        (box.getChildAt(box.childCount - 1) as TextView).setTextColor(col)
        if (onClick != null) Ui.pressable(box, onClick)
        return box
    }

    private fun prevShort(): String = when (pKey) {
        "today" -> "hier"
        "yesterday" -> "avant-hier"
        else -> "avant"
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
        summaryCard(listOf(summaryAudience(), summaryMoney()))
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
        downloads?.let { d ->
            content.addView(
                pair(
                    kpi("Téléchargements", n(d.optInt("total")), "APK depuis le lancement", Ui.PRIMARY),
                    kpi("Version ${d.optString("ver")}", n(d.optInt("latest")), "téléchargements de cette version", Ui.SUCCESS),
                ),
            )
        }
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
        val m = range?.optJSONObject("money") ?: return
        val arr = m.optJSONArray("series") ?: return
        if (arr.length() < 2) return
        val monthly = m.optBoolean("monthly")
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
        c.addView(t("Total net : ${f(total)} · ${periodLabel()}", 12.5f, Ui.TEXT2, true))
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
        "code_copy" -> "Codes 1xBet copiés"
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
        val cur = range?.optJSONObject("cur") ?: JSONObject()
        val prev = range?.optJSONObject("prev")
        fun pv(k: String): Double? = prev?.optDouble(k, 0.0)
        summaryCard(listOf(summaryAudience(), summaryUsage()))
        sectionTitle("Audience · ${periodLabel()}")
        val avg = cur.optDouble("avgDaily", 0.0)
        content.addView(
            pair(
                kpiTrend("Visites", n(cur.optInt("visits")), cur.optDouble("visits", 0.0), pv("visits"), Ui.TEXT),
                kpiTrend("Utilisateurs / jour", String.format(Locale.FRANCE, "%.1f", avg).removeSuffix(",0"), avg, pv("avgDaily"), Ui.PRIMARY),
            ),
        )
        content.addView(
            pair(
                kpiTrend("Nouveaux", n(cur.optInt("newUsers")), cur.optDouble("newUsers", 0.0), pv("newUsers"), Ui.SUCCESS),
                kpiTrend("Comptes créés", n(cur.optInt("accounts")), cur.optDouble("accounts", 0.0), pv("accounts"), Ui.WARNING),
            ),
        )
        val pk = s.optJSONObject("peak")
        val nd = range?.optInt("days") ?: 1
        val uniq = if (s.isNull("unique")) null else s.optInt("unique")
        content.addView(
            pair(
                if (uniq != null) kpi("Téléphones différents", n(uniq), periodLabel(), Ui.TEXT)
                else kpi("Record", n(pk?.optInt("users") ?: 0), if (pk != null) "utilisateurs le ${frDay(str(pk, "day"), false)}" else "–", Ui.TEXT),
                kpiTrend("Actions", n(cur.optInt("events")), cur.optDouble("events", 0.0), pv("events"), Ui.TEXT2),
            ),
        )
        if (nd > 1) {
            content.addView(
                pair(
                    kpi("Record", n(pk?.optInt("users") ?: 0), if (pk != null) "utilisateurs le ${frDay(str(pk, "day"), false)}" else "–", Ui.SUCCESS),
                    kpi("Jours d'activité", n(s.optInt("activeDays")), "sur ${plural(nd, "jour", "jours")}", Ui.PRIMARY),
                ),
            )
        }

        val daily = s.optJSONArray("daily") ?: JSONArray()
        if (daily.length() > 1) {
            sectionTitle("Utilisateurs par jour · ${periodLabel()}")
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

        if (cur.optInt("events") == 0 && cur.optInt("visits") == 0) {
            val c = card()
            c.addView(t("Aucune donnée sur cette période.", 13f, Ui.TEXT2))
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
        val m = range?.optJSONObject("money") ?: JSONObject()
        val prev = range?.optJSONObject("prev")
        fun pv(k: String): Double? = prev?.optDouble(k, 0.0)
        summaryCard(listOf(summaryMoney()))
        sectionTitle("Chiffre d'affaires · ${periodLabel()}")
        content.addView(
            pair(
                kpiTrend("Encaissé (net)", f(m.optInt("net")), m.optDouble("net", 0.0), pv("net"), Ui.SUCCESS),
                kpiTrend("Paiements", n(m.optInt("n")), m.optDouble("n", 0.0), pv("payments"), Ui.TEXT),
            ),
        )
        val gross = m.optInt("gross")
        val fee = m.optInt("fee")
        val rate = if (gross > 0) String.format(Locale.FRANCE, "%.1f %%", fee * 100.0 / gross) else "–"
        content.addView(
            pair(
                kpiTrend("Brut", f(gross), gross.toDouble(), pv("gross"), Ui.PRIMARY),
                kpi("Frais", f(fee), "MoneyFusion · $rate", Ui.WARNING),
            ),
        )
        val cnt = m.optInt("n")
        content.addView(
            pair(
                kpi("Clients", n(m.optInt("payers")), "ont payé sur la période", Ui.TEXT),
                kpi("Panier moyen", if (cnt > 0) f(gross / cnt) else "–", "par abonnement", Ui.PRIMARY),
            ),
        )
        val users = b.optInt("users")
        val payers = b.optInt("payers")
        val conv = if (users > 0) String.format(Locale.FRANCE, "%.1f %%", payers * 100.0 / users) else "–"
        sectionTitle("En ce moment")
        content.addView(
            pair(
                kpi("Abonnés actifs", n(b.optInt("active")), "${n(b.optInt("expiring"))} expirent sous 3 j", Ui.SUCCESS),
                kpi("Comptes", n(users), "dont ${n(payers)} déjà payants · $conv", Ui.TEXT),
            ),
        )
        val plans = m.optJSONArray("plans") ?: JSONArray()
        if (plans.length() > 0) {
            sectionTitle("Offres vendues · ${periodLabel()}")
            val rows = ArrayList<Triple<String, Int, String>>()
            for (i in 0 until plans.length()) {
                val o = plans.getJSONObject(i)
                val share = if (gross > 0) String.format(Locale.FRANCE, " · %.0f %% du brut", o.optInt("gross") * 100.0 / gross) else ""
                rows.add(Triple(planName(str(o, "plan")), o.optInt("n"), "${f(o.optInt("gross"))} brut · ${f(o.optInt("net"))} net$share"))
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

    // ---------- codes 1xBet des coupons du jour ----------

    private val codeList = listOf(
        "single" to "Single du jour",
        "free" to "Coupon gratuit",
        "vip2" to "Coupon VIP · cote 2",
        "vip3" to "Coupon VIP · cote 3",
        "vip5" to "Coupon VIP · cote 5",
        "c2" to "Coups sûrs · coupon cote 2+",
        "c5" to "Coups sûrs · coupon cote 5+",
        "c10" to "Coups sûrs · coupon cote 10+",
    )

    /** Appel au robot KOUNADIA (code HTTP + texte), null sans connexion. */
    private fun robotCall(method: String, url: String, token: String?, body: JSONObject?): Pair<Int, String>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 90000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        Pair(code, text)
    } catch (e: Exception) {
        null
    }

    private fun loadCodes() {
        codesLoading = true
        render()
        scope.launch {
            val token = SessionStorage.getToken(this@KounadiaAdminActivity)
            val r = withContext(Dispatchers.IO) { robotCall("GET", "$ROBOT/codes?admin=1", token, null) }
            if (r?.first == 200) codes = try { JSONObject(r.second) } catch (e: Exception) { codes }
            codesLoading = false
            if (tab == 11) render()
        }
    }

    private fun renderCodes() {
        val today = dayStr(Date())
        val cur = codes
        val saved = if (cur != null && str(cur, "day") == today) cur.optJSONObject("codes") ?: JSONObject() else JSONObject()
        val help = card()
        help.addView(t("Comment faire", 14f, Ui.TEXT, true))
        val steps = t(
            "1. Ouvre le coupon dans l'app KOUNADIA et refais-le sur 1xBet.\n" +
                "2. Sur 1xBet, enregistre le coupon pour obtenir son code.\n" +
                "3. Colle le code ici, puis touche « Enregistrer les codes ».\n\n" +
                "Les utilisateurs voient le code sur la carte du coupon, avec un bouton « Copier », puis le chargent dans 1xBet. " +
                "Single et coupon gratuit : visibles par tous. Coupons VIP et Coups sûrs : seulement par les abonnés Premium. " +
                "Les codes disparaissent d'eux-mêmes le lendemain.",
            13f, Ui.TEXT2,
        )
        steps.setPadding(0, dp(6), 0, 0)
        steps.setLineSpacing(0f, 1.15f)
        help.addView(steps)
        val hl = help.layoutParams as LinearLayout.LayoutParams
        hl.setMargins(0, dp(12), 0, dp(4))
        content.addView(help)
        if (codesLoading) {
            loadingCard()
            return
        }
        sectionTitle("Codes du ${frDay(today)}")
        val inputs = LinkedHashMap<String, EditText>()
        val c = card()
        for ((k, label) in codeList) {
            val l = t(label, 13f, if (saved.optString(k, "").isNotEmpty()) Ui.SUCCESS else Ui.TEXT2, true)
            l.setPadding(0, if (inputs.isEmpty()) 0 else dp(12), 0, dp(4))
            c.addView(l)
            val e = EditText(this)
            e.setText(saved.optString(k, ""))
            e.hint = "Code 1xBet (vide = aucun)"
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24), InputFilter.AllCaps())
            e.setTextColor(Ui.TEXT)
            e.setHintTextColor(Ui.TEXT2)
            e.textSize = 17f
            e.typeface = android.graphics.Typeface.MONOSPACE
            e.setSingleLine(true)
            e.setPadding(dp(12), dp(10), dp(12), dp(10))
            e.background = Ui.rounded(this, Ui.ELEVATED, 12, Ui.BORDER)
            c.addView(e, LinearLayout.LayoutParams(KM, KW))
            inputs[k] = e
        }
        content.addView(c)
        val save = Ui.button(this, "Enregistrer les codes") { saveCodes(today, inputs.mapValues { it.value.text.toString() }) }
        content.addView(save)
    }

    private fun saveCodes(day: String, values: Map<String, String>) {
        val cs = JSONObject()
        for ((k, v) in values) {
            val x = v.filter { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') }.uppercase()
            if (x.isNotEmpty() && x.length < 4) {
                toast("Code trop court : " + (codeList.firstOrNull { it.first == k }?.second ?: k))
                return
            }
            cs.put(k, x)
        }
        val token = SessionStorage.getToken(this)
        if (token == null) {
            toast("Session expirée, reconnecte-toi.")
            return
        }
        toast("Enregistrement…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { robotCall("POST", "$ROBOT/codes", token, JSONObject().put("day", day).put("codes", cs)) }
            when (r?.first) {
                200 -> {
                    codes = try { JSONObject(r.second) } catch (e: Exception) { codes }
                    toast("Codes enregistrés : visibles dans l'app d'ici 2 minutes")
                    if (tab == 11) render()
                }
                401, 403 -> toast("Accès refusé : reconnecte-toi.")
                400 -> toast(try { JSONObject(r.second).optString("error", "Code invalide.") } catch (e: Exception) { "Code invalide." })
                else -> toast("Pas de connexion. Réessaie.")
            }
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

    // ---------- pub du jour (images des coups sûrs à partager) ----------

    private fun pubFile(name: String): java.io.File {
        val dir = java.io.File(cacheDir, "pub")
        dir.mkdirs()
        return java.io.File(dir, name.replace('/', '_'))
    }

    /** Télécharge un fichier (image) ; null si échec. */
    private fun fetchBytes(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "KOUNADIA-Admin")
        val b = c.inputStream.use { it.readBytes() }
        c.disconnect()
        b
    } catch (e: Exception) {
        null
    }

    private fun loadPub() {
        pubLoading = true
        pubError = ""
        render()
        scope.launch {
            val day = withContext(Dispatchers.IO) { httpJson("$PUB_BASE/latest.json?t=${System.currentTimeMillis() / 60000}") }
            if (day == null) {
                pubError = "Les images ne sont pas encore prêtes. Elles arrivent chaque matin vers 8 h."
            } else {
                pubDay = day
                val items = day.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    for (file in listOf(str(item, "file"), str(item, "rfile"))) {
                        if (file.isEmpty() || pubImages.containsKey(file)) continue
                        val f = pubFile(file)
                        val bmp = withContext(Dispatchers.IO) {
                            val bytes = if (f.exists() && f.length() > 0) f.readBytes() else fetchBytes("$PUB_BASE/$file")?.also { b -> f.writeBytes(b) }
                            bytes?.let { b -> decodeScaled(b, 720) }
                        }
                        if (bmp != null) pubImages[file] = bmp
                    }
                }
            }
            pubLoading = false
            if (tab == 10) render()
        }
    }

    private fun sharePub(files: List<String>) {
        try {
            val uris = ArrayList<Uri>()
            for (file in files) {
                val f = pubFile(file)
                if (f.exists() && f.length() > 0) uris.add(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f))
            }
            if (uris.isEmpty()) {
                toast("Image pas encore téléchargée, réessaie.")
                return
            }
            val res = files.any { it.contains("-res-") }
            val text = (if (res) "✅ Résultats des Coups sûrs KOUNADIA" else "⚽ Coups sûrs du jour sur KOUNADIA") + " 👉 $SITE\n18+ · Aucun gain garanti"
            val i = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            i.type = "image/png"
            i.putExtra(Intent.EXTRA_TEXT, text)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Partager la pub"))
        } catch (e: Exception) {
            toast("Partage impossible pour le moment.")
        }
    }

    private fun renderPub() {
        val d = pubDay
        sectionTitle(if (d != null) str(d, "label") else "Pub du jour")
        if (pubLoading && d == null) {
            val c = card()
            c.addView(t("Chargement des images…", 13f, Ui.TEXT2))
            content.addView(c)
            return
        }
        if (d == null) {
            val c = card()
            c.addView(t(pubError.ifEmpty { "Aucune image pour le moment." }, 13f, Ui.TEXT2))
            content.addView(c)
            content.addView(Ui.button(this, "Réessayer", "secondary") { loadPub() })
            return
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        if (str(d, "day") != today) {
            val c = card()
            c.addView(t("Ce sont les images d'hier. Celles d'aujourd'hui arrivent vers 8 h.", 13f, Ui.WARNING, true))
            content.addView(c)
        }
        val items = d.optJSONArray("items") ?: JSONArray()
        val all = ArrayList<String>()
        val allRes = ArrayList<String>()
        for (i in 0 until items.length()) {
            str(items.getJSONObject(i), "file").takeIf { it.isNotEmpty() }?.let { all.add(it) }
            str(items.getJSONObject(i), "rfile").takeIf { it.isNotEmpty() }?.let { allRes.add(it) }
        }
        if (all.size > 1) {
            val b = Ui.button(this, "Partager les pronostics (${all.size} images)") { sharePub(all) }
            (b.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(if (allRes.size > 1) 0 else 12))
            content.addView(b)
        }
        if (allRes.size > 1) {
            val b = Ui.button(this, "Partager les résultats (${allRes.size} images)", "secondary") { sharePub(allRes) }
            (b.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, dp(12))
            content.addView(b)
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val file = str(item, "file")
            val c = card()
            c.addView(t(str(item, "title"), 16f, Ui.TEXT, true))
            c.addView(t(str(item, "sub"), 12.5f, Ui.TEXT2))
            val bmp = pubImages[file]
            if (bmp != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.adjustViewBounds = true
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                val lp = LinearLayout.LayoutParams(KM, KW)
                lp.setMargins(0, dp(10), 0, 0)
                c.addView(iv, lp)
                Ui.pressable(iv) { sharePub(listOf(file)) }
            } else {
                c.addView(t(if (pubLoading) "Chargement…" else "Image indisponible", 13f, Ui.TEXT2))
            }
            c.addView(Ui.button(this, "Partager les pronostics", "secondary") { sharePub(listOf(file)) })
            val rfile = str(item, "rfile")
            if (rfile.isNotEmpty()) {
                val rt = t("Résultats · " + str(item, "rsub"), 14f, Ui.SUCCESS, true)
                rt.setPadding(0, dp(18), 0, 0)
                c.addView(rt)
                val rb = pubImages[rfile]
                if (rb != null) {
                    val iv = ImageView(this)
                    iv.setImageBitmap(rb)
                    iv.adjustViewBounds = true
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    val lp = LinearLayout.LayoutParams(KM, KW)
                    lp.setMargins(0, dp(10), 0, 0)
                    c.addView(iv, lp)
                    Ui.pressable(iv) { sharePub(listOf(rfile)) }
                }
                c.addView(Ui.button(this, "Partager les résultats") { sharePub(listOf(rfile)) })
            }
            content.addView(c)
        }
        val note = t("Les matchs déjà commencés ne sont pas sur les images. 18+ · Aucun gain garanti.", 12f, Ui.TEXT2)
        note.setPadding(0, dp(4), 0, dp(16))
        content.addView(note)
    }

    private fun renderAds(s: JSONObject?) {
        sectionTitle("Images à partager")
        content.addView(menuCard(R.drawable.ic_megaphone, Ui.SUCCESS, "Pub du jour", "Coups sûrs du jour en images (dès 8 h), à partager") { go(10) })
        sectionTitle("Coupons")
        content.addView(menuCard(R.drawable.ic_code, Ui.WARNING, "Codes coupons 1xBet", "Mets le code 1xBet de chaque coupon du jour : les utilisateurs le copient") { go(11) })
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

    /** Pays ciblés par une pub (vide = tous les pays). */
    private fun countriesOf(ad: JSONObject?): ArrayList<String> {
        val out = ArrayList<String>()
        val a = ad?.optJSONArray("countries") ?: return out
        for (i in 0 until a.length()) a.optString(i).takeIf { it.length == 2 }?.let { out.add(it) }
        return out
    }

    private fun countriesLabel(list: List<String>): String = when {
        list.isEmpty() -> "Tous les pays"
        list.size <= 3 -> list.joinToString(", ") { countryName(it) }
        else -> list.take(2).joinToString(", ") { countryName(it) } + " + ${list.size - 2} autres"
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
        c.addView(t("🌍 " + countriesLabel(countriesOf(ad)), 12f, Ui.TEXT2).also { it.setPadding(0, dp(6), 0, 0) })

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

    /** Numéro WhatsApp de KOUNADIA SERVICE : enregistré dans le robot, lu par l'app, le site et la Pub du jour. */
    private fun editWhatsApp() {
        scope.launch {
            val cur = withContext(Dispatchers.IO) { httpJson("$ROBOT/contact") }?.optString("wa", "") ?: ""
            val input = EditText(this@KounadiaAdminActivity)
            input.inputType = InputType.TYPE_CLASS_PHONE
            input.setText(if (cur.isNotEmpty()) "+$cur" else "+226")
            input.setSelection(input.text.length)
            val box = FrameLayout(this@KounadiaAdminActivity)
            box.setPadding(dp(20), dp(8), dp(20), 0)
            box.addView(input)
            AlertDialog.Builder(this@KounadiaAdminActivity)
                .setTitle("Numéro WhatsApp")
                .setMessage("Avec l'indicatif du pays, ex. +226 52 57 12 01. Il change partout en quelques minutes.")
                .setView(box)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer") { _, _ -> saveWhatsApp(input.text.toString()) }
                .show()
        }
    }

    private fun saveWhatsApp(raw: String) {
        val wa = raw.filter { it.isDigit() }
        if (wa.length !in 8..15) {
            toast("Numéro invalide : mets l'indicatif, ex. +226 52 57 12 01")
            return
        }
        val token = SessionStorage.getToken(this)
        if (token == null) {
            toast("Session expirée, reconnecte-toi.")
            return
        }
        toast("Enregistrement…")
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                try {
                    val c = URL("$ROBOT/contact").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 20000
                    c.readTimeout = 90000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("Authorization", "Bearer $token")
                    c.outputStream.use { it.write(JSONObject().put("wa", wa).toString().toByteArray()) }
                    val r = c.responseCode
                    c.disconnect()
                    r
                } catch (e: Exception) {
                    -1
                }
            }
            toast(when (code) {
                200 -> "Numéro enregistré : +$wa"
                401, 403 -> "Accès refusé : reconnecte-toi."
                400 -> "Numéro invalide."
                else -> "Pas de connexion. Réessaie."
            })
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
        val f = RatioFrame(this, if (bmp != null || loadingPhoto) 2f else 2.7f)
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
            col.setPadding(dp(16), dp(28), dp(16), dp(if (cta.isNotEmpty() && hasLink) 46 else 14))
            val tt = t(title.ifEmpty { "Titre de la publicité" }, 17f, Color.WHITE, true)
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
            val c = t("$cta  →", 12.5f, c1, true)
            c.maxLines = 1
            c.ellipsize = TextUtils.TruncateAt.END
            c.maxWidth = dp(160)
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
            c.background = Ui.rounded(this, Color.WHITE, 22)
            val lp = FrameLayout.LayoutParams(KW, KW, Gravity.END or Gravity.BOTTOM)
            lp.setMargins(0, 0, dp(10), dp(10))
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
        val countries = countriesOf(ad)

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

        label(box, "Pays de diffusion")
        val countriesInfo = t("", 12.5f, Ui.TEXT2)
        lateinit var refreshCountries: () -> Unit
        fun pickCountries() {
            val names = KND_COUNTRIES.map { it.second }.toTypedArray()
            val checked = BooleanArray(KND_COUNTRIES.size) { countries.contains(KND_COUNTRIES[it].first) }
            AlertDialog.Builder(this)
                .setTitle("Pays de diffusion")
                .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                .setNegativeButton("Annuler", null)
                .setPositiveButton("OK") { _, _ ->
                    countries.clear()
                    for (i in checked.indices) if (checked[i]) countries.add(KND_COUNTRIES[i].first)
                    refreshCountries()
                }
                .show()
        }
        refreshCountries = chipRow(box, listOf("all" to "Tous les pays", "some" to "Choisir les pays"), { if (countries.isEmpty()) "all" else "some" }) {
            if (it == "all") {
                countries.clear()
                refreshCountries()
            } else {
                pickCountries()
            }
        }
        val baseRefresh = refreshCountries
        refreshCountries = {
            baseRefresh()
            countriesInfo.text = if (countries.isEmpty()) "La pub est affichée dans tous les pays." else "Affichée uniquement : " + countries.joinToString(", ") { countryName(it) }
        }
        refreshCountries()
        countriesInfo.setPadding(0, dp(6), 0, 0)
        countriesInfo.setOnClickListener { if (countries.isNotEmpty()) pickCountries() }
        box.addView(countriesInfo)
        box.addView(t("Les textes sont traduits automatiquement dans la langue de chaque utilisateur.", 12f, Ui.TEXT2).also { it.setPadding(0, dp(4), 0, 0) })

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
                        status.text = "Enregistrement et traduction…"
                        val body2 = JSONObject().put("pages", urls).put("countries", JSONArray(countries))
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

/** Cadre au format de la carte publicitaire : photo 2:1 (entière), texte plus bas (comme dans l'app). */
private class RatioFrame(context: Context, private val ratio: Float = 2f) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((w / ratio).toInt(), MeasureSpec.EXACTLY),
        )
    }
}

/** Page d'une publicité : photo (url) ou texte. */
private data class AdPage(val url: String?, val title: String, val text: String)
