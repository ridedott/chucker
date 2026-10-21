package com.chuckerteam.chucker.internal.grpc

import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.internal.data.entity.HttpHeader
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.grpc.format.GrpcMessageRenderer
import com.chuckerteam.chucker.internal.grpc.format.RenderedMessage
import com.chuckerteam.chucker.internal.support.GrpcStatusCodes
import com.chuckerteam.chucker.internal.support.JsonConverter
import com.chuckerteam.chucker.internal.support.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Records a single gRPC call into an [HttpTransaction].
 *
 * The `on*` methods may be called from any thread: they only queue what happened. A single
 * coroutine applies the queued inputs to the transaction, renders the messages and persists the
 * result, so writes for a call are always ordered and expensive work stays off the call's threads.
 * While the call is active, writes are throttled to one every [WRITE_INTERVAL_MS], or every
 * [CAPPED_WRITE_INTERVAL_MS] once a long stream stops recording messages and only counts them.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class GrpcCallRecorder(
    private val transaction: HttpTransaction,
    private val authority: String,
    private val requestCodec: GrpcMessageCodec,
    private val responseCodec: GrpcMessageCodec,
    private val renderer: GrpcMessageRenderer,
    maxContentLength: Long,
    private val sink: GrpcTransactionSink,
    scope: CoroutineScope,
) {
    private val activeStreams = GrpcActiveStreams
    private val isStreaming = transaction.grpcMethodType != UNARY
    private val startNanos = System.nanoTime()
    private val inputs = ConcurrentLinkedQueue<Input>()
    private val signals = Channel<Unit>(Channel.CONFLATED)

    // Everything below is only accessed by the persisting coroutine.
    private val events = mutableListOf(GrpcEvent(GrpcEvent.Type.STARTED, timeOffsetMs = 0))
    private val requestBodyBudget = BodyBudget(maxContentLength)
    private val responseBodyBudget = BodyBudget(maxContentLength)
    private var recordedMessageCount = 0
    private var isClosed = false

    init {
        transaction.requestPayloadSize = 0
        transaction.grpcEvents = JsonConverter.compactInstance.toJson(events)
        scope.launch { persistChanges() }
        signals.trySend(Unit)
    }

    fun onMessageSent(message: Any) = record(Input.Message(isRequest = true, message, elapsedMs()))

    fun onHalfClose() = record(Input.HalfClose(elapsedMs()))

    fun onCancel(
        message: String?,
        cause: Throwable?,
    ) = record(Input.Cancel(elapsedMs(), message, cause))

    fun onHeaders(
        headers: List<HttpHeader>,
        connection: GrpcConnectionInfo?,
    ) = record(Input.Headers(elapsedMs(), headers, connection))

    fun onMessageReceived(message: Any) = record(Input.Message(isRequest = false, message, elapsedMs()))

    fun onClose(
        statusCode: Int,
        description: String?,
        cause: Throwable?,
        trailers: List<HttpHeader>,
        connection: GrpcConnectionInfo?,
    ) = record(Input.Close(elapsedMs(), statusCode, description, cause, trailers, connection))

    private fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun record(input: Input) {
        inputs.add(input)
        signals.trySend(Unit)
        // Nothing can happen after the call is closed, so the persisting coroutine can finish.
        if (input is Input.Close) signals.close()
    }

    private suspend fun persistChanges() {
        var isInserted = false
        var isActive = false
        try {
            signals.consumeEach {
                try {
                    applyInputs()
                    if (isInserted) {
                        sink.update(transaction.snapshot(), isFinal = isClosed)
                    } else {
                        transaction.id = sink.insert(transaction.snapshot())
                        isInserted = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.error("Failed to save gRPC call ${transaction.grpcFullMethodName}", e)
                }
                if (isInserted && !isClosed && !isActive && isStreaming) {
                    activeStreams.add(transaction.id)
                    isActive = true
                }
                if (!isClosed) delay(writeInterval())
            }
        } finally {
            // Only once the final status is saved, so the stream never looks open without a status.
            if (isActive) activeStreams.remove(transaction.id)
        }
    }

    private fun writeInterval(): Long {
        val isRecordingMessages = recordedMessageCount < MAX_RECORDED_MESSAGES
        return if (isRecordingMessages) WRITE_INTERVAL_MS else CAPPED_WRITE_INTERVAL_MS
    }

    private fun applyInputs() {
        val eventCount = events.size
        while (true) {
            when (val input = inputs.poll() ?: break) {
                is Input.Message -> applyMessage(input)
                is Input.HalfClose -> events += GrpcEvent(GrpcEvent.Type.HALF_CLOSED, input.timeMs)
                is Input.Cancel -> applyCancel(input)
                is Input.Headers -> applyHeaders(input)
                is Input.Close -> applyClose(input)
            }
        }
        // The events of a long stream are large, so they're only serialized again when they change.
        if (events.size != eventCount) transaction.grpcEvents = JsonConverter.compactInstance.toJson(events)
    }

    private fun applyMessage(input: Input.Message) {
        val codec = if (input.isRequest) requestCodec else responseCodec
        val size = runCatching { codec.size(input.message) }.getOrNull()
        val index =
            if (input.isRequest) {
                transaction.requestPayloadSize = (transaction.requestPayloadSize ?: 0) + (size ?: 0)
                ++transaction.requestMessageCount
            } else {
                transaction.responsePayloadSize = (transaction.responsePayloadSize ?: 0) + (size ?: 0)
                ++transaction.responseMessageCount
            }
        // Long-lived streams keep counting messages, but stop recording them past the limit.
        if (recordedMessageCount >= MAX_RECORDED_MESSAGES) return
        recordedMessageCount++

        val type = if (input.isRequest) GrpcEvent.Type.MESSAGE_SENT else GrpcEvent.Type.MESSAGE_RECEIVED
        val budget = if (input.isRequest) requestBodyBudget else responseBodyBudget
        if (budget.isExhausted) {
            events += GrpcEvent(type, input.timeMs, index, size, format = BodyFormat.OMITTED)
            return
        }
        val rendered = render(input.message, size, codec)
        val body = budget.take(rendered.text)
        events +=
            GrpcEvent(
                type = type,
                timeOffsetMs = input.timeMs,
                index = index,
                size = size,
                body = body,
                format = rendered.format,
                isTruncated = body.length < rendered.text.length,
            )
    }

    private fun render(
        message: Any,
        size: Long?,
        codec: GrpcMessageCodec,
    ): RenderedMessage =
        try {
            renderer.render(transaction.grpcFullMethodName, message, size) { codec.serialize(message) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Logger.error("Failed to render a message of ${transaction.grpcFullMethodName}", e)
            RenderedMessage(e.toString(), BodyFormat.TEXT)
        }

    private fun applyCancel(input: Input.Cancel) {
        val detail = listOfNotNull(input.message, input.cause?.toString()).joinToString(": ").ifEmpty { null }
        events += GrpcEvent(GrpcEvent.Type.CANCELLED, input.timeMs, detail = detail)
    }

    private fun applyHeaders(input: Input.Headers) {
        transaction.responseDate = (transaction.requestDate ?: 0) + input.timeMs
        transaction.setResponseHeaders(input.headers)
        transaction.responseHeadersSize = input.headers.byteCount()
        transaction.responseContentType =
            input.headers.firstOrNull { it.name.equals(CONTENT_TYPE, ignoreCase = true) }?.value
        applyConnection(input.connection)
        events += GrpcEvent(GrpcEvent.Type.HEADERS_RECEIVED, input.timeMs)
    }

    private fun applyClose(input: Input.Close) {
        val statusName = GrpcStatusCodes.name(input.statusCode)
        transaction.apply {
            if (responseDate == null) responseDate = (requestDate ?: 0) + input.timeMs
            tookMs = input.timeMs
            responseCode = input.statusCode
            responseMessage = statusName
            grpcStatusDescription = input.description
            error = input.cause?.toString()
            setResponseTrailers(input.trailers)
            if (responsePayloadSize == null) responsePayloadSize = 0
        }
        applyConnection(input.connection)
        val detail = listOfNotNull(statusName, input.description).joinToString(": ")
        events += GrpcEvent(GrpcEvent.Type.CLOSED, input.timeMs, detail = detail)
        isClosed = true
    }

    private fun applyConnection(connection: GrpcConnectionInfo?) {
        if (connection == null) return
        transaction.apply {
            connection.scheme?.let {
                scheme = it
                url = "$it://$authority$path"
            }
            connection.tlsVersion?.let { responseTlsVersion = it }
            connection.cipherSuite?.let { responseCipherSuite = it }
            connection.remoteAddress?.let { hostIp = it }
        }
    }

    private class BodyBudget(
        private var remaining: Long,
    ) {
        val isExhausted: Boolean get() = remaining <= 0

        /** Returns as much of [text] as the budget allows, and deducts it. */
        fun take(text: String): String {
            val taken = if (text.length > remaining) text.take(remaining.toInt()) else text
            remaining -= taken.length
            return taken
        }
    }

    private sealed class Input {
        class Message(
            val isRequest: Boolean,
            val message: Any,
            val timeMs: Long,
        ) : Input()

        class HalfClose(
            val timeMs: Long,
        ) : Input()

        class Cancel(
            val timeMs: Long,
            val message: String?,
            val cause: Throwable?,
        ) : Input()

        class Headers(
            val timeMs: Long,
            val headers: List<HttpHeader>,
            val connection: GrpcConnectionInfo?,
        ) : Input()

        @Suppress("LongParameterList")
        class Close(
            val timeMs: Long,
            val statusCode: Int,
            val description: String?,
            val cause: Throwable?,
            val trailers: List<HttpHeader>,
            val connection: GrpcConnectionInfo?,
        ) : Input()
    }

    internal companion object {
        const val WRITE_INTERVAL_MS = 250L
        const val CAPPED_WRITE_INTERVAL_MS = 2_000L
        const val MAX_RECORDED_MESSAGES = 1_000
        private const val CONTENT_TYPE = "content-type"
        private const val UNARY = "UNARY"

        fun List<HttpHeader>.byteCount(): Long = sumOf { it.name.length + it.value.length }.toLong()
    }
}
