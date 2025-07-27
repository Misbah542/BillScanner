package com.billscanner.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.billscanner.app.ui.viewmodel.BillScannerViewModel

@Composable
fun HomeScreen(
    onNavigateToCamera: () -> Unit,
    onNavigateToBillResults: () -> Unit,
    viewModel: BillScannerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle navigation when bill is successfully scanned
    LaunchedEffect(uiState.billResponse) {
        if (uiState.billResponse != null) {
            onNavigateToBillResults()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Logo/Icon placeholder
            Card(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(60.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📄",
                        fontSize = 48.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App Title
            Text(
                text = "Bill Scanner",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Text(
                text = "Scan your bills and get detailed breakdown of items and totals",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Scan Button
            Button(
                onClick = onNavigateToCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Processing...")
                } else {
                    Text(
                        text = "📷 Scan Bill",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Features
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Features",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    FeatureItem("📱", "Easy Camera Scanning")
                    FeatureItem("📊", "Detailed Item Breakdown")
                    FeatureItem("💰", "Tax & Total Calculations")
                    FeatureItem("⚡", "Fast Processing")
                }
            }
        }

        // Error handling
        uiState.error?.let { error ->
            LaunchedEffect(error) {
                // Show error snackbar or dialog
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: String,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 20.sp,
            modifier = Modifier.padding(end = 16.dp)
        )
        Text(
            text = text,
            fontSize = 16.sp,
            color = Color.Gray
        )
    }
}

