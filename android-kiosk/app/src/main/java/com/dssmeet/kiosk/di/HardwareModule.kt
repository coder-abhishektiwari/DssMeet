package com.dssmeet.kiosk.di

import com.dssmeet.kiosk.hardware.FingerprintHardwareManager
import com.dssmeet.kiosk.hardware.KioskManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object HardwareModule {

    @Provides
    @Singleton
    fun provideFingerprintHardwareManager(
        @ApplicationContext context: Context
    ): FingerprintHardwareManager {
        return FingerprintHardwareManager(context)
    }

    @Provides
    @Singleton
    fun provideKioskManager(): KioskManager {
        return KioskManager()
    }
}
