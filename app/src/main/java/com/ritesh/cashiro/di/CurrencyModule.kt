package com.ritesh.cashiro.di

import com.ritesh.cashiro.data.currency.CurrencyConversionService
import com.ritesh.cashiro.data.currency.CurrencyConverter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CurrencyModule {

    @Binds
    @Singleton
    abstract fun bindCurrencyConverter(
        currencyConversionService: CurrencyConversionService
    ): CurrencyConverter
}
