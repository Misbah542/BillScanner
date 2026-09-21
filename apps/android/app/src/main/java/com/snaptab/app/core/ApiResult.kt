package com.snaptab.app.core

/**
 * Every repository call comes back as one of these, so a screen cannot forget to
 * handle a failure. The old app returned `Result<T>` and then dropped the message
 * on the floor — the error was plumbed into UI state and never displayed.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * A failure the user can be told about. `message` is written by the server for a
     * person to read; `code` is for the app to branch on.
     */
    data class Failure(
        val code: String,
        val message: String,
        val httpStatus: Int? = null,
        val details: Map<String, String> = emptyMap(),
        val cause: Throwable? = null
    ) : ApiResult<Nothing> {

        val isOffline: Boolean get() = code == CODE_OFFLINE
        val isAuthExpired: Boolean get() = httpStatus == 401

        companion object {
            const val CODE_OFFLINE = "OFFLINE"
            const val CODE_UNKNOWN = "UNKNOWN"
        }
    }

    val successOrNull: T? get() = (this as? Success)?.data
    val failureOrNull: Failure? get() = this as? Failure
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) action(data)
    return this
}

inline fun <T> ApiResult<T>.onFailure(action: (ApiResult.Failure) -> Unit): ApiResult<T> {
    if (this is ApiResult.Failure) action(this)
    return this
}
