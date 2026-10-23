package com.chuckerteam.chucker.api

import android.content.Context
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.internal.data.entity.HttpHeader
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.grpc.GrpcActiveStreams
import com.chuckerteam.chucker.internal.grpc.GrpcCallRecorder
import com.chuckerteam.chucker.internal.grpc.GrpcTransactionSink
import com.chuckerteam.chucker.util.NoLoggerRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.Int32Value
import com.google.protobuf.StringValue
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ForwardingServerCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@ExtendWith(NoLoggerRule::class)
internal class ChuckerGrpcInterceptorTest {
    private val sink = RecordingSink()
    private val serverName = InProcessServerBuilder.generateName()
    private val server: Server =
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(echoService(), MetadataServerInterceptor))
            .build()
            .start()
    private val channels = mutableListOf<ManagedChannel>()

    @AfterEach
    fun tearDown() {
        channels.forEach { it.shutdownNow() }
        server.shutdownNow()
    }

    @Test
    fun `a unary call is recorded with its metadata, messages and status`() {
        val response = unary(channel(), "hello")

        val transaction = sink.awaitClosedCall()
        assertThat(response.value).isEqualTo("HELLO")
        assertThat(transaction.isGrpc).isTrue()
        assertThat(transaction.method).isEqualTo("POST")
        assertThat(transaction.path).isEqualTo("/test.Echo/Unary")
        assertThat(transaction.grpcMethodType).isEqualTo("UNARY")
        assertThat(transaction.responseCode).isEqualTo(Status.Code.OK.value())
        assertThat(transaction.responseMessage).isEqualTo("OK")
        assertThat(transaction.error).isNull()
        assertThat(transaction.getParsedResponseHeaders()).contains(HttpHeader("x-server", "test"))
        assertThat(transaction.getParsedResponseTrailers()).contains(HttpHeader("x-trailer", "done"))
        assertThat(transaction.requestMessageCount).isEqualTo(1)
        assertThat(transaction.responseMessageCount).isEqualTo(1)
        assertThat(transaction.requestPayloadSize).isEqualTo(StringValue.of("hello").serializedSize.toLong())
        assertThat(transaction.getGrpcMessages(isRequest = true).single().body).isEqualTo("\"hello\"")
        assertThat(transaction.getGrpcMessages(isRequest = false).single().format).isEqualTo(BodyFormat.SCHEMA_JSON)
        assertThat(transaction.getGrpcEvents().map { it.type })
            .containsExactly(
                GrpcEvent.Type.STARTED,
                GrpcEvent.Type.MESSAGE_SENT,
                GrpcEvent.Type.HALF_CLOSED,
                GrpcEvent.Type.HEADERS_RECEIVED,
                GrpcEvent.Type.MESSAGE_RECEIVED,
                GrpcEvent.Type.CLOSED,
            ).inOrder()
    }

    @Test
    fun `a failed call is recorded with its status and trailers`() {
        assertThrows<StatusRuntimeException> { unary(channel(), FAIL) }

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.responseCode).isEqualTo(Status.Code.NOT_FOUND.value())
        assertThat(transaction.responseMessage).isEqualTo("NOT_FOUND")
        assertThat(transaction.grpcStatusDescription).isEqualTo("Nothing to echo")
        assertThat(transaction.getParsedResponseTrailers()).contains(HttpHeader("x-debug", "echo-1"))
        assertThat(transaction.responseMessageCount).isEqualTo(0)
    }

    @Test
    fun `a call past its deadline is recorded with its deadline`() {
        val options = CallOptions.DEFAULT.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)

        assertThrows<StatusRuntimeException> { unary(channel(), SLOW, options) }

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.responseMessage).isEqualTo("DEADLINE_EXCEEDED")
        assertThat(transaction.grpcDeadlineMs).isAtMost(DEADLINE_MS)
    }

    @Test
    fun `every message of a server stream is recorded`() {
        ClientCalls
            .blockingServerStreamingCall(channel(), serverStream, CallOptions.DEFAULT, StringValue.of("tick"))
            .forEach { _ -> }

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.grpcMethodType).isEqualTo("SERVER_STREAMING")
        assertThat(transaction.responseMessageCount).isEqualTo(STREAM_SIZE)
        assertThat(transaction.getGrpcMessages(isRequest = false).map { it.index }).containsExactly(1, 2, 3).inOrder()
        assertThat(transaction.getGrpcMessages(isRequest = false).map { it.body })
            .containsExactly("\"tick-1\"", "\"tick-2\"", "\"tick-3\"")
            .inOrder()
    }

    @Test
    fun `every message of a client stream is recorded`() {
        val done = CountDownLatch(1)
        val requests =
            ClientCalls.asyncClientStreamingCall(
                channel().newCall(clientStream, CallOptions.DEFAULT),
                latchObserver<Int32Value>(done),
            )
        repeat(STREAM_SIZE) { requests.onNext(StringValue.of("item-$it")) }
        requests.onCompleted()
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.requestMessageCount).isEqualTo(STREAM_SIZE)
        assertThat(transaction.getGrpcMessages(isRequest = false).single().body).isEqualTo("3")
    }

    @Test
    fun `a long stream keeps counting messages past the ones it records`() {
        val done = CountDownLatch(1)
        val requests =
            ClientCalls.asyncClientStreamingCall(
                channel().newCall(clientStream, CallOptions.DEFAULT),
                latchObserver<Int32Value>(done),
            )
        val messageCount = GrpcCallRecorder.MAX_RECORDED_MESSAGES + 5
        repeat(messageCount) { requests.onNext(StringValue.of("item-$it")) }
        requests.onCompleted()
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.requestMessageCount).isEqualTo(messageCount)
        assertThat(transaction.responseMessageCount).isEqualTo(1)
        assertThat(transaction.getGrpcEvents().count { it.isMessage }).isEqualTo(GrpcCallRecorder.MAX_RECORDED_MESSAGES)
    }

    @Test
    fun `only the update that closes a stream is final`() {
        val done = CountDownLatch(1)
        val requests =
            ClientCalls.asyncBidiStreamingCall(
                channel().newCall(bidiStream, CallOptions.DEFAULT),
                latchObserver<StringValue>(done),
            )
        awaitCondition { sink.inserted.isNotEmpty() }
        requests.onNext(StringValue.of("ping"))
        requests.onCompleted()
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        sink.awaitClosedCall()
        assertThat(sink.finalUpdates.map { it.responseCode }).containsExactly(Status.Code.OK.value())
    }

    @Test
    fun `a bidirectional stream keeps the order of the messages`() {
        val done = CountDownLatch(1)
        val requests =
            ClientCalls.asyncBidiStreamingCall(
                channel().newCall(bidiStream, CallOptions.DEFAULT),
                latchObserver<StringValue>(done),
            )
        requests.onNext(StringValue.of("ping"))
        requests.onNext(StringValue.of("pong"))
        requests.onCompleted()
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val transaction = sink.awaitClosedCall()
        val messages = transaction.getGrpcEvents().filter { it.isMessage }.map { it.type to it.body }
        assertThat(messages)
            .containsExactly(
                GrpcEvent.Type.MESSAGE_SENT to "\"ping\"",
                GrpcEvent.Type.MESSAGE_RECEIVED to "\"PING\"",
                GrpcEvent.Type.MESSAGE_SENT to "\"pong\"",
                GrpcEvent.Type.MESSAGE_RECEIVED to "\"PONG\"",
            ).inOrder()
    }

    @Test
    fun `a stream is active while it is open`() {
        val done = CountDownLatch(1)
        val requests =
            ClientCalls.asyncBidiStreamingCall(
                channel().newCall(bidiStream, CallOptions.DEFAULT),
                latchObserver<StringValue>(done),
            )
        requests.onNext(StringValue.of("ping"))
        awaitCondition { GrpcActiveStreams.ids.value.isNotEmpty() }
        val activeIds = GrpcActiveStreams.ids.value

        requests.onCompleted()
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val transaction = sink.awaitClosedCall()
        assertThat(activeIds).contains(transaction.id)
        awaitCondition { transaction.id !in GrpcActiveStreams.ids.value }
    }

    @Test
    fun `a unary call is never active`() {
        unary(channel(), "hello")
        val transaction = sink.awaitClosedCall()

        assertThat(GrpcActiveStreams.ids.value).doesNotContain(transaction.id)
    }

    @Test
    fun `a call cancelled by the client is recorded`() {
        val call = channel().newCall(serverStream, CallOptions.DEFAULT)
        val done = CountDownLatch(1)
        ClientCalls.asyncServerStreamingCall(
            call,
            StringValue.of("tick"),
            object : StreamObserver<StringValue> {
                override fun onNext(value: StringValue) = call.cancel("The user left", null)

                override fun onError(t: Throwable) = done.countDown()

                override fun onCompleted() = done.countDown()
            },
        )
        done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val transaction = sink.awaitClosedCall()
        assertThat(transaction.responseMessage).isEqualTo("CANCELLED")
        val cancelled = transaction.getGrpcEvents().single { it.type == GrpcEvent.Type.CANCELLED }
        assertThat(cancelled.detail).isEqualTo("The user left")
    }

    @Test
    fun `metadata is redacted and binary metadata is shown as base64`() {
        val metadata =
            Metadata().apply {
                put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer secret")
                put(Metadata.Key.of("trace-bin", Metadata.BINARY_BYTE_MARSHALLER), byteArrayOf(1, 2, 3))
            }
        val channel = ClientInterceptors.intercept(channel(), MetadataUtils.newAttachHeadersInterceptor(metadata))

        unary(channel, "hello")

        val headers = sink.awaitClosedCall().getParsedRequestHeaders()
        assertThat(headers).contains(HttpHeader("authorization", "**"))
        assertThat(headers).contains(HttpHeader("trace-bin", "AQID"))
    }

    @Test
    fun `skipped methods and services are not recorded`() {
        val skippingMethod = channel { skipMethods("test.Echo/Unary") }
        val skippingService = channel { skipServices("test.Echo") }
        val skippingRegex = channel { skipMethods(Regex("test\\.Echo/Un.*")) }

        listOf(skippingMethod, skippingService, skippingRegex).forEach { unary(it, "hello") }
        ClientCalls
            .blockingServerStreamingCall(skippingMethod, serverStream, CallOptions.DEFAULT, StringValue.of("tick"))
            .forEach { _ -> }

        assertThat(sink.awaitClosedCall().path).isEqualTo("/test.Echo/ServerStream")
        assertThat(sink.inserted).hasSize(1)
    }

    @Test
    fun `messages past the content limit are truncated, then left out`() {
        // Each message is rendered as "tick-N", 8 characters with the quotes.
        val channel = channel { maxContentLength(12) }

        ClientCalls
            .blockingServerStreamingCall(channel, serverStream, CallOptions.DEFAULT, StringValue.of("tick"))
            .forEach { _ -> }

        val messages = sink.awaitClosedCall().getGrpcMessages(isRequest = false)
        assertThat(messages[0].body).isEqualTo("\"tick-1\"")
        assertThat(messages[0].isTruncated).isFalse()
        assertThat(messages[1].body).isEqualTo("\"tic")
        assertThat(messages[1].isTruncated).isTrue()
        assertThat(messages[2].body).isNull()
        assertThat(messages[2].format).isEqualTo(BodyFormat.OMITTED)
    }

    @Test
    fun `calls keep working when recording fails`() {
        val failingChannel =
            channel {
                sink(FailingSink)
                addMessageFormatter { _, _ -> error("Formatter failure") }
            }

        assertThat(unary(failingChannel, "hello").value).isEqualTo("HELLO")
    }

    private fun channel(configure: ChuckerGrpcInterceptor.Builder.() -> Unit = {}): ManagedChannel {
        val interceptor =
            ChuckerGrpcInterceptor
                .Builder(mockk<Context>())
                .sink(sink)
                .redactHeaders("Authorization")
                .apply(configure)
                .build()
        return InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .intercept(interceptor)
            .build()
            .also(channels::add)
    }

    private fun unary(
        channel: Channel,
        value: String,
        options: CallOptions = CallOptions.DEFAULT,
    ): StringValue = ClientCalls.blockingUnaryCall(channel, unary, options, StringValue.of(value))

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("The condition was never met")
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun <T> latchObserver(latch: CountDownLatch): StreamObserver<T> =
        object : StreamObserver<T> {
            override fun onNext(value: T) = Unit

            override fun onError(t: Throwable) = latch.countDown()

            override fun onCompleted() = latch.countDown()
        }

    private fun echoService(): ServerServiceDefinition =
        ServerServiceDefinition
            .builder(SERVICE)
            .addMethod(
                unary,
                ServerCalls.asyncUnaryCall { request, observer ->
                    when (request.value) {
                        FAIL -> {
                            val trailers = Metadata().apply { put(DEBUG_KEY, "echo-1") }
                            val status = Status.NOT_FOUND.withDescription("Nothing to echo")
                            observer.onError(status.asRuntimeException(trailers))
                        }
                        // Never answers, so that the call reaches its deadline.
                        SLOW -> Unit
                        else -> {
                            observer.onNext(StringValue.of(request.value.uppercase()))
                            observer.onCompleted()
                        }
                    }
                },
            ).addMethod(
                serverStream,
                ServerCalls.asyncServerStreamingCall { request, observer ->
                    val serverObserver = observer as ServerCallStreamObserver<StringValue>
                    for (index in 1..STREAM_SIZE) {
                        if (serverObserver.isCancelled) return@asyncServerStreamingCall
                        observer.onNext(StringValue.of("${request.value}-$index"))
                    }
                    observer.onCompleted()
                },
            ).addMethod(
                clientStream,
                ServerCalls.asyncClientStreamingCall { observer ->
                    object : StreamObserver<StringValue> {
                        private var count = 0

                        override fun onNext(value: StringValue) {
                            count++
                        }

                        override fun onError(t: Throwable) = Unit

                        override fun onCompleted() {
                            observer.onNext(Int32Value.of(count))
                            observer.onCompleted()
                        }
                    }
                },
            ).addMethod(
                bidiStream,
                ServerCalls.asyncBidiStreamingCall { observer ->
                    object : StreamObserver<StringValue> {
                        override fun onNext(value: StringValue) {
                            observer.onNext(StringValue.of(value.value.uppercase()))
                        }

                        override fun onError(t: Throwable) = Unit

                        override fun onCompleted() = observer.onCompleted()
                    }
                },
            ).build()

    private object MetadataServerInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> =
            next.startCall(
                object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    override fun sendHeaders(headers: Metadata) {
                        headers.put(Metadata.Key.of("x-server", Metadata.ASCII_STRING_MARSHALLER), "test")
                        super.sendHeaders(headers)
                    }

                    override fun close(
                        status: Status,
                        trailers: Metadata,
                    ) {
                        if (status.isOk) trailers.put(TRAILER_KEY, "done")
                        super.close(status, trailers)
                    }
                },
                headers,
            )
    }

    /** Collects what the interceptor stores, like the database would. */
    private class RecordingSink : GrpcTransactionSink {
        private val nextId = AtomicLong(1)
        private val closedCalls = LinkedBlockingQueue<HttpTransaction>()
        val inserted = CopyOnWriteArrayList<HttpTransaction>()
        val finalUpdates = CopyOnWriteArrayList<HttpTransaction>()

        override suspend fun insert(transaction: HttpTransaction): Long {
            inserted += transaction
            if (transaction.responseCode != null) closedCalls += transaction
            return nextId.getAndIncrement()
        }

        override suspend fun update(
            transaction: HttpTransaction,
            isFinal: Boolean,
        ) {
            if (transaction.responseCode != null) closedCalls += transaction
            if (isFinal) finalUpdates += transaction
        }

        fun awaitClosedCall(): HttpTransaction =
            closedCalls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw AssertionError("No call was closed")
    }

    private object FailingSink : GrpcTransactionSink {
        override suspend fun insert(transaction: HttpTransaction): Long = error("Database failure")

        override suspend fun update(
            transaction: HttpTransaction,
            isFinal: Boolean,
        ) = error("Database failure")
    }

    private companion object {
        const val SERVICE = "test.Echo"
        const val FAIL = "fail"
        const val SLOW = "slow"
        const val STREAM_SIZE = 3
        const val DEADLINE_MS = 100L
        const val TIMEOUT_SECONDS = 5L
        const val POLL_INTERVAL_MS = 10L

        val DEBUG_KEY: Metadata.Key<String> = Metadata.Key.of("x-debug", Metadata.ASCII_STRING_MARSHALLER)
        val TRAILER_KEY: Metadata.Key<String> = Metadata.Key.of("x-trailer", Metadata.ASCII_STRING_MARSHALLER)

        val unary = method(MethodType.UNARY, "Unary", StringValue.getDefaultInstance())
        val serverStream = method(MethodType.SERVER_STREAMING, "ServerStream", StringValue.getDefaultInstance())
        val clientStream = method(MethodType.CLIENT_STREAMING, "ClientStream", Int32Value.getDefaultInstance())
        val bidiStream = method(MethodType.BIDI_STREAMING, "BidiStream", StringValue.getDefaultInstance())

        fun <RespT : com.google.protobuf.MessageLite> method(
            type: MethodType,
            name: String,
            response: RespT,
        ): MethodDescriptor<StringValue, RespT> =
            MethodDescriptor
                .newBuilder(
                    ProtoLiteUtils.marshaller(StringValue.getDefaultInstance()),
                    ProtoLiteUtils.marshaller(response),
                ).setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
                .build()
    }
}
