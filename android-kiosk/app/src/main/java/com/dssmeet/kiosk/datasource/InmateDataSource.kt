package com.dssmeet.kiosk.datasource

import com.dssmeet.kiosk.models.common.ApiResponse
import com.dssmeet.kiosk.models.inmate.InmateBalance
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.wallet.WalletStatement

interface InmateDataSource {
    suspend fun getProfile(id: String): ApiResponse<InmateProfile>
    suspend fun getBalance(id: String): ApiResponse<InmateBalance>
    suspend fun getWalletStatement(id: String): ApiResponse<WalletStatement>
    /** Any previously cached statement (even stale) for instant paint; null if none. */
    fun peekWalletStatement(id: String): WalletStatement?
}
