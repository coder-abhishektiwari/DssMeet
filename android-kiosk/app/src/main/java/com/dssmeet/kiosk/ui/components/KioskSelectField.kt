package com.dssmeet.kiosk.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.TextGray

/**
 * Read-only dropdown backed by a Material pick list, so selection fields stay
 * tap-only — the admin never has to type an option by hand.
 * Empty value shows a "Select…" placeholder so the field never looks blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskSelectField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    placeholder: String = "Select…",
    errorText: String? = null,
    display: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    val isError = errorText != null
    val shown = if (value.isBlank()) placeholder else display(value)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!expanded) expanded = true },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(if (required) "$label *" else label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            isError = isError,
            supportingText = {
                Text(
                    text = errorText ?: " ",
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
                unfocusedTextColor = if (value.isBlank()) TextGray else PrimaryDarkNavy,
                errorBorderColor = DangerRed,
                errorLabelColor = DangerRed
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Empty option only when the field is optional and currently blank.
            if (!required && value.isBlank()) {
                DropdownMenuItem(
                    text = { Text(placeholder) },
                    onClick = {
                        onValueChange("")
                        expanded = false
                    }
                )
            }
            options.forEach { option ->
                if (option.isBlank() && required) return@forEach
                DropdownMenuItem(
                    text = { Text(display(option).ifBlank { placeholder }) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
