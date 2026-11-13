package com.dssmeet.kiosk.ui.admin

import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.SuccessGreen

import com.dssmeet.kiosk.ui.theme.TextGray

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dssmeet.kiosk.models.admin.KioskDevice
import com.dssmeet.kiosk.models.admin.Prisoner
import com.dssmeet.kiosk.models.admin.PrisonerFormValues
import com.dssmeet.kiosk.ui.components.KioskDateField
import com.dssmeet.kiosk.ui.components.KioskSelectField
import com.dssmeet.kiosk.ui.components.KioskTextField

val ID_PROOF_OPTIONS = listOf("Aadhaar Card", "Voter ID", "PAN Card")
val RELIGION_OPTIONS = listOf("Hindu", "Muslim", "Christian", "Sikh", "Buddhist", "Jain", "Other")
val RELATIONSHIP_OPTIONS = listOf("Father", "Mother", "Spouse", "Brother", "Sister", "Son", "Daughter", "Guardian", "Relative", "Other")
val NATIONALITY_OPTIONS = listOf("Indian", "Other")
val STATE_OPTIONS = listOf(
    "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh",
    "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka",
    "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram",
    "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu",
    "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
    "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu",
    "Delhi", "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry"
)

/**
 * Editable state shared by the add and edit prisoner screens so both collect
 * exactly the same fields, in the same order, with the same widgets.
 */
class PrisonerFormState(initial: Prisoner?) {
    var fullName by mutableStateOf(initial?.displayName ?: "")
    var gender by mutableStateOf(initial?.gender ?: "male")
    var age by mutableStateOf(initial?.age?.toString() ?: "")
    var dateOfAdmission by mutableStateOf(initial?.dateOfAdmission ?: "")
    var fatherName by mutableStateOf(initial?.fatherName ?: "")
    var motherName by mutableStateOf(initial?.motherName ?: "")
    var idProof by mutableStateOf(initial?.idProof ?: "")
    var idNumber by mutableStateOf(initial?.idNumber ?: "")
    var religion by mutableStateOf(initial?.religion ?: "")
    var nationality by mutableStateOf(initial?.nationality ?: "")
    var state by mutableStateOf(initial?.state ?: "")
    var district by mutableStateOf(initial?.district ?: "")
    var address by mutableStateOf(initial?.address ?: "")
    var prisonerNumber by mutableStateOf(initial?.prisonerNumber ?: "")
    var assignedKioskId by mutableStateOf(initial?.assignedKioskId ?: "")
    var active by mutableStateOf(initial?.active ?: true)

    /**
     * Pre-select the kiosk this device is running as — the admin is standing
     * at that kiosk, so it is the obvious assignment. An inmate that already
     * has a kiosk keeps it.
     */
    fun applyDefaultKiosk(kioskId: String) {
        if (kioskId.isBlank()) return
        if (assignedKioskId.isBlank()) assignedKioskId = kioskId
    }

    fun toValues(): PrisonerFormValues = PrisonerFormValues(
        name = fullName.trim(),
        gender = gender.ifBlank { null },
        age = age.trim().ifBlank { null },
        dateOfAdmission = dateOfAdmission.ifBlank { null },
        fatherName = fatherName.trim().ifBlank { null },
        motherName = motherName.trim().ifBlank { null },
        idProof = idProof.ifBlank { null },
        idNumber = idNumber.trim().ifBlank { null },
        religion = religion.ifBlank { null },
        nationality = nationality.ifBlank { null },
        state = state.ifBlank { null },
        district = district.trim().ifBlank { null },
        address = address.trim().ifBlank { null },
        prisonerNumber = prisonerNumber.trim(),
        assignedKioskId = assignedKioskId.trim().ifBlank { null }
    )

    /**
     * Personal step — every field is required.
     */
    fun validatePersonal(): String {
        val values = toValues()
        if (values.name.isEmpty()) return "Prisoner name is required"
        if (values.age.isNullOrBlank()) return "Age is required"
        val ageNum = values.age?.toIntOrNull()
        if (ageNum == null) return "Age must be a number"
        if (ageNum < 1 || ageNum > 120) return "Age must be between 1 and 120"
        if (values.fatherName.isNullOrBlank()) return "Father/Husband name is required"
        if (values.motherName.isNullOrBlank()) return "Mother name is required"
        return ""
    }

    /** Identity step — every field is required. */
    fun validateIdentity(): String {
        val values = toValues()
        if (values.idProof.isNullOrBlank()) return "ID proof is required"
        if (values.idNumber.isNullOrBlank()) return "ID number is required"
        if (values.dateOfAdmission.isNullOrBlank()) return "Admission date is required"
        if (values.prisonerNumber.isBlank()) return "Inmate number is required"
        if (values.assignedKioskId.isNullOrBlank()) return "Assigned kiosk is required"
        return ""
    }

    /** Address step — every field is required. */
    fun validateAddress(): String {
        val values = toValues()
        if (values.religion.isNullOrBlank()) return "Religion is required"
        if (values.nationality.isNullOrBlank()) return "Nationality is required"
        if (values.state.isNullOrBlank()) return "State is required"
        if (values.district.isNullOrBlank()) return "District is required"
        if (values.address.isNullOrBlank()) return "Address is required"
        return ""
    }

    /** True when every step so far passes — used to gate Next. */
    fun validateStep(step: Int): String = when (step) {
        0 -> validatePersonal()
        1 -> validateIdentity()
        2 -> validateAddress()
        else -> ""
    }
}

@Composable
private fun FormCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )
            content()
        }
    }
}

