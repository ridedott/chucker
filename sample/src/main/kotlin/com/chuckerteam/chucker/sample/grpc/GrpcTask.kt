package com.chuckerteam.chucker.sample.grpc

import android.content.Context
import android.util.Log
import com.chuckerteam.chucker.api.ChuckerGrpcInterceptor
import com.google.protobuf.Empty
import com.google.protobuf.StringValue
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

/**
 * Makes gRPC calls of every type to [SampleGrpcServer], including calls that fail, time out or
 * are cancelled, so that each case can be inspected in Chucker.
 */
@Suppress("TooManyFunctions", "MagicNumber")
class GrpcTask(
    private val context: Context,
) {
    private val interceptor by lazy {
        ChuckerGrpcInterceptor
            .Builder(context)
            .redactHeaders("authorization")
            .build()
    }

    fun run(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val port = SampleGrpcServer.start()
            val channel = createChannel(port)
            try {
                supervisorScope {
                    scenarios.forEach { scenario ->
                        launch { runCatching { scenario(channel) }.onFailure { Log.d(TAG, "gRPC call failed", it) } }
                    }
                }
            } finally {
                channel.shutdown()
            }
        }
    }

    private fun createChannel(port: Int): ManagedChannel =
        OkHttpChannelBuilder
            .forAddress("localhost", port)
            .usePlaintext()
            .intercept(interceptor)
            .build()

    private val scenarios: List<suspend (Channel) -> Unit> =
        listOf(
            { getRide(it) },
            { getMissingRide(it) },
            { getSlowRide(it) },
            { watchRide(it) },
            { watchRideThenCancel(it) },
            { uploadRoute(it) },
            { chat(it) },
            { getConfig(it) },
            { getServerTime(it) },
            { getRideSnapshot(it) },
            { getRideFromUnreachableServer() },
        )

    private fun getRide(channel: Channel) {
        val request = GetRideRequest(ride_id = "r-1001", include_route = true)
        ClientCalls.blockingUnaryCall(withMetadata(channel), SampleGrpcServices.getRide, CallOptions.DEFAULT, request)
    }

    private fun getMissingRide(channel: Channel) {
        val request = GetRideRequest(ride_id = SampleGrpcServer.MISSING_RIDE_ID)
        ClientCalls.blockingUnaryCall(withMetadata(channel), SampleGrpcServices.getRide, CallOptions.DEFAULT, request)
    }

    private fun getSlowRide(channel: Channel) {
        val options = CallOptions.DEFAULT.withDeadlineAfter(SHORT_DEADLINE_MS, TimeUnit.MILLISECONDS)
        val request = GetRideRequest(ride_id = SampleGrpcServer.SLOW_RIDE_ID)
        ClientCalls.blockingUnaryCall(channel, SampleGrpcServices.getRide, options, request)
    }

    private fun watchRide(channel: Channel) {
        // Long enough to watch the stream live in Chucker.
        val request = WatchRideRequest(ride_id = "r-1001", update_count = LIVE_RIDE_UPDATES)
        ClientCalls
            .blockingServerStreamingCall(channel, SampleGrpcServices.watchRide, CallOptions.DEFAULT, request)
            .forEach { _ -> }
    }

    // Like a screen that stops listening when the user leaves it.
    private suspend fun watchRideThenCancel(channel: Channel) {
        val call = channel.newCall(SampleGrpcServices.watchRide, CallOptions.DEFAULT)
        val done = CompletableDeferred<Unit>()
        val request = WatchRideRequest(ride_id = "r-2002", update_count = RIDE_UPDATES)
        var received = 0
        ClientCalls.asyncServerStreamingCall(
            call,
            request,
            object : StreamObserver<Ride> {
                override fun onNext(value: Ride) {
                    if (++received == UPDATES_BEFORE_CANCEL) call.cancel("The user closed the ride screen", null)
                }

                override fun onError(t: Throwable) {
                    done.complete(Unit)
                }

                override fun onCompleted() {
                    done.complete(Unit)
                }
            },
        )
        done.await()
    }

    private suspend fun uploadRoute(channel: Channel) {
        val done = CompletableDeferred<Unit>()
        val requests =
            ClientCalls.asyncClientStreamingCall(
                channel.newCall(SampleGrpcServices.uploadRoute, CallOptions.DEFAULT),
                completingObserver<RouteSummary>(done),
            )
        repeat(ROUTE_POINTS) { index ->
            requests.onNext(Location(latitude = 52.37 + index * 0.0005, longitude = 4.89, accuracy_meters = 3f))
            delay(MESSAGE_INTERVAL_MS)
        }
        requests.onCompleted()
        done.await()
    }

    private suspend fun chat(channel: Channel) {
        val done = CompletableDeferred<Unit>()
        val requests =
            ClientCalls.asyncBidiStreamingCall(
                channel.newCall(SampleGrpcServices.chat, CallOptions.DEFAULT),
                completingObserver<ChatMessage>(done),
            )
        listOf("Hi, my scooter stopped", "It's at Dam Square", "The screen says E42", "Thanks!").forEach { text ->
            requests.onNext(ChatMessage(author = "rider", text = text))
            delay(CHAT_INTERVAL_MS)
        }
        requests.onCompleted()
        done.await()
    }

    private fun getConfig(channel: Channel) {
        val request = StringValue.of("android")
        ClientCalls.blockingUnaryCall(channel, SampleGrpcServices.getConfig, CallOptions.DEFAULT, request)
    }

    private fun getServerTime(channel: Channel) {
        val request = Empty.getDefaultInstance()
        ClientCalls.blockingUnaryCall(channel, SampleGrpcServices.getServerTime, CallOptions.DEFAULT, request)
    }

    // Without a generated class, Chucker decodes the bytes without a schema.
    private fun getRideSnapshot(channel: Channel) {
        val request = GetRideRequest.ADAPTER.encode(GetRideRequest(ride_id = "r-snapshot"))
        ClientCalls.blockingUnaryCall(channel, SampleGrpcServices.getRideSnapshot, CallOptions.DEFAULT, request)
    }

    private fun getRideFromUnreachableServer() {
        val channel = createChannel(UNREACHABLE_PORT)
        try {
            val request = GetRideRequest(ride_id = "r-1001")
            ClientCalls.blockingUnaryCall(channel, SampleGrpcServices.getRide, CallOptions.DEFAULT, request)
        } finally {
            channel.shutdown()
        }
    }

    private fun withMetadata(channel: Channel): Channel {
        val metadata =
            Metadata().apply {
                put(AUTHORIZATION, "Bearer secret-token")
                put(REQUEST_ID, "4f8b2c1e")
                put(TRACE_CONTEXT, "trace-7b3f".encodeUtf8().toByteArray())
            }
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    private fun <T> completingObserver(done: CompletableDeferred<Unit>): StreamObserver<T> =
        object : StreamObserver<T> {
            override fun onNext(value: T) = Unit

            override fun onError(t: Throwable) {
                done.complete(Unit)
            }

            override fun onCompleted() {
                done.complete(Unit)
            }
        }

    private companion object {
        const val TAG = "GrpcTask"
        const val SHORT_DEADLINE_MS = 300L
        const val RIDE_UPDATES = 6
        const val LIVE_RIDE_UPDATES = 30
        const val UPDATES_BEFORE_CANCEL = 2
        const val ROUTE_POINTS = 8
        const val MESSAGE_INTERVAL_MS = 150L
        const val CHAT_INTERVAL_MS = 2_500L

        // Nothing listens on the discard port of the device, so the connection is refused.
        const val UNREACHABLE_PORT = 9

        val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        val REQUEST_ID: Metadata.Key<String> = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)
        val TRACE_CONTEXT: Metadata.Key<ByteArray> =
            Metadata.Key.of("x-trace-context-bin", Metadata.BINARY_BYTE_MARSHALLER)
    }
}
