package com.billscanner.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BillItem(
    val name: String,
    val quantity: Int,
    val rate: Double,
    val amount: Double
)
