package com.kounadia.kndtigui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
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
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
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
            statusText.text = "KND-Tigui v0.2\n\nDemande des permissions SMS en cours..."
        } else {
            statusText.text = "KND-Tigui v0.2\n\n✅ Permissions SMS deja accordees.\nEn attente d'un paiement..."
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
                "KND-Tigui v0.2\n\n✅ Permissions accordees.\nEn attente d'un paiement..."
            } else {
                "KND-Tigui v0.2\n\n❌ Permissions refusees.\nImpossible de detecter les paiements."
            }
        }
    }
}
