package com.dssmeet.kiosk.repository

import com.dssmeet.kiosk.models.inmate.InmateBalance
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.wallet.WalletStatement
import com.dssmeet.kiosk.network.NetworkResult
import kotlinx.coroutines.flow.Flow

interface InmateRepository {
    fun getProfile(id: String): Flow<NetworkResult<InmateProfile>>
    fun getBalance(id: String): Flow<NetworkResult<InmateBalance>>
    fun getWalletStatement(id: String): Flow<NetworkResult<WalletStatement>>
    /** Any previously cached statement (even stale) for instant paint; null if none. */
    fun peekWalletStatement(id: String): WalletStatement?
}
