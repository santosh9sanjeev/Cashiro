package com.ritesh.cashiro.testing

import com.ritesh.cashiro.data.database.dao.CardDao
import com.ritesh.cashiro.data.database.entity.CardEntity
import com.ritesh.cashiro.data.database.entity.CardType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory stand-in for [CardDao], shared by the tests that attribute card alerts. */
internal class FakeCardDao : CardDao {
    val cards = mutableListOf<CardEntity>()
    private var nextId = 1L

    override suspend fun insertCard(card: CardEntity): Long {
        val id = nextId++
        cards += card.copy(id = id)
        return id
    }

    override suspend fun updateCard(card: CardEntity) {
        val i = cards.indexOfFirst { it.id == card.id }
        if (i >= 0) cards[i] = card
    }

    override suspend fun deleteCard(card: CardEntity) {
        cards.removeAll { it.id == card.id }
    }

    override suspend fun getCard(bankName: String, cardLast4: String): CardEntity? =
        cards.firstOrNull { it.bankName == bankName && it.cardLast4 == cardLast4 }

    override suspend fun getCardById(cardId: Long): CardEntity? =
        cards.firstOrNull { it.id == cardId }

    override suspend fun getCardsByLast4(cardLast4: String): List<CardEntity> =
        cards.filter { it.cardLast4 == cardLast4 }

    override suspend fun getCardsForAccount(accountLast4: String): List<CardEntity> =
        cards.filter { it.accountLast4 == accountLast4 }

    override fun getCardsForAccountFlow(accountLast4: String): Flow<List<CardEntity>> =
        flowOf(cards.filter { it.accountLast4 == accountLast4 })

    override suspend fun getOrphanedCards(cardType: CardType): List<CardEntity> =
        cards.filter { it.cardType == cardType && it.accountLast4 == null }

    override fun getAllActiveCards(): Flow<List<CardEntity>> =
        flowOf(cards.filter { it.isActive })

    override fun getAllCards(): Flow<List<CardEntity>> = flowOf(cards.toList())

    override suspend fun linkCardToAccount(cardId: Long, accountLast4: String?) {
        val i = cards.indexOfFirst { it.id == cardId }
        if (i >= 0) cards[i] = cards[i].copy(accountLast4 = accountLast4)
    }

    override suspend fun relinkCardsToAccount(
        oldAccountLast4: String,
        newAccountLast4: String
    ): Int {
        var n = 0
        for (i in cards.indices) {
            if (cards[i].accountLast4 == oldAccountLast4) {
                cards[i] = cards[i].copy(accountLast4 = newAccountLast4)
                n++
            }
        }
        return n
    }

    override suspend fun setCardActive(cardId: Long, isActive: Boolean) {
        val i = cards.indexOfFirst { it.id == cardId }
        if (i >= 0) cards[i] = cards[i].copy(isActive = isActive)
    }

    override suspend fun getCardCount(): Int = cards.size

    override suspend fun getCardCountByType(cardType: CardType): Int =
        cards.count { it.cardType == cardType }

    override suspend fun getBankCards(bankName: String, cardType: CardType): List<CardEntity> =
        cards.filter { it.bankName == bankName && it.cardType == cardType }

    override suspend fun deleteAllCards() {
        cards.clear()
    }

    override suspend fun deleteSampleCards() {
        cards.removeAll { it.isSample }
    }
}
