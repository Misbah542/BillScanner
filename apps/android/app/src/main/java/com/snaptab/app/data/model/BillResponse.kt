package com.snaptab.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BillResponse(
    val items: List<BillItem>,
    val totals: BillTotals
)