@Composable
private fun GenderPill(
    label: String,
    value: String,
    selectedValue: String,
    onSelect: (String) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onSelect(value) },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selectedValue == value) color else Color.White,
        border = BorderStroke(1.dp, if (selectedValue == value) color else BorderColor)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            textAlign = TextAlign.Center,
            fontWeight = if (selectedValue == value) FontWeight.Bold else FontWeight.Normal,
            color = if (selectedValue == value) Color.White else PrimaryDarkNavy
        )
    }
}

// --- One screenful per step, so add/edit never feel like one long form ---

@Composable
fun PersonalDetailsCard(state: PrisonerFormState) {
    FormCard("Personal Details") {
        KioskTextField(
            value = state.fullName,
            onValueChange = { state.fullName = it },
            label = "Prisoner Name",
            required = true,
            placeholder = "Full name as on record",
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )

        Text(
            text = "Gender *",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = PrimaryDarkNavy
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GenderPill("Male", "male", state.gender, { state.gender = it }, PrimaryNavy, Modifier.weight(1f))
            GenderPill("Female", "female", state.gender, { state.gender = it }, Color(0xFFC2185B), Modifier.weight(1f))
            GenderPill("Other", "other", state.gender, { state.gender = it }, Color(0xFF7B1FA2), Modifier.weight(1f))
        }

    
        KioskTextField(
            value = state.age,
            onValueChange = { input -> state.age = input.filter { it.isDigit() }.take(3) },
            label = "Age",
            placeholder = "Years",
            required = true,
            keyboardType = KeyboardType.Number,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )
        KioskTextField(
            value = state.fatherName,
            onValueChange = { state.fatherName = it },
            label = "Father/Husband Name",
            required = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )

        KioskTextField(
            value = state.motherName,
            onValueChange = { state.motherName = it },
            label = "Mother Name",
            required = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        )
    }
}

@Composable
fun IdentityAdmissionCard(
    state: PrisonerFormState,
    kioskOptions: List<KioskDevice> = emptyList()
) {
    FormCard("Identity & Admission") {
        KioskSelectField(
            value = state.idProof,
            onValueChange = { state.idProof = it },
            label = "ID Proof",
            options = ID_PROOF_OPTIONS,
            placeholder = "Select ID proof",
            required = true,
            modifier = Modifier.fillMaxWidth()
        )

        KioskTextField(
            value = state.idNumber,
            onValueChange = { state.idNumber = it },
            label = "ID Number",
            placeholder = "Number on the selected ID",
            helperText = "Number of the selected ID proof",
            required = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )

        KioskDateField(
            value = state.dateOfAdmission,
            onValueChange = { state.dateOfAdmission = it },
            label = "Admission Date",
            required = true
        )

        KioskTextField(
            value = state.prisonerNumber,
            onValueChange = { input -> state.prisonerNumber = input.filter { it.isDigit() }.take(12) },
            label = "Inmate Number",
            placeholder = "e.g. 420",
            helperText = "Numbers only",
            required = true,
            keyboardType = KeyboardType.Number,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )

        KioskSelectField(
            value = state.assignedKioskId,
            onValueChange = { state.assignedKioskId = it },
            label = "Assigned Kiosk",
            options = listOf("") + kioskOptions.map { it.kioskId },
            placeholder = "Select kiosk",
            required = true,
            display = { id ->
                if (id.isBlank()) "Select kiosk"
                else kioskOptions.find { it.kioskId == id }
                    ?.location?.takeIf { it.isNotBlank() }
                    ?.let { "$id · $it" } ?: id
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AddressCard(state: PrisonerFormState) {
    FormCard("Address Details") {
        KioskSelectField(
            value = state.religion,
            onValueChange = { state.religion = it },
            label = "Religion",
            options = RELIGION_OPTIONS,
            placeholder = "Select religion",
            required = true,
            modifier = Modifier.fillMaxWidth()
        )

        KioskSelectField(
            value = state.nationality,
            onValueChange = { state.nationality = it },
            label = "Nationality",
            options = NATIONALITY_OPTIONS,
            placeholder = "Select nationality",
            required = true,
            modifier = Modifier.fillMaxWidth()
        )

        KioskSelectField(
            value = state.state,
            onValueChange = { state.state = it },
            label = "State",
            options = STATE_OPTIONS,
            placeholder = "Select state",
            required = true,
            modifier = Modifier.fillMaxWidth()
        )

        KioskTextField(
            value = state.district,
            onValueChange = { state.district = it },
            label = "District",
            required = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        )

        KioskTextField(
            value = state.address,
            onValueChange = { state.address = it },
            label = "Address",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            required = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        )
    }
}

// --- Shared step chrome for add + edit ---

@Composable
fun PrisonerStepIndicator(
    stepNumber: Int,
    title: String,
    isActive: Boolean,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // The shared step header passes weight(1f) so every step gets an equal
        // share and 5 steps still fit a compact screen; bare callers wrap-content.
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = when {
                isCompleted -> SuccessGreen
                isActive -> PrimaryNavy
                else -> BorderColor
            },
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = stepNumber.toString(),
                        color = if (isActive) Color.White else TextGray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive || isCompleted) PrimaryNavy else TextGray,
            textAlign = TextAlign.Center
        )
    }
}

/** Progress bar + numbered step titles; shared by add and edit so both match. */
@Composable
fun PrisonerStepHeader(titles: List<String>, currentStep: Int) {
    LinearProgressIndicator(
        progress = (currentStep + 1f) / titles.size,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = PrimaryNavy,
        trackColor = BorderColor
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        titles.forEachIndexed { index, title ->
            PrisonerStepIndicator(
                stepNumber = index + 1,
                title = title,
                isActive = index == currentStep,
                isCompleted = index < currentStep,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
