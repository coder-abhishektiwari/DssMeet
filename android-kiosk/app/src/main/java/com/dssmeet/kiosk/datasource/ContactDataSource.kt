package com.dssmeet.kiosk.datasource

import com.dssmeet.kiosk.models.common.ApiResponse
import com.dssmeet.kiosk.models.contacts.Contact

interface ContactDataSource {
    suspend fun getContacts(id: String): ApiResponse<List<Contact>>
}
