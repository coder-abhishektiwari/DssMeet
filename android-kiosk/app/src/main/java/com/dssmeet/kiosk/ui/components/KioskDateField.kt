package com.dssmeet.kiosk.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dssmeet.kiosk.ui.theme.BorderColor
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.TextGray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val STORAGE_PATTERN = "yyyy-MM-dd"
private const val DISPLAY_PATTERN = "dd MMM yyyy"
private const val LEGACY_DISPLAY_PATTERN = "dd-MM-yyyy"
private const val LEGACY_INPUT_PATTERN = "yyyy-MM-dd HH:mm:ss"

private fun storageFormat(): SimpleDateFormat =
    SimpleDateFormat(STORAGE_PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun millisOf(raw: String): Long? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    listOf(STORAGE_PATTERN, LEGACY_DISPLAY_PATTERN, LEGACY_INPUT_PATTERN).forEach { pattern ->
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            sdf.isLenient = false
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.parse(value)?.time
        } catch (_: Exception) {
        }
    }
    return null
}

private fun displayOf(raw: String): String {
    val millis = millisOf(raw) ?: return raw.trim()
    return SimpleDateFormat(DISPLAY_PATTERN, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))
}

private fun storageOf(millis: Long): String =
    storageFormat().format(Date(millis))

/**
 * The ONE date field every form uses. Stores ISO `yyyy-MM-dd` but only lets
 * the admin pick from a Material date picker. Looks enabled (not greyed-out)
 * with a calendar trailing icon so it reads as tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    placeholder: String = "Pick a date",
    errorText: String? = null,
    onClear: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    val isError = errorText != null
    val display = displayOf(value)
    val shown = display.ifBlank { placeholder }

    OutlinedTextField(
        value = shown,
        onValueChange = {},
        label = { Text(if (required) "$label *" else label) },
        readOnly = true,
        enabled = true,
        singleLine = true,
        isError = isError,
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { (onClear ?: { onValueChange("") })() }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear $label", tint = TextGray)
                }
            } else {
                Icon(Icons.Default.CalendarToday, contentDescription = "Pick $label", tint = PrimaryNavy)
            }
        },
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
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { showPicker = true }
    )

    if (showPicker) {
        val initialMillis = millisOf(value) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onValueChange(storageOf(it)) }
                        showPicker = false
                    }
                ) { Text("OK", color = PrimaryNavy) }
            },
            dismissButton = {
                if (value.isNotBlank() || onClear != null) {
                    TextButton(
                        onClick = {
                            (onClear ?: { onValueChange("") })()
                            showPicker = false
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear $label",
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Clear")
                    }
                } else {
                    TextButton(onClick = { showPicker = false }) { Text("Cancel") }
                }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}
