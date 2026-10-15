package com.chuckerteam.chucker.sample.grpc

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Timestamp
import com.google.protobuf.Value
import io.grpc.ForwardingServerCall
import io.grpc.InsecureServerCredentials
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

/**
 * A gRPC server running inside the sample app, so that the sample works offline and can show
 * every call type and failure without depending on a remote service.
 */
@Suppress("TooManyFunctions", "MagicNumber")
internal object SampleGrpcServer {
    const val MISSING_RIDE_ID = "r-404"
    const val SLOW_RIDE_ID = "r-slow"

    private const val UPDATE_INTERVAL_MS = 400L
    private const val SLOW_RESPONSE_MS = 1_500L
    private const val ROUTE_POINT_DISTANCE_METERS = 85.5
    private const val SAMPLE_FARE = 4.35
    private const val SAMPLE_BATTERY = 87
    private const val SAMPLE_VEHICLE_ID = 1_234_567_890L
    private const val SAMPLE_START = 1_760_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000
    private const val MILLIS_PER_SECOND = 1_000L

    private val SERVER_REGION = Metadata.Key.of("x-server-region", Metadata.ASCII_STRING_MARSHALLER)
    private val PROCESSING_TIME = Metadata.Key.of("x-processing-time-ms", Metadata.ASCII_STRING_MARSHALLER)
    private val DEBUG_INFO = Metadata.Key.of("x-debug-info", Metadata.ASCII_STRING_MARSHALLER)

    private var server: Server? = null

    /** Starts the server if needed and returns its port. Performs network I/O. */
    @Synchronized
    fun start(): Int {
        val running = server ?: createServer().start().also { server = it }
        return running.port
    }

    private fun createServer(): Server =
        OkHttpServerBuilder
            .forPort(0, InsecureServerCredentials.create())
            .addService(ServerInterceptors.intercept(rideService(), MetadataInterceptor))
            .addService(ServerInterceptors.intercept(configService(), MetadataInterceptor))
            .build()

    private fun rideService(): ServerServiceDefinition =
        ServerServiceDefinition
            .builder(SampleGrpcServices.RIDE_SERVICE)
            .addMethod(SampleGrpcServices.getRideOnServer, ServerCalls.asyncUnaryCall(::getRide))
            .addMethod(SampleGrpcServices.watchRide, ServerCalls.asyncServerStreamingCall(::watchRide))
            .addMethod(SampleGrpcServices.uploadRoute, ServerCalls.asyncClientStreamingCall(::uploadRoute))
            .addMethod(SampleGrpcServices.chat, ServerCalls.asyncBidiStreamingCall(::chat))
            .addMethod(
                SampleGrpcServices.getRideSnapshot,
                ServerCalls.asyncUnaryCall { _, observer ->
                    observer.onNext(Ride.ADAPTER.encode(sampleRide("r-snapshot")))
                    observer.onCompleted()
                },
            ).build()

    private fun configService(): ServerServiceDefinition =
        ServerServiceDefinition
            .builder(SampleGrpcServices.CONFIG_SERVICE)
            .addMethod(
                SampleGrpcServices.getConfig,
                ServerCalls.asyncUnaryCall { _, observer ->
                    observer.onNext(sampleConfig())
                    observer.onCompleted()
                },
            ).addMethod(
                SampleGrpcServices.getServerTime,
                ServerCalls.asyncUnaryCall { _, observer ->
                    val now = System.currentTimeMillis()
                    val timestamp =
                        Timestamp
                            .newBuilder()
                            .setSeconds(now / MILLIS_PER_SECOND)
                            .setNanos(((now % MILLIS_PER_SECOND) * NANOS_PER_MILLI).toInt())
                            .build()
                    observer.onNext(timestamp)
                    observer.onCompleted()
                },
            ).build()

    private fun getRide(
        request: GetRideRequest,
        observer: StreamObserver<RideV2>,
    ) {
        when (request.ride_id) {
            MISSING_RIDE_ID -> {
                val trailers = Metadata().apply { put(DEBUG_INFO, "lookup=primary; shard=7") }
                val status = Status.NOT_FOUND.withDescription("Ride ${request.ride_id} doesn't exist")
                observer.onError(status.asRuntimeException(trailers))
            }
            else -> {
                if (request.ride_id == SLOW_RIDE_ID) Thread.sleep(SLOW_RESPONSE_MS)
                observer.onNext(sampleRideV2(request.ride_id, request.include_route))
                observer.onCompleted()
            }
        }
    }

