package com.chuckerteam.chucker.sample.grpc

import com.google.protobuf.Empty
import com.google.protobuf.StringValue
import com.google.protobuf.Struct
import com.google.protobuf.Timestamp
import com.squareup.wire.ProtoAdapter
import io.grpc.MethodDescriptor
import io.grpc.protobuf.lite.ProtoLiteUtils
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The methods of the sample gRPC services, declared by hand so that the sample needs no gRPC code
 * generation. They use three kinds of messages, to show how Chucker displays each of them:
 * - Wire messages, generated from `ride.proto`;
 * - `protobuf-javalite` messages, here the well-known types;
 * - raw bytes, which Chucker can only decode without a schema.
 */
internal object SampleGrpcServices {
    const val RIDE_SERVICE = "chucker.sample.ride.v1.RideService"
    const val CONFIG_SERVICE = "chucker.sample.config.v1.ConfigService"

    val getRide = rideMethod(MethodDescriptor.MethodType.UNARY, "GetRide", GetRideRequest.ADAPTER, Ride.ADAPTER)

    // The server answers GetRide with a newer version of Ride, which has fields the client doesn't know.
    val getRideOnServer =
        rideMethod(MethodDescriptor.MethodType.UNARY, "GetRide", GetRideRequest.ADAPTER, RideV2.ADAPTER)

    val watchRide =
        rideMethod(MethodDescriptor.MethodType.SERVER_STREAMING, "WatchRide", WatchRideRequest.ADAPTER, Ride.ADAPTER)

    val uploadRoute =
        rideMethod(MethodDescriptor.MethodType.CLIENT_STREAMING, "UploadRoute", Location.ADAPTER, RouteSummary.ADAPTER)

    val chat = rideMethod(MethodDescriptor.MethodType.BIDI_STREAMING, "Chat", ChatMessage.ADAPTER, ChatMessage.ADAPTER)

    val getRideSnapshot: MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor
            .newBuilder(BytesMarshaller, BytesMarshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(RIDE_SERVICE, "GetRideSnapshot"))
            .build()

    val getConfig: MethodDescriptor<StringValue, Struct> =
        MethodDescriptor
            .newBuilder(
                ProtoLiteUtils.marshaller(StringValue.getDefaultInstance()),
                ProtoLiteUtils.marshaller(Struct.getDefaultInstance()),
            ).setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(CONFIG_SERVICE, "GetConfig"))
            .build()

    val getServerTime: MethodDescriptor<Empty, Timestamp> =
        MethodDescriptor
            .newBuilder(
                ProtoLiteUtils.marshaller(Empty.getDefaultInstance()),
                ProtoLiteUtils.marshaller(Timestamp.getDefaultInstance()),
            ).setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(CONFIG_SERVICE, "GetServerTime"))
            .build()

    private fun <ReqT : Any, RespT : Any> rideMethod(
        type: MethodDescriptor.MethodType,
        name: String,
        requestAdapter: ProtoAdapter<ReqT>,
        responseAdapter: ProtoAdapter<RespT>,
    ): MethodDescriptor<ReqT, RespT> =
        MethodDescriptor
            .newBuilder(WireMarshaller(requestAdapter), WireMarshaller(responseAdapter))
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(RIDE_SERVICE, name))
            .build()

    private class WireMarshaller<T : Any>(
        private val adapter: ProtoAdapter<T>,
    ) : MethodDescriptor.Marshaller<T> {
        override fun stream(value: T): InputStream = ByteArrayInputStream(adapter.encode(value))

        override fun parse(stream: InputStream): T = adapter.decode(stream)
    }

    private object BytesMarshaller : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

        override fun parse(stream: InputStream): ByteArray = stream.readBytes()
    }
}
