package com.chuckerteam.chucker.internal.grpc.format

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/**
 * Decodes protobuf wire bytes without a schema, like `protoc --decode_raw`.
 *
 * Fields are keyed by `#<field number>`, and fields that repeat become arrays. Without a schema
 * some values are ambiguous, so the decoder guesses:
 * - length-delimited values are shown as text when they're printable UTF-8, as a nested message
 *   when they parse as one, and as base64 otherwise;
 * - fixed-width values are shown as floating point numbers when that gives a plausible value.
 */
internal object RawProtoDecoder {
    private const val MAX_DEPTH = 32
    private const val MAX_FIELD_NUMBER = (1 shl 29) - 1
    private const val MAX_VARINT_BYTES = 10
    private const val VARINT_PAYLOAD_BITS = 7
    private const val VARINT_PAYLOAD_MASK = 0x7F
    private const val VARINT_CONTINUATION = 0x80
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val TAG_TYPE_BITS = 3
    private const val TAG_TYPE_MASK = 7L

    private const val WIRE_TYPE_VARINT = 0
    private const val WIRE_TYPE_I64 = 1
    private const val WIRE_TYPE_LEN = 2
    private const val WIRE_TYPE_I32 = 5

    private const val I64_SIZE = 8
    private const val I32_SIZE = 4

    // Bounds outside which a fixed-width value is more likely an integer than a float.
    private const val MIN_PLAUSIBLE_FLOAT = 1e-6
    private const val MAX_PLAUSIBLE_DOUBLE = 1e15
    private const val MAX_PLAUSIBLE_FLOAT = 1e9

    /** Returns the decoded message, or null when [bytes] aren't a valid protobuf message. */
    fun decode(bytes: ByteArray): JsonObject? = decodeFields(bytes)?.let(::toJsonObject)

    /** Returns the fields of [bytes] in wire order, or null when they aren't a valid message. */
    fun decodeFields(bytes: ByteArray): List<Pair<Int, JsonElement>>? =
        try {
            Reader(bytes, 0, bytes.size).readFields(depth = 0)
        } catch (_: InvalidMessageException) {
            null
        }

    /** Groups [fields] by number, keeping the order of their first occurrence. */
    fun toJsonObject(fields: List<Pair<Int, JsonElement>>): JsonObject {
        val grouped = LinkedHashMap<Int, MutableList<JsonElement>>()
        for ((number, value) in fields) {
            grouped.getOrPut(number) { mutableListOf() }.add(value)
        }
        return JsonObject().apply {
            for ((number, values) in grouped) {
                val value = values.singleOrNull() ?: JsonArray().apply { values.forEach(::add) }
                add(fieldKey(number), value)
            }
        }
    }

    fun fieldKey(number: Int): String = "#$number"

    private class InvalidMessageException : Exception()

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int,
        private val end: Int,
    ) {
        fun readFields(depth: Int): List<Pair<Int, JsonElement>> {
            val fields = mutableListOf<Pair<Int, JsonElement>>()
            while (position < end) {
                val tag = readVarint()
                val number = tag ushr TAG_TYPE_BITS
                if (number <= 0 || number > MAX_FIELD_NUMBER) throw InvalidMessageException()
                val value =
                    when ((tag and TAG_TYPE_MASK).toInt()) {
                        WIRE_TYPE_VARINT -> unsignedNumber(readVarint())
                        WIRE_TYPE_I64 -> fixed64(readLittleEndian(I64_SIZE))
                        WIRE_TYPE_LEN -> readLengthDelimited(depth)
                        WIRE_TYPE_I32 -> fixed32(readLittleEndian(I32_SIZE).toInt())
                        // Groups are deprecated and wire types 6 and 7 don't exist.
                        else -> throw InvalidMessageException()
                    }
                fields += number.toInt() to value
            }
            return fields
        }

        private fun readVarint(): Long {
            var result = 0L
            for (index in 0 until MAX_VARINT_BYTES) {
                if (position >= end) throw InvalidMessageException()
                val byte = bytes[position++].toInt() and BYTE_MASK
                result = result or ((byte and VARINT_PAYLOAD_MASK).toLong() shl (VARINT_PAYLOAD_BITS * index))
                if (byte and VARINT_CONTINUATION == 0) return result
            }
            throw InvalidMessageException()
        }

        private fun readLittleEndian(size: Int): Long {
            if (end - position < size) throw InvalidMessageException()
            var result = 0L
            for (index in 0 until size) {
                result = result or ((bytes[position++].toLong() and BYTE_MASK.toLong()) shl (BITS_PER_BYTE * index))
            }
            return result
        }

        private fun readLengthDelimited(depth: Int): JsonElement {
            val length = readVarint()
            if (length < 0 || length > end - position) throw InvalidMessageException()
            val start = position
            position += length.toInt()
            return decodeLengthDelimited(start, position, depth)
        }

        private fun decodeLengthDelimited(
            start: Int,
            end: Int,
            depth: Int,
        ): JsonElement {
            if (start == end) return JsonPrimitive("")
            val text = decodeUtf8(start, end)
            if (text != null && text.isPrintable()) return JsonPrimitive(text)
            if (depth < MAX_DEPTH) {
                val nested =
                    try {
                        Reader(bytes, start, end).readFields(depth + 1)
                    } catch (_: InvalidMessageException) {
                        null
                    }
                if (nested != null) return toJsonObject(nested)
            }
            return JsonPrimitive(bytes.toByteString(start, end - start).base64())
        }

        private fun decodeUtf8(
            start: Int,
            end: Int,
        ): String? =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, end - start))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
    }

    private fun String.isPrintable(): Boolean = none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }

    private fun unsignedNumber(value: Long): JsonPrimitive =
        if (value >= 0) {
            JsonPrimitive(value)
        } else {
            JsonPrimitive(BigInteger(java.lang.Long.toUnsignedString(value)))
        }

    private fun fixed64(bits: Long): JsonPrimitive {
        val asDouble = Double.fromBits(bits)
        return if (asDouble.isPlausible(MAX_PLAUSIBLE_DOUBLE)) JsonPrimitive(asDouble) else unsignedNumber(bits)
    }

    private fun fixed32(bits: Int): JsonPrimitive {
        val asFloat = Float.fromBits(bits)
        return if (asFloat.toDouble().isPlausible(MAX_PLAUSIBLE_FLOAT)) {
            JsonPrimitive(asFloat)
        } else {
            JsonPrimitive(Integer.toUnsignedLong(bits))
        }
    }

    private fun Double.isPlausible(max: Double): Boolean = isFinite() && abs(this) in MIN_PLAUSIBLE_FLOAT..max
}
