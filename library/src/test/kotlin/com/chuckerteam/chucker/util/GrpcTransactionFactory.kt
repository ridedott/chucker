package com.chuckerteam.chucker.util

import com.chuckerteam.chucker.internal.data.entity.GrpcEvent
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.internal.data.entity.HttpHeader
import com.chuckerteam.chucker.internal.data.entity.HttpTransaction
import com.chuckerteam.chucker.internal.support.JsonConverter

internal object GrpcTransactionFactory {
    /** A completed server streaming call that sent one message and received two. */
    fun createTransaction(
        scheme: String? = "https",
        authority: String = "api.example.com:443",
        requestFormat: BodyFormat = BodyFormat.SCHEMA_JSON,
    ): HttpTransaction =
        HttpTransaction().apply {
            isGrpc = true
            requestDate = REQUEST_DATE
            responseDate = REQUEST_DATE + 12
            tookMs = 250
            protocol = "gRPC"
            method = "POST"
            path = "/ride.v1.RideService/WatchRide"
            host = "api.example.com"
            this.scheme = scheme
            url = if (scheme == null) "$authority$path" else "$scheme://$authority$path"
            grpcMethodType = "SERVER_STREAMING"
            requestContentType = "application/grpc"
            setRequestHeaders(listOf(HttpHeader("x-request-id", "abc"), HttpHeader("note", "it's here")))
            setResponseHeaders(listOf(HttpHeader("content-type", "application/grpc")))
            setResponseTrailers(listOf(HttpHeader("x-processing-ms", "12")))
            responseCode = 14
            responseMessage = "UNAVAILABLE"
            grpcStatusDescription = "Connection reset"
            requestMessageCount = 1
            responseMessageCount = 2
            requestPayloadSize = 9
            responsePayloadSize = 40
            grpcEvents =
                JsonConverter.compactInstance.toJson(
                    listOf(
                        GrpcEvent(GrpcEvent.Type.STARTED, 0),
                        GrpcEvent(GrpcEvent.Type.MESSAGE_SENT, 1, 1, 9, """{"rideId":"r-1"}""", requestFormat),
                        GrpcEvent(GrpcEvent.Type.HALF_CLOSED, 1),
                        GrpcEvent(GrpcEvent.Type.HEADERS_RECEIVED, 12),
                        received(30, 1, """{"state":"STARTED"}"""),
                        received(1_200, 2, """{"state":"ENDED"}"""),
                        GrpcEvent(GrpcEvent.Type.CLOSED, 250, detail = "UNAVAILABLE: Connection reset"),
                    ),
                )
        }

    private fun received(
        time: Long,
        index: Int,
        body: String,
    ) = GrpcEvent(GrpcEvent.Type.MESSAGE_RECEIVED, time, index, 20, body, BodyFormat.SCHEMA_JSON)

    private const val REQUEST_DATE = 1_760_000_000_000L
}
