package com.chuckerteam.chucker.internal.data.har.log

import androidx.annotation.VisibleForTesting
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.data.har.log.entry.Cache
import com.chuckerteam.chucker.internal.data.har.log.entry.Header
import com.chuckerteam.chucker.internal.data.har.log.entry.Request
import com.chuckerteam.chucker.internal.data.har.log.entry.Response
import com.chuckerteam.chucker.internal.data.har.log.entry.Timings
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// https://github.com/ahmadnassri/har-spec/blob/master/versions/1.2.md#entries
// http://www.softwareishard.com/blog/har-12-spec/#entries
internal data class Entry(
    @SerializedName("pageref") val pageref: String? = null,
    @SerializedName("startedDateTime") val startedDateTime: String,
    @SerializedName("time") var time: Long,
    @SerializedName("request") val request: Request,
    @SerializedName("response") val response: Response,
    @SerializedName("cache") val cache: Cache,
    @SerializedName("timings") val timings: Timings,
    @SerializedName("serverIPAddress") val serverIPAddress: String? = null,
    @SerializedName("connection") val connection: String? = null,
    @SerializedName("comment") val comment: String? = null,
    // Custom fields must start with an underscore, see the HAR 1.2 specification.
    @SerializedName("_grpc") val grpc: Grpc? = null,
) {
    constructor(transaction: HttpTransaction) : this(
        startedDateTime = transaction.requestDate?.harFormatted().orEmpty(),
        time = Timings(transaction).getTime(),
        request = Request(transaction),
        response = Response(transaction),
        cache = Cache(),
        timings = Timings(transaction),
        grpc = if (transaction.isGrpc) Grpc(transaction) else null,
    )

    /** What HAR can't express about a gRPC call. */
    internal data class Grpc(
        @SerializedName("method") val method: String,
        @SerializedName("callType") val callType: String?,
        @SerializedName("statusCode") val statusCode: Int?,
        @SerializedName("status") val status: String?,
        @SerializedName("statusDescription") val statusDescription: String?,
        @SerializedName("trailers") val trailers: List<Header>,
        @SerializedName("requestMessageCount") val requestMessageCount: Int,
        @SerializedName("responseMessageCount") val responseMessageCount: Int,
    ) {
        constructor(transaction: HttpTransaction) : this(
            method = transaction.grpcFullMethodName,
            callType = transaction.grpcMethodType,
            statusCode = transaction.responseCode,
            status = transaction.responseMessage,
            statusDescription = transaction.grpcStatusDescription,
            trailers = transaction.getParsedResponseTrailers()?.map { Header(it) }.orEmpty(),
            requestMessageCount = transaction.requestMessageCount,
            responseMessageCount = transaction.responseMessageCount,
        )
    }

    @VisibleForTesting
    object DateFormat : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
    }

    companion object {
        private const val DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

        private fun Long.harFormatted(): String = DateFormat.get()!!.format(Date(this))
    }
}
