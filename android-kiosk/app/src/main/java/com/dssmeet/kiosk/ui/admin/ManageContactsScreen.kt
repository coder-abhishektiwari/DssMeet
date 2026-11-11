package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.LightBg

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.models.admin.VerifiedContact
import com.dssmeet.kiosk.network.NetworkResult
import com.dssmeet.kiosk.ui.components.ConfirmDeleteDialog
import com.dssmeet.kiosk.ui.components.KioskErrorState
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.components.KioskPhoneField
import com.dssmeet.kiosk.ui.components.KioskSelectField
import com.dssmeet.kiosk.ui.components.KioskTextField
import com.dssmeet.kiosk.ui.components.KioskTopBar
import com.dssmeet.kiosk.ui.components.normalizeIndianPhone
import com.dssmeet.kiosk.ui.components.phoneNationalDigits
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction

@Composable
fun ManageContactsScreen(
    prisonerId: String,
    onBackClick: () -> Unit,
    viewModel: ManageContactsViewModel = hiltViewModel()
) {
    val contactsResult by viewModel.contacts.collectAsState()
    val addResult by viewModel.addContactState.collectAsState()
    val editResult by viewModel.editContactState.collectAsState()
    val deleteResult by viewModel.deleteContactState.collectAsState()
    val toggleError by viewModel.toggleError.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<VerifiedContact?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<VerifiedContact?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // Auto-dismiss snackbar after 3 seconds
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            kotlinx.coroutines.delay(3000L)
            snackbarMessage = null
        }
    }

    LaunchedEffect(toggleError) {
        if (toggleError != null) {
            snackbarMessage = toggleError
            viewModel.clearToggleError()
        }
    }

    LaunchedEffect(prisonerId) {
        viewModel.loadContacts(prisonerId)
    }

    // Feedback from mutations
    LaunchedEffect(addResult) {
        when (addResult) {
            is NetworkResult.Success -> { snackbarMessage = "Contact added"; viewModel.resetAddState() }
            is NetworkResult.Failure -> { snackbarMessage = "Failed to add contact"; viewModel.resetAddState() }
            else -> {}
        }
    }
    LaunchedEffect(editResult) {
        when (editResult) {
            is NetworkResult.Success -> { snackbarMessage = "Contact updated"; viewModel.resetEditState() }
            is NetworkResult.Failure -> { snackbarMessage = "Failed to update contact"; viewModel.resetEditState() }
            else -> {}
        }
    }
    LaunchedEffect(deleteResult) {
        when (deleteResult) {
            is NetworkResult.Success -> { snackbarMessage = "Contact deleted"; viewModel.resetDeleteState() }
            is NetworkResult.Failure -> { snackbarMessage = "Failed to delete contact"; viewModel.resetDeleteState() }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            KioskTopBar(
                title = "Verified Contacts",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = PrimaryNavy,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact")
            }
        },
        snackbarHost = {
            snackbarMessage?.let { msg ->
                Snackbar {
                    Text(msg)
                }
            }
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (val result = contactsResult) {
                is NetworkResult.Loading -> {
                    // Only show spinner on first load (no existing data)
                    if (result !is NetworkResult.Success) {
                        KioskLoadingState(modifier = Modifier.align(Alignment.Center))
                    }
                }
                is NetworkResult.Success -> {
                    if (result.data.isEmpty()) {
                        EmptyContactsView(modifier = Modifier.align(Alignment.Center))
                    } else {
                        ContactsList(
                            contacts = result.data,
                            onToggleStatus = { contactId, active ->
                                viewModel.toggleContactStatus(contactId, prisonerId, active)
                            },
                            onEdit = { showEditDialog = it },
                            onDelete = { showDeleteConfirm = it }
                        )
                    }
                }
                is NetworkResult.Failure -> {
                    KioskErrorState(
                        message = result.error.message ?: "Failed to load contacts",
                        onRetry = { viewModel.loadContacts(prisonerId) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {}
            }
        }
    }

    // Add — same form as edit (name, relationship, mobile only)
    if (showAddDialog) {
        ContactFormDialog(
            title = "Add Verified Contact",
            confirmLabel = "Add",
            initialName = "",
            initialMobile = "",
            initialRelationship = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, mobile, relation ->
                viewModel.addContact(prisonerId, name, mobile, relation)
                showAddDialog = false
            }
        )
    }

    // Edit — same form as add
    showEditDialog?.let { contact ->
        ContactFormDialog(
            title = "Edit Contact",
            confirmLabel = "Save",
            initialName = contact.displayName,
            initialMobile = contact.phone,
            initialRelationship = contact.relationship ?: "",
            onDismiss = { showEditDialog = null },
            onConfirm = { name, mobile, relation ->
                viewModel.editContact(contact.contactId, prisonerId, name, mobile, relation)
                showEditDialog = null
            }
        )
    }

    // Delete Confirmation
    showDeleteConfirm?.let { contact ->
        ConfirmDeleteDialog(
            title = "Delete Contact",
            message = "Are you sure you want to delete ${contact.displayName}?",
            onConfirm = {
                viewModel.deleteContact(contact.contactId, prisonerId)
                showDeleteConfirm = null
            },
            onDismiss = { showDeleteConfirm = null }
        )
    }
}

@Composable
fun ContactsList(
    contacts: List<VerifiedContact>,
    onToggleStatus: (String, Boolean) -> Unit,
    onEdit: (VerifiedContact) -> Unit,
    onDelete: (VerifiedContact) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        items(contacts, key = { it.contactId }) { contact ->
            ContactItem(contact, onToggleStatus, onEdit, onDelete)
        }
    }
}

