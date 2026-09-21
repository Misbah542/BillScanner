package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.ScanDto
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploading a receipt and waiting for the server to read it.
 *
 * The client does no recognition at all: it hands over the image and polls. That keeps
 * the OCR provider swappable, lets the parsing rules be fixed for everyone without an
 * app release, and means the scan finishes even if the user closes the app.
 */
@Singleton
class ScanRepository @Inject constructor(private val api: SnapTabApi) {

    suspend fun upload(image: File): ApiResult<ScanDto> {
        val mediaType = when (image.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
        }.toMediaTypeOrNull()

        val part = MultipartBody.Part.createFormData(
            "image",
            image.name,
            image.asRequestBody(mediaType)
        )

        // An idempotency key means a retry after a dropped connection does not queue the
        // same photo, and does not pay for a second OCR call.
        return apiCall { api.uploadScan(part, UUID.randomUUID().toString()) }.map { it.scan }
    }

    suspend fun status(scanId: String): ApiResult<ScanDto> =
        apiCall { api.scan(scanId) }.map { it.scan }

    suspend fun retry(scanId: String): ApiResult<ScanDto> =
        apiCall { api.retryScan(scanId) }.map { it.scan }

    /**
     * Polls until the scan finishes, with a ceiling so a stuck job surfaces as an error
     * rather than spinning forever. Backs off gently: most scans land on the first or
     * second poll and there is no point hammering.
     */
    suspend fun awaitResult(
        scanId: String,
        firstDelayMs: Long = 900,
        maxAttempts: Int = 20,
        onProgress: (ScanDto) -> Unit = {}
    ): ApiResult<ScanDto> {
        var wait = firstDelayMs
        repeat(maxAttempts) { attempt ->
            delay(wait)
            when (val result = status(scanId)) {
                is ApiResult.Success -> {
                    onProgress(result.data)
                    when (result.data.status) {
                        "SUCCEEDED", "FAILED" -> return result
                    }
                }
                is ApiResult.Failure -> if (result.isOffline || attempt >= maxAttempts - 1) return result
            }
            wait = (wait * 1.25).toLong().coerceAtMost(4_000)
        }
        return ApiResult.Failure(
            code = "SCAN_TIMEOUT",
            message = "That bill is taking unusually long to read. It will finish in the background."
        )
    }
}
