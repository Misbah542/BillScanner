package com.billscanner.app.data.repository

import com.billscanner.app.data.api.BillScannerApi
import com.billscanner.app.data.model.BillResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillScannerRepository @Inject constructor(
    private val api: BillScannerApi
) {
    suspend fun scanBill(imageFile: File): Result<BillResponse> {
        return try {
            val requestFile = imageFile.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)
            
            val response = api.scanBill(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to scan bill: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