@Composable
fun ContactItem(
    contact: VerifiedContact,
    onToggleStatus: (String, Boolean) -> Unit,
    onEdit: (VerifiedContact) -> Unit,
    onDelete: (VerifiedContact) -> Unit
) {
    // Optimistic local state for toggle — prevents snap-back
    var localActive by remember(contact.contactId) { mutableStateOf(contact.active) }
    LaunchedEffect(contact.active) { localActive = contact.active }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = PrimaryNavy.copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryNavy)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(contact.phone, color = TextGray, fontSize = 14.sp)
                Text(contact.relationship ?: "Contact", color = PrimaryNavy, fontSize = 12.sp)
            }
            IconButton(onClick = { onEdit(contact) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF666666))
            }
            IconButton(onClick = { onDelete(contact) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed)
            }
            Switch(
                checked = localActive,
                onCheckedChange = { newValue ->
                    localActive = newValue
                    onToggleStatus(contact.contactId, newValue)
                }
            )
        }
    }
}

@Composable
fun EmptyContactsView(modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Icon(Icons.Default.GroupOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.LightGray)
        Spacer(modifier = Modifier.height(16.dp))
        Text("No verified contacts found", color = TextGray)
    }
}

/**
 * The ONE form used for both adding and editing a family contact.
 * A contact is only name, relationship and mobile number.
 */
@Composable
fun ContactFormDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialMobile: String,
    initialRelationship: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    // Store the full +91 number; submit national digits to the backend.
    var mobile by remember { mutableStateOf(normalizeIndianPhone(initialMobile)) }
    var relation by remember { mutableStateOf(initialRelationship) }
    var submitted by remember { mutableStateOf(false) }

    val nameError = if (submitted && name.isBlank()) "Name is required" else null
    val national = phoneNationalDigits(mobile)
    val mobileError = when {
        submitted && national.length != 10 -> "Enter a valid 10-digit mobile number"
        mobile.isNotBlank() && national.length != 10 -> "Enter a valid 10-digit mobile number"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KioskTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Full Name",
                    required = true,
                    placeholder = "e.g. Neha Gupta",
                    errorText = nameError,
                    imeAction = ImeAction.Next
                )

                KioskSelectField(
                    value = relation,
                    onValueChange = { relation = it },
                    label = "Relationship",
                    options = RELATIONSHIP_OPTIONS,
                    placeholder = "Select relationship",
                    modifier = Modifier.fillMaxWidth()
                )

                KioskPhoneField(
                    value = mobile,
                    onValueChange = { mobile = it },
                    label = "Mobile Number",
                    required = true,
                    errorText = mobileError,
                    imeAction = ImeAction.Done
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    if (name.isNotBlank() && national.length == 10) {
                        onConfirm(name.trim(), national, relation.trim())
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
