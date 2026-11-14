package com.ritesh.cashiro.testing

import com.ritesh.cashiro.data.database.dao.SubscriptionDao
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A subscription table that is always empty; enough for code that only writes to it. */
internal class FakeSubscriptionDao : SubscriptionDao {
    override suspend fun insertSubscription(subscription: com.ritesh.cashiro.data.database.entity.SubscriptionEntity): Long = 1
    override fun getAllSubscriptions(): Flow<List<com.ritesh.cashiro.data.database.entity.SubscriptionEntity>> = flowOf(emptyList())
    override fun getSubscriptionsByState(state: com.ritesh.cashiro.data.database.entity.SubscriptionState): Flow<List<com.ritesh.cashiro.data.database.entity.SubscriptionEntity>> = flowOf(emptyList())
    override fun getActiveSubscriptions(): Flow<List<com.ritesh.cashiro.data.database.entity.SubscriptionEntity>> = flowOf(emptyList())
    override fun getUpcomingSubscriptions(date: java.time.LocalDate): Flow<List<com.ritesh.cashiro.data.database.entity.SubscriptionEntity>> = flowOf(emptyList())
    override suspend fun getActiveSubscriptionByMerchant(merchantName: String): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun getHiddenSubscriptionByMerchant(merchantName: String): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun getSubscriptionByUmn(umn: String): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun getSubscriptionByMerchantAmountAndDate(merchantName: String, amount: BigDecimal, paymentDate: java.time.LocalDate): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun getSubscriptionByMerchantAndAmount(merchantName: String, amount: BigDecimal): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun getSubscriptionById(id: Long): com.ritesh.cashiro.data.database.entity.SubscriptionEntity? = null
    override suspend fun updateSubscription(subscription: com.ritesh.cashiro.data.database.entity.SubscriptionEntity) = Unit
    override suspend fun updateSubscriptionState(id: Long, state: com.ritesh.cashiro.data.database.entity.SubscriptionState) = Unit
    override suspend fun updateNextPaymentDate(id: Long, nextPaymentDate: java.time.LocalDate) = Unit
    override suspend fun updatePaymentStatus(id: Long, nextPaymentDate: java.time.LocalDate, lastPaidDate: java.time.LocalDate?) = Unit
    override suspend fun deleteSubscription(subscription: com.ritesh.cashiro.data.database.entity.SubscriptionEntity) = Unit
    override suspend fun deleteSubscriptionById(id: Long) = Unit
    override suspend fun getSubscriptionsByStateList(state: com.ritesh.cashiro.data.database.entity.SubscriptionState): List<com.ritesh.cashiro.data.database.entity.SubscriptionEntity> = emptyList()
    override suspend fun deleteSampleSubscriptions() = Unit
    override suspend fun deleteAllSubscriptions() = Unit
}
