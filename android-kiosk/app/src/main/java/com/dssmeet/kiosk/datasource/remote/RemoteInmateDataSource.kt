package com.dssmeet.kiosk.datasource.remote

import com.dssmeet.kiosk.api.TrustApiService
import com.dssmeet.kiosk.core.ApiCache
import com.dssmeet.kiosk.datasource.InmateDataSource
import com.dssmeet.kiosk.models.common.ApiResponse
import com.dssmeet.kiosk.models.inmate.InmateBalance
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.wallet.WalletStatement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteInmateDataSource @Inject constructor(
    private val apiService: TrustApiService,
    private val cache: ApiCache
) : InmateDataSource {
    override suspend fun getProfile(id: String): ApiResponse<InmateProfile> =
        cache.getOrFetch("inmate:profile:$id") { apiService.getInmateProfile(id) }

    override suspend fun getBalance(id: String): ApiResponse<InmateBalance> =
        cache.getOrFetch("inmate:balance:$id") { apiService.getInmateBalance(id) }

    override suspend fun getWalletStatement(id: String): ApiResponse<WalletStatement> =
        cache.getOrFetch("inmate:wallet:$id") { apiService.getWalletStatement(id) }

    override fun peekWalletStatement(id: String): WalletStatement? =
        cache.getStale<ApiResponse<WalletStatement>>("inmate:wallet:$id")?.data
}
