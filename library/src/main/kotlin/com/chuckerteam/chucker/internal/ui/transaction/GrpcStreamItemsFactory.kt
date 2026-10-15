package com.chuckerteam.chucker.internal.ui.transaction

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.GrpcStatusCodes
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.text.DateFormat

/**
 * Turns a streaming gRPC call into the rows of its screen: a summary of the call, then every event
 * in the order it happened.
 */
internal class GrpcStreamItemsFactory(
    private val context: Context,
) {
    /**
     * @param isLiveStream whether the stream is still open in this process.
     * @param expandedKeys the keys of the rows the user expanded.
     * @param nowMs the current time, used for how long an open stream has been running.
     */
    fun create(
        transaction: HttpTransaction,
        isLiveStream: Boolean,
        expandedKeys: Set<String>,
        nowMs: Long,
    ): List<StreamItem> {
        // A stream with a status is over, even if its recorder hasn't let go of it yet.
        val isLive = isLiveStream && transaction.responseCode == null
        val statusColor = statusColor(transaction, isLive)
        return buildList {
            add(StreamItem.Summary(summary(transaction, isLive, statusColor, expandedKeys, nowMs)))
            transaction.getGrpcEvents().forEachIndexed { position, event ->
                add(StreamItem.Event(eventRow(position, event, transaction, statusColor, expandedKeys)))
            }
            if (isLive) add(StreamItem.Waiting(GrpcPresentation.formatOffset(elapsedMs(transaction, nowMs))))
        }
    }

    private fun summary(
        transaction: HttpTransaction,
        isLive: Boolean,
        @ColorInt statusColor: Int,
        expandedKeys: Set<String>,
        nowMs: Long,
    ): StreamSummary {
        val code = transaction.responseCode
        val status =
            when {
                isLive -> context.getString(R.string.chucker_live)
                code != null -> "$code ${GrpcStatusCodes.name(code)}"
                else -> context.getString(R.string.chucker_grpc_no_status)
            }
        val duration =
            when {
                isLive -> GrpcPresentation.formatOffset(elapsedMs(transaction, nowMs))
                else -> transaction.tookMs?.let(GrpcPresentation::formatOffset) ?: NO_VALUE
            }
        val durationLabel = if (isLive) R.string.chucker_grpc_open_for else R.string.chucker_duration
        val deadline =
            transaction.grpcDeadlineMs?.let {
                context.getString(R.string.chucker_grpc_deadline_value, GrpcPresentation.formatOffset(it))
            } ?: context.getString(R.string.chucker_grpc_no_deadline_summary)
        val started =
            transaction.requestDate?.let {
                context.getString(R.string.chucker_grpc_started_at, DateFormat.getTimeInstance().format(it))
            }
        return StreamSummary(
            status = status,
            statusColor = statusColor,
            isLive = isLive,
            description = transaction.grpcStatusDescription?.takeIf { code != null },
            callType = context.getString(GrpcPresentation.callTypeLabel(transaction.grpcMethodType)),
            durationLabel = context.getString(durationLabel),
            duration = duration,
            sentCount = transaction.requestMessageCount,
            sentSize = GrpcPresentation.formatSize(transaction.requestPayloadSize),
            receivedCount = transaction.responseMessageCount,
            receivedSize = GrpcPresentation.formatSize(transaction.responsePayloadSize),
            startedAndDeadline = listOfNotNull(started, deadline).joinToString(" · "),
            isExpanded = GrpcStreamAdapter.KEY_DETAILS in expandedKeys,
            details = details(transaction),
            error = transaction.error,
        )
    }

    private fun details(transaction: HttpTransaction): List<Pair<String, String>> =
        listOf(
            R.string.chucker_url to transaction.getFormattedUrl(encode = false),
            R.string.chucker_method to transaction.grpcFullMethodName,
            R.string.chucker_protocol to transaction.protocol,
            R.string.chucker_host_ip to transaction.hostIp,
            R.string.chucker_tls_version to transaction.responseTlsVersion,
            R.string.chucker_tls_cipher_suite to transaction.responseCipherSuite,
            R.string.chucker_request_time to transaction.requestDateString,
            R.string.chucker_response_time to transaction.responseDateString,
            R.string.chucker_total_size to transaction.totalSizeString,
        ).mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { context.getString(label) to it } }

    @Suppress("LongMethod")
    private fun eventRow(
        position: Int,
        event: GrpcEvent,
        transaction: HttpTransaction,
        @ColorInt statusColor: Int,
        expandedKeys: Set<String>,
    ): StreamEventRow {
        val neutral = color(R.color.chucker_status_requested)
        val row =
            StreamEventRow(
                position = position,
                event = event,
                title = "",
                titleColor = null,
                icon = R.drawable.chucker_ic_stream_event,
                iconColor = neutral,
                subtitle = null,
                isSubtitleCode = false,
                keepsSubtitleWhenExpanded = false,
                detailsLabel = null,
                headers = emptyList(),
                isExpanded = GrpcStreamAdapter.eventKey(position) in expandedKeys,
            )
        val size = GrpcPresentation.formatSize(event.size)
        return when (event.type) {
            GrpcEvent.Type.STARTED -> {
                val metadata = transaction.getParsedRequestHeaders().orEmpty()
                row.copy(
                    title = context.getString(R.string.chucker_grpc_event_started),
                    subtitle = countOrNull(R.string.chucker_grpc_metadata_count, metadata.size),
                    headers = metadata,
                )
            }
            GrpcEvent.Type.HEADERS_RECEIVED -> {
                val headers = transaction.getParsedResponseHeaders().orEmpty()
                row.copy(
                    title = context.getString(R.string.chucker_grpc_event_headers_received),
                    subtitle = countOrNull(R.string.chucker_grpc_headers_count, headers.size),
                    headers = headers,
                )
            }
            GrpcEvent.Type.MESSAGE_SENT ->
                messageRow(
                    row,
                    context.getString(R.string.chucker_grpc_event_message_sent, event.index ?: 0, size),
                    R.drawable.chucker_ic_stream_sent,
                    color(R.color.chucker_color_primary),
                )
            GrpcEvent.Type.MESSAGE_RECEIVED ->
                messageRow(
                    row,
                    context.getString(R.string.chucker_grpc_event_message_received, event.index ?: 0, size),
                    R.drawable.chucker_ic_stream_received,
                    color(R.color.chucker_status_live),
                )
            GrpcEvent.Type.HALF_CLOSED ->
                row.copy(
                    title = context.getString(R.string.chucker_grpc_event_half_closed),
                    icon = R.drawable.chucker_ic_stream_half_closed,
                )
            GrpcEvent.Type.CANCELLED ->
                row.copy(
                    title = context.getString(R.string.chucker_grpc_event_cancelled),
                    icon = R.drawable.chucker_ic_stream_cancelled,
                    iconColor = color(R.color.chucker_status_400),
                    subtitle = event.detail,
                    keepsSubtitleWhenExpanded = true,
                )
            GrpcEvent.Type.CLOSED -> closedRow(row, transaction, statusColor)
        }
    }

    private fun messageRow(
        row: StreamEventRow,
        title: String,
        icon: Int,
        @ColorInt iconColor: Int,
    ): StreamEventRow =
        row.copy(
            title = title,
            icon = icon,
            iconColor = iconColor,
            subtitle = row.event.body ?: context.getString(R.string.chucker_grpc_message_omitted),
            isSubtitleCode = row.event.body != null,
        )

    private fun closedRow(
        row: StreamEventRow,
        transaction: HttpTransaction,
        @ColorInt statusColor: Int,
    ): StreamEventRow {
        val code = transaction.responseCode
        val closed = context.getString(R.string.chucker_grpc_event_closed)
        return row.copy(
            title = if (code != null) "$closed · $code ${GrpcStatusCodes.name(code)}" else closed,
            titleColor = statusColor.takeIf { code != null && code != GrpcStatusCodes.OK },
            icon =
                if (code == GrpcStatusCodes.OK) {
                    R.drawable.chucker_ic_stream_closed
                } else {
                    R.drawable.chucker_ic_stream_cancelled
                },
            iconColor = statusColor,
            subtitle = transaction.grpcStatusDescription,
            keepsSubtitleWhenExpanded = true,
            detailsLabel = context.getString(R.string.chucker_grpc_trailers),
            headers = transaction.getParsedResponseTrailers().orEmpty(),
        )
    }

    @ColorInt
    private fun statusColor(
        transaction: HttpTransaction,
        isLive: Boolean,
    ): Int {
        val code = transaction.responseCode
        val colorRes =
            when {
                isLive -> R.color.chucker_status_live
                code == null -> R.color.chucker_status_requested
                code == GrpcStatusCodes.OK -> R.color.chucker_status_live
                GrpcStatusCodes.httpEquivalent(code) >= HTTP_INTERNAL_ERROR -> R.color.chucker_status_500
                else -> R.color.chucker_status_400
            }
        return color(colorRes)
    }

    private fun countOrNull(
        stringRes: Int,
        count: Int,
    ): String? = if (count > 0) context.getString(stringRes, count) else null

    @ColorInt
    private fun color(colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    private fun elapsedMs(
        transaction: HttpTransaction,
        nowMs: Long,
    ): Long = (nowMs - (transaction.requestDate ?: return 0)).coerceAtLeast(0)

    private companion object {
        const val NO_VALUE = "—"
    }
}
