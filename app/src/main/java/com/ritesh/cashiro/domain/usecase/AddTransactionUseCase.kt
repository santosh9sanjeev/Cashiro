package com.ritesh.cashiro.domain.usecase

import com.ritesh.cashiro.utils.SubscriptionUtils

import com.ritesh.cashiro.data.database.entity.SubscriptionEntity
import com.ritesh.cashiro.data.database.entity.SubscriptionState
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.database.entity.TransactionType
import com.ritesh.cashiro.data.currency.CurrencyConversionService
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.SubscriptionRepository
import com.ritesh.cashiro.data.repository.TransactionRepository
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

class AddTransactionUseCase
@Inject
constructor(
        private val transactionRepository: TransactionRepository,
        private val subscriptionRepository: SubscriptionRepository,
        private val accountBalanceRepository: AccountBalanceRepository,
        private val currencyConversionService: CurrencyConversionService
) {
    suspend fun execute(
            amount: BigDecimal,
            merchant: String,
            category: String,
            type: TransactionType,
            date: LocalDateTime,
            notes: String? = null,
            subcategory: String? = null,
            isRecurring: Boolean = false,
            bankName: String? = null,
            accountLast4: String? = null,
            currency: String = "INR",
            sourceAccountId: Long? = null,
            targetAccountBankName: String? = null,
            targetAccountLast4: String? = null,
            billingCycle: String? = null,
            createSubscription: Boolean = true,
            attachments: String = ""
    ): Long {
        // Generate a unique hash for manual transactions
        val transactionHash =
                generateManualTransactionHash(amount = amount, merchant = merchant, date = date)

        // Create the transaction entity
        val transaction =
                TransactionEntity(
                        amount = amount,
                        merchantName = merchant,
                        category = category,
                        subcategory = subcategory,
                        transactionType = type,
                        dateTime = date,
                        description = notes,
                        smsBody = null, // null indicates manual entry
                        bankName = bankName ?: "Manual Entry",
                        smsSender = null, // null indicates manual entry
                        accountNumber = accountLast4,
                        fromAccount = accountLast4,
                        toAccount = targetAccountLast4,
                        balanceAfter = null,
                        transactionHash = transactionHash,
                        isRecurring = isRecurring,
                        createdAt = LocalDateTime.now(),
                        updatedAt = LocalDateTime.now(),
                        currency = currency,
                        billingCycle = billingCycle,
                        attachments = attachments
                )

        // Insert the transaction
        val transactionId = transactionRepository.insertTransaction(transaction)

        // Update account balances based on transaction type
        if (bankName != null && accountLast4 != null) {
            // The account's credit-card flag must be carried through, otherwise the
            // balance calculation treats a card like a bank account and subtracts
            // spending instead of adding it to the outstanding amount.
            val sourceAccount = accountBalanceRepository.getLatestBalance(bankName, accountLast4)
            val targetAccount =
                    if (targetAccountBankName != null && targetAccountLast4 != null) {
                        accountBalanceRepository
                                .getLatestBalance(targetAccountBankName, targetAccountLast4)
                    } else null

            val sourceIsCreditCard = sourceAccount?.isCreditCard ?: false
            val targetIsCreditCard = targetAccount?.isCreditCard ?: false

            // Each account holds money in its own currency. A transaction entered in
            // a different one has to be converted per account, so a transfer between
            // accounts of differing currencies moves an equivalent value rather than
            // the same bare number.
            val sourceCurrency = sourceAccount?.currency ?: currency
            val targetCurrency = targetAccount?.currency ?: currency
            val sourceAmount = currencyConversionService.convertAmount(
                    amount = amount,
                    fromCurrency = currency,
                    toCurrency = sourceCurrency
            )
            val targetAmount = currencyConversionService.convertAmount(
                    amount = amount,
                    fromCurrency = currency,
                    toCurrency = targetCurrency
            )

            when (type) {
                TransactionType.TRANSFER -> {
                    // Transfer: subtract from source, add to target
                    if (targetAccountBankName != null && targetAccountLast4 != null) {
                        accountBalanceRepository.insertTransactionBalance(
                            bankName = bankName,
                            accountLast4 = accountLast4,
                            amount = sourceAmount,
                            transactionType = TransactionType.EXPENSE,
                            explicitBalance = null,
                            timestamp = date,
                            transactionId = transactionId,
                            creditLimit = null,
                            isCreditCard = sourceIsCreditCard,
                            smsSource = null,
                            currency = sourceCurrency
                        )
                        accountBalanceRepository.insertTransactionBalance(
                            bankName = targetAccountBankName,
                            accountLast4 = targetAccountLast4,
                            amount = targetAmount,
                            transactionType = TransactionType.INCOME,
                            explicitBalance = null,
                            timestamp = date,
                            transactionId = transactionId,
                            creditLimit = null,
                            isCreditCard = targetIsCreditCard,
                            smsSource = null,
                            currency = targetCurrency
                        )
                    }
                }
                TransactionType.BALANCE_UPDATE -> {
                    // Balance update already comes with its own balance, no adjustment needed
                }
                else -> {
                    // INCOME, EXPENSE, CREDIT, INVESTMENT:
                    // Use insertTransactionBalance which correctly:
                    // (1) finds the balance AT the transaction date (not the latest),
                    // (2) computes the new balance relative to that point, and
                    // (3) recalculates all subsequent balance entries to propagate the change forward.
                    accountBalanceRepository.insertTransactionBalance(
                        bankName = bankName,
                        accountLast4 = accountLast4,
                        amount = sourceAmount,
                        transactionType = type,
                        explicitBalance = null,
                        timestamp = date,
                        transactionId = transactionId,
                        creditLimit = null,
                        isCreditCard = sourceIsCreditCard,
                        smsSource = null,
                        currency = sourceCurrency
                    )
                }
            }
        }

        // If marked as recurring, create a subscription
        if (createSubscription && isRecurring && transactionId != -1L) {
            val nextPaymentDate = SubscriptionUtils.calculateNextPaymentDate(date.toLocalDate(), billingCycle)

            val subscription =
                    SubscriptionEntity(
                            merchantName = merchant,
                            amount = amount,
                            nextPaymentDate = nextPaymentDate,
                            state = SubscriptionState.ACTIVE,
                            bankName = bankName ?: "Manual Entry",
                            category = category,
                            subcategory = subcategory,
                            createdAt = LocalDateTime.now(),
                            updatedAt = LocalDateTime.now(),
                            currency = currency,
                            billingCycle = billingCycle,
                            lastPaidDate = date.toLocalDate()
                    )

            subscriptionRepository.insertSubscription(subscription)
        }
        return transactionId
    }


    private fun generateManualTransactionHash(
            amount: BigDecimal,
            merchant: String,
            date: LocalDateTime
    ): String {
        // Create a unique hash for manual transactions
        // Format: MANUAL_<amount>_<merchant>_<datetime>
        val data = "MANUAL_${amount}_${merchant}_${date}"

        return MessageDigest.getInstance("MD5").digest(data.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }
}
