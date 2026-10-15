package com.chuckerteam.chucker.internal.support

import android.content.Context
import com.chuckerteam.chucker.R
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import okio.Buffer
import okio.Source
import java.net.URI

/**
 * Shares a gRPC call as a [grpcurl](https://github.com/fullstorydev/grpcurl) command that replays
 * it. grpcurl needs the server to support reflection, or the `.proto` files passed with `-proto`.
 */
internal class TransactionGrpcurlCommandSharable(
    private val transaction: HttpTransaction,
) : Sharable {
    override fun toSharableContent(context: Context): Source =
        Buffer().apply {
            val messages = transaction.getGrpcMessages(isRequest = true)
            // grpcurl needs proto3 JSON, which raw decoding, text and truncated messages are not.
            val jsonMessages = messages.filter { it.format in REPLAYABLE_FORMATS && !it.isTruncated }
            if (jsonMessages.size < transaction.requestMessageCount) {
                writeUtf8("# ${context.getString(R.string.chucker_grpcurl_incomplete_body)}\n")
            }

            writeUtf8("grpcurl")
            if (transaction.scheme.equals("http", ignoreCase = true)) writeUtf8(" -plaintext")
            transaction.getParsedRequestHeaders()?.forEach { header ->
                writeUtf8(" -H ${quote("${header.name}: ${header.value}")}")
            }
            if (jsonMessages.isNotEmpty()) {
                writeUtf8(" -d ${quote(jsonMessages.joinToString("\n") { it.body.orEmpty() })}")
            }
            writeUtf8(" ${quote(authorityWithPort())} ${quote(transaction.grpcFullMethodName)}")
        }

    // grpcurl requires a port, which an authority may leave out.
    private fun authorityWithPort(): String {
        val authority =
            transaction.url
                .orEmpty()
                .substringAfter("://")
                .removeSuffix(transaction.path.orEmpty())
        val port = runCatching { URI("//$authority").port }.getOrDefault(-1)
        if (port != -1) return authority
        val defaultPort = if (transaction.scheme.equals("http", ignoreCase = true)) HTTP_PORT else HTTPS_PORT
        return "$authority:$defaultPort"
    }

    // Single quotes keep everything literal in POSIX shells, except single quotes themselves.
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val REPLAYABLE_FORMATS = setOf(BodyFormat.SCHEMA_JSON, BodyFormat.CUSTOM_JSON)
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
    }
}
