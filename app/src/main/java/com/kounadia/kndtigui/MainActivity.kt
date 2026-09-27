package com.kounadia.kndtigui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var configStatusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var listText: TextView

    companion object {
        const val SMS_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 80, 40, 40)

        statusText = TextView(this)
        statusText.textSize = 16f
        statusText.setPadding(0, 0, 0, 30)
        root.addView(statusText)

        // --- Section configuration serveur ---
        val configLabel = TextView(this)
        configLabel.text = "Configuration serveur"
        configLabel.textSize = 15f
        configLabel.setPadding(0, 20, 0, 10)
        root.addView(configLabel)

        urlInput = EditText(this)
        urlInput.hint = "URL API (ex: https://knd-api-xxxx.onrender.com)"
        root.addView(urlInput)

        tokenInput = EditText(this)
        tokenInput.hint = "Token appareil"
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(tokenInput)

        val saveConfigButton = Button(this)
        saveConfigButton.text = "Enregistrer la configuration"
        saveConfigButton.setOnClickListener { saveConfig() }
        root.addView(saveConfigButton)

        configStatusText = TextView(this)
        configStatusText.textSize = 13f
        configStatusText.setPadding(0, 10, 0, 20)
        root.addView(configStatusText)

        val syncButton = Button(this)
        syncButton.text = "Synchroniser maintenant"
        syncButton.setOnClickListener { triggerManualSync() }
        root.addView(syncButton)

        // --- Section liste des paiements (inchangee) ---
        val refreshButton = Button(this)
        refreshButton.text = "Rafraichir la liste"
        refreshButton.setOnClickListener { refreshList() }
        root.addView(refreshButton)

        val listLabel = TextView(this)
        listLabel.text = "\nEvenements de paiement detectes :"
        listLabel.textSize = 15f
        listLabel.setPadding(0, 30, 0, 10)
        root.addView(listLabel)

        listText = TextView(this)
        listText.textSize = 13f
        listText.setTextIsSelectable(true)

        val scrollView = ScrollView(this)
        scrollView.addView(listText)
        root.addView(scrollView)

        setContentView(root)

        checkAndRequestPermissions()
        loadConfigIntoFields()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        triggerManualSync()
    }

    private fun loadConfigIntoFields() {
        val savedUrl = ConfigStorage.getApiBaseUrl(this)
        if (savedUrl != null) {
            urlInput.setText(savedUrl)
        }
        updateConfigStatus()
    }

    private fun updateConfigStatus() {
        configStatusText.text = if (ConfigStorage.isConfigured(this)) {
            "Statut : configure"
        } else {
            "Statut : non configure"
        }
    }

    private fun saveConfig() {
        val url = urlInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()

        if (url.isBlank() || token.isBlank()) {
            Toast.makeText(this, "URL et token requis", Toast.LENGTH_SHORT).show()
            return
        }

        ConfigStorage.saveConfig(this, url, token)
        tokenInput.setText("") // ne jamais laisser le token affiche a l'ecran
        updateConfigStatus()
        Toast.makeText(this, "Configuration enregistree", Toast.LENGTH_SHORT).show()
    }

    private fun triggerManualSync() {
        if (!ConfigStorage.isConfigured(this)) {
            return
        }
        val app = applicationContext as? KndTiguiApplication ?: return
        app.triggerSync()
        // Rafraichissement differe : la synchro tourne en arriere-plan,
        // on rafraichit l'affichage un peu plus tard pour refleter le resultat.
        listText.postDelayed({ refreshList() }, 3000)
    }

    private fun refreshList() {
        val prefs = getSharedPreferences(SmsReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(SmsReceiver.KEY_PAYMENT_EVENTS, "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }

        if (array.length() == 0) {
            listText.text = "(aucun paiement detecte pour le moment)"
            return
        }

        val builder = StringBuilder()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)

            val amount = entry.optDouble("amount", 0.0)
            val senderPhone = entry.optString("senderPhone", "?")
            val senderName = entry.optString("senderName", "?")
            val transactionId = entry.optString("transactionId", "?")
            val status = entry.optString("status", "?")
            val timestamp = entry.optLong("timestamp", 0L)
            val dateStr = DateFormat.format("dd/MM/yyyy HH:mm:ss", Date(timestamp))

            builder.append("--- Paiement #${i + 1} [$status] ---\n")
            builder.append("Montant: $amount FCFA\n")
            builder.append("De: $senderPhone ($senderName)\n")
            builder.append("Transaction: $transactionId\n")
            builder.append("Date: $dateStr\n\n")
        }

        listText.text = builder.toString()
    }

    private fun checkAndRequestPermissions() {
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
        val receiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)

        if (readSms != PackageManager.PERMISSION_GRANTED || receiveSms != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
                SMS_PERMISSION_CODE
            )
            statusText.text = "KND-Tigui v0.3\n\nDemande des permissions SMS en cours..."
        } else {
            statusText.text = "KND-Tigui v0.3\n\n✅ Permissions SMS deja accordees.\nEn attente d'un paiement..."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            statusText.text = if (allGranted) {
                "KND-Tigui v0.3\n\n✅ Permissions accordees.\nEn attente d'un paiement..."
            } else {
                "KND-Tigui v0.3\n\n❌ Permissions refusees.\nImpossible de detecter les paiements."
            }
        }
    }
}
