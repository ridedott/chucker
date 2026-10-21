package com.chuckerteam.chucker.sample.grpc

import com.google.protobuf.ListValue
import com.google.protobuf.MessageLite
import com.google.protobuf.StringValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.grpc.ForwardingServerCall
import io.grpc.InsecureServerCredentials
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
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver

/**
 * A gRPC server running inside the sample app, so that the sample works offline. Its methods are
 * declared by hand and use protobuf's well-known types as messages, so no code is generated.
 */
internal object SampleGrpcServer {
    const val MISSING_RIDE_ID = "r-404"

    val getRide = method(MethodType.UNARY, "GetRide", StringValue.getDefaultInstance(), Struct.getDefaultInstance())
    val watchRide =
        method(MethodType.SERVER_STREAMING, "WatchRide", StringValue.getDefaultInstance(), Struct.getDefaultInstance())
    val uploadRoute =
        method(MethodType.CLIENT_STREAMING, "UploadRoute", Struct.getDefaultInstance(), Struct.getDefaultInstance())
    val chat =
        method(MethodType.BIDI_STREAMING, "Chat", StringValue.getDefaultInstance(), StringValue.getDefaultInstance())

    private const val SERVICE = "chucker.sample.ride.v1.RideService"

    // Long enough to watch the stream live in Chucker.
    private const val RIDE_UPDATES = 30
    private const val UPDATE_INTERVAL_MS = 400L
    private const val SAMPLE_FARE = 4.35

    private val SERVER_REGION = Metadata.Key.of("x-server-region", Metadata.ASCII_STRING_MARSHALLER)
    private val DEBUG_INFO = Metadata.Key.of("x-debug-info", Metadata.ASCII_STRING_MARSHALLER)

    private var server: Server? = null

    /** Starts the server if needed and returns its port. Performs network I/O. */
    @Synchronized
    fun start(): Int {
        val running = server ?: createServer().start().also { server = it }
        return running.port
    }

    private fun createServer(): Server {
        val service =
            ServerServiceDefinition
                .builder(SERVICE)
                .addMethod(getRide, ServerCalls.asyncUnaryCall(::getRide))
                .addMethod(watchRide, ServerCalls.asyncServerStreamingCall(::watchRide))
                .addMethod(uploadRoute, ServerCalls.asyncClientStreamingCall(::uploadRoute))
                .addMethod(chat, ServerCalls.asyncBidiStreamingCall(::chat))
                .build()
        return OkHttpServerBuilder
            .forPort(0, InsecureServerCredentials.create())
            .addService(ServerInterceptors.intercept(service, MetadataInterceptor))
            .build()
    }

    private fun getRide(
        request: StringValue,
        observer: StreamObserver<Struct>,
    ) {
        if (request.value == MISSING_RIDE_ID) {
            val trailers = Metadata().apply { put(DEBUG_INFO, "lookup=primary; shard=7") }
            val status = Status.NOT_FOUND.withDescription("Ride ${request.value} doesn't exist")
            observer.onError(status.asRuntimeException(trailers))
            return
        }
        observer.onNext(ride(request.value, step = 0, state = "STARTED"))
        observer.onCompleted()
    }

    private fun watchRide(
        request: StringValue,
        observer: StreamObserver<Struct>,
    ) {
        repeat(RIDE_UPDATES) { step ->
            val state = if (step == RIDE_UPDATES - 1) "ENDED" else "STARTED"
            observer.onNext(ride(request.value, step, state))
            Thread.sleep(UPDATE_INTERVAL_MS)
        }
        observer.onCompleted()
    }

    private fun uploadRoute(observer: StreamObserver<Struct>): StreamObserver<Struct> =
        object : StreamObserver<Struct> {
            private var points = 0

            override fun onNext(value: Struct) {
                points++
            }

            override fun onError(t: Throwable) = Unit

            override fun onCompleted() {
                observer.onNext(struct("pointCount" to points))
                observer.onCompleted()
            }
        }

    private fun chat(observer: StreamObserver<StringValue>): StreamObserver<StringValue> =
        object : StreamObserver<StringValue> {
            override fun onNext(value: StringValue) = observer.onNext(StringValue.of("Got it: ${value.value}"))

            override fun onError(t: Throwable) = Unit

            override fun onCompleted() = observer.onCompleted()
        }

    private fun ride(
        id: String,
        step: Int,
        state: String,
    ): Struct =
        struct(
            "id" to id,
            "state" to state,
            "location" to location(step),
            "fare" to SAMPLE_FARE + step,
            "tags" to listOf("amsterdam", "scooter"),
        )

    /** Adds response headers and trailers to every call, like a real server would. */
    private object MetadataInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val forwardingCall =
                object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    override fun sendHeaders(headers: Metadata) {
                        headers.put(SERVER_REGION, "eu-west-1")
                        super.sendHeaders(headers)
                    }
                }
            return next.startCall(forwardingCall, headers)
        }
    }

    private fun <ReqT : MessageLite, RespT : MessageLite> method(
        type: MethodType,
        name: String,
        request: ReqT,
        response: RespT,
    ): MethodDescriptor<ReqT, RespT> =
        MethodDescriptor
            .newBuilder(ProtoLiteUtils.marshaller(request), ProtoLiteUtils.marshaller(response))
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
            .build()
}

@Suppress("MagicNumber") // Coordinates in Amsterdam, a little further north at each step.
internal fun location(step: Int): Struct = struct("latitude" to 52.3676 + step * 0.001, "longitude" to 4.9041)

/** Builds a `google.protobuf.Struct`, which Chucker shows as a plain JSON object. */
internal fun struct(vararg fields: Pair<String, Any>): Struct =
    Struct
        .newBuilder()
        .apply { fields.forEach { (name, value) -> putFields(name, value(value)) } }
        .build()

private fun value(value: Any): Value =
    when (value) {
        is String -> Value.newBuilder().setStringValue(value).build()
        is Number -> Value.newBuilder().setNumberValue(value.toDouble()).build()
        is Boolean -> Value.newBuilder().setBoolValue(value).build()
        is Struct -> Value.newBuilder().setStructValue(value).build()
        is List<*> -> {
            val values = value.map { value(checkNotNull(it)) }
            Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(values)).build()
        }
        else -> error("Unsupported value: $value")
    }
