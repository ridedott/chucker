package com.chuckerteam.chucker.api

/**
 * Converts gRPC messages to the text shown in the Chucker UI.
 *
 * Chucker already shows protobuf (`protobuf-java` and `protobuf-javalite`) messages as JSON, so a
 * formatter is only needed for other message types, such as Wire's, or to customise the output.
 * Formatters added with [ChuckerGrpcInterceptor.Builder.addMessageFormatter] run before the
 * built-in formatting, in the order they were added.
 *
 * Returning JSON is recommended, as Chucker highlights it.
 */
public fun interface GrpcMessageFormatter {
    /**
     * Returns a text representation of [message], or `null` to let the next formatter handle it.
     *
     * @param fullMethodName the gRPC method, e.g. `ride.v1.RideService/WatchRide`.
     * @param message a request or response message of that method.
     */
    public fun format(
        fullMethodName: String,
        message: Any,
    ): String?
}
