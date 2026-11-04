package com.dssmeet.kiosk.repository

import com.dssmeet.kiosk.datasource.InmateDataSource
import com.dssmeet.kiosk.models.inmate.InmateBalance
import com.dssmeet.kiosk.models.inmate.InmateProfile
import com.dssmeet.kiosk.models.wallet.WalletStatement
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.models.common.ApiError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InmateRepositoryImpl @Inject constructor(
    private val dataSource: InmateDataSource
) : InmateRepository {

    override fun getProfile(id: String): Flow<NetworkResult<InmateProfile>> = flow {
        emit(NetworkResult.Loading)
        try {
            val response = dataSource.getProfile(id)
            if (response.success && response.data != null) {
                emit(NetworkResult.Success(response.data))
            } else {
                emit(NetworkResult.Failure(response.error ?: ApiError("UNKNOWN", "Unknown error")))
            }
        } catch (e: Exception) {
            emit(NetworkResult.Failure(ApiError("EXCEPTION", e.message ?: "Network exception")))
        }
    }

    override fun getBalance(id: String): Flow<NetworkResult<InmateBalance>> = flow {
        emit(NetworkResult.Loading)
        try {
            val response = dataSource.getBalance(id)
            if (response.success && response.data != null) {
                emit(NetworkResult.Success(response.data))
            } else {
                emit(NetworkResult.Failure(response.error ?: ApiError("UNKNOWN", "Unknown error")))
            }
        } catch (e: Exception) {
            emit(NetworkResult.Failure(ApiError("EXCEPTION", e.message ?: "Network exception")))
        }
    }

    override fun getWalletStatement(id: String): Flow<NetworkResult<WalletStatement>> = flow {
        emit(NetworkResult.Loading)
        try {
            val response = dataSource.getWalletStatement(id)
            if (response.success && response.data != null) {
                emit(NetworkResult.Success(response.data))
            } else {
                emit(NetworkResult.Failure(response.error ?: ApiError("UNKNOWN", "Unknown error")))
            }
        } catch (e: Exception) {
            emit(NetworkResult.Failure(ApiError("EXCEPTION", e.message ?: "Network exception")))
        }
    }

    override fun peekWalletStatement(id: String): WalletStatement? =
        dataSource.peekWalletStatement(id)
}
