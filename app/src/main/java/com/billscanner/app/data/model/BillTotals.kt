package com.billscanner.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BillTotals(
    @SerialName("item_total")
    val itemTotal: Double,
    @SerialName("service_charge")
    val serviceCharge: Double,
    @SerialName("state_gst")
    val stateGst: Double,
    @SerialName("central_gst")
    val centralGst: Double,
    @SerialName("round_off")
    val roundOff: Double,
    @SerialName("net_amount")
    val netAmount: Double
)
