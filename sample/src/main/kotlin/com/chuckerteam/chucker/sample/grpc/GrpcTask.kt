package com.chuckerteam.chucker.sample.grpc

import android.content.Context
import android.util.Log
import com.chuckerteam.chucker.api.ChuckerGrpcInterceptor
import com.google.protobuf.StringValue
import com.google.protobuf.Struct
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientInterceptors
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

/** Makes a gRPC call of every type to [SampleGrpcServer], plus one that fails, to inspect in Chucker. */
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
            val channel =
                OkHttpChannelBuilder
                    .forAddress("localhost", SampleGrpcServer.start())
                    .usePlaintext()
                    .intercept(interceptor)
                    .build()
            val withMetadata =
                ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata))
            try {
                supervisorScope {
                    listOf<suspend () -> Unit>(
                        { getRide(withMetadata, "r-1001") },
                        { getRide(withMetadata, SampleGrpcServer.MISSING_RIDE_ID) },
                        { watchRide(channel) },
                        { uploadRoute(channel) },
                        { chat(channel) },
                    ).forEach { call ->
                        launch { runCatching { call() }.onFailure { Log.d(TAG, "gRPC call failed", it) } }
                    }
                }
            } finally {
                channel.shutdown()
            }
        }
    }

    private fun getRide(
        channel: Channel,
        rideId: String,
    ) {
        ClientCalls.blockingUnaryCall(channel, SampleGrpcServer.getRide, CallOptions.DEFAULT, StringValue.of(rideId))
    }

    private fun watchRide(channel: Channel) {
        val request = StringValue.of("r-1001")
        ClientCalls
            .blockingServerStreamingCall(channel, SampleGrpcServer.watchRide, CallOptions.DEFAULT, request)
            .forEach { _ -> }
    }

    private suspend fun uploadRoute(channel: Channel) {
        val done = CompletableDeferred<Unit>()
        val call = channel.newCall(SampleGrpcServer.uploadRoute, CallOptions.DEFAULT)
        val requests = ClientCalls.asyncClientStreamingCall(call, observer<Struct>(done))
        repeat(ROUTE_POINTS) { step ->
            requests.onNext(location(step))
            delay(MESSAGE_INTERVAL_MS)
        }
        requests.onCompleted()
        done.await()
    }

    private suspend fun chat(channel: Channel) {
        val done = CompletableDeferred<Unit>()
        val call = channel.newCall(SampleGrpcServer.chat, CallOptions.DEFAULT)
        val requests = ClientCalls.asyncBidiStreamingCall(call, observer<StringValue>(done))
        listOf("Hi, my scooter stopped", "It's at Dam Square", "Thanks!").forEach { text ->
            requests.onNext(StringValue.of(text))
            delay(CHAT_INTERVAL_MS)
        }
        requests.onCompleted()
        done.await()
    }

    private fun <T> observer(done: CompletableDeferred<Unit>): StreamObserver<T> =
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
        const val ROUTE_POINTS = 8
        const val MESSAGE_INTERVAL_MS = 150L
        const val CHAT_INTERVAL_MS = 2_500L

        val metadata =
            Metadata().apply {
                put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer secret-token")
                put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), "4f8b2c1e")
            }
    }
}
