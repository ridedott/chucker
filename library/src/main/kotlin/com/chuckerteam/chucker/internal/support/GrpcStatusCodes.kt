package com.chuckerteam.chucker.internal.support

/**
 * gRPC status codes, as defined in `google/rpc/code.proto`.
 *
 * Kept independent from `io.grpc.Status` on purpose: gRPC is an optional dependency of Chucker,
 * so the UI must never load `io.grpc` classes.
 */
internal object GrpcStatusCodes {
    const val OK = 0

    private val NAMES =
        listOf(
            "OK",
            "CANCELLED",
            "UNKNOWN",
            "INVALID_ARGUMENT",
            "DEADLINE_EXCEEDED",
            "NOT_FOUND",
            "ALREADY_EXISTS",
            "PERMISSION_DENIED",
            "RESOURCE_EXHAUSTED",
            "FAILED_PRECONDITION",
            "ABORTED",
            "OUT_OF_RANGE",
            "UNIMPLEMENTED",
            "INTERNAL",
            "UNAVAILABLE",
            "DATA_LOSS",
            "UNAUTHENTICATED",
        )

    // The HTTP status that google.rpc.Code documents as the closest match for each code.
    private val HTTP_EQUIVALENTS =
        intArrayOf(200, 499, 500, 400, 504, 404, 409, 403, 429, 400, 409, 400, 501, 500, 503, 500, 401)

    private const val FALLBACK_HTTP_STATUS = 500

    fun name(code: Int): String = NAMES.getOrNull(code) ?: "CODE_$code"

    /** Returns the closest HTTP status for [code], so that gRPC rows share the HTTP colours. */
    fun httpEquivalent(code: Int): Int = HTTP_EQUIVALENTS.getOrNull(code) ?: FALLBACK_HTTP_STATUS
}
