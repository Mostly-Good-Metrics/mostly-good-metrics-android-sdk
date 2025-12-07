package com.mostlygoodmetrics.sdk

/**
 * Errors that can occur during SDK operations.
 */
sealed class MGMError : Exception() {

    /**
     * Network connectivity error.
     */
    data class NetworkError(override val cause: Throwable) : MGMError() {
        override val message: String = "Network error: ${cause.message}"
    }

    /**
     * Failed to encode events to JSON.
     */
    data class EncodingError(override val cause: Throwable) : MGMError() {
        override val message: String = "Encoding error: ${cause.message}"
    }

    /**
     * Server returned an invalid response.
     */
    data object InvalidResponse : MGMError() {
        override val message: String = "Invalid response from server"
    }

    /**
     * Bad request (400 error).
     */
    data class BadRequest(val details: String) : MGMError() {
        override val message: String = "Bad request: $details"
    }

    /**
     * Invalid API key (401 error).
     */
    data object Unauthorized : MGMError() {
        override val message: String = "Unauthorized: Invalid API key"
    }

    /**
     * Access forbidden (403 error).
     */
    data class Forbidden(val details: String) : MGMError() {
        override val message: String = "Forbidden: $details"
    }

    /**
     * Rate limited (429 error).
     */
    data class RateLimited(val retryAfterSeconds: Long?) : MGMError() {
        override val message: String = "Rate limited. Retry after: ${retryAfterSeconds ?: "unknown"} seconds"
    }

    /**
     * Server error (5xx).
     */
    data class ServerError(val statusCode: Int, val details: String) : MGMError() {
        override val message: String = "Server error ($statusCode): $details"
    }

    /**
     * Unexpected HTTP status code.
     */
    data class UnexpectedStatusCode(val statusCode: Int) : MGMError() {
        override val message: String = "Unexpected status code: $statusCode"
    }

    /**
     * Invalid event name.
     */
    data class InvalidEventName(val name: String) : MGMError() {
        override val message: String = "Invalid event name: $name"
    }
}
