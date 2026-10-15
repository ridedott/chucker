package com.chuckerteam.chucker.internal.grpc.format

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Helpers shared by the message readers to produce the proto3 JSON representation of values.
 * See https://protobuf.dev/programming-guides/json/.
 */
internal object ProtoJson {
    /** Shown instead of the value of fields that Wire marks as `redacted`. */
    const val REDACTED = "██"

    private const val MILLIS_PER_SECOND = 1000L
    private const val NANOS_PER_MILLI = 1_000_000
    private const val NANOS_PER_MICRO = 1_000

    /** Converts a proto field name such as `user_id` to its JSON name, `userId`. */
    fun jsonName(protoName: String): String =
        buildString(protoName.length) {
            var capitalizeNext = false
            for (char in protoName) {
                when {
                    char == '_' -> capitalizeNext = true
                    capitalizeNext -> {
                        append(char.uppercaseChar())
                        capitalizeNext = false
                    }
                    else -> append(char)
                }
            }
        }

    fun number(value: Number): JsonElement =
        when (value) {
            is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
            is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())
            else -> JsonPrimitive(value)
        }

    fun unsigned(value: Long): JsonElement =
        if (value >= 0) JsonPrimitive(value) else JsonPrimitive(BigInteger(java.lang.Long.toUnsignedString(value)))

    fun unsigned(value: Int): JsonElement = JsonPrimitive(Integer.toUnsignedLong(value))

    /** Formats a `google.protobuf.Timestamp` as RFC 3339, e.g. `2026-10-15T10:42:01.120Z`. */
    fun timestamp(
        seconds: Long,
        nanos: Int,
    ): String {
        val format =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        return format.format(Date(seconds * MILLIS_PER_SECOND)) + fraction(nanos) + "Z"
    }

    /** Formats a `google.protobuf.Duration`, e.g. `1.500s`. */
    fun duration(
        seconds: Long,
        nanos: Int,
    ): String {
        val sign = if (seconds < 0 || nanos < 0) "-" else ""
        return "$sign${abs(seconds)}${fraction(abs(nanos))}s"
    }

    // Uses 0, 3, 6 or 9 fractional digits, as the proto3 JSON mapping requires.
    private fun fraction(nanos: Int): String =
        when {
            nanos == 0 -> ""
            nanos % NANOS_PER_MILLI == 0 -> ".%03d".format(Locale.US, nanos / NANOS_PER_MILLI)
            nanos % NANOS_PER_MICRO == 0 -> ".%06d".format(Locale.US, nanos / NANOS_PER_MICRO)
            else -> ".%09d".format(Locale.US, nanos)
        }
}
