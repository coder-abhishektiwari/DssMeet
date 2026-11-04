package com.dssmeet.kiosk.datasource.remote

import com.dssmeet.kiosk.api.TrustApiService
import com.dssmeet.kiosk.core.ApiCache
import com.dssmeet.kiosk.datasource.ContactDataSource
import com.dssmeet.kiosk.models.common.ApiResponse
import com.dssmeet.kiosk.models.contacts.Contact
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteContactDataSource @Inject constructor(
    private val apiService: TrustApiService,
    private val cache: ApiCache
) : ContactDataSource {
    override suspend fun getContacts(id: String): ApiResponse<List<Contact>> =
        cache.getOrFetch("contacts:$id") { apiService.getContacts(id) }
}
