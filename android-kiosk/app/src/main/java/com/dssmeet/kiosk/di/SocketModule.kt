package com.dssmeet.kiosk.di

import com.dssmeet.kiosk.socket.SocketService
import com.dssmeet.kiosk.socket.SocketServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SocketModule {

    @Binds
    @Singleton
    abstract fun bindSocketService(
        socketServiceImpl: SocketServiceImpl
    ): SocketService
}
