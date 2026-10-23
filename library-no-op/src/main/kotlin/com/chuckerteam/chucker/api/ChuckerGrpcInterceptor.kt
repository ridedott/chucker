package com.chuckerteam.chucker.api

import android.content.Context
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.MethodDescriptor

/**
 * No-op implementation.
 */
@Suppress("UnusedPrivateMember", "UNUSED_PARAMETER")
public class ChuckerGrpcInterceptor private constructor(
    builder: Builder,
) : ClientInterceptor {
    /**
     * No-op implementation.
     */
    public constructor(context: Context) : this(Builder(context))

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> = next.newCall(method, callOptions)

    /**
     * No-op implementation.
     */
    public class Builder(
        private val context: Context,
    ) {
        public fun collector(collector: ChuckerCollector): Builder = this

        public fun maxContentLength(length: Long): Builder = this

        public fun redactHeaders(headerNames: Iterable<String>): Builder = this

        public fun redactHeaders(vararg headerNames: String): Builder = this

        public fun addMessageFormatter(formatter: GrpcMessageFormatter): Builder = this

        public fun skipMethods(vararg fullMethodNames: String): Builder = this

        public fun skipMethods(fullMethodNames: Regex): Builder = this

        public fun skipServices(vararg serviceNames: String): Builder = this

        public fun build(): ChuckerGrpcInterceptor = ChuckerGrpcInterceptor(this)
    }
}
