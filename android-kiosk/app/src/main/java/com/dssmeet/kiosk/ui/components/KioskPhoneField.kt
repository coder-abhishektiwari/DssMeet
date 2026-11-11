package com.dssmeet.kiosk.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.TextGray

/** Single supported dial option — only India is offered today. */
private data class DialOption(val iso: String, val code: String, val label: String)

private val DIAL_OPTIONS = listOf(
    DialOption("IN", "+91", "IN +91")
)

/**
 * Shared phone field: a country-code dropdown (only IN +91) plus a 10-digit
 * number box. [onValueChange] always receives the full number with the
 * selected country code prepended (e.g. `+919876543210`).
 *
 * Bare 10-digit values are accepted on input and re-emitted with `+91`.
 */
@Composable
fun KioskPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    helperText: String? = "10-digit mobile number",
    errorText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = DIAL_OPTIONS.first()

    // Show only the national number in the box; keep +91 in the emitted value.
    val nationalDigits = remember(value) {
        val digits = value.filter { it.isDigit() }
        when {
            value.startsWith("+91") || value.startsWith("91") && value.length >= 12 ->
                digits.takeLast(10)
            else -> digits.takeLast(10)
        }
    }
    val isError = errorText != null

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Country-code selector (single option today — still a real dropdown).
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isError) DangerRed else BorderColor
            ),
            modifier = Modifier.height(56.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Text(
                    text = selected.label,
                    color = PrimaryDarkNavy,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select country code",
                    tint = PrimaryNavy
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DIAL_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        // Re-emit with this dial code (only +91 exists today).
                        onValueChange(option.code + nationalDigits)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = nationalDigits,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(10)
                onValueChange(selected.code + digits)
            },
            label = { Text(if (required) "$label *" else label) },
            placeholder = { Text("98765 43210") },
            isError = isError,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = imeAction
            ),
            supportingText = {
                Text(
                    text = errorText ?: helperText ?: " ",
                    color = if (isError) DangerRed else TextGray,
                    maxLines = 2
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryNavy,
                unfocusedBorderColor = BorderColor,
                focusedLabelColor = PrimaryNavy,
                unfocusedLabelColor = TextGray,
                focusedTextColor = PrimaryDarkNavy,
                unfocusedTextColor = PrimaryDarkNavy,
                errorBorderColor = DangerRed,
                errorLabelColor = DangerRed
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}

/**
 * Normalizes any stored phone (bare 10-digit, `91…`, `0…`, or full `+91…`)
 * to the canonical `+91XXXXXXXXXX` form the app sends around.
 */
fun normalizeIndianPhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    val national = when {
        digits.length >= 12 && digits.startsWith("91") -> digits.takeLast(10)
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        else -> digits.takeLast(10)
    }
    return if (national.length == 10) "+91$national" else raw.trim()
}

/** Digits only (no `+91`) — what the backend's 10-digit check expects. */
fun phoneNationalDigits(raw: String): String =
    normalizeIndianPhone(raw).filter { it.isDigit() }.takeLast(10)
