package com.dssmeet.kiosk.ui.auth

import com.dssmeet.kiosk.ui.theme.ControlBtnBg
import com.dssmeet.kiosk.ui.theme.DangerRed
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.PrimaryBlue
import com.dssmeet.kiosk.ui.theme.PrimaryNavy
import com.dssmeet.kiosk.ui.theme.SuccessGreen


import com.dssmeet.kiosk.ui.theme.TextGray

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition

@Composable
fun PremiumAuthCard(
    title: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    large: Boolean = false
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "Scale")

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .clickable { onClick() },
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (large) 72.dp else if (compact) 42.dp else 52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PrimaryNavy, PrimaryDarkNavy)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (large) 40.dp else if (compact) 22.dp else 28.dp)
                )
            }
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
            Text(
                text = title,
                fontSize = if (large) 22.sp else if (compact) 15.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = if (large) 15.sp else if (compact) 11.sp else 12.sp,
                color = TextGray,
                textAlign = TextAlign.Center,
                lineHeight = if (large) 20.sp else if (compact) 14.sp else 16.sp
            )
        }
    }
}

@Composable
fun IPhoneKeypad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val numbers = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "DEL")
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        numbers.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { item ->
                    if (item.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else {
                        KeypadButton(
                            text = item,
                            onClick = {
                                if (item == "DEL") onDeleteClick() else onNumberClick(item)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        color = ControlBtnBg,
        tonalElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (text == "DEL") {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, tint = PrimaryDarkNavy)
            } else {
                Text(
                    text = text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryDarkNavy
                )
            }
        }
    }
}

/**
 * Strict 10-digit RFID card-number entry: dot indicator + on-screen numeric
 * keypad, same interaction as the Prisoner ID entry. Digits cap at 10 —
 * callers auto-submit when [onDigitsChange] reports a full number.
 */
@Composable
fun RfidKeypadEntry(
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(10) { index ->
                val filled = digits.length > index
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (filled) PrimaryDarkNavy else Color.LightGray.copy(alpha = 0.5f))
                        .border(1.dp, if (filled) PrimaryDarkNavy else Color.Gray, CircleShape)
                )
            }
        }

        IPhoneKeypad(
            onNumberClick = {
                if (digits.length < 10) onDigitsChange(digits + it)
            },
            onDeleteClick = {
                if (digits.isNotEmpty()) onDigitsChange(digits.dropLast(1))
            }
        )
    }
}

@Composable
fun PremiumLoadingOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.95f),
            tonalElevation = 8.dp,
            modifier = Modifier.size(200.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = PrimaryNavy,
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = message,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDarkNavy,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Looping "tap your card on the reader" animation used on the RFID login
 * screen and the RFID registration dialog.
 * Source: assets/animations/rfid_card_tap_animation.json
 */
@Composable
fun RfidTapAnimation(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("animations/rfid_card_tap_animation.json")
    )
    LottieAnimation(
        composition = composition,
        modifier = modifier,
        iterations = LottieConstants.IterateForever
    )
}

@Composable
fun FingerprintScanAnimation(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("animations/fingerprint_scan_animation.json")
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (composition != null) {
            LottieAnimation(
                composition = composition,
                modifier = Modifier.matchParentSize(),
                iterations = LottieConstants.IterateForever
            )
        } else {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = null,
                tint = PrimaryNavy,
                modifier = Modifier.size(72.dp)
            )
        }
    }
}

