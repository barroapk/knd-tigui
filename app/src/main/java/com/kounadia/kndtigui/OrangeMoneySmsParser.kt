package com.kounadia.kndtigui

/**
 * Parseur local, miroir exact de la logique deja validee cote KND API
 * (orange-money-sms.parser.ts). Extrait les champs d'un SMS de RECEPTION
 * Orange Money deja filtre (format confirme : "Vous avez recu" + "FCFA"
 * + "Trans id:").
 *
 * Exemple reel :
 * "Vous avez recu 500.0 FCFA, Frais:  FCFA, Taxe:  FCFA du 07802610,Spierreclavers.
 *  Le solde de votre compte est de 579.0 FCFA. Trans id: MP260926.1442.95750390."
 */
data class ParsedOrangeMoneyPayment(
    val amount: Double,
    val senderPhone: String,
    val senderName: String,
    val newBalance: Double,
    val transactionId: String
)

object OrangeMoneySmsParser {

    // Insensible a la casse ("Trans id:" client / "Trans ID:" agent) et point
    // facultatif apres "FCFA" (absent dans les SMS de transfert agent).
    private val regex = Regex(
        "Vous avez recu ([\\d.]+)\\s*FCFA.*?du (\\d+),(\\S+)\\.\\s*Le solde de votre compte est de ([\\d.]+)\\s*FCFA\\.?\\s*Trans id:\\s*(\\S+)\\.",
        RegexOption.IGNORE_CASE,
    )

    fun parse(smsText: String): ParsedOrangeMoneyPayment? {
        val match = regex.find(smsText) ?: return null

        val (amount, senderPhone, senderName, newBalance, transactionId) = match.destructured

        return ParsedOrangeMoneyPayment(
            amount = amount.toDoubleOrNull() ?: return null,
            senderPhone = normalizePhoneNumber(senderPhone.trim()),
            senderName = senderName.trim(),
            newBalance = newBalance.toDoubleOrNull() ?: return null,
            transactionId = transactionId.trim()
        )
    }

    /**
     * Normalise un numero court (8 chiffres, ex: 07802610) vers le format
     * avec indicatif Burkina Faso (226), pour rester coherent avec le
     * parseur backend et avec declared_payment_phone.
     */
    private fun normalizePhoneNumber(phone: String): String {
        val digitsOnly = phone.filter { it.isDigit() }
        return if (digitsOnly.length == 8) "226$digitsOnly" else digitsOnly
    }
}
