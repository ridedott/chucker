package com.chuckerteam.chucker.internal.data.entity

import androidx.room.ColumnInfo
import com.chuckerteam.chucker.internal.support.FormatUtils
import com.chuckerteam.chucker.internal.support.FormattedUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A subset of [HttpTransaction] to perform faster Read operations on the Repository.
 * This Tuple is good to be used on List or Preview interfaces.
 */
@Suppress("LongParameterList")
internal data class HttpTransactionTuple(
    @ColumnInfo(name = "id") var id: Long,
    @ColumnInfo(name = "requestDate") var requestDate: Long?,
    @ColumnInfo(name = "tookMs") var tookMs: Long?,
    @ColumnInfo(name = "protocol") var protocol: String?,
    @ColumnInfo(name = "method") var method: String?,
    @ColumnInfo(name = "host") var host: String?,
    @ColumnInfo(name = "path") var path: String?,
    @ColumnInfo(name = "scheme") var scheme: String?,
    @ColumnInfo(name = "responseCode") var responseCode: Int?,
    @ColumnInfo(name = "requestPayloadSize") var requestPayloadSize: Long?,
    @ColumnInfo(name = "responsePayloadSize") var responsePayloadSize: Long?,
    @ColumnInfo(name = "error") var error: String?,
    @ColumnInfo(name = "graphQlDetected") var graphQlDetected: Boolean = false,
    @ColumnInfo(name = "graphQlOperationName") var graphQlOperationName: String?,
    @ColumnInfo(name = "isGrpc") var isGrpc: Boolean = false,
    @ColumnInfo(name = "grpcMethodType") var grpcMethodType: String? = null,
    @ColumnInfo(name = "requestMessageCount") var requestMessageCount: Int = 0,
    @ColumnInfo(name = "responseMessageCount") var responseMessageCount: Int = 0,
) {
    val isSsl: Boolean get() = scheme.equals("https", ignoreCase = true)

    /** Whether this is a gRPC call that streams messages in at least one direction. */
    val isGrpcStream: Boolean get() = isGrpc && grpcMethodType != "UNARY"

    val status: HttpTransaction.Status
        get() =
            when {
                error != null -> HttpTransaction.Status.Failed
                responseCode == null -> HttpTransaction.Status.Requested
                else -> HttpTransaction.Status.Complete
            }

    val durationString: String? get() = tookMs?.let { "$it ms" }

    val totalSizeString: String
        get() {
            val reqBytes = requestPayloadSize ?: 0
            val resBytes = responsePayloadSize ?: 0
            return formatBytes(reqBytes + resBytes)
        }

    private fun formatBytes(bytes: Long): String = FormatUtils.formatByteCount(bytes, true)

    fun getFormattedPath(encode: Boolean): String {
        val path = this.path ?: return ""
        if (isGrpc) return path

        // Create dummy URL since there is no data in this class to get it from
        // and we are only interested in a formatted path with query.
        val dummyUrl = "https://www.example.com$path"

        val httpUrl = dummyUrl.toHttpUrlOrNull() ?: return ""
        return FormattedUrl.fromHttpUrl(httpUrl, encode).pathWithQuery
    }
}
