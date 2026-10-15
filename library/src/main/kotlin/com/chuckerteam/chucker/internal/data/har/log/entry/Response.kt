package com.chuckerteam.chucker.internal.data.har.log.entry

import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.data.har.log.entry.response.Content
import com.chuckerteam.chucker.internal.data.har.log.entry.response.Content.Companion.EMPTY
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection

// https://github.com/ahmadnassri/har-spec/blob/master/versions/1.2.md#response
// http://www.softwareishard.com/blog/har-12-spec/#response
internal data class Response(
    @SerializedName("status") val status: Int,
    @SerializedName("statusText") val statusText: String,
    @SerializedName("httpVersion") val httpVersion: String,
    @SerializedName("cookies") val cookies: List<Any> = emptyList(),
    @SerializedName("headers") val headers: List<Header>,
    @SerializedName("content") val content: Content? = null,
    @SerializedName("redirectURL") val redirectUrl: String = "",
    @SerializedName("headersSize") val headersSize: Long,
    @SerializedName("bodySize") val bodySize: Long,
    @SerializedName("totalSize") val totalSize: Long,
    @SerializedName("comment") val comment: String? = null,
) {
    constructor(transaction: HttpTransaction) : this(
        status = transaction.harStatus(),
        statusText = transaction.harStatusText(),
        httpVersion = transaction.harHttpVersion(),
        headers = transaction.getParsedResponseHeaders()?.map { Header(it) } ?: emptyList(),
        content = transaction.responsePayloadSize?.run { Content(transaction) } ?: EMPTY,
        headersSize = transaction.responseHeadersSize ?: 0,
        bodySize = transaction.getHarResponseBodySize(),
        totalSize = transaction.getResponseTotalSize(),
    )
}

// A gRPC status isn't an HTTP status: a call that got response headers was answered with a 200.
private fun HttpTransaction.harStatus(): Int =
    when {
        !isGrpc -> responseCode ?: 0
        responseHeaders != null -> HttpURLConnection.HTTP_OK
        else -> 0
    }

private fun HttpTransaction.harStatusText(): String =
    when {
        !isGrpc -> responseMessage ?: ""
        responseHeaders != null -> "OK"
        else -> ""
    }
