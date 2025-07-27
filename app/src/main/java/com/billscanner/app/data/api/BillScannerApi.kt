package com.billscanner.app.data.api

import com.billscanner.app.data.model.BillResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface BillScannerApi {
    @Multipart
    @POST("scan-bill")
    suspend fun scanBill(
        @Part image: MultipartBody.Part
    ): Response<BillResponse>
}