    private fun watchRide(
        request: WatchRideRequest,
        observer: StreamObserver<Ride>,
    ) {
        val serverObserver = observer as ServerCallStreamObserver<Ride>
        val states = listOf(RideState.RIDE_STATE_RESERVED, RideState.RIDE_STATE_STARTED, RideState.RIDE_STATE_PAUSED)
        for (index in 0 until request.update_count) {
            if (serverObserver.isCancelled) return
            val isLast = index == request.update_count - 1
            val state = if (isLast) RideState.RIDE_STATE_ENDED else states[index % states.size]
            observer.onNext(sampleRide(request.ride_id, state = state, step = index))
            Thread.sleep(UPDATE_INTERVAL_MS)
        }
        observer.onCompleted()
    }

    private fun uploadRoute(observer: StreamObserver<RouteSummary>): StreamObserver<Location> =
        object : StreamObserver<Location> {
            private var count = 0

            override fun onNext(value: Location) {
                count++
            }

            override fun onError(t: Throwable) = Unit

            override fun onCompleted() {
                val summary = RouteSummary(point_count = count, distance_meters = count * ROUTE_POINT_DISTANCE_METERS)
                observer.onNext(summary)
                observer.onCompleted()
            }
        }

    private fun chat(observer: StreamObserver<ChatMessage>): StreamObserver<ChatMessage> =
        object : StreamObserver<ChatMessage> {
            override fun onNext(value: ChatMessage) {
                observer.onNext(ChatMessage(author = "support", text = "Got it: ${value.text}"))
            }

            override fun onError(t: Throwable) = Unit

            override fun onCompleted() {
                observer.onNext(ChatMessage(author = "support", text = "Have a nice ride!"))
                observer.onCompleted()
            }
        }

    private fun sampleRide(
        id: String,
        state: RideState = RideState.RIDE_STATE_STARTED,
        step: Int = 0,
    ): Ride =
        Ride(
            id = id,
            state = state,
            location = location(step),
            tags = mapOf("city" to "Amsterdam", "vehicle" to "scooter"),
            started_at_epoch_millis = SAMPLE_START,
            fare = SAMPLE_FARE + step,
            battery_percent = SAMPLE_BATTERY - step,
            wallet_id = "wallet-42",
        )

    private fun sampleRideV2(
        id: String,
        includeRoute: Boolean,
    ): RideV2 =
        RideV2(
            id = id,
            state = RideState.RIDE_STATE_STARTED,
            location = location(0),
            route = if (includeRoute) (0..2).map(::location) else emptyList(),
            tags = mapOf("city" to "Amsterdam"),
            started_at_epoch_millis = SAMPLE_START,
            fare = SAMPLE_FARE,
            receipt_signature = "signed-by-chucker".encodeUtf8(),
            promo_code = "WELCOME10",
            card = Card(brand = "VISA", last_four = "4242"),
            battery_percent = SAMPLE_BATTERY,
            vehicle_name = "Scooter #42",
            vehicle_id = SAMPLE_VEHICLE_ID,
        )

    private fun location(step: Int): Location =
        Location(latitude = 52.3676 + step * 0.001, longitude = 4.9041 + step * 0.001, accuracy_meters = 4.5f)

    private fun sampleConfig(): Struct {
        fun string(value: String) = Value.newBuilder().setStringValue(value).build()

        val flags =
            Struct
                .newBuilder()
                .putFields("new_checkout", Value.newBuilder().setBoolValue(true).build())
                .putFields("dark_mode", Value.newBuilder().setBoolValue(false).build())
        val regions = ListValue.newBuilder().addValues(string("ams")).addValues(string("ber"))
        return Struct
            .newBuilder()
            .putFields("feature_flags", Value.newBuilder().setStructValue(flags).build())
            .putFields("max_speed_kmh", Value.newBuilder().setNumberValue(25.0).build())
            .putFields("regions", Value.newBuilder().setListValue(regions).build())
            .putFields("message_of_the_day", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
            .build()
    }

    /** Adds response headers and trailers to every call, like a real server would. */
    private object MetadataInterceptor : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val startNanos = System.nanoTime()
            val forwardingCall =
                object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    override fun sendHeaders(headers: Metadata) {
                        headers.put(SERVER_REGION, "eu-west-1")
                        super.sendHeaders(headers)
                    }

                    override fun close(
                        status: Status,
                        trailers: Metadata,
                    ) {
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                        trailers.put(PROCESSING_TIME, elapsedMs.toString())
                        super.close(status, trailers)
                    }
                }
            return next.startCall(forwardingCall, headers)
        }
    }
}
