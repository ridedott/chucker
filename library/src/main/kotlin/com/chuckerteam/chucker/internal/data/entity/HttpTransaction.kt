@file:Suppress("TooManyFunctions")

package com.chuckerteam.chucker.internal.data.entity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.chuckerteam.chucker.internal.support.FormatUtils
import com.chuckerteam.chucker.internal.support.FormattedUrl
import com.chuckerteam.chucker.internal.support.HttpHeaderSerializer
import com.chuckerteam.chucker.internal.support.JsonConverter
import com.chuckerteam.chucker.internal.support.SpanTextUtil
import com.google.gson.reflect.TypeToken
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.HttpURLConnection
import java.util.Date

/**
 * Represent a full HTTP transaction (with Request and Response). Instances of this classes
 * should be populated as soon as the library receives data from OkHttp.
 */
@Suppress("LongParameterList")
@Entity(tableName = "transactions")
internal class HttpTransaction(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var id: Long = 0,
    @ColumnInfo(name = "requestDate") var requestDate: Long?,
    @ColumnInfo(name = "responseDate") var responseDate: Long?,
    @ColumnInfo(name = "tookMs") var tookMs: Long?,
    @ColumnInfo(name = "protocol") var protocol: String?,
    @ColumnInfo(name = "method") var method: String?,
    @ColumnInfo(name = "url") var url: String?,
    @ColumnInfo(name = "hostIp") var hostIp: String?,
    @ColumnInfo(name = "host") var host: String?,
    @ColumnInfo(name = "path") var path: String?,
    @ColumnInfo(name = "scheme") var scheme: String?,
    @ColumnInfo(name = "responseTlsVersion") var responseTlsVersion: String?,
    @ColumnInfo(name = "responseCipherSuite") var responseCipherSuite: String?,
    @ColumnInfo(name = "requestPayloadSize") var requestPayloadSize: Long?,
    @ColumnInfo(name = "requestContentType") var requestContentType: String?,
    @ColumnInfo(name = "requestHeaders") var requestHeaders: String?,
    @ColumnInfo(name = "requestHeadersSize") var requestHeadersSize: Long?,
    @ColumnInfo(name = "requestBody") var requestBody: String?,
    @ColumnInfo(name = "isRequestBodyEncoded") var isRequestBodyEncoded: Boolean = false,
    @ColumnInfo(name = "responseCode") var responseCode: Int?,
    @ColumnInfo(name = "responseMessage") var responseMessage: String?,
    @ColumnInfo(name = "error") var error: String?,
    @ColumnInfo(name = "responsePayloadSize") var responsePayloadSize: Long?,
    @ColumnInfo(name = "responseContentType") var responseContentType: String?,
    @ColumnInfo(name = "responseHeaders") var responseHeaders: String?,
    @ColumnInfo(name = "responseHeadersSize") var responseHeadersSize: Long?,
    @ColumnInfo(name = "responseBody") var responseBody: String?,
    @ColumnInfo(name = "isResponseBodyEncoded") var isResponseBodyEncoded: Boolean = false,
    @ColumnInfo(name = "responseImageData") var responseImageData: ByteArray?,
    @ColumnInfo(name = "graphQlDetected") var graphQlDetected: Boolean = false,
    @ColumnInfo(name = "graphQlOperationName") var graphQlOperationName: String?,
    @ColumnInfo(name = "isGrpc") var isGrpc: Boolean = false,
    @ColumnInfo(name = "grpcMethodType") var grpcMethodType: String? = null,
    @ColumnInfo(name = "grpcStatusDescription") var grpcStatusDescription: String? = null,
    @ColumnInfo(name = "grpcDeadlineMs") var grpcDeadlineMs: Long? = null,
    @ColumnInfo(name = "responseTrailers") var responseTrailers: String? = null,
    @ColumnInfo(name = "requestMessageCount") var requestMessageCount: Int = 0,
    @ColumnInfo(name = "responseMessageCount") var responseMessageCount: Int = 0,
    @ColumnInfo(name = "grpcEvents") var grpcEvents: String? = null,
) {
    // Parsing the events is expensive, so the last result is kept together with its source.
    @Ignore
    @Volatile
    private var grpcEventsCache: Pair<String, List<GrpcEvent>>? = null

    @Ignore
    constructor() : this(
        requestDate = null,
        responseDate = null,
        tookMs = null,
        protocol = null,
        method = null,
        url = null,
        hostIp = null,
        host = null,
        path = null,
        scheme = null,
        responseTlsVersion = null,
        responseCipherSuite = null,
        requestPayloadSize = null,
        requestContentType = null,
        requestHeaders = null,
        requestHeadersSize = null,
        requestBody = null,
        responseCode = null,
        responseMessage = null,
        error = null,
        responsePayloadSize = null,
        responseContentType = null,
        responseHeaders = null,
        responseHeadersSize = null,
        responseBody = null,
        responseImageData = null,
        graphQlOperationName = null,
    )

    enum class Status {
        Requested,
        Complete,
        Failed,
    }

    val status: Status
        get() =
            when {
                error != null -> Status.Failed
                responseCode == null -> Status.Requested
                else -> Status.Complete
            }

    val requestDateString: String?
        get() = requestDate?.let { Date(it).toString() }

    val responseDateString: String?
        get() = responseDate?.let { Date(it).toString() }

    val durationString: String?
        get() = tookMs?.let { "$it ms" }

    val requestSizeString: String
        get() = formatBytes(requestPayloadSize ?: 0)

    val responseSizeString: String?
        get() = responsePayloadSize?.let { formatBytes(it) }

    val totalSizeString: String
        get() {
            val reqBytes = requestPayloadSize ?: 0
            val resBytes = responsePayloadSize ?: 0
            return formatBytes(reqBytes + resBytes)
        }

    val responseSummaryText: String?
        get() {
            // A failed gRPC call still has a status, its error is shown separately.
            if (isGrpc && responseCode != null) return "$responseCode $responseMessage"
            return when (status) {
                Status.Failed -> error
                Status.Requested -> null
                else -> "$responseCode $responseMessage"
            }
        }

    val notificationText: String
        get() {
            val target = if (isGrpc) grpcFullMethodName else "$method $path"
            return when (status) {
                Status.Failed -> " ! ! !  $target"
                Status.Requested -> " . . .  $target"
                else -> if (isGrpc) "$responseMessage $target" else "$responseCode $target"
            }
        }

    /** The full gRPC method name, e.g. `ride.v1.RideService/WatchRide`. */
    val grpcFullMethodName: String
        get() = path?.removePrefix("/").orEmpty()

    val isSsl: Boolean
        get() = scheme.equals("https", ignoreCase = true)

    val responseImageBitmap: Bitmap?
        get() {
            return responseImageData?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        }

    fun setRequestHeaders(headers: Headers) {
        setRequestHeaders(toHttpHeaderList(headers))
    }

    fun setRequestHeaders(headers: List<HttpHeader>) {
        requestHeaders = HttpHeaderSerializer.toJson(headers)
    }

    fun setGraphQlOperationName(headers: Headers) {
        graphQlOperationName =
            toHttpHeaderList(headers)
                .find { it.name.lowercase().contains("operation-name") }
                ?.value
    }

    fun getParsedRequestHeaders(): List<HttpHeader>? =
        JsonConverter.instance.fromJson<List<HttpHeader>>(
            requestHeaders,
            TypeToken.getParameterized(List::class.java, HttpHeader::class.java).type,
        )

    fun getParsedResponseHeaders(): List<HttpHeader>? =
        JsonConverter.instance.fromJson<List<HttpHeader>>(
            responseHeaders,
            TypeToken.getParameterized(List::class.java, HttpHeader::class.java).type,
        )

    fun getRequestHeadersString(withMarkup: Boolean): String =
        FormatUtils.formatHeaders(
            httpHeaders = getParsedRequestHeaders(),
            withMarkup = withMarkup,
        )

    fun setResponseHeaders(headers: Headers) {
        setResponseHeaders(toHttpHeaderList(headers))
    }

    fun setResponseHeaders(headers: List<HttpHeader>) {
        responseHeaders = HttpHeaderSerializer.toJson(headers)
    }

    fun getResponseHeadersString(withMarkup: Boolean): String =
        FormatUtils.formatHeaders(
            httpHeaders = getParsedResponseHeaders(),
            withMarkup = withMarkup,
        )

    fun setResponseTrailers(trailers: List<HttpHeader>) {
        responseTrailers = HttpHeaderSerializer.toJson(trailers)
    }

    fun getParsedResponseTrailers(): List<HttpHeader>? =
        JsonConverter.instance.fromJson<List<HttpHeader>>(
            responseTrailers,
            TypeToken.getParameterized(List::class.java, HttpHeader::class.java).type,
        )

    fun getResponseTrailersString(withMarkup: Boolean): String =
        FormatUtils.formatHeaders(
            httpHeaders = getParsedResponseTrailers(),
            withMarkup = withMarkup,
        )

    /** Returns the recorded events of a gRPC call, oldest first. */
    fun getGrpcEvents(): List<GrpcEvent> {
        val source = grpcEvents ?: return emptyList()
        val cached = grpcEventsCache
        if (cached != null && cached.first === source) return cached.second
        val type = TypeToken.getParameterized(List::class.java, GrpcEvent::class.java).type
        val events =
            runCatching { JsonConverter.instance.fromJson<List<GrpcEvent>>(source, type) }
                .getOrNull()
                .orEmpty()
        grpcEventsCache = source to events
        return events
    }

    fun getGrpcMessages(isRequest: Boolean): List<GrpcEvent> {
        val type = if (isRequest) GrpcEvent.Type.MESSAGE_SENT else GrpcEvent.Type.MESSAGE_RECEIVED
        return getGrpcEvents().filter { it.type == type }
    }

    /**
     * Returns all the recorded messages of one direction of a gRPC call as a single text, used
     * when copying, saving or sharing. A call with a single message returns just that message.
     */
    fun getGrpcBodyText(
        isRequest: Boolean,
        pretty: Boolean,
    ): String {
        val messages = getGrpcMessages(isRequest)
        val totalCount = if (isRequest) requestMessageCount else responseMessageCount
        if (messages.size == 1 && totalCount == 1) return messages.single().bodyText(pretty)
        return messages.joinToString(separator = "\n\n") { message ->
            val size = formatBytes(message.size ?: 0)
            "// #${message.index} +${message.timeOffsetMs} ms, $size\n${message.bodyText(pretty)}"
        }
    }

    /** Returns the text of one recorded gRPC message, used when copying or saving it. */
    fun getGrpcMessageText(
        message: GrpcEvent,
        pretty: Boolean,
    ): String = message.bodyText(pretty)

    private fun GrpcEvent.bodyText(pretty: Boolean): String {
        val text =
            when {
                body == null -> GRPC_BODY_OMITTED
                pretty && format?.isJson == true && !isTruncated -> FormatUtils.formatJson(body)
                else -> body
            }
        return if (isTruncated) "$text\n$GRPC_BODY_TRUNCATED" else text
    }

    private fun toHttpHeaderList(headers: Headers): List<HttpHeader> {
        val httpHeaders = ArrayList<HttpHeader>()
        for (i in 0 until headers.size) {
            httpHeaders.add(HttpHeader(headers.name(i), headers.value(i)))
        }
        return httpHeaders
    }

    private fun formatBody(
        body: String,
        contentType: String?,
    ): String =
        when {
            contentType.isNullOrBlank() -> body
            contentType.contains("json", ignoreCase = true) -> FormatUtils.formatJson(body)
            contentType.contains("xml", ignoreCase = true) -> FormatUtils.formatXml(body)
            contentType.contains("x-www-form-urlencoded", ignoreCase = true) ->
                FormatUtils.formatUrlEncodedForm(body)
            else -> body
        }

    /**
     * This method creates [android.text.SpannableString] from body
     * and add [ForegroundColorSpan] to text with different colors for better contrast between
     * keys and values and etc in the body.
     *
     * This method just works with json content-type yet, and calls [formatBody]
     * for other content-type until parser function will be developed for other content-types.
     */
    private fun spanBody(
        body: CharSequence,
        contentType: String?,
        context: Context?,
    ): CharSequence =
        when {
            // TODO Implement Other Content Types
            contentType.isNullOrBlank() -> body
            contentType.contains("json", ignoreCase = true) && context != null -> {
                SpanTextUtil(context).spanJson(body)
            }
            else -> formatBody(body.toString(), contentType)
        }

    private fun formatBytes(bytes: Long): String = FormatUtils.formatByteCount(bytes, true)

    fun getFormattedRequestBody(): String =
        if (isGrpc) {
            getGrpcBodyText(isRequest = true, pretty = true)
        } else {
            requestBody?.let { formatBody(it, requestContentType) } ?: ""
        }

    fun getSpannedRequestBody(context: Context?): CharSequence =
        requestBody?.let { spanBody(it, requestContentType, context) }
            ?: SpannableStringBuilder.valueOf("")

    fun getFormattedResponseBody(): String =
        if (isGrpc) {
            getGrpcBodyText(isRequest = false, pretty = true)
        } else {
            responseBody?.let { formatBody(it, responseContentType) } ?: ""
        }

    fun getSpannedResponseBody(context: Context?): CharSequence =
        responseBody?.let {
            spanBody(it, responseContentType, context)
        } ?: SpannableStringBuilder.valueOf("")

    fun populateUrl(httpUrl: HttpUrl): HttpTransaction {
        val formattedUrl = FormattedUrl.fromHttpUrl(httpUrl, encoded = false)
        url = formattedUrl.url
        host = formattedUrl.host
        path = formattedUrl.pathWithQuery
        scheme = formattedUrl.scheme
        return this
    }

    fun getFormattedUrl(encode: Boolean): String {
        // gRPC URLs have no query to encode, and may use a scheme OkHttp can't parse.
        if (isGrpc) return url.orEmpty()
        val httpUrl = url?.toHttpUrl() ?: return ""
        return FormattedUrl.fromHttpUrl(httpUrl, encode).url
    }

    fun getFormattedPath(encode: Boolean): String {
        if (isGrpc) return path.orEmpty()
        val httpUrl = url?.toHttpUrl() ?: return ""
        return FormattedUrl.fromHttpUrl(httpUrl, encode).pathWithQuery
    }

    fun getRequestTotalSize(): Long = (requestHeadersSize ?: 0) + (requestPayloadSize ?: 0)

    fun getResponseTotalSize(): Long = (responseHeadersSize ?: 0) + getHarResponseBodySize()

    fun getHarResponseBodySize(): Long =
        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            0
        } else {
            responsePayloadSize ?: 0
        }

    // Not relying on 'equals' because comparison be long due to request and response sizes
    // and it would be unwise to do this every time 'equals' is called.
    @Suppress("ComplexMethod")
    fun hasTheSameContent(other: HttpTransaction?): Boolean {
        if (this === other) return true
        if (other == null) return false

        return (id == other.id) &&
            (requestDate == other.requestDate) &&
            (responseDate == other.responseDate) &&
            (tookMs == other.tookMs) &&
            (protocol == other.protocol) &&
            (method == other.method) &&
            (url == other.url) &&
            (hostIp == other.hostIp) &&
            (host == other.host) &&
            (path == other.path) &&
            (scheme == other.scheme) &&
            (responseTlsVersion == other.responseTlsVersion) &&
            (responseCipherSuite == other.responseCipherSuite) &&
            (requestPayloadSize == other.requestPayloadSize) &&
            (requestContentType == other.requestContentType) &&
            (requestHeaders == other.requestHeaders) &&
            (requestHeadersSize == other.requestHeadersSize) &&
            (requestBody == other.requestBody) &&
            (isRequestBodyEncoded == other.isRequestBodyEncoded) &&
            (responseCode == other.responseCode) &&
            (responseMessage == other.responseMessage) &&
            (error == other.error) &&
            (responsePayloadSize == other.responsePayloadSize) &&
            (responseContentType == other.responseContentType) &&
            (responseHeaders == other.responseHeaders) &&
            (responseHeadersSize == other.responseHeadersSize) &&
            (responseBody == other.responseBody) &&
            (isResponseBodyEncoded == other.isResponseBodyEncoded) &&
            (responseImageData?.contentEquals(other.responseImageData ?: byteArrayOf()) != false) &&
            (graphQlOperationName == other.graphQlOperationName) &&
            (graphQlDetected == other.graphQlDetected) &&
            (isGrpc == other.isGrpc) &&
            (grpcMethodType == other.grpcMethodType) &&
            (grpcStatusDescription == other.grpcStatusDescription) &&
            (grpcDeadlineMs == other.grpcDeadlineMs) &&
            (responseTrailers == other.responseTrailers) &&
            (requestMessageCount == other.requestMessageCount) &&
            (responseMessageCount == other.responseMessageCount) &&
            (grpcEvents == other.grpcEvents)
    }

    /** Returns a copy of this transaction that can be handed to another thread. */
    fun snapshot(): HttpTransaction =
        HttpTransaction(
            id = id,
            requestDate = requestDate,
            responseDate = responseDate,
            tookMs = tookMs,
            protocol = protocol,
            method = method,
            url = url,
            hostIp = hostIp,
            host = host,
            path = path,
            scheme = scheme,
            responseTlsVersion = responseTlsVersion,
            responseCipherSuite = responseCipherSuite,
            requestPayloadSize = requestPayloadSize,
            requestContentType = requestContentType,
            requestHeaders = requestHeaders,
            requestHeadersSize = requestHeadersSize,
            requestBody = requestBody,
            isRequestBodyEncoded = isRequestBodyEncoded,
            responseCode = responseCode,
            responseMessage = responseMessage,
            error = error,
            responsePayloadSize = responsePayloadSize,
            responseContentType = responseContentType,
            responseHeaders = responseHeaders,
            responseHeadersSize = responseHeadersSize,
            responseBody = responseBody,
            isResponseBodyEncoded = isResponseBodyEncoded,
            responseImageData = responseImageData,
            graphQlDetected = graphQlDetected,
            graphQlOperationName = graphQlOperationName,
            isGrpc = isGrpc,
            grpcMethodType = grpcMethodType,
            grpcStatusDescription = grpcStatusDescription,
            grpcDeadlineMs = grpcDeadlineMs,
            responseTrailers = responseTrailers,
            requestMessageCount = requestMessageCount,
            responseMessageCount = responseMessageCount,
            grpcEvents = grpcEvents,
        )

    private companion object {
        const val GRPC_BODY_OMITTED = "(not recorded, content limit reached)"
        const val GRPC_BODY_TRUNCATED = "--- Content truncated ---"
    }
}
