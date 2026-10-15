package com.chuckerteam.chucker.internal.grpc.format

import com.chuckerteam.chucker.api.GrpcMessageFormatter
import com.chuckerteam.chucker.internal.data.entity.GrpcEvent.BodyFormat
import com.chuckerteam.chucker.internal.support.Logger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal class RenderedMessage(
    val text: String,
    val format: BodyFormat,
)

/**
 * Turns a gRPC message into text, trying in order:
 * 1. the formatters supplied by the app;
 * 2. the message's generated class, for Wire and protobuf messages;
 * 3. the serialized bytes, decoded without a schema;
 * 4. `toString()`.
 */
internal class GrpcMessageRenderer(
    private val formatters: List<GrpcMessageFormatter>,
) {
    fun render(
        fullMethodName: String,
        message: Any,
        serializedSize: Long?,
        serialize: () -> ByteArray?,
    ): RenderedMessage =
        renderWithFormatters(fullMethodName, message)
            ?: renderWithSchema(message, serializedSize)
            ?: renderWithoutSchema(serialize)
            ?: RenderedMessage(message.toString(), BodyFormat.TEXT)

    private fun renderWithFormatters(
        fullMethodName: String,
        message: Any,
    ): RenderedMessage? {
        for (formatter in formatters) {
            val text =
                try {
                    formatter.format(fullMethodName, message)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Logger.error("GrpcMessageFormatter failed to format a message of $fullMethodName", e)
                    null
                } ?: continue
            val format = if (isJson(text)) BodyFormat.CUSTOM_JSON else BodyFormat.TEXT
            return RenderedMessage(text, format)
        }
        return null
    }

    private fun renderWithSchema(
        message: Any,
        serializedSize: Long?,
    ): RenderedMessage? {
        val json =
            try {
                WireMessageReader.read(message) ?: ProtobufMessageReader.read(message)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Logger.warn("Couldn't read ${message.javaClass.name} with its generated class", e)
                null
            } ?: return null
        // When R8 removed the accessors nothing could be read, so the bytes are decoded instead.
        val isEmpty = json is JsonObject && json.size() == 0
        if (isEmpty && serializedSize != null && serializedSize > 0) return null
        return RenderedMessage(json.toString(), BodyFormat.SCHEMA_JSON)
    }

    private fun renderWithoutSchema(serialize: () -> ByteArray?): RenderedMessage? {
        val bytes = runCatching(serialize).getOrNull() ?: return null
        val json = RawProtoDecoder.decode(bytes) ?: return null
        return RenderedMessage(json.toString(), BodyFormat.RAW_JSON)
    }

    private fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        return runCatching { JsonParser.parseString(trimmed) }.getOrNull()?.isJsonContainer() == true
    }

    private fun JsonElement.isJsonContainer(): Boolean = isJsonObject || isJsonArray
}
