package com.chuckerteam.chucker.internal.data.entity

import com.google.gson.annotations.SerializedName

/**
 * A single step in the lifetime of a gRPC call, persisted as part of [HttpTransaction.grpcEvents].
 *
 * Events are stored in the order in which they happened, and [timeOffsetMs] is relative to the
 * moment the call was started. Message events carry the rendered message in [body].
 */
internal data class GrpcEvent(
    @SerializedName("type") val type: Type,
    @SerializedName("time") val timeOffsetMs: Long,
    @SerializedName("index") val index: Int? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("format") val format: BodyFormat? = null,
    @SerializedName("truncated") val isTruncated: Boolean = false,
    @SerializedName("detail") val detail: String? = null,
) {
    val isMessage: Boolean
        get() = type == Type.MESSAGE_SENT || type == Type.MESSAGE_RECEIVED

    enum class Type {
        @SerializedName("started")
        STARTED,

        @SerializedName("message_sent")
        MESSAGE_SENT,

        @SerializedName("half_closed")
        HALF_CLOSED,

        @SerializedName("headers_received")
        HEADERS_RECEIVED,

        @SerializedName("message_received")
        MESSAGE_RECEIVED,

        @SerializedName("cancelled")
        CANCELLED,

        @SerializedName("closed")
        CLOSED,
    }

    enum class BodyFormat {
        /** JSON built from the message's generated class, with proto field names. */
        @SerializedName("schema_json")
        SCHEMA_JSON,

        /** JSON decoded from the wire bytes without a schema, keyed by field numbers. */
        @SerializedName("raw_json")
        RAW_JSON,

        /** JSON returned by a user supplied formatter. */
        @SerializedName("custom_json")
        CUSTOM_JSON,

        /** Plain text, either from a user supplied formatter or from `toString()`. */
        @SerializedName("text")
        TEXT,

        /** The message was not stored because the content limit was reached. */
        @SerializedName("omitted")
        OMITTED,
        ;

        val isJson: Boolean
            get() = this == SCHEMA_JSON || this == RAW_JSON || this == CUSTOM_JSON
    }
}
