package com.chuckerteam.chucker.api

/**
 * No-op declaration
 */
public fun interface GrpcMessageFormatter {
    public fun format(
        fullMethodName: String,
        message: Any,
    ): String?
}
