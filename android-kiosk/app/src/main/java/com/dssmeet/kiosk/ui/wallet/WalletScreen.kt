package com.dssmeet.kiosk.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dssmeet.kiosk.core.UiState
import com.dssmeet.kiosk.models.wallet.WalletTransaction
import com.dssmeet.kiosk.ui.components.KioskErrorState
import com.dssmeet.kiosk.ui.components.KioskLoadingState
import com.dssmeet.kiosk.ui.theme.LightBg
import com.dssmeet.kiosk.ui.theme.MoneyGreen
import com.dssmeet.kiosk.ui.theme.MoneyGreenBg
import com.dssmeet.kiosk.ui.theme.MoneyRed
import com.dssmeet.kiosk.ui.theme.MoneyRedBg
import com.dssmeet.kiosk.ui.theme.PrimaryDarkNavy
import com.dssmeet.kiosk.ui.theme.TextGray
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    windowSizeClass: WindowSizeClass,
    onBackClick: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val state by viewModel.walletState.collectAsState()
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            com.dssmeet.kiosk.ui.components.KioskTopBar(
                title = "My Wallet",
                showBackButton = true,
                onBackClick = onBackClick,
                leadingActions = {
                    IconButton(onClick = { viewModel.loadWallet() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        bottomBar = {
            com.dssmeet.kiosk.ui.components.KioskBottomActionBar(onBackClick = onBackClick)
        },
        containerColor = LightBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val s = state) {
                is UiState.Loading -> KioskLoadingState()
                is UiState.Error -> KioskErrorState(
                    message = s.message,
                    onRetry = { viewModel.loadWallet() }
                )
                is UiState.Success -> {
                    com.dssmeet.kiosk.ui.components.KioskResponsiveBox(maxWidth = 1100.dp) {
                        if (isExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                            WalletSummaryCard(
                                data = s.data,
                                modifier = Modifier.weight(0.8f),
                                wide = true
                            )
                            WalletTransactionsCard(
                                data = s.data,
                                modifier = Modifier.weight(1f),
                                wide = true
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            WalletSummaryCard(data = s.data)
                            Spacer(modifier = Modifier.height(16.dp))
                            WalletTransactionsCard(
                                data = s.data,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                        }
                }
                else -> Unit
            }
        }
    }
}

/** Balance card — family wallet-link se hi paisa aata hai, koi request UI nahi. */
@Composable
private fun WalletSummaryCard(
    data: WalletViewModel.WalletUiData,
    modifier: Modifier = Modifier,
    wide: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (wide) 28.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MoneyGreenBg,
                shape = CircleShape,
                modifier = Modifier.size(if (wide) 60.dp else 44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = MoneyGreen,
                        modifier = Modifier.size(if (wide) 30.dp else 22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (wide) 14.dp else 8.dp))
            Text(
                "Wallet Balance",
                fontSize = if (wide) 16.sp else 12.sp,
                color = TextGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // Server ledger stores paise; display rupees.
                text = "₹${String.format("%.2f", data.balance / 100.0)}",
                fontSize = if (wide) 34.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDarkNavy
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Money added by family",
                fontSize = if (wide) 14.sp else 11.sp,
                color = TextGray
            )
        }
    }
}

/** Transaction history — calm, scannable rows; two columns on tablets. */
@Composable
private fun WalletTransactionsCard(
    data: WalletViewModel.WalletUiData,
    modifier: Modifier = Modifier,
    wide: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(if (wide) 20.dp else 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Transaction History",
                        fontSize = if (wide) 20.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDarkNavy
                    )
                    Text(
                        "All credits and debits",
                        fontSize = if (wide) 13.sp else 11.sp,
                        color = TextGray
                    )
                }
                Surface(
                    color = MoneyGreenBg,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "${data.transactions.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = if (wide) 14.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoneyGreen
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (data.transactions.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Wallet,
                            contentDescription = null,
                            tint = TextGray,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No transactions yet.",
                            fontSize = if (wide) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            color = TextGray
                        )
                    }
                }
            } else if (wide) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 420.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    gridItems(data.transactions) { tx -> TransactionRow(tx, wide = true) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(data.transactions) { tx -> TransactionRow(tx) }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: WalletTransaction, wide: Boolean = false) {
    val isDebit = tx.isDebit
    val icon = if (isDebit) Icons.Default.RemoveCircle else Icons.Default.AddCircle
    val iconTint = if (isDebit) MoneyRed else MoneyGreen
    val iconBg = if (isDebit) MoneyRedBg else MoneyGreenBg
    // Transaction amounts are paise on the server ledger.
    val amountText = if (isDebit) "-₹${String.format("%.2f", tx.amount / 100.0)}" else "+₹${String.format("%.2f", tx.amount / 100.0)}"
    val amountColor = if (isDebit) MoneyRed else MoneyGreen
    val iconBox = if (wide) 44.dp else 36.dp

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = iconBg,
                shape = CircleShape,
                modifier = Modifier.size(iconBox)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(if (wide) 26.dp else 20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.displayDescription,
                    fontSize = if (wide) 16.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryDarkNavy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTransactionTime(tx.timestamp),
                    fontSize = if (wide) 13.sp else 11.sp,
                    color = TextGray
                )
                tx.chargesSubLine?.let { sub ->
                    Text(
                        text = sub,
                        fontSize = if (wide) 12.sp else 10.sp,
                        color = TextGray
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = amountText,
                    fontSize = if (wide) 20.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Text(
                    text = if (isDebit) "Debit" else "Credit",
                    fontSize = 10.sp,
                    color = TextGray
                )
            }
        }
    }
}

private fun formatTransactionTime(timestamp: String?): String {
    if (timestamp.isNullOrBlank()) return ""
    return try {
        val parsed = java.time.OffsetDateTime.parse(timestamp).toZonedDateTime()
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(parsed.toInstant().toEpochMilli()))
    } catch (e: Exception) {
        timestamp
    }
}

private fun formatRechargeDate(timestamp: String?): String {
    if (timestamp.isNullOrBlank()) return ""
    return try {
        val parsed = java.time.OffsetDateTime.parse(timestamp).toZonedDateTime()
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(parsed.toInstant().toEpochMilli()))
    } catch (e: Exception) {
        timestamp
    }
}
