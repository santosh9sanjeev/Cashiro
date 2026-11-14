package com.ritesh.parser.core

import java.math.BigDecimal
import java.security.MessageDigest

data class ParsedTransaction(
    val amount: BigDecimal,
    val type: TransactionType,
    val merchant: String?,
    val reference: String?,
    val accountLast4: String?,
    val balance: BigDecimal?,
    val creditLimit: BigDecimal? = null,
    val smsBody: String,
    val sender: String,
    val timestamp: Long,
    val bankName: String,
    val transactionHash: String? = null,
    val isFromCard: Boolean = false,
    val currency: String = "INR",
    val fromAccount: String? = null,
    val toAccount: String? = null,
    /**
     * The last four digits of the card used, when the alert names it apart from
     * the account. Some banks state both ("Account XXXX0002 / Card XXXX1234");
     * [accountLast4] is then the account the money moved on and this is the card
     * that moved it.
     */
    val cardLast4: String? = null
) {
    fun generateTransactionId(): String {
        val normalizedAmount = amount.setScale(2, java.math.RoundingMode.HALF_UP)
        // Use SMS body hash for reliable deduplication across different timestamp sources
        // (BroadcastReceiver uses SC timestamp, ContentProvider uses device timestamp)
        val smsBodyHash = MessageDigest.getInstance("SHA-256")
            .digest(smsBody.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16) // First 16 chars of SMS body hash
        val data = "$sender|$normalizedAmount|$smsBodyHash"
        return MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}


