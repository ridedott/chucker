package com.chuckerteam.chucker.internal.support

import android.content.Context
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.ui.transaction.GrpcPresentation
import okio.Buffer
import okio.Source

internal class TransactionDetailsSharable(
    private val transaction: HttpTransaction,
    private val encodeUrls: Boolean,
) : Sharable {
    @Suppress("LongMethod")
    override fun toSharableContent(context: Context): Source =
        Buffer().apply {
            writeUtf8("${context.getString(R.string.chucker_url)}: ${transaction.getFormattedUrl(encodeUrls)}\n")
            writeUtf8("${context.getString(R.string.chucker_host_ip)}: ${transaction.hostIp}\n")
            val method = if (transaction.isGrpc) transaction.grpcFullMethodName else transaction.method
            writeUtf8("${context.getString(R.string.chucker_method)}: $method\n")
            writeUtf8("${context.getString(R.string.chucker_protocol)}: ${transaction.protocol}\n")
            writeUtf8("${context.getString(R.string.chucker_status)}: ${transaction.status}\n")
            writeUtf8("${context.getString(R.string.chucker_response)}: ${transaction.responseSummaryText}\n")
            if (transaction.isGrpc) writeGrpcDetails(context)
            val isSsl = if (transaction.isSsl) R.string.chucker_yes else R.string.chucker_no
            writeUtf8("${context.getString(R.string.chucker_ssl)}: ${context.getString(isSsl)}\n")
            writeUtf8("\n")
            writeUtf8("${context.getString(R.string.chucker_request_time)}: ${transaction.requestDateString}\n")
            writeUtf8("${context.getString(R.string.chucker_response_time)}: ${transaction.responseDateString}\n")
            writeUtf8("${context.getString(R.string.chucker_duration)}: ${transaction.durationString}\n")
            writeUtf8("\n")
            writeUtf8("${context.getString(R.string.chucker_request_size)}: ${transaction.requestSizeString}\n")
            writeUtf8("${context.getString(R.string.chucker_response_size)}: ${transaction.responseSizeString}\n")
            writeUtf8("${context.getString(R.string.chucker_total_size)}: ${transaction.totalSizeString}\n")
            writeUtf8("\n")
            writeUtf8("---------- ${context.getString(R.string.chucker_request)} ----------\n\n")
            var headers = FormatUtils.formatHeaders(transaction.getParsedRequestHeaders(), false)
            if (headers.isNotBlank()) {
                writeUtf8(headers)
                writeUtf8("\n")
            }
            writeUtf8(
                if (transaction.getFormattedRequestBody().isBlank()) {
                    val resId =
                        if (transaction.isResponseBodyEncoded) {
                            R.string.chucker_body_omitted
                        } else {
                            R.string.chucker_body_empty
                        }
                    context.getString(resId)
                } else {
                    transaction.getFormattedRequestBody()
                },
            )
            writeUtf8("\n\n")
            writeUtf8("---------- ${context.getString(R.string.chucker_response)} ----------\n\n")
            headers = FormatUtils.formatHeaders(transaction.getParsedResponseHeaders(), false)

            if (headers.isNotBlank()) {
                writeUtf8(headers)
                writeUtf8("\n")
            }

            writeUtf8(
                if (transaction.getFormattedResponseBody().isBlank()) {
                    val resId =
                        if (transaction.isResponseBodyEncoded) {
                            R.string.chucker_body_omitted
                        } else {
                            R.string.chucker_body_empty
                        }
                    context.getString(resId)
                } else {
                    transaction.getFormattedResponseBody()
                },
            )
            val trailers = FormatUtils.formatHeaders(transaction.getParsedResponseTrailers(), false)
            if (trailers.isNotBlank()) {
                writeUtf8("\n\n---------- ${context.getString(R.string.chucker_grpc_trailers)} ----------\n\n")
                writeUtf8(trailers)
            }
        }

    private fun Buffer.writeGrpcDetails(context: Context) {
        val callType = context.getString(GrpcPresentation.callTypeLabel(transaction.grpcMethodType))
        writeUtf8("${context.getString(R.string.chucker_grpc_call_type)}: $callType\n")
        transaction.grpcStatusDescription?.let {
            writeUtf8("${context.getString(R.string.chucker_grpc_status_description)}: $it\n")
        }
        val messages =
            context.getString(
                R.string.chucker_grpc_messages_value,
                transaction.requestMessageCount,
                transaction.responseMessageCount,
            )
        writeUtf8("${context.getString(R.string.chucker_grpc_messages)}: $messages\n")
        transaction.error?.let { writeUtf8("${context.getString(R.string.chucker_error)}: $it\n") }
    }
}
