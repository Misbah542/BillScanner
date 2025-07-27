package com.billscanner.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.billscanner.app.data.model.BillItem
import com.billscanner.app.data.model.BillResponse
import com.billscanner.app.data.model.BillTotals
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillResultsScreen(
    billResponse: BillResponse,
    onNavigateBack: () -> Unit,
    onScanAnother: () -> Unit
) {
    val decimalFormat = DecimalFormat("#,##0.00")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("Bill Details") },
            navigationIcon = {
                TextButton(onClick = onNavigateBack) {
                    Text("Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Items Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Items",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Header Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Item",
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(2f)
                            )
                            Text(
                                text = "Qty",
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(0.8f)
                            )
                            Text(
                                text = "Rate",
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Amount",
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Divider(color = Color.Gray.copy(alpha = 0.3f))
                    }
                }
            }

            // Item List
            items(billResponse.items) { item ->
                BillItemRow(item = item, decimalFormat = decimalFormat)
            }

            // Totals Section
            item {
                BillTotalsCard(totals = billResponse.totals, decimalFormat = decimalFormat)
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Done")
                    }
                    
                    Button(
                        onClick = onScanAnother,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Another")
                    }
                }
            }
        }
    }
}

@Composable
private fun BillItemRow(
    item: BillItem,
    decimalFormat: DecimalFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name,
                modifier = Modifier.weight(2f),
                fontSize = 14.sp
            )
            Text(
                text = item.quantity.toString(),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.8f),
                fontSize = 14.sp
            )
            Text(
                text = "₹${decimalFormat.format(item.rate)}",
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp
            )
            Text(
                text = "₹${decimalFormat.format(item.amount)}",
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun BillTotalsCard(
    totals: BillTotals,
    decimalFormat: DecimalFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Bill Summary",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            TotalRow("Item Total", totals.itemTotal, decimalFormat)
            TotalRow("Service Charge", totals.serviceCharge, decimalFormat)
            TotalRow("State GST", totals.stateGst, decimalFormat)
            TotalRow("Central GST", totals.centralGst, decimalFormat)
            TotalRow("Round Off", totals.roundOff, decimalFormat)
            
            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.Gray.copy(alpha = 0.3f)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Net Amount",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "₹${decimalFormat.format(totals.netAmount)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    amount: Double,
    decimalFormat: DecimalFormat
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp
        )
        Text(
            text = "₹${decimalFormat.format(amount)}",
            fontSize = 14.sp
        )
    }
}
