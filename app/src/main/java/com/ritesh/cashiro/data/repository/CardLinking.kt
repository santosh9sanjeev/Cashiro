package com.ritesh.cashiro.data.repository

import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType

/**
 * The account a linked card's transactions belong to, or null when the card is
 * not linked to an account that exists.
 *
 * A link stores only the account's last four digits. The account under the
 * card's own bank name is preferred; otherwise the one account with those
 * digits under any name, because an account the user set up by hand is often
 * named differently from the bank name its alerts carry. A credit card's
 * spending is owed on a credit card and never taken from a bank account, so a
 * credit card linked to a bank account is treated as unlinked.
 */
internal fun linkedAccountOf(
    card: CardEntity,
    accounts: List<AccountBalanceEntity>
): AccountBalanceEntity? {
    val last4 = card.accountLast4 ?: return null
    val candidates = accounts.filter {
        it.accountLast4 == last4 &&
            !(it.bankName == card.bankName && it.accountLast4 == card.cardLast4) &&
            (it.isCreditCard || card.cardType != CardType.CREDIT)
    }
    return candidates.firstOrNull { it.bankName == card.bankName }
        ?: candidates.distinctBy { it.bankName }.singleOrNull()
}

/**
 * Whether a card belongs in the list of cards waiting to be linked.
 *
 * A card whose own number is a credit card account is that credit card and
 * stands on its own. Any other card without a link is waiting for one, whatever
 * type it was first filed as: card spending arrives as an expense, so a credit
 * card is often first filed as a debit card.
 */
internal fun isAwaitingLink(card: CardEntity, accounts: List<AccountBalanceEntity>): Boolean {
    if (card.accountLast4 != null) return false
    val ownAccount = accounts.firstOrNull {
        it.bankName == card.bankName && it.accountLast4 == card.cardLast4
    }
    return ownAccount?.isCreditCard != true
}