@Composable
fun FingerprintFeedbackIcon(
    isScanning: Boolean,
    isMismatch: Boolean,
    isDeviceError: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val iconTint by animateColorAsState(
        targetValue = when {
            isMismatch || isDeviceError -> DangerRed
            isScanning -> Color(0xFF2E7D32)
            else -> PrimaryNavy
        },
        animationSpec = tween(220),
        label = "FingerprintIconTint"
    )

    val shake = remember { Animatable(0f) }
    LaunchedEffect(isMismatch, reduceMotion) {
        if (isMismatch && !reduceMotion) {
            repeat(4) {
                shake.animateTo(-12f, tween(70, easing = LinearEasing))
                shake.animateTo(12f, tween(110, easing = LinearEasing))
                shake.animateTo(-8f, tween(110, easing = LinearEasing))
            }
            shake.animateTo(0f, tween(90))
        } else {
            shake.snapTo(0f)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "FingerprintScan")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FingerprintScanProgress"
    )
    val scanY = (-58 + scanProgress * 116).dp

    Box(
        modifier = modifier
            .offset(x = shake.value.dp)
            .clip(CircleShape)
            .background(iconTint.copy(alpha = 0.10f))
            .border(1.dp, iconTint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isScanning && !reduceMotion) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = scanY)
                        .size(width = 150.dp, height = 24.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF86EFAC).copy(alpha = 0.75f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = when {
                isMismatch -> "Fingerprint not recognized"
                isDeviceError -> "Fingerprint scanner error"
                isScanning -> "Reading fingerprint"
                else -> "Place finger on scanner"
            },
            tint = iconTint,
            modifier = Modifier.size(84.dp)
        )
    }
}

/**
 * Invisible RFID card-number capture field for USB HID readers (HID mode).
 *
 * The reader types the card number and then sends Enter (trailing `\n`).
 * The field is [readOnly] — CRITICAL: suppresses the Android virtual
 * on-screen soft keyboard while still accepting physical HID keystrokes —
 * auto-focused on mount via [FocusRequester], and pinned1dp transparent
 * at the back of the z-order so nothing is visible.
 * [onCardDetected] fires as soon as Enter arrives (or automatically once
 * [cardLength] digits are collected) and the buffer resets for the next tap.
 *
 * Pass [enabled] = false while a dialog owns the screen; focus returns to
 * this field automatically when it is enabled again.
 */
@Composable
fun HiddenRfidTapInput(
    cardLength: Int = 10,
    enabled: Boolean = true,
    onCardDetected: (String) -> Unit
) {
    var digits by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Bumped whenever focus is lost so the field can steal it back —
    // kiosk screens have no other text input, so nothing competes for it.
    var refocusTick by remember { mutableStateOf(0) }

    fun submit() {
        val card = digits.filter { it.isDigit() }
        digits = ""
        if (card.isNotEmpty()) onCardDetected(card)
    }

    fun digitFor(key: Key): Char? = when (key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }

    // Auto-focus the field the instant the screen mounts (or re-enable).
    LaunchedEffect(enabled, refocusTick) {
        if (!enabled) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
        keyboardController?.hide()
    }

    // STRICT soft-keyboard suppression net (OEM IMEs sometimes ignore
    // readOnly alone): the IME is banned at the WINDOW level while this
    // field is enabled — SOFT_INPUT_STATE_ALWAYS_HIDDEN wins even over
    // IMEs that ignore SoftwareKeyboardController.hide(). Scoped to this
    // composable's lifetime, so fields on the underlying screen are safe.
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val activityWindow = if (enabled) {
            var ctx: Context? = view.context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            (ctx as? Activity)?.window
        } else {
            null
        }
        activityWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        onDispose {
            activityWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED)
        }
    }

    TextField(
        value = "",
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        singleLine = true,
        modifier = Modifier
            .size(1.dp)
            .zIndex(-1f)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (enabled) {
                    if (it.isFocused) keyboardController?.hide()
                    else refocusTick++
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val digit = digitFor(event.key)
                if (digit != null) {
                    digits = (digits + digit).take(cardLength)
                    if (digits.length == cardLength) submit()
                    return@onKeyEvent true
                }
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        submit()
                        true
                    }
                    Key.Backspace -> {
                        digits = digits.dropLast(1)
                        true
                    }
                    else -> false
                }
            },
        textStyle = TextStyle(color = Color.Transparent),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled) submit() })
    )
}
