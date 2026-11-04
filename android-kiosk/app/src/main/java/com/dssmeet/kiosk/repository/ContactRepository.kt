package com.dssmeet.kiosk.repository

import com.dssmeet.kiosk.models.contacts.Contact
import com.dssmeet.kiosk.network.NetworkResult
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun getContacts(id: String): Flow<NetworkResult<List<Contact>>>
}
