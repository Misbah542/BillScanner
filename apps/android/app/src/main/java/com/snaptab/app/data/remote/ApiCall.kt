package com.snaptab.app.data.remote

import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.remote.dto.ApiErrorEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a Retrofit call into an [ApiResult], keeping the server's human-readable
 * message so a screen can show it verbatim.
 *
 * The API always reports failures as `{ error: { code, message, details? } }`, which is
 * why this can be one function for the whole app.
 */
private val errorJson = Json { ignoreUnknownKeys = true }

suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    val body = response.body()
    when {
        response.isSuccessful && body != null -> ApiResult.Success(body)

        // 204 on a Unit-returning call: success with nothing to hand back.
        response.isSuccessful -> @Suppress("UNCHECKED_CAST") ApiResult.Success(Unit as T)

        else -> parseError(response)
    }
} catch (error: UnknownHostException) {
    offline(error)
} catch (error: SocketTimeoutException) {
    ApiResult.Failure(
        code = "TIMEOUT",
        message = "The server took too long to answer. Try again.",
        cause = error
    )
} catch (error: IOException) {
    offline(error)
} catch (error: Exception) {
    ApiResult.Failure(
        code = ApiResult.Failure.CODE_UNKNOWN,
        message = error.message ?: "Something went wrong.",
        cause = error
    )
}

private fun offline(error: Throwable) = ApiResult.Failure(
    code = ApiResult.Failure.CODE_OFFLINE,
    message = "No connection.",
    cause = error
)

private fun <T> parseError(response: Response<T>): ApiResult.Failure {
    val raw = runCatching { response.errorBody()?.string() }.getOrNull()

    val parsed = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { errorJson.decodeFromString<ApiErrorEnvelope>(it) }.getOrNull() }

    // `details` can be an object of field errors or a list of validation issues; flatten
    // the object case, which is what the split engine sends its arithmetic in.
    val details = (parsed?.error?.details as? JsonObject)
        ?.mapValues { (_, value) -> runCatching { value.jsonPrimitive.content }.getOrDefault("") }
        .orEmpty()

    return ApiResult.Failure(
        code = parsed?.error?.code ?: "HTTP_${response.code()}",
        message = parsed?.error?.message ?: fallbackMessage(response.code()),
        httpStatus = response.code(),
        details = details
    )
}

private fun fallbackMessage(status: Int): String = when (status) {
    401 -> "Your session ended. Sign in again."
    403 -> "You do not have access to that."
    404 -> "That could not be found."
    409 -> "That conflicts with something that already exists."
    413 -> "That file is too large."
    429 -> "Too many attempts. Try again shortly."
    in 500..599 -> "The server had a problem. Try again."
    else -> "Something went wrong."
}
